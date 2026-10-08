from __future__ import annotations

import json
import sys
import types

import numpy as np

from server.adapters import UltralyticsRegionDetector, create_region_detector


class FakeBox:
    def __init__(self, class_id: int, confidence: float, xyxy: list[float]) -> None:
        self.cls = np.array([class_id])
        self.conf = np.array([confidence])
        self.xyxy = np.array([xyxy], dtype=float)


class FakeResult:
    names = {0: "hand", 1: "meld", 2: "other"}

    def __init__(self) -> None:
        self.orig_shape = (720, 1280)
        self.speed = {"preprocess": 1.0, "inference": 2.0, "postprocess": 0.5}
        self.boxes = [
            FakeBox(0, 0.01, [10, 20, 110, 220]),
            FakeBox(1, 0.02, [200, 30, 360, 180]),
            FakeBox(2, 0.99, [1, 2, 3, 4]),
        ]


class FakeYOLO:
    calls: list[dict] = []

    def __init__(self, model_path: str) -> None:
        self.model_path = model_path

    def predict(self, **kwargs):
        FakeYOLO.calls.append(kwargs)
        return [FakeResult()]


def install_fake_ultralytics(monkeypatch) -> None:
    FakeYOLO.calls = []
    monkeypatch.setitem(sys.modules, "ultralytics", types.SimpleNamespace(YOLO=FakeYOLO))


def write_manifest(tmp_path):
    manifest = tmp_path / "region.ultralytics.manifest.json"
    manifest.write_text(
        json.dumps(
            {
                "runtime": "ultralytics",
                "model_path": "region.pt",
                "model_version": "region-ultralytics-test-v1",
                "predict_args": {"imgsz": 640},
                "class_mapping": {"hand": "hand", "meld": "meld"},
            }
        ),
        encoding="utf-8",
    )
    return manifest


def test_ultralytics_region_detector_maps_result_boxes_without_confidence_filter(tmp_path, monkeypatch):
    install_fake_ultralytics(monkeypatch)
    detector = UltralyticsRegionDetector(write_manifest(tmp_path))

    regions = detector.detect(np.zeros((720, 1280, 3), dtype=np.uint8))

    assert [region.region_type for region in regions] == ["hand", "meld"]
    assert [region.confidence for region in regions] == [0.01, 0.02]
    assert regions[0].bbox.as_dict() == {"x1": 10.0, "y1": 20.0, "x2": 110.0, "y2": 220.0}
    assert regions[0].model_version == "region-ultralytics-test-v1"
    assert FakeYOLO.calls[0]["verbose"] is False
    assert FakeYOLO.calls[0]["imgsz"] == 640


def test_region_detector_factory_selects_ultralytics_runtime(tmp_path, monkeypatch):
    install_fake_ultralytics(monkeypatch)

    detector = create_region_detector(write_manifest(tmp_path))

    assert isinstance(detector, UltralyticsRegionDetector)
