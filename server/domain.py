from __future__ import annotations

from dataclasses import dataclass, field
from typing import Any, Literal


RegionType = Literal["hand", "meld"]


@dataclass(frozen=True)
class BBox:
    x1: float
    y1: float
    x2: float
    y2: float

    @property
    def width(self) -> float:
        return max(0.0, self.x2 - self.x1)

    @property
    def height(self) -> float:
        return max(0.0, self.y2 - self.y1)

    @property
    def center_x(self) -> float:
        return (self.x1 + self.x2) / 2

    @property
    def center_y(self) -> float:
        return (self.y1 + self.y2) / 2

    def padding_px(self, ratio: float, min_pixels: float) -> float:
        return max(min(self.width, self.height) * ratio, min_pixels)

    def expand(self, pad: float, image_width: int, image_height: int) -> "BBox":
        return BBox(
            max(0.0, self.x1 - pad),
            max(0.0, self.y1 - pad),
            min(float(image_width), self.x2 + pad),
            min(float(image_height), self.y2 + pad),
        )

    def contains_point(self, x: float, y: float) -> bool:
        return self.x1 <= x <= self.x2 and self.y1 <= y <= self.y2

    def iou(self, other: "BBox") -> float:
        ix1, iy1 = max(self.x1, other.x1), max(self.y1, other.y1)
        ix2, iy2 = min(self.x2, other.x2), min(self.y2, other.y2)
        intersection = max(0.0, ix2 - ix1) * max(0.0, iy2 - iy1)
        if intersection <= 0:
            return 0.0
        union = self.width * self.height + other.width * other.height - intersection
        return intersection / union if union else 0.0

    def as_dict(self) -> dict[str, float]:
        return {"x1": round(self.x1, 2), "y1": round(self.y1, 2), "x2": round(self.x2, 2), "y2": round(self.y2, 2)}


@dataclass(frozen=True)
class DetectedRegion:
    region_id: str
    region_type: RegionType
    bbox: BBox
    confidence: float
    model_version: str

    def as_dict(self) -> dict[str, Any]:
        return {
            "region_id": self.region_id,
            "region_type": self.region_type,
            "bbox": self.bbox.as_dict(),
            "confidence": round(self.confidence, 4),
            "model_version": self.model_version,
        }


@dataclass(frozen=True)
class TileDetection:
    tile: str
    tile_confidence: float
    bbox_in_roi: BBox
    bbox_in_original_image: BBox
    source_region_id: str
    region_type: RegionType
    region_confidence: float

    @property
    def combined_score(self) -> float:
        return self.tile_confidence * self.region_confidence

    def as_dict(self) -> dict[str, Any]:
        return {
            "tile": self.tile,
            "tile_confidence": round(self.tile_confidence, 4),
            "bbox_in_roi": self.bbox_in_roi.as_dict(),
            "bbox_in_original_image": self.bbox_in_original_image.as_dict(),
            "source_region_id": self.source_region_id,
            "region_type": self.region_type,
            "region_confidence": round(self.region_confidence, 4),
            "combined_score": round(self.combined_score, 4),
        }


@dataclass
class StructuredHand:
    hand_tiles: list[str] = field(default_factory=list)
    meld_groups: list[dict[str, Any]] = field(default_factory=list)
    bonus_tiles: list[str] = field(default_factory=list)
    accepted_detections: list[TileDetection] = field(default_factory=list)
    warnings: list[str] = field(default_factory=list)
    diagnostic_code: str | None = None
