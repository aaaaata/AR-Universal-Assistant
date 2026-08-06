from pydantic import BaseModel, Field
from typing import List, Optional, Dict, Any

from rule_profiles import DEFAULT_RULE_PROFILE_ID

class StartSessionRequest(BaseModel):
    session_id: str

class EndSessionRequest(BaseModel):
    session_id: str

class AnalyzeResponse(BaseModel):
    user_hand: List[str]
    melded_tiles: List[str]
    suggested_play: str
    rule_profile: str = DEFAULT_RULE_PROFILE_ID
    recognition_confidence: Optional[float] = Field(None, ge=0.0, le=1.0)
    suggestion_confidence: Optional[float] = Field(None, ge=0.0, le=1.0)
    annotated_image_path: Optional[str] = None
    action_detected: Optional[str] = None
    warning: Optional[str] = None
    is_stable: bool = True

class ProcessAudioResponse(BaseModel):
    transcript: str
    events: List[Dict[str, Any]]
    updated_visible_tiles_count: int
    details: List[str]
