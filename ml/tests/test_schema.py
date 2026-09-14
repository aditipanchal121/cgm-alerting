import pandas as pd
import pytest

from src.schema import empty_canonical, validate_canonical


def test_empty_canonical_is_valid():
    validate_canonical(empty_canonical())


def test_missing_column_rejected():
    df = empty_canonical().drop(columns=["iob_units"])
    with pytest.raises(ValueError, match="missing canonical column"):
        validate_canonical(df)


def test_nan_sgv_rejected():
    df = pd.DataFrame(
        {
            "subject_id": ["a"],
            "timestamp_ms": pd.array([0], dtype="int64"),
            "sgv_mgdl": [float("nan")],
            "iob_units": [1.0],
            "carbs_g": [0.0],
            "source": ["vigil"],
        }
    )
    with pytest.raises(ValueError, match="sgv_mgdl must never be NaN"):
        validate_canonical(df)


def test_unregistered_source_rejected():
    df = pd.DataFrame(
        {
            "subject_id": ["a"],
            "timestamp_ms": pd.array([0], dtype="int64"),
            "sgv_mgdl": [120.0],
            "iob_units": [1.0],
            "carbs_g": [0.0],
            "source": ["made_up_source"],
        }
    )
    with pytest.raises(ValueError, match="unregistered source"):
        validate_canonical(df)
