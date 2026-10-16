from __future__ import annotations

from dataclasses import dataclass
from pathlib import Path
from typing import Any

import cv2
import numpy as np
import onnxruntime as ort


@dataclass(frozen=True)
class RawDetection:
    class_name: str
    confidence: float
    bbox: tuple[float, float, float, float]


class OnnxYoloRuntime:
    """Small reusable YOLOv8 ONNX runtime, independent from the legacy service."""

    def __init__(
        self,
        model_path: Path,
        class_names_path: Path,
        confidence_threshold: float,
        iou_threshold: float,
        input_size: tuple[int, int] | None = None,
    ) -> None:
        if not model_path.is_file() or not class_names_path.is_file():
            raise FileNotFoundError(f"YOLO model assets unavailable: {model_path}, {class_names_path}")
        self.session = ort.InferenceSession(str(model_path))
        model_input = self.session.get_inputs()[0]
        self.input_name = model_input.name
        self.output_name = self.session.get_outputs()[0].name
        shape = model_input.shape
        self.input_height = input_size[1] if input_size else (shape[2] if isinstance(shape[2], int) else 640)
        self.input_width = input_size[0] if input_size else (shape[3] if isinstance(shape[3], int) else 640)
        self.confidence_threshold = confidence_threshold
        self.iou_threshold = iou_threshold
        self.class_names = [line.strip() for line in class_names_path.read_text(encoding="utf-8").splitlines() if line.strip()]

    def _preprocess(self, image: np.ndarray) -> tuple[np.ndarray, float, float, float]:
        image_height, image_width = image.shape[:2]
        scale = min(self.input_width / image_width, self.input_height / image_height)
        resized_width, resized_height = int(round(image_width * scale)), int(round(image_height * scale))
        resized = cv2.resize(image, (resized_width, resized_height), interpolation=cv2.INTER_LINEAR)
        pad_x, pad_y = (self.input_width - resized_width) / 2, (self.input_height - resized_height) / 2
        top, bottom = int(round(pad_y - 0.1)), int(round(pad_y + 0.1))
        left, right = int(round(pad_x - 0.1)), int(round(pad_x + 0.1))
        padded = cv2.copyMakeBorder(resized, top, bottom, left, right, cv2.BORDER_CONSTANT, value=(114, 114, 114))
        tensor = cv2.cvtColor(padded, cv2.COLOR_BGR2RGB).transpose((2, 0, 1))
        tensor = np.ascontiguousarray(tensor[np.newaxis, ...], dtype=np.float32) / 255.0
        return tensor, scale, pad_x, pad_y

    def infer(self, image: np.ndarray) -> list[RawDetection]:
        tensor, scale, pad_x, pad_y = self._preprocess(image)
        output = self.session.run([self.output_name], {self.input_name: tensor})[0]
        predictions = np.transpose(output, (0, 2, 1))[0]
        boxes, scores = predictions[:, :4], predictions[:, 4:]
        class_ids = np.argmax(scores, axis=1)
        confidences = np.max(scores, axis=1)
        mask = confidences > self.confidence_threshold
        boxes, class_ids, confidences = boxes[mask], class_ids[mask], confidences[mask]
        if not len(boxes):
            return []
        xywh = np.column_stack((boxes[:, 0] - boxes[:, 2] / 2, boxes[:, 1] - boxes[:, 3] / 2, boxes[:, 2], boxes[:, 3]))
        indices = cv2.dnn.NMSBoxes(xywh.tolist(), confidences.tolist(), self.confidence_threshold, self.iou_threshold)
        if len(indices) == 0:
            return []
        detections: list[RawDetection] = []
        for index in np.array(indices).flatten():
            x, y, width, height = xywh[index]
            x1, y1 = (x - pad_x) / scale, (y - pad_y) / scale
            x2, y2 = (x + width - pad_x) / scale, (y + height - pad_y) / scale
            class_id = int(class_ids[index])
            if 0 <= class_id < len(self.class_names):
                detections.append(RawDetection(self.class_names[class_id], float(confidences[index]), (float(x1), float(y1), float(x2), float(y2))))
        return detections
