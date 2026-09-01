export type Severity = 'INFO' | 'WARNING' | 'CRITICAL';

export type AlertType =
  | 'LOW'
  | 'URGENT_LOW'
  | 'HIGH'
  | 'URGENT_HIGH'
  | 'IOB_HIGH'
  | 'STALE_DATA'
  | 'PREDICTED_LOW';

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
}

export interface GlucoseReading {
  sgv: number;
  direction: string;
  dateMs: number;
  iob: number | null;
}

export interface AlertEvent {
  type: AlertType;
  severity: Severity;
  value: number | null;
  message: string;
  timestamp: number;
  acknowledged: boolean;
}
