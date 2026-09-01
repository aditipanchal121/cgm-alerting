# NightWatch haptic alarm device (ESP32)

A dedicated, purpose-built vibrating alarm for CGM alerts - built ahead of
the Wear OS extension per the project's priorities. Polls the Firebase
Realtime Database node the backend writes to (`../backend`), independent of
the phone, over WiFi.

## Hardware

- ESP32 dev board (any variant with WiFi; code assumes a standard `esp32dev`
  board definition).
- Vibration motor driven through a transistor/MOSFET on `VIBRATION_PIN`
  (GPIO26 by default) - do not drive a motor directly from a GPIO pin.
  For a stronger, patterned buzz, swap in a haptic driver IC like the
  DRV2605 instead of a bare motor + MOSFET.
- A momentary push button between `ACK_BUTTON_PIN` (GPIO27) and GND
  (the firmware uses `INPUT_PULLUP`, so no external resistor needed) to
  silence an alarm locally.
- USB or a small battery for power.

## One-time setup

1. In the Firebase console: **Authentication > Sign-in method**, enable
   **Anonymous** sign-in (the device authenticates anonymously to satisfy
   the `auth != null` Realtime Database rule in `../backend/database.rules.json`).
2. Copy your project's **Web API Key** (Project settings > General) and
   **Realtime Database URL** into the `FIREBASE_API_KEY` and
   `FIREBASE_DATABASE_URL` `#define`s at the top of `src/main.cpp`.
3. Install [PlatformIO](https://platformio.org/), then from this directory:
   `pio run --target upload` (with the board connected over USB).
4. On first boot the device opens a WiFi setup portal named
   `NightWatch-Setup-esp32-XXXXXXXXXXXX` - connect to it from a phone and
   enter your home WiFi credentials.
5. Open the serial monitor (`pio device monitor`, 115200 baud) to read the
   device's ID, then enter it in the Android app's "Alarm device" pairing
   screen to link it to a patient.

## Known prototype-level simplifications

- Polls the backend's alert node every 20 seconds rather than streaming -
  simpler and more stable to get right than a real-time Firebase client
  library, at the cost of up to ~20s of added latency (trivial next to the
  backend's 5-minute poll cycle).
- Skips TLS certificate validation (`WiFiClientSecure::setInsecure()`).
  Fine for a prototype; pin Google's root CA before relying on this for
  real overnight monitoring.
- The acknowledge button silences the buzz locally only - it doesn't write
  back to the backend or the Android app's alert history.
