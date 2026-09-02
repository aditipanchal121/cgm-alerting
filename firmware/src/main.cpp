/**
 * NightWatch haptic alarm device (ESP32).
 *
 * Polls the Firebase Realtime Database node this device is paired to
 * (/devices/{deviceId}/alert, written by the backend's pollGlucose
 * function - see ../backend) over plain HTTPS REST every POLL_INTERVAL_MS,
 * and drives a vibration motor AND a piezo buzzer when a WARNING/CRITICAL
 * alert appears - two independent physical channels so a person who sleeps
 * through (or subconsciously tunes out) one still has the other.
 *
 * Deliberately uses plain HTTPClient + the Realtime Database REST API
 * instead of a Firebase C++ SDK: it's a smaller, more stable surface to
 * get right in a base prototype than a fast-moving third-party library.
 * The tradeoff is polling latency (POLL_INTERVAL_MS) instead of an
 * instant push - fine here since the backend itself only re-evaluates
 * alerts every 5 minutes.
 *
 * Known simplifications (see ../backend/README.md for the matching
 * backend-side notes):
 *  - TLS certificate validation is skipped (WiFiClientSecure::setInsecure)
 *    for simplicity; pin Google's root CA for anything beyond a prototype.
 *  - The acknowledge button silences the buzz locally only - it doesn't
 *    write back to the backend, so the phone app's alert history is a
 *    separate acknowledgment path.
 */

#include <Arduino.h>
#include <WiFi.h>
#include <WiFiClientSecure.h>
#include <HTTPClient.h>
#include <WiFiManager.h>
#include <ArduinoJson.h>

// ---- Fill these in before flashing --------------------------------------
// Firebase Console > Project settings > General > Web API Key
#define FIREBASE_API_KEY "REPLACE_WITH_YOUR_FIREBASE_WEB_API_KEY"
// Firebase Console > Realtime Database > URL (e.g. https://xxx-default-rtdb.firebaseio.com)
#define FIREBASE_DATABASE_URL "https://REPLACE_WITH_YOUR_PROJECT-default-rtdb.firebaseio.com"
// ---------------------------------------------------------------------------

constexpr unsigned long POLL_INTERVAL_MS = 20'000;
constexpr unsigned long TOKEN_REFRESH_MARGIN_MS = 5UL * 60 * 1000; // refresh 5 min early

constexpr int VIBRATION_PIN = 26;
constexpr int ACK_BUTTON_PIN = 27;
constexpr int VIBRATION_PWM_CHANNEL = 0;

// A passive piezo buzzer draws little enough current to drive directly from
// the GPIO/LEDC output - unlike the vibration motor, it does not need a
// transistor.
constexpr int BUZZER_PIN = 25;
constexpr int BUZZER_PWM_CHANNEL = 1;

String deviceId;
String idToken;
String refreshToken;
unsigned long tokenExpiresAtMs = 0;
unsigned long lastPollAtMs = 0;
String lastAlertId;
bool alarming = false;

void computeDeviceId() {
  uint64_t chipId = ESP.getEfuseMac();
  char buf[24];
  snprintf(buf, sizeof(buf), "esp32-%04X%08X",
           (uint16_t)(chipId >> 32), (uint32_t)chipId);
  deviceId = String(buf);
}

void connectWiFi() {
  WiFiManager wifiManager;
  // Portal SSID includes the device ID so it's identifiable if multiple
  // NightWatch devices are being set up on the same network.
  String portalName = "NightWatch-Setup-" + deviceId;
  if (!wifiManager.autoConnect(portalName.c_str())) {
    Serial.println("WiFi setup timed out, restarting...");
    ESP.restart();
  }
}

bool signInAnonymously() {
  WiFiClientSecure client;
  client.setInsecure();
  HTTPClient http;
  String url = "https://identitytoolkit.googleapis.com/v1/accounts:signUp?key=" FIREBASE_API_KEY;
  if (!http.begin(client, url)) return false;
  http.addHeader("Content-Type", "application/json");

  int code = http.POST("{\"returnSecureToken\":true}");
  if (code != 200) {
    Serial.printf("Anonymous sign-in failed: HTTP %d\n", code);
    http.end();
    return false;
  }

  JsonDocument doc;
  deserializeJson(doc, http.getStream());
  http.end();

  idToken = doc["idToken"].as<String>();
  refreshToken = doc["refreshToken"].as<String>();
  long expiresInSec = doc["expiresIn"].as<String>().toInt();
  tokenExpiresAtMs = millis() + (expiresInSec * 1000UL);
  return idToken.length() > 0;
}

bool refreshIdToken() {
  WiFiClientSecure client;
  client.setInsecure();
  HTTPClient http;
  String url = "https://securetoken.googleapis.com/v1/token?key=" FIREBASE_API_KEY;
  if (!http.begin(client, url)) return false;
  http.addHeader("Content-Type", "application/x-www-form-urlencoded");

  String body = "grant_type=refresh_token&refresh_token=" + refreshToken;
  int code = http.POST(body);
  if (code != 200) {
    Serial.printf("Token refresh failed: HTTP %d\n", code);
    http.end();
    return false;
  }

  JsonDocument doc;
  deserializeJson(doc, http.getStream());
  http.end();

  idToken = doc["id_token"].as<String>();
  refreshToken = doc["refresh_token"].as<String>();
  long expiresInSec = doc["expires_in"].as<String>().toInt();
  tokenExpiresAtMs = millis() + (expiresInSec * 1000UL);
  return idToken.length() > 0;
}

void ensureFreshToken() {
  if (idToken.isEmpty()) {
    signInAnonymously();
    return;
  }
  if (millis() + TOKEN_REFRESH_MARGIN_MS >= tokenExpiresAtMs) {
    if (!refreshIdToken()) {
      // Refresh token itself may have gone stale - fall back to a fresh
      // anonymous identity rather than getting stuck.
      signInAnonymously();
    }
  }
}

void setVibration(bool on, int intensity255 = 255) {
  ledcWrite(VIBRATION_PWM_CHANNEL, on ? intensity255 : 0);
}

void setBuzzer(bool on, double frequencyHz) {
  if (on) {
    ledcWriteTone(BUZZER_PWM_CHANNEL, frequencyHz);
  } else {
    ledcWrite(BUZZER_PWM_CHANNEL, 0);
  }
}

String alarmSeverity;

void triggerAlarm(const String &severity) {
  alarming = true;
  alarmSeverity = severity;
  Serial.printf("ALERT (%s) - vibrating and sounding\n", severity.c_str());
}

void serviceAlarmOutputs() {
  if (!alarming) return;

  // Both channels are re-evaluated every loop() call using millis(), so
  // neither needs a blocking delay() (which would stall WiFi polling and
  // button reads). Vibration and buzzer cadences are independent - the two
  // channels are meant to be redundant backups for each other, not a single
  // synchronized effect, so one being slept through doesn't matter as long
  // as the other gets noticed.
  if (alarmSeverity == "CRITICAL") {
    // Vibration: near-continuous at full intensity.
    unsigned long vibT = millis() % 2000;
    setVibration(vibT < 1800, 255);

    // Buzzer: fast, high-pitched, urgent beeping.
    unsigned long beepT = millis() % 250;
    setBuzzer(beepT < 150, 3000);
  } else {
    // Vibration: short pulse, long rest, lower intensity.
    unsigned long vibT = millis() % 3000;
    setVibration(vibT < 600, 160);

    // Buzzer: a single short, lower-pitched beep - noticeably calmer than
    // the CRITICAL pattern.
    unsigned long beepT = millis() % 1500;
    setBuzzer(beepT < 200, 1500);
  }
}

void checkAckButton() {
  if (digitalRead(ACK_BUTTON_PIN) == LOW) {
    if (alarming) {
      Serial.println("Alert acknowledged locally - silencing.");
    }
    alarming = false;
    setVibration(false);
    setBuzzer(false, 0);
  }
}

void pollAlert() {
  ensureFreshToken();
  if (idToken.isEmpty()) return;

  WiFiClientSecure client;
  client.setInsecure();
  HTTPClient http;
  String url = String(FIREBASE_DATABASE_URL) + "/devices/" + deviceId + "/alert.json?auth=" + idToken;
  if (!http.begin(client, url)) return;

  int code = http.GET();
  if (code != 200) {
    Serial.printf("Poll failed: HTTP %d\n", code);
    http.end();
    return;
  }

  JsonDocument doc;
  DeserializationError err = deserializeJson(doc, http.getStream());
  http.end();
  if (err) return;

  if (doc.isNull()) return; // not paired yet, or node cleared

  String severity = doc["severity"] | "NONE";
  String alertId = doc["alertId"] | "";

  if (alertId != lastAlertId) {
    lastAlertId = alertId;
    if (severity == "WARNING" || severity == "CRITICAL") {
      triggerAlarm(severity);
    } else {
      alarming = false;
      setVibration(false);
      setBuzzer(false, 0);
    }
  }
}

void setup() {
  Serial.begin(115200);
  delay(200);

  computeDeviceId();
  Serial.println("=========================================");
  Serial.println("NightWatch haptic alarm device");
  Serial.println("Device ID (enter this in the app's Pair Device screen):");
  Serial.println(deviceId);
  Serial.println("=========================================");

  pinMode(ACK_BUTTON_PIN, INPUT_PULLUP);
  ledcSetup(VIBRATION_PWM_CHANNEL, 5000, 8);
  ledcAttachPin(VIBRATION_PIN, VIBRATION_PWM_CHANNEL);

  ledcSetup(BUZZER_PWM_CHANNEL, 2000, 8);
  ledcAttachPin(BUZZER_PIN, BUZZER_PWM_CHANNEL);

  connectWiFi();
  signInAnonymously();
}

void loop() {
  checkAckButton();
  serviceAlarmOutputs();

  if (millis() - lastPollAtMs >= POLL_INTERVAL_MS) {
    lastPollAtMs = millis();
    pollAlert();
  }
}
