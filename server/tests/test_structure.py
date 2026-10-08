from server.domain import BBox, DetectedRegion, TileDetection
from server.structure import keep_detections_in_source_regions, structure_tiles


def test_overlapping_regions_do_not_duplicate_tiles(regions, tile_detections):
    result = structure_tiles(regions, tile_detections, 0.5, 0.1)

    assert result.diagnostic_code is None
    assert len(result.hand_tiles) == 14
    assert result.hand_tiles.count("1m") == 1
    assert len(result.accepted_detections) == 14


def test_keep_detections_drops_centers_outside_the_original_region():
    region = DetectedRegion("hand-a", "hand", BBox(332, 912, 967, 1010), 0.97, "fake")
    outside = TileDetection("1p", 0.33, BBox(0, 0, 21, 80), BBox(299, 908, 320, 990), "hand-a", "hand", 0.97)
    overhanging = TileDetection("7p", 0.94, BBox(0, 0, 48, 45), BBox(328, 931, 376, 980), "hand-a", "hand", 0.97)

    kept = keep_detections_in_source_regions([outside, overhanging], [region])

    assert [item.tile for item in kept] == ["7p"]


def test_assignment_conflict_is_diagnostic(regions, tile_detections):
    from server.domain import BBox, DetectedRegion, TileDetection

    regions = regions + [DetectedRegion("meld-a", "meld", BBox(0, 0, 300, 160), 0.95, "fake-region-v1")]
    conflicting = tile_detections[0]

    conflict = TileDetection(
        conflicting.tile,
        0.91,
        conflicting.bbox_in_roi,
        conflicting.bbox_in_original_image,
        "meld-a",
        "meld",
        0.95,
    )
    result = structure_tiles(regions, tile_detections + [conflict], 0.5, 0.1)

    assert result.diagnostic_code == "REGION_ASSIGNMENT_AMBIGUOUS"
