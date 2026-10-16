from __future__ import annotations

from dataclasses import replace
from pathlib import Path
from types import SimpleNamespace

import numpy as np
import pytest

from cloud.config import Settings
from cloud.errors import CloudError
from cloud.retention import RetentionManager
from cloud.service import AnalysisService, DecodedImage
from cloud.storage import SQLiteStore


PROFILE_OVERRIDE_NAMES = (
    "CLOUD_DEBUG_IMAGE_MODE",
    "CLOUD_DEBUG_IMAGE_SUCCESS_SAMPLE_INTERVAL_SECONDS",
    "CLOUD_DEBUG_IMAGE_RETENTION_DAYS",
    "CLOUD_DEBUG_IMAGE_MAX_BYTES",
    "CLOUD_ANALYSIS_RETENTION_DAYS",
    "CLOUD_SQLITE_MAX_BYTES",
    "CLOUD_EVIDENCE_RETENTION_DAYS",
    "CLOUD_EVIDENCE_MAX_BYTES",
    "CLOUD_STORAGE_MAX_BYTES",
    "CLOUD_STORAGE_MIN_FREE_BYTES",
    "CLOUD_EVIDENCE_SLOW_ROUTE_THRESHOLD_MS",
)


def _service_with_settings(service: AnalysisService, settings: Settings) -> AnalysisService:
    return AnalysisService(
        settings=settings,
        store=SQLiteStore(settings.database_path),
        profile=service.profile,
        region_detector=service.region_detector,
        tile_detector=service.tile_detector,
    )


def _decoded_image() -> DecodedImage:
    return DecodedImage(
        bgr=np.zeros((120, 640, 3), dtype=np.uint8),
        sha256="retention-test-image",
        image_format="png",
        width=640,
        height=120,
    )


def _complete_analysis(service: AnalysisService, request_id: str, sequence_id: int = 1) -> tuple[str, str, dict]:
    session_id = service.create_session("guangdong_mvp_v1")
    result = service.analyze(session_id, request_id, sequence_id, _decoded_image())
    return session_id, result["analysis_id"], result


def test_asset_path_prefers_stored_image_path(settings: Settings, tmp_path: Path):
    manager = RetentionManager(settings, SQLiteStore(settings.database_path), tmp_path / "images")
    stored_path = tmp_path / "images" / "custom.png"

    assert manager._asset_path({"analysis_id": "analysis-1", "image_path": str(stored_path)}) == stored_path
    assert manager._asset_path({"analysis_id": "analysis-2", "image_path": None}) == (
        tmp_path / "images" / "analysis-2.png"
    )


def test_runtime_env_profile_defaults_and_explicit_override(monkeypatch: pytest.MonkeyPatch):
    for name in PROFILE_OVERRIDE_NAMES:
        monkeypatch.delenv(name, raising=False)
    monkeypatch.setenv("CLOUD_RUNTIME_ENV", "test")

    settings = Settings.from_env()

    assert settings.runtime_env == "test"
    assert settings.debug_image_mode == "sampled"
    assert settings.debug_image_success_sample_interval_seconds == 30
    assert settings.analysis_retention_days == 7
    assert settings.storage_max_bytes == 6 * 1024 * 1024 * 1024
    assert settings.session_recording_max_bytes == 8 * 1024 * 1024 * 1024
    assert settings.session_recording_retention_days == 1
    assert settings.session_recording_max_active == 1

    monkeypatch.setenv("CLOUD_DEBUG_IMAGE_MAX_BYTES", "123")
    assert Settings.from_env().debug_image_max_bytes == 123


def test_runtime_env_rejects_unknown_profile(monkeypatch: pytest.MonkeyPatch):
    monkeypatch.setenv("CLOUD_RUNTIME_ENV", "staging")

    with pytest.raises(ValueError, match="CLOUD_RUNTIME_ENV"):
        Settings.from_env()


def test_storage_state_reports_recordings_and_export_temps_separately(settings: Settings, tmp_path: Path):
    debug_dir = tmp_path / "debug"
    evidence_dir = tmp_path / "evidence"
    recording_dir = tmp_path / "recordings"
    debug_dir.mkdir()
    evidence_dir.mkdir()
    (recording_dir / "session-1" / "frames").mkdir(parents=True)
    (debug_dir / "normal.png").write_bytes(b"d" * 3)
    (evidence_dir / "frozen.bin").write_bytes(b"e" * 5)
    (evidence_dir / "ar-mahjong-session-regression-orphan.zip").write_bytes(b"x" * 7)
    (recording_dir / "session-1" / "frames" / "000001.png").write_bytes(b"r" * 11)
    configured = replace(
        settings,
        database_path=tmp_path / "cloud.db",
        debug_image_dir=debug_dir,
        evidence_dir=evidence_dir,
        session_recording_dir=recording_dir,
        storage_min_free_bytes=0,
    )
    manager = RetentionManager(configured, SQLiteStore(configured.database_path), debug_dir)

    state = manager.storage_state()

    assert state["debug_image_bytes"] == 3
    assert state["evidence_bytes"] == 5
    assert state["session_recording_bytes"] == 11
    assert state["export_temp_bytes"] == 7
    assert state["sqlite_bytes"] > 0
    assert state["ordinary_storage_bytes"] == 3 + 5 + 7 + state["sqlite_bytes"]
    assert state["actual_directory_bytes"] == 3 + 5 + 7 + 11


def test_recording_preflight_allows_exact_min_free_boundary(
    settings: Settings, tmp_path: Path, monkeypatch: pytest.MonkeyPatch
):
    configured = replace(
        settings,
        database_path=tmp_path / "cloud.db",
        debug_image_dir=tmp_path / "debug",
        evidence_dir=tmp_path / "evidence",
        session_recording_dir=tmp_path / "recordings",
        storage_min_free_bytes=100,
    )
    manager = RetentionManager(configured, SQLiteStore(configured.database_path), configured.debug_image_dir)
    monkeypatch.setattr(
        "cloud.retention.shutil.disk_usage",
        lambda _: SimpleNamespace(total=10_000, used=8_900, free=1_100),
    )

    manager.prepare_for_session_recording(1_000)


def test_recording_preflight_rejects_without_false_reservation(
    settings: Settings, tmp_path: Path, monkeypatch: pytest.MonkeyPatch
):
    configured = replace(
        settings,
        database_path=tmp_path / "cloud.db",
        debug_image_dir=tmp_path / "debug",
        evidence_dir=tmp_path / "evidence",
        session_recording_dir=tmp_path / "recordings",
        storage_min_free_bytes=100,
    )
    store = SQLiteStore(configured.database_path)
    manager = RetentionManager(configured, store, configured.debug_image_dir)
    monkeypatch.setattr(
        "cloud.retention.shutil.disk_usage",
        lambda _: SimpleNamespace(total=10_000, used=9_001, free=999),
    )

    with pytest.raises(CloudError) as exc:
        manager.prepare_for_session_recording(900)

    assert exc.value.code == "REGRESSION_RECORDING_STORAGE_UNAVAILABLE"
    assert exc.value.status_code == 507
    assert not list(configured.session_recording_dir.rglob("*"))


def test_recording_preflight_stops_after_purge_makes_no_progress(
    settings: Settings, tmp_path: Path, monkeypatch: pytest.MonkeyPatch
):
    configured = replace(
        settings,
        database_path=tmp_path / "cloud.db",
        debug_image_dir=tmp_path / "debug",
        session_recording_dir=tmp_path / "recordings",
        storage_min_free_bytes=100,
    )
    manager = RetentionManager(configured, SQLiteStore(configured.database_path), configured.debug_image_dir)
    candidate = {"analysis_id": "stuck", "image_path": str(configured.debug_image_dir / "stuck.png")}
    calls = 0

    def candidates(**_kwargs):
        nonlocal calls
        calls += 1
        return [candidate] if calls == 3 else []

    monkeypatch.setattr(manager.store, "list_debug_assets", candidates)
    monkeypatch.setattr(manager, "_purge_asset", lambda *_args: False)
    monkeypatch.setattr(
        "cloud.retention.shutil.disk_usage",
        lambda _: SimpleNamespace(total=10_000, used=9_100, free=900),
    )

    with pytest.raises(CloudError) as exc:
        manager.prepare_for_session_recording(900)

    assert exc.value.status_code == 507
    assert calls == 3


def test_recording_budget_is_not_blocked_by_ordinary_storage_limit(
    settings: Settings, tmp_path: Path, monkeypatch: pytest.MonkeyPatch
):
    gib = 1024 * 1024 * 1024
    configured = replace(
        settings,
        database_path=tmp_path / "cloud.db",
        debug_image_dir=tmp_path / "debug",
        session_recording_dir=tmp_path / "recordings",
        storage_max_bytes=6 * gib,
        storage_min_free_bytes=gib,
        session_recording_max_bytes=8 * gib,
    )
    manager = RetentionManager(configured, SQLiteStore(configured.database_path), configured.debug_image_dir)
    monkeypatch.setattr(
        "cloud.retention.shutil.disk_usage",
        lambda _: SimpleNamespace(total=20 * gib, used=11 * gib, free=9 * gib),
    )

    manager.prepare_for_session_recording(configured.session_recording_max_bytes)


def test_recording_preflight_deletes_normal_debug_before_touching_evidence(
    service: AnalysisService, settings: Settings, tmp_path: Path, monkeypatch: pytest.MonkeyPatch
):
    configured = replace(
        settings,
        database_path=tmp_path / "priority.db",
        debug_image_dir=tmp_path / "debug",
        evidence_dir=tmp_path / "evidence",
        session_recording_dir=tmp_path / "recordings",
        storage_min_free_bytes=100,
        storage_max_bytes=10_000_000,
        debug_image_max_bytes=10_000_000,
    )
    configured_service = _service_with_settings(service, configured)
    _, normal_id, _ = _complete_analysis(configured_service, "normal")
    _, evidence_id, _ = _complete_analysis(configured_service, "evidence")
    normal_path = configured_service.debug_image_path(normal_id)
    evidence_path = configured_service.debug_image_path(evidence_id)
    configured_service.create_evidence(evidence_id, source="manual")
    monkeypatch.setattr(
        "cloud.retention.shutil.disk_usage",
        lambda _: SimpleNamespace(
            total=10_000,
            used=9_810 if normal_path.exists() else 9_700,
            free=190 if normal_path.exists() else 300,
        ),
    )

    configured_service.retention.prepare_for_session_recording(100)

    assert not normal_path.exists()
    assert evidence_path.is_file()
    assert configured_service.store.get_debug_asset(evidence_id)["status"] == "stored"


@pytest.mark.parametrize("status", ["completed", "incomplete"])
def test_cleanup_expired_recording_keeps_tombstone_and_removes_frames(
    settings: Settings, tmp_path: Path, status: str
):
    configured = replace(
        settings,
        database_path=tmp_path / f"{status}.db",
        debug_image_dir=tmp_path / "debug",
        session_recording_dir=tmp_path / "recordings",
    )
    store = SQLiteStore(configured.database_path)
    session_id = store.create_session("guangdong_mvp_v1", recording_mode="regression")
    recording_dir = configured.session_recording_dir / session_id
    recording_dir.mkdir(parents=True)
    (recording_dir / "frame.png").write_bytes(b"frame")
    with store._connection() as connection:
        connection.execute(
            "UPDATE session_recordings SET status = ?, expires_at = datetime('now', '-1 second') WHERE session_id = ?",
            (status, session_id),
        )
    report = RetentionManager(configured, store, configured.debug_image_dir).run_once()

    assert report.expired_session_recordings == 1
    assert not recording_dir.exists()
    assert store.get_session_recording(session_id)["status"] == "expired"


def test_recording_directory_delete_failure_does_not_create_tombstone(
    settings: Settings, tmp_path: Path, monkeypatch: pytest.MonkeyPatch
):
    configured = replace(
        settings,
        database_path=tmp_path / "failure.db",
        debug_image_dir=tmp_path / "debug",
        session_recording_dir=tmp_path / "recordings",
    )
    store = SQLiteStore(configured.database_path)
    session_id = store.create_session("guangdong_mvp_v1", recording_mode="regression")
    recording_dir = configured.session_recording_dir / session_id
    recording_dir.mkdir(parents=True)
    with store._connection() as connection:
        connection.execute(
            "UPDATE session_recordings SET status = 'completed', expires_at = datetime('now', '-1 second') "
            "WHERE session_id = ?",
            (session_id,),
        )
    monkeypatch.setattr("cloud.retention.shutil.rmtree", lambda _: (_ for _ in ()).throw(OSError("busy")))

    report = RetentionManager(configured, store, configured.debug_image_dir).run_once()

    assert store.get_session_recording(session_id)["status"] == "completed"
    assert report.expired_session_recordings == 0
    assert any("recording cleanup failed" in error for error in report.errors)


def test_cleanup_recovers_stale_active_recording_and_removes_orphan_temps(settings: Settings, tmp_path: Path):
    configured = replace(
        settings,
        database_path=tmp_path / "stale.db",
        debug_image_dir=tmp_path / "debug",
        evidence_dir=tmp_path / "evidence",
        session_recording_dir=tmp_path / "recordings",
        session_recording_retention_days=1,
    )
    store = SQLiteStore(configured.database_path)
    session_id = store.create_session("guangdong_mvp_v1", recording_mode="regression")
    session_dir = configured.session_recording_dir / session_id
    session_dir.mkdir(parents=True)
    frame_tmp = session_dir / ".000001.png.tmp"
    frame_tmp.write_bytes(b"tmp")
    configured.evidence_dir.mkdir(parents=True)
    export_tmp = configured.evidence_dir / "ar-mahjong-session-regression-orphan.zip"
    export_tmp.write_bytes(b"zip")
    frozen_evidence = configured.evidence_dir / "ar-mahjong-evidence-frozen.zip"
    frozen_evidence.write_bytes(b"keep")
    old = 0
    import os
    os.utime(frame_tmp, (old, old))
    os.utime(export_tmp, (old, old))
    os.utime(frozen_evidence, (old, old))
    with store._connection() as connection:
        connection.execute(
            "UPDATE session_recordings SET started_at = datetime('now', '-2 days') WHERE session_id = ?",
            (session_id,),
        )

    RetentionManager(configured, store, configured.debug_image_dir).run_once()

    assert store.get_session_recording(session_id)["status"] == "expired"
    assert not frame_tmp.exists()
    assert not export_tmp.exists()
    assert frozen_evidence.is_file()


def test_cleanup_recovers_active_recording_for_already_ended_session(settings: Settings, tmp_path: Path):
    configured = replace(
        settings,
        database_path=tmp_path / "ended.db",
        debug_image_dir=tmp_path / "debug",
        session_recording_dir=tmp_path / "recordings",
    )
    store = SQLiteStore(configured.database_path)
    session_id = store.create_session("guangdong_mvp_v1", recording_mode="regression")
    store.end_session(session_id)

    report = RetentionManager(configured, store, configured.debug_image_dir).run_once()

    assert report.recovered_stale_session_recordings == 1
    assert store.get_session_recording(session_id)["status"] == "expired"


def test_cleanup_ends_recording_session_after_stale_analysis_is_failed(settings: Settings, tmp_path: Path):
    configured = replace(
        settings,
        database_path=tmp_path / "stale-recording.db",
        debug_image_dir=tmp_path / "debug",
        session_recording_dir=tmp_path / "recordings",
        pending_analysis_stale_minutes=1,
        session_recording_retention_days=1,
    )
    store = SQLiteStore(configured.database_path)
    session_id = store.create_session("guangdong_mvp_v1", recording_mode="regression")
    store.acquire_session_in_flight(session_id, "stale-request")
    analysis_id, _ = store.start_analysis(
        session_id,
        "stale-request",
        1,
        {"sha256": "stale", "format": "png", "width": 1, "height": 1, "frame_ts": 1},
    )
    with store._connection() as connection:
        connection.execute(
            "UPDATE analyses SET created_at = datetime('now', '-2 minutes') WHERE analysis_id = ?",
            (analysis_id,),
        )
        connection.execute(
            "UPDATE session_in_flights SET acquired_at = datetime('now', '-2 minutes') WHERE session_id = ?",
            (session_id,),
        )

    report = RetentionManager(configured, store, configured.debug_image_dir).run_once()

    assert report.status == "completed"
    assert report.recovered_stale_analyses == 1
    recording = store.get_session_recording(session_id)
    assert recording["status"] == "expired"
    assert recording["ended_at"] is not None
    assert recording["expires_at"] is not None
    with store._connection() as connection:
        session = connection.execute(
            "SELECT status, ended_at FROM sessions WHERE session_id = ?", (session_id,)
        ).fetchone()
        holder = connection.execute(
            "SELECT 1 FROM session_in_flights WHERE session_id = ?", (session_id,)
        ).fetchone()
    assert session["status"] == "ended"
    assert session["ended_at"] is not None
    assert holder is None


def test_cleanup_recovers_old_incomplete_recording_without_expiry(settings: Settings, tmp_path: Path):
    configured = replace(
        settings,
        database_path=tmp_path / "incomplete.db",
        debug_image_dir=tmp_path / "debug",
        session_recording_dir=tmp_path / "recordings",
        session_recording_retention_days=1,
    )
    store = SQLiteStore(configured.database_path)
    session_id = store.create_session("guangdong_mvp_v1", recording_mode="regression")
    with store._connection() as connection:
        connection.execute(
            """
            UPDATE session_recordings
            SET status = 'incomplete', incomplete_reason = 'RECORDING_FRAME_WRITE_FAILED',
                started_at = datetime('now', '-2 days'), expires_at = NULL
            WHERE session_id = ?
            """,
            (session_id,),
        )

    report = RetentionManager(configured, store, configured.debug_image_dir).run_once()

    assert report.status == "completed"
    assert store.get_session_recording(session_id)["status"] == "expired"
    with store._connection() as connection:
        assert connection.execute(
            "SELECT status FROM sessions WHERE session_id = ?", (session_id,)
        ).fetchone()["status"] == "ended"


def test_cleanup_does_not_end_fresh_incomplete_recording(settings: Settings, tmp_path: Path):
    configured = replace(
        settings,
        database_path=tmp_path / "fresh-incomplete.db",
        debug_image_dir=tmp_path / "debug",
        session_recording_dir=tmp_path / "recordings",
        session_recording_retention_days=1,
    )
    store = SQLiteStore(configured.database_path)
    session_id = store.create_session("guangdong_mvp_v1", recording_mode="regression")
    store.mark_session_recording_incomplete(session_id, "RECORDING_FRAME_WRITE_FAILED")

    RetentionManager(configured, store, configured.debug_image_dir).run_once()

    assert store.get_session_recording(session_id)["status"] == "incomplete"
    with store._connection() as connection:
        assert connection.execute(
            "SELECT status FROM sessions WHERE session_id = ?", (session_id,)
        ).fetchone()["status"] == "active"


def test_analysis_retention_keeps_session_with_expired_recording_tombstone(
    settings: Settings, tmp_path: Path
):
    configured = replace(
        settings,
        database_path=tmp_path / "tombstone.db",
        debug_image_dir=tmp_path / "debug",
        session_recording_dir=tmp_path / "recordings",
        analysis_retention_days=1,
    )
    store = SQLiteStore(configured.database_path)
    session_id = store.create_session("guangdong_mvp_v1")
    analysis_id, _ = store.start_analysis(
        session_id,
        "old",
        1,
        {"sha256": "old", "format": "png", "width": 1, "height": 1, "frame_ts": 1},
    )
    store.complete_analysis(analysis_id, {"status": "complete"})
    store.end_session(session_id)
    with store._connection() as connection:
        connection.execute(
            "INSERT INTO session_recordings(session_id, status, expires_at) VALUES (?, 'expired', CURRENT_TIMESTAMP)",
            (session_id,),
        )
        connection.execute(
            "UPDATE analyses SET completed_at = datetime('now', '-2 days') WHERE analysis_id = ?",
            (analysis_id,),
        )

    report = RetentionManager(configured, store, configured.debug_image_dir).run_once()

    assert report.status == "completed"
    assert report.deleted_analyses == 1
    assert store.get_session_recording(session_id)["status"] == "expired"
    with store._connection() as connection:
        assert connection.execute(
            "SELECT 1 FROM sessions WHERE session_id = ?", (session_id,)
        ).fetchone() is not None


def test_test_profile_samples_success_images_per_session(service: AnalysisService, settings: Settings, tmp_path: Path):
    sampled_settings = replace(
        settings,
        database_path=tmp_path / "sampled.db",
        debug_image_dir=tmp_path / "images",
        runtime_env="test",
        debug_image_mode="sampled",
        debug_image_success_sample_interval_seconds=30,
        storage_min_free_bytes=0,
        storage_max_bytes=10_000_000,
        debug_image_max_bytes=10_000_000,
    )
    sampled_service = _service_with_settings(service, sampled_settings)
    session_id = sampled_service.create_session("guangdong_mvp_v1")

    first = sampled_service.analyze(session_id, "sampled-1", 1, _decoded_image())
    second = sampled_service.analyze(session_id, "sampled-2", 2, _decoded_image())

    assert first["debug_image"]["stored"] is True
    assert second["debug_image"] == {
        "stored": False,
        "reason": "DEBUG_IMAGE_SAMPLE_INTERVAL",
    }
    assert len(list(sampled_settings.debug_image_dir.glob("*.png"))) == 1


def test_capacity_pressure_skips_debug_image_without_failing_analysis(service: AnalysisService, settings: Settings, tmp_path: Path):
    constrained_settings = replace(
        settings,
        database_path=tmp_path / "capacity.db",
        debug_image_dir=tmp_path / "images",
        debug_image_mode="always",
        storage_min_free_bytes=0,
        storage_max_bytes=10_000_000,
        debug_image_max_bytes=1,
    )
    constrained_service = _service_with_settings(service, constrained_settings)

    _, _, result = _complete_analysis(constrained_service, "capacity-1")

    assert result["status"] == "complete"
    assert result["debug_image"] == {
        "stored": False,
        "reason": "DEBUG_IMAGE_CAPACITY_REACHED",
    }
    assert not list(constrained_settings.debug_image_dir.glob("*.png"))


def test_cleanup_purges_expired_normal_image_and_marks_result_unavailable(
    service: AnalysisService, settings: Settings, tmp_path: Path
):
    retention_settings = replace(
        settings,
        database_path=tmp_path / "ttl.db",
        debug_image_dir=tmp_path / "images",
        debug_image_retention_days=1,
        analysis_retention_days=3,
        storage_min_free_bytes=0,
        storage_max_bytes=10_000_000,
        debug_image_max_bytes=10_000_000,
    )
    retention_service = _service_with_settings(service, retention_settings)
    session_id, analysis_id, result = _complete_analysis(retention_service, "ttl-1")
    image_path = retention_service.debug_image_path(analysis_id)
    assert result["debug_image"]["stored"] is True
    retention_service.store.end_session(session_id)
    with retention_service.store._connection() as connection:
        connection.execute(
            "UPDATE analyses SET completed_at = datetime('now', '-2 days') WHERE analysis_id = ?",
            (analysis_id,),
        )

        connection.execute(
            "UPDATE debug_assets SET stored_at = datetime('now', '-2 days') WHERE analysis_id = ?",
            (analysis_id,),
        )
    report = retention_service.run_retention_cleanup_once()
    detail = retention_service.store.get_debug_analysis(analysis_id)["analysis"]

    assert report["status"] == "completed"
    assert report["deleted_debug_images"] == 1
    assert not image_path.exists()
    assert detail["debug_image_asset_status"] == "purged"
    assert detail["result"]["debug_image"]["stored"] is False
    assert detail["result"]["debug_image"]["reason"] == "RETENTION_TTL_EXPIRED"


def test_active_evidence_protects_image_from_normal_ttl_cleanup(
    service: AnalysisService, settings: Settings, tmp_path: Path
):
    retention_settings = replace(
        settings,
        database_path=tmp_path / "evidence.db",
        debug_image_dir=tmp_path / "images",
        debug_image_retention_days=1,
        analysis_retention_days=1,
        storage_min_free_bytes=0,
        storage_max_bytes=10_000_000,
        debug_image_max_bytes=10_000_000,
    )
    retention_service = _service_with_settings(service, retention_settings)
    session_id, analysis_id, _ = _complete_analysis(retention_service, "evidence-1")
    image_path = retention_service.debug_image_path(analysis_id)
    evidence = retention_service.create_evidence(analysis_id, source="manual")
    retention_service.store.end_session(session_id)
    with retention_service.store._connection() as connection:
        connection.execute(
            "UPDATE analyses SET completed_at = datetime('now', '-2 days') WHERE analysis_id = ?",
            (analysis_id,),
        )

        connection.execute(
            "UPDATE debug_assets SET stored_at = datetime('now', '-2 days') WHERE analysis_id = ?",
            (analysis_id,),
        )
    report = retention_service.run_retention_cleanup_once()
    detail = retention_service.store.get_debug_analysis(analysis_id)["analysis"]

    assert evidence is not None
    assert report["deleted_debug_images"] == 0
    assert image_path.is_file()
    assert detail["debug_image_asset_status"] == "stored"
    assert detail["evidence"]["source"] == "manual"


def test_maintenance_lease_and_stale_in_flight_recovery(service: AnalysisService, settings: Settings, tmp_path: Path):
    maintenance_settings = replace(
        settings,
        database_path=tmp_path / "maintenance.db",
        debug_image_dir=tmp_path / "images",
        pending_analysis_stale_minutes=1,
        storage_min_free_bytes=0,
    )
    maintenance_service = _service_with_settings(service, maintenance_settings)
    store = maintenance_service.store
    assert store.try_acquire_maintenance_lease("test-lease", "holder-a", 60) is True
    assert store.try_acquire_maintenance_lease("test-lease", "holder-b", 60) is False
    store.release_maintenance_lease("test-lease", "holder-a")
    assert store.try_acquire_maintenance_lease("test-lease", "holder-b", 60) is True
    store.release_maintenance_lease("test-lease", "holder-b")

    session_id = maintenance_service.create_session("guangdong_mvp_v1")
    analysis_id, existing = store.start_analysis(
        session_id,
        "stale-1",
        1,
        {"sha256": "stale", "format": "png", "width": 640, "height": 120, "frame_ts": 0},
    )
    assert existing is None
    with store._connection() as connection:
        connection.execute(
            "UPDATE analyses SET created_at = datetime('now', '-2 minutes') WHERE analysis_id = ?",
            (analysis_id,),
        )
        connection.execute(
            "INSERT INTO session_in_flights(session_id, request_id, acquired_at) VALUES (?, ?, datetime('now', '-2 minutes'))",
            (session_id, "stale-1"),
        )

    report = maintenance_service.run_retention_cleanup_once()

    assert report["recovered_stale_analyses"] == 1
    with store._connection() as connection:
        status = connection.execute(
            "SELECT status FROM analyses WHERE analysis_id = ?", (analysis_id,)
        ).fetchone()["status"]
        in_flight = connection.execute(
            "SELECT 1 FROM session_in_flights WHERE session_id = ?", (session_id,)
        ).fetchone()
    assert status == "failed"
    assert in_flight is None



def test_manual_evidence_evicts_oldest_automatic_evidence_first(
    service: AnalysisService, settings: Settings, tmp_path: Path
):
    roomy_settings = replace(
        settings,
        database_path=tmp_path / "priority.db",
        debug_image_dir=tmp_path / "images",
        storage_min_free_bytes=0,
        storage_max_bytes=10_000_000,
        debug_image_max_bytes=10_000_000,
        evidence_max_bytes=10_000_000,
    )
    roomy_service = _service_with_settings(service, roomy_settings)
    _, automatic_analysis_id, _ = _complete_analysis(roomy_service, "automatic-evidence")
    _, manual_analysis_id, _ = _complete_analysis(roomy_service, "manual-evidence")
    automatic = roomy_service.create_evidence(automatic_analysis_id, source="automatic")
    assert automatic is not None
    asset_size = roomy_service.store.get_debug_asset(automatic_analysis_id)["size_bytes"]

    constrained_service = AnalysisService(
        settings=replace(roomy_settings, evidence_max_bytes=asset_size),
        store=roomy_service.store,
        profile=roomy_service.profile,
        region_detector=roomy_service.region_detector,
        tile_detector=roomy_service.tile_detector,
    )
    manual = constrained_service.create_evidence(manual_analysis_id, source="manual")

    assert manual is not None
    assert constrained_service.store.get_evidence(automatic["evidence_id"])["status"] == "evicted"
    assert constrained_service.store.get_evidence(manual["evidence_id"])["status"] == "active"

