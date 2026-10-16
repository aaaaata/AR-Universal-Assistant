from __future__ import annotations

import asyncio
import hashlib
import io
import json
import zipfile
from dataclasses import replace
from pathlib import Path
from threading import Event
import time

from fastapi.testclient import TestClient
import pytest
from starlette.background import BackgroundTask

from cloud.app import _DisposableFileResponse, create_app
from cloud.debug_monitor import DEBUG_MONITOR_HTML
from cloud.service import AnalysisService
from cloud.tests.test_api import png_bytes
from cloud.tests.test_api_boundaries import analysis_headers
from cloud.storage import SQLiteStore


def test_monitor_page_is_available_without_exposing_data(service):
    client = TestClient(create_app(service.settings, service))

    response = client.get("/monitor")

    assert response.status_code == 200
    assert "AR Mahjong Cloud Monitor" in response.text
    assert "/debug/sessions" in response.text


def test_disposable_file_response_removes_evidence_zip_when_send_fails(tmp_path: Path):
    archive_path = tmp_path / "evidence.zip"
    archive_path.write_bytes(b"zip")
    success_callbacks: list[bool] = []
    response = _DisposableFileResponse(
        archive_path,
        background=BackgroundTask(lambda: success_callbacks.append(True)),
    )

    async def receive():
        return {"type": "http.request", "body": b"", "more_body": False}

    async def failing_send(_message):
        raise RuntimeError("client disconnected")

    with pytest.raises(RuntimeError, match="client disconnected"):
        asyncio.run(
            response(
                {"type": "http", "method": "GET", "headers": []},
                receive,
                failing_send,
            )
        )

    assert not archive_path.exists()
    assert success_callbacks == []


def test_regression_pack_download_is_top_level_monitor_function():
    download = "async function downloadRegressionPack(sessionId, button) {"
    render = "function renderSelectedAnalysis(analysis) {\n      currentAnalysis = analysis;"

    assert DEBUG_MONITOR_HTML.count(download) == 1
    assert DEBUG_MONITOR_HTML.index(download) < DEBUG_MONITOR_HTML.index(render)


def test_monitor_renders_all_recording_state_and_completed_only_download():
    assert 'recording.status === "completed"' in DEBUG_MONITOR_HTML
    assert "recording.frame_count" in DEBUG_MONITOR_HTML
    assert "recording.total_bytes" in DEBUG_MONITOR_HTML
    assert "recording.incomplete_reason" in DEBUG_MONITOR_HTML
    assert "recording.expires_at" in DEBUG_MONITOR_HTML
    assert "recording.exported_at" in DEBUG_MONITOR_HTML


def test_monitor_draws_dashed_roi_crop_boxes():
    assert ".roi-box" in DEBUG_MONITOR_HTML
    assert "stroke-dasharray" in DEBUG_MONITOR_HTML
    assert "region.roi_bbox" in DEBUG_MONITOR_HTML
    assert 'span class="roi">roi</span>' in DEBUG_MONITOR_HTML


def test_debug_connect_requires_api_key(service):
    client = TestClient(create_app(service.settings, service))

    response = client.get("/debug/connect")

    assert response.status_code == 401
    assert response.json()["error"]["code"] == "UNAUTHORIZED"


def test_debug_connect_renders_scene_neutral_qr_profile(service):
    client = TestClient(create_app(service.settings, service))

    response = client.get("/debug/connect?api_key=test-key")

    assert response.status_code == 200
    assert response.headers["cache-control"] == "no-store"
    assert "AR 通用客户端调试连接" in response.text
    assert "data:image/png;base64," in response.text
    assert "rayneo-assist://configure" in response.text
    assert "http://testserver/" in response.text
    assert "scene=mahjong" in response.text


def test_debug_sessions_require_api_key(service):
    client = TestClient(create_app(service.settings, service))

    response = client.get("/debug/sessions")

    assert response.status_code == 401
    assert response.json()["error"]["code"] == "UNAUTHORIZED"


def test_debug_sessions_can_be_loaded_with_query_key(service):
    client = TestClient(create_app(service.settings, service))

    response = client.get("/debug/sessions?api_key=test-key")

    assert response.status_code == 200
    assert response.json() == {"sessions": []}


def test_debug_monitor_lists_session_analysis_and_detail(service):
    client = TestClient(create_app(service.settings, service))
    headers = {"X-API-Key": "test-key"}
    session = client.post("/api/v1/mahjong/sessions", headers=headers, json={"rule_profile_id": "guangdong_mvp_v1"})
    session_id = session.json()["session_id"]
    analysis = client.post(
        f"/api/v1/mahjong/sessions/{session_id}/analyses",
        headers=analysis_headers("debug-monitor-1"),
        content=png_bytes(),
    )
    analysis_id = analysis.json()["analysis_id"]

    sessions = client.get("/debug/sessions", headers=headers)
    session_detail = client.get(f"/debug/sessions/{session_id}", headers=headers)
    analysis_detail = client.get(f"/debug/analyses/{analysis_id}", headers=headers)

    assert sessions.status_code == 200
    assert len(sessions.json()["sessions"]) == 1
    assert sessions.json()["sessions"][0]["analysis_count"] == 1
    assert sessions.json()["sessions"][0]["latest_analysis"]["analysis_id"] == analysis_id
    assert sessions.json()["sessions"][0]["latest_analysis"]["result_status"] == "complete"

    assert session_detail.status_code == 200
    assert session_detail.json()["session"]["session_id"] == session_id
    assert session_detail.json()["analyses"][0]["analysis_id"] == analysis_id
    assert session_detail.json()["analyses"][0]["result"]["analysis_id"] == analysis_id

    assert analysis_detail.status_code == 200
    assert analysis_detail.json()["analysis"]["analysis_id"] == analysis_id
    assert analysis_detail.json()["analysis"]["result"]["status"] == "complete"


def test_debug_image_is_saved_and_served_by_default(service, tmp_path):
    debug_settings = replace(service.settings, debug_image_dir=tmp_path / "debug-images")
    debug_service = AnalysisService(
        settings=debug_settings,
        store=service.store,
        profile=service.profile,
        region_detector=service.region_detector,
        tile_detector=service.tile_detector,
    )
    client = TestClient(create_app(debug_settings, debug_service))
    headers = {"X-API-Key": "test-key"}
    session_id = client.post("/api/v1/mahjong/sessions", headers=headers, json={}).json()["session_id"]
    analysis = client.post(
        f"/api/v1/mahjong/sessions/{session_id}/analyses",
        headers=analysis_headers("debug-image-1"),
        content=png_bytes(),
    )
    payload = analysis.json()
    analysis_id = payload["analysis_id"]

    assert payload["debug_image"] == {"stored": True, "format": "png", "url": f"/debug/analyses/{analysis_id}/image"}
    assert (debug_settings.debug_image_dir / f"{analysis_id}.png").is_file()

    detail = client.get(f"/debug/analyses/{analysis_id}", headers=headers).json()["analysis"]
    image = client.get(f"/debug/analyses/{analysis_id}/image", headers=headers)

    assert detail["debug_image_stored"] is True
    assert detail["debug_image_url"] == f"/debug/analyses/{analysis_id}/image"
    assert image.status_code == 200
    assert image.headers["content-type"].startswith("image/png")
    assert image.content.startswith(b"\x89PNG\r\n\x1a\n")


def test_debug_image_route_reports_missing_when_file_is_absent(service, tmp_path):
    debug_settings = replace(service.settings, debug_image_dir=tmp_path / "debug-images")
    debug_service = AnalysisService(
        settings=debug_settings,
        store=service.store,
        profile=service.profile,
        region_detector=service.region_detector,
        tile_detector=service.tile_detector,
    )
    client = TestClient(create_app(debug_settings, debug_service))
    headers = {"X-API-Key": "test-key"}
    session_id = client.post("/api/v1/mahjong/sessions", headers=headers, json={}).json()["session_id"]
    analysis = client.post(
        f"/api/v1/mahjong/sessions/{session_id}/analyses",
        headers=analysis_headers("debug-image-missing"),
        content=png_bytes(),
    )
    analysis_id = analysis.json()["analysis_id"]
    debug_service.debug_image_path(analysis_id).unlink()

    response = client.get(f"/debug/analyses/{analysis_id}/image", headers=headers)

    assert response.status_code == 404
    assert response.json()["error"]["code"] == "DEBUG_IMAGE_NOT_STORED"


def test_debug_suggestion_label_summarizes_draw_wait_tiles():
    assert SQLiteStore._suggestion_label({"tile": "1m"}) == "1m"
    assert SQLiteStore._suggestion_label({"jinzhang_tiles": ["1m", "2m", "3m", "4m", "5m", "6m"]}) == "1m,2m,3m,4m,5m..."


def test_debug_evidence_freeze_and_download_is_api_key_protected(service):
    client = TestClient(create_app(service.settings, service))
    headers = {"X-API-Key": "test-key"}
    session_id = client.post("/api/v1/mahjong/sessions", headers=headers, json={}).json()["session_id"]
    analysis = client.post(
        f"/api/v1/mahjong/sessions/{session_id}/analyses",
        headers=analysis_headers("debug-evidence-1"),
        content=png_bytes(),
    ).json()
    analysis_id = analysis["analysis_id"]

    assert analysis["debug_image"]["stored"] is True
    unauthorized = client.post(f"/debug/analyses/{analysis_id}/evidence")
    assert unauthorized.status_code == 401

    frozen = client.post(f"/debug/analyses/{analysis_id}/evidence", headers=headers)
    assert frozen.status_code == 200
    evidence = frozen.json()["evidence"]
    assert evidence["analysis_id"] == analysis_id
    assert evidence["source"] == "manual"
    assert evidence["status"] == "active"

    repeated = client.post(f"/debug/analyses/{analysis_id}/evidence", headers=headers)
    assert repeated.status_code == 200
    assert repeated.json()["evidence"]["evidence_id"] == evidence["evidence_id"]

    denied_download = client.get(f"/debug/evidence/{evidence['evidence_id']}/download")
    assert denied_download.status_code == 401

    downloaded = client.get(f"/debug/evidence/{evidence['evidence_id']}/download", headers=headers)
    assert downloaded.status_code == 200
    assert downloaded.headers["content-type"].startswith("application/zip")
    assert evidence["evidence_id"] in downloaded.headers["content-disposition"]

    with zipfile.ZipFile(io.BytesIO(downloaded.content)) as archive:
        assert set(archive.namelist()) == {
            "analysis.json",
            "case.json",
            "checksums.json",
            "debug-image.png",
            "model-manifests.json",
            "runtime-context.json",
            "session.json",
        }
        case = json.loads(archive.read("case.json"))
        analysis_payload = json.loads(archive.read("analysis.json"))
        session_payload = json.loads(archive.read("session.json"))
        runtime_context = json.loads(archive.read("runtime-context.json"))
        checksums = json.loads(archive.read("checksums.json"))["sha256"]
        debug_image = archive.read("debug-image.png")
        json_payload = b"".join(archive.read(name) for name in archive.namelist() if name.endswith(".json"))

    assert case["evidence"]["evidence_id"] == evidence["evidence_id"]
    assert runtime_context["service_version"] == service.settings.service_version
    assert analysis_payload["analysis"]["analysis_id"] == analysis_id
    assert analysis_payload["result"]["analysis_id"] == analysis_id
    assert session_payload["session"]["session_id"] == session_id
    assert runtime_context["runtime_env"] == service.settings.runtime_env
    assert checksums["debug-image.png"] == hashlib.sha256(debug_image).hexdigest()
    assert b"test-key" not in json_payload

    stored = client.get(f"/debug/analyses/{analysis_id}", headers=headers).json()["analysis"]
    assert stored["evidence"]["evidence_id"] == evidence["evidence_id"]
    assert stored["evidence"]["exported_at"] is not None


def test_retention_cleanup_starts_in_app_lifespan(service, monkeypatch):
    cleanup_started = Event()

    def fake_cleanup() -> dict[str, object]:
        cleanup_started.set()
        return {"status": "completed", "errors": []}

    monkeypatch.setattr(service, "run_retention_cleanup_once", fake_cleanup)
    with TestClient(create_app(service.settings, service)) as client:
        assert client.get("/health/live").status_code == 200
        assert cleanup_started.wait(timeout=1)


def test_slow_route_creates_automatic_evidence(service, monkeypatch):
    slow_settings = replace(service.settings, evidence_slow_route_threshold_ms=1)
    slow_service = AnalysisService(
        settings=slow_settings,
        store=service.store,
        profile=service.profile,
        region_detector=service.region_detector,
        tile_detector=service.tile_detector,
    )
    original_analyze = slow_service.analyze

    def slow_analyze(*args, **kwargs):
        time.sleep(0.01)
        return original_analyze(*args, **kwargs)

    monkeypatch.setattr(slow_service, "analyze", slow_analyze)
    client = TestClient(create_app(slow_settings, slow_service))
    headers = {"X-API-Key": "test-key"}
    session_id = client.post("/api/v1/mahjong/sessions", headers=headers, json={}).json()["session_id"]
    response = client.post(
        f"/api/v1/mahjong/sessions/{session_id}/analyses",
        headers=analysis_headers("slow-route-evidence-1"),
        content=png_bytes(),
    )

    assert response.status_code == 200
    analysis_id = response.json()["analysis_id"]
    assert response.json()["timings_ms"]["route_handler_total"] >= slow_settings.evidence_slow_route_threshold_ms
    evidence = client.get(f"/debug/analyses/{analysis_id}", headers=headers).json()["analysis"]["evidence"]
    assert evidence["source"] == "automatic"
    assert evidence["signature"].startswith("slow_route:")
