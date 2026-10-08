from __future__ import annotations

import hashlib
import io
import shutil
import time
from dataclasses import dataclass
from pathlib import Path
from typing import Any

import cv2
import numpy as np
from PIL import Image, ImageOps, UnidentifiedImageError

from server.adapters import FullImageHandRegionDetector, RegionDetector, TileDetector, create_region_detector, create_tile_detector
from server.config import Settings
from server.domain import DetectedRegion, StructuredHand
from server.errors import CloudError, ModelNotReadyError
from server.retention import RetentionManager
from server.rules import GuangdongMvpRuleEngine, RuleProfile, UnsupportedMeldError, load_rule_profile
from server.storage import SQLiteStore
from server.structure import keep_detections_in_source_regions, structure_tiles


@dataclass(frozen=True)
class DecodedImage:
    bgr: np.ndarray
    sha256: str
    image_format: str
    width: int
    height: int


def decode_image(image_bytes: bytes, content_type: str, settings: Settings, rotation_degrees: int = 0) -> DecodedImage:
    if content_type not in {"image/png", "image/jpeg"}:
        raise CloudError("UNSUPPORTED_MEDIA_TYPE", "Only image/png and image/jpeg are accepted", 415)
    if rotation_degrees not in {0, 90, 180, 270}:
        raise CloudError("INVALID_IMAGE_ROTATION", "X-Image-Rotation-Degrees must be 0, 90, 180 or 270", 422)
    try:
        with Image.open(io.BytesIO(image_bytes)) as source:
            decoded_format = source.format
            if decoded_format not in {"PNG", "JPEG"}:
                raise CloudError("UNSUPPORTED_MEDIA_TYPE", "Uploaded bytes are not PNG or JPEG", 415)
            expected_format = "PNG" if content_type == "image/png" else "JPEG"
            if decoded_format != expected_format:
                raise CloudError("IMAGE_FORMAT_MISMATCH", "Content-Type does not match decoded image", 415)
            width, height = source.size
            if width < settings.min_image_width or height < settings.min_image_height:
                raise CloudError("IMAGE_TOO_SMALL", "Image dimensions are below the supported minimum", 422)
            pixels = width * height
            if settings.max_image_pixels > 0 and pixels > settings.max_image_pixels:
                raise CloudError("IMAGE_TOO_LARGE", "Image pixel count exceeds the supported limit", 422)
            image = ImageOps.exif_transpose(source)
            if rotation_degrees:
                image = image.rotate(-rotation_degrees, expand=True)
            width, height = image.size
            image = image.convert("RGB")
            bgr = cv2.cvtColor(np.asarray(image), cv2.COLOR_RGB2BGR)
    except (Image.DecompressionBombError, UnidentifiedImageError, OSError, ValueError) as exc:
        raise CloudError("INVALID_IMAGE", f"Image decode failed: {exc}", 422) from exc
    return DecodedImage(bgr=bgr, sha256=hashlib.sha256(image_bytes).hexdigest(), image_format=decoded_format.lower(), width=width, height=height)


class AnalysisService:
    def __init__(
        self,
        settings: Settings,
        store: SQLiteStore,
        profile: RuleProfile,
        region_detector: RegionDetector | None,
        tile_detector: TileDetector | None,
        readiness_errors: list[str] | None = None,
    ) -> None:
        self.settings = settings
        self.store = store
        self.profile = profile
        self.region_detector = region_detector
        self.tile_detector = tile_detector
        self.readiness_errors = readiness_errors or []
        self.retention = RetentionManager(settings, store, self.debug_image_dir())

    @classmethod
    def from_settings(cls, settings: Settings) -> "AnalysisService":
        rule_path = Path(__file__).parent / "rules" / "guangdong_mvp_v1.json"
        profile = load_rule_profile(rule_path)
        errors: list[str] = []
        region_detector: RegionDetector | None = None
        tile_detector: TileDetector | None = None
        try:
            tile_detector = create_tile_detector(settings.tile_manifest_path, allowed_tiles=set(profile.tile_set))
        except Exception as exc:
            errors.append(str(exc))
        try:
            if settings.dev_full_image_hand_region:
                region_detector = FullImageHandRegionDetector()
            else:
                if not settings.region_manifest_path:
                    raise ModelNotReadyError("CLOUD_REGION_MANIFEST is not configured")
                region_detector = create_region_detector(settings.region_manifest_path)
        except Exception as exc:
            errors.append(str(exc))
        return cls(settings, SQLiteStore(settings.database_path), profile, region_detector, tile_detector, errors)

    def readiness(self) -> tuple[bool, list[str]]:
        errors = list(self.readiness_errors)
        if not self.settings.api_key:
            errors.append("CLOUD_API_KEY is not configured")
        if not self.region_detector:
            errors.append("Region detector is unavailable")
        if not self.tile_detector:
            errors.append("Tile detector is unavailable")
        return not errors, errors

    def create_session(self, rule_profile_id: str, recording_mode: str = "normal") -> str:
        if rule_profile_id != self.profile.rule_profile_id:
            raise CloudError("RULE_PROFILE_NOT_SUPPORTED", f"Unsupported rule profile: {rule_profile_id}", 422)
        if recording_mode not in {"normal", "regression"}:
            raise CloudError("INVALID_RECORDING_MODE", "recording_mode must be normal or regression", 422)
        if recording_mode == "regression":
            if self.settings.runtime_env != "test":
                raise CloudError(
                    "REGRESSION_RECORDING_TEST_ONLY",
                    "Regression recording is available only in the test environment",
                    409,
                )
            if not self.settings.session_recording_enabled:
                raise CloudError("REGRESSION_RECORDING_DISABLED", "Regression recording is disabled", 409)
            self.retention.prepare_for_session_recording(self.settings.session_recording_max_bytes)
        return self.store.create_session(
            rule_profile_id,
            recording_mode=recording_mode,
            recording_retention_days=self.settings.session_recording_retention_days,
            recording_max_active=self.settings.session_recording_max_active,
        )

    def end_session(self, session_id: str) -> dict[str, Any] | None:
        return self.store.end_session_and_finalize_recording(
            session_id,
            self.settings.session_recording_retention_days,
        )

    def debug_image_dir(self) -> Path:
        return self.settings.debug_image_dir or (self.settings.database_path.parent / "debug_images")

    def debug_image_path(self, analysis_id: str) -> Path:
        return self.debug_image_dir() / f"{analysis_id}.png"

    def session_recording_dir(self, session_id: str) -> Path:
        return self.retention.recording_session_dir(session_id)

    @staticmethod
    def _recording_extension(content_type: str) -> str:
        return "png" if content_type == "image/png" else "jpg"

    def _store_regression_frame(
        self,
        *,
        session_id: str,
        analysis_id: str,
        request_id: str,
        sequence_id: int,
        frame_ts: int,
        decoded: DecodedImage,
        original_image_bytes: bytes | None,
        content_type: str | None,
        rotation_degrees: int,
    ) -> None:
        frame_path: Path | None = None
        temporary_path: Path | None = None
        try:
            recording = self.store.get_session_recording(session_id)
            if not recording or recording.get("status") != "active":
                return
            if not original_image_bytes or content_type not in {"image/png", "image/jpeg"}:
                self.store.mark_session_recording_incomplete(session_id, "RECORDING_SOURCE_BYTES_UNAVAILABLE")
                return
            if int(recording.get("total_bytes", 0)) + len(original_image_bytes) > self.settings.session_recording_max_bytes:
                self.store.mark_session_recording_incomplete(session_id, "RECORDING_SESSION_CAPACITY_EXCEEDED")
                return
            session_dir = self.session_recording_dir(session_id)
            frame_path = session_dir / "frames" / f"{sequence_id:06d}.{self._recording_extension(content_type)}"
            temporary_path = frame_path.with_name(f".{frame_path.name}.tmp")
            frame_path.parent.mkdir(parents=True, exist_ok=True)
            free_bytes = shutil.disk_usage(session_dir).free
            if free_bytes - len(original_image_bytes) < self.settings.storage_min_free_bytes:
                self.store.mark_session_recording_incomplete(session_id, "RECORDING_STORAGE_MIN_FREE_SPACE_REACHED")
                return
            temporary_path.write_bytes(original_image_bytes)
            temporary_path.replace(frame_path)
            stored = self.store.record_regression_frame(
                session_id,
                analysis_id,
                request_id,
                sequence_id,
                content_type=content_type,
                rotation_degrees=rotation_degrees,
                frame_ts=frame_ts,
                sha256=decoded.sha256,
                size_bytes=len(original_image_bytes),
                source_path=frame_path,
                max_bytes=self.settings.session_recording_max_bytes,
            )
            if not stored:
                frame_path.unlink(missing_ok=True)
        except Exception:
            try:
                if temporary_path is not None:
                    temporary_path.unlink(missing_ok=True)
                if frame_path is not None:
                    frame_path.unlink(missing_ok=True)
            except OSError:
                pass
            try:
                self.store.mark_session_recording_incomplete(session_id, "RECORDING_FRAME_WRITE_FAILED")
            except Exception:
                # Recording is strictly best-effort and must never turn a
                # successful analysis into an HTTP 5xx.
                pass

        """Run maintenance outside an analysis request and return a safe report."""
    def run_retention_cleanup_once(self) -> dict[str, Any]:
        return self.retention.run_once().as_dict()

    def create_evidence(
        self,
        analysis_id: str,
        source: str = "manual",
        note: str | None = None,
        *,
        signature: str | None = None,
    ) -> dict[str, Any] | None:
        if not self.settings.evidence_enabled:
            raise CloudError("EVIDENCE_DISABLED", "Evidence capture is disabled", 409)
        if source == "automatic":
            asset = self.store.get_debug_asset(analysis_id)
            incoming_bytes = int(asset.get("size_bytes", 0)) if asset else 0
            if self.store.active_evidence_asset_bytes() + incoming_bytes > self.settings.evidence_max_bytes:
                return None
        evidence = self.store.create_or_get_evidence(
            analysis_id,
            source=source,
            note=note,
            retention_days=self.settings.evidence_retention_days,
            signature=signature,
            max_per_signature_per_hour=(
                self.settings.evidence_max_per_signature_per_hour if source == "automatic" else None
            ),
        )
        if evidence and source == "manual":
            self._make_room_for_manual_evidence()
        return evidence

    def _make_room_for_manual_evidence(self) -> None:
        while self.store.active_evidence_asset_bytes() > self.settings.evidence_max_bytes:
            if not self.store.evict_oldest_automatic_evidence(limit=1):
                return

    def _automatic_evidence_signature(self, category: str) -> str:
        return ":".join(
            [
                category,
                str(getattr(self.region_detector, "model_version", "unknown")),
                str(getattr(self.tile_detector, "model_version", "unknown")),
            ]
        )

    def _create_automatic_evidence(self, analysis_id: str, category: str) -> None:
        if not self.settings.evidence_enabled:
            return
        asset = self.store.get_debug_asset(analysis_id)
        incoming_bytes = int(asset.get("size_bytes", 0)) if asset else 0
        if self.store.active_evidence_asset_bytes() + incoming_bytes > self.settings.evidence_max_bytes:
            return
        try:
            self.create_evidence(
                analysis_id,
                source="automatic",
                signature=self._automatic_evidence_signature(category),
            )
        except Exception:
            # Evidence must never change a diagnostic/analysis outcome. The
            # scheduled retention report and normal logs remain the observability
            # path if SQLite is temporarily unavailable.
            return

    @staticmethod
    def _record_algorithm_total(timings: dict[str, float]) -> None:
        algorithm_keys = ("region_detection", "tile_detection", "structure", "rule_engine")
        algorithm_total = round(sum(timings.get(key, 0.0) for key in algorithm_keys), 2)
        timings["algorithm_total"] = algorithm_total
        timings["total"] = algorithm_total

    def _complete_started_analysis(self, analysis_id: str, result: dict[str, Any], timings: dict[str, float]) -> None:
        start = time.perf_counter()
        self.store.complete_analysis(analysis_id, result)
        timings["db_complete_analysis"] = round((time.perf_counter() - start) * 1000, 2)
        self.store.update_analysis_result(analysis_id, result)

    def _store_debug_image(
        self,
        analysis_id: str,
        decoded: DecodedImage,
        timings: dict[str, float],
        *,
        force: bool = False,
        automatic_evidence_category: str | None = None,
    ) -> dict[str, Any]:
        should_store, reason = self.store.should_store_debug_image(
            analysis_id,
            mode=self.settings.debug_image_mode,
            sample_interval_seconds=self.settings.debug_image_success_sample_interval_seconds,
            force=force,
        )
        if not should_store:
            self.store.record_debug_asset(analysis_id, None, status="not_stored", reason=reason)
            timings["debug_image_write"] = 0.0
            return {"stored": False, "reason": reason}
        start = time.perf_counter()
        try:
            encoded_ok, encoded_image = cv2.imencode(".png", decoded.bgr)
            image_bytes = encoded_image.tobytes() if encoded_ok else b""
        except Exception:
            image_bytes = b""
        if not image_bytes:
            timings["debug_image_write"] = round((time.perf_counter() - start) * 1000, 2)
            self.store.record_debug_asset(analysis_id, None, status="not_stored", reason="DEBUG_IMAGE_WRITE_FAILED")
            return {"stored": False, "reason": "DEBUG_IMAGE_WRITE_FAILED"}
        has_capacity, capacity_reason = self.retention.can_store_debug_image(len(image_bytes))
        if not has_capacity:
            timings["debug_image_write"] = round((time.perf_counter() - start) * 1000, 2)
            self.store.record_debug_asset(analysis_id, None, status="not_stored", reason=capacity_reason)
            return {"stored": False, "reason": capacity_reason}
        image_path = self.debug_image_path(analysis_id)
        temporary_path = image_path.with_name(f".{analysis_id}.tmp")
        try:
            image_path.parent.mkdir(parents=True, exist_ok=True)
            temporary_path.write_bytes(image_bytes)
            temporary_path.replace(image_path)
            stored = True
        except Exception:
            try:
                temporary_path.unlink(missing_ok=True)
            except OSError:
                pass
            stored = False
        timings["debug_image_write"] = round((time.perf_counter() - start) * 1000, 2)
        if not stored:
            self.store.record_debug_asset(analysis_id, None, status="not_stored", reason="DEBUG_IMAGE_WRITE_FAILED")
            return {"stored": False, "reason": "DEBUG_IMAGE_WRITE_FAILED"}
        try:
            self.store.record_debug_asset(analysis_id, image_path, len(image_bytes))
        except Exception:
            # Preserve a completed analysis even if SQLite metadata maintenance is
            # temporarily unavailable; remove the untracked file to avoid leaks.
            try:
                image_path.unlink(missing_ok=True)
            except OSError:
                pass
            return {"stored": False, "reason": "DEBUG_IMAGE_METADATA_WRITE_FAILED"}
        if automatic_evidence_category:
            self._create_automatic_evidence(analysis_id, automatic_evidence_category)
        return {"stored": True, "format": "png", "url": f"/debug/analyses/{analysis_id}/image"}

    def _capture_failure_debug_image(
        self, analysis_id: str, decoded: DecodedImage, timings: dict[str, float], error: CloudError
    ) -> dict[str, Any]:
        return self._store_debug_image(
            analysis_id,
            decoded,
            timings,
            force=True,
            automatic_evidence_category=f"error:{error.code}",
        )

    def _regions_payload(
        self,
        regions: list[DetectedRegion],
        padded_regions: list[DetectedRegion] | None = None,
    ) -> list[dict[str, Any]]:
        padded_by_id = {region.region_id: region for region in (padded_regions or [])}
        payload: list[dict[str, Any]] = []
        for region in regions:
            item = region.as_dict()
            padded = padded_by_id.get(region.region_id)
            if padded is not None:
                item["roi_bbox"] = padded.bbox.as_dict()
            payload.append(item)
        return payload

    def _diagnostic(
        self,
        analysis_id: str,
        decoded: DecodedImage,
        regions: list[DetectedRegion],
        structured: StructuredHand | None,
        timings: dict[str, float],
        frame_ts: int,
        diagnostic_code: str,
        padded_regions: list[DetectedRegion] | None = None,
    ) -> dict[str, Any]:
        accepted = structured.accepted_detections if structured else []
        result = {
            "analysis_id": analysis_id,
            "status": "diagnostic",
            "diagnostic_code": diagnostic_code,
            "regions": self._regions_payload(regions, padded_regions),
            "tile_detections": [item.as_dict() for item in accepted],
            "hand_tiles": structured.hand_tiles if structured else [],
            "meld_groups": structured.meld_groups if structured else [],
            "bonus_tiles": structured.bonus_tiles if structured else [],
            "suggestion": None,
            "confidence": {"region": 0.0, "recognition": 0.0, "structure": 0.0, "suggestion": 0.0, "reasons": [diagnostic_code]},
            "model_versions": {"region": getattr(self.region_detector, "model_version", None), "tile": getattr(self.tile_detector, "model_version", None)},
            "rule_profile": {"id": self.profile.rule_profile_id, "version": self.profile.version, "algorithm_version": self.profile.algorithm_version},
            "image": {"sha256": decoded.sha256, "format": decoded.image_format, "width": decoded.width, "height": decoded.height, "frame_ts": frame_ts},
            "timings_ms": timings,
        }
        debug_image = self._store_debug_image(
            analysis_id,
            decoded,
            timings,
            force=True,
            automatic_evidence_category=f"diagnostic:{diagnostic_code}",
        )
        result["debug_image"] = debug_image
        return result

    def analyze(
        self,
        session_id: str,
        request_id: str,
        sequence_id: int,
        decoded: DecodedImage,
        frame_ts: int = 0,
        timings: dict[str, float] | None = None,
        *,
        original_image_bytes: bytes | None = None,
        content_type: str | None = None,
        rotation_degrees: int = 0,
    ) -> dict[str, Any]:
        ready, errors = self.readiness()
        if not ready:
            raise ModelNotReadyError("; ".join(errors))
        timings = timings if timings is not None else {}
        image_meta = {"sha256": decoded.sha256, "format": decoded.image_format, "width": decoded.width, "height": decoded.height, "frame_ts": frame_ts}
        start = time.perf_counter()
        analysis_id, existing = self.store.start_analysis(session_id, request_id, sequence_id, image_meta)
        timings["db_start_analysis"] = round((time.perf_counter() - start) * 1000, 2)
        if existing:
            return existing

        self._store_regression_frame(
            session_id=session_id,
            analysis_id=analysis_id,
            request_id=request_id,
            sequence_id=sequence_id,
            frame_ts=frame_ts,
            decoded=decoded,
            original_image_bytes=original_image_bytes,
            content_type=content_type,
            rotation_degrees=rotation_degrees,
        )

        try:
            return self._analyze_started(analysis_id, decoded, frame_ts, timings)
        except CloudError as exc:
            debug_image = self._capture_failure_debug_image(analysis_id, decoded, timings, exc)
            self.store.fail_analysis(analysis_id, exc, debug_image=debug_image)
            raise
        except Exception as exc:
            failure = CloudError("ANALYSIS_FAILED", "Analysis failed", 500)
            debug_image = self._capture_failure_debug_image(analysis_id, decoded, timings, failure)
            self.store.fail_analysis(analysis_id, failure, debug_image=debug_image)
            raise failure from exc

    def _analyze_started(self, analysis_id: str, decoded: DecodedImage, frame_ts: int, timings: dict[str, float]) -> dict[str, Any]:
        start = time.perf_counter()
        regions = self.region_detector.detect(decoded.bgr)  # type: ignore[union-attr]
        timings["region_detection"] = round((time.perf_counter() - start) * 1000, 2)
        active_regions = regions
        if len(active_regions) > self.settings.max_regions_per_frame:
            self._record_algorithm_total(timings)
            result = self._diagnostic(analysis_id, decoded, regions, None, timings, frame_ts, "TOO_MANY_REGIONS")
            self._complete_started_analysis(analysis_id, result, timings)
            return result
        if not any(region.region_type == "hand" for region in active_regions):
            self._record_algorithm_total(timings)
            result = self._diagnostic(analysis_id, decoded, regions, None, timings, frame_ts, "HAND_REGION_NOT_FOUND")
            self._complete_started_analysis(analysis_id, result, timings)
            return result

        padded_regions = [
            DetectedRegion(
                region.region_id,
                region.region_type,
                region.bbox.expand(
                    region.bbox.padding_px(self.settings.roi_padding_ratio, self.settings.roi_padding_px),
                    decoded.width,
                    decoded.height,
                ),
                region.confidence,
                region.model_version,
            )
            for region in active_regions
        ]
        start = time.perf_counter()
        detections = self.tile_detector.detect_batch(decoded.bgr, padded_regions)  # type: ignore[union-attr]
        timings["tile_detection"] = round((time.perf_counter() - start) * 1000, 2)
        detections = keep_detections_in_source_regions(detections, active_regions)
        start = time.perf_counter()
        structured = structure_tiles(active_regions, detections, self.settings.dedup_iou_threshold, self.settings.assignment_margin)
        timings["structure"] = round((time.perf_counter() - start) * 1000, 2)
        if structured.diagnostic_code:
            self._record_algorithm_total(timings)
            result = self._diagnostic(
                analysis_id, decoded, regions, structured, timings, frame_ts, structured.diagnostic_code, padded_regions
            )
            self._complete_started_analysis(analysis_id, result, timings)
            return result

        start = time.perf_counter()
        try:
            rule_result = GuangdongMvpRuleEngine(self.profile).analyze(structured)
        except UnsupportedMeldError:
            timings["rule_engine"] = round((time.perf_counter() - start) * 1000, 2)
            self._record_algorithm_total(timings)
            result = self._diagnostic(
                analysis_id, decoded, regions, structured, timings, frame_ts, "UNSUPPORTED_MELD_TYPE", padded_regions
            )
            self._complete_started_analysis(analysis_id, result, timings)
            return result
        timings["rule_engine"] = round((time.perf_counter() - start) * 1000, 2)
        basic = [item for item in structured.accepted_detections if item.tile not in structured.bonus_tiles]
        tile_scores = sorted(item.tile_confidence for item in basic)
        region_scores = [item.region_confidence for item in basic]
        p10_index = max(0, int(len(tile_scores) * 0.1) - 1)
        recognition_confidence = round(0.6 * (sum(tile_scores) / len(tile_scores)) + 0.4 * tile_scores[p10_index], 4)
        region_confidence = round(sum(region_scores) / len(region_scores), 4)
        input_quality = min(region_confidence, recognition_confidence)
        suggestion_confidence = round(input_quality * rule_result["decision_margin"], 4)
        confidence_reasons = []
        if input_quality < 0.6:
            confidence_reasons.append("LOW_INPUT_CONFIDENCE")
        if rule_result["decision_margin"] < 0.5:
            confidence_reasons.append("LOW_DECISION_SEPARATION")
        self._record_algorithm_total(timings)
        result = {
            "analysis_id": analysis_id,
            "status": "complete",
            "diagnostic_code": None,
            "regions": self._regions_payload(regions, padded_regions),
            "tile_detections": [item.as_dict() for item in structured.accepted_detections],
            "has_low_confidence_hand_tile_detection": any(
                item.region_type == "hand" and item.tile_confidence < 0.70
                for item in detections
            ),
            "hand_tiles": structured.hand_tiles,
            "meld_groups": structured.meld_groups,
            "bonus_tiles": structured.bonus_tiles,
            "suggestion": rule_result,
            "confidence": {
                "region": region_confidence,
                "recognition": recognition_confidence,
                "structure": 1.0,
                "suggestion": suggestion_confidence,
                "reasons": confidence_reasons,
            },
            "model_versions": {"region": self.region_detector.model_version, "tile": self.tile_detector.model_version},  # type: ignore[union-attr]
            "rule_profile": {"id": self.profile.rule_profile_id, "version": self.profile.version, "algorithm_version": self.profile.algorithm_version},
            "image": {"sha256": decoded.sha256, "format": decoded.image_format, "width": decoded.width, "height": decoded.height, "frame_ts": frame_ts},
            "timings_ms": timings,
        }
        debug_image = self._store_debug_image(analysis_id, decoded, timings)
        if debug_image is not None:
            result["debug_image"] = debug_image
        self._complete_started_analysis(analysis_id, result, timings)
        return result
