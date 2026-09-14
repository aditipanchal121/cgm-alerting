"""Canonical schema -> model-ready data: unified sampling grid, gap-aware
windowing, and Chronos's GluonTS/Arrow training format.

Gaps are never interpolated across more than one missed sample - a real
sensor dropout (compression low, warm-up, disconnect) is a discontinuity in
the physiology being measured, not a modeling nuisance to paper over. A
window is only ever built from a contiguous run.
"""
from __future__ import annotations

from pathlib import Path

import numpy as np
import pandas as pd

from .schema import NATIVE_SAMPLING_MINUTES, validate_canonical

# Beyond this many missed samples in a row, the run is split rather than
# interpolated - one missed 5-min sample (a single skipped poll) is filled
# linearly since that's almost certainly transport, not physiology; more
# than that and treating it as continuous risks inventing a trend that
# never happened.
MAX_INTERPOLATED_GAP_STEPS = 1


def _fill_short_gaps(series: pd.Series, max_gap_steps: int) -> pd.Series:
    """pandas' own interpolate(limit=N) fills the N NaNs nearest each valid
    edge of a run, however long the run is - verified directly, it does NOT
    leave a too-long run untouched, which is what MAX_INTERPOLATED_GAP_STEPS
    is supposed to guarantee. This instead measures each run's full length
    first, and only fills runs at or under the threshold, in full."""
    is_na = series.isna()
    run_id = (~is_na).cumsum()
    run_lengths = is_na.groupby(run_id).transform("sum")
    fillable = is_na & (run_lengths <= max_gap_steps)
    filled = series.interpolate(method="index", limit_area="inside")
    return series.where(~fillable, filled)


def resample_to_grid(df: pd.DataFrame, grid_minutes: int = 5) -> pd.DataFrame:
    """Reindexes each subject onto a fixed grid_minutes grid. A source
    natively sampled coarser than grid_minutes (Shanghai's 15-min) is left
    at its native rate rather than fabricated finer via interpolation -
    upsampling a 15-min series to a 5-min grid would invent two out of
    every three points."""
    validate_canonical(df)
    parts = []
    for (subject_id, source), group in df.groupby(["subject_id", "source"], sort=False):
        native_minutes = NATIVE_SAMPLING_MINUTES[source]
        step_minutes = max(grid_minutes, native_minutes)
        group = group.sort_values("timestamp_ms")
        start, end = group["timestamp_ms"].iloc[0], group["timestamp_ms"].iloc[-1]
        grid = np.arange(start, end + 1, step_minutes * 60_000)

        indexed = group.set_index("timestamp_ms")
        reindexed = indexed.reindex(indexed.index.union(grid)).sort_index()
        for col in ("sgv_mgdl", "iob_units", "carbs_g"):
            reindexed[col] = _fill_short_gaps(reindexed[col], MAX_INTERPOLATED_GAP_STEPS)
        reindexed["subject_id"] = subject_id
        reindexed["source"] = source
        parts.append(reindexed.loc[grid].reset_index(names="timestamp_ms"))

    result = pd.concat(parts, ignore_index=True) if parts else df.iloc[0:0].copy()
    return result


def split_into_runs(df: pd.DataFrame, grid_minutes: int = 5) -> list[pd.DataFrame]:
    """Splits each subject's series at any sgv_mgdl gap (a spot
    resample_to_grid left un-interpolated), returning only contiguous runs -
    the unit windowing and Arrow export both operate on."""
    runs = []
    for _, group in df.groupby(["subject_id", "source"], sort=False):
        group = group.sort_values("timestamp_ms")
        is_gap = group["sgv_mgdl"].isna()
        run_id = is_gap.cumsum()
        for _, run in group[~is_gap].groupby(run_id):
            if len(run) > 0:
                runs.append(run.reset_index(drop=True))
    return runs


def make_windows(
    runs: list[pd.DataFrame], context_length: int, horizon: int
) -> list[dict]:
    """One sliding window per valid (context, target) pair per run -
    context_length points of history, horizon points to predict, stride 1.
    Returned as plain dicts (subject_id, source, context sgv/iob/carbs
    arrays, target sgv array, target timestamps) rather than a DataFrame -
    each window is a fixed-size training example, not a row of a table."""
    windows = []
    for run in runs:
        n = len(run)
        for i in range(n - context_length - horizon + 1):
            ctx = run.iloc[i : i + context_length]
            tgt = run.iloc[i + context_length : i + context_length + horizon]
            windows.append(
                {
                    "subject_id": run["subject_id"].iloc[0],
                    "source": run["source"].iloc[0],
                    "context_sgv": ctx["sgv_mgdl"].to_numpy(),
                    "context_iob": ctx["iob_units"].to_numpy(),
                    "context_carbs": ctx["carbs_g"].to_numpy(),
                    "context_timestamps_ms": ctx["timestamp_ms"].to_numpy(),
                    "target_sgv": tgt["sgv_mgdl"].to_numpy(),
                    "target_timestamps_ms": tgt["timestamp_ms"].to_numpy(),
                }
            )
    return windows


def write_chronos_arrow(runs: list[pd.DataFrame], output_path: str | Path, grid_minutes: int = 5) -> None:
    """Writes runs as GluonTS-style Arrow (one row per run: start timestamp +
    target array) - the format amazon-science/chronos-forecasting's
    training/train.py expects for `training_data_paths`. Implemented
    directly against pyarrow rather than importing chronos-forecasting's own
    convert_to_arrow helper, so this module only needs pandas/numpy/pyarrow -
    torch and the chronos package itself are only needed at actual
    fine-tuning time (see fine_tune.py), not for data prep."""
    import pyarrow as pa
    from pyarrow import parquet as pq

    if not runs:
        raise ValueError("no runs to write - check split_into_runs output first")

    starts = [pd.Timestamp(int(r["timestamp_ms"].iloc[0]), unit="ms", tz="UTC") for r in runs]
    targets = [r["sgv_mgdl"].to_numpy(dtype="float32") for r in runs]
    table = pa.table(
        {
            "start": pa.array(starts, type=pa.timestamp("ms", tz="UTC")),
            "target": pa.array(targets, type=pa.list_(pa.float32())),
            "item_id": pa.array([r["subject_id"].iloc[0] for r in runs]),
        }
    )
    pq.write_table(table, output_path)
