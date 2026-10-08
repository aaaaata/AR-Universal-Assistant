from __future__ import annotations

import hashlib
from io import BytesIO

from fastapi.testclient import TestClient
from PIL import Image

from server.app import create_app


def png_bytes() -> bytes:
    image = Image.new("RGB", (640, 360), color=(25, 80, 125))
    buffer = BytesIO()
    image.save(buffer, format="PNG")
    return buffer.getvalue()


def test_create_session_route_is_bound_to_create_session(service):
    client = TestClient(create_app(service.settings, service))

    response = client.post(
        "/api/v1/mahjong/sessions",
        headers={"X-API-Key": "test-key"},
        json={"rule_profile_id": "guangdong_mvp_v1"},
    )

    assert response.status_code == 200
    assert response.json()["rule_profile_id"] == "guangdong_mvp_v1"
    assert response.json()["recording_mode"] == "normal"


def test_raw_png_contract_and_idempotency(service):
    client = TestClient(create_app(service.settings, service))
    headers = {"X-API-Key": "test-key"}
    session = client.post("/api/v1/mahjong/sessions", headers=headers, json={"rule_profile_id": "guangdong_mvp_v1"})
    session_id = session.json()["session_id"]
    image = png_bytes()
    analysis_headers = {
        **headers,
        "Content-Type": "image/png",
        "X-Request-Id": "png-request-1",
        "X-Sequence-Id": "1",
        "X-Frame-Ts": "123456",
        "X-Frame-Count": "1",
    }

    first = client.post(f"/api/v1/mahjong/sessions/{session_id}/analyses", headers=analysis_headers, content=image)
    second = client.post(f"/api/v1/mahjong/sessions/{session_id}/analyses", headers=analysis_headers, content=image)

    assert first.status_code == 200
    assert first.json()["image"]["sha256"] == hashlib.sha256(image).hexdigest()
    assert first.json()["image"]["format"] == "png"
    assert first.json()["analysis_id"] == second.json()["analysis_id"]


def test_multiple_frames_are_rejected(service):
    client = TestClient(create_app(service.settings, service))
    session_id = client.post("/api/v1/mahjong/sessions", headers={"X-API-Key": "test-key"}, json={}).json()["session_id"]
    response = client.post(
        f"/api/v1/mahjong/sessions/{session_id}/analyses",
        headers={
            "X-API-Key": "test-key",
            "Content-Type": "image/png",
            "X-Request-Id": "png-request-2",
            "X-Sequence-Id": "1",
            "X-Frame-Ts": "123456",
            "X-Frame-Count": "2",
        },
        content=png_bytes(),
    )
    assert response.status_code == 422
    assert response.json()["error"]["code"] == "FRAME_COUNT_NOT_ENABLED"
