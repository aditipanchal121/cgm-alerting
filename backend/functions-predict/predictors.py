 """Experimental glucose predictors - the single source of truth for this
math. Ported faithfully from the Android app's original GlucosePredictor.kt
(now removed - see android-app/README.md). Pure functions only: no
Firestore, no Firebase, no I/O of any kind, so this is importable and
testable with nothing but plain dicts/lists as input.

Every predictor has the same shape:
    predict(readings, physiology, treatments, horizon_minutes) -> (projected_value, note)

readings:    list of {"sgv": float, "direction": str, "dateMs": int,
                       "iob": float | None, "iobUnreliable": bool}
physiology:  {"insulinSensitivityFactor": float, "carbRatio": float}
treatments:  list of {"mills": int, "insulin": float | None,
                       "carbs": float | None, "durationMinutes": float | None}
"""

import math

MIN_DISPLAYABLE_MGDL = 40.0
MAX_DISPLAYABLE_MGDL = 400.0


def _clamp(value: float) -> float:
    return min(MAX_DISPLAYABLE_MGDL, max(MIN_DISPLAYABLE_MGDL, value))


def _sorted_by_time(readings: list[dict]) -> list[dict]:
    return sorted(readings, key=lambda r: r["dateMs"])


def _linear_projection(sorted_r: list[dict], horizon_minutes: float) -> float | None:
    if len(sorted_r) < 2:
        return sorted_r[0]["sgv"] if sorted_r else 0.0
    first, last = sorted_r[0], sorted_r[-1]
    minutes_elapsed = (last["dateMs"] - first["dateMs"]) / 60_000.0
    if minutes_elapsed <= 0:
        return float(last["sgv"])
    rate_per_minute = (last["sgv"] - first["sgv"]) / minutes_elapsed
    return _clamp(last["sgv"] + rate_per_minute * horizon_minutes)


def predict_linear(readings, physiology, treatments, horizon_minutes):
    return _linear_projection(_sorted_by_time(readings), horizon_minutes), None


def predict_iob_aware(readings, physiology, treatments, horizon_minutes):
    sorted_r = _sorted_by_time(readings)
    if len(sorted_r) < 2:
        return (sorted_r[0]["sgv"] if sorted_r else 0.0), None

    first, last = sorted_r[0], sorted_r[-1]
    minutes_elapsed = (last["dateMs"] - first["dateMs"]) / 60_000.0
    if minutes_elapsed <= 0:
        return float(last["sgv"]), None

    observed_rate = (last["sgv"] - first["sgv"]) / minutes_elapsed
    iob = last.get("iob")
    isf = physiology.get("insulinSensitivityFactor", 0.0)
    can_scale = observed_rate < 0 and iob is not None and not last.get("iobUnreliable") and isf > 0

    effective_rate = observed_rate
    if can_scale:
        distance_above_floor = max(last["sgv"] - MIN_DISPLAYABLE_MGDL, 1.0)
        iob_factor = (iob * isf) / distance_above_floor
        effective_rate = observed_rate * iob_factor

    return _clamp(last["sgv"] + effective_rate * horizon_minutes), None


SINGLE_ARROW_WINDOW_MINUTES = 20
DOUBLE_ARROW_WINDOW_MINUTES = 15
SINGLE_ARROW_DIRECTIONS = {"SingleUp", "SingleDown"}
DOUBLE_ARROW_DIRECTIONS = {"DoubleUp", "DoubleDown"}


def _windowed_readings(sorted_r: list[dict], window_minutes: float | None) -> list[dict]:
    if window_minutes is None:
        return sorted_r
    cutoff_ms = sorted_r[-1]["dateMs"] - window_minutes * 60_000
    windowed = [r for r in sorted_r if r["dateMs"] >= cutoff_ms]
    return windowed if len(windowed) >= 2 else sorted_r


def predict_direction_aware(readings, physiology, treatments, horizon_minutes):
    sorted_r = _sorted_by_time(readings)
    if len(sorted_r) < 2:
        return (sorted_r[0]["sgv"] if sorted_r else 0.0), None

    direction = sorted_r[-1]["direction"]
    if direction in SINGLE_ARROW_DIRECTIONS:
        window_minutes = SINGLE_ARROW_WINDOW_MINUTES
    elif direction in DOUBLE_ARROW_DIRECTIONS:
        window_minutes = DOUBLE_ARROW_WINDOW_MINUTES
    else:
        window_minutes = None

    return _linear_projection(_windowed_readings(sorted_r, window_minutes), horizon_minutes), None


# Same constants/derivation as the original KalmanFilterPredictor - see
# Welch & Bishop, "An Introduction to the Kalman Filter" (UNC TR 95-041),
# and Facchinetti et al. 2013, Diabetes Care, on Kalman filtering for CGM
# signal smoothing specifically.
KALMAN_MEASUREMENT_VARIANCE = 100.0
KALMAN_INITIAL_VELOCITY_VARIANCE = 4.0
KALMAN_PROCESS_VARIANCE_POSITION = 0.25
KALMAN_PROCESS_VARIANCE_VELOCITY = 0.01


def predict_kalman(readings, physiology, treatments, horizon_minutes):
    sorted_r = _sorted_by_time(readings)
    if len(sorted_r) < 2:
        return (sorted_r[0]["sgv"] if sorted_r else 0.0), None

    glucose = float(sorted_r[0]["sgv"])
    velocity = 0.0
    p_gg = KALMAN_MEASUREMENT_VARIANCE
    p_gv = 0.0
    p_vv = KALMAN_INITIAL_VELOCITY_VARIANCE

    previous_ms = sorted_r[0]["dateMs"]
    for reading in sorted_r[1:]:
        dt = (reading["dateMs"] - previous_ms) / 60_000.0
        previous_ms = reading["dateMs"]
        if dt <= 0:
            continue

        glucose += velocity * dt
        propagated_p_gv = p_gv + dt * p_vv
        p_gg += dt * (p_gv + propagated_p_gv) + KALMAN_PROCESS_VARIANCE_POSITION * dt
        p_gv = propagated_p_gv
        p_vv += KALMAN_PROCESS_VARIANCE_VELOCITY * dt

        innovation = reading["sgv"] - glucose
        innovation_variance = p_gg + KALMAN_MEASUREMENT_VARIANCE
        kalman_gain_g = p_gg / innovation_variance
        kalman_gain_v = p_gv / innovation_variance

        glucose += kalman_gain_g * innovation
        velocity += kalman_gain_v * innovation

        updated_p_gg = (1 - kalman_gain_g) * p_gg
        updated_p_gv = (1 - kalman_gain_g) * p_gv
        p_vv -= kalman_gain_v * p_gv
        p_gg = updated_p_gg
        p_gv = updated_p_gv

    return _clamp(glucose + velocity * horizon_minutes), None


# Same constants/curve as the original MultiBolusInsulinActivityPredictor -
# the exponential insulin-action model documented at
# https://loopkit.github.io/loopdocs/operation/algorithm/prediction/.
INSULIN_DURATION_MINUTES = 240.0
INSULIN_PEAK_MINUTES = 75.0
INSULIN_DURATION_MS = INSULIN_DURATION_MINUTES * 60_000
CARB_DEFAULT_DURATION_MINUTES = 180.0
CARB_PEAK_RATIO = INSULIN_PEAK_MINUTES / INSULIN_DURATION_MINUTES


def _activity_remaining_fraction(minutes_since_dose: float, duration_minutes: float, peak_minutes: float) -> float:
    if minutes_since_dose <= 0:
        return 1.0
    if minutes_since_dose >= duration_minutes:
        return 0.0

    tau = (peak_minutes * (1 - peak_minutes / duration_minutes)) / (1 - (2 * peak_minutes) / duration_minutes)
    a = (2 * tau) / duration_minutes
    s = 1 / (1 - a + (1 + a) * math.exp(-duration_minutes / tau))
    t = minutes_since_dose

    return 1 - s * (1 - a) * (
        (t**2 / (tau * duration_minutes * (1 - a)) - t / tau - 1) * math.exp(-t / tau) + 1
    )


def _is_active_insulin(treatment: dict, last_ms: int) -> bool:
    dose = treatment.get("insulin")
    if dose is None or dose <= 0:
        return False
    elapsed_ms = last_ms - treatment["mills"]
    return 0 <= elapsed_ms < INSULIN_DURATION_MS


def _carb_duration_minutes(treatment: dict) -> float:
    duration = treatment.get("durationMinutes")
    return duration if duration and duration > 0 else CARB_DEFAULT_DURATION_MINUTES


def _is_active_carb(treatment: dict, last_ms: int) -> bool:
    carbs = treatment.get("carbs")
    if carbs is None or carbs <= 0:
        return False
    elapsed_minutes = (last_ms - treatment["mills"]) / 60_000.0
    return 0 <= elapsed_minutes <= _carb_duration_minutes(treatment)


def predict_multi_bolus(readings, physiology, treatments, horizon_minutes):
    if not readings:
        return None, "No readings yet."
    last = max(readings, key=lambda r: r["dateMs"])

    isf = physiology.get("insulinSensitivityFactor", 0.0)
    if isf <= 0:
        return float(last["sgv"]), "No insulin sensitivity factor set."

    active_boluses = [t for t in treatments if _is_active_insulin(t, last["dateMs"])]
    active_carbs = [t for t in treatments if _is_active_carb(t, last["dateMs"])]
    if not active_boluses and not active_carbs:
        return float(last["sgv"]), "No active insulin or carbs."

    total_insulin_drop = 0.0
    for bolus in active_boluses:
        minutes_since_dose = (last["dateMs"] - bolus["mills"]) / 60_000.0
        fraction_used = _activity_remaining_fraction(
            minutes_since_dose, INSULIN_DURATION_MINUTES, INSULIN_PEAK_MINUTES
        ) - _activity_remaining_fraction(
            minutes_since_dose + horizon_minutes, INSULIN_DURATION_MINUTES, INSULIN_PEAK_MINUTES
        )
        total_insulin_drop += bolus["insulin"] * fraction_used * isf

    carb_ratio = physiology.get("carbRatio", 0.0)
    carb_sensitivity_factor = (isf / carb_ratio) if carb_ratio > 0 else 0.0
    total_carb_rise = 0.0
    for carb in active_carbs:
        minutes_since_dose = (last["dateMs"] - carb["mills"]) / 60_000.0
        duration_minutes = _carb_duration_minutes(carb)
        peak_minutes = duration_minutes * CARB_PEAK_RATIO
        fraction_absorbed = _activity_remaining_fraction(
            minutes_since_dose, duration_minutes, peak_minutes
        ) - _activity_remaining_fraction(minutes_since_dose + horizon_minutes, duration_minutes, peak_minutes)
        total_carb_rise += carb["carbs"] * fraction_absorbed * carb_sensitivity_factor

    net_drop = total_insulin_drop - total_carb_rise
    return _clamp(last["sgv"] - net_drop), None


PREDICTORS = [
    {
        "key": "linear",
        "name": "Linear regression",
        "description": (
            "Draws a straight line between your oldest and newest readings in the "
            "window and extends it forward. Simple and predictable, but assumes "
            "the current rate of change continues exactly as-is for the full "
            "30 minutes."
        ),
        "sourceUrl": None,
        "predict": predict_linear,
    },
    {
        "key": "iobAware",
        "name": "IOB-aware trend",
        "description": (
            "Same straight-line trend as Linear regression, but scales a falling "
            "projection by how much theoretical glucose-lowering effect "
            "remains in the current IOB (IOB x insulin sensitivity factor) "
            "relative to how far above a physiological floor the current "
            "reading is - can project a steeper fall than plain linear when "
            "that ratio is high, not just a gentler one. Falls back to the "
            "plain trend if IOB is missing or flagged unreliable."
        ),
        "sourceUrl": None,
        "predict": predict_iob_aware,
    },
    {
        "key": "directionAware",
        "name": "Direction-aware (windowed)",
        "description": (
            "Estimates the current rate from a window sized to the arrow's "
            "urgency, then extrapolates linearly - the full window for flat "
            "or diagonal trends, about 20 minutes for a single arrow, and "
            "15 minutes (matching the Loop automated insulin delivery "
            "algorithm's own rate calculation) for a double arrow, so a "
            "fast, recent change isn't averaged down by calmer data from "
            "earlier in the window."
        ),
        "sourceUrl": None,
        "predict": predict_direction_aware,
    },
    {
        "key": "kalman",
        "name": "Kalman filter",
        "description": (
            "Recursively estimates a smoothed rate of change from the noisy CGM "
            "readings, discounting ones its own uncertainty says are likely "
            "just sensor noise, then extrapolates that single rate forward. "
            "The standard technique for this exact problem (see the model "
            "details link below); also see Facchinetti et al. 2013, Diabetes "
            "Care, on Kalman filtering for CGM signal smoothing specifically."
        ),
        "sourceUrl": "https://www.cs.utexas.edu/~pstone/Courses/393Rfall15/readings/Welch+Bishop-TR-95.pdf",
        "predict": predict_kalman,
    },
    {
        "key": "multiBolus",
        "name": "Multi-bolus insulin activity",
        "description": (
            "Reads actual bolus and carb history from Nightscout and evaluates a "
            "standard exponential activity curve at the real elapsed time "
            "since each one (see the model details link below), summing each "
            "bolus's glucose-lowering effect and each carb entry's glucose-"
            "raising effect over the next 30 minutes - overlapping treatments "
            "are assumed additive. Carb effect is derived from your insulin "
            "sensitivity factor and carb ratio, since there's no independently "
            "measured carb sensitivity factor."
        ),
        "sourceUrl": "https://loopkit.github.io/loopdocs/operation/algorithm/prediction/",
        "predict": predict_multi_bolus,
    },
]
