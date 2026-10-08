# Training Hub personal data interchange

Training Hub keeps body-composition and nutrition data in source-agnostic canonical tables.

The goal is to let scale vendors, MacroFactor exports, future APIs, CSV files or other apps map into one internal model without changing the training database.

## Canonical JSON v1

```json
{
  "schemaVersion": 1,
  "source": "EXAMPLE_SOURCE",
  "bodyMeasurements": [
    {
      "timestampMs": 1791450000000,
      "weightKg": 90.2,
      "bodyFatPercent": 17.4,
      "muscleMassKg": 69.8,
      "visceralFatIndex": 7
    }
  ],
  "nutritionDays": [
    {
      "date": "2026-10-08",
      "caloriesKcal": 2875,
      "proteinG": 188,
      "carbsG": 315,
      "fatG": 82,
      "fiberG": 31,
      "expenditureKcal": 3010,
      "targetKcal": 2900,
      "weightTrendKg": 90.0
    }
  ]
}
```

All fields except `schemaVersion`, `source` and each record's date/timestamp are nullable.

## Canonical body model

- source
- timestamp
- weight
- body-fat percentage
- muscle mass
- visceral-fat index

A future scale adapter may expose only weight, or a larger set of measurements. Unsupported fields remain in raw source data until there is a reason to promote them into the canonical schema.

## Canonical nutrition model

- source
- day
- calories
- protein
- carbohydrates
- fat
- fibre
- estimated expenditure
- calorie target
- weight trend

This covers the metrics most likely to be useful for joining training performance with energy intake and body-weight trends without coupling the app to MacroFactor's export format.

## Adapter policy

Source-specific parsing lives behind an adapter. Adapters convert vendor data into canonical records and should not leak vendor field names into workout code.

Planned adapters include:

- MacroFactor export/API adapter, depending on what supported export surface is available when implemented
- one or more scale adapters once the actual scale/vendor is known
- generic CSV mapping for simple weight logs

The canonical JSON importer already works and acts as the contract for future adapters.

## Privacy

Personal-health data stays local unless the user explicitly exports or shares it.

The Training Hub backup includes canonical body/nutrition records but excludes pairing codes and saved BLE addresses.
