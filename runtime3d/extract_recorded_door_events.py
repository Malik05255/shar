#!/usr/bin/env python3
"""Detect and extract the real opening/closing events from one recorded door cycle.

No sound is synthesized. The source is decoded to mono float PCM only for RMS analysis; the chosen
intervals are then cut from the original recording and normalized with ffmpeg.
"""
from __future__ import annotations

import argparse
from array import array
import json
import math
import subprocess
from dataclasses import dataclass
from pathlib import Path
from statistics import median

SAMPLE_RATE = 44_100
WINDOW_MS = 50
HOP_MS = 25
MERGE_GAP_MS = 220
PAD_MS = 110
MIN_EVENT_MS = 180


@dataclass(frozen=True)
class Window:
    start: float
    end: float
    rms: float


@dataclass(frozen=True)
class Cluster:
    start: float
    end: float
    peak_rms: float
    mean_rms: float

    @property
    def duration(self) -> float:
        return self.end - self.start


def percentile(values: list[float], q: float) -> float:
    if not values:
        return 0.0
    ordered = sorted(values)
    index = int(round((len(ordered) - 1) * min(1.0, max(0.0, q))))
    return ordered[index]


def decode_pcm(path: Path) -> array:
    proc = subprocess.run(
        [
            "ffmpeg", "-hide_banner", "-loglevel", "error", "-i", str(path),
            "-vn", "-ac", "1", "-ar", str(SAMPLE_RATE), "-f", "f32le", "-",
        ],
        check=True,
        capture_output=True,
    )
    samples = array("f")
    samples.frombytes(proc.stdout)
    if not samples:
        raise RuntimeError("Door recording decoded to empty PCM")
    return samples


def rms(samples: array, start: int, end: int) -> float:
    start = max(0, start)
    end = min(len(samples), end)
    if end <= start:
        return 0.0
    total = 0.0
    count = 0
    for i in range(start, end):
        value = float(samples[i])
        if not math.isfinite(value):
            continue
        value = min(1.0, max(-1.0, value))
        total += value * value
        count += 1
    return math.sqrt(total / count) if count else 0.0


def analyze(samples: array) -> tuple[list[Window], float, float, float]:
    window_size = max(1, SAMPLE_RATE * WINDOW_MS // 1000)
    hop = max(1, SAMPLE_RATE * HOP_MS // 1000)
    windows: list[Window] = []
    for start in range(0, len(samples), hop):
        end = min(len(samples), start + window_size)
        windows.append(Window(start / SAMPLE_RATE, end / SAMPLE_RATE, rms(samples, start, end)))
        if end == len(samples):
            break
    values = [w.rms for w in windows]
    noise = percentile(values, 0.20)
    strong = percentile(values, 0.95)
    peak = max(values, default=0.0)
    # The recording can contain long digital silence, so the threshold is anchored both to the
    # measured floor and to the active recording's own high-percentile energy.
    threshold = max(0.00035, noise * 3.0, strong * 0.075, peak * 0.035)
    return windows, noise, peak, threshold


def clusters_from_windows(windows: list[Window], threshold: float) -> list[Cluster]:
    active = [w for w in windows if w.rms >= threshold]
    if not active:
        return []
    merge_gap = MERGE_GAP_MS / 1000.0
    groups: list[list[Window]] = [[active[0]]]
    for w in active[1:]:
        if w.start - groups[-1][-1].end <= merge_gap:
            groups[-1].append(w)
        else:
            groups.append([w])
    clusters: list[Cluster] = []
    for group in groups:
        start = group[0].start
        end = group[-1].end
        if (end - start) * 1000.0 < MIN_EVENT_MS:
            continue
        energies = [w.rms for w in group]
        clusters.append(Cluster(start, end, max(energies), sum(energies) / len(energies)))
    return clusters


def split_single_cluster(cluster: Cluster, windows: list[Window], threshold: float) -> tuple[Cluster, Cluster]:
    inside = [w for w in windows if w.start >= cluster.start and w.end <= cluster.end]
    if len(inside) < 8:
        raise RuntimeError("Door recording has one unsplittable active event")
    lo = max(1, int(len(inside) * 0.22))
    hi = min(len(inside) - 2, int(len(inside) * 0.78))
    if hi <= lo:
        raise RuntimeError("Door recording active range is too short to separate open/close")
    # Prefer a real quiet valley. If no window drops under threshold, the minimum-energy point is
    # still deterministic and keeps us from fabricating a second sound from a silent tail.
    valley_index = min(range(lo, hi + 1), key=lambda i: inside[i].rms)
    split_time = (inside[valley_index].start + inside[valley_index].end) * 0.5
    left = [w for w in inside if w.end <= split_time]
    right = [w for w in inside if w.start >= split_time]
    if not left or not right:
        raise RuntimeError("Could not split recorded door cycle into two physical events")

    def build(group: list[Window]) -> Cluster:
        vals = [w.rms for w in group]
        return Cluster(group[0].start, group[-1].end, max(vals), sum(vals) / len(vals))

    return build(left), build(right)


def choose_events(clusters: list[Cluster], windows: list[Window], threshold: float) -> tuple[Cluster, Cluster, str]:
    if len(clusters) >= 2:
        # Keep chronological semantics from a source described as an opening+closing cycle.
        return clusters[0], clusters[-1], "first-last-active-clusters"
    if len(clusters) == 1:
        first, second = split_single_cluster(clusters[0], windows, threshold)
        return first, second, "largest-active-cluster-valley-split"
    raise RuntimeError("No physical door events detected")


def padded_interval(cluster: Cluster, total_duration: float) -> tuple[float, float]:
    pad = PAD_MS / 1000.0
    start = max(0.0, cluster.start - pad)
    end = min(total_duration, cluster.end + pad)
    if end - start < MIN_EVENT_MS / 1000.0:
        raise RuntimeError(f"Detected door event too short: {start:.3f}..{end:.3f}")
    return start, end


def extract(source: Path, output: Path, start: float, end: float) -> None:
    duration = end - start
    fade_out_start = max(0.0, duration - min(0.16, duration * 0.16))
    output.parent.mkdir(parents=True, exist_ok=True)
    subprocess.run(
        [
            "ffmpeg", "-hide_banner", "-loglevel", "error", "-y",
            "-ss", f"{start:.6f}", "-t", f"{duration:.6f}", "-i", str(source),
            "-af",
            (
                "highpass=f=55,"
                "loudnorm=I=-25:TP=-4:LRA=12,"
                "afade=t=in:st=0:d=0.015,"
                f"afade=t=out:st={fade_out_start:.6f}:d={duration-fade_out_start:.6f}"
            ),
            "-ac", "1", "-ar", str(SAMPLE_RATE), "-c:a", "libvorbis", "-q:a", "5",
            str(output),
        ],
        check=True,
    )
    if not output.is_file() or output.stat().st_size < 1000:
        raise RuntimeError(f"Extracted door cue missing/corrupt: {output}")


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--input", type=Path, required=True)
    parser.add_argument("--open-output", type=Path, required=True)
    parser.add_argument("--close-output", type=Path, required=True)
    parser.add_argument("--report", type=Path, required=True)
    args = parser.parse_args()
    if not args.input.is_file():
        raise SystemExit(f"Door source missing: {args.input}")

    samples = decode_pcm(args.input)
    total_duration = len(samples) / SAMPLE_RATE
    windows, noise, peak, threshold = analyze(samples)
    clusters = clusters_from_windows(windows, threshold)
    open_cluster, close_cluster, strategy = choose_events(clusters, windows, threshold)
    open_start, open_end = padded_interval(open_cluster, total_duration)
    close_start, close_end = padded_interval(close_cluster, total_duration)
    if close_start <= open_start:
        raise RuntimeError("Detected close event is not after opening event")

    extract(args.input, args.open_output, open_start, open_end)
    extract(args.input, args.close_output, close_start, close_end)

    report = {
        "source": args.input.name,
        "sourceDuration": total_duration,
        "sampleRate": SAMPLE_RATE,
        "windowMs": WINDOW_MS,
        "hopMs": HOP_MS,
        "noiseFloorRms": noise,
        "peakRms": peak,
        "activeThresholdRms": threshold,
        "strategy": strategy,
        "clusters": [
            {"start": c.start, "end": c.end, "duration": c.duration,
             "peakRms": c.peak_rms, "meanRms": c.mean_rms}
            for c in clusters
        ],
        "open": {"start": open_start, "end": open_end, "duration": open_end - open_start},
        "close": {"start": close_start, "end": close_end, "duration": close_end - close_start},
        "synthesizedAudio": False,
        "productionGate": "CLOSED",
    }
    args.report.parent.mkdir(parents=True, exist_ok=True)
    args.report.write_text(json.dumps(report, indent=2) + "\n", encoding="utf-8")
    print(json.dumps(report, indent=2), flush=True)
    print("RECORDED_DOOR_EVENT_DETECTION=PASS", flush=True)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
