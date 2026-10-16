from __future__ import annotations

from dataclasses import replace

import numpy as np
import pytest

import cloud.service as service_module
from cloud.config import Settings
from cloud.service import AnalysisService


class StubTileDetector:
    model_version = "stub-tile-v1"

    def detect_batch(self, image, regions):
        return []


def test_settings_from_env_reads_dev_full_image_switch(monkeypatch):
    monkeypatch.setenv("CLOUD_API_KEY", "test-key")
    monkeypatch.setenv("CLOUD_DEV_FULL_IMAGE_HAND_REGION", "1")
    monkeypatch.delenv("CLOUD_REGION_MANIFEST", raising=False)

    settings = Settings.from_env()

    assert settings.dev_full_image_hand_region is True
    assert settings.region_manifest_path is None


def test_dev_full_image_region_bypasses_region_manifest(settings, monkeypatch):
    def fail_region_detector(_manifest_path):
        raise AssertionError("region manifest should not be loaded in dev full-image mode")

    monkeypatch.setattr(service_module, "create_tile_detector", lambda *args, **kwargs: StubTileDetector())
    monkeypatch.setattr(service_module, "create_region_detector", fail_region_detector)
    dev_settings = replace(settings, region_manifest_path=None, dev_full_image_hand_region=True)

    service = AnalysisService.from_settings(dev_settings)
    ready, errors = service.readiness()
    regions = service.region_detector.detect(np.zeros((360, 640, 3), dtype=np.uint8))

    assert ready, errors
    assert len(regions) == 1
    assert regions[0].region_id == "dev-full-image-hand"
    assert regions[0].region_type == "hand"
    assert regions[0].bbox.as_dict() == {"x1": 0, "y1": 0, "x2": 640.0, "y2": 360.0}
    assert regions[0].confidence == 1.0


def test_settings_from_env_reads_image_limit_overrides(monkeypatch):
    monkeypatch.setenv("CLOUD_API_KEY", "test-key")
    monkeypatch.setenv("CLOUD_MIN_IMAGE_WIDTH", "128")
    monkeypatch.setenv("CLOUD_MIN_IMAGE_HEIGHT", "96")
    monkeypatch.setenv("CLOUD_MAX_IMAGE_PIXELS", "0")

    settings = Settings.from_env()

    assert settings.min_image_width == 128
    assert settings.min_image_height == 96
    assert settings.max_image_pixels == 0


def test_settings_from_env_reads_roi_padding_overrides(monkeypatch):
    monkeypatch.setenv("CLOUD_API_KEY", "test-key")
    monkeypatch.setenv("CLOUD_ROI_PADDING_RATIO", "0.1")
    monkeypatch.setenv("CLOUD_ROI_PADDING_PX", "4")

    settings = Settings.from_env()

    assert settings.roi_padding_ratio == 0.1
    assert settings.roi_padding_px == 4


def test_settings_from_env_rejects_negative_roi_padding(monkeypatch):
    monkeypatch.setenv("CLOUD_API_KEY", "test-key")
    monkeypatch.setenv("CLOUD_ROI_PADDING_RATIO", "-0.1")

    with pytest.raises(ValueError, match="CLOUD_ROI_PADDING_RATIO"):
        Settings.from_env()
