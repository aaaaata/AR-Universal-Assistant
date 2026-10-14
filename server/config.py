from __future__ import annotations

import os
from dataclasses import dataclass
from pathlib import Path


def _int_from_env(name: str, default: int) -> int:
    raw = os.getenv(name)
    return default if raw in {None, ""} else int(raw)


def _bool_from_env(name: str, default: bool) -> bool:
    raw = os.getenv(name)
    if raw in {None, ""}:
        return default
    normalized = raw.strip().lower()
    if normalized in {"1", "true", "yes", "on"}:
        return True
    if normalized in {"0", "false", "no", "off"}:
        return False
    raise ValueError(f"{name} must be a boolean value")


def _runtime_env_from_env() -> str:
    # Keep local as the import-safe default for direct Python/test usage. The
    # checked-in templates set this explicitly, and invalid supplied values must
    # never silently select a profile.
    value = os.getenv("CLOUD_RUNTIME_ENV", "local").strip().lower()
    if value not in {"local", "test"}:
        raise ValueError("CLOUD_RUNTIME_ENV must be either 'local' or 'test'")
    return value


def _profile_defaults(runtime_env: str) -> dict[str, int | str]:
    mib = 1024 * 1024
    gib = 1024 * mib
    if runtime_env == "test":
        return {
            "debug_image_mode": "sampled",
            "debug_image_success_sample_interval_seconds": 30,
            "debug_image_retention_days": 3,
            "debug_image_max_bytes": 3 * gib,
            "analysis_retention_days": 7,
            "sqlite_max_bytes": 512 * mib,
            "evidence_retention_days": 14,
            "evidence_max_bytes": 2 * gib,
            "storage_max_bytes": 6 * gib,
            "storage_min_free_bytes": 1 * gib,
            "evidence_slow_route_threshold_ms": 5000,
            "session_recording_max_bytes": 8 * gib,
            "session_recording_retention_days": 1,
            "session_recording_max_active": 1,
        }
    return {
        "debug_image_mode": "always",
        "debug_image_success_sample_interval_seconds": 0,
        "debug_image_retention_days": 1,
        "debug_image_max_bytes": 1 * gib,
        "analysis_retention_days": 3,
        "sqlite_max_bytes": 128 * mib,
        "evidence_retention_days": 3,
        "evidence_max_bytes": 512 * mib,
        "storage_max_bytes": 2 * gib,
        "storage_min_free_bytes": 512 * mib,
        "evidence_slow_route_threshold_ms": 0,
        "session_recording_max_bytes": 8 * gib,
        "session_recording_retention_days": 1,
        "session_recording_max_active": 1,
    }


def _positive_int_from_env(name: str, default: int, *, allow_zero: bool = False) -> int:
    value = _int_from_env(name, default)
    if value < 0 or (value == 0 and not allow_zero):
        comparator = "non-negative" if allow_zero else "positive"
        raise ValueError(f"{name} must be {comparator}")
    return value


def _non_negative_float_from_env(name: str, default: float) -> float:
    raw = os.getenv(name)
    value = default if raw in {None, ""} else float(raw)
    if value < 0:
        raise ValueError(f"{name} must be non-negative")
    return value


@dataclass(frozen=True)
class Settings:
    api_key: str
    database_path: Path
    tile_manifest_path: Path
    region_manifest_path: Path | None
    debug_image_dir: Path | None = None
    dev_full_image_hand_region: bool = False
    max_upload_bytes: int = 10 * 1024 * 1024
    min_image_width: int = 640
    min_image_height: int = 360
    max_image_pixels: int = 8_000_000
    max_regions_per_frame: int = 24
    roi_padding_ratio: float = 0.05
    roi_padding_px: float = 8
    dedup_iou_threshold: float = 0.50
    assignment_margin: float = 0.10
    analysis_concurrency: int = 1
    analysis_queue_timeout_ms: int = 200
    analysis_exec_timeout_ms: int = 0
    runtime_env: str = "local"
    service_version: str = "unknown"
    retention_enabled: bool = True
    retention_cleanup_interval_minutes: int = 30
    storage_max_bytes: int = 2 * 1024 * 1024 * 1024
    storage_min_free_bytes: int = 512 * 1024 * 1024
    debug_image_mode: str = "always"
    debug_image_success_sample_interval_seconds: int = 0
    debug_image_retention_days: int = 1
    debug_image_max_bytes: int = 1024 * 1024 * 1024
    analysis_retention_days: int = 3
    sqlite_max_bytes: int = 128 * 1024 * 1024
    pending_analysis_stale_minutes: int = 30
    evidence_enabled: bool = True
    evidence_dir: Path | None = None
    evidence_retention_days: int = 3
    evidence_max_bytes: int = 512 * 1024 * 1024
    evidence_max_per_signature_per_hour: int = 5
    evidence_slow_route_threshold_ms: int = 0
    session_recording_enabled: bool = True
    session_recording_dir: Path | None = None
    session_recording_max_bytes: int = 8 * 1024 * 1024 * 1024
    session_recording_retention_days: int = 1
    session_recording_max_active: int = 1

    @classmethod
    def from_env(cls) -> "Settings":
        repo_root = Path(__file__).resolve().parents[1]
        runtime_env = _runtime_env_from_env()
        defaults = _profile_defaults(runtime_env)
        debug_image_dir = Path(os.getenv("CLOUD_DEBUG_IMAGE_DIR", str(repo_root / "server" / "data" / "debug_images")))
        debug_image_mode = os.getenv("CLOUD_DEBUG_IMAGE_MODE", str(defaults["debug_image_mode"])).strip().lower()
        if debug_image_mode not in {"always", "sampled", "diagnostic", "off"}:
            raise ValueError("CLOUD_DEBUG_IMAGE_MODE must be one of: always, sampled, diagnostic, off")
        return cls(
            api_key=os.getenv("CLOUD_API_KEY", ""),
            database_path=Path(os.getenv("CLOUD_DATABASE_PATH", str(repo_root / "server" / "data" / "server.db"))),
            tile_manifest_path=Path(os.getenv("CLOUD_TILE_MANIFEST", str(repo_root / "server" / "models" / "tile.manifest.json"))),
            region_manifest_path=(Path(os.environ["CLOUD_REGION_MANIFEST"]) if os.getenv("CLOUD_REGION_MANIFEST") else None),
            debug_image_dir=debug_image_dir,
            dev_full_image_hand_region=os.getenv("CLOUD_DEV_FULL_IMAGE_HAND_REGION", "").lower() in {"1", "true", "yes", "on"},
            max_upload_bytes=_int_from_env("CLOUD_MAX_UPLOAD_BYTES", 10 * 1024 * 1024),
            min_image_width=_int_from_env("CLOUD_MIN_IMAGE_WIDTH", 640),
            min_image_height=_int_from_env("CLOUD_MIN_IMAGE_HEIGHT", 360),
            max_image_pixels=_int_from_env("CLOUD_MAX_IMAGE_PIXELS", 8_000_000),
            roi_padding_ratio=_non_negative_float_from_env("CLOUD_ROI_PADDING_RATIO", 0.05),
            roi_padding_px=_non_negative_float_from_env("CLOUD_ROI_PADDING_PX", 8),
            analysis_concurrency=max(1, _int_from_env("CLOUD_ANALYSIS_CONCURRENCY", 1)),
            analysis_queue_timeout_ms=max(0, _int_from_env("CLOUD_ANALYSIS_QUEUE_TIMEOUT_MS", 200)),
            analysis_exec_timeout_ms=max(0, _int_from_env("CLOUD_ANALYSIS_EXEC_TIMEOUT_MS", 0)),
            runtime_env=runtime_env,
            service_version=os.getenv("CLOUD_SERVICE_VERSION", "unknown").strip() or "unknown",
            retention_enabled=_bool_from_env("CLOUD_RETENTION_ENABLED", True),
            retention_cleanup_interval_minutes=_positive_int_from_env("CLOUD_RETENTION_CLEANUP_INTERVAL_MINUTES", 30),
            storage_max_bytes=_positive_int_from_env("CLOUD_STORAGE_MAX_BYTES", int(defaults["storage_max_bytes"])),
            storage_min_free_bytes=_positive_int_from_env("CLOUD_STORAGE_MIN_FREE_BYTES", int(defaults["storage_min_free_bytes"]), allow_zero=True),
            debug_image_mode=debug_image_mode,
            debug_image_success_sample_interval_seconds=_positive_int_from_env(
                "CLOUD_DEBUG_IMAGE_SUCCESS_SAMPLE_INTERVAL_SECONDS",
                int(defaults["debug_image_success_sample_interval_seconds"]),
                allow_zero=True,
            ),
            debug_image_retention_days=_positive_int_from_env("CLOUD_DEBUG_IMAGE_RETENTION_DAYS", int(defaults["debug_image_retention_days"])),
            debug_image_max_bytes=_positive_int_from_env("CLOUD_DEBUG_IMAGE_MAX_BYTES", int(defaults["debug_image_max_bytes"])),
            analysis_retention_days=_positive_int_from_env("CLOUD_ANALYSIS_RETENTION_DAYS", int(defaults["analysis_retention_days"])),
            sqlite_max_bytes=_positive_int_from_env("CLOUD_SQLITE_MAX_BYTES", int(defaults["sqlite_max_bytes"])),
            pending_analysis_stale_minutes=_positive_int_from_env("CLOUD_PENDING_ANALYSIS_STALE_MINUTES", 30),
            evidence_enabled=_bool_from_env("CLOUD_EVIDENCE_ENABLED", True),
            evidence_dir=Path(os.getenv("CLOUD_EVIDENCE_DIR", str(debug_image_dir.parent / "evidence"))),
            evidence_retention_days=_positive_int_from_env("CLOUD_EVIDENCE_RETENTION_DAYS", int(defaults["evidence_retention_days"])),
            evidence_max_bytes=_positive_int_from_env("CLOUD_EVIDENCE_MAX_BYTES", int(defaults["evidence_max_bytes"])),
            evidence_max_per_signature_per_hour=_positive_int_from_env("CLOUD_EVIDENCE_MAX_PER_SIGNATURE_PER_HOUR", 5),
            evidence_slow_route_threshold_ms=_positive_int_from_env(
                "CLOUD_EVIDENCE_SLOW_ROUTE_THRESHOLD_MS",
                int(defaults["evidence_slow_route_threshold_ms"]),
                allow_zero=True,
            ),
            session_recording_enabled=_bool_from_env("CLOUD_SESSION_RECORDING_ENABLED", True),
            session_recording_dir=Path(
                os.getenv("CLOUD_SESSION_RECORDING_DIR", str(debug_image_dir.parent / "session_recordings"))
            ),
            session_recording_max_bytes=_positive_int_from_env(
                "CLOUD_SESSION_RECORDING_MAX_BYTES", int(defaults["session_recording_max_bytes"])
            ),
            session_recording_retention_days=_positive_int_from_env(
                "CLOUD_SESSION_RECORDING_RETENTION_DAYS", int(defaults["session_recording_retention_days"])
            ),
            session_recording_max_active=_positive_int_from_env(
                "CLOUD_SESSION_RECORDING_MAX_ACTIVE", int(defaults["session_recording_max_active"])
            ),
        )
