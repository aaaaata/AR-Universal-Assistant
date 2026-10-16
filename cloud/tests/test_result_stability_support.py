from cloud.adapters import FakeTileDetector
from cloud.domain import BBox, DetectedRegion, TileDetection
from cloud.service import DecodedImage
from cloud.structure import structure_tiles


def _detection(tile: str, confidence: float, x1: float) -> TileDetection:
    bbox = BBox(x1, 10, x1 + 16, 80)
    return TileDetection(tile, confidence, bbox, bbox, "hand", "hand", 0.95)


def test_hand_tiles_are_returned_in_tile_code_order() -> None:
    regions = [DetectedRegion("hand", "hand", BBox(0, 0, 320, 100), 0.95, "fake")]
    tiles = ["7z", "2s", "1p", "9m", "1m", "1z", "9s", "2p", "5m", "3z", "4p", "3s", "2m"]
    detections = [_detection(tile, 0.9, index * 20) for index, tile in enumerate(tiles)]

    result = structure_tiles(regions, detections, 0.5, 0.1)

    assert result.diagnostic_code is None
    assert result.hand_tiles == ["1m", "2m", "5m", "9m", "1p", "2p", "4p", "2s", "3s", "9s", "1z", "3z", "7z"]


def test_complete_result_reports_no_low_confidence_hand_detection(service, bgr_image) -> None:
    session_id = service.create_session("guangdong_mvp_v1")
    result = service.analyze(
        session_id,
        "no-low-confidence-hand-box",
        1,
        DecodedImage(bgr_image, "1" * 64, "png", 640, 120),
    )

    assert result["status"] == "complete"
    assert result["has_low_confidence_hand_tile_detection"] is False


def test_complete_result_reports_low_confidence_hand_detection(service, tile_detections, bgr_image) -> None:
    low_box = tile_detections[0].bbox_in_original_image
    service.tile_detector = FakeTileDetector(
        [
            *tile_detections,
            TileDetection("1m", 0.6, low_box, low_box, "hand-a", "hand", 0.95),
        ]
    )
    session_id = service.create_session("guangdong_mvp_v1")
    result = service.analyze(
        session_id,
        "low-confidence-hand-box",
        1,
        DecodedImage(bgr_image, "2" * 64, "png", 640, 120),
    )

    assert result["status"] == "complete"
    assert result["has_low_confidence_hand_tile_detection"] is True


def test_padding_only_false_positive_is_dropped_from_hand(service, bgr_image) -> None:
    from cloud.adapters import FakeRegionDetector, FakeTileDetector

    region = DetectedRegion("hand", "hand", BBox(40, 10, 300, 90), 0.97, "fake")
    tiles = ["1m", "2m", "3m", "4m", "5m", "6m", "7m", "8m", "9m", "1p", "2p", "3p", "4p", "5p"]
    detections = []
    for index, tile in enumerate(tiles):
        x1 = 48 + index * 16
        bbox = BBox(x1, 20, x1 + 14, 80)
        detections.append(TileDetection(tile, 0.9, bbox, bbox, "hand", "hand", 0.97))
    padding_false = TileDetection("1p", 0.3, BBox(0, 0, 16, 80), BBox(8, 12, 24, 80), "hand", "hand", 0.97)
    service.region_detector = FakeRegionDetector([region])
    service.tile_detector = FakeTileDetector(detections + [padding_false])

    session_id = service.create_session("guangdong_mvp_v1")
    result = service.analyze(
        session_id,
        "padding-only-1p",
        1,
        DecodedImage(bgr_image, "3" * 64, "png", 640, 120),
    )

    assert result["status"] == "complete"
    assert result["diagnostic_code"] is None
    assert len(result["hand_tiles"]) == 14
    assert "1p" in result["hand_tiles"]
    assert result["has_low_confidence_hand_tile_detection"] is False
    assert result["regions"][0]["bbox"] == {"x1": 40.0, "y1": 10.0, "x2": 300.0, "y2": 90.0}
    assert result["regions"][0]["roi_bbox"] == {"x1": 32.0, "y1": 2.0, "x2": 308.0, "y2": 98.0}
