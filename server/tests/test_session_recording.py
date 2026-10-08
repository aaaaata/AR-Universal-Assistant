from __future__ import annotations

import sqlite3
from dataclasses import replace
from types import SimpleNamespace

import pytest

from server.errors import CloudError
from server.service import DecodedImage
from server.storage import SQLiteStore


def _start_analysis(store: SQLiteStore, session_id: str, request_id: str, sequence_id: int) -> str:
    analysis_id, existing = store.start_analysis(
        session_id,
        request_id,
        sequence_id,
        {
            "sha256": request_id,
            "format": "png",
            "width": 640,
            "height": 360,
            "frame_ts": sequence_id * 100,
        },
    )
    assert existing is None
    return analysis_id


def _record_frame(
    store: SQLiteStore,
    session_id: str,
    analysis_id: str,
    request_id: str,
    sequence_id: int,
    source_path,
) -> None:
    source_path.parent.mkdir(parents=True, exist_ok=True)
    source_path.write_bytes(b"frame")
    assert store.record_regression_frame(
        session_id,
        analysis_id,
        request_id,
        sequence_id,
        content_type="image/png",
        rotation_degrees=90,
        frame_ts=sequence_id * 100,
        sha256=request_id,
        size_bytes=5,
        source_path=source_path,
        max_bytes=1024,
    )


def _regression_session(store: SQLiteStore) -> str:
    return store.create_session("guangdong_mvp_v1", recording_mode="regression")


def test_recording_modes_and_single_active_limit(tmp_path):
    store = SQLiteStore(tmp_path / "cloud.db")
    normal = store.create_session("guangdong_mvp_v1")
    regression = _regression_session(store)

    assert store.get_session_recording(normal) is None
    assert store.get_session_recording(regression)["status"] == "active"
    with pytest.raises(CloudError) as busy:
        _regression_session(store)
    assert busy.value.code == "REGRESSION_RECORDING_BUSY"
    with pytest.raises(CloudError) as invalid:
        store.create_session("guangdong_mvp_v1", recording_mode="invalid")
    assert invalid.value.code == "INVALID_RECORDING_MODE"


def test_regression_mode_is_test_only_and_can_be_disabled(service):
    with pytest.raises(CloudError) as not_test:
        service.create_session("guangdong_mvp_v1", "regression")
    assert not_test.value.code == "REGRESSION_RECORDING_TEST_ONLY"

    service.settings = replace(service.settings, runtime_env="test", session_recording_enabled=False)
    with pytest.raises(CloudError) as disabled:
        service.create_session("guangdong_mvp_v1", "regression")
    assert disabled.value.code == "REGRESSION_RECORDING_DISABLED"


def test_end_and_finalize_completed_recording_atomically(tmp_path):
    store = SQLiteStore(tmp_path / "cloud.db")
    session_id = _regression_session(store)
    analysis_id = _start_analysis(store, session_id, "request-1", 1)
    _record_frame(store, session_id, analysis_id, "request-1", 1, tmp_path / "frame.png")
    store.complete_analysis(analysis_id, {"status": "complete"})

    recording = store.end_session_and_finalize_recording(session_id, 2)

    assert recording["status"] == "completed"
    assert recording["frame_count"] == 1
    assert recording["total_bytes"] == 5
    assert recording["incomplete_reason"] is None
    assert recording["ended_at"]
    assert recording["expires_at"]
    with store._connection() as connection:
        session = connection.execute("SELECT status, ended_at FROM sessions WHERE session_id = ?", (session_id,)).fetchone()
    assert dict(session)["status"] == "ended"
    assert dict(session)["ended_at"]


def test_end_and_finalize_roll_back_together_on_recording_update_failure(tmp_path):
    store = SQLiteStore(tmp_path / "cloud.db")
    session_id = _regression_session(store)
    with store._connection() as connection:
        connection.execute(
            """
            CREATE TRIGGER fail_recording_finalize
            BEFORE UPDATE ON session_recordings
            BEGIN
                SELECT RAISE(ABORT, 'finalize failed');
            END
            """
        )

    with pytest.raises(sqlite3.IntegrityError):
        store.end_session_and_finalize_recording(session_id, 1)

    with store._connection() as connection:
        session_status = connection.execute(
            "SELECT status FROM sessions WHERE session_id = ?", (session_id,)
        ).fetchone()[0]
        recording_status = connection.execute(
            "SELECT status FROM session_recordings WHERE session_id = ?", (session_id,)
        ).fetchone()[0]
    assert session_status == "active"
    assert recording_status == "active"


@pytest.mark.parametrize(
    ("setup", "reason"),
    [
        ("empty", "RECORDING_EMPTY"),
        ("pending", "RECORDING_SESSION_ENDED_WITH_PENDING_ANALYSES"),
        ("in_flight", "RECORDING_SESSION_ENDED_WITH_IN_FLIGHT_ANALYSIS"),
        ("failed", "RECORDING_SESSION_CONTAINS_FAILED_ANALYSES"),
        ("missing_frame", "RECORDING_FRAME_INDEX_INCOMPLETE"),
    ],
)
def test_end_race_and_invalid_contents_are_incomplete(tmp_path, setup, reason):
    store = SQLiteStore(tmp_path / f"{setup}.db")
    session_id = _regression_session(store)
    if setup != "empty":
        if setup == "in_flight":
            store.acquire_session_in_flight(session_id, "request-1")
        analysis_id = _start_analysis(store, session_id, "request-1", 1)
        if setup == "failed":
            _record_frame(store, session_id, analysis_id, "request-1", 1, tmp_path / setup / "frame.png")
            store.fail_analysis(analysis_id, CloudError("BROKEN", "broken", 422))
        elif setup == "missing_frame":
            store.complete_analysis(analysis_id, {"status": "complete"})

    recording = store.end_session_and_finalize_recording(session_id, 1)

    assert recording["status"] == "incomplete"
    assert recording["incomplete_reason"] == reason


def test_completed_pack_has_frame_and_analysis_metadata(tmp_path):
    store = SQLiteStore(tmp_path / "cloud.db")
    session_id = _regression_session(store)
    analysis_id = _start_analysis(store, session_id, "request-1", 1)
    frame_path = tmp_path / "frame.png"
    _record_frame(store, session_id, analysis_id, "request-1", 1, frame_path)
    store.complete_analysis(analysis_id, {"status": "complete", "answer": 42})
    store.end_session_and_finalize_recording(session_id, 1)

    snapshot = store.get_regression_pack_snapshot(session_id)

    assert snapshot["recording"]["status"] == "completed"
    assert snapshot["session"]["status"] == "ended"
    assert snapshot["frames"] == [
        {
            **snapshot["frames"][0],
            "session_id": session_id,
            "sequence_id": 1,
            "analysis_id": analysis_id,
            "request_id": "request-1",
            "content_type": "image/png",
            "rotation_degrees": 90,
            "frame_ts": 100,
            "sha256": "request-1",
            "size_bytes": 5,
            "source_path": str(frame_path),
            "analysis_status": "completed",
            "result": {"status": "complete", "answer": 42},
            "image_width": 640,
            "image_height": 360,
            "image_format": "png",
        }
    ]


def test_empty_or_incomplete_recording_cannot_be_exported(tmp_path):
    store = SQLiteStore(tmp_path / "cloud.db")
    session_id = _regression_session(store)
    store.end_session_and_finalize_recording(session_id, 1)

    with pytest.raises(CloudError) as exc:
        store.get_regression_pack_snapshot(session_id)
    assert exc.value.code == "REGRESSION_RECORDING_NOT_EXPORTABLE"


def test_idempotent_analysis_does_not_duplicate_recording_frame(service, bgr_image, tmp_path):
    service.settings = replace(
        service.settings,
        runtime_env="test",
        session_recording_dir=tmp_path / "recordings",
        storage_min_free_bytes=0,
    )
    service.retention.settings = service.settings
    service.retention.session_recording_dir = service.settings.session_recording_dir
    session_id = service.create_session("guangdong_mvp_v1", "regression")
    decoded = DecodedImage(bgr_image, "a" * 64, "png", 640, 120)

    first = service.analyze(
        session_id,
        "request-1",
        1,
        decoded,
        123,
        original_image_bytes=b"frame",
        content_type="image/png",
    )
    second = service.analyze(
        session_id,
        "request-1",
        1,
        decoded,
        123,
        original_image_bytes=b"different",
        content_type="image/png",
    )

    assert first["analysis_id"] == second["analysis_id"]
    with service.store._connection() as connection:
        count = connection.execute(
            "SELECT COUNT(*) FROM session_recording_frames WHERE session_id = ?", (session_id,)
        ).fetchone()[0]
    assert count == 1
    assert (service.session_recording_dir(session_id) / "frames" / "000001.png").read_bytes() == b"frame"


def test_recording_write_and_marker_failures_never_change_analysis_result(service, bgr_image, monkeypatch, tmp_path):
    service.settings = replace(
        service.settings,
        runtime_env="test",
        session_recording_dir=tmp_path / "recordings",
        storage_min_free_bytes=0,
    )
    service.retention.settings = service.settings
    service.retention.session_recording_dir = service.settings.session_recording_dir
    session_id = service.create_session("guangdong_mvp_v1", "regression")
    decoded = DecodedImage(bgr_image, "b" * 64, "png", 640, 120)

    monkeypatch.setattr(service.store, "record_regression_frame", lambda *args, **kwargs: (_ for _ in ()).throw(sqlite3.Error("db")))
    monkeypatch.setattr(
        service.store,
        "mark_session_recording_incomplete",
        lambda *args, **kwargs: (_ for _ in ()).throw(sqlite3.Error("marker")),
    )

    result = service.analyze(
        session_id,
        "request-1",
        1,
        decoded,
        original_image_bytes=b"frame",
        content_type="image/png",
    )

    assert result["status"] == "complete"
    assert not (service.session_recording_dir(session_id) / "frames" / "000001.png").exists()


def test_recording_file_write_failure_marks_incomplete_without_failing_analysis(
    service, bgr_image, monkeypatch, tmp_path
):
    service.settings = replace(
        service.settings,
        runtime_env="test",
        session_recording_dir=tmp_path / "recordings",
        storage_min_free_bytes=0,
    )
    service.retention.settings = service.settings
    service.retention.session_recording_dir = service.settings.session_recording_dir
    session_id = service.create_session("guangdong_mvp_v1", "regression")
    decoded = DecodedImage(bgr_image, "c" * 64, "png", 640, 120)
    original_write_bytes = type(tmp_path).write_bytes

    def fail_recording_write(path, data):
        if service.settings.session_recording_dir in path.parents:
            raise OSError("disk write failed")
        return original_write_bytes(path, data)

    monkeypatch.setattr(type(tmp_path), "write_bytes", fail_recording_write)

    result = service.analyze(
        session_id,
        "request-1",
        1,
        decoded,
        original_image_bytes=b"frame",
        content_type="image/png",
    )

    assert result["status"] == "complete"
    recording = service.store.get_session_recording(session_id)
    assert recording["status"] == "incomplete"
    assert recording["incomplete_reason"] == "RECORDING_FRAME_WRITE_FAILED"
    assert not list(service.settings.session_recording_dir.rglob("*.tmp"))


def test_recording_frame_allows_exact_minimum_free_space_boundary(
    service, bgr_image, monkeypatch, tmp_path
):
    service.settings = replace(
        service.settings,
        runtime_env="test",
        session_recording_dir=tmp_path / "recordings",
        session_recording_max_bytes=5,
        storage_min_free_bytes=10,
    )
    service.retention.settings = service.settings
    service.retention.session_recording_dir = service.settings.session_recording_dir
    session_id = service.create_session("guangdong_mvp_v1", "regression")
    monkeypatch.setattr(
        "server.service.shutil.disk_usage",
        lambda _path: SimpleNamespace(total=100, used=85, free=15),
    )

    result = service.analyze(
        session_id,
        "request-boundary",
        1,
        DecodedImage(bgr_image, "d" * 64, "png", 640, 120),
        original_image_bytes=b"frame",
        content_type="image/png",
    )

    assert result["status"] == "complete"
    assert service.store.get_session_recording(session_id)["status"] == "active"
    assert (service.session_recording_dir(session_id) / "frames" / "000001.png").read_bytes() == b"frame"


def test_completed_recording_protects_analysis_from_ordinary_retention(tmp_path):
    store = SQLiteStore(tmp_path / "cloud.db")
    session_id = _regression_session(store)
    analysis_id = _start_analysis(store, session_id, "request-1", 1)
    _record_frame(store, session_id, analysis_id, "request-1", 1, tmp_path / "frame.png")
    store.complete_analysis(analysis_id, {"status": "complete"})
    store.end_session_and_finalize_recording(session_id, 1)

    assert store.delete_analysis_if_prunable(analysis_id) is False
    assert store.analysis_exists(analysis_id) is True


def test_expired_tombstone_preserves_recording_and_clears_frame_index(tmp_path):
    store = SQLiteStore(tmp_path / "cloud.db")
    session_id = _regression_session(store)
    analysis_id = _start_analysis(store, session_id, "request-1", 1)
    _record_frame(store, session_id, analysis_id, "request-1", 1, tmp_path / "frame.png")
    store.complete_analysis(analysis_id, {"status": "complete"})
    store.end_session_and_finalize_recording(session_id, 1)

    tombstone = store.expire_session_recording(session_id)

    assert tombstone["status"] == "expired"
    assert tombstone["frame_count"] == 1
    assert tombstone["total_bytes"] == 5
    assert store.get_session_recording(session_id)["status"] == "expired"
    with store._connection() as connection:
        assert connection.execute(
            "SELECT COUNT(*) FROM session_recording_frames WHERE session_id = ?", (session_id,)
        ).fetchone()[0] == 0
