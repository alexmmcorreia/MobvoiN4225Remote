package com.local.mobvoin4225remote

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import android.net.Uri
import androidx.core.content.FileProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.time.LocalDate
import java.util.Locale
import kotlin.math.max

data class WorkoutSetPlan(
    val key: String,
    val sourceExerciseId: String,
    val groupIndex: Int,
    val setIndex: Int,
    val prescribedLoad: Double?,
    val prescribedReps: Int?,
    val prescribedRpe: Double?,
    val actualLoad: Double?,
    val actualReps: Int?,
    val actualRpe: Double?,
    val completed: Boolean,
    val comment: String?,
    val videoUri: String?,
    val heartRateBpm: Int? = null,
    val peakHeartRateBpm: Int? = null,
)

data class WorkoutExercisePlan(
    val sourceId: String,
    val name: String,
    val notes: String?,
    val instructions: String?,
    val restSeconds: Int,
    val mappedFitNotesName: String?,
    val mappingConfidence: Double?,
    val lastPerformance: String?,
    val bestRecentE1rm: Double?,
    val isFree: Boolean = false,
    val sets: List<WorkoutSetPlan>,
)

data class WorkoutDayPlan(
    val date: String,
    val program: Int?,
    val week: Int?,
    val session: Int?,
    val programInstance: Int?,
    val exercises: List<WorkoutExercisePlan>,
) {
    val totalSets: Int get() = exercises.sumOf { it.sets.size }
    val completedSets: Int get() = exercises.sumOf { ex -> ex.sets.count { it.completed } }
    val complete: Boolean get() = totalSets > 0 && completedSets == totalSets
}

data class MappingCandidate(
    val msbName: String,
    val fitNotesName: String?,
    val confidence: Double,
    val autoMapped: Boolean,
)

data class StrengthAnalytics(
    val trainingDays30: Int = 0,
    val sets30: Int = 0,
    val tonnage30: Double = 0.0,
    val adherence30Pct: Double? = null,
    val avgRpeDelta30: Double? = null,
    val pendingSyncSets: Int = 0,
)

data class StrengthExecutionState(
    val selectedDate: String = LocalDate.now().toString(),
    val workout: WorkoutDayPlan? = null,
    val autoMappedCount: Int = 0,
    val reviewMappings: List<MappingCandidate> = emptyList(),
    val analytics: StrengthAnalytics = StrengthAnalytics(),
    val knownExercises: List<String> = emptyList(),
    val restEndMs: Long? = null,
    val lastMessage: String? = null,
    val busy: Boolean = false,
)

class StrengthExecutionRepository(context: Context) {
    private val appContext = context.applicationContext
    private val db = ExecutionDb(appContext)
    private val importedDbFile: File get() = appContext.getDatabasePath("training_hub.db")
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val runtimePrefs = appContext.getSharedPreferences("strength_runtime", Context.MODE_PRIVATE)

    val state = MutableStateFlow(
        StrengthExecutionState(
            restEndMs = loadPersistedRestEnd(),
            busy = true,
        )
    )

    init {
        // Open once so lightweight schema migrations finish before other repositories read this DB.
        db.writableDatabase
        scope.launch {
            runCatching { refreshSync(LocalDate.now().toString()) }
                .onFailure { error ->
                    state.value = state.value.copy(
                        busy = false,
                        lastMessage = "Falha a carregar treino: ${error.message ?: "erro desconhecido"}",
                    )
                }
        }
    }

    suspend fun refresh(date: String = state.value.selectedDate) = withContext(Dispatchers.IO) {
        refreshSync(date)
    }

    suspend fun previousDay() = withContext(Dispatchers.IO) {
        val date = LocalDate.parse(state.value.selectedDate).minusDays(1).toString()
        refreshSync(date)
    }

    suspend fun nextDay() = withContext(Dispatchers.IO) {
        val date = LocalDate.parse(state.value.selectedDate).plusDays(1).toString()
        refreshSync(date)
    }

    suspend fun goToday() = withContext(Dispatchers.IO) {
        refreshSync(LocalDate.now().toString())
    }

    suspend fun completeSet(
        set: WorkoutSetPlan,
        weight: Double?,
        reps: Int?,
        rpe: Double?,
        comment: String?,
    ): Int = withContext(Dispatchers.IO) {
        val rest = restSecondsForExercise(set.sourceExerciseId)
        val existing = loadLocalSet(set.key)
        val values = ContentValues().apply {
            put("set_key", set.key)
            put("source_exercise_id", set.sourceExerciseId)
            put("group_index", set.groupIndex)
            put("set_index", set.setIndex)
            putNullable("weight", weight)
            putNullable("reps", reps)
            putNullable("rpe", rpe)
            putNullable("comment", comment?.takeIf { it.isNotBlank() })
            putNullable("video_uri", existing?.videoUri)
            val watchState = WatchBridgeRuntime.state.value
            val currentHr = watchState.lastHeartRateBpm?.takeIf {
                watchState.paired &&
                    watchState.lastSeenMs != null &&
                    System.currentTimeMillis() - watchState.lastSeenMs < 15_000L
            }
            putNullable("heart_rate_bpm", currentHr)
            putNullable("peak_heart_rate_bpm", currentHr)
            put("completed_at", System.currentTimeMillis())
            put("sync_state", if (set.sourceExerciseId.startsWith("free:")) "local_only" else "pending")
        }
        db.writableDatabase.insertWithOnConflict(
            "local_set",
            null,
            values,
            SQLiteDatabase.CONFLICT_REPLACE,
        )
        refreshSync(state.value.selectedDate, "Série gravada · descanso ${formatSeconds(rest)}")
        val restEnd = System.currentTimeMillis() + rest * 1000L
        persistRestEnd(restEnd)
        state.value = state.value.copy(restEndMs = restEnd)
        rest
    }

    suspend fun undoSet(setKey: String) = withContext(Dispatchers.IO) {
        db.writableDatabase.delete("local_set", "set_key=?", arrayOf(setKey))
        persistRestEnd(null)
        refreshSync(state.value.selectedDate, "Série anulada")
        state.value = state.value.copy(restEndMs = null)
    }

    suspend fun attachVideo(set: WorkoutSetPlan, uri: String) = withContext(Dispatchers.IO) {
        val current = loadLocalSet(set.key)
        val stableUri = materializeVideoUri(set.key, uri)
        val values = ContentValues().apply {
            put("set_key", set.key)
            put("source_exercise_id", set.sourceExerciseId)
            put("group_index", set.groupIndex)
            put("set_index", set.setIndex)
            putNullable("weight", current?.weight)
            putNullable("reps", current?.reps)
            putNullable("rpe", current?.rpe)
            putNullable("comment", current?.comment)
            put("video_uri", stableUri)
            putNullable("heart_rate_bpm", current?.heartRateBpm)
            putNullable("peak_heart_rate_bpm", current?.peakHeartRateBpm)
            if (current?.completedAt != null) put("completed_at", current.completedAt)
            put("sync_state", if (set.sourceExerciseId.startsWith("free:")) "local_only" else "pending")
        }
        db.writableDatabase.insertWithOnConflict(
            "local_set",
            null,
            values,
            SQLiteDatabase.CONFLICT_REPLACE,
        )
        refreshSync(state.value.selectedDate, "Vídeo associado à série")
    }

    private fun materializeVideoUri(setKey: String, rawUri: String): String {
        val uri = Uri.parse(rawUri)
        if (uri.authority == "${appContext.packageName}.fileprovider") return rawUri

        val input = appContext.contentResolver.openInputStream(uri)
            ?: error("Não foi possível abrir o vídeo selecionado.")
        val mime = appContext.contentResolver.getType(uri).orEmpty()
        val extension = when {
            "quicktime" in mime -> "mov"
            "webm" in mime -> "webm"
            else -> "mp4"
        }
        val safe = setKey.replace(Regex("[^A-Za-z0-9_-]"), "_")
        val dir = File(appContext.filesDir, "workout_videos").apply { mkdirs() }
        val target = File(dir, "imported_${System.currentTimeMillis()}_$safe.$extension")
        input.use { source ->
            target.outputStream().use { sink -> source.copyTo(sink) }
        }
        return FileProvider.getUriForFile(
            appContext,
            "${appContext.packageName}.fileprovider",
            target,
        ).toString()
    }

    suspend fun acceptSuggestedMapping(msbName: String) = withContext(Dispatchers.IO) {
        val norm = normalizeExerciseName(msbName)
        val values = ContentValues().apply { put("confirmed", 1) }
        db.writableDatabase.update("exercise_map", values, "msb_normalized=?", arrayOf(norm))
        refreshSync(state.value.selectedDate, "Correspondência confirmada")
    }

    suspend fun rejectSuggestedMapping(msbName: String) = withContext(Dispatchers.IO) {
        val norm = normalizeExerciseName(msbName)
        val values = ContentValues().apply {
            putNull("fit_exercise_id")
            putNull("fit_name")
            put("confidence", 0.0)
            put("confirmed", -1)
        }
        db.writableDatabase.update("exercise_map", values, "msb_normalized=?", arrayOf(norm))
        refreshSync(state.value.selectedDate, "Correspondência rejeitada")
    }

    fun extendRest(seconds: Int = 30) {
        val base = state.value.restEndMs ?: System.currentTimeMillis()
        val end = base + seconds * 1000L
        persistRestEnd(end)
        state.value = state.value.copy(restEndMs = end)
    }

    fun clearRest() {
        persistRestEnd(null)
        state.value = state.value.copy(restEndMs = null)
    }

    private fun loadPersistedRestEnd(): Long? {
        val value = runtimePrefs.getLong("rest_end_ms", 0L)
        return if (value > System.currentTimeMillis()) value else {
            runtimePrefs.edit().remove("rest_end_ms").apply()
            null
        }
    }

    private fun persistRestEnd(value: Long?) {
        val edit = runtimePrefs.edit()
        if (value == null) edit.remove("rest_end_ms") else edit.putLong("rest_end_ms", value)
        edit.apply()
    }

    suspend fun addFreeExercise(
        name: String,
        setCount: Int = 3,
    ) = withContext(Dispatchers.IO) {
        val clean = name.trim()
        if (clean.isBlank()) return@withContext
        val date = state.value.selectedDate
        var rest = 180
        var fitId: Int? = null
        if (importedDbFile.exists()) {
            val imported = SQLiteDatabase.openDatabase(importedDbFile.absolutePath, null, SQLiteDatabase.OPEN_READONLY)
            try {
                val norm = normalizeExerciseName(clean)
                imported.rawQuery(
                    "SELECT source_id,name,default_rest_time FROM fit_exercise",
                    null,
                ).use { cursor ->
                    var bestScore = 0.0
                    while (cursor.moveToNext()) {
                        val candidateName = cursor.getString(1)
                        val score = nameSimilarity(norm, normalizeExerciseName(candidateName))
                        if (score > bestScore) {
                            bestScore = score
                            if (score >= 0.80) {
                                fitId = cursor.getInt(0)
                                if (!cursor.isNull(2)) rest = cursor.getInt(2)
                            }
                        }
                    }
                }
            } finally {
                imported.close()
            }
        }

        val order = db.readableDatabase.rawQuery(
            "SELECT COALESCE(MAX(order_index),-1)+1 FROM free_exercise WHERE date=?",
            arrayOf(date),
        ).use { cursor -> if (cursor.moveToFirst()) cursor.getInt(0) else 0 }

        val values = ContentValues().apply {
            put("date", date)
            put("name", clean)
            putNullable("fit_exercise_id", fitId)
            put("rest_seconds", rest)
            put("order_index", order)
        }
        val id = db.writableDatabase.insert("free_exercise", null, values)
        if (id > 0) {
            repeat(setCount.coerceIn(1, 10)) { index ->
                val sv = ContentValues().apply {
                    put("free_exercise_id", id)
                    put("set_index", index)
                }
                db.writableDatabase.insert("free_set_plan", null, sv)
            }
        }
        refreshSync(date, "Exercício livre adicionado")
    }

    suspend fun addFreeSet(sourceExerciseId: String) = withContext(Dispatchers.IO) {
        val id = sourceExerciseId.removePrefix("free:").toLongOrNull() ?: return@withContext
        val next = db.readableDatabase.rawQuery(
            "SELECT COALESCE(MAX(set_index),-1)+1 FROM free_set_plan WHERE free_exercise_id=?",
            arrayOf(id.toString()),
        ).use { cursor -> if (cursor.moveToFirst()) cursor.getInt(0) else 0 }
        val values = ContentValues().apply {
            put("free_exercise_id", id)
            put("set_index", next)
        }
        db.writableDatabase.insert("free_set_plan", null, values)
        refreshSync(state.value.selectedDate, "Série adicionada")
    }

    suspend fun deleteFreeExercise(sourceExerciseId: String) = withContext(Dispatchers.IO) {
        val id = sourceExerciseId.removePrefix("free:").toLongOrNull() ?: return@withContext
        db.writableDatabase.beginTransaction()
        try {
            db.writableDatabase.delete("local_set", "source_exercise_id=?", arrayOf(sourceExerciseId))
            db.writableDatabase.delete("free_set_plan", "free_exercise_id=?", arrayOf(id.toString()))
            db.writableDatabase.delete("free_exercise", "id=?", arrayOf(id.toString()))
            db.writableDatabase.setTransactionSuccessful()
        } finally {
            db.writableDatabase.endTransaction()
        }
        refreshSync(state.value.selectedDate, "Exercício livre removido")
    }

    suspend fun exportPendingSync(): File = withContext(Dispatchers.IO) {
        val dir = File(appContext.filesDir, "exports").apply { mkdirs() }
        val file = File(dir, "msb_pending_sync_${System.currentTimeMillis()}.json")
        val arr = JSONArray()
        // Resolve plan metadata from the imported database separately.
        val imported = if (importedDbFile.exists()) {
            SQLiteDatabase.openDatabase(importedDbFile.absolutePath, null, SQLiteDatabase.OPEN_READONLY)
        } else null
        try {
            db.readableDatabase.rawQuery(
                """
                SELECT set_key,source_exercise_id,group_index,set_index,
                       weight,reps,rpe,comment,video_uri,heart_rate_bpm,peak_heart_rate_bpm,completed_at
                FROM local_set
                WHERE completed_at IS NOT NULL AND sync_state='pending'
                ORDER BY completed_at
                """.trimIndent(),
                null,
            ).use { cursor ->
                while (cursor.moveToNext()) {
                    val sourceId = cursor.getString(1)
                    var date: String? = null
                    var exercise: String? = null
                    imported?.rawQuery(
                        "SELECT date,display_name FROM planned_exercise WHERE source_id=?",
                        arrayOf(sourceId),
                    )?.use { pc ->
                        if (pc.moveToFirst()) {
                            date = pc.getString(0)
                            exercise = pc.getString(1)
                        }
                    }
                    arr.put(
                        JSONObject().apply {
                            put("setKey", cursor.getString(0))
                            put("sourceExerciseId", sourceId)
                            put("groupIndex", cursor.getInt(2))
                            put("setIndex", cursor.getInt(3))
                            putNullableJson("weight", if (cursor.isNull(4)) null else cursor.getDouble(4))
                            putNullableJson("reps", if (cursor.isNull(5)) null else cursor.getInt(5))
                            putNullableJson("rpe", if (cursor.isNull(6)) null else cursor.getDouble(6))
                            putNullableJson("comment", if (cursor.isNull(7)) null else cursor.getString(7))
                            putNullableJson("videoUri", if (cursor.isNull(8)) null else cursor.getString(8))
                            putNullableJson("heartRateBpm", if (cursor.isNull(9)) null else cursor.getInt(9))
                            putNullableJson("peakHeartRateBpm", if (cursor.isNull(10)) null else cursor.getInt(10))
                            put("completedAt", cursor.getLong(11))
                            putNullableJson("date", date)
                            putNullableJson("exercise", exercise)
                        }
                    )
                }
            }
        } finally {
            imported?.close()
        }
        val root = JSONObject().apply {
            put("schemaVersion", 1)
            put("createdAt", System.currentTimeMillis())
            put("source", "Training Hub")
            put("sets", arr)
        }
        file.writeText(root.toString(2))
        file
    }

    private fun refreshSync(date: String, message: String? = null) {
        if (!importedDbFile.exists()) {
            val workout = loadFreeOnlyWorkout(date)
            state.value = StrengthExecutionState(
                selectedDate = date,
                workout = workout,
                autoMappedCount = 0,
                reviewMappings = emptyList(),
                analytics = StrengthAnalytics(pendingSyncSets = 0),
                restEndMs = state.value.restEndMs,
                lastMessage = message ?: if (workout == null) "Sem plano importado — podes criar um treino livre." else null,
                busy = false,
            )
            return
        }

        state.value = state.value.copy(busy = true)
        val imported = SQLiteDatabase.openDatabase(
            importedDbFile.absolutePath,
            null,
            SQLiteDatabase.OPEN_READONLY,
        )
        try {
            reconcileMappings(imported)
            val workout = loadWorkout(imported, date)
            val mappings = loadMappingSummary()
            state.value = StrengthExecutionState(
                selectedDate = date,
                workout = workout,
                autoMappedCount = mappings.first,
                reviewMappings = mappings.second,
                analytics = loadAnalytics(imported),
                knownExercises = loadKnownExercises(imported),
                restEndMs = state.value.restEndMs,
                lastMessage = message,
                busy = false,
            )
        } finally {
            imported.close()
        }
    }

    private fun loadFreeOnlyWorkout(date: String): WorkoutDayPlan? {
        val exercises = mutableListOf<WorkoutExercisePlan>()
        db.readableDatabase.rawQuery(
            """
            SELECT id,name,rest_seconds
            FROM free_exercise
            WHERE date=?
            ORDER BY order_index
            """.trimIndent(),
            arrayOf(date),
        ).use { fc ->
            while (fc.moveToNext()) {
                val freeId = fc.getLong(0)
                val name = fc.getString(1)
                val rest = fc.getInt(2)
                val sourceId = "free:$freeId"
                val sets = mutableListOf<WorkoutSetPlan>()
                db.readableDatabase.rawQuery(
                    """
                    SELECT id,set_index,load,reps,rpe
                    FROM free_set_plan
                    WHERE free_exercise_id=?
                    ORDER BY set_index
                    """.trimIndent(),
                    arrayOf(freeId.toString()),
                ).use { sc ->
                    while (sc.moveToNext()) {
                        val planId = sc.getLong(0)
                        val setIndex = sc.getInt(1)
                        val key = "free:$freeId:set:$planId"
                        val local = loadLocalSet(key)
                        sets += WorkoutSetPlan(
                            key = key,
                            sourceExerciseId = sourceId,
                            groupIndex = 0,
                            setIndex = setIndex,
                            prescribedLoad = if (sc.isNull(2)) null else sc.getDouble(2),
                            prescribedReps = if (sc.isNull(3)) null else sc.getInt(3),
                            prescribedRpe = if (sc.isNull(4)) null else sc.getDouble(4),
                            actualLoad = local?.weight,
                            actualReps = local?.reps,
                            actualRpe = local?.rpe,
                            completed = local?.completedAt != null,
                            comment = local?.comment,
                            videoUri = local?.videoUri,
                            heartRateBpm = local?.heartRateBpm,
                            peakHeartRateBpm = local?.peakHeartRateBpm,
                        )
                    }
                }
                exercises += WorkoutExercisePlan(
                    sourceId = sourceId,
                    name = name,
                    notes = null,
                    instructions = null,
                    restSeconds = rest,
                    mappedFitNotesName = null,
                    mappingConfidence = null,
                    lastPerformance = null,
                    bestRecentE1rm = null,
                    isFree = true,
                    sets = sets,
                )
            }
        }
        return if (exercises.isEmpty()) null else WorkoutDayPlan(
            date = date,
            program = null,
            week = null,
            session = null,
            programInstance = null,
            exercises = exercises,
        )
    }

    private fun loadWorkout(imported: SQLiteDatabase, date: String): WorkoutDayPlan? {
        val exercises = mutableListOf<WorkoutExercisePlan>()
        var program: Int? = null
        var week: Int? = null
        var session: Int? = null
        var programInstance: Int? = null
        imported.rawQuery(
            """
            SELECT source_id,display_name,notes,instructions,
                   program,program_week,program_session,program_instance
            FROM planned_exercise
            WHERE date=?
            ORDER BY order_index
            """.trimIndent(),
            arrayOf(date),
        ).use { c ->
            while (c.moveToNext()) {
                val sourceId = c.getString(0)
                val name = c.getString(1)
                val notes = if (c.isNull(2)) null else c.getString(2)
                val instructions = if (c.isNull(3)) null else c.getString(3)
                if (program == null && !c.isNull(4)) program = c.getInt(4)
                if (week == null && !c.isNull(5)) week = c.getInt(5)
                if (session == null && !c.isNull(6)) session = c.getInt(6)
                if (programInstance == null && !c.isNull(7)) programInstance = c.getInt(7)
                val mapping = mappingForName(normalizeExerciseName(name))
                val rest = mapping?.fitExerciseId?.let { fitId ->
                    imported.rawQuery(
                        "SELECT default_rest_time FROM fit_exercise WHERE source_id=?",
                        arrayOf(fitId.toString()),
                    ).use { rc ->
                        if (rc.moveToFirst() && !rc.isNull(0)) rc.getInt(0) else null
                    }
                } ?: 180

                val sets = mutableListOf<WorkoutSetPlan>()
                imported.rawQuery(
                    """
                    SELECT group_index,set_count,reps,rpe,load
                    FROM planned_set_group
                    WHERE source_exercise_id=?
                    ORDER BY group_index
                    """.trimIndent(),
                    arrayOf(sourceId),
                ).use { sc ->
                    while (sc.moveToNext()) {
                        val group = sc.getInt(0)
                        val count = if (sc.isNull(1)) 1 else max(1, sc.getInt(1))
                        val reps = if (sc.isNull(2)) null else sc.getInt(2)
                        val rpe = if (sc.isNull(3)) null else sc.getDouble(3)
                        val load = if (sc.isNull(4)) null else sc.getDouble(4)
                        repeat(count) { setIndex ->
                            val key = "$sourceId:$group:$setIndex"
                            val local = loadLocalSet(key)
                            sets += WorkoutSetPlan(
                                key = key,
                                sourceExerciseId = sourceId,
                                groupIndex = group,
                                setIndex = setIndex,
                                prescribedLoad = load,
                                prescribedReps = reps,
                                prescribedRpe = rpe,
                                actualLoad = local?.weight,
                                actualReps = local?.reps,
                                actualRpe = local?.rpe,
                                completed = local?.completedAt != null,
                                comment = local?.comment,
                                videoUri = local?.videoUri,
                            )
                        }
                    }
                }

                val recent = mapping?.fitName?.let { fitName ->
                    loadRecentPerformance(imported, fitName, date)
                }

                exercises += WorkoutExercisePlan(
                    sourceId = sourceId,
                    name = name,
                    notes = notes,
                    instructions = instructions,
                    restSeconds = rest,
                    mappedFitNotesName = mapping?.fitName,
                    mappingConfidence = mapping?.confidence,
                    lastPerformance = recent?.first,
                    bestRecentE1rm = recent?.second,
                    sets = sets,
                )
            }
        }
        db.readableDatabase.rawQuery(
            """
            SELECT id,name,fit_exercise_id,rest_seconds
            FROM free_exercise
            WHERE date=?
            ORDER BY order_index
            """.trimIndent(),
            arrayOf(date),
        ).use { fc ->
            while (fc.moveToNext()) {
                val freeId = fc.getLong(0)
                val name = fc.getString(1)
                val sourceId = "free:$freeId"
                val fitId = if (fc.isNull(2)) null else fc.getInt(2)
                val rest = fc.getInt(3)
                var fitName: String? = null
                if (fitId != null) {
                    imported.rawQuery(
                        "SELECT name FROM fit_exercise WHERE source_id=?",
                        arrayOf(fitId.toString()),
                    ).use { nc -> if (nc.moveToFirst()) fitName = nc.getString(0) }
                }
                val recent = fitName?.let { loadRecentPerformance(imported, it, date) }
                val sets = mutableListOf<WorkoutSetPlan>()
                db.readableDatabase.rawQuery(
                    """
                    SELECT id,set_index,load,reps,rpe
                    FROM free_set_plan
                    WHERE free_exercise_id=?
                    ORDER BY set_index
                    """.trimIndent(),
                    arrayOf(freeId.toString()),
                ).use { sc ->
                    while (sc.moveToNext()) {
                        val planId = sc.getLong(0)
                        val setIndex = sc.getInt(1)
                        val key = "free:$freeId:set:$planId"
                        val local = loadLocalSet(key)
                        sets += WorkoutSetPlan(
                            key = key,
                            sourceExerciseId = sourceId,
                            groupIndex = 0,
                            setIndex = setIndex,
                            prescribedLoad = if (sc.isNull(2)) null else sc.getDouble(2),
                            prescribedReps = if (sc.isNull(3)) null else sc.getInt(3),
                            prescribedRpe = if (sc.isNull(4)) null else sc.getDouble(4),
                            actualLoad = local?.weight,
                            actualReps = local?.reps,
                            actualRpe = local?.rpe,
                            completed = local?.completedAt != null,
                            comment = local?.comment,
                            videoUri = local?.videoUri,
                            heartRateBpm = local?.heartRateBpm,
                            peakHeartRateBpm = local?.peakHeartRateBpm,
                        )
                    }
                }
                exercises += WorkoutExercisePlan(
                    sourceId = sourceId,
                    name = name,
                    notes = null,
                    instructions = null,
                    restSeconds = rest,
                    mappedFitNotesName = fitName,
                    mappingConfidence = if (fitName != null) 1.0 else null,
                    lastPerformance = recent?.first,
                    bestRecentE1rm = recent?.second,
                    isFree = true,
                    sets = sets,
                )
            }
        }

        return if (exercises.isEmpty()) null else WorkoutDayPlan(
            date = date,
            program = program,
            week = week,
            session = session,
            programInstance = programInstance,
            exercises = exercises,
        )
    }

    private fun restSecondsForExercise(sourceExerciseId: String): Int {
        if (sourceExerciseId.startsWith("free:")) {
            val id = sourceExerciseId.removePrefix("free:").toLongOrNull() ?: return 180
            return db.readableDatabase.rawQuery(
                "SELECT rest_seconds FROM free_exercise WHERE id=?",
                arrayOf(id.toString()),
            ).use { cursor -> if (cursor.moveToFirst()) cursor.getInt(0) else 180 }
        }
        if (!importedDbFile.exists()) return 180
        val imported = SQLiteDatabase.openDatabase(
            importedDbFile.absolutePath,
            null,
            SQLiteDatabase.OPEN_READONLY,
        )
        return try {
            var name: String? = null
            imported.rawQuery(
                "SELECT display_name FROM planned_exercise WHERE source_id=?",
                arrayOf(sourceExerciseId),
            ).use { c -> if (c.moveToFirst()) name = c.getString(0) }
            val mapping = name?.let { mappingForName(normalizeExerciseName(it)) }
            mapping?.fitExerciseId?.let { fitId ->
                imported.rawQuery(
                    "SELECT default_rest_time FROM fit_exercise WHERE source_id=?",
                    arrayOf(fitId.toString()),
                ).use { c ->
                    if (c.moveToFirst() && !c.isNull(0)) c.getInt(0) else 180
                }
            } ?: 180
        } finally {
            imported.close()
        }
    }

    private fun reconcileMappings(imported: SQLiteDatabase) {
        val existing = mutableSetOf<String>()
        db.readableDatabase.rawQuery("SELECT msb_normalized FROM exercise_map", null).use { c ->
            while (c.moveToNext()) existing += c.getString(0)
        }

        val fitExercises = mutableListOf<FitCandidate>()
        imported.rawQuery(
            "SELECT source_id,name,default_rest_time FROM fit_exercise",
            null,
        ).use { c ->
            while (c.moveToNext()) {
                fitExercises += FitCandidate(
                    id = c.getInt(0),
                    name = c.getString(1),
                    normalized = normalizeExerciseName(c.getString(1)),
                )
            }
        }

        val msbNames = linkedSetOf<String>()
        imported.rawQuery("SELECT DISTINCT display_name FROM planned_exercise", null).use { c ->
            while (c.moveToNext()) msbNames += c.getString(0)
        }

        val msbDates = loadDateSets(imported, "MSB")
        val fitDates = loadDateSets(imported, "FITNOTES")

        for (name in msbNames) {
            val norm = normalizeExerciseName(name)
            if (norm in existing) continue

            var best: FitCandidate? = null
            var bestScore = 0.0
            for (fit in fitExercises) {
                val nameScore = nameSimilarity(norm, fit.normalized)
                if (nameScore < 0.35) continue
                val overlap = dateOverlap(msbDates[name].orEmpty(), fitDates[fit.name].orEmpty())
                val score = when {
                    nameScore >= 0.999 -> 1.0
                    overlap > 0.0 -> (nameScore * 0.72 + overlap * 0.28).coerceAtMost(0.99)
                    else -> nameScore * 0.9
                }
                if (score > bestScore) {
                    bestScore = score
                    best = fit
                }
            }

            val values = ContentValues().apply {
                put("msb_normalized", norm)
                put("msb_display", name)
                putNullable("fit_exercise_id", best?.id)
                putNullable("fit_name", best?.name)
                put("confidence", bestScore)
                put("confirmed", if (bestScore >= 0.95) 1 else 0)
            }
            db.writableDatabase.insertWithOnConflict(
                "exercise_map",
                null,
                values,
                SQLiteDatabase.CONFLICT_IGNORE,
            )
        }
    }

    private fun loadDateSets(imported: SQLiteDatabase, source: String): Map<String, Set<String>> {
        val out = mutableMapOf<String, MutableSet<String>>()
        imported.rawQuery(
            "SELECT exercise_name,date FROM performed_set WHERE source=?",
            arrayOf(source),
        ).use { c ->
            while (c.moveToNext()) {
                out.getOrPut(c.getString(0)) { linkedSetOf() } += c.getString(1)
            }
        }
        return out
    }

    private fun loadMappingSummary(): Pair<Int, List<MappingCandidate>> {
        var auto = 0
        val review = mutableListOf<MappingCandidate>()
        db.readableDatabase.rawQuery(
            """
            SELECT msb_display,fit_name,confidence,confirmed
            FROM exercise_map
            ORDER BY confidence DESC,msb_display
            """.trimIndent(),
            null,
        ).use { c ->
            while (c.moveToNext()) {
                val confirmedValue = c.getInt(3)
                if (confirmedValue == 1) auto++
                else if (confirmedValue == 0) {
                    review += MappingCandidate(
                        msbName = c.getString(0),
                        fitNotesName = if (c.isNull(1)) null else c.getString(1),
                        confidence = c.getDouble(2),
                        autoMapped = false,
                    )
                }
            }
        }
        return auto to review.take(20)
    }

    private fun mappingForName(normalized: String): MappingRow? {
        db.readableDatabase.rawQuery(
            """
            SELECT fit_exercise_id,fit_name,confidence,confirmed
            FROM exercise_map
            WHERE msb_normalized=?
            """.trimIndent(),
            arrayOf(normalized),
        ).use { c ->
            if (!c.moveToFirst()) return null
            if (c.isNull(0)) return null
            return MappingRow(
                fitExerciseId = c.getInt(0),
                fitName = if (c.isNull(1)) null else c.getString(1),
                confidence = c.getDouble(2),
                confirmed = c.getInt(3) == 1,
            )
        }
    }

    private fun loadRecentPerformance(
        imported: SQLiteDatabase,
        fitName: String,
        beforeDate: String,
    ): Pair<String, Double?>? {
        var lastDate: String? = null
        val recentSets = mutableListOf<Triple<Double?, Int?, String>>()
        imported.rawQuery(
            """
            SELECT date,weight,reps
            FROM performed_set
            WHERE source='FITNOTES' AND exercise_name=? AND date<?
            ORDER BY date DESC,set_index DESC
            LIMIT 12
            """.trimIndent(),
            arrayOf(fitName, beforeDate),
        ).use { c ->
            while (c.moveToNext()) {
                val date = c.getString(0)
                if (lastDate == null) lastDate = date
                if (date == lastDate) {
                    recentSets += Triple(
                        if (c.isNull(1)) null else c.getDouble(1),
                        if (c.isNull(2)) null else c.getInt(2),
                        date,
                    )
                }
            }
        }
        if (recentSets.isEmpty()) return null
        val setText = recentSets.reversed().joinToString(" · ") { (w, reps, _) ->
            when {
                w != null && reps != null -> "${formatCompact(w)}×$reps"
                reps != null -> "×$reps"
                else -> "—"
            }
        }
        var best: Double? = null
        imported.rawQuery(
            """
            SELECT weight,reps
            FROM performed_set
            WHERE source='FITNOTES' AND exercise_name=? AND date<?
              AND weight IS NOT NULL AND reps IS NOT NULL
            ORDER BY date DESC
            LIMIT 120
            """.trimIndent(),
            arrayOf(fitName, beforeDate),
        ).use { c ->
            while (c.moveToNext()) {
                val w = c.getDouble(0)
                val reps = c.getInt(1)
                if (w > 0 && reps > 0 && reps <= 12) {
                    val e = w * (1.0 + reps / 30.0)
                    if (best == null || e > best!!) best = e
                }
            }
        }
        return "Última vez ($lastDate): $setText" to best
    }

    private fun loadKnownExercises(imported: SQLiteDatabase): List<String> {
        val names = mutableListOf<String>()
        imported.rawQuery(
            """
            SELECT name
            FROM fit_exercise
            ORDER BY favourite DESC,name COLLATE NOCASE
            """.trimIndent(),
            null,
        ).use { cursor ->
            while (cursor.moveToNext()) names += cursor.getString(0)
        }
        return names.distinct()
    }

    private fun loadAnalytics(imported: SQLiteDatabase): StrengthAnalytics {
        val cutoff = LocalDate.now().minusDays(29).toString()
        var days = 0
        var sets = 0
        var tonnage = 0.0
        imported.rawQuery(
            """
            SELECT COUNT(DISTINCT date),COUNT(*),
                   COALESCE(SUM(CASE WHEN weight IS NOT NULL AND reps IS NOT NULL
                                     THEN weight*reps ELSE 0 END),0)
            FROM performed_set
            WHERE source='FITNOTES' AND date>=?
            """.trimIndent(),
            arrayOf(cutoff),
        ).use { c ->
            if (c.moveToFirst()) {
                days = c.getInt(0)
                sets = c.getInt(1)
                tonnage = c.getDouble(2)
            }
        }
        var prescribedSets = 0
        imported.rawQuery(
            """
            SELECT psg.set_count
            FROM planned_set_group psg
            JOIN planned_exercise pe ON pe.source_id=psg.source_exercise_id
            WHERE pe.date>=?
            """.trimIndent(),
            arrayOf(cutoff),
        ).use { cursor ->
            while (cursor.moveToNext()) prescribedSets += if (cursor.isNull(0)) 1 else max(1, cursor.getInt(0))
        }
        val msbActualSets = imported.rawQuery(
            "SELECT COUNT(*) FROM performed_set WHERE source='MSB' AND date>=?",
            arrayOf(cutoff),
        ).use { cursor -> if (cursor.moveToFirst()) cursor.getInt(0) else 0 }
        val adherence = if (prescribedSets > 0) msbActualSets * 100.0 / prescribedSets else null

        val plannedRpe = mutableMapOf<String, Double>()
        imported.rawQuery(
            """
            SELECT psg.source_exercise_id,psg.group_index,psg.rpe
            FROM planned_set_group psg
            JOIN planned_exercise pe ON pe.source_id=psg.source_exercise_id
            WHERE pe.date>=? AND psg.rpe IS NOT NULL
            """.trimIndent(),
            arrayOf(cutoff),
        ).use { cursor ->
            while (cursor.moveToNext()) {
                plannedRpe["${cursor.getString(0)}:${cursor.getInt(1)}"] = cursor.getDouble(2)
            }
        }
        var deltaSum = 0.0
        var deltaCount = 0
        imported.rawQuery(
            """
            SELECT source_key,rpe
            FROM performed_set
            WHERE source='MSB' AND date>=? AND rpe IS NOT NULL
            """.trimIndent(),
            arrayOf(cutoff),
        ).use { cursor ->
            while (cursor.moveToNext()) {
                val parts = cursor.getString(0).split(":")
                if (parts.size >= 3) {
                    val key = "${parts[0]}:${parts[1]}"
                    val target = plannedRpe[key]
                    if (target != null) {
                        deltaSum += cursor.getDouble(1) - target
                        deltaCount++
                    }
                }
            }
        }
        val avgRpeDelta = if (deltaCount > 0) deltaSum / deltaCount else null

        val pending = db.readableDatabase.rawQuery(
            "SELECT COUNT(*) FROM local_set WHERE completed_at IS NOT NULL AND sync_state='pending'",
            null,
        ).use { c -> if (c.moveToFirst()) c.getInt(0) else 0 }
        return StrengthAnalytics(
            trainingDays30 = days,
            sets30 = sets,
            tonnage30 = tonnage,
            adherence30Pct = adherence,
            avgRpeDelta30 = avgRpeDelta,
            pendingSyncSets = pending,
        )
    }

    private fun loadLocalSet(key: String): LocalSetRow? {
        db.readableDatabase.rawQuery(
            """
            SELECT weight,reps,rpe,comment,video_uri,heart_rate_bpm,peak_heart_rate_bpm,completed_at
            FROM local_set WHERE set_key=?
            """.trimIndent(),
            arrayOf(key),
        ).use { c ->
            if (!c.moveToFirst()) return null
            return LocalSetRow(
                weight = if (c.isNull(0)) null else c.getDouble(0),
                reps = if (c.isNull(1)) null else c.getInt(1),
                rpe = if (c.isNull(2)) null else c.getDouble(2),
                comment = if (c.isNull(3)) null else c.getString(3),
                videoUri = if (c.isNull(4)) null else c.getString(4),
                heartRateBpm = if (c.isNull(5)) null else c.getInt(5),
                peakHeartRateBpm = if (c.isNull(6)) null else c.getInt(6),
                completedAt = if (c.isNull(7)) null else c.getLong(7),
            )
        }
    }

    private data class FitCandidate(val id: Int, val name: String, val normalized: String)
    private data class MappingRow(
        val fitExerciseId: Int,
        val fitName: String?,
        val confidence: Double,
        val confirmed: Boolean,
    )
    private data class LocalSetRow(
        val weight: Double?,
        val reps: Int?,
        val rpe: Double?,
        val comment: String?,
        val videoUri: String?,
        val heartRateBpm: Int?,
        val peakHeartRateBpm: Int?,
        val completedAt: Long?,
    )
}

private class ExecutionDb(context: Context) :
    SQLiteOpenHelper(context, "strength_execution.db", null, 3) {

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE exercise_map(
                msb_normalized TEXT PRIMARY KEY,
                msb_display TEXT NOT NULL,
                fit_exercise_id INTEGER,
                fit_name TEXT,
                confidence REAL NOT NULL DEFAULT 0,
                confirmed INTEGER NOT NULL DEFAULT 0
            )
            """.trimIndent()
        )
        db.execSQL(
            """
            CREATE TABLE local_set(
                set_key TEXT PRIMARY KEY,
                source_exercise_id TEXT NOT NULL,
                group_index INTEGER NOT NULL,
                set_index INTEGER NOT NULL,
                weight REAL,
                reps INTEGER,
                rpe REAL,
                comment TEXT,
                video_uri TEXT,
                heart_rate_bpm INTEGER,
                peak_heart_rate_bpm INTEGER,
                completed_at INTEGER,
                sync_state TEXT NOT NULL DEFAULT 'pending'
            )
            """.trimIndent()
        )
        createFreeTables(db)
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        if (oldVersion < 2) createFreeTables(db)
        if (oldVersion < 3) {
            db.execSQL("ALTER TABLE local_set ADD COLUMN heart_rate_bpm INTEGER")
            db.execSQL("ALTER TABLE local_set ADD COLUMN peak_heart_rate_bpm INTEGER")
        }
    }

    private fun createFreeTables(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS free_exercise(
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                date TEXT NOT NULL,
                name TEXT NOT NULL,
                fit_exercise_id INTEGER,
                rest_seconds INTEGER NOT NULL DEFAULT 180,
                order_index INTEGER NOT NULL DEFAULT 0
            )
            """.trimIndent()
        )
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS free_set_plan(
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                free_exercise_id INTEGER NOT NULL,
                set_index INTEGER NOT NULL,
                load REAL,
                reps INTEGER,
                rpe REAL
            )
            """.trimIndent()
        )
    }
}

internal fun normalizeExerciseName(input: String): String {
    var s = input.lowercase(Locale.ROOT)
        .replace("&", " and ")
        .replace(Regex("[^a-z0-9]+"), " ")
        .trim()

    val replacements = listOf(
        Regex("""\bdl\b""") to "deadlift",
        Regex("""\bdead lift\b""") to "deadlift",
        Regex("""\bcomp\b""") to "competition",
        Regex("""\bpause\b""") to "paused",
        Regex("""\bhb\b""") to "high bar",
        Regex("""\blb\b""") to "low bar",
        Regex("""\boh\b""") to "overhead",
        Regex("""\btriceps\b""") to "tricep",
        Regex("""\bextensions\b""") to "extension",
        Regex("""\brows\b""") to "row",
        Regex("""\bcurls\b""") to "curl",
    )
    for ((regex, value) in replacements) s = s.replace(regex, value)

    return s.split(Regex("""\s+"""))
        .filter { it.isNotBlank() }
        .joinToString(" ")
}

internal fun nameSimilarity(a: String, b: String): Double {
    if (a == b) return 1.0
    val ta = a.split(" ").filter { it.isNotBlank() }.toSet()
    val tb = b.split(" ").filter { it.isNotBlank() }.toSet()
    if (ta.isEmpty() || tb.isEmpty()) return 0.0

    val intersection = ta.intersect(tb).size.toDouble()
    val union = ta.union(tb).size.toDouble()
    var jaccard = intersection / union

    val primary = listOf("squat", "bench", "deadlift")
    val pa = primary.firstOrNull { it in ta }
    val pb = primary.firstOrNull { it in tb }
    if (pa != null && pb != null && pa != pb) return 0.0
    if (pa != null && pa == pb) jaccard = (jaccard + 0.18).coerceAtMost(1.0)

    return jaccard
}

internal fun dateOverlap(a: Set<String>, b: Set<String>): Double {
    if (a.isEmpty() || b.isEmpty()) return 0.0
    val intersection = a.intersect(b).size.toDouble()
    val minSize = minOf(a.size, b.size).toDouble()
    return if (minSize == 0.0) 0.0 else (intersection / minSize).coerceAtMost(1.0)
}

private fun formatSeconds(seconds: Int): String {
    val m = seconds / 60
    val s = seconds % 60
    return "%d:%02d".format(m, s)
}

private fun ContentValues.putNullable(key: String, value: Int?) {
    if (value == null) putNull(key) else put(key, value)
}

private fun ContentValues.putNullable(key: String, value: Double?) {
    if (value == null || value.isNaN()) putNull(key) else put(key, value)
}

private fun ContentValues.putNullable(key: String, value: String?) {
    if (value == null) putNull(key) else put(key, value)
}


private fun formatCompact(value: Double): String =
    if (value % 1.0 == 0.0) value.toInt().toString()
    else "%.1f".format(Locale.US, value)


private fun JSONObject.putNullableJson(key: String, value: Any?) {
    if (value == null) put(key, JSONObject.NULL) else put(key, value)
}
