# Mobvoi N4225 Remote

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
