# Training Hub

Android remote for **Mobvoi Home N4225** treadmills exposing the standard Bluetooth LE **FTMS** service.

Verified on the owner's N4225:
- Fitness Machine Service `0x1826`
- Control Point `0x2AD9` with Indicate + Write Without Response
- Request Control: success
- Set Target Speed: success
- Start / Resume: success
- Stop / Pause: success
- Supported speed: **1.0–6.0 km/h**, step **0.5 km/h**

## Install without Android Studio

Open the repository's **Actions** tab, open the latest **Build Android APK** run, and download the `MobvoiN4225Remote-debug` artifact. Extract it and install `app-debug.apk` on Android.

Before testing, disconnect/close nRF Connect so it does not hold the BLE connection.

## First safe test

Keep the belt empty and stay next to the physical power switch.

1. Grant Nearby devices/Bluetooth permission.
2. Scan and connect to `Mobvoi WTMP`.
3. Wait for **Control ready**.
4. Send STOP.
5. Set 1.0 km/h.
6. START.
7. Test +0.5 km/h.
8. STOP.

This is an unofficial controller and is not affiliated with Mobvoi.

## v0.2

- Simplified daily-use UI with automatic reconnect.
- Automatic workout recording and local history.
- Live time, distance, calories, average/max speed.
- Health Connect export for workout, distance and calories.
- FTMS diagnostics moved under **Mais**.
- Watch integration is planned for a later version.


## v0.6

This batch moves the project from a treadmill remote toward a local-first training hub.

- Strength-first navigation: Today, Calendar, Cardio, More.
- MSB workout execution with load, reps, RPE, notes and actual-vs-prescribed deltas.
- Automatic rest timer with FitNotes rest defaults, +30 s, skip and haptic completion.
- Recent FitNotes performance and estimated e1RM context inside the workout.
- Likely e1RM PR indication.
- Exercise reconciliation between MSB and FitNotes with >=95% auto-matching and manual accept/reject review.
- 30-day strength dashboard: training days, sets, tonnage, MSB adherence and average RPE drift.
- Video capture or attachment per set.
- Pending MSB write-back queue with safe JSON export; no private endpoint is called yet.
- Standalone free-workout mode, including FitNotes exercise autocomplete and extra sets.
- Local Training Hub execution merged back into calendar history.
- N4225 remains available under Cardio instead of dominating the app.


## v0.7 — Active 2 deep integration

- Dedicated Amazfit Active 2 Round Zepp OS companion.
- Watch-native current exercise, set number, prescribed load/reps and editable RPE.
- Complete the current set from the watch.
- Automatic rest timer on the watch with +30 s and Skip.
- Haptic feedback for set completion and rest flow.
- Live watch heart rate sent to Training Hub and stored in the canonical metrics database.
- Daily watch context import: resting HR, sleep score/duration/deep sleep, steps, stress, SpO2 and skin temperature where the device/API returns them.
- Dedicated phone bridge bound to localhost only, protected by a six-digit pairing code.
- Foreground Android bridge keeps watch control available while Training Hub is backgrounded.
- Active treadmill sessions can be controlled from the watch: ±0.5 km/h, Pause/Resume and Stop.
- No remote treadmill Start is exposed from an idle state.
- Generic source-agnostic metrics store prepared for future scale and nutrition integrations.

The Active 2 companion is built separately by the **Build Active 2 Companion** GitHub Actions workflow and produces the `TrainingHub-Active2` artifact.
