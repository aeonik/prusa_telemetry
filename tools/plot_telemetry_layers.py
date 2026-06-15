#!/usr/bin/env python3
"""Plot Prusa telemetry KPIs overlaid by layer or print-position phase.

The telemetry logger writes one EDN map per line. This script reads those
records, flattens numeric metrics into KPI series, assigns each sample to a
layer when possible, and renders page-based PNG reports. In watch mode it polls
the file and regenerates plots when the file changes.

True layer overlays require either a layer metric in the telemetry stream or
the matching G-code file so `sdpos` can be mapped to G-code byte offsets. Without
that, the script falls back to monotonic `sdpos` segments and labels the plots as
segments rather than real layers.
"""

from __future__ import annotations

import argparse
import math
import os
import queue
import re
import sys
import threading
import time
from collections import deque
from dataclasses import dataclass
from pathlib import Path
from typing import Any, Iterable

os.environ.setdefault("MPLCONFIGDIR", "/tmp/prusa_telemetry_mpl")

import matplotlib

import numpy as np
import pandas as pd


plt: Any = None


def get_pyplot(gui: bool = False) -> Any:
    """Import pyplot after selecting the right backend for this run mode."""
    global plt
    if plt is not None:
        return plt
    if gui:
        matplotlib.use("TkAgg", force=True)
    elif not os.environ.get("DISPLAY") and sys.platform != "darwin":
        matplotlib.use("Agg", force=True)
    import matplotlib.pyplot as pyplot

    plt = pyplot
    return plt


LAYER_KPI_CANDIDATES = (
    "layer",
    "layer_num",
    "layer_nr",
    "print_layer",
    "current_layer",
    "z",
    "z_pos",
    "zpos",
    "pos_z",
)


class EdnParseError(ValueError):
    """Raised when a telemetry EDN line cannot be parsed."""


class EdnReader:
    """Small EDN subset reader for the telemetry line format."""

    def __init__(self, text: str):
        self.text = text
        self.i = 0
        self.n = len(text)

    def parse(self) -> Any:
        value = self._parse_value()
        self._skip_ws()
        if self.i != self.n:
            raise EdnParseError(f"trailing data at byte {self.i}")
        return value

    def _skip_ws(self) -> None:
        while self.i < self.n:
            ch = self.text[self.i]
            if ch.isspace() or ch == ",":
                self.i += 1
                continue
            if ch == ";":
                while self.i < self.n and self.text[self.i] != "\n":
                    self.i += 1
                continue
            break

    def _parse_value(self) -> Any:
        self._skip_ws()
        if self.i >= self.n:
            raise EdnParseError("unexpected end of input")
        ch = self.text[self.i]
        if ch == "{":
            return self._parse_map()
        if ch == "[":
            return self._parse_sequence("]", list)
        if ch == "(":
            return self._parse_sequence(")", list)
        if ch == '"':
            return self._parse_string()
        if ch == ":":
            return self._parse_keyword()
        if ch == "#":
            return self._parse_tagged()
        return self._parse_token()

    def _parse_map(self) -> dict[Any, Any]:
        result: dict[Any, Any] = {}
        self.i += 1
        while True:
            self._skip_ws()
            if self.i >= self.n:
                raise EdnParseError("unterminated map")
            if self.text[self.i] == "}":
                self.i += 1
                return result
            key = self._parse_value()
            self._skip_ws()
            if self.i >= self.n or self.text[self.i] == "}":
                raise EdnParseError("map has key without value")
            result[key] = self._parse_value()

    def _parse_sequence(self, end: str, factory: type[list]) -> list[Any]:
        result: list[Any] = []
        self.i += 1
        while True:
            self._skip_ws()
            if self.i >= self.n:
                raise EdnParseError(f"unterminated sequence, expected {end}")
            if self.text[self.i] == end:
                self.i += 1
                return factory(result)
            result.append(self._parse_value())

    def _parse_string(self) -> str:
        self.i += 1
        chars: list[str] = []
        while self.i < self.n:
            ch = self.text[self.i]
            self.i += 1
            if ch == '"':
                return "".join(chars)
            if ch != "\\":
                chars.append(ch)
                continue
            if self.i >= self.n:
                raise EdnParseError("unterminated string escape")
            esc = self.text[self.i]
            self.i += 1
            if esc == "n":
                chars.append("\n")
            elif esc == "r":
                chars.append("\r")
            elif esc == "t":
                chars.append("\t")
            elif esc == "b":
                chars.append("\b")
            elif esc == "f":
                chars.append("\f")
            elif esc in {'"', "\\", "/"}:
                chars.append(esc)
            elif esc == "u":
                if self.i + 4 > self.n:
                    raise EdnParseError("short unicode escape")
                chars.append(chr(int(self.text[self.i : self.i + 4], 16)))
                self.i += 4
            else:
                chars.append(esc)
        raise EdnParseError("unterminated string")

    def _parse_keyword(self) -> str:
        self.i += 1
        start = self.i
        while self.i < self.n and not self._is_delimiter(self.text[self.i]):
            self.i += 1
        return self.text[start : self.i]

    def _parse_tagged(self) -> Any:
        self.i += 1
        tag = self._parse_token()
        value = self._parse_value()
        return {"tag": tag, "value": value}

    def _parse_token(self) -> Any:
        start = self.i
        while self.i < self.n and not self._is_delimiter(self.text[self.i]):
            self.i += 1
        token = self.text[start : self.i]
        if token == "":
            raise EdnParseError(f"unexpected character {self.text[self.i]!r}")
        if token == "nil":
            return None
        if token == "true":
            return True
        if token == "false":
            return False
        if token == "NaN":
            return math.nan
        if token in ("Infinity", "+Infinity"):
            return math.inf
        if token == "-Infinity":
            return -math.inf
        try:
            if any(ch in token for ch in (".", "e", "E")):
                return float(token)
            return int(token)
        except ValueError:
            return token

    @staticmethod
    def _is_delimiter(ch: str) -> bool:
        return ch.isspace() or ch in '{}[]()",;'


@dataclass(frozen=True)
class LayerSpan:
    index: int
    start: int
    end: int
    z: float | None = None


def is_number(value: Any) -> bool:
    return isinstance(value, (int, float, np.integer, np.floating)) and not isinstance(value, bool)


def read_lines(path: Path, max_lines: int | None) -> list[str]:
    if max_lines is None:
        with path.open("r", encoding="utf-8", errors="replace") as f:
            return f.readlines()
    with path.open("r", encoding="utf-8", errors="replace") as f:
        return list(deque(f, maxlen=max_lines))


def parse_packet(line: str) -> dict[str, Any]:
    value = EdnReader(line).parse()
    if not isinstance(value, dict):
        raise EdnParseError("telemetry line is not an EDN map")
    return value


def metric_time(metric: dict[str, Any], packet: dict[str, Any]) -> float:
    if is_number(metric.get("device-time-us")):
        return float(metric["device-time-us"])
    prelude = packet.get("prelude") if isinstance(packet.get("prelude"), dict) else {}
    base = prelude.get("base-time-us") if isinstance(prelude, dict) else None
    if base is None and isinstance(prelude, dict):
        base = prelude.get("tm")
    if is_number(metric.get("offset-us")) and is_number(base):
        return float(base) + float(metric["offset-us"])
    if is_number(metric.get("offset-ms")) and is_number(base):
        return float(base) + float(metric["offset-ms"]) * 1000.0
    if is_number(packet.get("received-at")):
        return float(packet["received-at"]) * 1000.0
    return math.nan


def flatten_packets(lines: Iterable[str]) -> tuple[pd.DataFrame, pd.DataFrame, dict[str, int]]:
    rows: list[dict[str, Any]] = []
    metadata_rows: list[dict[str, Any]] = []
    stats = {"lines": 0, "packets": 0, "parse_errors": 0, "metrics": 0}
    row_id = 0

    for line_no, line in enumerate(lines, start=1):
        stats["lines"] += 1
        if not line.strip():
            continue
        try:
            packet = parse_packet(line)
        except Exception as exc:
            stats["parse_errors"] += 1
            if stats["parse_errors"] <= 3:
                print(f"Skipping unparsable line {line_no}: {exc}", file=sys.stderr)
            continue

        stats["packets"] += 1
        packet_msg = None
        prelude = packet.get("prelude")
        if isinstance(prelude, dict):
            packet_msg = prelude.get("msg")
        received_at = packet.get("received-at")
        wall_time_str = packet.get("wall-time-str")
        sender = packet.get("sender")
        metrics = packet.get("metrics")
        if not isinstance(metrics, list):
            continue

        for metric_index, metric in enumerate(metrics):
            if not isinstance(metric, dict):
                continue
            stats["metrics"] += 1
            name = str(metric.get("name", "unknown"))
            mtype = str(metric.get("type", ""))
            sort_us = metric_time(metric, packet)
            base = {
                "row_id": row_id,
                "line_no": line_no,
                "packet_msg": packet_msg,
                "metric_index": metric_index,
                "metric_name": name,
                "metric_type": mtype,
                "received_at": received_at,
                "wall_time_str": wall_time_str,
                "sender": sender,
                "device_time_us": metric.get("device-time-us"),
                "device_time_str": metric.get("device-time-str"),
                "sort_us": sort_us,
            }

            if name in {"print_filename", "filament"}:
                metadata_rows.append({**base, "meta_name": name, "meta_value": metric.get("value")})
            elif name == "is_printing":
                metadata_rows.append({**base, "meta_name": name, "meta_value": metric.get("value")})

            if mtype == "structured" and isinstance(metric.get("fields"), dict):
                for field, value in metric["fields"].items():
                    if is_number(value):
                        rows.append({**base, "kpi": f"{name}.{field}", "value": float(value)})
                        row_id += 1
                continue

            if mtype == "error" or "error" in metric:
                rows.append({**base, "kpi": f"{name}.error_count", "value": 1.0})
                row_id += 1
                continue

            value = metric.get("value")
            if is_number(value):
                rows.append({**base, "kpi": name, "value": float(value)})
                row_id += 1

    df = pd.DataFrame(rows)
    metadata = pd.DataFrame(metadata_rows)
    return df, metadata, stats


def merge_context_series(df: pd.DataFrame, metadata: pd.DataFrame) -> pd.DataFrame:
    if df.empty:
        return df

    df = df.sort_values(["sort_us", "line_no", "metric_index", "row_id"], kind="mergesort").reset_index(drop=True)

    sdpos = (
        df.loc[df["kpi"] == "sdpos", ["sort_us", "value"]]
        .rename(columns={"value": "sdpos"})
        .dropna(subset=["sort_us"])
        .sort_values("sort_us")
    )
    if not sdpos.empty:
        df = pd.merge_asof(df, sdpos, on="sort_us", direction="backward")
        missing = df["sdpos"].isna()
        if missing.any():
            df.loc[missing, "sdpos"] = pd.merge_asof(
                df.loc[missing, ["sort_us"]].sort_values("sort_us"),
                sdpos,
                on="sort_us",
                direction="forward",
            )["sdpos"].to_numpy()
    else:
        df["sdpos"] = np.nan

    if not metadata.empty:
        for meta_name in ("print_filename", "filament", "is_printing"):
            series = (
                metadata.loc[metadata["meta_name"] == meta_name, ["sort_us", "meta_value"]]
                .dropna(subset=["sort_us"])
                .sort_values("sort_us")
                .rename(columns={"meta_value": meta_name})
            )
            if not series.empty:
                df = pd.merge_asof(df, series, on="sort_us", direction="backward")

    return df


def parse_gcode_layers(path: Path, min_gap_bytes: int = 128) -> list[LayerSpan]:
    layer_markers: list[dict[str, Any]] = []
    file_size = path.stat().st_size
    current_z: float | None = None

    def add_marker(offset: int, z: float | None = None, index_hint: int | None = None) -> None:
        if layer_markers and offset - int(layer_markers[-1]["start"]) < min_gap_bytes:
            if z is not None:
                layer_markers[-1]["z"] = z
            if index_hint is not None:
                layer_markers[-1]["index_hint"] = index_hint
            return
        layer_markers.append({"start": offset, "z": z, "index_hint": index_hint})

    offset = 0
    with path.open("rb") as f:
        for raw in f:
            line_offset = offset
            offset += len(raw)
            try:
                line = raw.decode("utf-8", errors="ignore").strip()
            except UnicodeDecodeError:
                continue
            if not line:
                continue

            if line.startswith(";LAYER_CHANGE"):
                add_marker(line_offset)
                continue

            layer_match = re.match(r";LAYER:([0-9]+)", line)
            if layer_match:
                add_marker(line_offset, index_hint=int(layer_match.group(1)))
                continue

            z_comment = re.match(r";Z:([-+]?[0-9]*\.?[0-9]+)", line)
            if z_comment:
                z = float(z_comment.group(1))
                add_marker(line_offset, z=z)
                current_z = z
                continue

            if line.startswith(("G0", "G1")):
                z_match = re.search(r"(?:^|\s)Z([-+]?[0-9]*\.?[0-9]+)", line)
                if z_match:
                    z = float(z_match.group(1))
                    if current_z is None or z > current_z + 0.001:
                        add_marker(line_offset, z=z)
                    current_z = z

    if not layer_markers:
        raise ValueError(f"no layer markers or Z moves found in {path}")

    layer_markers = sorted(layer_markers, key=lambda marker: int(marker["start"]))
    spans: list[LayerSpan] = []
    for order, marker in enumerate(layer_markers):
        start = int(marker["start"])
        end = int(layer_markers[order + 1]["start"]) - 1 if order + 1 < len(layer_markers) else file_size
        index = int(marker["index_hint"]) if marker.get("index_hint") is not None else order
        spans.append(LayerSpan(index=index, start=start, end=max(start + 1, end), z=marker.get("z")))
    return spans


def assign_layers_from_gcode(df: pd.DataFrame, spans: list[LayerSpan]) -> tuple[pd.DataFrame, str]:
    if df["sdpos"].isna().all():
        raise ValueError("telemetry has no sdpos samples; cannot map to G-code layers")

    starts = np.array([span.start for span in spans], dtype=float)
    ends = np.array([span.end for span in spans], dtype=float)
    indexes = np.array([span.index for span in spans], dtype=int)
    sdpos = df["sdpos"].to_numpy(dtype=float)
    span_indexes = np.searchsorted(starts, sdpos, side="right") - 1
    valid = (span_indexes >= 0) & (span_indexes < len(spans))

    df = df.copy()
    df["layer"] = np.nan
    df["layer_start"] = np.nan
    df["layer_end"] = np.nan
    df["phase"] = np.nan
    df.loc[valid, "layer"] = indexes[span_indexes[valid]]
    df.loc[valid, "layer_start"] = starts[span_indexes[valid]]
    df.loc[valid, "layer_end"] = ends[span_indexes[valid]]
    denom = np.maximum(df["layer_end"] - df["layer_start"], 1.0)
    df.loc[valid, "phase"] = ((df.loc[valid, "sdpos"] - df.loc[valid, "layer_start"]) / denom[valid]).clip(0.0, 1.0)
    return df, "gcode layer"


def assign_layers_from_metric(df: pd.DataFrame, layer_kpi: str) -> tuple[pd.DataFrame, str]:
    samples = (
        df.loc[df["kpi"] == layer_kpi, ["sort_us", "value"]]
        .rename(columns={"value": "layer"})
        .dropna(subset=["sort_us", "layer"])
        .sort_values("sort_us")
    )
    if samples.empty:
        raise ValueError(f"layer metric {layer_kpi!r} was not found")

    df = pd.merge_asof(df.sort_values("sort_us"), samples, on="sort_us", direction="backward")
    missing = df["layer"].isna()
    if missing.any():
        df.loc[missing, "layer"] = pd.merge_asof(
            df.loc[missing, ["sort_us"]].sort_values("sort_us"),
            samples,
            on="sort_us",
            direction="forward",
        )["layer"].to_numpy()
    df["layer"] = df["layer"].round().astype("Int64")
    df = assign_phase_within_groups(df)
    return df, f"telemetry metric {layer_kpi}"


def assign_layers_from_sdpos_segments(df: pd.DataFrame, reset_threshold: float) -> tuple[pd.DataFrame, str]:
    df = df.sort_values(["line_no", "metric_index", "row_id"], kind="mergesort").copy()
    sdpos = df["sdpos"].to_numpy(dtype=float)
    segment = np.zeros(len(df), dtype=int)
    current = 0
    last = math.nan
    max_seen = -math.inf
    for i, value in enumerate(sdpos):
        reset = (
            reset_threshold > 0
            and math.isfinite(value)
            and math.isfinite(last)
            and value < last - reset_threshold
            and (not math.isfinite(max_seen) or value < max_seen * 0.5)
        )
        if reset:
            current += 1
        segment[i] = current
        if math.isfinite(value):
            last = value
            max_seen = max(max_seen, value)
    df["layer"] = segment
    df = assign_phase_within_groups(df)
    return df, "sdpos segment"


def assign_phase_within_groups(df: pd.DataFrame) -> pd.DataFrame:
    df = df.copy()
    df["phase"] = np.nan
    for layer, index in df.groupby("layer", sort=True).groups.items():
        if pd.isna(layer):
            continue
        group = df.loc[index]
        sdpos = group["sdpos"]
        if sdpos.notna().sum() > 1 and float(sdpos.max()) > float(sdpos.min()):
            phase = ((sdpos - sdpos.min()) / max(float(sdpos.max() - sdpos.min()), 1.0)).clip(0.0, 1.0)
        else:
            count = len(group)
            phase = pd.Series(np.linspace(0.0, 1.0, count), index=group.index) if count > 1 else pd.Series([0.0], index=group.index)
        df.loc[group.index, "phase"] = phase
    return df


def choose_layer_kpi(df: pd.DataFrame, requested: str | None) -> str | None:
    if requested:
        return requested
    available = set(df["kpi"].dropna().astype(str))
    for candidate in LAYER_KPI_CANDIDATES:
        if candidate in available:
            return candidate
    return None


def assign_layer_phase(
    df: pd.DataFrame,
    gcode: Path | None,
    layer_kpi: str | None,
    reset_threshold: float,
) -> tuple[pd.DataFrame, str]:
    chosen_layer_kpi = choose_layer_kpi(df, layer_kpi)
    if chosen_layer_kpi:
        return assign_layers_from_metric(df, chosen_layer_kpi)
    if gcode is not None:
        spans = parse_gcode_layers(gcode)
        return assign_layers_from_gcode(df, spans)
    return assign_layers_from_sdpos_segments(df, reset_threshold)


def filter_kpis(df: pd.DataFrame, include: str | None, exclude: str | None) -> pd.DataFrame:
    result = df
    if include:
        pattern = re.compile(include)
        result = result[result["kpi"].astype(str).map(lambda kpi: bool(pattern.search(kpi)))]
    if exclude:
        pattern = re.compile(exclude)
        result = result[~result["kpi"].astype(str).map(lambda kpi: bool(pattern.search(kpi)))]
    return result


def summarize_for_plot(df: pd.DataFrame, bins: int, max_layers: int | None) -> pd.DataFrame:
    usable = df.dropna(subset=["kpi", "value", "phase", "layer"]).copy()
    if usable.empty:
        return usable
    usable["value"] = pd.to_numeric(usable["value"], errors="coerce")
    usable["phase"] = pd.to_numeric(usable["phase"], errors="coerce")
    usable["layer"] = pd.to_numeric(usable["layer"], errors="coerce")
    usable = usable[np.isfinite(usable["value"]) & np.isfinite(usable["phase"]) & np.isfinite(usable["layer"])]
    if usable.empty:
        return usable
    usable["layer"] = usable["layer"].astype(int)
    if max_layers is not None and max_layers > 0:
        layers = sorted(usable["layer"].unique())[-max_layers:]
        usable = usable[usable["layer"].isin(layers)]
    usable["phase_bin"] = np.floor(usable["phase"].clip(0.0, 1.0) * bins).astype(int).clip(0, bins - 1)
    usable["phase_mid"] = (usable["phase_bin"] + 0.5) / bins

    error_mask = usable["kpi"].astype(str).str.endswith(".error_count")
    normal = usable.loc[~error_mask]
    errors = usable.loc[error_mask]
    parts: list[pd.DataFrame] = []
    if not normal.empty:
        parts.append(
            normal.groupby(["kpi", "layer", "phase_bin", "phase_mid"], as_index=False)["value"].mean()
        )
    if not errors.empty:
        parts.append(
            errors.groupby(["kpi", "layer", "phase_bin", "phase_mid"], as_index=False)["value"].sum()
        )
    if not parts:
        return pd.DataFrame()
    return pd.concat(parts, ignore_index=True).sort_values(["kpi", "layer", "phase_bin"])


def clean_output_pages(output_dir: Path, prefix: str) -> None:
    output_dir.mkdir(parents=True, exist_ok=True)
    for old in output_dir.glob(f"{prefix}_page_*.png"):
        old.unlink()


def plot_periodic_kpi(ax: Any, kpi_df: pd.DataFrame, kpi: str, phase_label: str) -> None:
    layers = sorted(kpi_df["layer"].unique())
    cmap = get_pyplot().get_cmap("turbo", max(len(layers), 2))
    for idx, layer in enumerate(layers):
        layer_df = kpi_df[kpi_df["layer"] == layer].sort_values("phase_mid")
        color = cmap(idx / max(len(layers) - 1, 1))
        if len(layer_df) > 1:
            ax.plot(layer_df["phase_mid"], layer_df["value"], color=color, alpha=0.5, linewidth=1.1)
        else:
            ax.scatter(layer_df["phase_mid"], layer_df["value"], color=color, alpha=0.7, s=16)
    ax.set_title(kpi, fontsize=10)
    ax.set_xlim(0.0, 1.0)
    ax.set_xlabel(f"{phase_label} phase")
    ax.grid(True, alpha=0.25, linewidth=0.6)


def plot_polar_kpi(ax: Any, kpi_df: pd.DataFrame, kpi: str) -> None:
    kpi_df = kpi_df.copy()
    finite = kpi_df["value"].replace([np.inf, -np.inf], np.nan).dropna()
    if finite.empty:
        ax.axis("off")
        return
    vmin = float(finite.min())
    vmax = float(finite.max())
    if math.isclose(vmin, vmax):
        kpi_df["radius"] = 0.55
    else:
        kpi_df["radius"] = 0.1 + 0.9 * ((kpi_df["value"] - vmin) / (vmax - vmin))
    layers = sorted(kpi_df["layer"].unique())
    cmap = get_pyplot().get_cmap("turbo", max(len(layers), 2))
    for idx, layer in enumerate(layers):
        layer_df = kpi_df[kpi_df["layer"] == layer].sort_values("phase_mid")
        theta = layer_df["phase_mid"].to_numpy(dtype=float) * 2.0 * math.pi
        radius = layer_df["radius"].to_numpy(dtype=float)
        color = cmap(idx / max(len(layers) - 1, 1))
        if len(layer_df) > 1:
            ax.plot(theta, radius, color=color, alpha=0.45, linewidth=1.0)
        else:
            ax.scatter(theta, radius, color=color, alpha=0.7, s=14)
    ax.set_title(kpi, fontsize=10)
    ax.set_yticklabels([])
    ax.grid(True, alpha=0.25, linewidth=0.6)


def save_periodic_pages(
    summary: pd.DataFrame,
    output_dir: Path,
    title_suffix: str,
    rows: int,
    cols: int,
) -> list[Path]:
    pyplot = get_pyplot()
    clean_output_pages(output_dir, "periodic")
    outputs: list[Path] = []
    kpis = sorted(summary["kpi"].unique())
    page_size = rows * cols
    for page, start in enumerate(range(0, len(kpis), page_size), start=1):
        page_kpis = kpis[start : start + page_size]
        fig, axes = pyplot.subplots(rows, cols, figsize=(cols * 4.6, rows * 3.2), squeeze=False)
        for ax in axes.flat[len(page_kpis) :]:
            ax.axis("off")
        for ax, kpi in zip(axes.flat, page_kpis):
            kpi_df = summary[summary["kpi"] == kpi]
            plot_periodic_kpi(ax, kpi_df, kpi, title_suffix)
        fig.suptitle(f"KPI overlays by {title_suffix} phase", fontsize=14)
        fig.tight_layout(rect=(0, 0, 1, 0.96))
        output = output_dir / f"periodic_page_{page:02d}.png"
        fig.savefig(output, dpi=150)
        pyplot.close(fig)
        outputs.append(output)
    return outputs


def save_polar_pages(
    summary: pd.DataFrame,
    output_dir: Path,
    title_suffix: str,
    rows: int,
    cols: int,
) -> list[Path]:
    pyplot = get_pyplot()
    clean_output_pages(output_dir, "polar")
    outputs: list[Path] = []
    kpis = sorted(summary["kpi"].unique())
    page_size = rows * cols
    for page, start in enumerate(range(0, len(kpis), page_size), start=1):
        page_kpis = kpis[start : start + page_size]
        fig, axes = pyplot.subplots(
            rows,
            cols,
            figsize=(cols * 4.0, rows * 4.0),
            subplot_kw={"projection": "polar"},
            squeeze=False,
        )
        for ax in axes.flat[len(page_kpis) :]:
            ax.axis("off")
        for ax, kpi in zip(axes.flat, page_kpis):
            kpi_df = summary[summary["kpi"] == kpi].copy()
            plot_polar_kpi(ax, kpi_df, kpi)
        fig.suptitle(f"Radial KPI overlays by {title_suffix} phase", fontsize=14)
        fig.tight_layout(rect=(0, 0, 1, 0.96))
        output = output_dir / f"polar_page_{page:02d}.png"
        fig.savefig(output, dpi=150)
        pyplot.close(fig)
        outputs.append(output)
    return outputs


def prepare_summary(args: argparse.Namespace) -> tuple[pd.DataFrame, dict[str, Any]]:
    lines = read_lines(args.telemetry_file, args.history_lines)
    df, metadata, stats = flatten_packets(lines)
    if df.empty:
        raise RuntimeError("no numeric telemetry KPI rows found")

    df = merge_context_series(df, metadata)
    gcode = Path(args.gcode).expanduser().resolve() if args.gcode else None
    df, phase_label = assign_layer_phase(df, gcode, args.layer_metric, args.sdpos_reset_threshold)
    df = filter_kpis(df, args.include_kpi, args.exclude_kpi)
    summary = summarize_for_plot(df, args.phase_bins, args.max_layers)
    if summary.empty:
        raise RuntimeError("no KPI rows remain after layer/phase assignment and filtering")

    meta = {
        **stats,
        "kpis": int(summary["kpi"].nunique()),
        "layers": sorted(int(layer) for layer in summary["layer"].unique()),
        "phase_label": phase_label,
    }
    return summary, meta


def render_once(args: argparse.Namespace) -> tuple[list[Path], dict[str, Any]]:
    summary, meta = prepare_summary(args)

    output_dir = Path(args.output_dir).expanduser().resolve()
    output_dir.mkdir(parents=True, exist_ok=True)
    outputs: list[Path] = []
    if args.chart in {"periodic", "both"}:
        outputs.extend(save_periodic_pages(summary, output_dir, meta["phase_label"], args.rows, args.cols))
    if args.chart in {"polar", "both"}:
        outputs.extend(save_polar_pages(summary, output_dir, meta["phase_label"], args.rows, args.cols))

    meta = {**meta, "outputs": len(outputs)}
    return outputs, meta


def run_gui(args: argparse.Namespace) -> int:
    import tkinter as tk
    from tkinter import ttk

    try:
        get_pyplot(gui=True)
        from matplotlib.backends.backend_tkagg import FigureCanvasTkAgg, NavigationToolbar2Tk
        from matplotlib.figure import Figure
    except Exception as exc:
        print(f"Could not initialize GUI backend: {exc}", file=sys.stderr)
        print("Check that DISPLAY is set and that TkAgg is available in this Python.", file=sys.stderr)
        return 1

    class TelemetryPlotGui:
        def __init__(self) -> None:
            self.root = tk.Tk()
            self.root.title("Prusa Telemetry Layer KPI Viewer")
            self.root.geometry("1400x900")
            self.summary = pd.DataFrame()
            self.meta: dict[str, Any] = {}
            self.kpis: list[str] = []
            self.page = 0
            self.last_signature: tuple[int, int] | None = None
            self.loading = False
            self.results: queue.Queue[tuple[str, tuple[int, int] | None, Any, Any]] = queue.Queue()

            self.chart_var = tk.StringVar(value="polar" if args.chart == "polar" else "periodic")
            self.auto_var = tk.BooleanVar(value=not args.no_auto_refresh)
            self.paged_var = tk.BooleanVar(value=args.paged)
            self.filter_var = tk.StringVar(value=args.include_kpi or "")
            self.page_var = tk.StringVar(value="No KPI data")
            self.status_var = tk.StringVar(value="Opening telemetry viewer...")

            controls = ttk.Frame(self.root, padding=(8, 6))
            controls.pack(side=tk.TOP, fill=tk.X)

            ttk.Label(controls, text="Chart").pack(side=tk.LEFT)
            chart_select = ttk.Combobox(
                controls,
                textvariable=self.chart_var,
                values=("periodic", "polar"),
                state="readonly",
                width=10,
            )
            chart_select.pack(side=tk.LEFT, padx=(4, 12))
            chart_select.bind("<<ComboboxSelected>>", lambda _event: self.draw_page())

            self.prev_button = ttk.Button(controls, text="Prev", command=lambda: self.change_page(-1))
            self.prev_button.pack(side=tk.LEFT)
            ttk.Label(controls, textvariable=self.page_var, width=20, anchor=tk.CENTER).pack(side=tk.LEFT, padx=4)
            self.next_button = ttk.Button(controls, text="Next", command=lambda: self.change_page(1))
            self.next_button.pack(side=tk.LEFT, padx=(0, 12))
            ttk.Checkbutton(controls, text="Paged", variable=self.paged_var, command=self.draw_page).pack(side=tk.LEFT, padx=(0, 12))

            ttk.Label(controls, text="KPI regex").pack(side=tk.LEFT)
            filter_entry = ttk.Entry(controls, textvariable=self.filter_var, width=28)
            filter_entry.pack(side=tk.LEFT, padx=(4, 4))
            filter_entry.bind("<Return>", lambda _event: self.request_refresh(force=True))
            ttk.Button(controls, text="Apply", command=lambda: self.request_refresh(force=True)).pack(side=tk.LEFT, padx=(0, 12))

            ttk.Button(controls, text="Refresh", command=lambda: self.request_refresh(force=True)).pack(side=tk.LEFT)
            ttk.Checkbutton(controls, text="Auto refresh", variable=self.auto_var).pack(side=tk.LEFT, padx=(12, 0))

            status = ttk.Label(self.root, textvariable=self.status_var, anchor=tk.W, padding=(8, 4))
            status.pack(side=tk.BOTTOM, fill=tk.X)

            self.plot_frame = ttk.Frame(self.root)
            self.plot_frame.pack(side=tk.TOP, fill=tk.BOTH, expand=True)
            self.plot_frame.rowconfigure(0, weight=1)
            self.plot_frame.columnconfigure(0, weight=1)

            self.scroll_canvas = tk.Canvas(self.plot_frame, highlightthickness=0)
            self.v_scroll = ttk.Scrollbar(self.plot_frame, orient=tk.VERTICAL, command=self.scroll_canvas.yview)
            self.h_scroll = ttk.Scrollbar(self.plot_frame, orient=tk.HORIZONTAL, command=self.scroll_canvas.xview)
            self.scroll_canvas.configure(yscrollcommand=self.v_scroll.set, xscrollcommand=self.h_scroll.set)
            self.scroll_canvas.grid(row=0, column=0, sticky=tk.NSEW)
            self.v_scroll.grid(row=0, column=1, sticky=tk.NS)
            self.h_scroll.grid(row=1, column=0, sticky=tk.EW)

            self.figure = Figure(figsize=(18.0, 10.0), dpi=100)
            self.mpl_canvas = FigureCanvasTkAgg(self.figure, master=self.scroll_canvas)
            self.mpl_widget = self.mpl_canvas.get_tk_widget()
            self.mpl_window = self.scroll_canvas.create_window((0, 0), window=self.mpl_widget, anchor=tk.NW)
            self.mpl_widget.bind("<Configure>", self.update_scroll_region)
            self.scroll_canvas.bind("<Shift-MouseWheel>", self.on_shift_mousewheel)
            self.scroll_canvas.bind("<MouseWheel>", self.on_mousewheel)
            self.scroll_canvas.bind("<Button-4>", lambda _event: self.scroll_canvas.yview_scroll(-3, "units"))
            self.scroll_canvas.bind("<Button-5>", lambda _event: self.scroll_canvas.yview_scroll(3, "units"))
            self.mpl_widget.bind("<Shift-MouseWheel>", self.on_shift_mousewheel)
            self.mpl_widget.bind("<MouseWheel>", self.on_mousewheel)
            self.mpl_widget.bind("<Button-4>", lambda _event: self.scroll_canvas.yview_scroll(-3, "units"))
            self.mpl_widget.bind("<Button-5>", lambda _event: self.scroll_canvas.yview_scroll(3, "units"))

            toolbar = NavigationToolbar2Tk(self.mpl_canvas, self.root, pack_toolbar=False)
            toolbar.update()
            toolbar.pack(side=tk.BOTTOM, fill=tk.X)

            try:
                self.root.state("zoomed")
            except tk.TclError:
                try:
                    self.root.attributes("-zoomed", True)
                except tk.TclError:
                    pass

            self.root.after(100, lambda: self.request_refresh(force=True))
            self.root.after(max(250, int(args.poll_seconds * 1000)), self.poll_file)

        def update_scroll_region(self, _event: Any | None = None) -> None:
            self.scroll_canvas.configure(scrollregion=self.scroll_canvas.bbox("all"))

        def on_mousewheel(self, event: Any) -> None:
            self.scroll_canvas.yview_scroll(int(-1 * (event.delta / 120)), "units")

        def on_shift_mousewheel(self, event: Any) -> None:
            self.scroll_canvas.xview_scroll(int(-1 * (event.delta / 120)), "units")

        def current_signature(self) -> tuple[int, int] | None:
            try:
                stat = args.telemetry_file.stat()
            except OSError as exc:
                self.status_var.set(f"Cannot stat telemetry file: {exc}")
                return None
            return stat.st_size, stat.st_mtime_ns

        def worker_args(self) -> argparse.Namespace:
            worker_args = argparse.Namespace(**vars(args))
            include = self.filter_var.get().strip()
            worker_args.include_kpi = include or None
            return worker_args

        def request_refresh(self, force: bool = False) -> None:
            if self.loading:
                return
            signature = self.current_signature()
            if signature is None:
                return
            if not force and signature == self.last_signature:
                return

            self.loading = True
            self.status_var.set(f"Reading {args.telemetry_file.name}...")
            worker_args = self.worker_args()
            thread = threading.Thread(target=self.load_worker, args=(worker_args, signature), daemon=True)
            thread.start()
            self.root.after(100, self.consume_results)

        def load_worker(self, worker_args: argparse.Namespace, signature: tuple[int, int]) -> None:
            try:
                summary, meta = prepare_summary(worker_args)
            except Exception as exc:
                self.results.put(("error", signature, str(exc), None))
                return
            self.results.put(("ok", signature, summary, meta))

        def consume_results(self) -> None:
            try:
                status, signature, payload, meta = self.results.get_nowait()
            except queue.Empty:
                if self.loading:
                    self.root.after(100, self.consume_results)
                return

            self.loading = False
            if status == "error":
                self.status_var.set(f"Plot failed: {payload}")
                return

            self.last_signature = signature
            self.summary = payload
            self.meta = meta
            self.kpis = sorted(self.summary["kpi"].unique())
            self.page = min(self.page, max(self.page_count() - 1, 0))
            self.draw_page()

        def poll_file(self) -> None:
            if self.auto_var.get():
                self.request_refresh(force=False)
            self.root.after(max(250, int(args.poll_seconds * 1000)), self.poll_file)

        def page_size(self) -> int:
            if self.paged_var.get():
                return max(args.rows * args.cols, 1)
            return max(len(self.kpis), 1)

        def page_count(self) -> int:
            if not self.kpis:
                return 0
            if not self.paged_var.get():
                return 1
            return int(math.ceil(len(self.kpis) / self.page_size()))

        def change_page(self, delta: int) -> None:
            if not self.paged_var.get():
                return
            count = self.page_count()
            if count == 0:
                return
            self.page = (self.page + delta) % count
            self.draw_page()

        def layout_for_page(self) -> tuple[list[str], int, int]:
            if self.paged_var.get():
                start = self.page * self.page_size()
                page_kpis = self.kpis[start : start + self.page_size()]
                return page_kpis, args.rows, args.cols
            cols = min(max(args.wide_cols, 1), max(len(self.kpis), 1))
            rows = max(1, int(math.ceil(len(self.kpis) / cols)))
            return self.kpis, rows, cols

        def resize_figure(self, rows: int, cols: int, chart: str) -> None:
            if chart == "polar":
                cell_width = 3.15
                cell_height = 3.05
            else:
                cell_width = 3.45
                cell_height = 2.45
            if self.paged_var.get():
                visible_width = max(self.scroll_canvas.winfo_width(), 1200) / self.figure.dpi
                visible_height = max(self.scroll_canvas.winfo_height(), 700) / self.figure.dpi
                width = max(visible_width, cols * cell_width)
                height = max(visible_height, rows * cell_height + 0.6)
            else:
                width = max(12.0, cols * cell_width)
                height = max(8.0, rows * cell_height + 0.7)
            self.figure.set_size_inches(width, height, forward=True)

        def draw_page(self) -> None:
            self.figure.clear()
            count = self.page_count()
            if count == 0:
                ax = self.figure.add_subplot(1, 1, 1)
                ax.axis("off")
                ax.text(0.5, 0.5, "No KPI data loaded", ha="center", va="center")
                self.page_var.set("No KPI data")
                self.prev_button.configure(state=tk.DISABLED)
                self.next_button.configure(state=tk.DISABLED)
                self.mpl_canvas.draw_idle()
                return

            self.page = max(0, min(self.page, count - 1))
            chart = self.chart_var.get()
            phase_label = str(self.meta.get("phase_label", "layer"))
            page_kpis, rows, cols = self.layout_for_page()
            self.resize_figure(rows, cols, chart)

            for idx, kpi in enumerate(page_kpis, start=1):
                projection = "polar" if chart == "polar" else None
                ax = self.figure.add_subplot(rows, cols, idx, projection=projection)
                kpi_df = self.summary[self.summary["kpi"] == kpi]
                if chart == "polar":
                    plot_polar_kpi(ax, kpi_df, kpi)
                else:
                    plot_periodic_kpi(ax, kpi_df, kpi, phase_label)

            for idx in range(len(page_kpis) + 1, rows * cols + 1):
                ax = self.figure.add_subplot(rows, cols, idx)
                ax.axis("off")

            title = "Radial" if chart == "polar" else "Periodic"
            self.figure.suptitle(f"{title} KPI overlays by {phase_label} phase", fontsize=14)
            self.figure.tight_layout(rect=(0, 0, 1, 0.95))
            self.mpl_canvas.draw_idle()
            self.mpl_widget.update_idletasks()
            self.update_scroll_region()

            if self.paged_var.get():
                self.page_var.set(f"Page {self.page + 1} / {count}")
                state = tk.NORMAL if count > 1 else tk.DISABLED
                self.prev_button.configure(state=state)
                self.next_button.configure(state=state)
            else:
                self.page_var.set(f"All {len(self.kpis)} KPIs")
                self.prev_button.configure(state=tk.DISABLED)
                self.next_button.configure(state=tk.DISABLED)
            layers = self.meta.get("layers", [])
            layer_text = f"{layers[0]}..{layers[-1]}" if len(layers) > 8 else ", ".join(str(layer) for layer in layers)
            self.status_var.set(
                f"{self.meta.get('kpis', 0)} KPI(s), {self.meta.get('packets', 0)} packet(s), "
                f"{phase_label}s {layer_text}, grid {rows}x{cols}"
            )

        def run(self) -> int:
            self.root.mainloop()
            return 0

    try:
        return TelemetryPlotGui().run()
    except tk.TclError as exc:
        print(f"Could not open GUI: {exc}", file=sys.stderr)
        print("Check that DISPLAY is set and that you are running from a desktop session.", file=sys.stderr)
        return 1


def print_kpis(args: argparse.Namespace) -> None:
    lines = read_lines(args.telemetry_file, args.history_lines)
    df, metadata, stats = flatten_packets(lines)
    df = merge_context_series(df, metadata) if not df.empty else df
    print(f"Read {stats['packets']} packets, {stats['metrics']} metrics, {stats['parse_errors']} parse errors")
    for kpi in sorted(df["kpi"].unique()) if not df.empty else []:
        count = int((df["kpi"] == kpi).sum())
        print(f"{kpi}\t{count}")


def build_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(
        description="Plot Prusa telemetry KPIs overlaid by layer phase.",
        formatter_class=argparse.ArgumentDefaultsHelpFormatter,
    )
    parser.add_argument("telemetry_file", type=Path, help="EDN telemetry file to read")
    parser.add_argument("--gcode", help="matching text G-code file; maps sdpos byte offsets to true layers")
    parser.add_argument("--layer-metric", help="telemetry KPI to use as the layer number")
    parser.add_argument("--output-dir", default="target/telemetry-layer-plots", help="directory for generated PNG pages")
    parser.add_argument("--gui", action="store_true", help="open a live Tk/Matplotlib GUI instead of writing PNG pages")
    parser.add_argument("--paged", action="store_true", help="use paged GUI layout instead of one wide scrollable grid")
    parser.add_argument("--wide-cols", type=int, default=16, help="columns in the default all-KPI GUI grid")
    parser.add_argument("--no-auto-refresh", action="store_true", help="disable GUI auto-refresh")
    parser.add_argument("--watch", action="store_true", help="poll the file and regenerate plots when it changes")
    parser.add_argument("--poll-seconds", type=float, default=2.0, help="watch-mode polling interval")
    parser.add_argument("--history-lines", type=int, help="only read the last N telemetry lines")
    parser.add_argument("--chart", choices=("periodic", "polar", "both"), default="both", help="chart type to render")
    parser.add_argument("--phase-bins", type=int, default=180, help="number of bins across each layer phase")
    parser.add_argument("--max-layers", type=int, default=16, help="most recent layers or segments to overlay")
    parser.add_argument("--rows", type=int, default=3, help="subplot rows per page")
    parser.add_argument("--cols", type=int, default=3, help="subplot columns per page")
    parser.add_argument("--include-kpi", help="regex of KPI names to include")
    parser.add_argument("--exclude-kpi", help="regex of KPI names to exclude")
    parser.add_argument(
        "--sdpos-reset-threshold",
        type=float,
        default=0.0,
        help="sdpos drop that starts a new fallback segment; 0 disables fallback splitting",
    )
    parser.add_argument("--list-kpis", action="store_true", help="list discovered KPIs and exit")
    return parser


def main(argv: list[str] | None = None) -> int:
    parser = build_parser()
    args = parser.parse_args(argv)
    args.telemetry_file = args.telemetry_file.expanduser().resolve()

    if not args.telemetry_file.exists():
        parser.error(f"telemetry file does not exist: {args.telemetry_file}")
    if args.gcode and not Path(args.gcode).expanduser().exists():
        parser.error(f"G-code file does not exist: {args.gcode}")
    if args.phase_bins <= 0:
        parser.error("--phase-bins must be positive")
    if args.rows <= 0 or args.cols <= 0:
        parser.error("--rows and --cols must be positive")
    if args.wide_cols <= 0:
        parser.error("--wide-cols must be positive")

    if args.list_kpis:
        print_kpis(args)
        return 0

    if args.gui:
        return run_gui(args)

    last_signature: tuple[int, int] | None = None
    first = True
    while True:
        stat = args.telemetry_file.stat()
        signature = (stat.st_size, stat.st_mtime_ns)
        if first or signature != last_signature:
            try:
                outputs, meta = render_once(args)
            except Exception as exc:
                print(f"plot failed: {exc}", file=sys.stderr)
                if not args.watch:
                    return 1
            else:
                layers = meta["layers"]
                if len(layers) > 8:
                    layer_text = f"{layers[0]}..{layers[-1]} ({len(layers)} total)"
                else:
                    layer_text = ", ".join(str(layer) for layer in layers)
                print(
                    "Rendered "
                    f"{meta['outputs']} PNG page(s), {meta['kpis']} KPI(s), "
                    f"{meta['phase_label']}s {layer_text} from "
                    f"{meta['packets']} packet(s) -> {Path(args.output_dir).resolve()}"
                )
                if outputs:
                    print("First page:", outputs[0])
            last_signature = signature
            first = False

        if not args.watch:
            return 0
        time.sleep(args.poll_seconds)


if __name__ == "__main__":
    raise SystemExit(main())
