"""Runs Vigil's existing rule-based predictors (backend/functions-predict/
predictors.py - already pure/dependency-free per its own docstring) against
offline windows, so a fine-tuned Chronos model has something concrete and
already-in-production to beat, not just an abstract RMSE target. The
literature itself (see ../README.md) found zero-shot foundation models don't
reliably beat a decent existing baseline - skipping this comparison would
make any "the new model is better" claim unverifiable.

Loaded by file path via importlib rather than a package import - predictors.py
lives in a separate Python project (functions-predict/) with its own
venv/requirements, and reaching across that boundary by path avoids needing
this package installed there or vice versa.
"""
from __future__ import annotations

import importlib.util
from pathlib import Path

import numpy as np

# ISF 40 mg/dL/unit, carb ratio 10g/unit - matches index.ts's DEFAULT_PHYSIOLOGY.
# Real per-subject physiology isn't in exportTrainingData.ts's CSV yet (see
# loaders/vigil_export.py's docstring) or in most public datasets' documented
# schema - this is a known placeholder, not a per-subject fitted value.
DEFAULT_PHYSIOLOGY = {"insulinSensitivityFactor": 40.0, "carbRatio": 10.0}

_PREDICTORS_PATH = Path(__file__).resolve().parents[2] / "backend" / "functions-predict" / "predictors.py"


def _load_predictors_module():
    spec = importlib.util.spec_from_file_location("vigil_predictors", _PREDICTORS_PATH)
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


def _window_to_readings(window: dict) -> list[dict]:
    """predictors.py expects dicts with sgv/direction/dateMs/iob/iobUnreliable/
    cob (see its module docstring) - direction isn't used by any predictor
    this evaluates against, so it's a constant placeholder, not derived."""
    return [
        {
            "sgv": float(sgv),
            "direction": "NOT COMPUTABLE",
            "dateMs": int(ts),
            "iob": None if np.isnan(iob) else float(iob),
            "iobUnreliable": False,
            "cob": None if np.isnan(carbs) else float(carbs),
        }
        for sgv, ts, iob, carbs in zip(
            window["context_sgv"], window["context_timestamps_ms"], window["context_iob"], window["context_carbs"]
        )
    ]


def run_baselines(
    windows: list[dict], horizon_minutes: float, physiology: dict = DEFAULT_PHYSIOLOGY
) -> dict[str, np.ndarray]:
    """Returns {predictor_key: array of projected values}, one per window, in
    the same order as `windows` - align against [w["target_sgv"][-1] for w in
    windows] (the value `horizon_minutes` ahead) for evaluate.py."""
    predictors_module = _load_predictors_module()
    results: dict[str, list[float]] = {p["key"]: [] for p in predictors_module.PREDICTORS}

    for window in windows:
        readings = _window_to_readings(window)
        for predictor in predictors_module.PREDICTORS:
            projected, _note = predictor["predict"](readings, physiology, horizon_minutes, readings)
            results[predictor["key"]].append(projected)

    return {key: np.array(values) for key, values in results.items()}
