import numpy as np

from src.evaluate import clarke_zone, mard, rmse, summarize


def test_rmse_zero_for_perfect_prediction():
    assert rmse(np.array([100.0, 120.0]), np.array([100.0, 120.0])) == 0.0


def test_rmse_known_value():
    assert rmse(np.array([100.0]), np.array([110.0])) == 10.0


def test_mard_known_value():
    assert mard(np.array([100.0]), np.array([110.0])) == 10.0


def test_clarke_zone_a_within_20_percent():
    assert clarke_zone(100, 100) == "A"


def test_clarke_zone_a_both_hypo():
    assert clarke_zone(50, 50) == "A"


def test_clarke_zone_c_overcorrection():
    # ref=100 (in 70-290), pred=250 >= ref+110=210
    assert clarke_zone(100, 250) == "C"


def test_clarke_zone_e_opposite_extremes():
    assert clarke_zone(250, 50) == "E"


def test_clarke_zone_b_benign_disagreement():
    assert clarke_zone(90, 140) == "B"


def test_summarize_shape():
    actual = np.array([100.0, 150.0, 60.0])
    predicted = np.array([105.0, 140.0, 65.0])
    result = summarize(actual, predicted)
    assert result["n"] == 3
    assert set(result["clarke_zones_pct"]) == set("ABCDE")
    assert abs(sum(result["clarke_zones_pct"].values()) - 100.0) < 1e-9
