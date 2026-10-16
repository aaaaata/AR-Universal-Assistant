from pathlib import Path

import numpy as np

from cloud.adapters import FakeRegionDetector, FakeTileDetector
from cloud.domain import BBox, DetectedRegion, TileDetection
from cloud.rules import load_rule_profile
from cloud.service import AnalysisService, DecodedImage
from cloud.storage import SQLiteStore


def test_fake_hand_and_meld_regions_return_diagnostic_for_chi(service, settings) -> None:
    regions = [
        DetectedRegion("hand", "hand", BBox(0, 0, 500, 100), 0.95, "fake-region-v1"),
        DetectedRegion("meld", "meld", BBox(0, 100, 300, 200), 0.95, "fake-region-v1"),
    ]
    hand_tiles = ["1m", "2m", "3m", "4m", "5m", "6m", "7m", "8m", "9m", "1p", "1p"]
    meld_tiles = ["2s", "3s", "4s"]
    detections: list[TileDetection] = []
    for index, tile in enumerate(hand_tiles):
        bbox = BBox(index * 20, 10, index * 20 + 16, 80)
        detections.append(TileDetection(tile, 0.9, bbox, bbox, "hand", "hand", 0.95))
    for index, tile in enumerate(meld_tiles):
        bbox = BBox(index * 20, 110, index * 20 + 16, 180)
        detections.append(TileDetection(tile, 0.9, bbox, bbox, "meld", "meld", 0.95))

    profile_path = Path(__file__).resolve().parents[1] / "rules" / "guangdong_mvp_v1.json"
    service = AnalysisService(
        settings=settings,
        store=SQLiteStore(settings.database_path),
        profile=load_rule_profile(profile_path),
        region_detector=FakeRegionDetector(regions),
        tile_detector=FakeTileDetector(detections),
    )
    session_id = service.create_session("guangdong_mvp_v1")
    image = DecodedImage(np.zeros((240, 640, 3), dtype=np.uint8), "d" * 64, "png", 640, 240)

    result = service.analyze(session_id, "hand-and-meld", 1, image)

    assert result["status"] == "diagnostic"
    assert result["diagnostic_code"] == "UNSUPPORTED_MELD_TYPE"
    assert result["hand_tiles"] == hand_tiles
    assert result["meld_groups"] == [{"type": "chi", "tiles": meld_tiles}]
    assert result["suggestion"] is None



def test_fake_hand_and_pon_meld_regions_still_complete(service, settings) -> None:
    regions = [
        DetectedRegion("hand", "hand", BBox(0, 0, 500, 100), 0.95, "fake-region-v1"),
        DetectedRegion("meld", "meld", BBox(0, 100, 300, 200), 0.95, "fake-region-v1"),
    ]
    hand_tiles = ["1m", "2m", "3m", "4m", "5m", "6m", "7m", "8m", "9m", "1p", "1p"]
    meld_tiles = ["2s", "2s", "2s"]
    detections: list[TileDetection] = []
    for index, tile in enumerate(hand_tiles):
        bbox = BBox(index * 20, 10, index * 20 + 16, 80)
        detections.append(TileDetection(tile, 0.9, bbox, bbox, "hand", "hand", 0.95))
    for index, tile in enumerate(meld_tiles):
        bbox = BBox(index * 20, 110, index * 20 + 16, 180)
        detections.append(TileDetection(tile, 0.9, bbox, bbox, "meld", "meld", 0.95))

    profile_path = Path(__file__).resolve().parents[1] / "rules" / "guangdong_mvp_v1.json"
    service = AnalysisService(
        settings=settings,
        store=SQLiteStore(settings.database_path),
        profile=load_rule_profile(profile_path),
        region_detector=FakeRegionDetector(regions),
        tile_detector=FakeTileDetector(detections),
    )
    session_id = service.create_session("guangdong_mvp_v1")
    image = DecodedImage(np.zeros((240, 640, 3), dtype=np.uint8), "e" * 64, "png", 640, 240)

    result = service.analyze(session_id, "hand-and-pon-meld", 1, image)

    assert result["status"] == "complete"
    assert result["diagnostic_code"] is None
    assert result["meld_groups"] == [{"type": "pon", "tiles": meld_tiles}]
    assert result["suggestion"]["advice_type"] == "discard"
