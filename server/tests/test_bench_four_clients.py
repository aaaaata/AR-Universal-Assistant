from __future__ import annotations

import importlib.util
from pathlib import Path


ROOT = Path(__file__).resolve().parents[2]
MODULE_PATH = ROOT / "tools" / "bench_four_clients.py"


def load_bench():
    spec = importlib.util.spec_from_file_location("bench_four_clients", MODULE_PATH)
    module = importlib.util.module_from_spec(spec)
    assert spec.loader is not None
    spec.loader.exec_module(module)
    return module


def test_pick_stable_interval_chooses_smallest_passing_cadence():
    bench = load_bench()
    runs = [
        {
            "interval_ms": 1000,
            "sent": 80,
            "ok": 40,
            "http_429": 30,
            "wall_ms": {"p95": 4200},
        },
        {
            "interval_ms": 3000,
            "sent": 60,
            "ok": 58,
            "http_429": 1,
            "wall_ms": {"p95": 1400},
        },
        {
            "interval_ms": 2000,
            "sent": 60,
            "ok": 59,
            "http_429": 1,
            "wall_ms": {"p95": 1800},
        },
    ]
    assert bench.pick_stable_interval(runs) == 2000


def test_classify_busy_errors_and_timeouts():
    bench = load_bench()
    assert bench.classify_result(429, {"error": {"code": "ANALYSIS_BUSY"}}, None) == "http_429"
    assert bench.classify_result(409, {"error": {"code": "SESSION_ANALYSIS_BUSY"}}, None) == "http_409"
    assert bench.classify_result(200, {"status": "complete"}, None) == "ok"
    assert bench.classify_result(None, None, TimeoutError("timed out")) == "timeout"
