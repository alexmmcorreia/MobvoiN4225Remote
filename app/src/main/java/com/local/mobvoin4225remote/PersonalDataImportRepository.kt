package com.local.mobvoin4225remote

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.InputStream
import java.time.LocalDate
import java.time.ZoneId

data class PersonalImportState(
    val statuses: List<IntegrationImportStatus> = emptyList(),
    val busy: Boolean = false,
    val lastMessage: String? = null,
)

data class CanonicalPersonalData(
    val source: String,
    val body: List<CanonicalBodyRecord>,
    val nutrition: List<CanonicalNutritionRecord>,
)

data class CanonicalBodyRecord(
    val timestampMs: Long,
    val weightKg: Double?,
    val bodyFatPercent: Double?,
    val muscleMassKg: Double?,
    val visceralFatIndex: Double?,
    val rawJson: String,
)

data class CanonicalNutritionRecord(
    val date: String,
    val caloriesKcal: Double?,
    val proteinG: Double?,
    val carbsG: Double?,
    val fatG: Double?,
    val fiberG: Double?,
    val expenditureKcal: Double?,
    val targetKcal: Double?,
    val weightTrendKg: Double?,
    val rawJson: String,
)

interface PersonalDataAdapter {
    val id: String
    fun parse(input: InputStream): CanonicalPersonalData
}

class CanonicalPersonalJsonAdapter : PersonalDataAdapter {
    override val id: String = "TRAINING_HUB_CANONICAL_JSON"

    override fun parse(input: InputStream): CanonicalPersonalData {
        val root = JSONObject(input.bufferedReader().use { it.readText() })
        require(root.optInt("schemaVersion", 0) == 1) {
            "JSON pessoal inválido: é esperado schemaVersion 1."
        }
        val source = root.optString("source").trim().ifBlank { "UNKNOWN" }

        val body = buildList {
            val arr = root.optJSONArray("bodyMeasurements") ?: return@buildList
            for (i in 0 until arr.length()) {
                val item = arr.optJSONObject(i) ?: continue
                val timestamp = when {
                    item.has("timestampMs") -> item.optLong("timestampMs", 0L)
                    item.has("date") -> runCatching {
                        LocalDate.parse(item.getString("date"))
                            .atStartOfDay(ZoneId.systemDefault())
                            .toInstant().toEpochMilli()
                    }.getOrDefault(0L)
                    else -> 0L
                }
                if (timestamp <= 0L) continue
                add(
                    CanonicalBodyRecord(
                        timestampMs = timestamp,
                        weightKg = item.optNullableDouble("weightKg"),
                        bodyFatPercent = item.optNullableDouble("bodyFatPercent"),
                        muscleMassKg = item.optNullableDouble("muscleMassKg"),
                        visceralFatIndex = item.optNullableDouble("visceralFatIndex"),
                        rawJson = item.toString(),
                    )
                )
            }
        }

        val nutrition = buildList {
            val arr = root.optJSONArray("nutritionDays") ?: return@buildList
            for (i in 0 until arr.length()) {
                val item = arr.optJSONObject(i) ?: continue
                val date = item.optString("date").takeIf {
                    runCatching { LocalDate.parse(it) }.isSuccess
                } ?: continue
                add(
                    CanonicalNutritionRecord(
                        date = date,
                        caloriesKcal = item.optNullableDouble("caloriesKcal"),
                        proteinG = item.optNullableDouble("proteinG"),
                        carbsG = item.optNullableDouble("carbsG"),
                        fatG = item.optNullableDouble("fatG"),
                        fiberG = item.optNullableDouble("fiberG"),
                        expenditureKcal = item.optNullableDouble("expenditureKcal"),
                        targetKcal = item.optNullableDouble("targetKcal"),
                        weightTrendKg = item.optNullableDouble("weightTrendKg"),
                        rawJson = item.toString(),
                    )
                )
            }
        }

        require(body.isNotEmpty() || nutrition.isNotEmpty()) {
            "O ficheiro não contém bodyMeasurements nem nutritionDays."
        }
        return CanonicalPersonalData(source, body, nutrition)
    }
}

class PersonalIntegrationRepository(context: Context) {
    private val store = PersonalMetricsStore(context.applicationContext)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val adapters: List<PersonalDataAdapter> = listOf(CanonicalPersonalJsonAdapter())

    val state = MutableStateFlow(PersonalImportState())

    init {
        scope.launch { refresh() }
    }

    suspend fun refresh() = withContext(Dispatchers.IO) {
        state.value = state.value.copy(statuses = store.integrationStatuses(), busy = false)
    }

    suspend fun importCanonical(input: InputStream) = withContext(Dispatchers.IO) {
        state.value = state.value.copy(busy = true, lastMessage = "A importar dados pessoais…")
        val result = runCatching {
            val parsed = adapters.first().parse(input)
            parsed.body.forEach {
                store.saveBodyMeasurement(
                    BodyMeasurement(
                        source = parsed.source,
                        timestampMs = it.timestampMs,
                        weightKg = it.weightKg,
                        bodyFatPercent = it.bodyFatPercent,
                        muscleMassKg = it.muscleMassKg,
                        visceralFatIndex = it.visceralFatIndex,
                    ),
                    rawJson = it.rawJson,
                )
            }
            parsed.nutrition.forEach {
                store.saveNutritionDay(
                    NutritionDay(
                        source = parsed.source,
                        date = it.date,
                        caloriesKcal = it.caloriesKcal,
                        proteinG = it.proteinG,
                        carbsG = it.carbsG,
                        fatG = it.fatG,
                        fiberG = it.fiberG,
                        expenditureKcal = it.expenditureKcal,
                        targetKcal = it.targetKcal,
                        weightTrendKg = it.weightTrendKg,
                    ),
                    rawJson = it.rawJson,
                )
            }
            if (parsed.body.isNotEmpty()) {
                store.recordIntegrationImport(
                    source = parsed.source,
                    kind = "BODY",
                    records = parsed.body.size,
                )
            }
            if (parsed.nutrition.isNotEmpty()) {
                store.recordIntegrationImport(
                    source = parsed.source,
                    kind = "NUTRITION",
                    records = parsed.nutrition.size,
                )
            }
            "Importado de ${parsed.source}: ${parsed.body.size} medições corporais · ${parsed.nutrition.size} dias de nutrição"
        }

        state.value = if (result.isSuccess) {
            PersonalImportState(
                statuses = store.integrationStatuses(),
                busy = false,
                lastMessage = result.getOrThrow(),
            )
        } else {
            PersonalImportState(
                statuses = store.integrationStatuses(),
                busy = false,
                lastMessage = "Falha na importação: ${result.exceptionOrNull()?.message ?: "erro desconhecido"}",
            )
        }
    }
}

private fun JSONObject.optNullableDouble(key: String): Double? {
    if (!has(key) || isNull(key)) return null
    return when (val value = opt(key)) {
        is Number -> value.toDouble()
        is String -> value.replace(",", ".").toDoubleOrNull()
        else -> null
    }
}
