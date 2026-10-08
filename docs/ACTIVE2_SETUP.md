# Amazfit Active 2 Round — setup

Training Hub uses a dedicated Zepp OS companion plus a local Android bridge.

## What is implemented

The watch companion can:

- show the current strength exercise and set
- show prescribed load and reps
- adjust RPE in 0.5 steps
- complete the current set
- show the rest timer
- add 30 seconds or skip rest
- vibrate when rest finishes
- show the next exercise
- send live heart rate to Training Hub
- sync useful daily context: resting HR, sleep score and duration, deep sleep, steps, stress, SpO2 and skin temperature when available
- control an already-running N4225 treadmill session: speed ±0.5 km/h, pause/resume and stop

For safety, the watch does not expose remote treadmill START from an idle treadmill.

## Install Android v0.7.1

1. Open GitHub Actions.
2. Open the newest successful **Build Android APK** run.
3. Download the **MobvoiN4225Remote-debug** artifact.
4. Extract and install the APK.
5. Open Training Hub > **Mais > Active 2 · integração profunda**.
6. Tap **Ativar bridge**.
7. Note the six-digit pairing code.

## Install the Active 2 companion

1. In the Zepp mobile app, enable Developer Mode.
2. Open GitHub Actions.
3. Open the newest successful **Build Active 2 Companion** run.
4. Download the **TrainingHub-Active2** artifact.
5. Extract the generated .zab file.
6. Install the package on the Active 2 using the Zepp developer installation flow.
7. In the Mini App settings in Zepp, enter the six-digit pairing code shown by Training Hub.
8. Open **Training Hub** on the watch.

The first physical-device test should confirm that the Zepp App Side Service can reach the Android localhost bridge on the user's phone. The package and Android app compile successfully, but this transport path still requires real-device validation.

## Data architecture

Watch data is stored locally in the source-agnostic personal metrics database. The same database already contains canonical tables for future:

- body-weight / body-composition sources
- nutrition and MacroFactor daily data

This avoids tying Training Hub's internal model to Zepp, a particular scale brand, or MacroFactor.
