# Training Hub — product decisions

## Product role

The Android app is the primary training hub.

- MyStrengthBook (MSB) is the source of coach programming.
- Training Hub is the preferred execution/logging interface.
- FitNotes is the historical source and fallback during migration.
- N4225 is a secondary cardio integration.
- Amazfit Active 2 is a companion sensor/control surface.
- Health Connect is optional interoperability, not a core dependency.

## Confirmed behaviour

- MSB plan should ultimately sync automatically; file import remains the safe fallback.
- Performed sets may differ from the prescription without confirmation friction.
- Completing a set saves it, starts the rest timer and advances the workout flow.
- Rest defaults come from the mapped FitNotes exercise; fallback is 180 seconds.
- Exercise matching may auto-confirm only at >=95% confidence.
- Video flow is explicit: "film next set" or attach an existing video.
- MSB write-back should batch at exercise level once its private endpoints are validated.
- Active 2 v1 scope: live HR, rest timer, current/next set and quick actions.
- v1 analytics: e1RM, PRs, top sets, volume/tonnage, intensity context, actual vs prescribed RPE and adherence.

## Canonical execution model

A performed set is stored independently of the MSB and FitNotes imports:

- source exercise id
- prescription group + set index
- actual load
- reps
- RPE
- comment
- video URI
- completion timestamp
- sync state

This keeps local logging usable even if an external service is temporarily unavailable.

## Data precedence

For historical strength execution, FitNotes is treated as the primary historical log where both sources contain duplicate sessions.

MSB remains authoritative for the planned workout and coach programming.

Training Hub local sets are authoritative for sessions performed in Training Hub.

## MSB write-back safety

Automatic write-back stays disabled until a real authenticated MSB write request has been captured and validated.

Until then:

- local sets are marked `pending`
- a JSON payload can be exported for inspection
- no private MSB endpoint is called by Training Hub

## Watch roadmap

### Phase 1
- Bluetooth Heart Rate Service when Active 2 exposes HR broadcast
- live HR in workout
- session HR average/max

### Phase 2 — Zepp OS companion
- current exercise / set
- prescription
- rest countdown
- HR
- Done / +30 sec / Skip
- treadmill quick controls

### Phase 3 — useful daily context
Import only metrics that improve training context:
- resting HR
- sleep duration/score where API access permits
- stress where useful
- steps/activity
- workout sessions recorded on the watch

Avoid cluttering the main UI with every watch metric.

## Deferred

- advanced set types (cluster, rest-pause, drop sets)
- video bar-speed estimation
- automatic readiness-based load changes
- advanced HRV/recovery recommendations
- fully automatic MSB authentication/sync
