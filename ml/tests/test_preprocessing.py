import numpy as np
import pandas as pd

from src.preprocessing import make_windows, resample_to_grid, split_into_runs
from src.schema import empty_canonical


def _minutes(*mins: int) -> np.ndarray:
    return (np.array(mins) * 60_000).astype("int64")


def test_resample_fills_single_step_gap_but_not_long_gap():
    # 0,5,15,20,25 present (10 missing - a single 5-min step), then a jump to
    # 60 (30,35,40,45,50,55 missing - six steps, must NOT be filled).
    df = pd.DataFrame(
        {
            "subject_id": ["p1"] * 6,
            "timestamp_ms": _minutes(0, 5, 15, 20, 25, 60),
            "sgv_mgdl": [100.0, 105.0, 115.0, 120.0, 125.0, 180.0],
            "iob_units": [1.0, 1.0, 1.0, 1.0, 1.0, 1.0],
            "carbs_g": [0.0, 0.0, 0.0, 0.0, 0.0, 0.0],
            "source": ["vigil"] * 6,
        }
    )
    result = resample_to_grid(df, grid_minutes=5)

    at_10 = result.loc[result["timestamp_ms"] == _minutes(10)[0], "sgv_mgdl"].iloc[0]
    assert at_10 == 110.0  # single-step gap, linearly interpolated

    for m in (30, 35, 40, 45, 50, 55):
        val = result.loc[result["timestamp_ms"] == _minutes(m)[0], "sgv_mgdl"].iloc[0]
        assert pd.isna(val), f"minute {m} should remain a real gap, not be fabricated"


def test_split_into_runs_breaks_at_gap():
    df = pd.DataFrame(
        {
            "subject_id": ["p1"] * 8,
            "timestamp_ms": _minutes(0, 5, 10, 15, 20, 25, 30, 60),
            "sgv_mgdl": [100.0, 105.0, 110.0, 115.0, 120.0, 125.0, np.nan, 180.0],
            "iob_units": [1.0] * 8,
            "carbs_g": [0.0] * 8,
            "source": ["vigil"] * 8,
        }
    )
    runs = split_into_runs(df)
    assert len(runs) == 2
    assert len(runs[0]) == 6
    assert len(runs[1]) == 1


def test_make_windows_counts_and_alignment():
    run = pd.DataFrame(
        {
            "subject_id": ["p1"] * 10,
            "timestamp_ms": _minutes(*range(0, 50, 5)),
            "sgv_mgdl": np.arange(100.0, 110.0),
            "iob_units": [1.0] * 10,
            "carbs_g": [0.0] * 10,
            "source": ["vigil"] * 10,
        }
    )
    windows = make_windows([run], context_length=6, horizon=2)
    # 10 points, context 6 + horizon 2 = 8, so valid start offsets are 0,1,2 -> 3 windows
    assert len(windows) == 3
    first = windows[0]
    assert len(first["context_sgv"]) == 6
    assert len(first["target_sgv"]) == 2
    assert first["context_sgv"][0] == 100.0
    assert first["target_sgv"][-1] == 107.0  # index 6+2-1=7 -> 100+7
