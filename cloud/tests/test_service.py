from __future__ import annotations

from cloud.service import DecodedImage


def test_analysis_is_idempotent_and_returns_complete_result(service, bgr_image):
    session_id = service.create_session("guangdong_mvp_v1")
    image = DecodedImage(bgr_image, "a" * 64, "png", 640, 120)

    first = service.analyze(session_id, "request-1", 1, image)
    retried = service.analyze(session_id, "request-1", 1, image)

    assert first["status"] == "complete"
    assert first["analysis_id"] == retried["analysis_id"]
    assert first["image"]["sha256"] == "a" * 64
    assert first["suggestion"]["advice_type"] == "discard"


def test_out_of_order_sequence_is_rejected(service, bgr_image):
    from cloud.errors import CloudError

    session_id = service.create_session("guangdong_mvp_v1")
    image = DecodedImage(bgr_image, "b" * 64, "png", 640, 120)
    service.analyze(session_id, "request-1", 2, image)

    try:
        service.analyze(session_id, "request-2", 1, image)
    except CloudError as exc:
        assert exc.code == "SEQUENCE_OUT_OF_ORDER"
    else:
        raise AssertionError("Expected an ordering error")
