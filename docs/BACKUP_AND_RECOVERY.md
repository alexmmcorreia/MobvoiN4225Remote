# Training Hub backup and recovery

Training Hub is designed to become the primary local training log, so local data must not depend on a cloud service remaining available.

## Full backup

The app can export a ZIP containing:

- imported MyStrengthBook/FitNotes canonical database
- Training Hub local workout execution
- exercise mappings
- Active 2 metrics and daily context
- body-weight/body-composition records
- nutrition records
- treadmill session history
- app-owned workout videos

The backup deliberately excludes:

- Active 2 pairing code
- saved Bluetooth addresses
- other device/session secrets

## Restore

Restore is two-phase:

1. inspect and validate the backup before changing data
2. attempt a safety snapshot of the current app state
3. restore known tables inside SQLite transactions
4. run SQLite `PRAGMA quick_check`
5. restore app-owned workout videos

Unknown future tables/columns are not blindly written into an older schema.

A failed safety snapshot does not prevent recovery from a valid backup, which is important if the current database itself is damaged.

## Automatic safety snapshots

Before operations that replace imported source data, Training Hub creates a rolling safety snapshot:

- MyStrengthBook re-import
- FitNotes re-import
- backup restore

The app keeps the five most recent automatic safety snapshots.

## Database migrations

Imported training history now follows a non-destructive migration policy. Future schema changes must use explicit migrations; upgrading the app must not drop historical tables.

## Video durability

Videos selected from another Android provider are copied into Training Hub-owned storage before their URI is recorded. This means their association does not depend on an external temporary document permission.

App-owned set videos are included in full backups.
