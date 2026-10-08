# Amazfit Active 2 Round integration

## Architecture

The watch does not talk to a cloud service.

```
Active 2 Device App
    ⇅ Zepp ZML
Zepp app-side service on Android
    ⇅ localhost HTTP
Training Hub WatchBridgeService
    ⇅
StrengthExecutionRepository / TreadmillController / PersonalMetricsStore
```

The Android bridge binds only to `127.0.0.1:18765`. Requests that control or read Training Hub state require the six-digit pairing code shown in **Training Hub → Mais → Active 2**.

## Strength controls on the watch

The current companion shows:

- current exercise
- current set / total sets
- load and reps from the Training Hub plan
- live heart rate
- RPE quick adjustment
- complete-set action
- rest countdown
- +30 seconds
- skip rest
- workout progress

Completing a set uses the same Android repository as the phone UI, so the local set is still queued for future MSB write-back exactly once.

## Treadmill controls

When an N4225 session is already active, the watch switches to a cardio control view with:

- current and target speed
- distance
- elapsed time
- ±0.5 km/h
- Pause / Resume
- Stop

Starting an idle treadmill from the watch is deliberately not exposed.

## Watch health/context data

When the Mini App is opened, it sends the health/context values available through the Zepp APIs to the Android canonical metrics store:

- continuous heart rate while the Mini App is active
- resting heart rate
- sleep score
- total sleep minutes
- deep-sleep minutes
- steps
- current stress
- latest SpO2 result
- latest body-surface temperature

Missing or unsupported values are left null rather than inferred.

## Pairing / first test

1. Install the current Training Hub Android APK.
2. Open **Mais → Active 2 · integração profunda**.
3. Tap **Ativar bridge** and note the six-digit pairing code.
4. Install the `TrainingHub-Active2` ZAB on the Active 2 Round through the Zepp developer/sideload flow.
5. In the Mini App settings inside Zepp, enter the same pairing code.
6. Open Training Hub on the watch.
7. Confirm the phone card changes to **Ligado à Mini App Zepp**.
8. Test first with a throw-away/free workout set before using it for a real programmed set.
9. Test treadmill controls only with an already-running, empty/controlled belt and remain next to the physical stop/power controls.

## Future sources

`PersonalMetricsStore` is not watch-specific. Future integrations should write canonical samples using a distinct source name, for example:

- `AMAZFIT_ACTIVE_2`
- `SCALE_<provider>`
- `MACROFACTOR`

This allows body weight, weight trend, nutrition/macros, expenditure and wearable context to share one time-series layer without making the training model depend on any one vendor.
