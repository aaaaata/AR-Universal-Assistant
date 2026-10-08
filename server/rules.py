from __future__ import annotations

import json
from dataclasses import dataclass
from pathlib import Path
from typing import Any

from mahjong.shanten import Shanten

from server.domain import StructuredHand


class UnsupportedMeldError(Exception):
    def __init__(self, meld_types: list[str]) -> None:
        self.meld_types = meld_types
        super().__init__("Unsupported meld types: " + ", ".join(meld_types))


MPSZ_TILES = [f"{number}{suit}" for suit in ("m", "p", "s") for number in range(1, 10)] + [f"{number}z" for number in range(1, 8)]
MPSZ_TO_INDEX = {tile: index for index, tile in enumerate(MPSZ_TILES)}


@dataclass(frozen=True)
class RuleProfile:
    rule_profile_id: str
    version: str
    tile_set: list[str]
    allowed_melds: list[str]
    algorithm_version: str


def load_rule_profile(path: Path) -> RuleProfile:
    content = json.loads(path.read_text(encoding="utf-8"))
    return RuleProfile(
        rule_profile_id=content["rule_profile_id"],
        version=content["version"],
        tile_set=list(content["tile_set"]),
        allowed_melds=list(content["allowed_melds"]),
        algorithm_version=content["algorithm_version"],
    )


class GuangdongMvpRuleEngine:
    """34-tile tingpai-steps/jinzhang recommendation. It intentionally excludes scoring."""

    def __init__(self, profile: RuleProfile) -> None:
        self.profile = profile
        self.tingpai_calculator = Shanten()

    @staticmethod
    def _counts(tiles: list[str]) -> list[int]:
        counts = [0] * 34
        for tile in tiles:
            counts[MPSZ_TO_INDEX[tile]] += 1
        return counts

    @staticmethod
    def _meld_tiles(hand: StructuredHand) -> list[str]:
        return [tile for group in hand.meld_groups for tile in group["tiles"]]

    def _validate_melds(self, hand: StructuredHand) -> None:
        unsupported = sorted({group["type"] for group in hand.meld_groups if group["type"] not in self.profile.allowed_melds})
        if unsupported:
            raise UnsupportedMeldError(unsupported)

    def _effective_counts(self, hand: StructuredHand) -> list[int]:
        counts = self._counts(hand.hand_tiles + self._meld_tiles(hand))
        for group in hand.meld_groups:
            if group["type"] != "kan":
                continue
            tile = group["tiles"][0]
            index = MPSZ_TO_INDEX[tile]
            counts[index] -= 1
        return counts

    def _draw_candidates(self, calculation_counts: list[int], visible_counts: list[int], tingpai_steps: int) -> list[dict[str, Any]]:
        candidates: list[dict[str, Any]] = []
        for index, visible_count in enumerate(visible_counts):
            if visible_count >= 4:
                continue
            calculation_counts[index] += 1
            next_tingpai_steps = self.tingpai_calculator.calculate_shanten(calculation_counts)
            calculation_counts[index] -= 1
            if next_tingpai_steps < tingpai_steps:
                candidates.append({"tile": MPSZ_TILES[index], "remaining": 4 - visible_count, "tingpai_steps": next_tingpai_steps})
        return candidates

    def _calculate_jinzhang(self, calculation_counts: list[int], visible_counts: list[int], tingpai_steps: int) -> tuple[int, list[str]]:
        candidates = self._draw_candidates(calculation_counts, visible_counts, tingpai_steps)
        return sum(candidate["remaining"] for candidate in candidates), [candidate["tile"] for candidate in candidates]

    def _discard_candidates(self, hidden_counts: list[int], calculation_counts: list[int], visible_counts: list[int]) -> list[dict[str, Any]]:
        candidates: list[dict[str, Any]] = []
        for index, count in enumerate(hidden_counts):
            if not count:
                continue
            calculation_counts[index] -= 1
            calculated_tingpai_steps = self.tingpai_calculator.calculate_shanten(calculation_counts)
            jinzhang_count, jinzhang_tiles = self._calculate_jinzhang(calculation_counts, visible_counts, calculated_tingpai_steps)
            calculation_counts[index] += 1
            candidates.append({"tile": MPSZ_TILES[index], "tingpai_steps": calculated_tingpai_steps, "jinzhang_count": jinzhang_count, "jinzhang_tiles": jinzhang_tiles})
        return sorted(candidates, key=lambda candidate: (candidate["tingpai_steps"], -candidate["jinzhang_count"], candidate["tile"]))

    def analyze(self, hand: StructuredHand) -> dict[str, Any]:
        self._validate_melds(hand)
        hidden_counts = self._counts(hand.hand_tiles)
        visible_counts = self._counts(hand.hand_tiles + self._meld_tiles(hand))
        calculation_counts = self._effective_counts(hand)
        adjusted_count = sum(calculation_counts)
        tingpai_steps = self.tingpai_calculator.calculate_shanten(calculation_counts)
        if adjusted_count % 3 == 2:
            candidates = self._discard_candidates(hidden_counts, calculation_counts, visible_counts)
            best = candidates[0]
            margin = 1.0
            if len(candidates) > 1:
                second = candidates[1]
                if best["tingpai_steps"] == second["tingpai_steps"]:
                    margin = min(1.0, 0.2 + (best["jinzhang_count"] - second["jinzhang_count"]) / max(1, best["jinzhang_count"]))
            return {
                "advice_type": "discard",
                "suggestion": best,
                "candidates": candidates[:3],
                "tingpai_steps": tingpai_steps,
                "can_hu": tingpai_steps == -1,
                "decision_margin": max(0.0, margin),
            }

        draw_candidates = self._draw_candidates(calculation_counts, visible_counts, tingpai_steps)
        jinzhang_count = sum(candidate["remaining"] for candidate in draw_candidates)
        jinzhang_tiles = [candidate["tile"] for candidate in draw_candidates]
        return {
            "advice_type": "draw_wait",
            "suggestion": {
                "type": "draw_wait",
                "jinzhang_count": jinzhang_count,
                "jinzhang_tiles": jinzhang_tiles,
                "tingpai_steps": tingpai_steps,
            },
            "candidates": draw_candidates,
            "tingpai_steps": tingpai_steps,
            "jinzhang_count": jinzhang_count,
            "jinzhang_tiles": jinzhang_tiles,
            "can_hu": tingpai_steps == -1,
            "decision_margin": 1.0,
        }
