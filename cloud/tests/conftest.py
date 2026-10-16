from __future__ import annotations

from pathlib import Path

import numpy as np
import pytest

from cloud.adapters import FakeRegionDetector, FakeTileDetector
from cloud.config import Settings
from cloud.domain import BBox, DetectedRegion, TileDetection
from cloud.rules import load_rule_profile
from cloud.service import AnalysisService
from cloud.storage import SQLiteStore


@pytest.fixture
def settings(tmp_path: Path) -> Settings:
    return Settings(
        api_key="test-key",
        database_path=tmp_path / "cloud.db",
        tile_manifest_path=tmp_path / "tile.manifest.json",
        region_manifest_path=tmp_path / "region.manifest.json",
        min_image_width=32,
        min_image_height=32,
        max_image_pixels=1_000_000,
    )


@pytest.fixture
def regions() -> list[DetectedRegion]:
    return [
        DetectedRegion("hand-a", "hand", BBox(0, 0, 300, 160), 0.95, "fake-region-v1"),
        DetectedRegion("hand-b", "hand", BBox(240, 0, 500, 160), 0.92, "fake-region-v1"),
    ]


@pytest.fixture
def tile_detections() -> list[TileDetection]:
    tiles = ["1m", "2m", "3m", "4m", "5m", "6m", "7m", "8m", "9m", "1p", "2p", "3p", "4p", "5p"]
    detections: list[TileDetection] = []
    for index, tile in enumerate(tiles):
        x1 = 10 + index * 18
        bbox = BBox(x1, 20, x1 + 14, 100)
        detections.append(TileDetection(tile, 0.9, bbox, bbox, "hand-a", "hand", 0.95))
    duplicate_box = BBox(10, 20, 24, 100)
    detections.append(TileDetection("1m", 0.88, duplicate_box, duplicate_box, "hand-b", "hand", 0.92))
    return detections


@pytest.fixture
def service(settings: Settings, regions: list[DetectedRegion], tile_detections: list[TileDetection]) -> AnalysisService:
    profile_path = Path(__file__).resolve().parents[1] / "rules" / "guangdong_mvp_v1.json"
    return AnalysisService(
        settings=settings,
        store=SQLiteStore(settings.database_path),
        profile=load_rule_profile(profile_path),
        region_detector=FakeRegionDetector(regions),
        tile_detector=FakeTileDetector(tile_detections),
    )


@pytest.fixture
def bgr_image() -> np.ndarray:
    return np.zeros((120, 640, 3), dtype=np.uint8)
