import { AlertEvent, AlertType, GlucoseReading, Severity, Thresholds } from './types';
import { predictMinutesToThreshold } from './predictor';

const FAST_DROP_DIRECTIONS = new Set(['DoubleDown', 'SingleDown']);
const FAST_RISE_DIRECTIONS = new Set(['DoubleUp', 'SingleUp']);
/** Alert types worth escalating to CRITICAL overnight - a missed low is the
 * primary risk this whole system exists to catch; a high glucose overnight
 * is comparatively slow-onset and doesn't need the same wake-up urgency. */
const NIGHT_ESCALATED_TYPES = new Set<AlertType>(['LOW', 'PREDICTED_LOW', 'IOB_HIGH']);

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
