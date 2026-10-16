from __future__ import annotations

from collections import Counter
from statistics import median

from cloud.domain import DetectedRegion, StructuredHand, TileDetection


def keep_detections_in_source_regions(
    detections: list[TileDetection],
    regions: list[DetectedRegion],
) -> list[TileDetection]:
    by_id = {region.region_id: region for region in regions}
    kept: list[TileDetection] = []
    for item in detections:
        region = by_id.get(item.source_region_id)
        if region is None:
            continue
        box = item.bbox_in_original_image
        if region.bbox.contains_point(box.center_x, box.center_y):
            kept.append(item)
    return kept


BONUS_PREFIXES = ("f",)
BONUS_TILES = {"s1", "s2", "s3", "s4", "f1", "f2", "f3", "f4"}
TILE_SUIT_ORDER = {"m": 0, "p": 1, "s": 2, "z": 3}


def _tile_code_sort_key(tile: str) -> tuple[int, int, str]:
    if len(tile) == 2 and tile[0].isdigit() and tile[1] in TILE_SUIT_ORDER:
        return TILE_SUIT_ORDER[tile[1]], int(tile[0]), tile
    return len(TILE_SUIT_ORDER), 0, tile


def is_bonus(tile: str) -> bool:
    return tile in BONUS_TILES or tile.startswith(BONUS_PREFIXES)


def _deduplicate(detections: list[TileDetection], iou_threshold: float, assignment_margin: float) -> tuple[list[TileDetection], bool]:
    kept: list[TileDetection] = []
    ambiguous_assignment = False
    for candidate in sorted(detections, key=lambda item: item.combined_score, reverse=True):
        duplicate = next((item for item in kept if candidate.bbox_in_original_image.iou(item.bbox_in_original_image) >= iou_threshold), None)
        if duplicate is None:
            kept.append(candidate)
            continue
        if duplicate.region_type != candidate.region_type and abs(duplicate.combined_score - candidate.combined_score) < assignment_margin:
            ambiguous_assignment = True
    return kept, ambiguous_assignment


def _meld_type(tiles: list[str]) -> str:
    if len(tiles) == 4 and len(set(tiles)) == 1:
        return "kan"
    if len(tiles) == 3 and len(set(tiles)) == 1:
        return "pon"
    if len(tiles) == 3:
        try:
            numbers = sorted(int(tile[0]) for tile in tiles)
            suits = {tile[1] for tile in tiles}
        except (ValueError, IndexError):
            return "unknown"
        if len(suits) == 1 and next(iter(suits)) in {"m", "p", "s"} and numbers == list(range(numbers[0], numbers[0] + 3)):
            return "chi"
    return "unknown"


def _split_legal_meld_chunk(group: list[TileDetection]) -> list[list[TileDetection]] | None:
    ordered = sorted(group, key=lambda value: value.bbox_in_original_image.center_x)
    memo: dict[int, list[list[TileDetection]] | None] = {}

    def split_from(start: int) -> list[list[TileDetection]] | None:
        if start == len(ordered):
            return []
        if start in memo:
            return memo[start]
        for size in (4, 3):
            chunk = ordered[start : start + size]
            if len(chunk) == size and _meld_type([item.tile for item in chunk]) != "unknown":
                rest = split_from(start + size)
                if rest is not None:
                    memo[start] = [chunk, *rest]
                    return memo[start]
        memo[start] = None
        return None

    return split_from(0)


def _cluster_melds(detections: list[TileDetection]) -> list[list[TileDetection]]:
    if not detections:
        return []
    typical_height = median(item.bbox_in_original_image.height for item in detections)
    rows: list[list[TileDetection]] = []
    for item in sorted(detections, key=lambda value: value.bbox_in_original_image.center_y):
        if not rows or abs(item.bbox_in_original_image.center_y - median(value.bbox_in_original_image.center_y for value in rows[-1])) > typical_height * 1.2:
            rows.append([item])
        else:
            rows[-1].append(item)

    groups: list[list[TileDetection]] = []
    for row in rows:
        ordered = sorted(row, key=lambda value: value.bbox_in_original_image.center_x)
        typical_width = median(item.bbox_in_original_image.width for item in ordered)
        current = [ordered[0]]
        for item in ordered[1:]:
            previous = current[-1]
            gap = item.bbox_in_original_image.x1 - previous.bbox_in_original_image.x2
            if gap > typical_width * 1.5:
                split_groups = _split_legal_meld_chunk(current)
                groups.extend(split_groups if split_groups is not None else [current])
                current = [item]
            else:
                current.append(item)
        split_groups = _split_legal_meld_chunk(current)
        groups.extend(split_groups if split_groups is not None else [current])
    return groups


def structure_tiles(
    regions: list[DetectedRegion],
    detections: list[TileDetection],
    dedup_iou_threshold: float,
    assignment_margin: float,
) -> StructuredHand:
    active_region_ids = {region.region_id for region in regions}
    accepted, assignment_ambiguous = _deduplicate(
        [item for item in detections if item.source_region_id in active_region_ids], dedup_iou_threshold, assignment_margin
    )
    if assignment_ambiguous:
        return StructuredHand(accepted_detections=accepted, diagnostic_code="REGION_ASSIGNMENT_AMBIGUOUS")

    hand = [item for item in accepted if item.region_type == "hand" and not is_bonus(item.tile)]
    meld = [item for item in accepted if item.region_type == "meld" and not is_bonus(item.tile)]
    bonus = [item for item in accepted if is_bonus(item.tile)]
    if not any(region.region_type == "hand" for region in regions):
        return StructuredHand(accepted_detections=accepted, diagnostic_code="HAND_REGION_NOT_FOUND")
    if not hand:
        return StructuredHand(accepted_detections=accepted, diagnostic_code="NO_VALID_HAND_TILES")

    meld_groups: list[dict] = []
    for group in _cluster_melds(meld):
        ordered_group = sorted(group, key=lambda item: item.bbox_in_original_image.center_x)
        meld_groups.append({"type": _meld_type([item.tile for item in ordered_group]), "tiles": [item.tile for item in ordered_group]})

    if any(group["type"] == "unknown" for group in meld_groups):
        return StructuredHand(accepted_detections=accepted, diagnostic_code="INVALID_MELD_STRUCTURE")
    all_basic_tiles = [item.tile for item in hand] + [tile for group in meld_groups for tile in group["tiles"]]
    if any(count > 4 for count in Counter(all_basic_tiles).values()):
        return StructuredHand(accepted_detections=accepted, diagnostic_code="DUPLICATE_TILE_OVER_LIMIT")
    kan_count = sum(1 for group in meld_groups if group["type"] == "kan")
    adjusted_count = len(all_basic_tiles) - kan_count
    if adjusted_count not in {13, 14}:
        return StructuredHand(accepted_detections=accepted, diagnostic_code="INVALID_TILE_COUNT")

    return StructuredHand(
        hand_tiles=sorted((item.tile for item in hand), key=_tile_code_sort_key),
        meld_groups=meld_groups,
        bonus_tiles=[item.tile for item in sorted(bonus, key=lambda item: item.bbox_in_original_image.center_x)],
        accepted_detections=accepted,
    )
