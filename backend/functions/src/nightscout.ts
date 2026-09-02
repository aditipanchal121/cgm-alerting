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

  const iob = await fetchIob(trimmedBase, apiSecret);
  // Nightscout returns newest-first; callers want oldest-first for trend math.
  return entries
    .slice()
    .reverse()
    .map((e, i, arr) => ({
      sgv: e.sgv,
      direction: e.direction ?? 'NOT COMPUTABLE',
      dateMs: e.date,
      iob: i === arr.length - 1 ? iob : null,
    }));
}

async function fetchIob(trimmedBase: string, apiSecret: string): Promise<number | null> {
  try {
    const statuses = await getJson<Array<Record<string, any>>>(
      `${trimmedBase}/api/v1/devicestatus.json?count=1`,
      apiSecret
    );
    const status = statuses[0];
    if (!status) return null;
    // IOB shape depends on which loop system feeds Gluroo - check known shapes.
    const iob =
      status?.glurooIob ??
      status?.loop?.iob?.iob ??
      status?.openaps?.iob?.iob ??
      (Array.isArray(status?.openaps?.iob) ? status.openaps.iob[0]?.iob : undefined) ??
      status?.pump?.iob?.bolusiob ??
      null;
    return typeof iob === 'number' ? iob : null;
  } catch {
    // devicestatus is optional - a Gluroo account with no loop system won't have it.
    return null;
  }
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
