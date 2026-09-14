import numpy as np

from src.baselines import run_baselines

EXPECTED_KEYS = {"linear", "iobAware", "directionAware", "kalman", "multiBolus"}


def _synthetic_window():
    n = 8
    return {
        "subject_id": "brother",
        "source": "vigil",
        "context_sgv": np.linspace(160.0, 130.0, n),  # steady decline
        "context_iob": np.linspace(3.0, 2.0, n),
        "context_carbs": np.full(n, np.nan),
        "context_timestamps_ms": (np.arange(n) * 5 * 60_000).astype("int64"),
        "target_sgv": np.array([125.0, 120.0]),
        "target_timestamps_ms": np.array([(n + 0) * 5 * 60_000, (n + 1) * 5 * 60_000]),
    }


def test_run_baselines_returns_all_predictor_keys():
    windows = [_synthetic_window()]
    results = run_baselines(windows, horizon_minutes=30.0)

    assert set(results.keys()) == EXPECTED_KEYS
    for key, values in results.items():
        assert values.shape == (1,)
        assert 40.0 <= values[0] <= 400.0, f"{key} produced an out-of-clamp-range value: {values[0]}"


def test_run_baselines_linear_extrapolates_the_decline():
    windows = [_synthetic_window()]
    results = run_baselines(windows, horizon_minutes=30.0)
    # Context declines the whole way (160 -> 130 over 8 points); a linear
    # projection 30 min further out should continue below the last context value.
    assert results["linear"][0] < 130.0
