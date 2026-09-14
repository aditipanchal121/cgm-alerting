"""Canonical row schema every dataset loader converts into, so preprocessing,
fine-tuning, and evaluation never need to know which source a row came from.

One row = one CGM sample. Wide (not long/tidy) on purpose - iob/carbs live as
columns alongside sgv, not as separate rows, since Chronos-2/ChronosX-style
covariate injection and the existing rule-based baselines (see baselines.py)
both want per-timestamp feature vectors, not an event log to re-align.
"""
from __future__ import annotations

import pandas as pd

CANONICAL_COLUMNS = {
    "subject_id": "string",  # stable per-person id, unique only *within* a source
    "timestamp_ms": "int64",  # epoch ms, UTC
    "sgv_mgdl": "float64",
    "iob_units": "float64",  # NaN where the source has no insulin data at all
    "carbs_g": "float64",  # NaN where the source has no carb data at all
    "source": "string",  # e.g. "diatrend", "ohio_t1dm", "vigil"
}

# 15-min-sampled sources (ShanghaiT1DM/T2DM) mixed in with 5-min sources
# without resampling would teach the model two different step sizes for the
# same physiological process - see preprocessing.resample_to_grid.
NATIVE_SAMPLING_MINUTES = {
    "diatrend": 5,
    "ohio_t1dm": 5,
    "replacebg": 5,
    "shanghai_t1dm": 15,
    "shanghai_t2dm": 15,
    "big_ideas_lab": 5,
    "vigil": 5,
}


def validate_canonical(df: pd.DataFrame) -> None:
    """Raises if `df` doesn't match CANONICAL_COLUMNS - call this at the end
    of every loader and the start of every preprocessing step, so a schema
    drift fails loudly at the boundary instead of silently downstream.
    Uses pandas' is_*_dtype checks rather than comparing dtype name strings -
    pandas 3.0's default string dtype reprs as "str", not "object", so a
    literal string comparison silently breaks across pandas versions."""
    missing = set(CANONICAL_COLUMNS) - set(df.columns)
    if missing:
        raise ValueError(f"missing canonical column(s): {sorted(missing)}")
    for col, expected_dtype in CANONICAL_COLUMNS.items():
        series = df[col]
        if expected_dtype == "float64" and not pd.api.types.is_float_dtype(series):
            raise ValueError(f"column '{col}' expected float64, got {series.dtype}")
        if expected_dtype == "int64" and not pd.api.types.is_integer_dtype(series):
            raise ValueError(f"column '{col}' expected int64, got {series.dtype}")
        if expected_dtype == "string" and not pd.api.types.is_string_dtype(series):
            raise ValueError(f"column '{col}' expected string-like, got {series.dtype}")
    if df["sgv_mgdl"].isna().any():
        raise ValueError("sgv_mgdl must never be NaN - a gap is a missing row, not a NaN sgv")
    if not df["source"].isin(NATIVE_SAMPLING_MINUTES).all():
        unknown = sorted(set(df["source"].unique()) - set(NATIVE_SAMPLING_MINUTES))
        raise ValueError(f"unregistered source(s) - add to NATIVE_SAMPLING_MINUTES: {unknown}")


def empty_canonical() -> pd.DataFrame:
    return pd.DataFrame({col: pd.Series(dtype=dtype) for col, dtype in CANONICAL_COLUMNS.items()})
