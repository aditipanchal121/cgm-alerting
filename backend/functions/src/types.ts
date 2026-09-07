export type Severity = 'INFO' | 'WARNING' | 'CRITICAL';

export type AlertType =
  | 'LOW'
  | 'URGENT_LOW'
  | 'HIGH'
  | 'URGENT_HIGH'
  | 'IOB_HIGH'
  | 'IOB_UNRELIABLE'
  | 'STALE_DATA'
  | 'PREDICTED_LOW'
  | 'COMPRESSION_LOW';

export interface Thresholds {
  units: 'mgdl' | 'mmol';
  lowMgdl: number;
  urgentLowMgdl: number;
  highMgdl: number;
  urgentHighMgdl: number;
  iobThreshold: number;
  /** "HH:MM" 24h local time */
  nightWindowStart: string;
  /** "HH:MM" 24h local time */
  nightWindowEnd: string;
  /** IANA zone, e.g. "America/Los_Angeles" - used to evaluate the night window */
  timezone: string;
  /** how old the latest reading can be before it's flagged as a signal-loss alert */
  staleMinutes: number;
  /** Which alert types this member wants to be notified about at all - an
   * event of a type not in this list is never generated for them, not even
   * logged. Personal, like the rest of Thresholds. */
  enabledAlertTypes: AlertType[];
}

/** Physiological facts about the patient, not personal alerting preferences -
 * shared across the whole family (patients/{patientId}/thresholds/current)
 * rather than per-member like Thresholds above, since there's only one real
 * insulin sensitivity/carb ratio regardless of who's viewing the app. */
export interface PatientPhysiology {
  /** mg/dL that 1 unit of insulin is expected to lower glucose by. */
  insulinSensitivityFactor: number;
  /** Grams of carbs covered by 1 unit of insulin (insulin-to-carb ratio). */
  carbRatio: number;
}

export interface GlucoseReading {
  sgv: number;
  direction: string;
  dateMs: number;
  iob: number | null;
  /** True when iob is 0 but dropped from a meaningfully higher value in a
   * single poll cycle - insulin doesn't disappear that fast, so this is
   * likely a Gluroo-side glitch rather than a real zero. The zero is still
   * reported as-is; this only flags it as suspect. */
  iobUnreliable?: boolean;
  /** Carbs on board, in grams, when Gluroo's devicestatus reports it. Not
   * currently used by alerting or the on-device predictors - captured for
   * future model training (see backend/README.md's data retention note). */
  cob: number | null;
}

export interface AlertEvent {
  type: AlertType;
  severity: Severity;
  value: number | null;
  message: string;
  timestamp: number;
}
