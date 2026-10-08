from __future__ import annotations

import sqlite3

import pytest

from server.storage import SQLiteStore


def test_initialize_adds_frame_ts_to_legacy_analysis_table(tmp_path):
    database_path = tmp_path / "legacy-cloud.db"
    with sqlite3.connect(database_path) as connection:
        connection.executescript(
            """
            CREATE TABLE sessions (
                session_id TEXT PRIMARY KEY,
                rule_profile_id TEXT NOT NULL,
                last_sequence_id INTEGER NOT NULL DEFAULT -1,
                status TEXT NOT NULL DEFAULT 'active',
                created_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP,
                ended_at TEXT
            );
            CREATE TABLE analyses (
                analysis_id TEXT PRIMARY KEY,
                session_id TEXT NOT NULL,
                request_id TEXT NOT NULL,
                sequence_id INTEGER NOT NULL,
                status TEXT NOT NULL,
                image_sha256 TEXT,
                image_format TEXT,
                image_width INTEGER,
                image_height INTEGER,
                result_json TEXT,
                created_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP,
                completed_at TEXT,
                UNIQUE(session_id, request_id),
                FOREIGN KEY(session_id) REFERENCES sessions(session_id)
            );
            """
        )

    store = SQLiteStore(database_path)
    with sqlite3.connect(database_path) as connection:
        columns = {row[1] for row in connection.execute("PRAGMA table_info(analyses)")}

    assert "frame_ts" in columns

    session_id = store.create_session("guangdong_mvp_v1")
    analysis_id, existing = store.start_analysis(
        session_id,
        "request-1",
        1,
        {
            "sha256": "abc",
            "format": "jpeg",
            "width": 1024,
            "height": 683,
            "frame_ts": 123456,
        },
    )

    assert analysis_id
    assert existing is None


def test_initialize_adds_recording_tables_without_changing_legacy_rows(tmp_path):
    database_path = tmp_path / "legacy-recording.db"
    with sqlite3.connect(database_path) as connection:
        connection.executescript(
            """
            CREATE TABLE sessions (
                session_id TEXT PRIMARY KEY,
                rule_profile_id TEXT NOT NULL,
                last_sequence_id INTEGER NOT NULL DEFAULT -1,
                status TEXT NOT NULL DEFAULT 'active',
                created_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP,
                ended_at TEXT
            );
            CREATE TABLE analyses (
                analysis_id TEXT PRIMARY KEY,
                session_id TEXT NOT NULL,
                request_id TEXT NOT NULL,
                sequence_id INTEGER NOT NULL,
                status TEXT NOT NULL,
                image_sha256 TEXT,
                image_format TEXT,
                image_width INTEGER,
                image_height INTEGER,
                result_json TEXT,
                created_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP,
                completed_at TEXT,
                UNIQUE(session_id, request_id),
                FOREIGN KEY(session_id) REFERENCES sessions(session_id)
            );
            INSERT INTO sessions(session_id, rule_profile_id) VALUES ('legacy-session', 'guangdong_mvp_v1');
            INSERT INTO analyses(
                analysis_id, session_id, request_id, sequence_id, status
            ) VALUES ('legacy-analysis', 'legacy-session', 'legacy-request', 1, 'completed');
            """
        )

    SQLiteStore(database_path)

    with sqlite3.connect(database_path) as connection:
        tables = {
            row[0]
            for row in connection.execute(
                "SELECT name FROM sqlite_master WHERE type = 'table'"
            )
        }
        session = connection.execute(
            "SELECT rule_profile_id FROM sessions WHERE session_id = 'legacy-session'"
        ).fetchone()
        analysis = connection.execute(
            "SELECT status FROM analyses WHERE analysis_id = 'legacy-analysis'"
        ).fetchone()
    assert {"session_recordings", "session_recording_frames"} <= tables
    assert session == ("guangdong_mvp_v1",)
    assert analysis == ("completed",)


def test_every_store_connection_enforces_foreign_keys(tmp_path):
    store = SQLiteStore(tmp_path / "cloud.db")

    with store._connection() as connection:
        assert connection.execute("PRAGMA foreign_keys").fetchone()[0] == 1
        with pytest.raises(sqlite3.IntegrityError):
            connection.execute(
                "INSERT INTO session_recordings(session_id) VALUES ('missing-session')"
            )