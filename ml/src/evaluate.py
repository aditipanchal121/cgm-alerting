"""Evaluation metrics: RMSE, MARD, and Clarke Error Grid zone classification -
the three numbers the CGM-forecasting literature reports results in (see
../README.md's citations). RMSE alone is not the bar this project should be
held to; >90% of predictions in Clarke zones A+B combined is the
conventional clinical-acceptability threshold, and that's the number to lead
with once real evaluation runs.

Clarke zone boundaries per Clarke WL et al., "Evaluating Clinical Accuracy
of Systems for Self-Monitoring of Blood Glucose," Diabetes Care 1987,
transcribed from the widely-used open reference implementation at
https://github.com/suetAndTie/ClarkeErrorGrid (verified against that source
directly, not from memory).
"""
from __future__ import annotations

import numpy as np


def rmse(actual_mgdl: np.ndarray, predicted_mgdl: np.ndarray) -> float:
    return float(np.sqrt(np.mean((actual_mgdl - predicted_mgdl) ** 2)))


def mard(actual_mgdl: np.ndarray, predicted_mgdl: np.ndarray) -> float:
    """Mean Absolute Relative Difference, as a percentage - the metric CGM
    sensor manufacturers themselves report accuracy in (e.g. Dexcom G6's
    ~9% MARD), so this is the number to compare a forecast's error against
    the sensor's own noise floor with."""
    return float(np.mean(np.abs(actual_mgdl - predicted_mgdl) / actual_mgdl) * 100.0)


def clarke_zone(ref_mgdl: float, pred_mgdl: float) -> str:
    """Classifies one (reference, predicted) pair. Order matters - E and C
    are checked before the A/B distinction they'd otherwise be swallowed by,
    matching the reference implementation's own precedence."""
    if (ref_mgdl >= 180 and pred_mgdl <= 70) or (ref_mgdl <= 70 and pred_mgdl >= 180):
        return "E"
    if (ref_mgdl >= 70 and ref_mgdl <= 290 and pred_mgdl >= ref_mgdl + 110) or (
        ref_mgdl >= 130 and ref_mgdl <= 180 and pred_mgdl <= (7 / 5) * ref_mgdl - 182
    ):
        return "C"
    if (
        (ref_mgdl >= 240 and 70 <= pred_mgdl <= 180)
        or (ref_mgdl <= 175 / 3 and 70 <= pred_mgdl <= 180)
        or (175 / 3 <= ref_mgdl <= 70 and pred_mgdl >= (6 / 5) * ref_mgdl)
    ):
        return "D"
    if (ref_mgdl <= 70 and pred_mgdl <= 70) or (0.8 * ref_mgdl <= pred_mgdl <= 1.2 * ref_mgdl):
        return "A"
    return "B"


def clarke_zone_distribution(actual_mgdl: np.ndarray, predicted_mgdl: np.ndarray) -> dict[str, float]:
    """Returns each zone's share of all pairs, as percentages summing to
    ~100. `zones["A"] + zones["B"]` is the number to report as the headline
    clinical-acceptability figure (>90% is the conventional bar - see this
    module's docstring)."""
    zones = [clarke_zone(r, p) for r, p in zip(actual_mgdl, predicted_mgdl)]
    n = len(zones)
    return {z: 100.0 * zones.count(z) / n for z in "ABCDE"} if n else {z: 0.0 for z in "ABCDE"}


def summarize(actual_mgdl: np.ndarray, predicted_mgdl: np.ndarray) -> dict:
    zones = clarke_zone_distribution(actual_mgdl, predicted_mgdl)
    return {
        "n": len(actual_mgdl),
        "rmse_mgdl": rmse(actual_mgdl, predicted_mgdl),
        "mard_pct": mard(actual_mgdl, predicted_mgdl),
        "clarke_zones_pct": zones,
        "clarke_ab_pct": zones["A"] + zones["B"],
    }
