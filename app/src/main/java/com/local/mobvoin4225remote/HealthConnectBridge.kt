package com.local.mobvoin4225remote

import android.content.Context
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.permission.HealthPermission
import androidx.health.connect.client.records.DistanceRecord
import androidx.health.connect.client.records.ExerciseSessionRecord
import androidx.health.connect.client.records.Record
import androidx.health.connect.client.records.TotalCaloriesBurnedRecord
import androidx.health.connect.client.records.metadata.Metadata
import androidx.health.connect.client.units.Energy
import androidx.health.connect.client.units.Length
import java.time.Instant
import java.time.ZoneId

class HealthConnectBridge(private val context: Context) {
    val permissions: Set<String> = setOf(
        HealthPermission.getWritePermission(ExerciseSessionRecord::class),
        HealthPermission.getWritePermission(DistanceRecord::class),
        HealthPermission.getWritePermission(TotalCaloriesBurnedRecord::class),
    )

    fun isAvailable(): Boolean =
        HealthConnectClient.getSdkStatus(context) == HealthConnectClient.SDK_AVAILABLE

    suspend fun hasPermissions(): Boolean {
        if (!isAvailable()) return false
        val client = HealthConnectClient.getOrCreate(context)
        return client.permissionController.getGrantedPermissions().containsAll(permissions)
    }

    suspend fun export(session: WorkoutSession): Result<Unit> = runCatching {
        check(isAvailable()) { "Health Connect indisponível neste dispositivo." }

        val client = HealthConnectClient.getOrCreate(context)
        val granted = client.permissionController.getGrantedPermissions()
        check(granted.containsAll(permissions)) { "Faltam permissões do Health Connect." }

        val start = Instant.ofEpochMilli(session.startedAtMs)
        val end = Instant.ofEpochMilli(session.endedAtMs.coerceAtLeast(session.startedAtMs + 1000L))
        val zone = ZoneId.systemDefault()
        val startOffset = zone.rules.getOffset(start)
        val endOffset = zone.rules.getOffset(end)

        val records = buildList<Record> {
            add(
                ExerciseSessionRecord(
                    startTime = start,
                    startZoneOffset = startOffset,
                    endTime = end,
                    endZoneOffset = endOffset,
                    exerciseType = ExerciseSessionRecord.EXERCISE_TYPE_WALKING,
                    title = "Caminhada na passadeira",
                    metadata = Metadata.manualEntry(),
                )
            )

            if (session.distanceKm > 0.0) {
                add(
                    DistanceRecord(
                        distance = Length.kilometers(session.distanceKm),
                        startTime = start,
                        startZoneOffset = startOffset,
                        endTime = end,
                        endZoneOffset = endOffset,
                        metadata = Metadata.manualEntry(),
                    )
                )
            }

            session.caloriesKcal?.takeIf { it > 0 }?.let { kcal ->
                add(
                    TotalCaloriesBurnedRecord(
                        energy = Energy.kilocalories(kcal.toDouble()),
                        startTime = start,
                        startZoneOffset = startOffset,
                        endTime = end,
                        endZoneOffset = endOffset,
                        metadata = Metadata.manualEntry(),
                    )
                )
            }
        }

        client.insertRecords(records)
    }
}
