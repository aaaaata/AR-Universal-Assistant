from __future__ import annotations

import json
from dataclasses import dataclass
from pathlib import Path
from typing import Any, Protocol

import numpy as np

from server.domain import BBox, DetectedRegion, TileDetection
from server.errors import ModelNotReadyError
from server.onnx_yolo import OnnxYoloRuntime
from server.structure import BONUS_TILES


YOLO_TO_MPSZ = {
    "1B": "1s", "2B": "2s", "3B": "3s", "4B": "4s", "5B": "5s", "6B": "6s", "7B": "7s", "8B": "8s", "9B": "9s",
    "1C": "1m", "2C": "2m", "3C": "3m", "4C": "4m", "5C": "5m", "6C": "6m", "7C": "7m", "8C": "8m", "9C": "9m",
    "1D": "1p", "2D": "2p", "3D": "3p", "4D": "4p", "5D": "5p", "6D": "6p", "7D": "7p", "8D": "8p", "9D": "9p",
    "EW": "1z", "SW": "2z", "WW": "3z", "NW": "4z", "WD": "5z", "GD": "6z", "RD": "7z",
    "1F": "f1", "2F": "f2", "3F": "f3", "4F": "f4", "1S": "s1", "2S": "s2", "3S": "s3", "4S": "s4",
}


class RegionDetector(Protocol):
    model_version: str

    def detect(self, image: np.ndarray) -> list[DetectedRegion]: ...


class TileDetector(Protocol):
    model_version: str

    def detect_batch(self, image: np.ndarray, regions: list[DetectedRegion]) -> list[TileDetection]: ...


@dataclass(frozen=True)
class ModelManifest:
    model_path: Path
    class_names_path: Path | None
    model_version: str
    confidence_threshold: float
    iou_threshold: float
    input_size: tuple[int, int] | None
    class_mapping: dict[str, str]
    runtime: str = "onnx"
    predict_args: dict[str, Any] | None = None


def load_manifest(path: Path) -> ModelManifest:
    if not path.is_file():
        raise ModelNotReadyError(f"Model manifest not found: {path}")
    content = json.loads(path.read_text(encoding="utf-8"))
    base_dir = path.parent

    def resolve(value: str) -> Path:
        candidate = Path(value)
        return candidate if candidate.is_absolute() else (base_dir / candidate).resolve()

    size = content.get("input_size")
    return ModelManifest(
        model_path=resolve(content["model_path"]),
        class_names_path=resolve(content["class_names_path"]) if content.get("class_names_path") else None,
        model_version=content["model_version"],
        confidence_threshold=float(content.get("confidence_threshold", 0.54)),
        iou_threshold=float(content.get("iou_threshold", 0.85)),
        input_size=(int(size[0]), int(size[1])) if size else None,
        class_mapping={str(key): str(value) for key, value in content.get("class_mapping", {}).items()},
        runtime=str(content.get("runtime", "onnx")).lower(),
        predict_args=dict(content.get("predict_args") or {}),
    )


class OnnxRegionDetector:
    def __init__(self, manifest_path: Path) -> None:
        manifest = load_manifest(manifest_path)
        if manifest.runtime != "onnx":
            raise ModelNotReadyError(f"Region manifest runtime is not onnx: {manifest.runtime}")
        if manifest.class_names_path is None:
            raise ModelNotReadyError("ONNX region model manifest requires class_names_path")
        self.model_version = manifest.model_version
        self.class_mapping = {key.lower(): value for key, value in manifest.class_mapping.items()}
        missing_region_types = {"hand", "meld"} - set(self.class_mapping.values())
        if missing_region_types:
            raise ModelNotReadyError("Region model manifest must map both hand and meld classes")
        try:
            self.runtime = OnnxYoloRuntime(
                manifest.model_path,
                manifest.class_names_path,
                manifest.confidence_threshold,
                manifest.iou_threshold,
                manifest.input_size,
            )
        except Exception as exc:
            raise ModelNotReadyError(f"Unable to load region model: {exc}") from exc

    def detect(self, image: np.ndarray) -> list[DetectedRegion]:
        regions: list[DetectedRegion] = []
        for index, detection in enumerate(self.runtime.infer(image)):
            region_type = self.class_mapping.get(detection.class_name.lower())
            if region_type not in {"hand", "meld"}:
                continue
            x1, y1, x2, y2 = detection.bbox
            regions.append(DetectedRegion(f"region-{index}", region_type, BBox(x1, y1, x2, y2), detection.confidence, self.model_version))
        return regions


class UltralyticsRegionDetector:
    """Use an Ultralytics YOLO model result directly as hand/meld regions."""

    def __init__(self, manifest_path: Path) -> None:
        manifest = load_manifest(manifest_path)
        if manifest.runtime != "ultralytics":
            raise ModelNotReadyError(f"Region manifest runtime is not ultralytics: {manifest.runtime}")
        self.model_version = manifest.model_version
        self.class_mapping = {str(key).lower(): value for key, value in manifest.class_mapping.items()}
        self.predict_args = manifest.predict_args or {}
        try:
            from ultralytics import YOLO

            self.model = YOLO(str(manifest.model_path))
        except Exception as exc:
            raise ModelNotReadyError(f"Unable to load ultralytics region model: {exc}") from exc

    def _region_type(self, class_id: int, result_names: dict[int, str] | None) -> str | None:
        class_name = str(result_names.get(class_id, class_id) if result_names else class_id)
        mapped = self.class_mapping.get(class_name.lower()) or self.class_mapping.get(str(class_id))
        if mapped in {"hand", "meld"}:
            return mapped
        if class_name.lower() in {"hand", "meld"}:
            return class_name.lower()
        return None

    def detect(self, image: np.ndarray) -> list[DetectedRegion]:
        results = self.model.predict(source=image, verbose=False, **self.predict_args)
        if not results:
            return []
        result = results[0]
        result_names = getattr(result, "names", None)
        regions: list[DetectedRegion] = []
        boxes = getattr(result, "boxes", None)
        if boxes is None:
            return []
        for index, box in enumerate(boxes):
            class_id = int(box.cls.item())
            region_type = self._region_type(class_id, result_names)
            if region_type not in {"hand", "meld"}:
                continue
            confidence = float(box.conf.item())
            x1, y1, x2, y2 = [float(value) for value in box.xyxy[0].tolist()]
            regions.append(
                DetectedRegion(
                    f"region-{index}",
                    region_type,
                    BBox(x1, y1, x2, y2),
                    confidence,
                    self.model_version,
                )
            )
        return regions


def create_region_detector(manifest_path: Path) -> RegionDetector:
    manifest = load_manifest(manifest_path)
    if manifest.runtime == "ultralytics":
        return UltralyticsRegionDetector(manifest_path)
    if manifest.runtime == "onnx":
        return OnnxRegionDetector(manifest_path)
    raise ModelNotReadyError(f"Unsupported region model runtime: {manifest.runtime}")


class FullImageHandRegionDetector:
    """Local-only detector for exercising the full cloud chain before region model delivery."""

    model_version = "dev-full-image-hand-region-v1"

    def detect(self, image: np.ndarray) -> list[DetectedRegion]:
        height, width = image.shape[:2]
        return [
            DetectedRegion(
                "dev-full-image-hand",
                "hand",
                BBox(0, 0, float(width), float(height)),
                1.0,
                self.model_version,
            )
        ]


class OnnxTileDetector:
    def __init__(self, manifest_path: Path, allowed_tiles: set[str] | None = None) -> None:
        manifest = load_manifest(manifest_path)
        if manifest.runtime != "onnx":
            raise ModelNotReadyError(f"Tile manifest runtime is not onnx: {manifest.runtime}")
        if manifest.class_names_path is None:
            raise ModelNotReadyError("ONNX tile model manifest requires class_names_path")
        self.model_version = manifest.model_version
        self.class_mapping = manifest.class_mapping or YOLO_TO_MPSZ
        supported_tiles = (set(allowed_tiles) if allowed_tiles is not None else set(YOLO_TO_MPSZ.values())) | BONUS_TILES
        unsupported_tiles = sorted(set(self.class_mapping.values()) - supported_tiles)
        if unsupported_tiles:
            raise ModelNotReadyError(f"Tile model manifest contains unsupported tile labels: {', '.join(unsupported_tiles)}")
        try:
            self.runtime = OnnxYoloRuntime(
                manifest.model_path,
                manifest.class_names_path,
                manifest.confidence_threshold,
                manifest.iou_threshold,
                manifest.input_size,
            )
        except Exception as exc:
            raise ModelNotReadyError(f"Unable to load tile model: {exc}") from exc

    def detect_batch(self, image: np.ndarray, regions: list[DetectedRegion]) -> list[TileDetection]:
        results: list[TileDetection] = []
        image_height, image_width = image.shape[:2]
        for region in regions:
            box = region.bbox
            x1, y1 = max(0, int(box.x1)), max(0, int(box.y1))
            x2, y2 = min(image_width, int(box.x2)), min(image_height, int(box.y2))
            if x2 <= x1 or y2 <= y1:
                continue
            roi = image[y1:y2, x1:x2]
            for detection in self.runtime.infer(roi):
                tile = self.class_mapping.get(detection.class_name, YOLO_TO_MPSZ.get(detection.class_name))
                if not tile:
                    continue
                rx1, ry1, rx2, ry2 = detection.bbox
                results.append(
                    TileDetection(
                        tile=tile,
                        tile_confidence=detection.confidence,
                        bbox_in_roi=BBox(rx1, ry1, rx2, ry2),
                        bbox_in_original_image=BBox(rx1 + x1, ry1 + y1, rx2 + x1, ry2 + y1),
                        source_region_id=region.region_id,
                        region_type=region.region_type,
                        region_confidence=region.confidence,
                    )
                )
        return results


class UltralyticsTileDetector:
    """Use an Ultralytics YOLO model directly for per-ROI mahjong tile detection."""

    def __init__(self, manifest_path, allowed_tiles=None):
        manifest = load_manifest(manifest_path)
        if manifest.runtime != "ultralytics":
            raise ModelNotReadyError(f"Tile manifest runtime is not ultralytics: {manifest.runtime}")
        self.model_version = manifest.model_version
        self.class_mapping = manifest.class_mapping or YOLO_TO_MPSZ
        self.predict_args = dict(manifest.predict_args or {})
        supported_tiles = (set(allowed_tiles) if allowed_tiles is not None else set(YOLO_TO_MPSZ.values())) | BONUS_TILES
        unsupported_tiles = sorted(set(self.class_mapping.values()) - supported_tiles)
        if unsupported_tiles:
            raise ModelNotReadyError("Tile model manifest contains unsupported tile labels: " + ", ".join(unsupported_tiles))
        try:
            from ultralytics import YOLO

            self.model = YOLO(str(manifest.model_path))
        except Exception as exc:
            raise ModelNotReadyError(f"Unable to load ultralytics tile model: {exc}") from exc

    def _tile(self, class_id, result_names):
        class_name = str(result_names.get(class_id, class_id) if result_names else class_id)
        return (
            self.class_mapping.get(class_name)
            or self.class_mapping.get(class_name.upper())
            or self.class_mapping.get(class_name.lower())
            or self.class_mapping.get(str(class_id))
            or YOLO_TO_MPSZ.get(class_name)
        )

    def detect_batch(self, image, regions):
        results = []
        image_height, image_width = image.shape[:2]
        for region in regions:
            box = region.bbox
            x1, y1 = max(0, int(box.x1)), max(0, int(box.y1))
            x2, y2 = min(image_width, int(box.x2)), min(image_height, int(box.y2))
            if x2 <= x1 or y2 <= y1:
                continue
            roi = image[y1:y2, x1:x2]
            predictions = self.model.predict(source=roi, verbose=False, **self.predict_args)
            if not predictions:
                continue
            result = predictions[0]
            result_names = getattr(result, "names", None)
            boxes = getattr(result, "boxes", None)
            if boxes is None:
                continue
            for detection in boxes:
                class_id = int(detection.cls.item())
                tile = self._tile(class_id, result_names)
                if not tile:
                    continue
                confidence = float(detection.conf.item())
                rx1, ry1, rx2, ry2 = [float(value) for value in detection.xyxy[0].tolist()]
                results.append(
                    TileDetection(
                        tile=tile,
                        tile_confidence=confidence,
                        bbox_in_roi=BBox(rx1, ry1, rx2, ry2),
                        bbox_in_original_image=BBox(rx1 + x1, ry1 + y1, rx2 + x1, ry2 + y1),
                        source_region_id=region.region_id,
                        region_type=region.region_type,
                        region_confidence=region.confidence,
                    )
                )
        return results


def create_tile_detector(manifest_path, allowed_tiles=None):
    manifest = load_manifest(manifest_path)
    if manifest.runtime == "ultralytics":
        return UltralyticsTileDetector(manifest_path, allowed_tiles=allowed_tiles)
    if manifest.runtime == "onnx":
        return OnnxTileDetector(manifest_path, allowed_tiles=allowed_tiles)
    raise ModelNotReadyError(f"Unsupported tile model runtime: {manifest.runtime}")


class FakeRegionDetector:
    """Contract-test detector. It must never be selected by production settings."""

    model_version = "fake-region-v1"

    def __init__(self, regions: list[DetectedRegion]) -> None:
        self.regions = regions

    def detect(self, image: np.ndarray) -> list[DetectedRegion]:
        return self.regions


class FakeTileDetector:
    model_version = "fake-tile-v1"

    def __init__(self, detections: list[TileDetection]) -> None:
        self.detections = detections

    def detect_batch(self, image: np.ndarray, regions: list[DetectedRegion]) -> list[TileDetection]:
        allowed = {region.region_id for region in regions}
        return [detection for detection in self.detections if detection.source_region_id in allowed]
