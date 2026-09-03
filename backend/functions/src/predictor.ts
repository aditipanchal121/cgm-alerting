import { GlucoseReading } from './types';

export interface PredictionResult {
  projectedValue: number;
  minutesToThreshold: number | null;
}

/**
 * Baseline predictor: linear extrapolation from the rate of change across
 * the given readings. Deliberately the only implementation for now - this
 * is the extension point future ML models get swapped into and A/B tested
 * against, so it's kept as a plain, easily-testable function.
 */
export function predictMinutesToThreshold(
  readings: GlucoseReading[],
  threshold: number,
  horizonMinutes = 30
): PredictionResult {
  const sorted = [...readings].sort((a, b) => a.dateMs - b.dateMs);
  if (sorted.length < 2) {
    return { projectedValue: sorted[0]?.sgv ?? 0, minutesToThreshold: null };
  }

  const first = sorted[0];
  const last = sorted[sorted.length - 1];
  const minutesElapsed = (last.dateMs - first.dateMs) / 60000;
  if (minutesElapsed <= 0) {
    return { projectedValue: last.sgv, minutesToThreshold: null };
  }

  const ratePerMinute = (last.sgv - first.sgv) / minutesElapsed;
  // CGMs don't report numbers outside this range either - Dexcom shows "LOW"
  // below 40 and "HIGH" above 400 rather than a value - so an extrapolated
  // projection has no business claiming more precision than the sensor
  // itself would (mirrors the same clamp in the Android app's
  // GlucosePredictor.kt, added after its quadratic model was seen producing
  // a -1 mg/dL projection).
  const projectedValue = Math.min(400, Math.max(40, last.sgv + ratePerMinute * horizonMinutes));

  if (ratePerMinute === 0) {
    return { projectedValue, minutesToThreshold: null };
  }

  const minutesToThreshold = (threshold - last.sgv) / ratePerMinute;
  const willCrossWithinHorizon = minutesToThreshold > 0 && minutesToThreshold <= horizonMinutes;
  return { projectedValue, minutesToThreshold: willCrossWithinHorizon ? minutesToThreshold : null };
}
