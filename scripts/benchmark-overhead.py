#!/usr/bin/env python3
"""Run each monitoring trial in a fresh JVM and retain per-trial and aggregate data."""

import argparse
import csv
import hashlib
import json
import math
import os
import platform
import signal
import statistics
import subprocess
import tempfile
from datetime import datetime, timezone
from pathlib import Path

FORK_FIELDS = (
    "operations", "wall_ns", "thread_cpu_ns", "process_cpu_ns", "heap_before_bytes", "heap_after_bytes",
    "samples_accepted", "samples_completed", "ticks_dropped", "tick_retries", "rule_incidents", "checksum",
)
FIELDS = ("pair", "mode", "status", "exit_code", *FORK_FIELDS, "operations_per_second")
AGGREGATE_FIELDS = (*FORK_FIELDS[:-1], "operations_per_second")
JVM = ["java", "-Xms128m", "-Xmx128m", "-XX:ActiveProcessorCount=2", "-cp", ".local-build/classes",
       "dev.jvmsentinel.cli.MonitoringBenchmarkTrial"]
BASE = Path(".local-build/benchmarks")


def describe(values):
    ordered = sorted(values)
    if not ordered:
        return {"n": 0, "median": None, "p95": None, "min": None, "max": None}
    return {"n": len(ordered), "median": statistics.median(ordered),
            "p95": ordered[math.ceil(len(ordered) * .95) - 1], "min": ordered[0], "max": ordered[-1]}


def overhead(baseline, monitored):
    if baseline is None or monitored is None or not math.isfinite(baseline) or not math.isfinite(monitored):
        return None
    return (baseline - monitored) / baseline * 100 if baseline > 0 and monitored >= 0 else None


def parse_trial(line, expected_operations):
    values = line.strip().split(",")
    if len(values) != len(FORK_FIELDS):
        raise ValueError("Incorrect trial CSV width")
    row = dict(zip(FORK_FIELDS, values))
    for name in FORK_FIELDS[:-1]:
        if name in ("thread_cpu_ns", "process_cpu_ns") and row[name] == "":
            continue
        row[name] = int(row[name])
        if row[name] < 0:
            raise ValueError("Negative trial measurement")
    if row["operations"] != expected_operations or row["wall_ns"] <= 0:
        raise ValueError("Incorrect operation count or invalid duration")
    if len(row["checksum"]) != 16 or any(char not in "0123456789abcdef" for char in row["checksum"]):
        raise ValueError("Invalid workload checksum")
    row["operations_per_second"] = row["operations"] * 1e9 / row["wall_ns"]
    return row


def self_check():
    assert describe(range(1, 21)) == {"n": 20, "median": 10.5, "p95": 19, "min": 1, "max": 20}
    assert describe([])["median"] is None
    assert overhead(100, 75) == 25 and overhead(100, 110) == -10
    assert overhead(0, 100) is None and overhead(100, float("nan")) is None
    fixture = "100,1000000000,,10,50,51,3,2,1,1,0,0000000000000001"
    assert parse_trial(fixture, 100)["operations_per_second"] == 100
    assert parse_trial(fixture, 100)["thread_cpu_ns"] == ""
    try:
        parse_trial(fixture, 101)
    except ValueError:
        pass
    else:
        raise AssertionError("Invalid operation count was accepted")
    print("PASS: synthetic aggregate, overhead, unavailable value, and CSV checks")


def physical_memory():
    if platform.system() == "Darwin":
        raw = subprocess.check_output(["sysctl", "-n", "hw.memsize"], text=True).strip()
        return int(raw)
    try:
        return os.sysconf("SC_PHYS_PAGES") * os.sysconf("SC_PAGE_SIZE")
    except (AttributeError, OSError, ValueError):
        return None


def capture_trial(command, expected_operations):
    with subprocess.Popen(command, stdout=subprocess.PIPE, stderr=subprocess.PIPE, text=True) as process:
        try:
            stdout, _ = process.communicate(timeout=20)
        except subprocess.TimeoutExpired:
            process.kill()
            process.communicate()
            return {"status": "TIMEOUT", "exit_code": process.returncode}
        except KeyboardInterrupt:
            process.kill()
            process.communicate()
            raise
        if process.returncode:
            return {"status": "FAILED", "exit_code": process.returncode}
    try:
        result = parse_trial(stdout, expected_operations)
    except (ValueError, OverflowError):
        return {"status": "INVALID_OUTPUT", "exit_code": 0}
    return {"status": "PASS", "exit_code": 0, **result}


def compute_aggregates(rows):
    by_mode = {
        mode: {field: describe(row[field] for row in rows if row["mode"] == mode
                               and row["status"] == "PASS" and row[field] != "")
               for field in AGGREGATE_FIELDS}
        for mode in ("off", "on")
    }
    completeness = all(row["status"] == "PASS" for row in rows)
    comparison = overhead(by_mode["off"]["operations_per_second"]["median"],
                          by_mode["on"]["operations_per_second"]["median"]) if completeness else None
    return by_mode, comparison


def source_fingerprint():
    globs = [":(glob)sentinel-*/src/**", ":(glob)sentinel-*/pom.xml", "pom.xml", "scripts/run-local.sh",
             "scripts/benchmark-overhead.sh", "scripts/benchmark-overhead.py", "scripts/demo-phase7.sh"]
    listed = subprocess.check_output(["git", "ls-files", "-z", "--cached", "--others",
                                      "--exclude-standard", "--", *globs])
    digest = hashlib.sha256()
    for name in sorted(set(listed.split(b"\0")) - {b""}):
        path = Path(os.fsdecode(name))
        digest.update(name + b"\0")
        digest.update(hashlib.sha256(path.read_bytes()).digest() if path.is_file() else b"missing")
    return digest.hexdigest()


def run(args):
    java = subprocess.run(["java", "-version"], capture_output=True, text=True, check=True).stderr.strip()
    if not java.startswith('java version "21.') and not java.startswith('openjdk version "21.'):
        raise SystemExit("Use a Java 21 JDK.")
    subprocess.run(["bash", "scripts/run-local.sh", "--help"], stdout=subprocess.DEVNULL, check=True, timeout=30)
    BASE.mkdir(parents=True, exist_ok=True)
    folder = Path(tempfile.mkdtemp(prefix="overhead-", dir=BASE))
    print(f"Benchmark artifacts: {folder}", flush=True)
    params = [str(args.warmup), str(args.operations), str(args.interval_ms)]
    commands = {mode: JVM + [mode] + params for mode in ("off", "on")}
    metadata = {
        "utc": datetime.now(timezone.utc).isoformat(),
        "commit": subprocess.check_output(["git", "rev-parse", "HEAD"], text=True).strip(),
        "source_sha256": source_fingerprint(),
        "dirty": bool(subprocess.check_output(["git", "status", "--porcelain"], text=True).strip()),
        "java": java, "os": platform.system(), "os_release": platform.release(), "architecture": platform.machine(),
        "logical_cpus": os.cpu_count(), "physical_ram_bytes": physical_memory(),
        "trials_per_mode": args.trials, "warmup_operations_per_jvm": args.warmup,
        "operations_per_trial": args.operations, "sampler_interval_ms": args.interval_ms,
        "commands": commands, "trial_timeout_seconds": 20,
        "exploratory": args.trials >= 10,
    }
    (folder / "environment.json").write_text(json.dumps(metadata, indent=2) + "\n", encoding="utf-8")
    rows = []
    with (folder / "trials.csv").open("w", newline="", encoding="utf-8") as output:
        writer = csv.DictWriter(output, FIELDS)
        writer.writeheader()
        for pair in range(1, args.trials + 1):
            order = ("off", "on") if pair % 2 else ("on", "off")
            trial_pair = [{"pair": pair, "mode": mode, **capture_trial(commands[mode], args.operations)}
                          for mode in order]
            if all(row["status"] == "PASS" for row in trial_pair) and \
                    trial_pair[0]["checksum"] != trial_pair[1]["checksum"]:
                for row in trial_pair:
                    row["status"] = "CHECKSUM_MISMATCH"
            writer.writerows(trial_pair)
            output.flush()
            rows.extend(trial_pair)
    by_mode, estimate = compute_aggregates(rows)
    failures = sum(row["status"] != "PASS" for row in rows)
    report = {"aggregates": by_mode, "relative_throughput_overhead_percent": estimate, "failures": failures}
    (folder / "summary.json").write_text(json.dumps(report, indent=2) + "\n", encoding="utf-8")
    for mode in ("off", "on"):
        result = by_mode[mode]["operations_per_second"]
        print(f"{mode}: {result['n']} successful trials, operations/s {result}")
    print(f"Relative throughput overhead (%): {estimate if estimate is not None else 'unavailable'}")
    print(f"Failed trials: {failures}")
    return 1 if failures else 0


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--self-check", action="store_true")
    parser.add_argument("--trials", type=int, default=10, help="pairs of trials, 1–50; fewer than 10 is a sanity run")
    parser.add_argument("--warmup", type=int, default=40_000_000)
    parser.add_argument("--operations", type=int, default=250_000_000)
    parser.add_argument("--interval-ms", type=int, default=10)
    args = parser.parse_args()
    if args.self_check:
        self_check()
        return 0
    if not (1 <= args.trials <= 50 and 1 <= args.warmup <= 1_000_000_000
            and 1 <= args.operations <= 2_000_000_000 and 1 <= args.interval_ms <= 1000):
        parser.error("Trial parameters out of range")
    return run(args)


def stop_on_signal(_number, _frame):
    raise KeyboardInterrupt


if __name__ == "__main__":
    signal.signal(signal.SIGTERM, stop_on_signal)
    try:
        raise SystemExit(main())
    except KeyboardInterrupt:
        raise SystemExit("Benchmark interrupted and active child stopped.")
