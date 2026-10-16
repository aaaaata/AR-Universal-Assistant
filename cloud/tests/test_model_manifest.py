import json

import pytest

from cloud.adapters import OnnxRegionDetector
from cloud.errors import ModelNotReadyError


def test_region_manifest_requires_both_hand_and_meld_classes(tmp_path):
    manifest = tmp_path / "region.manifest.json"
    manifest.write_text(
        json.dumps(
            {
                "model_path": "region.onnx",
                "class_names_path": "class_names.txt",
                "model_version": "region-test-v1",
                "class_mapping": {"hand": "hand"},
            }
        ),
        encoding="utf-8",
    )

    with pytest.raises(
        ModelNotReadyError,
        match="must map both hand and meld classes",
    ):
        OnnxRegionDetector(manifest)
