from typing import Any, Dict, Optional

from pydantic import BaseModel, Field


DEFAULT_RULE_PROFILE_ID = "guangdong"
FORBIDDEN_RULE_CONFIG_KEYS = {
    "callable",
    "code",
    "eval",
    "exec",
    "javascript",
    "js",
    "module",
    "python",
    "script",
}


class RuleProfile(BaseModel):
    id: str = Field(..., min_length=1)
    name: str = Field(..., min_length=1)
    version: int = Field(default=1, ge=1)
    winning: Dict[str, Any] = Field(default_factory=dict)
    scoring: Dict[str, Any] = Field(default_factory=dict)
    suggestion: Dict[str, Any] = Field(default_factory=dict)

    class Config:
        extra = "forbid"


DEFAULT_RULE_PROFILE = RuleProfile(
    id=DEFAULT_RULE_PROFILE_ID,
    name="Guangdong Mahjong",
    version=1,
    winning={
        "patterns": ["standard_4_melds_1_pair"],
        "special_patterns": [],
    },
    scoring={
        "mode": "mvp_hand_efficiency_only",
    },
    suggestion={
        "strategy": "hand_efficiency",
    },
)


def normalize_rule_profile_id(rule_profile: Optional[str]) -> str:
    value = (rule_profile or DEFAULT_RULE_PROFILE_ID).strip()
    return value or DEFAULT_RULE_PROFILE_ID


def validate_rule_profile_config(raw_config: Dict[str, Any]) -> RuleProfile:
    if not isinstance(raw_config, dict):
        raise ValueError("rule profile config must be a JSON object")

    _reject_executable_fields(raw_config)
    return RuleProfile(**raw_config)


def _reject_executable_fields(value: Any) -> None:
    if isinstance(value, dict):
        for key, nested_value in value.items():
            if str(key).lower() in FORBIDDEN_RULE_CONFIG_KEYS:
                raise ValueError(f"rule profile config cannot contain executable field: {key}")
            _reject_executable_fields(nested_value)
    elif isinstance(value, list):
        for item in value:
            _reject_executable_fields(item)
