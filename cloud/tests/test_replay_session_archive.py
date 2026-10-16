from __future__ import annotations

import hashlib
import io
import json
import stat
import zipfile
from pathlib import Path
from typing import Any

import httpx
import pytest

from tools import replay_session_archive as replay


def result(**changes: Any) -> dict[str, Any]:
    payload: dict[str, Any] = {
        "status": "complete",
        "diagnostic_code": None,
        "hand_tiles": ["1m", "2m", "2m"],
        "meld_groups": [{"type": "chi", "tiles": ["1p", "2p", "3p"]}],
        "bonus_tiles": ["1z", "1z"],
        "suggestion": {
            "advice_type": "discard",
            "can_hu": False,
            "tingpai_steps": 1,
            "suggestion": {
                "tile": "2m",
                "jinzhang_tiles": ["3m", "4m"],
            },
        },
        "confidence": {"overall": 0.9},
        "tile_detections": [{"bbox": [1, 2, 3, 4]}],
        "model_versions": {"tile": "old"},
        "rule_profile": {"id": "guangdong_mvp_v1", "version": "1"},
        "timings_ms": {"total": 12.3},
    }
    payload.update(changes)
    return payload


def result_with_suggestion(*, outer: dict[str, Any] | None = None, inner: dict[str, Any] | None = None) -> dict[str, Any]:
    suggestion = dict(result()["suggestion"])
    suggestion["suggestion"] = {**suggestion["suggestion"], **(inner or {})}
    suggestion.update(outer or {})
    return result(suggestion=suggestion)


def pack_bytes(
    *,
    frames: list[dict[str, Any]] | None = None,
    baselines: list[dict[str, Any]] | None = None,
    session: dict[str, Any] | None = None,
    mutate_members: dict[str, bytes | None] | None = None,
    checksums_override: dict[str, str] | None = None,
    duplicate: str | None = None,
    symlink: str | None = None,
) -> bytes:
    image1 = b"\x89PNG\r\n\x1a\nframe-one"
    image2 = b"\xff\xd8\xffframe-two"
    default_frames = [
        {
            "sequence_id": 10,
            "request_id": "req-10",
            "frame_ts": 1000,
            "content_type": "image/png",
            "rotation_degrees": 0,
            "sha256": hashlib.sha256(image1).hexdigest(),
            "size_bytes": len(image1),
            "frame_path": "frames/000010.png",
            "baseline_path": "baselines/000010.json",
        },
        {
            "sequence_id": 20,
            "request_id": "req-20",
            "frame_ts": 2000,
            "content_type": "image/jpeg",
            "rotation_degrees": 90,
            "sha256": hashlib.sha256(image2).hexdigest(),
            "size_bytes": len(image2),
            "frame_path": "frames/000020.jpg",
            "baseline_path": "baselines/000020.json",
        },
    ]
    selected_frames = frames if frames is not None else default_frames
    selected_baselines = baselines if baselines is not None else [result(), result()]
    manifest = {
        "format": "ar-mahjong-session-regression-v1",
        "frame_count": len(selected_frames),
        "frames": selected_frames,
    }
    members: dict[str, bytes] = {
        "manifest.json": json.dumps(manifest).encode(),
        "session.json": json.dumps(session or {"session": {"rule_profile_id": "guangdong_mvp_v1"}}).encode(),
        "runtime-context.json": b"{}",
        "model-manifests.json": b"{}",
    }
    images = {"frames/000010.png": image1, "frames/000020.jpg": image2}
    for index, frame in enumerate(selected_frames):
        if frame.get("frame_path") in images:
            members[str(frame["frame_path"])] = images[str(frame["frame_path"])]
        if index < len(selected_baselines):
            members[str(frame["baseline_path"])] = json.dumps(selected_baselines[index]).encode()
    for name, content in (mutate_members or {}).items():
        if content is None:
            members.pop(name, None)
        else:
            members[name] = content
    checksums = {name: hashlib.sha256(content).hexdigest() for name, content in members.items()}
    if checksums_override:
        checksums.update(checksums_override)
    members["checksums.json"] = json.dumps({"sha256": checksums}).encode()

    output = io.BytesIO()
    with zipfile.ZipFile(output, "w") as archive:
        for name, content in members.items():
            info = zipfile.ZipInfo(name)
            if name == symlink:
                info.create_system = 3
                info.external_attr = (stat.S_IFLNK | 0o777) << 16
            archive.writestr(info, content)
        if duplicate:
            archive.writestr(duplicate, members[duplicate])
    return output.getvalue()


def write_pack(tmp_path: Path, content: bytes) -> Path:
    path = tmp_path / "pack.zip"
    path.write_bytes(content)
    return path


def run_cli(tmp_path: Path, archive: bytes, transport: httpx.BaseTransport) -> tuple[int, dict[str, Any]]:
    archive_path = write_pack(tmp_path, archive)
    report_path = tmp_path / "report.json"
    code = replay.main(
        [
            "--archive",
            str(archive_path),
            "--base-url",
            "https://cloud.test",
            "--api-key",
            "secret",
            "--report",
            str(report_path),
        ],
        transport=transport,
    )
    return code, json.loads(report_path.read_text(encoding="utf-8"))


def successful_transport(
    candidates: list[dict[str, Any]] | None = None,
    *,
    end_status: int = 200,
) -> tuple[httpx.MockTransport, list[httpx.Request]]:
    requests: list[httpx.Request] = []
    queue = list(candidates or [result(), result()])

    def handler(request: httpx.Request) -> httpx.Response:
        requests.append(request)
        if request.url.path == "/health/ready":
            return httpx.Response(200, json={"status": "ready"})
        if request.url.path == "/api/v1/mahjong/sessions":
            return httpx.Response(200, json={"session_id": "candidate-session"})
        if request.url.path.endswith("/analyses"):
            return httpx.Response(200, json=queue.pop(0))
        if request.url.path.endswith("/end"):
            return httpx.Response(end_status, json={"status": "ended"})
        raise AssertionError(request.url)

    return httpx.MockTransport(handler), requests


def test_valid_pack_replays_raw_frames_in_manifest_order(tmp_path: Path):
    transport, requests = successful_transport()
    code, report = run_cli(tmp_path, pack_bytes(), transport)

    assert code == 0
    assert [request.url.path for request in requests] == [
        "/health/ready",
        "/api/v1/mahjong/sessions",
        "/api/v1/mahjong/sessions/candidate-session/analyses",
        "/api/v1/mahjong/sessions/candidate-session/analyses",
        "/api/v1/mahjong/sessions/candidate-session/end",
    ]
    create = requests[1]
    assert json.loads(create.content) == {"rule_profile_id": "guangdong_mvp_v1"}
    first, second = requests[2:4]
    assert first.content == b"\x89PNG\r\n\x1a\nframe-one"
    assert first.headers["content-type"] == "image/png"
    assert first.headers["x-request-id"] == "req-10"
    assert first.headers["x-sequence-id"] == "10"
    assert first.headers["x-frame-ts"] == "1000"
    assert first.headers["x-frame-count"] == "1"
    assert first.headers["x-image-rotation-degrees"] == "0"
    assert second.headers["x-sequence-id"] == "20"
    assert report["summary"]["passed"] == 2
    assert report["summary"]["pass_rate"] == 1.0
    assert report["exit_code"] == 0
    assert report["frames"][0]["observed"]["confidence"] == {"overall": 0.9}
    assert report["frames"][0]["server_timings"] == {"total": 12.3}


def test_retry_reuses_request_and_sequence_metadata(tmp_path: Path):
    attempts: list[httpx.Request] = []
    analysis_attempts = 0

    def handler(request: httpx.Request) -> httpx.Response:
        nonlocal analysis_attempts
        if request.url.path == "/health/ready":
            return httpx.Response(200, json={"status": "ready"})
        if request.url.path == "/api/v1/mahjong/sessions":
            return httpx.Response(200, json={"session_id": "candidate-session"})
        if request.url.path.endswith("/analyses"):
            attempts.append(request)
            analysis_attempts += 1
            if analysis_attempts == 1:
                return httpx.Response(429, json={"error": {"code": "BUSY"}})
            return httpx.Response(200, json=result())
        if request.url.path.endswith("/end"):
            return httpx.Response(200, json={"status": "ended"})
        raise AssertionError(request.url)

    code, _ = run_cli(tmp_path, pack_bytes(), httpx.MockTransport(handler))
    assert code == 0
    assert attempts[0].content == attempts[1].content
    assert attempts[0].headers["x-request-id"] == attempts[1].headers["x-request-id"] == "req-10"
    assert attempts[0].headers["x-sequence-id"] == attempts[1].headers["x-sequence-id"] == "10"


@pytest.mark.parametrize(
    ("candidate", "field"),
    [
        (result(status="diagnostic"), "status"),
        (result(diagnostic_code="HAND_REGION_NOT_FOUND"), "diagnostic_code"),
        (result(hand_tiles=["1m"]), "hand_tiles"),
        (result(meld_groups=[{"type": "peng", "tiles": ["1p", "1p", "1p"]}]), "meld_groups"),
        (result(bonus_tiles=["2z"]), "bonus_tiles"),
        (result_with_suggestion(outer={"advice_type": "draw_wait"}), "suggestion.advice_type"),
        (result_with_suggestion(outer={"can_hu": True}), "suggestion.can_hu"),
        (result_with_suggestion(outer={"tingpai_steps": 2}), "suggestion.shanten"),
        (result_with_suggestion(inner={"tile": "9m"}), "suggestion.tile"),
        (result_with_suggestion(inner={"jinzhang_tiles": ["9m"]}), "suggestion.jinzhang_tiles"),
    ],
)
def test_every_semantic_field_can_fail(tmp_path: Path, candidate: dict[str, Any], field: str):
    transport, _ = successful_transport([candidate, result()])
    code, report = run_cli(tmp_path, pack_bytes(), transport)
    assert code == 1
    assert field in report["frames"][0]["differences"]


def test_nested_suggestion_missing_inner_fields_is_not_masked_by_outer_legacy_values():
    baseline = result()
    candidate = result(
        suggestion={
            "advice_type": "discard",
            "can_hu": False,
            "tingpai_steps": 1,
            "tile": "2m",
            "jinzhang_tiles": ["3m", "4m"],
            "suggestion": {},
        }
    )

    differences = replay.semantic_differences(baseline, candidate)

    assert differences["suggestion.tile"] == {"baseline": "2m", "candidate": None}
    assert differences["suggestion.jinzhang_tiles"] == {
        "baseline": ["3m", "4m"],
        "candidate": None,
    }


def test_legacy_flat_suggestion_remains_supported_without_nested_object():
    flat = result(
        suggestion={
            "advice_type": "discard",
            "can_hu": False,
            "tingpai_steps": 1,
            "tile": "2m",
            "jinzhang_tiles": ["4m", "3m"],
        }
    )

    assert replay.semantic_differences(result(), flat) == {}


def test_outer_tingpai_steps_is_compared_without_inner_discard_candidate_overriding_it():
    baseline = result(
        suggestion={
            "advice_type": "discard",
            "can_hu": False,
            "tingpai_steps": 3,
            "suggestion": {"tile": "2m", "tingpai_steps": 1, "jinzhang_tiles": ["3m"]},
        }
    )
    candidate = result(
        suggestion={
            "advice_type": "discard",
            "can_hu": False,
            "tingpai_steps": 2,
            "suggestion": {"tile": "2m", "tingpai_steps": 1, "jinzhang_tiles": ["3m"]},
        }
    )

    differences = replay.semantic_differences(baseline, candidate)

    assert differences["suggestion.shanten"] == {"baseline": 3, "candidate": 2}


def test_order_only_and_non_decision_changes_pass(tmp_path: Path):
    nested = result()["suggestion"]["suggestion"]
    changed = result(
        hand_tiles=["2m", "1m", "2m"],
        meld_groups=[{"tiles": ["3p", "1p", "2p"], "type": "chi"}],
        bonus_tiles=["1z", "1z"],
        suggestion={
            "advice_type": "discard",
            "can_hu": False,
            "tingpai_steps": 1,
            "suggestion": {**nested, "jinzhang_tiles": ["4m", "3m"]},
        },
        confidence={"overall": 0.1},
        tile_detections=[{"bbox": [9, 9, 9, 9], "region": "other"}],
        model_versions={"tile": "new"},
        rule_profile={"id": "guangdong_mvp_v1", "version": "2"},
        timings_ms={"total": 999},
        decision_margin=0,
        candidate_ranking=[],
    )
    transport, _ = successful_transport([changed, changed])
    code, report = run_cli(tmp_path, pack_bytes(), transport)
    assert code == 0
    assert report["frames"][0]["differences"] == {}
    assert report["frames"][0]["observed"]["model_versions"] == {"tile": "new"}


@pytest.mark.parametrize(
    "archive",
    [
        b"not a zip",
        pack_bytes(mutate_members={"manifest.json": b"{"}),
        pack_bytes(mutate_members={"session.json": None}),
        pack_bytes(mutate_members={"surprise.txt": b"x"}),
        pack_bytes(checksums_override={"frames/000010.png": "0" * 64}),
        pack_bytes(duplicate="manifest.json"),
        pack_bytes(symlink="frames/000010.png"),
    ],
    ids=["unknown", "invalid-json", "missing", "extra", "tamper", "duplicate", "symlink"],
)
def test_invalid_archives_exit_two_and_write_report(tmp_path: Path, archive: bytes):
    transport, requests = successful_transport()
    code, report = run_cli(tmp_path, archive, transport)
    assert code == 2
    assert report["status"] == "archive_error"
    assert requests == []


def test_empty_archive_is_rejected_before_api_calls(tmp_path: Path):
    transport, requests = successful_transport()

    code, report = run_cli(tmp_path, pack_bytes(frames=[], baselines=[]), transport)

    assert code == 2
    assert report["status"] == "archive_error"
    assert "frame" in report["error"].lower()
    assert requests == []


def test_load_archive_does_not_retain_frame_bytes(tmp_path: Path):
    manifest, _, frames = replay.load_archive(write_pack(tmp_path, pack_bytes()))

    assert manifest["frame_count"] == 2
    assert all("image_bytes" not in frame for frame in frames)


def test_zip_bomb_compression_ratio_is_rejected(tmp_path: Path):
    archive = io.BytesIO()
    manifest = {
        "format": replay.FORMAT,
        "frame_count": 1,
        "frames": [
            {
                "sequence_id": 1,
                "request_id": "req",
                "frame_ts": 1,
                "content_type": "image/png",
                "rotation_degrees": 0,
                "sha256": hashlib.sha256(b"x").hexdigest(),
                "size_bytes": 1,
                "frame_path": "frames/1.png",
                "baseline_path": "baselines/1.json",
            }
        ],
    }
    members = {
        "manifest.json": json.dumps(manifest).encode(),
        "session.json": json.dumps({"session": {"rule_profile_id": "guangdong_mvp_v1"}}).encode(),
        "runtime-context.json": b"{}",
        "model-manifests.json": b"{}",
        "frames/1.png": b"x",
        "baselines/1.json": json.dumps(result()).encode(),
        "bomb.json": b"0" * (1024 * 1024),
    }
    checksums = {name: hashlib.sha256(value).hexdigest() for name, value in members.items()}
    members["checksums.json"] = json.dumps({"sha256": checksums}).encode()
    with zipfile.ZipFile(archive, "w", compression=zipfile.ZIP_DEFLATED) as output:
        for name, value in members.items():
            output.writestr(name, value)

    with pytest.raises(replay.ArchiveError, match="compression ratio|undeclared"):
        replay.load_archive(write_pack(tmp_path, archive.getvalue()))


def test_required_report_write_failure_returns_two(tmp_path: Path, capsys: pytest.CaptureFixture[str]):
    archive_path = write_pack(tmp_path, pack_bytes())
    invalid_parent = tmp_path / "not-a-directory"
    invalid_parent.write_text("occupied", encoding="utf-8")
    transport, _ = successful_transport()

    code = replay.main(
        [
            "--archive",
            str(archive_path),
            "--base-url",
            "https://cloud.test",
            "--api-key",
            "secret",
            "--report",
            str(invalid_parent / "report.json"),
        ],
        transport=transport,
    )

    assert code == 2
    assert "could not write report" in capsys.readouterr().err


@pytest.mark.parametrize("bad_path", ["/abs.png", "../escape.png", "frames\\bad.png"])
def test_unsafe_member_paths_are_rejected(tmp_path: Path, bad_path: str):
    content = pack_bytes(mutate_members={bad_path: b"x"})
    transport, _ = successful_transport()
    code, report = run_cli(tmp_path, content, transport)
    assert code == 2
    assert report["status"] == "archive_error"


@pytest.mark.parametrize(
    "change",
    [
        {"sequence_id": 10},
        {"sequence_id": 5},
        {"request_id": ""},
        {"content_type": "image/gif"},
        {"rotation_degrees": 45},
        {"frame_ts": "bad"},
        {"size_bytes": -1},
    ],
)
def test_invalid_frame_metadata_is_rejected(tmp_path: Path, change: dict[str, Any]):
    base = json.loads(zipfile.ZipFile(io.BytesIO(pack_bytes())).read("manifest.json"))["frames"]
    frames = [dict(base[0]), dict(base[1])]
    frames[1].update(change)
    transport, _ = successful_transport()
    code, report = run_cli(tmp_path, pack_bytes(frames=frames), transport)
    assert code == 2
    assert report["status"] == "archive_error"


@pytest.mark.parametrize("kind", ["401", "503", "429", "non-json", "timeout"])
def test_api_failures_exit_three_end_when_session_exists(tmp_path: Path, kind: str):
    requests: list[str] = []

    def handler(request: httpx.Request) -> httpx.Response:
        requests.append(request.url.path)
        if request.url.path == "/health/ready":
            if kind == "503":
                return httpx.Response(503, json={"status": "not_ready"})
            return httpx.Response(200, json={"status": "ready"})
        if request.url.path == "/api/v1/mahjong/sessions":
            if kind == "401":
                return httpx.Response(401, json={"error": {"code": "UNAUTHORIZED"}})
            return httpx.Response(200, json={"session_id": "candidate-session"})
        if request.url.path.endswith("/end"):
            return httpx.Response(200, json={"status": "ended"})
        if kind == "429":
            return httpx.Response(429, json={"error": {"code": "BUSY"}})
        if kind == "non-json":
            return httpx.Response(200, text="not-json")
        if kind == "timeout":
            raise httpx.ReadTimeout("slow", request=request)
        raise AssertionError(kind)

    code, report = run_cli(tmp_path, pack_bytes(), httpx.MockTransport(handler))
    assert code == 3
    assert report["status"] == "api_error"
    assert ("/end" in requests[-1]) is (kind not in {"401", "503"})


def test_end_failure_has_lowest_priority(tmp_path: Path):
    transport, _ = successful_transport(end_status=503)
    code, report = run_cli(tmp_path, pack_bytes(), transport)
    assert code == 4
    assert report["status"] == "end_error"

    transport, _ = successful_transport([result(status="different"), result()], end_status=503)
    code, report = run_cli(tmp_path, pack_bytes(), transport)
    assert code == 1
    assert report["status"] == "semantic_diff"


def test_diagnostic_matching_baseline_passes(tmp_path: Path):
    diagnostic = result(
        status="diagnostic",
        diagnostic_code="HAND_REGION_NOT_FOUND",
        hand_tiles=[],
        meld_groups=[],
        bonus_tiles=[],
        suggestion=None,
    )
    transport, _ = successful_transport([diagnostic, diagnostic])
    code, report = run_cli(tmp_path, pack_bytes(baselines=[diagnostic, diagnostic]), transport)
    assert code == 0
    assert report["summary"]["pass_rate"] == 1.0
