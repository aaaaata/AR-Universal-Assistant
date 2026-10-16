from __future__ import annotations

import asyncio
import hashlib
import threading
from dataclasses import replace
from io import BytesIO

import httpx
import pytest
from fastapi.testclient import TestClient
from PIL import Image

from cloud.app import create_app
from cloud.service import AnalysisService


def image_bytes(image_format: str) -> bytes:
    image = Image.new("RGB", (640, 360), color=(25, 80, 125))
    buffer = BytesIO()
    image.save(buffer, format=image_format)
    return buffer.getvalue()


def analysis_headers(
    request_id: str,
    sequence_id: int = 1,
    content_type: str = "image/png",
) -> dict[str, str]:
    return {
        "X-API-Key": "test-key",
        "Content-Type": content_type,
        "X-Request-Id": request_id,
        "X-Sequence-Id": str(sequence_id),
        "X-Frame-Ts": "123456",
        "X-Frame-Count": "1",
    }


def create_session(client: TestClient | httpx.AsyncClient):
    return client.post(
        "/api/v1/mahjong/sessions",
        headers={"X-API-Key": "test-key"},
        json={"rule_profile_id": "guangdong_mvp_v1"},
    )


def without_region_detector(service) -> AnalysisService:
    return AnalysisService(
        settings=service.settings,
        store=service.store,
        profile=service.profile,
        region_detector=None,
        tile_detector=service.tile_detector,
    )


def test_health_ready_reports_ready_and_missing_detector(service):
    ready_client = TestClient(create_app(service.settings, service))
    ready_response = ready_client.get("/health/ready")

    unavailable = without_region_detector(service)
    unavailable_client = TestClient(create_app(unavailable.settings, unavailable))
    unavailable_response = unavailable_client.get("/health/ready")

    assert ready_response.status_code == 200
    assert ready_response.json() == {"status": "ready", "errors": []}
    assert unavailable_response.status_code == 503
    assert unavailable_response.json()["status"] == "not_ready"
    assert "Region detector is unavailable" in unavailable_response.json()["errors"]


def test_analysis_reports_model_not_ready(service):
    unavailable = without_region_detector(service)
    client = TestClient(create_app(unavailable.settings, unavailable))
    session_id = create_session(client).json()["session_id"]

    response = client.post(
        f"/api/v1/mahjong/sessions/{session_id}/analyses",
        headers=analysis_headers("model-not-ready"),
        content=image_bytes("PNG"),
    )

    assert response.status_code == 503
    assert response.json()["error"]["code"] == "MODEL_NOT_READY"


class ExplodingRegionDetector:
    model_version = "exploding-region-v1"

    def detect(self, image):
        raise RuntimeError("inference failed")


def test_failed_analysis_replays_same_error_for_retry_and_get(service):
    client = TestClient(create_app(service.settings, service))
    session_id = create_session(client).json()["session_id"]
    service.region_detector = ExplodingRegionDetector()
    image = image_bytes("PNG")
    headers = analysis_headers("api-failed-request")

    first = client.post(
        f"/api/v1/mahjong/sessions/{session_id}/analyses",
        headers=headers,
        content=image,
    )
    with service.store._connection() as connection:
        connection.execute(
            "INSERT INTO session_in_flights(session_id, request_id) VALUES (?, ?)",
            (session_id, "api-failed-request"),
        )
    retry = client.post(
        f"/api/v1/mahjong/sessions/{session_id}/analyses",
        headers=headers,
        content=image,
    )
    with service.store._connection() as connection:
        analysis_id = connection.execute(
            "SELECT analysis_id FROM analyses WHERE session_id = ? AND request_id = ?",
            (session_id, "api-failed-request"),
        ).fetchone()["analysis_id"]
    fetched = client.get(
        f"/api/v1/mahjong/sessions/{session_id}/analyses/{analysis_id}",
        headers={"X-API-Key": "test-key"},
    )

    assert first.status_code == 500
    assert retry.status_code == first.status_code
    assert retry.json() == first.json()
    assert fetched.status_code == first.status_code
    assert fetched.json() == first.json()


def test_completed_analysis_retry_bypasses_lingering_same_request_holder(service):
    client = TestClient(create_app(service.settings, service))
    session_id = create_session(client).json()["session_id"]
    image = image_bytes("PNG")
    headers = analysis_headers("completed-with-holder")
    first = client.post(
        f"/api/v1/mahjong/sessions/{session_id}/analyses",
        headers=headers,
        content=image,
    )
    assert first.status_code == 200
    with service.store._connection() as connection:
        connection.execute(
            "INSERT INTO session_in_flights(session_id, request_id) VALUES (?, ?)",
            (session_id, "completed-with-holder"),
        )

    retry = client.post(
        f"/api/v1/mahjong/sessions/{session_id}/analyses",
        headers=headers,
        content=image,
    )

    assert retry.status_code == 200
    assert retry.json()["analysis_id"] == first.json()["analysis_id"]


def test_raw_jpeg_contract(service):
    client = TestClient(create_app(service.settings, service))
    session_id = create_session(client).json()["session_id"]
    image = image_bytes("JPEG")

    response = client.post(
        f"/api/v1/mahjong/sessions/{session_id}/analyses",
        headers=analysis_headers("jpeg-request", content_type="image/jpeg"),
        content=image,
    )

    assert response.status_code == 200
    assert response.json()["image"]["format"] == "jpeg"
    assert response.json()["image"]["sha256"] == hashlib.sha256(image).hexdigest()


def test_analysis_response_includes_server_pipeline_timings(service):
    client = TestClient(create_app(service.settings, service))
    session_id = create_session(client).json()["session_id"]
    image = image_bytes("PNG")

    response = client.post(
        f"/api/v1/mahjong/sessions/{session_id}/analyses",
        headers=analysis_headers("timings-request"),
        content=image,
    )

    assert response.status_code == 200
    payload = response.json()
    timings = payload["timings_ms"]
    expected_keys = {
        "request_body_read",
        "image_decode",
        "session_in_flight_acquire",
        "queue_wait",
        "db_start_analysis",
        "region_detection",
        "tile_detection",
        "structure",
        "rule_engine",
        "algorithm_total",
        "total",
        "debug_image_write",
        "db_complete_analysis",
        "analysis_worker",
        "route_handler_total",
        "route_overhead",
    }
    assert expected_keys <= set(timings)
    assert timings["total"] == timings["algorithm_total"]
    assert timings["route_handler_total"] >= timings["algorithm_total"]
    assert timings["route_overhead"] == pytest.approx(
        timings["route_handler_total"] - timings["algorithm_total"], abs=0.05
    )

    fetched = client.get(
        f"/api/v1/mahjong/sessions/{session_id}/analyses/{payload['analysis_id']}",
        headers={"X-API-Key": "test-key"},
    )

    assert fetched.status_code == 200
    assert fetched.json()["timings_ms"]["route_handler_total"] == timings["route_handler_total"]


@pytest.mark.parametrize(
    ("content_type", "payload", "expected_status", "expected_code"),
    [
        ("text/plain", b"not-an-image", 415, "UNSUPPORTED_MEDIA_TYPE"),
        ("image/png", b"not-a-png", 422, "INVALID_IMAGE"),
        ("image/png", b"", 422, "EMPTY_IMAGE"),
    ],
)
def test_image_input_errors(service, content_type, payload, expected_status, expected_code):
    client = TestClient(create_app(service.settings, service))
    session_id = create_session(client).json()["session_id"]

    response = client.post(
        f"/api/v1/mahjong/sessions/{session_id}/analyses",
        headers=analysis_headers(f"invalid-{expected_code}", content_type=content_type),
        content=payload,
    )

    assert response.status_code == expected_status
    assert response.json()["error"]["code"] == expected_code


def test_upload_byte_limit_is_enforced_before_decode(service):
    limited_settings = replace(service.settings, max_upload_bytes=32)
    client = TestClient(create_app(limited_settings, service))
    session_id = create_session(client).json()["session_id"]

    response = client.post(
        f"/api/v1/mahjong/sessions/{session_id}/analyses",
        headers=analysis_headers("too-many-bytes"),
        content=image_bytes("PNG"),
    )

    assert response.status_code == 413
    assert response.json()["error"]["code"] == "PAYLOAD_TOO_LARGE"


def test_analysis_is_serialized_without_blocking_health(service):
    app = create_app(replace(service.settings, analysis_queue_timeout_ms=1000), service)
    original_analyze = service.analyze
    first_entered = threading.Event()
    release_first = threading.Event()
    lock = threading.Lock()
    activity = {"active": 0, "max_active": 0, "calls": 0}

    def tracked_analyze(*args, **kwargs):
        with lock:
            activity["active"] += 1
            activity["calls"] += 1
            activity["max_active"] = max(activity["max_active"], activity["active"])
            call_number = activity["calls"]
        if call_number == 1:
            first_entered.set()
            release_first.wait(timeout=2)
        try:
            return original_analyze(*args, **kwargs)
        finally:
            with lock:
                activity["active"] -= 1

    async def exercise() -> None:
        service.analyze = tracked_analyze  # type: ignore[method-assign]
        transport = httpx.ASGITransport(app=app)
        try:
            async with httpx.AsyncClient(transport=transport, base_url="http://test") as client:
                first_session_id = (await create_session(client)).json()["session_id"]
                second_session_id = (await create_session(client)).json()["session_id"]
                first = asyncio.create_task(
                    client.post(
                        f"/api/v1/mahjong/sessions/{first_session_id}/analyses",
                        headers=analysis_headers("serialized-1", sequence_id=1),
                        content=image_bytes("PNG"),
                    )
                )
                for _ in range(100):
                    if first_entered.is_set():
                        break
                    await asyncio.sleep(0.01)
                assert first_entered.is_set()

                second = asyncio.create_task(
                    client.post(
                        f"/api/v1/mahjong/sessions/{second_session_id}/analyses",
                        headers=analysis_headers("serialized-2", sequence_id=1),
                        content=image_bytes("PNG"),
                    )
                )
                await asyncio.sleep(0.05)
                assert activity["max_active"] == 1

                health = await asyncio.wait_for(client.get("/health/live"), timeout=0.5)
                assert health.status_code == 200

                release_first.set()
                first_response, second_response = await asyncio.gather(first, second)
                assert first_response.status_code == 200
                assert second_response.status_code == 200
                assert activity["max_active"] == 1
        finally:
            release_first.set()
            service.analyze = original_analyze  # type: ignore[method-assign]

    asyncio.run(exercise())


def test_same_session_concurrent_analysis_is_rejected_without_advancing_sequence(service):
    app = create_app(replace(service.settings, analysis_queue_timeout_ms=1000), service)
    original_analyze = service.analyze
    first_entered = threading.Event()
    release_first = threading.Event()

    def tracked_analyze(*args, **kwargs):
        if not first_entered.is_set():
            first_entered.set()
            release_first.wait(timeout=2)
        return original_analyze(*args, **kwargs)

    async def exercise() -> None:
        service.analyze = tracked_analyze  # type: ignore[method-assign]
        transport = httpx.ASGITransport(app=app)
        try:
            async with httpx.AsyncClient(transport=transport, base_url="http://test") as client:
                session_id = (await create_session(client)).json()["session_id"]
                first = asyncio.create_task(
                    client.post(
                        f"/api/v1/mahjong/sessions/{session_id}/analyses",
                        headers=analysis_headers("session-busy-1", sequence_id=1),
                        content=image_bytes("PNG"),
                    )
                )
                for _ in range(100):
                    if first_entered.is_set():
                        break
                    await asyncio.sleep(0.01)
                assert first_entered.is_set()

                busy = await client.post(
                    f"/api/v1/mahjong/sessions/{session_id}/analyses",
                    headers=analysis_headers("session-busy-2", sequence_id=2),
                    content=image_bytes("PNG"),
                )
                assert busy.status_code == 409
                assert busy.json()["error"]["code"] == "SESSION_ANALYSIS_BUSY"
                with service.store._connection() as connection:
                    row = connection.execute("SELECT last_sequence_id FROM sessions WHERE session_id = ?", (session_id,)).fetchone()
                assert row["last_sequence_id"] == -1

                release_first.set()
                first_response = await first
                assert first_response.status_code == 200
                with service.store._connection() as connection:
                    row = connection.execute("SELECT last_sequence_id FROM sessions WHERE session_id = ?", (session_id,)).fetchone()
                assert row["last_sequence_id"] == 1

                next_frame = await client.post(
                    f"/api/v1/mahjong/sessions/{session_id}/analyses",
                    headers=analysis_headers("session-busy-3", sequence_id=2),
                    content=image_bytes("PNG"),
                )
                assert next_frame.status_code == 200
        finally:
            release_first.set()
            service.analyze = original_analyze  # type: ignore[method-assign]

    asyncio.run(exercise())


def test_global_analysis_queue_timeout_rejects_without_creating_analysis(service):
    app = create_app(replace(service.settings, analysis_queue_timeout_ms=10), service)
    original_analyze = service.analyze
    first_entered = threading.Event()
    release_first = threading.Event()

    def tracked_analyze(*args, **kwargs):
        if not first_entered.is_set():
            first_entered.set()
            release_first.wait(timeout=2)
        return original_analyze(*args, **kwargs)

    async def exercise() -> None:
        service.analyze = tracked_analyze  # type: ignore[method-assign]
        transport = httpx.ASGITransport(app=app)
        try:
            async with httpx.AsyncClient(transport=transport, base_url="http://test") as client:
                first_session_id = (await create_session(client)).json()["session_id"]
                second_session_id = (await create_session(client)).json()["session_id"]
                first = asyncio.create_task(
                    client.post(
                        f"/api/v1/mahjong/sessions/{first_session_id}/analyses",
                        headers=analysis_headers("capacity-busy-1", sequence_id=1),
                        content=image_bytes("PNG"),
                    )
                )
                for _ in range(100):
                    if first_entered.is_set():
                        break
                    await asyncio.sleep(0.01)
                assert first_entered.is_set()

                busy = await client.post(
                    f"/api/v1/mahjong/sessions/{second_session_id}/analyses",
                    headers=analysis_headers("capacity-busy-2", sequence_id=1),
                    content=image_bytes("PNG"),
                )
                assert busy.status_code == 429
                assert busy.json()["error"]["code"] == "ANALYSIS_BUSY"
                with service.store._connection() as connection:
                    session = connection.execute("SELECT last_sequence_id FROM sessions WHERE session_id = ?", (second_session_id,)).fetchone()
                    analysis_count = connection.execute("SELECT COUNT(*) AS count FROM analyses WHERE session_id = ?", (second_session_id,)).fetchone()
                assert session["last_sequence_id"] == -1
                assert analysis_count["count"] == 0

                release_first.set()
                first_response = await first
                assert first_response.status_code == 200

                next_frame = await client.post(
                    f"/api/v1/mahjong/sessions/{second_session_id}/analyses",
                    headers=analysis_headers("capacity-busy-3", sequence_id=1),
                    content=image_bytes("PNG"),
                )
                assert next_frame.status_code == 200
        finally:
            release_first.set()
            service.analyze = original_analyze  # type: ignore[method-assign]

    asyncio.run(exercise())
