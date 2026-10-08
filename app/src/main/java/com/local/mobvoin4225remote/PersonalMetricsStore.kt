package com.local.mobvoin4225remote

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import org.json.JSONObject
import java.time.LocalDate

data class BodyMeasurement(
    val source: String,
    val timestampMs: Long,
    val weightKg: Double?,
    val bodyFatPercent: Double? = null,
    val muscleMassKg: Double? = null,
    val visceralFatIndex: Double? = null,
)

data class NutritionDay(
    val source: String,
    val date: String,
    val caloriesKcal: Double?,
    val proteinG: Double?,
    val carbsG: Double?,
    val fatG: Double?,
    val fiberG: Double? = null,
    val expenditureKcal: Double? = null,
    val targetKcal: Double? = null,
    val weightTrendKg: Double? = null,
)

data class DailyBodyContext(
    val date: String,
    val restingHeartRate: Int? = null,
    val sleepScore: Int? = null,
    val sleepMinutes: Int? = null,
    val deepSleepMinutes: Int? = null,
    val steps: Int? = null,
    val stress: Int? = null,
    val spo2Percent: Int? = null,
    val skinTemperatureC: Double? = null,
    val syncedAtMs: Long = 0L,
)

class PersonalMetricsStore(context: Context) :
    SQLiteOpenHelper(context.applicationContext, "personal_metrics.db", null, 2) {

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE metric_sample(
                source TEXT NOT NULL,
                metric TEXT NOT NULL,
                timestamp_ms INTEGER NOT NULL,
                day TEXT NOT NULL,
                value REAL,
                unit TEXT,
                raw_json TEXT,
                PRIMARY KEY(source,metric,timestamp_ms)
            )
            """.trimIndent()
        )
        db.execSQL("CREATE INDEX idx_metric_sample_day ON metric_sample(day)")
        db.execSQL(
            """
            CREATE TABLE daily_context(
                source TEXT NOT NULL,
                day TEXT NOT NULL,
                resting_hr INTEGER,
                sleep_score INTEGER,
                sleep_minutes INTEGER,
                deep_sleep_minutes INTEGER,
                steps INTEGER,
                stress INTEGER,
                spo2 INTEGER,
                skin_temp_c REAL,
                synced_at_ms INTEGER NOT NULL,
                raw_json TEXT,
                PRIMARY KEY(source,day)
            )
            """.trimIndent()
        )
        createFutureSourceTables(db)
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        if (oldVersion < 2) createFutureSourceTables(db)
    }

    private fun createFutureSourceTables(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS body_measurement(
                source TEXT NOT NULL,
                timestamp_ms INTEGER NOT NULL,
                day TEXT NOT NULL,
                weight_kg REAL,
                body_fat_percent REAL,
                muscle_mass_kg REAL,
                visceral_fat_index REAL,
                raw_json TEXT,
                PRIMARY KEY(source,timestamp_ms)
            )
            """.trimIndent()
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_body_measurement_day ON body_measurement(day)")
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS nutrition_day(
                source TEXT NOT NULL,
                day TEXT NOT NULL,
                calories_kcal REAL,
                protein_g REAL,
                carbs_g REAL,
                fat_g REAL,
                fiber_g REAL,
                expenditure_kcal REAL,
                target_kcal REAL,
                weight_trend_kg REAL,
                raw_json TEXT,
                PRIMARY KEY(source,day)
            )
            """.trimIndent()
        )
    }

    @Synchronized
    fun saveBodyMeasurement(
        measurement: BodyMeasurement,
        rawJson: String? = null,
    ) {
        val day = java.time.Instant.ofEpochMilli(measurement.timestampMs)
            .atZone(java.time.ZoneId.systemDefault())
            .toLocalDate()
            .toString()
        val values = ContentValues().apply {
            put("source", measurement.source)
            put("timestamp_ms", measurement.timestampMs)
            put("day", day)
            putNullable("weight_kg", measurement.weightKg)
            putNullable("body_fat_percent", measurement.bodyFatPercent)
            putNullable("muscle_mass_kg", measurement.muscleMassKg)
            putNullable("visceral_fat_index", measurement.visceralFatIndex)
            if (rawJson == null) putNull("raw_json") else put("raw_json", rawJson)
        }
        writableDatabase.insertWithOnConflict(
            "body_measurement",
            null,
            values,
            SQLiteDatabase.CONFLICT_REPLACE,
        )
        measurement.weightKg?.let {
            recordMetric(
                source = measurement.source,
                metric = "body_weight",
                value = it,
                unit = "kg",
                timestampMs = measurement.timestampMs,
                rawJson = rawJson,
            )
        }
    }

    @Synchronized
    fun saveNutritionDay(
        nutrition: NutritionDay,
        rawJson: String? = null,
    ) {
        val values = ContentValues().apply {
            put("source", nutrition.source)
            put("day", nutrition.date)
            putNullable("calories_kcal", nutrition.caloriesKcal)
            putNullable("protein_g", nutrition.proteinG)
            putNullable("carbs_g", nutrition.carbsG)
            putNullable("fat_g", nutrition.fatG)
            putNullable("fiber_g", nutrition.fiberG)
            putNullable("expenditure_kcal", nutrition.expenditureKcal)
            putNullable("target_kcal", nutrition.targetKcal)
            putNullable("weight_trend_kg", nutrition.weightTrendKg)
            if (rawJson == null) putNull("raw_json") else put("raw_json", rawJson)
        }
        writableDatabase.insertWithOnConflict(
            "nutrition_day",
            null,
            values,
            SQLiteDatabase.CONFLICT_REPLACE,
        )
    }

    @Synchronized
    fun recordMetric(
        source: String,
        metric: String,
        value: Double?,
        unit: String?,
        timestampMs: Long = System.currentTimeMillis(),
        rawJson: String? = null,
    ) {
        val metricDay = java.time.Instant.ofEpochMilli(timestampMs)
            .atZone(java.time.ZoneId.systemDefault())
            .toLocalDate()
            .toString()
        val values = ContentValues().apply {
            put("source", source)
            put("metric", metric)
            put("timestamp_ms", timestampMs)
            put("day", metricDay)
            if (value == null) putNull("value") else put("value", value)
            if (unit == null) putNull("unit") else put("unit", unit)
            if (rawJson == null) putNull("raw_json") else put("raw_json", rawJson)
        }
        writableDatabase.insertWithOnConflict(
            "metric_sample",
            null,
            values,
            SQLiteDatabase.CONFLICT_REPLACE,
        )
    }

    @Synchronized
    fun saveWatchDaily(payload: JSONObject): DailyBodyContext {
        val date = payload.optString("date").takeIf { it.isNotBlank() } ?: LocalDate.now().toString()
        val context = DailyBodyContext(
            date = date,
            restingHeartRate = payload.optNullableInt("restingHeartRate"),
            sleepScore = payload.optNullableInt("sleepScore"),
            sleepMinutes = payload.optNullableInt("sleepMinutes"),
            deepSleepMinutes = payload.optNullableInt("deepSleepMinutes"),
            steps = payload.optNullableInt("steps"),
            stress = payload.optNullableInt("stress"),
            spo2Percent = payload.optNullableInt("spo2Percent"),
            skinTemperatureC = payload.optNullableDouble("skinTemperatureC"),
            syncedAtMs = System.currentTimeMillis(),
        )
        val values = ContentValues().apply {
            put("source", "AMAZFIT_ACTIVE_2")
            put("day", context.date)
            putNullable("resting_hr", context.restingHeartRate)
            putNullable("sleep_score", context.sleepScore)
            putNullable("sleep_minutes", context.sleepMinutes)
            putNullable("deep_sleep_minutes", context.deepSleepMinutes)
            putNullable("steps", context.steps)
            putNullable("stress", context.stress)
            putNullable("spo2", context.spo2Percent)
            putNullable("skin_temp_c", context.skinTemperatureC)
            put("synced_at_ms", context.syncedAtMs)
            put("raw_json", payload.toString())
        }
        writableDatabase.insertWithOnConflict(
            "daily_context",
            null,
            values,
            SQLiteDatabase.CONFLICT_REPLACE,
        )
        return context
    }

    @Synchronized
    fun latestWatchDaily(): DailyBodyContext? {
        readableDatabase.rawQuery(
            """
            SELECT day,resting_hr,sleep_score,sleep_minutes,deep_sleep_minutes,
                   steps,stress,spo2,skin_temp_c,synced_at_ms
            FROM daily_context
            WHERE source='AMAZFIT_ACTIVE_2'
            ORDER BY day DESC,synced_at_ms DESC
            LIMIT 1
            """.trimIndent(),
            null,
        ).use { c ->
            if (!c.moveToFirst()) return null
            return DailyBodyContext(
                date = c.getString(0),
                restingHeartRate = if (c.isNull(1)) null else c.getInt(1),
                sleepScore = if (c.isNull(2)) null else c.getInt(2),
                sleepMinutes = if (c.isNull(3)) null else c.getInt(3),
                deepSleepMinutes = if (c.isNull(4)) null else c.getInt(4),
                steps = if (c.isNull(5)) null else c.getInt(5),
                stress = if (c.isNull(6)) null else c.getInt(6),
                spo2Percent = if (c.isNull(7)) null else c.getInt(7),
                skinTemperatureC = if (c.isNull(8)) null else c.getDouble(8),
                syncedAtMs = c.getLong(9),
            )
        }
    }
}

private fun ContentValues.putNullable(key: String, value: Int?) {
    if (value == null) putNull(key) else put(key, value)
}

private fun ContentValues.putNullable(key: String, value: Double?) {
    if (value == null) putNull(key) else put(key, value)
}

private fun JSONObject.optNullableInt(key: String): Int? =
    if (!has(key) || isNull(key)) null else optInt(key)

private fun JSONObject.optNullableDouble(key: String): Double? =
    if (!has(key) || isNull(key)) null else optDouble(key)
