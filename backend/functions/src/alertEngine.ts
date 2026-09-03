import { AlertEvent, AlertType, GlucoseReading, Severity, Thresholds } from './types';
import { predictMinutesToThreshold } from './predictor';

const FAST_DROP_DIRECTIONS = new Set(['DoubleDown', 'SingleDown']);
const FAST_RISE_DIRECTIONS = new Set(['DoubleUp', 'SingleUp']);
/** Alert types worth escalating to CRITICAL overnight - a missed low is the
 * primary risk this whole system exists to catch; a high glucose overnight
 * is comparatively slow-onset and doesn't need the same wake-up urgency. */
const NIGHT_ESCALATED_TYPES = new Set<AlertType>(['LOW', 'PREDICTED_LOW', 'IOB_HIGH']);

/** Dexcom's own published trend-arrow cutoff for "DoubleDown": a rate of
 * change at or beyond 3 mg/dL/min. Used as the base bar for "this drop is
 * fast enough to be worth questioning" - daytime requires a bit more than
 * that (real fast drops, e.g. exercise, are more common and less alarming
 * while a caretaker is awake to see them happen), night requires a bit
 * less (compression - lying on the sensor - is overwhelmingly a nighttime
 * artifact, so a slightly gentler drop is already suspicious then). +/-20%
 * off the 3 mg/dL/min baseline. */
const COMPRESSION_LOW_DAY_RATE = 3.5;
const COMPRESSION_LOW_NIGHT_RATE = 2.5;

/** Generous upper bound on how fast IOB can plausibly drop glucose, in
 * mg/dL/min per unit of active insulin - deliberately biased toward
 * crediting insulin with more effect than is realistic, so this only flags
 * drops that are implausible even under the most generous assumption.
 * Derived from: a very insulin-sensitive ISF of 100 mg/dL/unit (typical
 * T1D ISF runs roughly 15-100 mg/dL/unit; 100 is already the sensitive
 * end), combined with rapid-acting insulin's activity curve peaking
 * around 60-90 min post-dose at roughly 1% of total effect per minute
 * (a ~3h duration-of-action spread unevenly, weighted toward the peak).
 * 100 mg/dL/unit x ~1%/min =~ 1 mg/dL/min per unit of IOB at peak. */
const COMPRESSION_LOW_ISF_PROXY_MGDL_PER_MIN_PER_UNIT = 1;

export function evaluateAlerts(
  recentReadings: GlucoseReading[],
  thresholds: Thresholds,
  nowMs: number
): AlertEvent[] {
  const latest = recentReadings[recentReadings.length - 1];
  if (!latest) return [];

  const ageMinutes = (nowMs - latest.dateMs) / 60000;
  if (ageMinutes >= thresholds.staleMinutes) {
    return [
      makeEvent(
        'STALE_DATA',
        'CRITICAL',
        null,
        `No new glucose reading in ${Math.round(ageMinutes)} minutes.`,
        nowMs
      ),
    ];
  }

  const events: AlertEvent[] = [];
  const sgv = latest.sgv;
  const fastDrop = FAST_DROP_DIRECTIONS.has(latest.direction);
  const fastRise = FAST_RISE_DIRECTIONS.has(latest.direction);

  if (sgv <= thresholds.urgentLowMgdl) {
    events.push(makeEvent('URGENT_LOW', 'CRITICAL', sgv, `Urgent low: ${sgv} mg/dL.`, nowMs));
    const compressionLow = detectCompressionLow(recentReadings, thresholds, nowMs);
    if (compressionLow) events.push(compressionLow);
  } else if (sgv <= thresholds.lowMgdl) {
    events.push(
      makeEvent(
        'LOW',
        fastDrop ? 'CRITICAL' : 'WARNING',
        sgv,
        `Low: ${sgv} mg/dL${fastDrop ? ', dropping fast' : ''}.`,
        nowMs
      )
    );
    const compressionLow = detectCompressionLow(recentReadings, thresholds, nowMs);
    if (compressionLow) events.push(compressionLow);
  } else if (sgv >= thresholds.urgentHighMgdl) {
    events.push(makeEvent('URGENT_HIGH', 'CRITICAL', sgv, `Urgent high: ${sgv} mg/dL.`, nowMs));
  } else if (sgv >= thresholds.highMgdl) {
    events.push(
      makeEvent(
        'HIGH',
        fastRise ? 'WARNING' : 'INFO',
        sgv,
        `High: ${sgv} mg/dL${fastRise ? ', rising fast' : ''}.`,
        nowMs
      )
    );
  } else {
    const prediction = predictMinutesToThreshold(recentReadings, thresholds.lowMgdl);
    if (prediction.minutesToThreshold !== null) {
      events.push(
        makeEvent(
          'PREDICTED_LOW',
          'WARNING',
          sgv,
          `Projected to cross ${thresholds.lowMgdl} mg/dL in ~${Math.round(
            prediction.minutesToThreshold
          )} min.`,
          nowMs
        )
      );
    }
  }

  if (latest.iob !== null && latest.iob >= thresholds.iobThreshold) {
    events.push(
      makeEvent(
        'IOB_HIGH',
        'WARNING',
        latest.iob,
        `IOB is ${latest.iob.toFixed(2)}u, above your ${thresholds.iobThreshold}u threshold.`,
        nowMs
      )
    );
  }

  if (latest.iobUnreliable) {
    events.push(
      makeEvent(
        'IOB_UNRELIABLE',
        'INFO',
        latest.iob,
        'IOB dropped to 0 abruptly - this may be unreliable data from Gluroo rather than a true zero.',
        nowMs
      )
    );
  }

  if (isWithinNightWindow(nowMs, thresholds)) {
    for (const event of events) {
      if (event.severity === 'WARNING' && NIGHT_ESCALATED_TYPES.has(event.type)) {
        event.severity = 'CRITICAL';
      }
    }
  }

  return events;
}

/** Flags a likely compression low: sensor pressure (classically, lying on
 * the sensor overnight) producing a reading that plunges far faster than
 * the patient's actual active insulin could plausibly explain. Layered on
 * top of the real LOW/URGENT_LOW event above rather than replacing it -
 * this is a "verify with a fingerstick before treating" hint, not a
 * suppression of the underlying alert, since a false negative on a real
 * low is far more dangerous than an unnecessary double-check. */
function detectCompressionLow(
  recentReadings: GlucoseReading[],
  thresholds: Thresholds,
  nowMs: number
): AlertEvent | null {
  if (recentReadings.length < 2) return null;
  const latest = recentReadings[recentReadings.length - 1];
  const previous = recentReadings[recentReadings.length - 2];

  const minutesElapsed = (latest.dateMs - previous.dateMs) / 60000;
  if (minutesElapsed <= 0) return null;

  const ratePerMin = (latest.sgv - previous.sgv) / minutesElapsed;
  const isNight = isWithinNightWindow(nowMs, thresholds);
  const requiredRate = isNight ? COMPRESSION_LOW_NIGHT_RATE : COMPRESSION_LOW_DAY_RATE;

  const iob = latest.iob ?? 0;
  const maxPlausibleRate = Math.max(requiredRate, iob * COMPRESSION_LOW_ISF_PROXY_MGDL_PER_MIN_PER_UNIT);

  if (ratePerMin > -maxPlausibleRate) return null;

  const dropped = Math.round(previous.sgv - latest.sgv);
  return makeEvent(
    'COMPRESSION_LOW',
    'WARNING',
    latest.sgv,
    `Dropped ${dropped} mg/dL in ${minutesElapsed.toFixed(1)} min with only ${iob.toFixed(2)}u IOB` +
      `${isNight ? ' overnight' : ''} - may be a compression low (sensor pressure) rather than a ` +
      `true reading. Consider a fingerstick check before treating.`,
    nowMs
  );
}

export function isWithinNightWindow(nowMs: number, thresholds: Thresholds): boolean {
  const parts = new Intl.DateTimeFormat('en-US', {
    hour: '2-digit',
    minute: '2-digit',
    hour12: false,
    timeZone: thresholds.timezone,
  }).formatToParts(new Date(nowMs));
  const hour = Number(parts.find((p) => p.type === 'hour')?.value ?? '0');
  const minute = Number(parts.find((p) => p.type === 'minute')?.value ?? '0');
  const minutesNow = hour * 60 + minute;

  const [startH, startM] = thresholds.nightWindowStart.split(':').map(Number);
  const [endH, endM] = thresholds.nightWindowEnd.split(':').map(Number);
  const startMinutes = startH * 60 + startM;
  const endMinutes = endH * 60 + endM;

  if (startMinutes === endMinutes) return true; // 24h night mode
  if (startMinutes < endMinutes) {
    return minutesNow >= startMinutes && minutesNow < endMinutes;
  }
  // window wraps past midnight, e.g. 22:00-07:00
  return minutesNow >= startMinutes || minutesNow < endMinutes;
}

function makeEvent(
  type: AlertType,
  severity: Severity,
  value: number | null,
  message: string,
  timestamp: number
): AlertEvent {
  return { type, severity, value, message, timestamp, acknowledged: false };
}
