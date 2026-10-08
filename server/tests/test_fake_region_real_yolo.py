from __future__ import annotations

import hashlib
from pathlib import Path

from fastapi.testclient import TestClient

from server.adapters import FakeRegionDetector, create_tile_detector
from server.app import create_app
from server.config import Settings
from server.domain import BBox, DetectedRegion
from server.rules import load_rule_profile
from server.service import AnalysisService
from server.storage import SQLiteStore


def test_raw_png_with_fake_regions_reaches_current_tile_detector(tmp_path: Path) -> None:
    """Regression test for the provisional region-model gap.

    The region detector is deliberately fake, while image decoding, ROI expansion,
    real tile detection through the current manifest, structure parsing, rule analysis, storage and the
    raw-binary HTTP API all use the production implementations.
    """
    repo_root = Path(__file__).resolve().parents[2]
    sample_path = repo_root / "server" / "3467m2356d5578s11z.png"
    image = sample_path.read_bytes()
    settings = Settings(
        api_key="test-key",
        database_path=tmp_path / "cloud.db",
        tile_manifest_path=repo_root / "server" / "models" / "tile.manifest.json",
        region_manifest_path=None,
    )
    regions = [
        DetectedRegion("fake-hand-top", "hand", BBox(0, 30, 970, 205), 0.99, "fake-region-v1"),
        DetectedRegion("fake-hand-bottom", "hand", BBox(0, 200, 970, 390), 0.99, "fake-region-v1"),
        DetectedRegion("fake-empty-meld", "meld", BBox(0, 395, 100, 414), 0.99, "fake-region-v1"),
    ]
    service = AnalysisService(
        settings=settings,
        store=SQLiteStore(settings.database_path),
        profile=load_rule_profile(repo_root / "server" / "rules" / "guangdong_mvp_v1.json"),
        region_detector=FakeRegionDetector(regions),
        tile_detector=create_tile_detector(settings.tile_manifest_path),
    )
    client = TestClient(create_app(settings, service))
    headers = {"X-API-Key": "test-key"}
    session = client.post("/api/v1/mahjong/sessions", headers=headers, json={})
    assert session.status_code == 200

    response = client.post(
        f"/api/v1/mahjong/sessions/{session.json()['session_id']}/analyses",
        headers={
            **headers,
            "Content-Type": "image/png",
            "X-Request-Id": "fake-region-real-yolo-1",
            "X-Sequence-Id": "1",
            "X-Frame-Ts": "1723344000000",
            "X-Frame-Count": "1",
        },
        content=image,
    )

    assert response.status_code == 200
    payload = response.json()
    assert payload["status"] in {"complete", "diagnostic"}
    assert payload["regions"][0]["region_id"] == "fake-hand-top"
    assert payload["model_versions"]["tile"] == "yolo26m-mahjong-detect-finetune-v1"
    assert "timings_ms" in payload
    assert "tile_detection" in payload["timings_ms"]
    if payload["status"] == "complete":
        assert payload["diagnostic_code"] is None
        assert payload["suggestion"] is not None
    else:
        assert payload["diagnostic_code"] in {"NO_VALID_HAND_TILES", "INVALID_TILE_COUNT", "DUPLICATE_TILE_OVER_LIMIT", "INVALID_MELD_STRUCTURE"}
