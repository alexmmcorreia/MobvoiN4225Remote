package com.local.mobvoin4225remote

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

data class WorkoutSession(
    val id: Long,
    val startedAtMs: Long,
    val endedAtMs: Long,
    val durationSec: Int,
    val distanceKm: Double,
    val averageSpeedKmh: Double,
    val maxSpeedKmh: Double,
    val caloriesKcal: Int?,
    val averageHeartRateBpm: Int?,
    val maxHeartRateBpm: Int?,
)

class SessionStore(context: Context) {
    private val file = File(context.filesDir, "treadmill_sessions.json")

    fun load(): List<WorkoutSession> = runCatching {
        if (!file.exists()) return emptyList()
        val array = JSONArray(file.readText())
        buildList {
            for (i in 0 until array.length()) {
                val o = array.getJSONObject(i)
                add(
                    WorkoutSession(
                        id = o.getLong("id"),
                        startedAtMs = o.getLong("startedAtMs"),
                        endedAtMs = o.getLong("endedAtMs"),
                        durationSec = o.getInt("durationSec"),
                        distanceKm = o.getDouble("distanceKm"),
                        averageSpeedKmh = o.getDouble("averageSpeedKmh"),
                        maxSpeedKmh = o.getDouble("maxSpeedKmh"),
                        caloriesKcal = o.optIntOrNull("caloriesKcal"),
                        averageHeartRateBpm = o.optIntOrNull("averageHeartRateBpm"),
                        maxHeartRateBpm = o.optIntOrNull("maxHeartRateBpm"),
                    )
                )
            }
        }.sortedByDescending { it.startedAtMs }
    }.getOrDefault(emptyList())

    fun save(sessions: List<WorkoutSession>) {
        val array = JSONArray()
        sessions.sortedByDescending { it.startedAtMs }.forEach { s ->
            array.put(
                JSONObject().apply {
                    put("id", s.id)
                    put("startedAtMs", s.startedAtMs)
                    put("endedAtMs", s.endedAtMs)
                    put("durationSec", s.durationSec)
                    put("distanceKm", s.distanceKm)
                    put("averageSpeedKmh", s.averageSpeedKmh)
                    put("maxSpeedKmh", s.maxSpeedKmh)
                    putNullable("caloriesKcal", s.caloriesKcal)
                    putNullable("averageHeartRateBpm", s.averageHeartRateBpm)
                    putNullable("maxHeartRateBpm", s.maxHeartRateBpm)
                }
            )
        }
        file.writeText(array.toString())
    }

    private fun JSONObject.optIntOrNull(key: String): Int? =
        if (!has(key) || isNull(key)) null else getInt(key)

    private fun JSONObject.putNullable(key: String, value: Int?) {
        if (value == null) put(key, JSONObject.NULL) else put(key, value)
    }
}
