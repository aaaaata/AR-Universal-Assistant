from __future__ import annotations

from dataclasses import replace
from io import BytesIO
import json

import numpy as np
import pytest
from PIL import Image

from cloud.adapters import FakeRegionDetector, OnnxTileDetector
from cloud.domain import BBox, DetectedRegion, StructuredHand, TileDetection
from cloud.errors import CloudError, ModelNotReadyError
from cloud.rules import GuangdongMvpRuleEngine, UnsupportedMeldError
from cloud.service import DecodedImage, decode_image
from cloud.structure import structure_tiles


class ExplodingRegionDetector:
    model_version = "exploding-region-v1"

    def detect(self, image: np.ndarray) -> list[DetectedRegion]:
        raise RuntimeError("inference failed")


def test_failed_analysis_reaches_terminal_state_and_allows_new_request(service, bgr_image):
    session_id = service.create_session("guangdong_mvp_v1")
    image = DecodedImage(bgr_image, "f" * 64, "png", 640, 120)
    service.region_detector = ExplodingRegionDetector()

    with pytest.raises(CloudError) as first_attempt:
        service.analyze(session_id, "failed-request", 1, image)
    assert first_attempt.value.code == "ANALYSIS_FAILED"

    with pytest.raises(CloudError) as retry:
        service.analyze(session_id, "failed-request", 1, image)
    assert retry.value.code == "ANALYSIS_FAILED"

    service.region_detector = FakeRegionDetector([])
    result = service.analyze(session_id, "next-request", 2, image)
    assert result["diagnostic_code"] == "HAND_REGION_NOT_FOUND"


def test_kan_uses_three_tiles_for_tingpai_steps_calculation(service):
    hand = StructuredHand(
        hand_tiles=["1m", "2m", "3m", "4m", "5m", "6m", "7m", "8m", "9m", "1p", "1p"],
        meld_groups=[{"type": "kan", "tiles": ["2s", "2s", "2s", "2s"]}],
    )

    result = GuangdongMvpRuleEngine(service.profile).analyze(hand)

    assert result["advice_type"] == "discard"
    assert result["candidates"]


def test_adjacent_legal_melds_are_split_by_tile_structure():
    regions = [
        DetectedRegion("hand", "hand", BBox(0, 0, 300, 80), 0.95, "fake"),
        DetectedRegion("meld", "meld", BBox(0, 100, 300, 180), 0.95, "fake"),
    ]
    detections: list[TileDetection] = []
    for index, tile in enumerate(["1m", "2m", "3m", "4m", "5m", "6m", "7m", "8m"]):
        bbox = BBox(index * 20, 10, index * 20 + 16, 70)
        detections.append(TileDetection(tile, 0.9, bbox, bbox, "hand", "hand", 0.95))
    for index, tile in enumerate(["2s", "3s", "4s", "5p", "6p", "7p"]):
        x1 = index * 20
        bbox = BBox(x1, 110, x1 + 16, 170)
        detections.append(TileDetection(tile, 0.9, bbox, bbox, "meld", "meld", 0.95))

    result = structure_tiles(regions, detections, 0.5, 0.1)

    assert result.diagnostic_code is None
    assert result.meld_groups == [
        {"type": "chi", "tiles": ["2s", "3s", "4s"]},
        {"type": "chi", "tiles": ["5p", "6p", "7p"]},
    ]


def test_large_compressed_image_is_rejected_before_rgb_conversion(settings, monkeypatch):
    raw = BytesIO()
    Image.new("1", (2_000, 2_000)).save(raw, format="PNG")

    def fail_conversion(self, *args, **kwargs):
        raise AssertionError("conversion must not run")

    monkeypatch.setattr(Image.Image, "convert", fail_conversion)

    with pytest.raises(CloudError) as exc:
        decode_image(raw.getvalue(), "image/png", settings)
    assert exc.value.code == "IMAGE_TOO_LARGE"


def test_tile_manifest_rejects_rule_external_tile_mapping(tmp_path):
    manifest = tmp_path / "tile.manifest.json"
    manifest.write_text(
        json.dumps(
            {
                "model_path": "tile.onnx",
                "class_names_path": "class_names.txt",
                "model_version": "tile-test-v1",
                "class_mapping": {"UNKNOWN": "9x"},
            }
        ),
        encoding="utf-8",
    )

    with pytest.raises(ModelNotReadyError, match="unsupported tile labels"):
        OnnxTileDetector(manifest, allowed_tiles={"1m", "2m"})


def test_zero_max_image_pixels_disables_pixel_gate(settings):
    raw = BytesIO()
    Image.new("RGB", (1_100, 1_100)).save(raw, format="PNG")

    decoded = decode_image(raw.getvalue(), "image/png", replace(settings, max_image_pixels=0))

    assert decoded.width == 1_100
    assert decoded.height == 1_100


def test_thirteen_tile_hand_returns_draw_wait_suggestion(service):
    hand = StructuredHand(hand_tiles=["1m", "2m", "3m", "4m", "5m", "6m", "7m", "8m", "9m", "1p", "1p", "2p", "3p"])

    result = GuangdongMvpRuleEngine(service.profile).analyze(hand)

    assert result["advice_type"] == "draw_wait"
    assert result["suggestion"] == {
        "type": "draw_wait",
        "jinzhang_count": result["jinzhang_count"],
        "jinzhang_tiles": result["jinzhang_tiles"],
        "tingpai_steps": result["tingpai_steps"],
    }
    assert result["jinzhang_count"] == 6
    assert result["jinzhang_tiles"] == ["1p", "4p"]
    assert result["candidates"] == [
        {"tile": "1p", "remaining": 2, "tingpai_steps": -1},
        {"tile": "4p", "remaining": 4, "tingpai_steps": -1},
    ]



def test_guangdong_mvp_rule_profile_disallows_chi(service):
    assert service.profile.allowed_melds == ["pon", "kan"]


def test_guangdong_mvp_rule_engine_rejects_chi_meld(service):
    hand = StructuredHand(
        hand_tiles=["1m", "2m", "3m", "4m", "5m", "6m", "7m", "8m", "9m", "1p", "1p"],
        meld_groups=[{"type": "chi", "tiles": ["2s", "3s", "4s"]}],
    )

    with pytest.raises(UnsupportedMeldError) as exc:
        GuangdongMvpRuleEngine(service.profile).analyze(hand)

    assert exc.value.meld_types == ["chi"]


def test_guangdong_mvp_rule_engine_allows_pon_meld(service):
    hand = StructuredHand(
        hand_tiles=["1m", "2m", "3m", "4m", "5m", "6m", "7m", "8m", "9m", "1p", "1p"],
        meld_groups=[{"type": "pon", "tiles": ["2s", "2s", "2s"]}],
    )

    result = GuangdongMvpRuleEngine(service.profile).analyze(hand)

    assert result["advice_type"] == "discard"
    assert result["candidates"]
