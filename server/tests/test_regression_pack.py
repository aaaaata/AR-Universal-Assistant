from __future__ import annotations

import hashlib
import io
import json
import zipfile
from dataclasses import replace
from pathlib import Path

import pytest
from fastapi.testclient import TestClient

from server.app import create_app
from server.tests.test_api import png_bytes
from server.tests.test_api_boundaries import analysis_headers


def _client(service, tmp_path: Path) -> tuple[TestClient, object]:
    settings = replace(
        service.settings,
        runtime_env="test",
        session_recording_dir=tmp_path / "recordings",
        evidence_dir=tmp_path / "evidence",
        storage_min_free_bytes=0,
    )
    service.settings = settings
    service.retention.settings = settings
    service.retention.session_recording_dir = settings.session_recording_dir
    return TestClient(create_app(settings, service)), service


def _create_recording(client: TestClient, sequences: tuple[int, ...] = (1,)) -> tuple[str, list[bytes]]:
    session_id = client.post(
        "/api/v1/mahjong/sessions",
        headers={"X-API-Key": "test-key"},
        json={"recording_mode": "regression"},
    ).json()["session_id"]
    images: list[bytes] = []
    for sequence_id in sequences:
        image = png_bytes()
        images.append(image)
        response = client.post(
            f"/api/v1/mahjong/sessions/{session_id}/analyses",
            headers=analysis_headers(f"request-{sequence_id}", sequence_id),
            content=image,
        )
        assert response.status_code == 200
    return session_id, images


def _download(client: TestClient, session_id: str):
    return client.get(
        f"/debug/sessions/{session_id}/regression-pack/download",
        headers={"X-API-Key": "test-key"},
    )


def test_regression_pack_route_auth_and_recording_state_errors(service, tmp_path):
    client, service = _client(service, tmp_path)
    normal_id = client.post(
        "/api/v1/mahjong/sessions",
        headers={"X-API-Key": "test-key"},
        json={},
    ).json()["session_id"]
    active_id, _ = _create_recording(client)

    unauthorized = client.get(f"/debug/sessions/{active_id}/regression-pack/download")
    missing = _download(client, "missing")
    normal = _download(client, normal_id)
    active = _download(client, active_id)
    client.post(
        f"/api/v1/mahjong/sessions/{active_id}/end",
        headers={"X-API-Key": "test-key"},
    )
    incomplete_id = client.post(
        "/api/v1/mahjong/sessions",
        headers={"X-API-Key": "test-key"},
        json={"recording_mode": "regression"},
    ).json()["session_id"]
    client.post(
        f"/api/v1/mahjong/sessions/{incomplete_id}/end",
        headers={"X-API-Key": "test-key"},
    )
    incomplete = _download(client, incomplete_id)
    with service.store._connection() as connection:
        connection.execute(
            "UPDATE session_recordings SET expires_at = datetime('now', '-1 second') WHERE session_id = ?",
            (active_id,),
        )
    expired = _download(client, active_id)

    assert unauthorized.status_code == 401
    assert missing.status_code == 404
    assert normal.status_code == 404
    assert active.status_code == 409
    assert incomplete.status_code == 409
    assert expired.status_code == 409
    assert expired.json()["error"]["code"] == "REGRESSION_RECORDING_EXPIRED"


def test_regression_pack_exact_members_manifest_order_checksums_and_redaction(service, tmp_path):
    client, service = _client(service, tmp_path)
    session_id, images = _create_recording(client, (3, 9))
    client.post(
        f"/api/v1/mahjong/sessions/{session_id}/end",
        headers={"X-API-Key": "test-key"},
    )

    response = _download(client, session_id)

    assert response.status_code == 200
    with zipfile.ZipFile(io.BytesIO(response.content)) as archive:
        names = archive.namelist()
        assert set(names) == {
            "manifest.json",
            "session.json",
            "frames/000003.png",
            "frames/000009.png",
            "baselines/000003.json",
            "baselines/000009.json",
            "runtime-context.json",
            "model-manifests.json",
            "checksums.json",
        }
        manifest = json.loads(archive.read("manifest.json"))
        checksums = json.loads(archive.read("checksums.json"))["sha256"]
        payload = b"".join(archive.read(name) for name in names)
        assert manifest["format"] == "ar-mahjong-session-regression-v1"
        assert [frame["sequence_id"] for frame in manifest["frames"]] == [3, 9]
        assert set(manifest["frames"][0]) == {
            "sequence_id",
            "request_id",
            "frame_ts",
            "content_type",
            "rotation_degrees",
            "sha256",
            "size_bytes",
            "frame_path",
            "baseline_path",
            "analysis_id",
        }
        assert archive.read("frames/000003.png") == images[0]
        assert manifest["frames"][0]["sha256"] == hashlib.sha256(images[0]).hexdigest()
        assert set(checksums) == set(names) - {"checksums.json"}
        for name, expected in checksums.items():
            assert hashlib.sha256(archive.read(name)).hexdigest() == expected
        assert b"test-key" not in payload
        assert b"Bearer " not in payload
        assert b"environ" not in payload
        assert b"SQLite format 3" not in payload

    recording = service.store.get_session_recording(session_id)
    assert recording["exported_at"] is not None
    export_dir = settings_export_dir(service)
    assert not list(export_dir.glob("ar-mahjong-session-regression-*.zip"))
    assert not list((tmp_path / "evidence").glob("ar-mahjong-session-regression-*.zip"))


def settings_export_dir(service) -> Path:
    return service.settings.session_recording_dir.parent / "exports"


@pytest.mark.parametrize(
    ("mutation", "expected_code"),
    [
        ("missing", "REGRESSION_PACK_INTEGRITY_ERROR"),
        ("tampered", "REGRESSION_PACK_INTEGRITY_ERROR"),
        ("outside", "REGRESSION_PACK_INTEGRITY_ERROR"),
        ("size", "REGRESSION_PACK_INTEGRITY_ERROR"),
        ("baseline", "REGRESSION_PACK_INCOMPLETE"),
        ("count", "REGRESSION_PACK_INCOMPLETE"),
    ],
)
def test_regression_pack_rejects_invalid_sources_and_baselines(service, tmp_path, mutation, expected_code):
    client, service = _client(service, tmp_path)
    session_id, _ = _create_recording(client)
    client.post(
        f"/api/v1/mahjong/sessions/{session_id}/end",
        headers={"X-API-Key": "test-key"},
    )
    frame = service.session_recording_dir(session_id) / "frames" / "000001.png"
    with service.store._connection() as connection:
        if mutation == "missing":
            frame.unlink()
        elif mutation == "tampered":
            frame.write_bytes(b"tampered")
        elif mutation == "outside":
            outside = tmp_path / "outside.png"
            outside.write_bytes(frame.read_bytes())
            connection.execute(
                "UPDATE session_recording_frames SET source_path = ? WHERE session_id = ?",
                (str(outside), session_id),
            )
        elif mutation == "size":
            connection.execute(
                "UPDATE session_recording_frames SET size_bytes = size_bytes + 1 WHERE session_id = ?",
                (session_id,),
            )
        elif mutation == "baseline":
            connection.execute(
                "UPDATE analyses SET status = 'failed' WHERE session_id = ?",
                (session_id,),
            )
        elif mutation == "count":
            connection.execute(
                "UPDATE session_recordings SET frame_count = frame_count + 1 WHERE session_id = ?",
                (session_id,),
            )

    response = _download(client, session_id)

    assert response.status_code == 409
    assert response.json()["error"]["code"] == expected_code
    assert not list(settings_export_dir(service).glob("ar-mahjong-session-regression-*.zip"))
    assert service.store.get_session_recording(session_id)["exported_at"] is None


def test_regression_export_lease_rejects_parallel_download_and_protects_cleanup(service, tmp_path):
    client, service = _client(service, tmp_path)
    session_id, _ = _create_recording(client)
    client.post(
        f"/api/v1/mahjong/sessions/{session_id}/end",
        headers={"X-API-Key": "test-key"},
    )
    holder = service.store.acquire_session_recording_export(session_id)

    response = _download(client, session_id)

    with service.store._connection() as connection:
        connection.execute(
            "UPDATE session_recordings SET expires_at = datetime('now', '-1 second') WHERE session_id = ?",
            (session_id,),
        )
    service.retention.cleanup_expired_session_recordings(
        __import__("server.retention", fromlist=["RetentionReport"]).RetentionReport(status="test")
    )

    assert response.status_code == 409
    assert response.json()["error"]["code"] == "REGRESSION_PACK_EXPORT_BUSY"
    assert service.session_recording_dir(session_id).is_dir()
    assert service.store.get_session_recording(session_id)["status"] == "completed"
    service.store.release_session_recording_export(session_id, holder)


def test_regression_pack_disk_preflight_releases_lease(service, tmp_path, monkeypatch):
    client, service = _client(service, tmp_path)
    session_id, _ = _create_recording(client)
    client.post(
        f"/api/v1/mahjong/sessions/{session_id}/end",
        headers={"X-API-Key": "test-key"},
    )
    real_disk_usage = __import__("shutil").disk_usage
    monkeypatch.setattr(
        "server.app.shutil.disk_usage",
        lambda _path: type("DiskUsage", (), {"total": 1, "used": 1, "free": 0})(),
    )

    unavailable = _download(client, session_id)

    assert unavailable.status_code == 507
    assert unavailable.json()["error"]["code"] == "REGRESSION_PACK_STORAGE_UNAVAILABLE"
    assert service.store.get_session_recording(session_id)["exported_at"] is None
    monkeypatch.setattr("server.app.shutil.disk_usage", real_disk_usage)
    assert _download(client, session_id).status_code == 200
