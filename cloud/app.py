from __future__ import annotations
import base64
import hashlib
import html
import io
import json
import logging
import shutil
import tempfile
import time
import zipfile

from contextlib import asynccontextmanager
from datetime import datetime, timezone
from functools import partial
from pathlib import Path
from typing import Any, Literal
from urllib.parse import urlencode

import anyio
import qrcode
from fastapi import FastAPI, Header, Query, Request
from fastapi.responses import FileResponse, HTMLResponse, JSONResponse
from pydantic import BaseModel
from starlette.background import BackgroundTask

from cloud.config import Settings
from cloud.debug_monitor import DEBUG_MONITOR_HTML
from cloud.errors import CloudError
from cloud.service import AnalysisService, decode_image


logger = logging.getLogger(__name__)


class CreateSessionRequest(BaseModel):
    rule_profile_id: str = "guangdong_mvp_v1"
    recording_mode: Literal["normal", "regression"] = "normal"


def build_debug_connection_payload(base_url: str) -> str:
    return "rayneo-assist://configure?" + urlencode(
        {
            "version": "1",
            "scene": "mahjong",
            "base_url": base_url,
        }
    )


def build_debug_connection_html(base_url: str, ready: bool, errors: list[str]) -> str:
    payload = build_debug_connection_payload(base_url)
    qr_image = qrcode.make(payload)
    image_buffer = io.BytesIO()
    qr_image.save(image_buffer, format="PNG")
    qr_data = base64.b64encode(image_buffer.getvalue()).decode("ascii")
    status = "Ready" if ready else "Not Ready"
    status_color = "#2bd96b" if ready else "#ff695e"
    error_text = "" if not errors else '<p class="errors">' + html.escape("；".join(errors)) + "</p>"
    safe_base_url = html.escape(base_url)
    safe_payload = html.escape(payload)
    return f"""<!doctype html>
<html lang="zh-CN">
<head>
  <meta charset="utf-8">
  <meta name="viewport" content="width=device-width, initial-scale=1">
  <title>AR 通用客户端调试连接</title>
  <style>
    html, body {{ width: 100%; height: 100%; }}
    body {{ margin: 0; background: #0b0f14; color: #e8edf2; font-family: sans-serif; }}
    main {{ min-height: 100vh; box-sizing: border-box; padding: 16px; text-align: center;
      display: flex; flex-direction: column; align-items: center; justify-content: center; gap: 10px; }}
    h1 {{ margin: 0; font-size: clamp(18px, 2.4vw, 30px); }}
    p {{ margin: 0; }}
    img {{ width: min(92vw, 86vh); height: min(92vw, 86vh); box-sizing: border-box;
      background: white; padding: max(12px, 2vmin); border-radius: 8px; }}
    code {{ display: block; margin: 0; max-width: 96vw; overflow-wrap: anywhere; color: #7dd3fc; }}
    .status {{ color: {status_color}; font-weight: 700; }}
    .errors {{ color: #ffb4ad; }}
    .hint {{ color: #9aa7b2; line-height: 1.45; }}
    .meta {{ font-size: clamp(12px, 1.5vw, 16px); }}
    details {{ max-width: 96vw; }}
  </style>
</head>
<body><main>
  <h1>AR 通用客户端调试连接</h1>
  <img src="data:image/png;base64,{qr_data}" alt="服务配置二维码">
  <p class="meta">服务状态：<span class="status">{status}</span></p>
  <code>{safe_base_url}</code>
  <p class="hint">眼镜 App 空闲状态下单击进入扫码配置。请用局域网 IP 打开本页。</p>
  {error_text}
  <details><summary>二维码内容</summary><code>{safe_payload}</code></details>
</main></body></html>"""

_SENSITIVE_KEY_MARKERS = ("api_key", "authorization", "credential", "token", "secret", "password")


def _redact_sensitive(value: Any) -> Any:
    if isinstance(value, dict):
        return {
            key: "[REDACTED]" if any(marker in str(key).lower() for marker in _SENSITIVE_KEY_MARKERS) else _redact_sensitive(item)
            for key, item in value.items()
        }
    if isinstance(value, list):
        return [_redact_sensitive(item) for item in value]
    if isinstance(value, tuple):
        return [_redact_sensitive(item) for item in value]
    return value


def _json_bytes(payload: Any) -> bytes:
    return json.dumps(_redact_sensitive(payload), ensure_ascii=False, indent=2, sort_keys=True, default=str).encode("utf-8")


def _sha256_path(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as source:
        for chunk in iter(lambda: source.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def _model_manifest_summary(path: Path | None) -> dict[str, Any] | None:
    if path is None:
        return None
    summary: dict[str, Any] = {"manifest_name": path.name, "available": path.is_file()}
    if not path.is_file():
        return summary
    try:
        payload = json.loads(path.read_text(encoding="utf-8"))
    except (OSError, json.JSONDecodeError):
        summary["error"] = "INVALID_MANIFEST"
        return summary
    if not isinstance(payload, dict):
        summary["error"] = "INVALID_MANIFEST"
        return summary
    predict_args = payload.get("predict_args")
    model_path = payload.get("model_path")
    summary.update(
        {
            "runtime": payload.get("runtime"),
            "model_version": payload.get("model_version"),
            "model_file": Path(str(model_path)).name if model_path else None,
            "device": predict_args.get("device") if isinstance(predict_args, dict) else None,
        }
    )
    return summary


def _evidence_runtime_context(settings: Settings, result: dict[str, Any]) -> dict[str, Any]:
    return {
        "runtime_env": str(getattr(settings, "runtime_env", "unknown")),
        "service": "AR Mahjong Cloud",
        "service_version": settings.service_version,
        "analysis": {
            "max_upload_bytes": settings.max_upload_bytes,
            "max_image_pixels": settings.max_image_pixels,
            "analysis_concurrency": settings.analysis_concurrency,
            "analysis_queue_timeout_ms": settings.analysis_queue_timeout_ms,
            "analysis_exec_timeout_ms": settings.analysis_exec_timeout_ms,
        },
        "model_versions": result.get("model_versions"),
        "rule_profile": result.get("rule_profile"),
    }


def _build_evidence_zip(
    evidence_id: str,
    snapshot: dict[str, Any],
    settings: Settings,
    service: AnalysisService,
) -> Path:
    evidence = snapshot.get("evidence") if isinstance(snapshot.get("evidence"), dict) else {}
    analysis = snapshot.get("analysis") if isinstance(snapshot.get("analysis"), dict) else {}
    session = snapshot.get("session") if isinstance(snapshot.get("session"), dict) else {}
    result = snapshot.get("result") if isinstance(snapshot.get("result"), dict) else {}
    if not result and isinstance(analysis.get("result"), dict):
        result = analysis["result"]

    analysis_payload = {
        key: value
        for key, value in analysis.items()
        if key not in {"result", "result_json"}
    }
    if not session:
        # `get_evidence_snapshot` returns the session summary flattened into
        # the analysis row so that the storage API never exposes a separate
        # mutable session object. Rebuild the safe, immutable export view here.
        session = {
            "session_id": analysis.get("session_id"),
            "rule_profile_id": analysis.get("rule_profile_id"),
            "status": analysis.get("session_status"),
            "created_at": analysis.get("session_created_at"),
            "ended_at": analysis.get("session_ended_at"),
        }
    documents = {
        "case.json": {
            "format_version": 1,
            "generated_at": datetime.now(timezone.utc).isoformat(),
            "evidence": evidence,
        },
        "analysis.json": {"analysis": analysis_payload, "result": result},
        "session.json": {"session": session},
        "runtime-context.json": _evidence_runtime_context(settings, result),
        "model-manifests.json": {
            "region": _model_manifest_summary(settings.region_manifest_path),
            "tile": _model_manifest_summary(settings.tile_manifest_path),
        },
    }
    encoded_documents = {name: _json_bytes(payload) for name, payload in documents.items()}
    debug_asset = snapshot.get("debug_asset") if isinstance(snapshot.get("debug_asset"), dict) else {}
    image_path = service.debug_image_path(str(analysis.get("analysis_id") or evidence.get("analysis_id") or ""))
    include_image = bool(debug_asset.get("status") == "stored" and image_path.name and image_path.is_file())

    archive_directory = settings.evidence_dir or (service.debug_image_dir().parent / "evidence")
    archive_directory.mkdir(parents=True, exist_ok=True)
    with tempfile.NamedTemporaryFile(
        dir=archive_directory, prefix=f"ar-mahjong-evidence-{evidence_id}-", suffix=".zip", delete=False
    ) as temporary:
        archive_path = Path(temporary.name)
    try:
        checksums = {name: hashlib.sha256(content).hexdigest() for name, content in encoded_documents.items()}
        if include_image:
            checksums["debug-image.png"] = _sha256_path(image_path)
        with zipfile.ZipFile(archive_path, "w", compression=zipfile.ZIP_DEFLATED) as archive:
            for name, content in encoded_documents.items():
                archive.writestr(name, content)
            if include_image:
                archive.write(image_path, arcname="debug-image.png")
            archive.writestr("checksums.json", _json_bytes({"sha256": checksums}))
        return archive_path
    except Exception:
        archive_path.unlink(missing_ok=True)
        raise


def _finish_evidence_download(store: Any, evidence_id: str, archive_path: Path) -> None:
    try:
        store.mark_evidence_exported(evidence_id)
    except Exception:
        pass
    finally:
        archive_path.unlink(missing_ok=True)



def _recording_source_path(service: AnalysisService, session_id: str, source_path: str) -> Path:
    root = service.session_recording_dir(session_id).resolve()
    try:
        source = Path(source_path).resolve()
        source.relative_to(root)
    except (OSError, ValueError) as exc:
        raise CloudError("REGRESSION_PACK_INTEGRITY_ERROR", "Recorded frame path is invalid", 409) from exc
    return source


def _build_regression_pack_zip(
    session_id: str,
    snapshot: dict[str, Any],
    settings: Settings,
    service: AnalysisService,
) -> Path:
    recording = snapshot.get("recording") if isinstance(snapshot.get("recording"), dict) else {}
    if recording.get("status") != "completed":
        raise CloudError(
            "REGRESSION_PACK_NOT_READY",
            "Regression recording must be completed without missing frames before download",
            409,
        )
    session = snapshot.get("session") if isinstance(snapshot.get("session"), dict) else {}
    frames = snapshot.get("frames") if isinstance(snapshot.get("frames"), list) else []
    frames = sorted(frames, key=lambda frame: int(frame["sequence_id"]) if isinstance(frame, dict) else -1)
    if int(recording.get("frame_count", 0)) != len(frames):
        raise CloudError("REGRESSION_PACK_INCOMPLETE", "Regression frame index is incomplete", 409)

    manifest_frames: list[dict[str, Any]] = []
    encoded_documents: dict[str, bytes] = {}
    checksums: dict[str, str] = {}
    baseline_results: list[dict[str, Any]] = []
    frame_sources: list[tuple[Path, str, int, str]] = []
    for frame in frames:
        if not isinstance(frame, dict) or frame.get("analysis_status") != "completed":
            raise CloudError("REGRESSION_PACK_INCOMPLETE", "Regression baseline analysis is incomplete", 409)
        result = frame.get("result")
        if not isinstance(result, dict):
            raise CloudError("REGRESSION_PACK_INCOMPLETE", "Regression baseline result is missing", 409)
        sequence_id = int(frame["sequence_id"])
        content_type = str(frame["content_type"])
        if content_type not in {"image/png", "image/jpeg"}:
            raise CloudError("REGRESSION_PACK_INTEGRITY_ERROR", "Recorded frame content type is invalid", 409)
        extension = "png" if content_type == "image/png" else "jpg"
        frame_name = f"frames/{sequence_id:06d}.{extension}"
        baseline_name = f"baselines/{sequence_id:06d}.json"
        source = _recording_source_path(service, session_id, str(frame["source_path"]))
        try:
            source_size = source.stat().st_size if source.is_file() else -1
        except OSError:
            source_size = -1
        if (
            source_size != int(frame["size_bytes"])
            or source_size < 0
            or _sha256_path(source) != str(frame["sha256"])
        ):
            raise CloudError("REGRESSION_PACK_INTEGRITY_ERROR", "Recorded frame is missing or has an invalid checksum", 409)
        baseline_bytes = _json_bytes(result)
        encoded_documents[baseline_name] = baseline_bytes
        checksums[baseline_name] = hashlib.sha256(baseline_bytes).hexdigest()
        checksums[frame_name] = str(frame["sha256"])
        frame_sources.append((source, frame_name, source_size, str(frame["sha256"])))
        manifest_frames.append(
            {
                "sequence_id": sequence_id,
                "request_id": frame["request_id"],
                "frame_ts": frame["frame_ts"],
                "content_type": content_type,
                "rotation_degrees": frame["rotation_degrees"],
                "sha256": frame["sha256"],
                "size_bytes": frame["size_bytes"],
                "frame_path": frame_name,
                "baseline_path": baseline_name,
                "analysis_id": frame["analysis_id"],
            }
        )
        baseline_results.append(result)

    manifest = {
        "format": "ar-mahjong-session-regression-v1",
        "generated_at": datetime.now(timezone.utc).isoformat(),
        "session_id": session_id,
        "frame_count": len(manifest_frames),
        "frames": manifest_frames,
    }
    latest_result = baseline_results[-1] if baseline_results else {}
    documents = {
        "manifest.json": manifest,
        "session.json": {"session": session},
        "runtime-context.json": _evidence_runtime_context(settings, latest_result),
        "model-manifests.json": {
            "region": _model_manifest_summary(settings.region_manifest_path),
            "tile": _model_manifest_summary(settings.tile_manifest_path),
        },
    }
    for name, payload in documents.items():
        content = _json_bytes(payload)
        encoded_documents[name] = content
        checksums[name] = hashlib.sha256(content).hexdigest()

    archive_directory = service.session_recording_dir(session_id).parent.parent / "exports"
    archive_directory.mkdir(parents=True, exist_ok=True)
    estimated_archive_bytes = (
        sum(int(frame["size_bytes"]) for frame in frames)
        + sum(len(content) for content in encoded_documents.values())
        + 1024 * 1024
    )
    if shutil.disk_usage(archive_directory).free < estimated_archive_bytes + settings.storage_min_free_bytes:
        raise CloudError(
            "REGRESSION_PACK_STORAGE_UNAVAILABLE",
            "Insufficient disk space to build the regression pack",
            507,
        )
    with tempfile.NamedTemporaryFile(
        dir=archive_directory, prefix=f"ar-mahjong-session-regression-{session_id}-", suffix=".zip", delete=False
    ) as temporary:
        archive_path = Path(temporary.name)
    try:
        with zipfile.ZipFile(archive_path, "w", compression=zipfile.ZIP_DEFLATED) as archive:
            for name, content in encoded_documents.items():
                archive.writestr(name, content)
            for source, archive_name, expected_size, expected_sha256 in frame_sources:
                digest = hashlib.sha256()
                written = 0
                with source.open("rb") as frame_source, archive.open(archive_name, "w") as archived_frame:
                    for chunk in iter(lambda: frame_source.read(1024 * 1024), b""):
                        digest.update(chunk)
                        archived_frame.write(chunk)
                        written += len(chunk)
                if written != expected_size or digest.hexdigest() != expected_sha256:
                    raise CloudError(
                        "REGRESSION_PACK_INTEGRITY_ERROR",
                        "Recorded frame changed while the regression pack was being built",
                        409,
                    )
            archive.writestr("checksums.json", _json_bytes({"sha256": checksums}))
        return archive_path
    except Exception:
        archive_path.unlink(missing_ok=True)
        raise


def _finish_regression_pack_download(
    store: Any,
    session_id: str,
    holder_id: str,
    archive_path: Path,
) -> None:
    try:
        store.mark_session_recording_exported(session_id)
    except Exception:
        pass
    finally:
        try:
            archive_path.unlink(missing_ok=True)
        finally:
            try:
                store.release_session_recording_export(session_id, holder_id)
            except Exception:
                pass


class _DisposableFileResponse(FileResponse):
    """Delete a generated response file if ASGI delivery fails."""

    def __init__(self, path: Path, *, failure_cleanup: Any = None, **kwargs: Any) -> None:
        super().__init__(path, **kwargs)
        self._failure_cleanup = failure_cleanup

    async def __call__(self, scope: Any, receive: Any, send: Any) -> None:
        try:
            await super().__call__(scope, receive, send)
        except BaseException:
            try:
                Path(self.path).unlink(missing_ok=True)
            finally:
                if self._failure_cleanup is not None:
                    try:
                        self._failure_cleanup()
                    except Exception:
                        pass
            raise


class _RegressionPackFileResponse(_DisposableFileResponse):
    """Release the export lease as well as deleting a failed response."""

    def __init__(
        self,
        path: Path,
        *,
        store: Any,
        session_id: str,
        holder_id: str,
        **kwargs: Any,
    ) -> None:
        self._regression_store = store
        self._regression_session_id = session_id
        self._regression_holder_id = holder_id
        super().__init__(
            path,
            failure_cleanup=lambda: self._regression_store.release_session_recording_export(
                self._regression_session_id,
                self._regression_holder_id,
            ),
            **kwargs,
        )

def create_app(settings: Settings | None = None, service: AnalysisService | None = None) -> FastAPI:
    settings = settings or Settings.from_env()
    service = service or AnalysisService.from_settings(settings)


    @asynccontextmanager
    async def retention_lifespan(_: FastAPI):
        if not settings.retention_enabled:
            yield
            return

        stopped = anyio.Event()
        interval_seconds = settings.retention_cleanup_interval_minutes * 60

        async def cleanup_loop() -> None:
            while not stopped.is_set():
                try:
                    report = await anyio.to_thread.run_sync(service.run_retention_cleanup_once)
                    if report.get("status") in {"failed", "lease_failed"} or report.get("errors"):
                        logger.warning("cloud retention cleanup reported: %s", report)
                except Exception:
                    # Retention is explicitly best-effort and must never make a
                    # worker unavailable. SQLite lease arbitration inside the
                    # retention manager keeps this loop safe across workers.
                    logger.exception("cloud retention cleanup failed")
                with anyio.move_on_after(interval_seconds):
                    await stopped.wait()

        async with anyio.create_task_group() as task_group:
            task_group.start_soon(cleanup_loop)
            try:
                yield
            finally:
                stopped.set()

    app = FastAPI(title="AR Mahjong Cloud", version="0.1.0", lifespan=retention_lifespan)
    app.state.settings = settings
    app.state.service = service
    app.state.analysis_limiter = anyio.CapacityLimiter(settings.analysis_concurrency)

    @app.exception_handler(CloudError)
    async def cloud_error_handler(request: Request, exc: CloudError) -> JSONResponse:
        return JSONResponse(status_code=exc.status_code, content={"error": {"code": exc.code, "message": exc.message}})

    def require_api_key(x_api_key: str | None) -> None:
        if not settings.api_key or x_api_key != settings.api_key:
            raise CloudError("UNAUTHORIZED", "Invalid API key", 401)


    async def run_analysis_with_limits(
        session_id: str,
        request_id: str,
        analyze_call: Any,
        timings: dict[str, float],
    ) -> dict[str, Any]:
        session_lease_acquired = False
        limiter_acquired = False
        try:
            start = time.perf_counter()
            service.store.acquire_session_in_flight(session_id, request_id)
            timings["session_in_flight_acquire"] = round((time.perf_counter() - start) * 1000, 2)
            session_lease_acquired = True
            start = time.perf_counter()
            with anyio.move_on_after(settings.analysis_queue_timeout_ms / 1000):
                await app.state.analysis_limiter.acquire()
                limiter_acquired = True
            timings["queue_wait"] = round((time.perf_counter() - start) * 1000, 2)
            if not limiter_acquired:
                raise CloudError("ANALYSIS_BUSY", "Analysis capacity is busy", 429)
            try:
                start = time.perf_counter()
                result = await anyio.to_thread.run_sync(analyze_call)
                timings["analysis_worker"] = round((time.perf_counter() - start) * 1000, 2)
                return result
            finally:
                app.state.analysis_limiter.release()
        finally:
            if session_lease_acquired:
                service.store.release_session_in_flight(session_id, request_id)

    def record_slow_route_evidence(result: dict[str, Any], route_timings: dict[str, float]) -> None:
        threshold = settings.evidence_slow_route_threshold_ms
        route_total = route_timings.get("route_handler_total", 0.0)
        analysis_id = result.get("analysis_id")
        if not threshold or not analysis_id or route_total < threshold:
            return
        model_versions = result.get("model_versions")
        signature = "slow_route:" + json.dumps(model_versions, ensure_ascii=False, sort_keys=True, default=str)
        try:
            service.create_evidence(str(analysis_id), source="automatic", signature=signature)
        except Exception:
            # Automatic evidence is forensic convenience, not a client-facing
            # failure mode. The analysis response remains successful.
            logger.exception("automatic slow-route evidence capture failed")

    async def read_image_stream(request: Request) -> bytes:
        body = bytearray()
        async for chunk in request.stream():
            body.extend(chunk)
            if len(body) > settings.max_upload_bytes:
                raise CloudError("PAYLOAD_TOO_LARGE", "Image exceeds maximum upload size", 413)
        if not body:
            raise CloudError("EMPTY_IMAGE", "Request body must contain an image", 422)
        return bytes(body)

    @app.get("/health/live")
    async def health_live() -> dict[str, str]:
        return {"status": "live"}

    @app.get("/health/ready")
    async def health_ready() -> JSONResponse:
        ready, errors = service.readiness()
        return JSONResponse(status_code=200 if ready else 503, content={"status": "ready" if ready else "not_ready", "errors": errors})

    def require_debug_api_key(x_api_key: str | None, api_key: str | None) -> None:
        require_api_key(x_api_key or api_key)

    @app.get("/monitor", response_class=HTMLResponse)
    @app.get("/debug/monitor", response_class=HTMLResponse)
    async def debug_monitor() -> HTMLResponse:
        return HTMLResponse(DEBUG_MONITOR_HTML)

    @app.get("/debug/connect", response_class=HTMLResponse)
    async def debug_connect(
        request: Request,
        api_key: str | None = Query(default=None),
        x_api_key: str | None = Header(default=None),
    ) -> HTMLResponse:
        require_debug_api_key(x_api_key, api_key)
        ready, errors = service.readiness()
        response = HTMLResponse(
            build_debug_connection_html(str(request.base_url), ready, errors)
        )
        response.headers["Cache-Control"] = "no-store"
        return response

    @app.get("/debug/sessions")
    async def debug_sessions(
        limit: int = Query(default=50, ge=1, le=200),
        api_key: str | None = Query(default=None),
        x_api_key: str | None = Header(default=None),
    ) -> dict[str, Any]:
        require_debug_api_key(x_api_key, api_key)
        return {"sessions": service.store.list_debug_sessions(limit)}

    @app.get("/debug/sessions/{session_id}")
    async def debug_session(
        session_id: str,
        api_key: str | None = Query(default=None),
        x_api_key: str | None = Header(default=None),
    ) -> dict[str, Any]:
        require_debug_api_key(x_api_key, api_key)
        return service.store.get_debug_session(session_id)

    @app.get("/debug/analyses/{analysis_id}")
    async def debug_analysis(
        analysis_id: str,
        api_key: str | None = Query(default=None),
        x_api_key: str | None = Header(default=None),
    ) -> dict[str, Any]:
        require_debug_api_key(x_api_key, api_key)
        return service.store.get_debug_analysis(analysis_id)

    @app.get("/debug/analyses/{analysis_id}/image")
    async def debug_analysis_image(
        analysis_id: str,
        api_key: str | None = Query(default=None),
        x_api_key: str | None = Header(default=None),
    ) -> FileResponse:
        require_debug_api_key(x_api_key, api_key)
        analysis = service.store.get_debug_analysis(analysis_id)["analysis"]
        image_path = service.debug_image_path(analysis["analysis_id"])
        if not image_path.is_file():
            raise CloudError("DEBUG_IMAGE_NOT_STORED", "Debug image was not stored for this analysis", 404)
        return FileResponse(image_path, media_type="image/png")

    @app.post("/debug/analyses/{analysis_id}/evidence")
    async def debug_freeze_evidence(
        analysis_id: str,
        api_key: str | None = Query(default=None),
        x_api_key: str | None = Header(default=None),
    ) -> dict[str, Any]:
        require_debug_api_key(x_api_key, api_key)
        evidence = service.create_evidence(analysis_id, source="manual")
        return {"evidence": evidence}

    @app.get("/debug/evidence/{evidence_id}/download")
    async def debug_download_evidence(
        evidence_id: str,
        api_key: str | None = Query(default=None),
        x_api_key: str | None = Header(default=None),
    ) -> FileResponse:
        require_debug_api_key(x_api_key, api_key)
        snapshot = service.store.get_evidence_snapshot(evidence_id)
        archive_path = _build_evidence_zip(evidence_id, snapshot, settings, service)
        return _DisposableFileResponse(
            archive_path,
            media_type="application/zip",
            filename=f"ar-mahjong-evidence-{evidence_id}.zip",
            background=BackgroundTask(_finish_evidence_download, service.store, evidence_id, archive_path),
        )


    @app.get("/debug/sessions/{session_id}/regression-pack/download")
    async def debug_download_regression_pack(
        session_id: str,
        api_key: str | None = Query(default=None),
        x_api_key: str | None = Header(default=None),
    ) -> FileResponse:
        require_debug_api_key(x_api_key, api_key)
        holder_id = service.store.acquire_session_recording_export(session_id)
        archive_path: Path | None = None
        try:
            snapshot = service.store.get_regression_pack_snapshot(session_id)
            archive_path = await anyio.to_thread.run_sync(
                _build_regression_pack_zip,
                session_id,
                snapshot,
                settings,
                service,
            )
            return _RegressionPackFileResponse(
                archive_path,
                store=service.store,
                session_id=session_id,
                holder_id=holder_id,
                media_type="application/zip",
                filename=f"ar-mahjong-session-regression-{session_id}.zip",
                background=BackgroundTask(
                    _finish_regression_pack_download,
                    service.store,
                    session_id,
                    holder_id,
                    archive_path,
                ),
            )
        except Exception:
            if archive_path is not None:
                archive_path.unlink(missing_ok=True)
            service.store.release_session_recording_export(session_id, holder_id)
            raise


    @app.post("/api/v1/mahjong/sessions")
    async def create_session(payload: CreateSessionRequest, x_api_key: str | None = Header(default=None)) -> dict[str, Any]:
        require_api_key(x_api_key)
        session_id = service.create_session(payload.rule_profile_id, payload.recording_mode)
        return {
            "session_id": session_id,
            "rule_profile_id": payload.rule_profile_id,
            "recording_mode": payload.recording_mode,
        }

    @app.post("/api/v1/mahjong/sessions/{session_id}/analyses")
    async def analyze(
        session_id: str,
        request: Request,
        x_api_key: str | None = Header(default=None),
        x_request_id: str | None = Header(default=None),
        x_sequence_id: int | None = Header(default=None),
        x_frame_ts: int | None = Header(default=None),
        x_frame_count: int | None = Header(default=None),
        x_image_rotation_degrees: int = Header(default=0),
    ) -> dict[str, Any]:
        route_start = time.perf_counter()
        route_timings: dict[str, float] = {}
        require_api_key(x_api_key)
        if not x_request_id or x_sequence_id is None or x_frame_ts is None:
            raise CloudError("MISSING_METADATA", "X-Request-Id, X-Sequence-Id and X-Frame-Ts are required", 422)
        if x_frame_count != 1:
            raise CloudError("FRAME_COUNT_NOT_ENABLED", "Only one image frame is supported", 422)
        content_type = request.headers.get("content-type", "").split(";", 1)[0].lower()
        start = time.perf_counter()
        image_bytes = await read_image_stream(request)
        route_timings["request_body_read"] = round((time.perf_counter() - start) * 1000, 2)
        start = time.perf_counter()
        decoded = decode_image(image_bytes, content_type, settings, x_image_rotation_degrees)
        route_timings["image_decode"] = round((time.perf_counter() - start) * 1000, 2)
        analyze_call = partial(
            service.analyze,
            session_id,
            x_request_id,
            x_sequence_id,
            decoded,
            x_frame_ts,
            route_timings,
            original_image_bytes=image_bytes,
            content_type=content_type,
            rotation_degrees=x_image_rotation_degrees,
        )
        result = await run_analysis_with_limits(session_id, x_request_id, analyze_call, route_timings)
        result_timings = result.get("timings_ms") if isinstance(result, dict) else None
        if result_timings is route_timings:
            route_timings["route_handler_total"] = round((time.perf_counter() - route_start) * 1000, 2)
            algorithm_total = route_timings.get("algorithm_total", route_timings.get("total", 0.0))
            route_timings["route_overhead"] = round(max(0.0, route_timings["route_handler_total"] - algorithm_total), 2)
            analysis_id = result.get("analysis_id")
            if analysis_id:
                service.store.update_analysis_result(analysis_id, result)
                record_slow_route_evidence(result, route_timings)
        return result

    @app.get("/api/v1/mahjong/sessions/{session_id}/analyses/{analysis_id}")
    async def get_analysis(session_id: str, analysis_id: str, x_api_key: str | None = Header(default=None)) -> dict[str, Any]:
        require_api_key(x_api_key)
        return service.store.get_analysis(session_id, analysis_id)

    @app.post("/api/v1/mahjong/sessions/{session_id}/end")
    async def end_session(session_id: str, x_api_key: str | None = Header(default=None)) -> dict[str, str]:
        require_api_key(x_api_key)
        service.end_session(session_id)
        return {"status": "ended", "session_id": session_id}

    return app


app = create_app()
