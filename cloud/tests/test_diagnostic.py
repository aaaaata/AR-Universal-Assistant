from cloud.adapters import FakeRegionDetector
from cloud.domain import BBox, DetectedRegion
from cloud.service import DecodedImage


def test_missing_hand_returns_diagnostic_with_frame_timestamp(service, bgr_image):
    service.region_detector = FakeRegionDetector(
        [DetectedRegion("meld-only", "meld", BBox(0, 0, 100, 100), 0.9, "fake-region-v1")]
    )
    session_id = service.create_session("guangdong_mvp_v1")
    result = service.analyze(
        session_id,
        "missing-hand",
        1,
        DecodedImage(bgr_image, "c" * 64, "png", 640, 120),
        frame_ts=987654,
    )

    assert result["status"] == "diagnostic"
    assert result["diagnostic_code"] == "HAND_REGION_NOT_FOUND"
    assert result["image"]["frame_ts"] == 987654


def test_no_regions_returns_hand_region_not_found(service, bgr_image):
    service.region_detector = FakeRegionDetector([])
    session_id = service.create_session("guangdong_mvp_v1")

    result = service.analyze(
        session_id,
        "no-regions",
        1,
        DecodedImage(bgr_image, "e" * 64, "png", 640, 120),
    )

    assert result["status"] == "diagnostic"
    assert result["diagnostic_code"] == "HAND_REGION_NOT_FOUND"


def test_more_than_maximum_active_regions_is_rejected(service, bgr_image):
    service.region_detector = FakeRegionDetector(
        [
            DetectedRegion(f"hand-{index}", "hand", BBox(index, 0, index + 10, 100), 0.9, "fake-region-v1")
            for index in range(service.settings.max_regions_per_frame + 1)
        ]
    )
    session_id = service.create_session("guangdong_mvp_v1")

    result = service.analyze(
        session_id,
        "too-many-regions",
        1,
        DecodedImage(bgr_image, "f" * 64, "png", 640, 120),
    )

    assert result["status"] == "diagnostic"
    assert result["diagnostic_code"] == "TOO_MANY_REGIONS"


def test_low_confidence_hand_region_is_still_used(service, bgr_image):
    service.region_detector = FakeRegionDetector(
        [DetectedRegion("hand-a", "hand", BBox(0, 0, 300, 160), 0.01, "fake-region-v1")]
    )
    session_id = service.create_session("guangdong_mvp_v1")

    result = service.analyze(
        session_id,
        "low-confidence-hand",
        1,
        DecodedImage(bgr_image, "a" * 64, "png", 640, 120),
    )

    assert result["status"] == "complete"
    assert result["regions"][0]["confidence"] == 0.01
