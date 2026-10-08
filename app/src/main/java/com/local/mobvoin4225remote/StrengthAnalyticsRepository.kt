package com.local.mobvoin4225remote

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.LocalDate
import java.time.temporal.WeekFields
import java.util.Locale
import kotlin.math.max

data class StrengthOverview(
    val firstDate: String? = null,
    val lastDate: String? = null,
    val trainingDays: Int = 0,
    val sets: Int = 0,
    val exercises: Int = 0,
    val tonnageKg: Double = 0.0,
    val sets30: Int = 0,
    val tonnage30Kg: Double = 0.0,
)

data class ExerciseAnalyticsSummary(
    val name: String,
    val category: String?,
    val firstDate: String,
    val lastDate: String,
    val trainingDays: Int,
    val sets: Int,
    val totalReps: Int,
    val tonnageKg: Double,
    val maxWeightKg: Double?,
    val bestE1rmKg: Double?,
    val bestE1rm30Kg: Double?,
    val sets30: Int,
    val tonnage30Kg: Double,
    val exposure80: Int,
    val exposure85: Int,
    val exposure90: Int,
    val latestRpe: Double?,
    val prCount: Int,
)

data class ExerciseTrendPoint(
    val date: String,
    val sets: Int,
    val volumeKg: Double,
    val topWeightKg: Double?,
    val bestE1rmKg: Double?,
    val averageRpe: Double?,
)

data class WeeklyStrengthPoint(
    val week: String,
    val sets: Int,
    val tonnageKg: Double,
    val trainingDays: Int,
)

data class StrengthPrEvent(
    val date: String,
    val exercise: String,
    val e1rmKg: Double,
    val previousE1rmKg: Double,
    val deltaKg: Double,
)

data class StrengthAnalyticsViewState(
    val overview: StrengthOverview = StrengthOverview(),
    val exercises: List<ExerciseAnalyticsSummary> = emptyList(),
    val weekly: List<WeeklyStrengthPoint> = emptyList(),
    val recentPrs: List<StrengthPrEvent> = emptyList(),
    val selectedExercise: String? = null,
    val selectedSummary: ExerciseAnalyticsSummary? = null,
    val selectedTrend: List<ExerciseTrendPoint> = emptyList(),
    val sourceNote: String = "",
    val busy: Boolean = false,
    val error: String? = null,
)

class StrengthAnalyticsRepository(context: Context) {
    private val appContext = context.applicationContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    val state = MutableStateFlow(StrengthAnalyticsViewState(busy = true))

    init {
        scope.launch { refresh() }
    }

    suspend fun refresh() = withContext(Dispatchers.IO) {
        val selected = state.value.selectedExercise
        state.value = state.value.copy(busy = true, error = null)
        runCatching { load() }
            .onSuccess { loaded ->
                state.value = if (selected != null) {
                    loaded.copy(
                        selectedExercise = selected,
                        selectedSummary = loaded.exercises.firstOrNull { it.name == selected },
                        selectedTrend = buildTrend(loadCanonicalSets().filter { it.exercise == selected })
                            .takeLast(40).reversed(),
                    )
                } else loaded
            }
            .onFailure { error ->
                state.value = state.value.copy(
                    busy = false,
                    error = error.message ?: "Falha a calcular analytics",
                )
            }
    }

    fun selectExercise(name: String?) {
        if (name.isNullOrBlank()) {
            state.value = state.value.copy(
                selectedExercise = null,
                selectedSummary = null,
                selectedTrend = emptyList(),
            )
            return
        }
        scope.launch {
            val summary = state.value.exercises.firstOrNull { it.name == name } ?: return@launch
            val trend = withContext(Dispatchers.IO) {
                buildTrend(loadCanonicalSets().filter { it.exercise == name }).takeLast(40).reversed()
            }
            state.value = state.value.copy(
                selectedExercise = name,
                selectedSummary = summary,
                selectedTrend = trend,
            )
        }
    }

    private fun load(): StrengthAnalyticsViewState {
        val sets = loadCanonicalSets()
        if (sets.isEmpty()) {
            return StrengthAnalyticsViewState(
                busy = false,
                sourceNote = "Sem execução histórica suficiente. Importa FitNotes ou regista treino no Training Hub.",
            )
        }

        val cutoff30 = LocalDate.now().minusDays(29).toString()
        val summaries = sets.groupBy { it.exercise }.map { (name, exerciseSets) ->
            buildSummary(name, exerciseSets, cutoff30)
        }.sortedWith(
            compareByDescending<ExerciseAnalyticsSummary> { it.sets }
                .thenBy { it.name.lowercase(Locale.ROOT) }
        )

        val dates = sets.map { it.date }.distinct().sorted()
        val overview = StrengthOverview(
            firstDate = dates.firstOrNull(),
            lastDate = dates.lastOrNull(),
            trainingDays = dates.size,
            sets = sets.size,
            exercises = summaries.size,
            tonnageKg = sets.sumOf { it.volume },
            sets30 = sets.count { it.date >= cutoff30 },
            tonnage30Kg = sets.filter { it.date >= cutoff30 }.sumOf { it.volume },
        )

        return StrengthAnalyticsViewState(
            overview = overview,
            exercises = summaries,
            weekly = buildWeekly(sets),
            recentPrs = buildRecentPrs(sets),
            sourceNote = "Histórico: FitNotes é a fonte principal; séries locais do Training Hub substituem o mesmo exercício/dia quando existirem. MSB é usado para plano, não é contado outra vez como execução histórica.",
            busy = false,
        )
    }

    private fun loadCanonicalSets(): List<CanonicalSet> {
        val importedFile = appContext.getDatabasePath("training_hub.db")
        val executionFile = appContext.getDatabasePath("strength_execution.db")
        if (!importedFile.exists() && !executionFile.exists()) return emptyList()

        val imported = if (importedFile.exists()) {
            SQLiteDatabase.openDatabase(importedFile.absolutePath, null, SQLiteDatabase.OPEN_READONLY)
        } else null
        val execution = if (executionFile.exists()) {
            SQLiteDatabase.openDatabase(executionFile.absolutePath, null, SQLiteDatabase.OPEN_READONLY)
        } else null

        try {
            val out = mutableListOf<CanonicalSet>()
            val fitDayExercise = mutableSetOf<String>()

            imported?.rawQuery(
                """
                SELECT date,exercise_name,category,weight,reps,rpe
                FROM performed_set
                WHERE source='FITNOTES'
                ORDER BY date,set_index
                """.trimIndent(),
                null,
            )?.use { cursor ->
                while (cursor.moveToNext()) {
                    val date = cursor.getString(0)
                    val exercise = cursor.getString(1)
                    fitDayExercise += "$date|$exercise"
                    out += CanonicalSet(
                        source = "FITNOTES",
                        date = date,
                        exercise = exercise,
                        category = if (cursor.isNull(2)) null else cursor.getString(2),
                        weight = if (cursor.isNull(3)) null else cursor.getDouble(3),
                        reps = if (cursor.isNull(4)) null else cursor.getInt(4),
                        rpe = if (cursor.isNull(5)) null else cursor.getDouble(5),
                    )
                }
            }

            if (execution != null) {
                val mapping = mutableMapOf<String, String>()
                execution.rawQuery(
                    "SELECT msb_normalized,fit_name FROM exercise_map WHERE fit_name IS NOT NULL AND confirmed=1",
                    null,
                ).use { cursor ->
                    while (cursor.moveToNext()) mapping[cursor.getString(0)] = cursor.getString(1)
                }

                val planned = mutableMapOf<String, Pair<String, String>>()
                imported?.rawQuery(
                    "SELECT source_id,date,display_name FROM planned_exercise",
                    null,
                )?.use { cursor ->
                    while (cursor.moveToNext()) {
                        planned[cursor.getString(0)] = cursor.getString(1) to cursor.getString(2)
                    }
                }

                val fitCategory = mutableMapOf<String, String?>()
                imported?.rawQuery(
                    "SELECT name,category FROM fit_exercise",
                    null,
                )?.use { cursor ->
                    while (cursor.moveToNext()) {
                        fitCategory[cursor.getString(0)] =
                            if (cursor.isNull(1)) null else cursor.getString(1)
                    }
                }

                val free = mutableMapOf<String, Pair<String, String>>()
                execution.rawQuery("SELECT id,date,name FROM free_exercise", null).use { cursor ->
                    while (cursor.moveToNext()) {
                        free["free:${cursor.getLong(0)}"] = cursor.getString(1) to cursor.getString(2)
                    }
                }

                execution.rawQuery(
                    """
                    SELECT source_exercise_id,weight,reps,rpe,completed_at
                    FROM local_set
                    WHERE completed_at IS NOT NULL
                    ORDER BY completed_at
                    """.trimIndent(),
                    null,
                ).use { cursor ->
                    while (cursor.moveToNext()) {
                        val sourceId = cursor.getString(0)
                        val base = if (sourceId.startsWith("free:")) free[sourceId] else planned[sourceId]
                        val date = base?.first ?: continue
                        val originalName = base.second
                        val canonicalName = if (sourceId.startsWith("free:")) {
                            originalName
                        } else {
                            mapping[normalizeExerciseName(originalName)] ?: originalName
                        }
                        if ("$date|$canonicalName" in fitDayExercise) continue

                        out += CanonicalSet(
                            source = "TRAINING_HUB",
                            date = date,
                            exercise = canonicalName,
                            category = fitCategory[canonicalName],
                            weight = if (cursor.isNull(1)) null else cursor.getDouble(1),
                            reps = if (cursor.isNull(2)) null else cursor.getInt(2),
                            rpe = if (cursor.isNull(3)) null else cursor.getDouble(3),
                        )
                    }
                }
            }

            return out.sortedBy { it.date }
        } finally {
            execution?.close()
            imported?.close()
        }
    }

    private fun buildSummary(
        name: String,
        sets: List<CanonicalSet>,
        cutoff30: String,
    ): ExerciseAnalyticsSummary {
        val sorted = sets.sortedBy { it.date }
        val e1rms = sorted.mapNotNull { it.e1rm }
        val best = e1rms.maxOrNull()
        val latestRpe = sorted.asReversed().firstNotNullOfOrNull { it.rpe }
        var runningBest = 0.0
        var prs = 0
        sorted.groupBy { it.date }.toSortedMap().forEach { (_, daySets) ->
            val dayBest = daySets.mapNotNull { it.e1rm }.maxOrNull() ?: return@forEach
            if (dayBest > runningBest + 0.1) {
                runningBest = dayBest
                prs++
            }
        }

        fun exposure(threshold: Double): Int {
            val denominator = best ?: return 0
            if (denominator <= 0.0) return 0
            return sorted.count {
                val w = it.weight ?: return@count false
                w / denominator >= threshold
            }
        }

        val recent = sorted.filter { it.date >= cutoff30 }
        return ExerciseAnalyticsSummary(
            name = name,
            category = sorted.firstNotNullOfOrNull { it.category },
            firstDate = sorted.first().date,
            lastDate = sorted.last().date,
            trainingDays = sorted.map { it.date }.distinct().size,
            sets = sorted.size,
            totalReps = sorted.sumOf { it.reps ?: 0 },
            tonnageKg = sorted.sumOf { it.volume },
            maxWeightKg = sorted.mapNotNull { it.weight }.maxOrNull(),
            bestE1rmKg = best,
            bestE1rm30Kg = recent.mapNotNull { it.e1rm }.maxOrNull(),
            sets30 = recent.size,
            tonnage30Kg = recent.sumOf { it.volume },
            exposure80 = exposure(0.80),
            exposure85 = exposure(0.85),
            exposure90 = exposure(0.90),
            latestRpe = latestRpe,
            prCount = prs,
        )
    }

    private fun buildTrend(sets: List<CanonicalSet>): List<ExerciseTrendPoint> =
        sets.groupBy { it.date }.toSortedMap().map { (date, daySets) ->
            val rpes = daySets.mapNotNull { it.rpe }
            ExerciseTrendPoint(
                date = date,
                sets = daySets.size,
                volumeKg = daySets.sumOf { it.volume },
                topWeightKg = daySets.mapNotNull { it.weight }.maxOrNull(),
                bestE1rmKg = daySets.mapNotNull { it.e1rm }.maxOrNull(),
                averageRpe = if (rpes.isEmpty()) null else rpes.average(),
            )
        }

    private fun buildRecentPrs(sets: List<CanonicalSet>): List<StrengthPrEvent> {
        val events = mutableListOf<StrengthPrEvent>()
        sets.groupBy { it.exercise }.forEach { (exercise, exerciseSets) ->
            var best = 0.0
            exerciseSets.groupBy { it.date }.toSortedMap().forEach { (date, daySets) ->
                val dayBest = daySets.mapNotNull { it.e1rm }.maxOrNull() ?: return@forEach
                if (best > 0.0 && dayBest > best + 0.1) {
                    events += StrengthPrEvent(
                        date = date,
                        exercise = exercise,
                        e1rmKg = dayBest,
                        previousE1rmKg = best,
                        deltaKg = dayBest - best,
                    )
                }
                if (dayBest > best) best = dayBest
            }
        }
        return events.sortedByDescending { it.date }.take(20)
    }

    private fun buildWeekly(sets: List<CanonicalSet>): List<WeeklyStrengthPoint> {
        val weekFields = WeekFields.ISO
        return sets.groupBy { set ->
            val date = LocalDate.parse(set.date)
            val year = date.get(weekFields.weekBasedYear())
            val week = date.get(weekFields.weekOfWeekBasedYear())
            "%04d-W%02d".format(Locale.US, year, week)
        }.toSortedMap().map { (week, weekSets) ->
            WeeklyStrengthPoint(
                week = week,
                sets = weekSets.size,
                tonnageKg = weekSets.sumOf { it.volume },
                trainingDays = weekSets.map { it.date }.distinct().size,
            )
        }.takeLast(16)
    }

    private data class CanonicalSet(
        val source: String,
        val date: String,
        val exercise: String,
        val category: String?,
        val weight: Double?,
        val reps: Int?,
        val rpe: Double?,
    ) {
        val volume: Double get() = max(0.0, weight ?: 0.0) * max(0, reps ?: 0)
        val e1rm: Double?
            get() {
                val w = weight ?: return null
                val repsDone = reps ?: return null
                if (w <= 0.0 || repsDone !in 1..12) return null
                val rir = rpe?.let { (10.0 - it).coerceIn(0.0, 5.0) } ?: 0.0
                return w * (1.0 + (repsDone + rir) / 30.0)
            }
    }
}
