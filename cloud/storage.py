from __future__ import annotations

import json
import sqlite3
import threading
import uuid
from pathlib import Path
from typing import Any

from cloud.errors import CloudError


class SQLiteStore:
    def __init__(self, database_path: Path) -> None:
        self.database_path = database_path
        self.database_path.parent.mkdir(parents=True, exist_ok=True)
        self.lock = threading.Lock()
        self._initialize()

    def _connection(self) -> sqlite3.Connection:
        connection = sqlite3.connect(self.database_path)
        connection.row_factory = sqlite3.Row
        connection.execute("PRAGMA foreign_keys = ON")
        return connection

    def _initialize(self) -> None:
        with self._connection() as connection:
            connection.executescript(
                """
                CREATE TABLE IF NOT EXISTS sessions (
                    session_id TEXT PRIMARY KEY,
                    rule_profile_id TEXT NOT NULL,
                    last_sequence_id INTEGER NOT NULL DEFAULT -1,
                    status TEXT NOT NULL DEFAULT 'active',
                    created_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP,
                    ended_at TEXT
                );
                CREATE TABLE IF NOT EXISTS analyses (
                    analysis_id TEXT PRIMARY KEY,
                    session_id TEXT NOT NULL,
                    request_id TEXT NOT NULL,
                    sequence_id INTEGER NOT NULL,
                    status TEXT NOT NULL,
                    image_sha256 TEXT,
                    image_format TEXT,
                    image_width INTEGER,
                    image_height INTEGER,
                    frame_ts INTEGER,
                    result_json TEXT,
                    created_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP,
                    completed_at TEXT,
                    UNIQUE(session_id, request_id),
                    FOREIGN KEY(session_id) REFERENCES sessions(session_id)
                );
                CREATE TABLE IF NOT EXISTS session_in_flights (
                    session_id TEXT PRIMARY KEY,
                    request_id TEXT NOT NULL,
                    acquired_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP,
                    FOREIGN KEY(session_id) REFERENCES sessions(session_id)
                );
                CREATE TABLE IF NOT EXISTS debug_assets (
                    analysis_id TEXT PRIMARY KEY,
                    image_path TEXT,
                    size_bytes INTEGER NOT NULL DEFAULT 0,
                    status TEXT NOT NULL DEFAULT 'not_stored',
                    storage_class TEXT NOT NULL DEFAULT 'normal',
                    stored_at TEXT,
                    purged_at TEXT,
                    purge_reason TEXT,
                    FOREIGN KEY(analysis_id) REFERENCES analyses(analysis_id)
                );
                CREATE INDEX IF NOT EXISTS idx_debug_assets_status_stored_at
                    ON debug_assets(status, stored_at);
                CREATE TABLE IF NOT EXISTS evidences (
                    evidence_id TEXT PRIMARY KEY,
                    analysis_id TEXT NOT NULL UNIQUE,
                    source TEXT NOT NULL,
                    note TEXT,
                    signature TEXT,
                    status TEXT NOT NULL DEFAULT 'active',
                    created_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP,
                    hold_until TEXT NOT NULL,
                    exported_at TEXT,
                    expired_at TEXT,
                    FOREIGN KEY(analysis_id) REFERENCES analyses(analysis_id)
                );
                CREATE INDEX IF NOT EXISTS idx_evidences_active_hold_until
                    ON evidences(status, hold_until);
                CREATE INDEX IF NOT EXISTS idx_evidences_signature_created
                    ON evidences(signature, created_at);
                CREATE TABLE IF NOT EXISTS maintenance_leases (
                    lease_name TEXT PRIMARY KEY,
                    holder_id TEXT NOT NULL,
                    acquired_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP,
                    expires_at TEXT NOT NULL
                );
                CREATE TABLE IF NOT EXISTS session_recordings (
                    session_id TEXT PRIMARY KEY,
                    status TEXT NOT NULL DEFAULT 'active',
                    frame_count INTEGER NOT NULL DEFAULT 0,
                    total_bytes INTEGER NOT NULL DEFAULT 0,
                    incomplete_reason TEXT,
                    started_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP,
                    ended_at TEXT,
                    expires_at TEXT,
                    exported_at TEXT,
                    FOREIGN KEY(session_id) REFERENCES sessions(session_id)
                );
                CREATE INDEX IF NOT EXISTS idx_session_recordings_status_expiry
                    ON session_recordings(status, expires_at);
                CREATE TABLE IF NOT EXISTS session_recording_exports (
                    lease_name TEXT PRIMARY KEY,
                    session_id TEXT NOT NULL,
                    holder_id TEXT NOT NULL,
                    acquired_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP,
                    expires_at TEXT NOT NULL,
                    FOREIGN KEY(session_id) REFERENCES session_recordings(session_id)
                );
                CREATE TABLE IF NOT EXISTS session_recording_frames (
                    session_id TEXT NOT NULL,
                    sequence_id INTEGER NOT NULL,
                    analysis_id TEXT NOT NULL UNIQUE,
                    request_id TEXT NOT NULL,
                    content_type TEXT NOT NULL,
                    rotation_degrees INTEGER NOT NULL DEFAULT 0,
                    frame_ts INTEGER NOT NULL,
                    sha256 TEXT NOT NULL,
                    size_bytes INTEGER NOT NULL,
                    source_path TEXT NOT NULL,
                    stored_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP,
                    PRIMARY KEY(session_id, sequence_id),
                    FOREIGN KEY(session_id) REFERENCES sessions(session_id),
                    FOREIGN KEY(analysis_id) REFERENCES analyses(analysis_id)
                );
                CREATE INDEX IF NOT EXISTS idx_session_recording_frames_session
                    ON session_recording_frames(session_id, sequence_id);
                """
            )
            analysis_columns = {row["name"] for row in connection.execute("PRAGMA table_info(analyses)")}
            if "frame_ts" not in analysis_columns:
                connection.execute("ALTER TABLE analyses ADD COLUMN frame_ts INTEGER")
            # Earlier databases predate debug asset / evidence metadata. New tables
            # above are additive, so existing analyses remain readable and simply
            # have no asset row until the next write or maintenance pass.

    def create_session(
        self,
        rule_profile_id: str,
        *,
        recording_mode: str = "normal",
        recording_retention_days: int = 1,
        recording_max_active: int = 1,
    ) -> str:
        if recording_mode not in {"normal", "regression"}:
            raise CloudError("INVALID_RECORDING_MODE", "recording_mode must be normal or regression", 422)
        if recording_retention_days <= 0 or recording_max_active <= 0:
            raise ValueError("recording retention and active limits must be positive")
        session_id = str(uuid.uuid4())
        with self.lock, self._connection() as connection:
            connection.execute("BEGIN IMMEDIATE")
            if recording_mode == "regression":
                active = connection.execute(
                    "SELECT COUNT(*) AS count FROM session_recordings WHERE status = 'active'"
                ).fetchone()
                if int(active["count"]) >= recording_max_active:
                    raise CloudError(
                        "REGRESSION_RECORDING_BUSY",
                        "Another regression recording session is already active",
                        409,
                    )
            connection.execute("INSERT INTO sessions(session_id, rule_profile_id) VALUES (?, ?)", (session_id, rule_profile_id))
            if recording_mode == "regression":
                connection.execute("INSERT INTO session_recordings(session_id) VALUES (?)", (session_id,))

        return session_id

    def get_session_recording(self, session_id: str) -> dict[str, Any] | None:
        with self._connection() as connection:
            row = connection.execute(
                "SELECT * FROM session_recordings WHERE session_id = ?",
                (session_id,),
            ).fetchone()
        return self._row_to_dict(row) if row else None

    def record_regression_frame(
        self,
        session_id: str,
        analysis_id: str,
        request_id: str,
        sequence_id: int,
        *,
        content_type: str,
        rotation_degrees: int,
        frame_ts: int,
        sha256: str,
        size_bytes: int,
        source_path: Path,
        max_bytes: int,
    ) -> bool:
        if size_bytes < 0 or max_bytes <= 0:
            raise ValueError("recording frame sizes must be positive")
        with self.lock, self._connection() as connection:
            connection.execute("BEGIN IMMEDIATE")
            recording = connection.execute(
                "SELECT * FROM session_recordings WHERE session_id = ?",
                (session_id,),
            ).fetchone()
            if not recording:
                return False
            if recording["status"] != "active":
                return False
            analysis = connection.execute(
                "SELECT session_id FROM analyses WHERE analysis_id = ?",
                (analysis_id,),
            ).fetchone()
            if not analysis or analysis["session_id"] != session_id:
                raise CloudError("ANALYSIS_NOT_FOUND", "Analysis was not found for the recording session", 404)
            duplicate = connection.execute(
                "SELECT 1 FROM session_recording_frames WHERE session_id = ? AND sequence_id = ?",
                (session_id, sequence_id),
            ).fetchone()
            if duplicate:
                return True
            if int(recording["total_bytes"]) + size_bytes > max_bytes:
                connection.execute(
                    "UPDATE session_recordings SET status = 'incomplete', incomplete_reason = ? WHERE session_id = ?",
                    ("RECORDING_SESSION_CAPACITY_EXCEEDED", session_id),
                )
                return False
            connection.execute(
                """
                INSERT INTO session_recording_frames(
                    session_id, sequence_id, analysis_id, request_id, content_type, rotation_degrees,
                    frame_ts, sha256, size_bytes, source_path
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """,
                (
                    session_id,
                    sequence_id,
                    analysis_id,
                    request_id,
                    content_type,
                    rotation_degrees,
                    frame_ts,
                    sha256,
                    size_bytes,
                    str(source_path),
                ),
            )
            connection.execute(
                "UPDATE session_recordings SET frame_count = frame_count + 1, total_bytes = total_bytes + ? WHERE session_id = ?",
                (size_bytes, session_id),
            )
        return True

    def mark_session_recording_incomplete(self, session_id: str, reason: str) -> None:
        with self._connection() as connection:
            connection.execute(
                """
                UPDATE session_recordings
                SET status = CASE WHEN status = 'active' THEN 'incomplete' ELSE status END,
                    incomplete_reason = COALESCE(incomplete_reason, ?)
                WHERE session_id = ?
                """,
                (reason, session_id),
            )

    def end_session_and_finalize_recording(self, session_id: str, retention_days: int) -> dict[str, Any] | None:
        if retention_days <= 0:
            raise ValueError("recording retention_days must be positive")
        with self.lock, self._connection() as connection:
            connection.execute("BEGIN IMMEDIATE")
            session = connection.execute(
                "SELECT status FROM sessions WHERE session_id = ?",
                (session_id,),
            ).fetchone()
            if not session or session["status"] != "active":
                raise CloudError("SESSION_NOT_FOUND", "Session is not active", 404)
            recording = connection.execute(
                "SELECT * FROM session_recordings WHERE session_id = ?",
                (session_id,),
            ).fetchone()
            if not recording:
                connection.execute(
                    "UPDATE sessions SET status = 'ended', ended_at = CURRENT_TIMESTAMP WHERE session_id = ?",
                    (session_id,),
                )
                connection.execute("DELETE FROM session_in_flights WHERE session_id = ?", (session_id,))
                return None
            counts = connection.execute(
                """
                SELECT
                    COUNT(*) AS analysis_count,
                    SUM(CASE WHEN status = 'pending' THEN 1 ELSE 0 END) AS pending_count,
                    SUM(CASE WHEN status = 'failed' THEN 1 ELSE 0 END) AS failed_count,
                    SUM(CASE WHEN status = 'completed' THEN 1 ELSE 0 END) AS completed_count
                FROM analyses WHERE session_id = ?
                """,
                (session_id,),
            ).fetchone()
            in_flight = connection.execute(
                "SELECT 1 FROM session_in_flights WHERE session_id = ? LIMIT 1",
                (session_id,),
            ).fetchone()
            frames = connection.execute(
                "SELECT COUNT(*) AS count FROM session_recording_frames WHERE session_id = ?",
                (session_id,),
            ).fetchone()
            incomplete_reason = recording["incomplete_reason"]
            completed_status = recording["status"]
            if completed_status == "active":
                analysis_count = int(counts["analysis_count"] or 0)
                pending_count = int(counts["pending_count"] or 0)
                failed_count = int(counts["failed_count"] or 0)
                completed_count = int(counts["completed_count"] or 0)
                if in_flight:
                    completed_status = "incomplete"
                    incomplete_reason = "RECORDING_SESSION_ENDED_WITH_IN_FLIGHT_ANALYSIS"
                elif pending_count > 0:
                    completed_status = "incomplete"
                    incomplete_reason = "RECORDING_SESSION_ENDED_WITH_PENDING_ANALYSES"
                elif failed_count > 0:
                    completed_status = "incomplete"
                    incomplete_reason = "RECORDING_SESSION_CONTAINS_FAILED_ANALYSES"
                elif analysis_count == 0:
                    completed_status = "incomplete"
                    incomplete_reason = "RECORDING_EMPTY"
                elif completed_count != analysis_count or int(frames["count"]) != analysis_count:
                    completed_status = "incomplete"
                    incomplete_reason = "RECORDING_FRAME_INDEX_INCOMPLETE"
                else:
                    completed_status = "completed"
            connection.execute(
                "UPDATE sessions SET status = 'ended', ended_at = CURRENT_TIMESTAMP WHERE session_id = ?",
                (session_id,),
            )
            connection.execute("DELETE FROM session_in_flights WHERE session_id = ?", (session_id,))
            connection.execute(
                """
                UPDATE session_recordings
                SET status = ?, incomplete_reason = ?, ended_at = CURRENT_TIMESTAMP,
                    expires_at = datetime('now', ?)
                WHERE session_id = ?
                """,
                (completed_status, incomplete_reason, f"+{int(retention_days)} days", session_id),
            )
            row = connection.execute(
                "SELECT * FROM session_recordings WHERE session_id = ?",
                (session_id,),
            ).fetchone()
        return self._row_to_dict(row) if row else None

    def get_regression_pack_snapshot(self, session_id: str) -> dict[str, Any]:
        with self._connection() as connection:
            recording = connection.execute(
                """
                SELECT recording.*, session.rule_profile_id, session.status AS session_status,
                       session.created_at AS session_created_at, session.ended_at AS session_ended_at
                FROM session_recordings recording
                JOIN sessions session ON session.session_id = recording.session_id
                WHERE recording.session_id = ?
                """,
                (session_id,),
            ).fetchone()
            if not recording:
                raise CloudError("REGRESSION_RECORDING_NOT_FOUND", "Regression recording was not found", 404)
            if recording["expires_at"] is not None and connection.execute(
                "SELECT ? <= CURRENT_TIMESTAMP", (recording["expires_at"],)
            ).fetchone()[0]:
                raise CloudError("REGRESSION_RECORDING_EXPIRED", "Regression recording has expired", 409)
            if recording["session_status"] != "ended":
                raise CloudError(
                    "REGRESSION_RECORDING_NOT_EXPORTABLE",
                    "Only an ended regression recording can be exported",
                    409,
                )
            if recording["status"] != "completed" or int(recording["frame_count"]) <= 0:
                raise CloudError(
                    "REGRESSION_RECORDING_NOT_EXPORTABLE",
                    "Only a non-empty completed regression recording can be exported",
                    409,
                )
            frames = connection.execute(
                """
                SELECT frame.*, analysis.status AS analysis_status, analysis.result_json,
                       analysis.image_width, analysis.image_height, analysis.image_format
                FROM session_recording_frames frame
                JOIN analyses analysis ON analysis.analysis_id = frame.analysis_id
                WHERE frame.session_id = ?
                ORDER BY frame.sequence_id ASC
                """,
                (session_id,),
            ).fetchall()
        session = {
            "session_id": session_id,
            "rule_profile_id": recording["rule_profile_id"],
            "status": recording["session_status"],
            "created_at": recording["session_created_at"],
            "ended_at": recording["session_ended_at"],
        }
        return {
            "recording": self._row_to_dict(recording),
            "session": session,
            "frames": [
                {
                    **self._row_to_dict(frame),
                    "result": self._load_result_json(frame["result_json"]),
                }
                for frame in frames
            ],
        }

    def acquire_session_recording_export(self, session_id: str, lease_seconds: int = 24 * 60 * 60) -> str:
        """Acquire the single global recording-export lease after state validation."""
        if lease_seconds <= 0:
            raise ValueError("recording export lease_seconds must be positive")
        holder_id = str(uuid.uuid4())
        with self.lock, self._connection() as connection:
            connection.execute("BEGIN IMMEDIATE")
            recording = connection.execute(
                """
                SELECT recording.status, recording.frame_count, recording.expires_at,
                       session.status AS session_status
                FROM session_recordings recording
                JOIN sessions session ON session.session_id = recording.session_id
                WHERE recording.session_id = ?
                """,
                (session_id,),
            ).fetchone()
            if not recording:
                raise CloudError("REGRESSION_RECORDING_NOT_FOUND", "Regression recording was not found", 404)
            if recording["expires_at"] is not None and connection.execute(
                "SELECT ? <= CURRENT_TIMESTAMP", (recording["expires_at"],)
            ).fetchone()[0]:
                raise CloudError("REGRESSION_RECORDING_EXPIRED", "Regression recording has expired", 409)
            if (
                recording["session_status"] != "ended"
                or recording["status"] != "completed"
                or int(recording["frame_count"]) <= 0
            ):
                raise CloudError(
                    "REGRESSION_RECORDING_NOT_EXPORTABLE",
                    "Only an ended, non-empty completed regression recording can be exported",
                    409,
                )
            connection.execute(
                "DELETE FROM session_recording_exports WHERE expires_at <= CURRENT_TIMESTAMP"
            )
            try:
                connection.execute(
                    """
                    INSERT INTO session_recording_exports(
                        lease_name, session_id, holder_id, expires_at
                    ) VALUES ('regression_pack_export', ?, ?, datetime('now', ?))
                    """,
                    (session_id, holder_id, f"+{int(lease_seconds)} seconds"),
                )
            except sqlite3.IntegrityError as exc:
                raise CloudError(
                    "REGRESSION_PACK_EXPORT_BUSY",
                    "Another regression pack download is already in progress",
                    409,
                ) from exc
        return holder_id

    def release_session_recording_export(self, session_id: str, holder_id: str) -> None:
        with self._connection() as connection:
            connection.execute(
                """
                DELETE FROM session_recording_exports
                WHERE lease_name = 'regression_pack_export'
                  AND session_id = ? AND holder_id = ?
                """,
                (session_id, holder_id),
            )

    def try_acquire_session_recording_cleanup(self, session_id: str, lease_seconds: int = 120) -> str | None:
        """Serialize source deletion with pack construction and download."""
        if lease_seconds <= 0:
            raise ValueError("recording cleanup lease_seconds must be positive")
        holder_id = str(uuid.uuid4())
        with self.lock, self._connection() as connection:
            connection.execute("BEGIN IMMEDIATE")
            connection.execute(
                "DELETE FROM session_recording_exports WHERE expires_at <= CURRENT_TIMESTAMP"
            )
            try:
                connection.execute(
                    """
                    INSERT INTO session_recording_exports(
                        lease_name, session_id, holder_id, expires_at
                    ) VALUES ('regression_pack_export', ?, ?, datetime('now', ?))
                    """,
                    (session_id, holder_id, f"+{int(lease_seconds)} seconds"),
                )
            except sqlite3.IntegrityError:
                return None
        return holder_id

    def mark_session_recording_exported(self, session_id: str) -> None:
        with self._connection() as connection:
            result = connection.execute(
                """
                UPDATE session_recordings
                SET exported_at = COALESCE(exported_at, CURRENT_TIMESTAMP)
                WHERE session_id = ? AND status = 'completed' AND frame_count > 0
                """,
                (session_id,),
            )
        if result.rowcount != 1:
            raise CloudError(
                "REGRESSION_RECORDING_NOT_EXPORTABLE",
                "Only a non-empty completed regression recording can be exported",
                409,
            )

    def list_expired_session_recordings(self) -> list[dict[str, Any]]:
        with self._connection() as connection:
            rows = connection.execute(
                """
                SELECT * FROM session_recordings
                WHERE status IN ('completed', 'incomplete') AND expires_at <= CURRENT_TIMESTAMP
                  AND NOT EXISTS (
                    SELECT 1 FROM session_recording_exports export
                    WHERE export.session_id = session_recordings.session_id
                      AND export.expires_at > CURRENT_TIMESTAMP
                  )
                ORDER BY expires_at ASC
                """
            ).fetchall()
        return [self._row_to_dict(row) for row in rows]

    def recover_stale_session_recordings(self, retention_days: int) -> int:
        """Atomically stop active recordings that can no longer be completed."""
        if retention_days <= 0:
            raise ValueError("recording retention_days must be positive")
        modifier = f"-{int(retention_days)} days"
        with self.lock, self._connection() as connection:
            connection.execute("BEGIN IMMEDIATE")
            rows = connection.execute(
                """
                SELECT session_id
                FROM session_recordings
                WHERE (
                    status = 'active'
                    AND (
                        started_at <= datetime('now', ?)
                        OR EXISTS (
                            SELECT 1 FROM sessions
                            WHERE sessions.session_id = session_recordings.session_id
                              AND sessions.status = 'ended'
                        )
                        OR EXISTS (
                            SELECT 1 FROM analyses
                            WHERE analyses.session_id = session_recordings.session_id
                              AND analyses.status = 'failed'
                              AND analyses.result_json LIKE '%"ANALYSIS_STALE"%'
                        )
                    )
                ) OR (
                    status = 'incomplete'
                    AND expires_at IS NULL
                    AND started_at <= datetime('now', ?)
                )
                """,
                (modifier, modifier),
            ).fetchall()
            session_ids = [str(row["session_id"]) for row in rows]
            if not session_ids:
                return 0
            placeholders = ",".join("?" for _ in session_ids)
            connection.execute(
                f"""
                UPDATE sessions
                SET status = 'ended', ended_at = COALESCE(ended_at, CURRENT_TIMESTAMP)
                WHERE session_id IN ({placeholders}) AND status = 'active'
                """,
                session_ids,
            )
            connection.execute(
                f"DELETE FROM session_in_flights WHERE session_id IN ({placeholders})",
                session_ids,
            )
            connection.execute(
                f"""
                UPDATE session_recordings
                SET status = 'incomplete',
                    incomplete_reason = CASE
                        WHEN status = 'incomplete' THEN COALESCE(
                            incomplete_reason, 'RECORDING_INCOMPLETE_RETENTION_EXPIRED'
                        )
                        WHEN EXISTS (
                            SELECT 1 FROM analyses
                            WHERE analyses.session_id = session_recordings.session_id
                              AND analyses.status = 'failed'
                              AND analyses.result_json LIKE '%"ANALYSIS_STALE"%'
                        ) THEN 'RECORDING_SESSION_CONTAINS_FAILED_ANALYSES'
                        WHEN EXISTS (
                            SELECT 1 FROM sessions
                            WHERE sessions.session_id = session_recordings.session_id
                              AND sessions.ended_at IS NOT NULL
                        ) THEN 'RECORDING_SESSION_ENDED_WITHOUT_FINALIZATION'
                        ELSE 'RECORDING_ACTIVE_RETENTION_EXPIRED'
                    END,
                    ended_at = COALESCE(ended_at, CURRENT_TIMESTAMP),
                    expires_at = CURRENT_TIMESTAMP
                WHERE session_id IN ({placeholders})
                """,
                session_ids,
            )
        return len(session_ids)

    def delete_session_recording_metadata(self, session_id: str) -> None:
        with self.lock, self._connection() as connection:
            connection.execute("BEGIN IMMEDIATE")
            connection.execute("DELETE FROM session_recording_frames WHERE session_id = ?", (session_id,))
            connection.execute("DELETE FROM session_recordings WHERE session_id = ?", (session_id,))

    def expire_session_recording(
        self,
        session_id: str,
        cleanup_holder_id: str | None = None,
    ) -> dict[str, Any]:
        with self.lock, self._connection() as connection:
            connection.execute("BEGIN IMMEDIATE")
            recording = connection.execute(
                "SELECT status FROM session_recordings WHERE session_id = ?",
                (session_id,),
            ).fetchone()
            if not recording:
                raise CloudError("REGRESSION_RECORDING_NOT_FOUND", "Regression recording was not found", 404)
            if recording["status"] not in {"completed", "incomplete", "expired"}:
                raise CloudError(
                    "REGRESSION_RECORDING_NOT_EXPIRABLE",
                    "An active regression recording cannot be expired",
                    409,
                )
            export = connection.execute(
                """
                SELECT holder_id FROM session_recording_exports
                WHERE session_id = ? AND expires_at > CURRENT_TIMESTAMP
                """,
                (session_id,),
            ).fetchone()
            if export and export["holder_id"] != cleanup_holder_id:
                raise CloudError(
                    "REGRESSION_PACK_EXPORT_BUSY",
                    "Regression recording is currently being exported",
                    409,
                )
            connection.execute("DELETE FROM session_recording_frames WHERE session_id = ?", (session_id,))
            connection.execute(
                """
                UPDATE session_recordings
                SET status = 'expired', expires_at = COALESCE(expires_at, CURRENT_TIMESTAMP)
                WHERE session_id = ?
                """,
                (session_id,),
            )
            row = connection.execute(
                "SELECT * FROM session_recordings WHERE session_id = ?",
                (session_id,),
            ).fetchone()
        return self._row_to_dict(row)

    def acquire_session_in_flight(self, session_id: str, request_id: str) -> None:
        with self.lock, self._connection() as connection:
            connection.execute("BEGIN IMMEDIATE")
            session = connection.execute("SELECT status FROM sessions WHERE session_id = ?", (session_id,)).fetchone()
            if not session or session["status"] != "active":
                raise CloudError("SESSION_NOT_FOUND", "Session is not active", 404)
            existing = connection.execute(
                "SELECT status FROM analyses WHERE session_id = ? AND request_id = ?", (session_id, request_id)
            ).fetchone()
            holder = connection.execute(
                "SELECT request_id FROM session_in_flights WHERE session_id = ?", (session_id,)
            ).fetchone()
            if existing and existing["status"] in {"completed", "failed"}:
                if holder and holder["request_id"] != request_id:
                    raise CloudError("SESSION_ANALYSIS_BUSY", "An analysis for this session is already running", 409)
                return
            if existing and existing["status"] == "pending":
                raise CloudError("ANALYSIS_IN_PROGRESS", "An analysis for this request_id is already running", 409)
            pending = connection.execute(
                "SELECT request_id FROM analyses WHERE session_id = ? AND status = 'pending' LIMIT 1", (session_id,)
            ).fetchone()
            if pending and pending["request_id"] != request_id:
                raise CloudError("SESSION_ANALYSIS_BUSY", "An analysis for this session is already running", 409)
            if holder:
                if holder["request_id"] == request_id:
                    raise CloudError("ANALYSIS_IN_PROGRESS", "An analysis for this request_id is already running", 409)
                raise CloudError("SESSION_ANALYSIS_BUSY", "An analysis for this session is already running", 409)
            connection.execute(
                "INSERT INTO session_in_flights(session_id, request_id) VALUES (?, ?)", (session_id, request_id)
            )

    def release_session_in_flight(self, session_id: str, request_id: str) -> None:
        with self.lock, self._connection() as connection:
            connection.execute(
                "DELETE FROM session_in_flights WHERE session_id = ? AND request_id = ?", (session_id, request_id)
            )

    def start_analysis(self, session_id: str, request_id: str, sequence_id: int, image_meta: dict[str, Any]) -> tuple[str, dict[str, Any] | None]:
        with self.lock, self._connection() as connection:
            session = connection.execute("SELECT * FROM sessions WHERE session_id = ?", (session_id,)).fetchone()
            if not session or session["status"] != "active":
                raise CloudError("SESSION_NOT_FOUND", "Session is not active", 404)
            existing = connection.execute(
                "SELECT analysis_id, status, result_json FROM analyses WHERE session_id = ? AND request_id = ?", (session_id, request_id)
            ).fetchone()
            if existing:
                if existing["status"] == "completed":
                    return existing["analysis_id"], json.loads(existing["result_json"])
                if existing["status"] == "failed":
                    payload = json.loads(existing["result_json"] or "{}")
                    error = payload.get("error", {})
                    raise CloudError(
                        error.get("code", "ANALYSIS_FAILED"),
                        error.get("message", "Analysis failed"),
                        int(error.get("status_code", 500)),
                    )
                raise CloudError("ANALYSIS_IN_PROGRESS", "An analysis for this request_id is already running", 409)
            pending = connection.execute(
                "SELECT request_id FROM analyses WHERE session_id = ? AND status = 'pending' LIMIT 1", (session_id,)
            ).fetchone()
            if pending and pending["request_id"] != request_id:
                raise CloudError("SESSION_ANALYSIS_BUSY", "An analysis for this session is already running", 409)
            if sequence_id <= session["last_sequence_id"]:
                raise CloudError("SEQUENCE_OUT_OF_ORDER", "sequence_id must be greater than the last accepted sequence", 409)
            analysis_id = str(uuid.uuid4())
            connection.execute(
                """INSERT INTO analyses(analysis_id, session_id, request_id, sequence_id, status, image_sha256, image_format, image_width, image_height, frame_ts)
                   VALUES (?, ?, ?, ?, 'pending', ?, ?, ?, ?, ?)""",
                (analysis_id, session_id, request_id, sequence_id, image_meta["sha256"], image_meta["format"], image_meta["width"], image_meta["height"], image_meta["frame_ts"]),
            )
            connection.execute("UPDATE sessions SET last_sequence_id = ? WHERE session_id = ?", (sequence_id, session_id))
        return analysis_id, None

    def complete_analysis(self, analysis_id: str, result: dict[str, Any]) -> None:
        with self._connection() as connection:
            connection.execute(
                "UPDATE analyses SET status = 'completed', result_json = ?, completed_at = CURRENT_TIMESTAMP WHERE analysis_id = ?",
                (json.dumps(result, ensure_ascii=False), analysis_id),
            )

    def update_analysis_result(self, analysis_id: str, result: dict[str, Any]) -> None:
        with self._connection() as connection:
            connection.execute(
                "UPDATE analyses SET result_json = ? WHERE analysis_id = ? AND status = 'completed'",
                (json.dumps(result, ensure_ascii=False), analysis_id),
            )

    def fail_analysis(
        self, analysis_id: str, error: CloudError, *, debug_image: dict[str, Any] | None = None
    ) -> None:
        result: dict[str, Any] = {
            "error": {"code": error.code, "message": error.message, "status_code": error.status_code}
        }
        if debug_image is not None:
            result["debug_image"] = debug_image
        with self._connection() as connection:
            connection.execute(
                "UPDATE analyses SET status = ?, result_json = ?, completed_at = CURRENT_TIMESTAMP WHERE analysis_id = ?",
                ("failed", json.dumps(result, ensure_ascii=False), analysis_id),
            )

    def get_analysis(self, session_id: str, analysis_id: str) -> dict[str, Any]:
        with self._connection() as connection:
            row = connection.execute(
                "SELECT result_json, status FROM analyses WHERE session_id = ? AND analysis_id = ?", (session_id, analysis_id)
            ).fetchone()
        if not row:
            raise CloudError("ANALYSIS_NOT_FOUND", "Analysis was not found", 404)
        if row["status"] != "completed":
            if row["status"] == "failed":
                payload = json.loads(row["result_json"] or "{}")
                error = payload.get("error", {})
                message = error.get("message", "Analysis failed")
                raise CloudError(error.get("code", "ANALYSIS_FAILED"), message, int(error.get("status_code", 500)))
            raise CloudError("ANALYSIS_IN_PROGRESS", "Analysis is still running", 409)
        return json.loads(row["result_json"])

    @staticmethod
    def _row_to_dict(row: sqlite3.Row) -> dict[str, Any]:
        return {key: row[key] for key in row.keys()}

    @staticmethod
    def _load_result_json(value: str | None) -> dict[str, Any] | None:
        if not value:
            return None
        try:
            payload = json.loads(value)
        except json.JSONDecodeError:
            return {"error": {"code": "INVALID_STORED_RESULT", "message": "Stored result_json is not valid JSON"}}
        return payload if isinstance(payload, dict) else {"raw_result": payload}

    @staticmethod
    def _suggestion_label(suggestion_detail: dict[str, Any] | None) -> str | None:
        if not isinstance(suggestion_detail, dict):
            return None
        tile = suggestion_detail.get("tile")
        if tile:
            return str(tile)
        tiles = suggestion_detail.get("jinzhang_tiles") or suggestion_detail.get("tiles")
        if not isinstance(tiles, list) or not tiles:
            return None
        head = ",".join(str(tile) for tile in tiles[:5])
        return f"{head}..." if len(tiles) > 5 else head

    def _analysis_debug_payload(self, row: sqlite3.Row, include_result: bool = False) -> dict[str, Any]:
        payload = self._row_to_dict(row)
        result = self._load_result_json(row["result_json"])
        error = result.get("error") if isinstance(result, dict) else None
        suggestion = result.get("suggestion") if isinstance(result, dict) else None
        suggestion_detail = suggestion.get("suggestion") if isinstance(suggestion, dict) else None
        confidence = result.get("confidence") if isinstance(result, dict) else None
        timings = result.get("timings_ms") if isinstance(result, dict) else None
        debug_image = result.get("debug_image") if isinstance(result, dict) else None

        payload["result_status"] = result.get("status") if isinstance(result, dict) else None
        payload["diagnostic_code"] = result.get("diagnostic_code") if isinstance(result, dict) else None
        if isinstance(error, dict):
            payload["error_code"] = error.get("code")
            payload["error_message"] = error.get("message")
        else:
            payload["error_code"] = None
            payload["error_message"] = None
        payload["suggestion_tile"] = self._suggestion_label(suggestion_detail)
        payload["recognition_confidence"] = confidence.get("recognition") if isinstance(confidence, dict) else None
        payload["suggestion_confidence"] = confidence.get("suggestion") if isinstance(confidence, dict) else None
        payload["total_ms"] = timings.get("total") if isinstance(timings, dict) else None
        payload["tile_detection_ms"] = timings.get("tile_detection") if isinstance(timings, dict) else None
        payload["debug_image_stored"] = bool(isinstance(debug_image, dict) and debug_image.get("stored"))
        payload["debug_image_url"] = debug_image.get("url") if isinstance(debug_image, dict) and debug_image.get("stored") else None
        asset = self.get_debug_asset(str(row["analysis_id"]))
        payload["debug_image_asset_status"] = asset.get("status") if asset else None
        payload["debug_image_purge_reason"] = asset.get("purge_reason") if asset else None
        evidence = self.get_evidence_for_analysis(str(row["analysis_id"]))
        payload["evidence"] = evidence
        payload["evidence_id"] = evidence.get("evidence_id") if evidence else None
        payload["evidence_status"] = evidence.get("status") if evidence else None
        payload["evidence_source"] = evidence.get("source") if evidence else None
        payload["evidence_hold_until"] = evidence.get("hold_until") if evidence else None
        payload["evidence_exported_at"] = evidence.get("exported_at") if evidence else None
        if include_result:
            payload["result"] = result
        payload.pop("result_json", None)
        return payload

    def list_debug_sessions(self, limit: int = 50) -> list[dict[str, Any]]:
        limit = max(1, min(int(limit), 200))
        with self._connection() as connection:
            sessions = connection.execute(
                """
                SELECT
                    s.*,
                    COUNT(a.analysis_id) AS analysis_count,
                    MAX(COALESCE(a.completed_at, a.created_at)) AS last_activity_at
                FROM sessions s
                LEFT JOIN analyses a ON a.session_id = s.session_id
                GROUP BY s.session_id
                ORDER BY COALESCE(last_activity_at, s.created_at) DESC
                LIMIT ?
                """,
                (limit,),
            ).fetchall()
            payloads: list[dict[str, Any]] = []
            for session in sessions:
                item = self._row_to_dict(session)
                latest = connection.execute(
                    """
                    SELECT * FROM analyses
                    WHERE session_id = ?
                    ORDER BY sequence_id DESC, created_at DESC
                    LIMIT 1
                    """,
                    (session["session_id"],),
                ).fetchone()
                item["latest_analysis"] = self._analysis_debug_payload(latest) if latest else None
                payloads.append(item)
        return payloads

    def get_debug_session(self, session_id: str) -> dict[str, Any]:
        with self._connection() as connection:
            session = connection.execute("SELECT * FROM sessions WHERE session_id = ?", (session_id,)).fetchone()
            if not session:
                raise CloudError("SESSION_NOT_FOUND", "Session was not found", 404)
            analyses = connection.execute(
                """
                SELECT * FROM analyses
                WHERE session_id = ?
                ORDER BY sequence_id ASC, created_at ASC
                """,
                (session_id,),
            ).fetchall()
        payload = {
            "session": self._row_to_dict(session),
            "analyses": [self._analysis_debug_payload(row, include_result=True) for row in analyses],
        }
        recording = self.get_session_recording(session_id)
        if recording:
            payload["recording"] = recording
        return payload

    def get_debug_analysis(self, analysis_id: str) -> dict[str, Any]:
        with self._connection() as connection:
            row = connection.execute("SELECT * FROM analyses WHERE analysis_id = ?", (analysis_id,)).fetchone()
        if not row:
            raise CloudError("ANALYSIS_NOT_FOUND", "Analysis was not found", 404)
        return {"analysis": self._analysis_debug_payload(row, include_result=True)}

    def get_debug_asset(self, analysis_id: str) -> dict[str, Any] | None:
        with self._connection() as connection:
            row = connection.execute(
                "SELECT * FROM debug_assets WHERE analysis_id = ?", (analysis_id,)
            ).fetchone()
        return self._row_to_dict(row) if row else None

    def analysis_exists(self, analysis_id: str) -> bool:
        with self._connection() as connection:
            row = connection.execute(
                "SELECT 1 FROM analyses WHERE analysis_id = ?", (analysis_id,)
            ).fetchone()
        return row is not None

    def record_debug_asset(
        self,
        analysis_id: str,
        image_path: Path | None,
        size_bytes: int = 0,
        *,
        status: str = "stored",
        reason: str | None = None,
    ) -> None:
        if status not in {"stored", "not_stored", "purged"}:
            raise ValueError("debug asset status must be stored, not_stored or purged")
        if size_bytes < 0:
            raise ValueError("debug asset size_bytes must be non-negative")
        with self._connection() as connection:
            analysis = connection.execute(
                "SELECT analysis_id FROM analyses WHERE analysis_id = ?", (analysis_id,)
            ).fetchone()
            if not analysis:
                raise CloudError("ANALYSIS_NOT_FOUND", "Analysis was not found", 404)
            connection.execute(
                """
                INSERT INTO debug_assets(analysis_id, image_path, size_bytes, status, storage_class, stored_at, purged_at, purge_reason)
                VALUES (?, ?, ?, ?, 'normal',
                    CASE WHEN ? = 'stored' THEN CURRENT_TIMESTAMP ELSE NULL END,
                    CASE WHEN ? = 'purged' THEN CURRENT_TIMESTAMP ELSE NULL END, ?)
                ON CONFLICT(analysis_id) DO UPDATE SET
                    image_path = excluded.image_path,
                    size_bytes = excluded.size_bytes,
                    status = excluded.status,
                    stored_at = CASE WHEN excluded.status = 'stored' THEN CURRENT_TIMESTAMP ELSE debug_assets.stored_at END,
                    purged_at = CASE WHEN excluded.status = 'purged' THEN CURRENT_TIMESTAMP ELSE debug_assets.purged_at END,
                    purge_reason = excluded.purge_reason
                """,
                (
                    analysis_id,
                    str(image_path) if image_path is not None else None,
                    size_bytes,
                    status,
                    status,
                    status,
                    reason,
                ),
            )

    def should_store_debug_image(
        self,
        analysis_id: str,
        *,
        mode: str,
        sample_interval_seconds: int,
        force: bool = False,
    ) -> tuple[bool, str | None]:
        if force:
            return True, None
        if mode == "off":
            return False, "DEBUG_IMAGE_DISABLED"
        if mode == "diagnostic":
            return False, "DEBUG_IMAGE_DIAGNOSTIC_ONLY"
        if mode == "always":
            return True, None
        if mode != "sampled":
            raise ValueError("unknown debug image mode")
        if sample_interval_seconds <= 0:
            return True, None
        with self._connection() as connection:
            row = connection.execute(
                """
                SELECT 1
                FROM analyses current
                JOIN analyses candidate ON candidate.session_id = current.session_id
                JOIN debug_assets asset ON asset.analysis_id = candidate.analysis_id
                WHERE current.analysis_id = ?
                  AND asset.status = 'stored'
                  AND asset.stored_at >= datetime('now', ?)
                LIMIT 1
                """,
                (analysis_id, f"-{int(sample_interval_seconds)} seconds"),
            ).fetchone()
        return (False, "DEBUG_IMAGE_SAMPLE_INTERVAL") if row else (True, None)

    @staticmethod
    def _debug_image_unavailable_payload(result: dict[str, Any], reason: str, purged_at: str | None = None) -> dict[str, Any]:
        debug_image = result.get("debug_image")
        payload = dict(debug_image) if isinstance(debug_image, dict) else {}
        payload.update({"stored": False, "reason": reason})
        payload.pop("url", None)
        if purged_at:
            payload["purged_at"] = purged_at
        result["debug_image"] = payload
        return result

    def mark_debug_asset_purged(self, analysis_id: str, reason: str) -> None:
        with self._connection() as connection:
            connection.execute(
                """
                UPDATE debug_assets
                SET status = 'purged', size_bytes = 0, purged_at = CURRENT_TIMESTAMP, purge_reason = ?
                WHERE analysis_id = ?
                """,
                (reason, analysis_id),
            )
            row = connection.execute(
                "SELECT result_json FROM analyses WHERE analysis_id = ?", (analysis_id,)
            ).fetchone()
            if not row or not row["result_json"]:
                return
            result = self._load_result_json(row["result_json"])
            if not isinstance(result, dict):
                return
            purged_at = connection.execute("SELECT purged_at FROM debug_assets WHERE analysis_id = ?", (analysis_id,)).fetchone()
            self._debug_image_unavailable_payload(result, reason, purged_at["purged_at"] if purged_at else None)
            connection.execute(
                "UPDATE analyses SET result_json = ? WHERE analysis_id = ?",
                (json.dumps(result, ensure_ascii=False), analysis_id),
            )

    def mark_debug_asset_missing(self, analysis_id: str) -> None:
        self.mark_debug_asset_purged(analysis_id, "DEBUG_IMAGE_FILE_MISSING")

    def list_debug_assets(
        self,
        *,
        stored_only: bool = True,
        older_than_days: int | None = None,
        exclude_active_evidence: bool = False,
        limit: int | None = None,
    ) -> list[dict[str, Any]]:
        clauses = ["1 = 1"]
        params: list[Any] = []
        if stored_only:
            clauses.append("asset.status = 'stored'")
        if older_than_days is not None:
            clauses.append("asset.stored_at < datetime('now', ?)")
            params.append(f"-{int(older_than_days)} days")
        if exclude_active_evidence:
            clauses.append(
                "NOT EXISTS (SELECT 1 FROM evidences evidence WHERE evidence.analysis_id = asset.analysis_id "
                "AND evidence.status = 'active' AND evidence.hold_until > CURRENT_TIMESTAMP)"
            )
        sql = (
            "SELECT asset.*, analysis.session_id, analysis.status AS analysis_status "
            "FROM debug_assets asset JOIN analyses analysis ON analysis.analysis_id = asset.analysis_id "
            f"WHERE {' AND '.join(clauses)} ORDER BY COALESCE(asset.stored_at, analysis.created_at) ASC"
        )
        if limit is not None:
            sql += " LIMIT ?"
            params.append(max(1, int(limit)))
        with self._connection() as connection:
            rows = connection.execute(sql, params).fetchall()
        return [self._row_to_dict(row) for row in rows]

    def debug_asset_bytes(self, *, exclude_active_evidence: bool = False) -> int:
        clauses = ["asset.status = 'stored'"]
        if exclude_active_evidence:
            clauses.append(
                "NOT EXISTS (SELECT 1 FROM evidences evidence WHERE evidence.analysis_id = asset.analysis_id "
                "AND evidence.status = 'active' AND evidence.hold_until > CURRENT_TIMESTAMP)"
            )
        with self._connection() as connection:
            row = connection.execute(
                f"SELECT COALESCE(SUM(asset.size_bytes), 0) AS total FROM debug_assets asset WHERE {' AND '.join(clauses)}"
            ).fetchone()
        return int(row["total"] if row else 0)

    @staticmethod
    def _evidence_payload(row: sqlite3.Row) -> dict[str, Any]:
        return {
            "evidence_id": row["evidence_id"],
            "analysis_id": row["analysis_id"],
            "source": row["source"],
            "note": row["note"],
            "signature": row["signature"],
            "status": row["status"],
            "created_at": row["created_at"],
            "hold_until": row["hold_until"],
            "exported_at": row["exported_at"],
            "expired_at": row["expired_at"],
        }

    def get_evidence(self, evidence_id: str) -> dict[str, Any]:
        with self._connection() as connection:
            row = connection.execute("SELECT * FROM evidences WHERE evidence_id = ?", (evidence_id,)).fetchone()
        if not row:
            raise CloudError("EVIDENCE_NOT_FOUND", "Evidence was not found", 404)
        return self._evidence_payload(row)

    def get_evidence_for_analysis(self, analysis_id: str) -> dict[str, Any] | None:
        with self._connection() as connection:
            row = connection.execute(
                "SELECT * FROM evidences WHERE analysis_id = ? ORDER BY created_at DESC LIMIT 1", (analysis_id,)
            ).fetchone()
        return self._evidence_payload(row) if row else None

    def create_or_get_evidence(
        self,
        analysis_id: str,
        source: str = "manual",
        note: str | None = None,
        retention_days: int | None = 14,
        *,
        signature: str | None = None,
        max_per_signature_per_hour: int | None = None,
    ) -> dict[str, Any] | None:
        if source not in {"manual", "automatic"}:
            raise ValueError("evidence source must be manual or automatic")
        if retention_days is None or retention_days <= 0:
            raise ValueError("evidence retention_days must be positive")
        with self.lock, self._connection() as connection:
            connection.execute("BEGIN IMMEDIATE")
            analysis = connection.execute(
                "SELECT analysis_id FROM analyses WHERE analysis_id = ?", (analysis_id,)
            ).fetchone()
            if not analysis:
                raise CloudError("ANALYSIS_NOT_FOUND", "Analysis was not found", 404)
            existing = connection.execute(
                "SELECT * FROM evidences WHERE analysis_id = ?", (analysis_id,)
            ).fetchone()
            if existing:
                if source == "manual":
                    connection.execute(
                        """
                        UPDATE evidences
                        SET source = 'manual', note = COALESCE(?, note), status = 'active', expired_at = NULL,
                            hold_until = CASE WHEN hold_until > datetime('now', ?) THEN hold_until ELSE datetime('now', ?) END
                        WHERE evidence_id = ?
                        """,
                        (note, f"+{int(retention_days)} days", f"+{int(retention_days)} days", existing["evidence_id"]),
                    )
                    existing = connection.execute(
                        "SELECT * FROM evidences WHERE evidence_id = ?", (existing["evidence_id"],)
                    ).fetchone()
                return self._evidence_payload(existing)
            if source == "automatic" and signature and max_per_signature_per_hour is not None:
                count = connection.execute(
                    """
                    SELECT COUNT(*) AS count FROM evidences
                    WHERE source = 'automatic' AND signature = ?
                      AND created_at >= datetime('now', '-1 hour')
                    """,
                    (signature,),
                ).fetchone()["count"]
                if int(count) >= max(1, int(max_per_signature_per_hour)):
                    return None
            evidence_id = str(uuid.uuid4())
            connection.execute(
                """
                INSERT INTO evidences(evidence_id, analysis_id, source, note, signature, status, hold_until)
                VALUES (?, ?, ?, ?, ?, 'active', datetime('now', ?))
                """,
                (evidence_id, analysis_id, source, note, signature, f"+{int(retention_days)} days"),
            )
            row = connection.execute("SELECT * FROM evidences WHERE evidence_id = ?", (evidence_id,)).fetchone()
        return self._evidence_payload(row)

    def mark_evidence_exported(self, evidence_id: str) -> dict[str, Any]:
        with self._connection() as connection:
            result = connection.execute(
                "UPDATE evidences SET exported_at = COALESCE(exported_at, CURRENT_TIMESTAMP) WHERE evidence_id = ?",
                (evidence_id,),
            )
            if result.rowcount != 1:
                raise CloudError("EVIDENCE_NOT_FOUND", "Evidence was not found", 404)
            row = connection.execute("SELECT * FROM evidences WHERE evidence_id = ?", (evidence_id,)).fetchone()
        return self._evidence_payload(row)

    def expire_evidence(self) -> int:
        with self._connection() as connection:
            result = connection.execute(
                """
                UPDATE evidences
                SET status = 'expired', expired_at = CURRENT_TIMESTAMP
                WHERE status = 'active' AND hold_until <= CURRENT_TIMESTAMP
                """
            )
        return result.rowcount

    def active_evidence_asset_bytes(self, *, source: str | None = None) -> int:
        clauses = [
            "asset.status = 'stored'",
            "evidence.status = 'active'",
            "evidence.hold_until > CURRENT_TIMESTAMP",
        ]
        params: list[Any] = []
        if source is not None:
            clauses.append("evidence.source = ?")
            params.append(source)
        with self._connection() as connection:
            row = connection.execute(
                "SELECT COALESCE(SUM(asset.size_bytes), 0) AS total "
                "FROM evidences evidence JOIN debug_assets asset ON asset.analysis_id = evidence.analysis_id "
                f"WHERE {' AND '.join(clauses)}",
                params,
            ).fetchone()
        return int(row["total"] if row else 0)

    def evict_oldest_automatic_evidence(self, limit: int = 1) -> list[dict[str, Any]]:
        if limit <= 0:
            return []
        with self.lock, self._connection() as connection:
            connection.execute("BEGIN IMMEDIATE")
            rows = connection.execute(
                """
                SELECT * FROM evidences
                WHERE source = 'automatic' AND status = 'active'
                ORDER BY created_at ASC
                LIMIT ?
                """,
                (int(limit),),
            ).fetchall()
            ids = [row["evidence_id"] for row in rows]
            if ids:
                connection.executemany(
                    "UPDATE evidences SET status = 'evicted', expired_at = CURRENT_TIMESTAMP WHERE evidence_id = ?",
                    [(evidence_id,) for evidence_id in ids],
                )
        return [self._evidence_payload(row) for row in rows]

    def get_evidence_snapshot(self, evidence_id: str) -> dict[str, Any]:
        evidence = self.get_evidence(evidence_id)
        with self._connection() as connection:
            row = connection.execute(
                """
                SELECT analysis.*, session.rule_profile_id, session.status AS session_status,
                       session.created_at AS session_created_at, session.ended_at AS session_ended_at
                FROM analyses analysis JOIN sessions session ON session.session_id = analysis.session_id
                WHERE analysis.analysis_id = ?
                """,
                (evidence["analysis_id"],),
            ).fetchone()
        if not row:
            raise CloudError("ANALYSIS_NOT_FOUND", "Analysis was not found", 404)
        analysis = self._row_to_dict(row)
        analysis["result"] = self._load_result_json(analysis.pop("result_json", None))
        asset = self.get_debug_asset(evidence["analysis_id"])
        if asset:
            asset.pop("image_path", None)
        return {"evidence": evidence, "analysis": analysis, "debug_asset": asset}

    def try_acquire_maintenance_lease(self, lease_name: str, holder_id: str, lease_seconds: int = 120) -> bool:
        if lease_seconds <= 0:
            raise ValueError("maintenance lease_seconds must be positive")
        with self.lock, self._connection() as connection:
            connection.execute("BEGIN IMMEDIATE")
            connection.execute(
                "DELETE FROM maintenance_leases WHERE expires_at <= CURRENT_TIMESTAMP"
            )
            result = connection.execute(
                """
                INSERT INTO maintenance_leases(lease_name, holder_id, expires_at)
                VALUES (?, ?, datetime('now', ?))
                ON CONFLICT(lease_name) DO UPDATE SET
                    holder_id = excluded.holder_id, acquired_at = CURRENT_TIMESTAMP, expires_at = excluded.expires_at
                WHERE maintenance_leases.expires_at <= CURRENT_TIMESTAMP
                """,
                (lease_name, holder_id, f"+{int(lease_seconds)} seconds"),
            )
        return result.rowcount == 1

    def release_maintenance_lease(self, lease_name: str, holder_id: str) -> None:
        with self._connection() as connection:
            connection.execute(
                "DELETE FROM maintenance_leases WHERE lease_name = ? AND holder_id = ?",
                (lease_name, holder_id),
            )

    def recover_stale_in_flights(self, stale_minutes: int) -> int:
        if stale_minutes <= 0:
            raise ValueError("stale_minutes must be positive")
        modifier = f"-{int(stale_minutes)} minutes"
        stale_error = json.dumps(
            {"error": {"code": "ANALYSIS_STALE", "message": "Analysis lease expired during maintenance", "status_code": 500}},
            ensure_ascii=False,
        )
        with self.lock, self._connection() as connection:
            connection.execute("BEGIN IMMEDIATE")
            rows = connection.execute(
                """
                SELECT analysis_id FROM analyses
                WHERE status = 'pending' AND created_at < datetime('now', ?)
                """,
                (modifier,),
            ).fetchall()
            if rows:
                connection.execute(
                    """
                    UPDATE analyses
                    SET status = 'failed', result_json = ?, completed_at = CURRENT_TIMESTAMP
                    WHERE status = 'pending' AND created_at < datetime('now', ?)
                    """,
                    (stale_error, modifier),
                )
            connection.execute(
                "DELETE FROM session_in_flights WHERE acquired_at < datetime('now', ?)", (modifier,)
            )
        return len(rows)

    def list_expired_analyses(self, retention_days: int, limit: int = 200) -> list[dict[str, Any]]:
        if retention_days < 0:
            raise ValueError("retention_days must be non-negative")
        with self._connection() as connection:
            rows = connection.execute(
                """
                SELECT analysis.*, asset.image_path, asset.status AS debug_asset_status
                FROM analyses analysis
                JOIN sessions session ON session.session_id = analysis.session_id
                LEFT JOIN debug_assets asset ON asset.analysis_id = analysis.analysis_id
                WHERE session.status = 'ended'
                  AND COALESCE(analysis.completed_at, analysis.created_at) < datetime('now', ?)
                  AND NOT EXISTS (
                    SELECT 1
                    FROM session_recording_frames recording_frame
                    JOIN session_recordings recording
                      ON recording.session_id = recording_frame.session_id
                    WHERE recording_frame.analysis_id = analysis.analysis_id
                      AND recording.status = 'completed'
                  )
                  AND NOT EXISTS (
                    SELECT 1 FROM evidences evidence
                    WHERE evidence.analysis_id = analysis.analysis_id
                      AND evidence.status = 'active' AND evidence.hold_until > CURRENT_TIMESTAMP
                  )
                ORDER BY COALESCE(analysis.completed_at, analysis.created_at) ASC
                LIMIT ?
                """,
                (f"-{int(retention_days)} days", max(1, int(limit))),
            ).fetchall()
        return [self._row_to_dict(row) for row in rows]

    def delete_analysis_if_prunable(self, analysis_id: str) -> bool:
        with self.lock, self._connection() as connection:
            connection.execute("BEGIN IMMEDIATE")
            analysis = connection.execute(
                "SELECT session_id FROM analyses WHERE analysis_id = ?", (analysis_id,)
            ).fetchone()
            if not analysis:
                return False
            session_id = str(analysis["session_id"])
            protected = connection.execute(
                """
                SELECT 1 FROM evidences
                WHERE analysis_id = ?
                  AND status = 'active' AND hold_until > CURRENT_TIMESTAMP
                LIMIT 1
                """,
                (analysis_id,),
            ).fetchone()
            if protected:
                return False
            protected = connection.execute(
                """
                SELECT 1
                FROM session_recording_frames frame
                JOIN session_recordings recording ON recording.session_id = frame.session_id
                WHERE frame.analysis_id = ? AND recording.status = 'completed'
                LIMIT 1
                """,
                (analysis_id,),
            ).fetchone()
            if protected:
                return False
            connection.execute("DELETE FROM debug_assets WHERE analysis_id = ?", (analysis_id,))
            connection.execute("DELETE FROM evidences WHERE analysis_id = ?", (analysis_id,))
            result = connection.execute("DELETE FROM analyses WHERE analysis_id = ?", (analysis_id,))
            if result.rowcount:
                connection.execute(
                    """
                    DELETE FROM sessions
                    WHERE session_id = ?
                      AND status = 'ended'
                      AND NOT EXISTS (
                          SELECT 1 FROM analyses
                          WHERE analyses.session_id = sessions.session_id
                      )
                      AND NOT EXISTS (
                          SELECT 1 FROM session_recordings
                          WHERE session_recordings.session_id = sessions.session_id
                      )
                    """,
                    (session_id,),
                )
        return result.rowcount == 1

    def has_active_analyses(self) -> bool:
        with self._connection() as connection:
            row = connection.execute("SELECT 1 FROM analyses WHERE status = 'pending' LIMIT 1").fetchone()
        return row is not None

    def checkpoint_and_vacuum(self) -> bool:
        if self.has_active_analyses():
            return False
        with self.lock, self._connection() as connection:
            connection.execute("PRAGMA wal_checkpoint(TRUNCATE)")
            connection.execute("VACUUM")
        return True

    def end_session(self, session_id: str) -> None:
        with self._connection() as connection:
            result = connection.execute(
                "UPDATE sessions SET status = 'ended', ended_at = CURRENT_TIMESTAMP WHERE session_id = ? AND status = 'active'", (session_id,)
            )
            if result.rowcount == 1:
                connection.execute("DELETE FROM session_in_flights WHERE session_id = ?", (session_id,))
        if result.rowcount != 1:
            raise CloudError("SESSION_NOT_FOUND", "Session is not active", 404)
