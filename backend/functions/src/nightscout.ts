import * as crypto from 'crypto';
import { GlucoseReading } from './types';

function hashSecret(secret: string): string {
  return crypto.createHash('sha1').update(secret).digest('hex');
}

interface NightscoutEntry {
  sgv: number;
  direction?: string;
  date: number;
}

async function getJson<T>(url: string, apiSecret: string): Promise<T> {
  const res = await fetch(url, { headers: { 'api-secret': hashSecret(apiSecret) } });
  if (!res.ok) {
    throw new Error(`Nightscout request failed (${res.status} ${res.statusText}): ${url}`);
  }
  return (await res.json()) as T;
}

// A point in the IOB/COB history we build ourselves (see updateIobCobHistory
// in index.ts) - Gluroo's devicestatus.json only ever returns a single
// current snapshot, not real history, regardless of `count` requested.
export interface DeviceStatusPoint {
  dateMs: number;
  iob: number | null;
  cob: number | null;
}

export async function fetchRecentReadings(
  baseUrl: string,
  apiSecret: string,
  count = 6
): Promise<GlucoseReading[]> {
  const trimmedBase = baseUrl.replace(/\/$/, '');
  const entries = await getJson<NightscoutEntry[]>(
    `${trimmedBase}/api/v1/entries.json?count=${count}`,
    apiSecret
  );
  if (!entries.length) return [];

  const { iob, cob } = await fetchDeviceStatus(trimmedBase, apiSecret);

  // Nightscout returns newest-first; callers want oldest-first for trend math.
  return entries
    .slice()
    .reverse()
    .map((e, i, arr) => ({
      sgv: e.sgv,
      direction: e.direction ?? 'NOT COMPUTABLE',
      dateMs: e.date,
      iob: i === arr.length - 1 ? iob : null,
      cob: i === arr.length - 1 ? cob : null,
    }));
}

async function fetchDeviceStatus(
  trimmedBase: string,
  apiSecret: string
): Promise<{ iob: number | null; cob: number | null }> {
  try {
    const statuses = await getJson<Array<Record<string, any>>>(
      `${trimmedBase}/api/v1/devicestatus.json?count=1`,
      apiSecret
    );
    const status = statuses[0];
    if (!status) return { iob: null, cob: null };
    // IOB/COB shape depends on which loop system feeds Gluroo - check known shapes.
    const iob =
      status?.glurooIob ??
      status?.loop?.iob?.iob ??
      status?.openaps?.iob?.iob ??
      (Array.isArray(status?.openaps?.iob) ? status.openaps.iob[0]?.iob : undefined) ??
      status?.pump?.iob?.bolusiob ??
      null;
    const cob = status?.glurooCob ?? status?.loop?.cob ?? status?.openaps?.cob ?? null;
    return {
      iob: typeof iob === 'number' ? iob : null,
      cob: typeof cob === 'number' ? cob : null,
    };
  } catch {
    // devicestatus is optional - a Gluroo account with no loop system won't have it.
    return { iob: null, cob: null };
  }
}

export interface TreatmentEvent {
  nightscoutId: string;
  eventType: string;
  mills: number;
  insulin: number | null;
  carbs: number | null;
  /** Minutes - from the treatment's `duration`/`absorptionTime` field. */
  durationMinutes: number | null;
  notes: string | null;
}

/** Fetches only treatments newer than [sinceMs] (null on first-ever sync,
 * which backfills up to [count] recent ones). Uses Nightscout's
 * `find[field][$op]` query params; the client-side mills filter below is a
 * backstop in case that server-side filter is ever ignored. */
export async function fetchTreatmentsSince(
  baseUrl: string,
  apiSecret: string,
  sinceMs: number | null,
  count = 100
): Promise<TreatmentEvent[]> {
  const trimmedBase = baseUrl.replace(/\/$/, '');
  const query = sinceMs != null ? `find[mills][$gte]=${sinceMs}&count=${count}` : `count=${count}`;
  const raw = await getJson<Array<Record<string, any>>>(
    `${trimmedBase}/api/v1/treatments.json?${query}`,
    apiSecret
  );
  return raw
    .filter((t) => typeof t.mills === 'number' && (sinceMs == null || t.mills > sinceMs))
    .map((t) => ({
      nightscoutId: String(t._id),
      eventType: typeof t.eventType === 'string' ? t.eventType : 'Unknown',
      mills: t.mills as number,
      insulin: typeof t.insulin === 'number' ? t.insulin : null,
      carbs: typeof t.carbs === 'number' ? t.carbs : null,
      durationMinutes: typeof t.duration === 'number' ? t.duration : null,
      notes: typeof t.notes === 'string' ? t.notes : null,
    }));
}

export async function verifyConnection(
  baseUrl: string,
  apiSecret: string
): Promise<{ ok: boolean; message: string }> {
  try {
    const readings = await fetchRecentReadings(baseUrl, apiSecret, 1);
    if (!readings.length) {
      return { ok: true, message: 'Connected, but no glucose entries were returned yet.' };
    }
    return { ok: true, message: `Connected. Latest reading: ${readings[0].sgv} mg/dL.` };
  } catch (err) {
    return { ok: false, message: err instanceof Error ? err.message : 'Unknown error' };
  }
}
