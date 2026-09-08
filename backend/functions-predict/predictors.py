"""Experimental glucose predictors - the single source of truth for this
math. Ported faithfully from the Android app's original GlucosePredictor.kt
(now removed - see android-app/README.md). Pure functions only: no
Firestore, no Firebase, no I/O of any kind, so this is importable and
testable with nothing but plain dicts/lists as input.

Every predictor has the same shape:
    predict(readings, physiology, horizon_minutes, iob_cob_history)
        -> (projected_value, note)

readings:        list of {"sgv": float, "direction": str, "dateMs": int,
                           "iob": float | None, "iobUnreliable": bool,
                           "cob": float | None} - this cycle's live CGM
                 fetch; only the newest entry typically carries a real
                 iob/cob (see fetchRecentReadings in nightscout.ts).
physiology:      {"insulinSensitivityFactor": float, "carbRatio": float}
iob_cob_history: same shape as `readings`, but a wider window of the same
                 devicestatus feed (see fetchRecentReadings), with real
                 iob/cob at each entry's own timestamp. Only
                 predict_multi_bolus uses this.
"""

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


def predict_linear(readings, physiology, horizon_minutes, iob_cob_history):
    return _linear_projection(_sorted_by_time(readings), horizon_minutes), None


def predict_iob_aware(readings, physiology, horizon_minutes, iob_cob_history):
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


def predict_direction_aware(readings, physiology, horizon_minutes, iob_cob_history):
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


def predict_kalman(readings, physiology, horizon_minutes, iob_cob_history):
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


# How far back to look for a second reliable reading (IOB or COB) to pair
# with the current one when estimating its current slope - see
# _observed_iob_slope_per_minute / _observed_cob_slope_per_minute.
TREND_WINDOW_MINUTES = 30.0


def _eligible_iob_readings(sorted_r: list[dict]) -> list[dict]:
    return [r for r in sorted_r if r.get("iob") is not None and not r.get("iobUnreliable")]


def _slope_per_minute(windowed: list[dict], value_key: str, round_values: bool) -> float | None:
    first, last = windowed[0], windowed[-1]
    minutes_elapsed = (last["dateMs"] - first["dateMs"]) / 60_000.0
    if minutes_elapsed <= 0:
        return None
    first_value = round(first[value_key]) if round_values else first[value_key]
    last_value = round(last[value_key]) if round_values else last[value_key]
    return (last_value - first_value) / minutes_elapsed


def _windowed_for_trend(eligible: list[dict]) -> list[dict] | None:
    if len(eligible) < 2:
        return None
    cutoff_ms = eligible[-1]["dateMs"] - TREND_WINDOW_MINUTES * 60_000
    windowed = [r for r in eligible if r["dateMs"] >= cutoff_ms]
    return windowed if len(windowed) >= 2 else eligible[-2:]


def _observed_iob_slope_per_minute(sorted_r: list[dict]) -> float | None:
    """IOB's rate of change (units/minute). Prefers a pair within
    TREND_WINDOW_MINUTES, falls back to the last two eligible points if
    that window has too few (IOB only updates on change, so a flat stretch
    can span longer than the window)."""
    windowed = _windowed_for_trend(_eligible_iob_readings(sorted_r))
    return _slope_per_minute(windowed, "iob", round_values=False) if windowed else None


def _eligible_cob_readings(sorted_r: list[dict]) -> list[dict]:
    return [r for r in sorted_r if r.get("cob") is not None]


def _observed_cob_slope_per_minute(sorted_r: list[dict]) -> float | None:
    """Same as _observed_iob_slope_per_minute, for COB. Each value is
    rounded to a whole gram first - Gluroo's reported decimal precision
    exceeds real carb-entry granularity and was making the slope noisier
    than the underlying signal."""
    windowed = _windowed_for_trend(_eligible_cob_readings(sorted_r))
    return _slope_per_minute(windowed, "cob", round_values=True) if windowed else None


def _iob_cob_working_set(current: dict, history: list[dict]) -> list[dict]:
    """Current IOB/COB always comes from `current` (this cycle's `readings`),
    never from history's own newest entry - history only supplies an older
    point to pair with it, so a stale history fetch can't make "now" look
    staler than it is."""
    older = [r for r in (history or []) if r["dateMs"] < current["dateMs"]]
    return _sorted_by_time(older + [current])


def predict_multi_bolus(readings, physiology, horizon_minutes, iob_cob_history):
    """`predicted glucose = current - ISF * ΔIOB(horizon) + CSF * ΔCOB(horizon)`
    (a falling IOB releases glucose-lowering effect; a falling COB releases
    glucose-raising effect) - same core equation OpenAPS/Loop use. ΔIOB/ΔCOB
    come from the recent slope of reported IOB/COB (see
    _observed_iob_slope_per_minute), not from individual treatment records -
    Gluroo's treatments feed for this patient only logs "Correction Bolus"
    events, so real meal boluses/carbs were invisible to a per-record sum.

    IOB is sourced from the pump's own notification (externalIob/current),
    independent of Gluroo; COB still comes from Gluroo's devicestatus feed,
    so it's not necessarily any more reliable, just differently sourced."""
    if not readings:
        return None, "No readings yet."
    sorted_r = _sorted_by_time(readings)
    last = sorted_r[-1]

    isf = physiology.get("insulinSensitivityFactor", 0.0)
    if isf <= 0:
        return float(last["sgv"]), "No insulin sensitivity factor set."

    working_set = _iob_cob_working_set(last, iob_cob_history)

    # Clamped to <= 0: a *rising* IOB/COB means a dose/meal was just logged,
    # not decay in progress - the linear extrapolation below is only valid
    # for the decay phase, and blindly extending a fresh upward blip would
    # invert the sign of its predicted effect (e.g. a meal just entered
    # would predict glucose falling, not rising). Treated as no expected
    # change this cycle instead; the following cycles will pick up the real
    # decay once it starts.
    current_iob = last.get("iob")
    raw_iob_slope = (
        _observed_iob_slope_per_minute(working_set)
        if current_iob is not None and not last.get("iobUnreliable")
        else None
    )
    iob_slope_per_minute = min(0.0, raw_iob_slope) if raw_iob_slope is not None else None

    current_cob = last.get("cob")
    carb_ratio = physiology.get("carbRatio", 0.0)
    carb_sensitivity_factor = (isf / carb_ratio) if carb_ratio > 0 else 0.0
    raw_cob_slope = (
        _observed_cob_slope_per_minute(working_set)
        if current_cob is not None and carb_sensitivity_factor > 0
        else None
    )
    cob_slope_per_minute = min(0.0, raw_cob_slope) if raw_cob_slope is not None else None
    if iob_slope_per_minute is None and cob_slope_per_minute is None:
        return float(last["sgv"]), "No reliable IOB or COB history."

    total_insulin_drop = 0.0
    if iob_slope_per_minute is not None:
        projected_iob = max(0.0, current_iob + iob_slope_per_minute * horizon_minutes)
        total_insulin_drop = (current_iob - projected_iob) * isf

    total_carb_rise = 0.0
    if cob_slope_per_minute is not None:
        current_cob_rounded = round(current_cob)
        projected_cob = max(0.0, current_cob_rounded + cob_slope_per_minute * horizon_minutes)
        total_carb_rise = (current_cob_rounded - projected_cob) * carb_sensitivity_factor

    net_drop = total_insulin_drop - total_carb_rise
    if iob_slope_per_minute is not None and cob_slope_per_minute is not None:
        note = None
    elif iob_slope_per_minute is not None:
        note = "No reliable COB history - carb effect not modeled this cycle."
    else:
        note = "No reliable IOB history - insulin effect not modeled this cycle."
    return _clamp(last["sgv"] - net_drop), note


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
        "name": "IOB/COB decay",
        "description": (
            "Reads the recent slope of your actual reported IOB and COB and "
            "extrapolates each forward, converting the change to a glucose "
            "effect via your insulin sensitivity factor and carb ratio - "
            "rather than reconstructing either from individual bolus/carb "
            "records, which is more robust to gaps in what Nightscout/Gluroo "
            "logs as treatment history. IOB comes from your pump's own "
            "notification, independent of Gluroo; COB still comes from "
            "Gluroo's own feed, so it isn't guaranteed to be any more "
            "reliable than the treatment history it would otherwise use - "
            "just differently sourced. Falls back to modeling only whichever "
            "of the two has reliable data this cycle."
        ),
        "sourceUrl": None,
        "predict": predict_multi_bolus,
    },
]
