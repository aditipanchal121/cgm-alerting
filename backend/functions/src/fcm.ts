import * as admin from 'firebase-admin';
import { AlertEvent, AlertType, GlucoseReading } from './types';

// Self-contained (queries `members` itself) - used only by pollOnePatient's
// catch-block fallback, which may fire before membersSnap/tokensByUid exists.
export async function getMemberTokens(patientId: string): Promise<string[]> {
  const db = admin.firestore();
  const membersSnap = await db.collection('patients').doc(patientId).collection('members').get();
  const uids = membersSnap.docs.map((d) => d.id);
  if (!uids.length) {
    console.warn(`getMemberTokens(${patientId}): no members found`);
    return [];
  }

  const tokens: string[] = [];
  for (const uid of uids) {
    const tokensSnap = await db.collection('users').doc(uid).collection('fcmTokens').get();
    tokensSnap.forEach((t) => tokens.push(t.id));
  }
  console.log(`getMemberTokens(${patientId}): ${uids.length} member(s), ${tokens.length} token(s)`);
  return tokens;
}

// Takes `uids` (caller already has them) rather than querying `members`
// itself, and returns per-uid so sendAlertPush and sendReadingStatusPush
// can each use the same fetch without re-querying.
export async function getMemberTokensByUid(uids: string[]): Promise<Map<string, string[]>> {
  const db = admin.firestore();
  const result = new Map<string, string[]>();
  for (const uid of uids) {
    const tokensSnap = await db.collection('users').doc(uid).collection('fcmTokens').get();
    result.set(
      uid,
      tokensSnap.docs.map((t) => t.id)
    );
  }
  return result;
}

/** Logs sendEachForMulticast's per-token result - it never throws on a
 * rejected token (e.g. one invalidated by a reinstall), so without this a
 * push can silently fail to reach anyone while the calling function still
 * reports success. */
function logMulticastResult(label: string, response: admin.messaging.BatchResponse): void {
  console.log(`${label}: ${response.successCount} succeeded, ${response.failureCount} failed`);
  response.responses.forEach((r, i) => {
    if (!r.success) {
      console.error(`${label}: token[${i}] failed - ${r.error?.code}: ${r.error?.message}`);
    }
  });
}

// Which alert types wake the phone with a full-screen takeover (see
// AlarmActivity), independent of notification channel/severity - severity
// still governs how loud/urgent the notification itself is, this governs
// only whether it interrupts. Deliberately narrow: a STALE_DATA or
// URGENT_HIGH alert (or a LOW re-firing every cycle while already treated
// and waiting on insulin/carbs to land) is still CRITICAL-channel-loud, but
// doesn't need to seize the screen the way a fresh urgent low does.
const WAKE_ALERT_TYPES = new Set<AlertType>(['URGENT_LOW']);

// Pushes an alert to one specific member (each has their own thresholds).
// `tokens` comes from the caller's getMemberTokensByUid, not fetched here.
// Data-only (no top-level `notification` field) - a message with one gets
// auto-displayed by Android whenever the app isn't foregrounded, bypassing
// VigilFcmService.onMessageReceived() entirely and with it every alert here,
// wake-worthy or not. The client builds the actual notification itself in
// every app state, same as sendReadingStatusPush below already does.
export async function sendAlertPush(
  uid: string,
  patientId: string,
  displayName: string,
  event: AlertEvent,
  tokens: string[]
): Promise<void> {
  if (!tokens.length) return;

  const message: admin.messaging.MulticastMessage = {
    tokens,
    android: { priority: 'high' },
    data: {
      kind: 'alert',
      severity: event.severity,
      type: event.type,
      patientId,
      displayName,
      message: event.message,
      wake: String(WAKE_ALERT_TYPES.has(event.type)),
    },
  };

  // TODO(production hardening): sweep response.responses for
  // messaging/registration-token-not-registered and delete those token docs.
  const response = await admin.messaging().sendEachForMulticast(message);
  logMulticastResult(`sendAlertPush(${uid})`, response);
}

// Pushes the latest reading to every member, keeping a persistent status
// notification current even when the app isn't open. Called every poll
// cycle regardless of outcome - `reading` is null when there's no sensor
// data, so the notification stays honest instead of freezing on stale content.
export async function sendReadingStatusPush(
  patientId: string,
  displayName: string,
  reading: GlucoseReading | null,
  tokens: string[]
): Promise<void> {
  if (!tokens.length) {
    console.warn(`sendReadingStatusPush(${patientId}): no tokens, nothing sent`);
    return;
  }

  const message: admin.messaging.MulticastMessage = {
    tokens,
    // Data-only (no `notification` block) - FCM must not auto-post a new
    // notification every 5 minutes. The client updates one ongoing
    // notification in place instead.
    data: reading
      ? {
          kind: 'reading',
          patientId,
          displayName,
          sgv: String(reading.sgv),
          direction: reading.direction,
          dateMs: String(reading.dateMs),
          iob: reading.iob !== null ? String(reading.iob) : '',
          iobUnreliable: String(reading.iobUnreliable ?? false),
        }
      : {
          kind: 'reading',
          patientId,
          displayName,
        },
    android: {
      priority: 'high',
    },
  };

  const response = await admin.messaging().sendEachForMulticast(message);
  logMulticastResult(`sendReadingStatusPush(${patientId})`, response);
}
