package com.local.mobvoin4225remote

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.util.zip.ZipInputStream

data class SourceImportSummary(
    val imported: Boolean = false,
    val label: String = "",
    val details: String = "",
)

data class StrengthDaySummary(
    val date: String,
    val plannedExercises: Int,
    val plannedSetGroups: Int,
    val msbActualSets: Int,
    val fitNotesSets: Int,
    val exerciseNames: List<String>,
) {
    val hasPlan: Boolean get() = plannedExercises > 0
    val hasActual: Boolean get() = msbActualSets > 0 || fitNotesSets > 0
}

data class StrengthDataState(
    val msb: SourceImportSummary = SourceImportSummary(),
    val fitNotes: SourceImportSummary = SourceImportSummary(),
    val days: List<StrengthDaySummary> = emptyList(),
    val lastMessage: String? = null,
    val busy: Boolean = false,
)

class TrainingRepository(context: Context) {
    private val appContext = context.applicationContext
    private val store = TrainingDataStore(appContext)
    val state = MutableStateFlow(loadState())

    suspend fun importMsb(input: InputStream) = withContext(Dispatchers.IO) {
        state.value = state.value.copy(busy = true, lastMessage = "A importar MyStrengthBook…")
        val result = runCatching { store.importMsb(input) }
        state.value = if (result.isSuccess) {
            loadState().copy(lastMessage = result.getOrThrow())
        } else {
            loadState().copy(lastMessage = "Falha a importar MSB: ${result.exceptionOrNull()?.message ?: "erro desconhecido"}")
        }
    }

    suspend fun importFitNotes(input: InputStream, displayName: String?) = withContext(Dispatchers.IO) {
        state.value = state.value.copy(busy = true, lastMessage = "A importar FitNotes…")
        val result = runCatching { store.importFitNotes(input, displayName) }
        state.value = if (result.isSuccess) {
            loadState().copy(lastMessage = result.getOrThrow())
        } else {
            loadState().copy(lastMessage = "Falha a importar FitNotes: ${result.exceptionOrNull()?.message ?: "erro desconhecido"}")
        }
    }

    suspend fun refresh() = withContext(Dispatchers.IO) {
        state.value = loadState()
    }

    private fun loadState(): StrengthDataState {
        val meta = store.loadImportMeta()
        return StrengthDataState(
            msb = meta["MSB"] ?: SourceImportSummary(),
            fitNotes = meta["FITNOTES"] ?: SourceImportSummary(),
            days = store.loadDays(),
            busy = false,
        )
    }
}

private class TrainingDataStore(context: Context) :
    SQLiteOpenHelper(context, "training_hub.db", null, 1) {

    private val cacheDir = context.cacheDir

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE import_meta(
                source TEXT PRIMARY KEY,
                imported_at INTEGER NOT NULL,
                label TEXT NOT NULL,
                details TEXT NOT NULL
            )
            """.trimIndent()
        )
        db.execSQL(
            """
            CREATE TABLE planned_exercise(
                source_id TEXT PRIMARY KEY,
                date TEXT NOT NULL,
                order_index INTEGER NOT NULL,
                display_name TEXT NOT NULL,
                primary_name TEXT,
                secondary_name TEXT,
                exercise_type TEXT,
                primary_muscle TEXT,
                secondary_muscle TEXT,
                notes TEXT,
                instructions TEXT,
                cycle_id TEXT,
                program INTEGER,
                program_week INTEGER,
                program_session INTEGER,
                program_instance INTEGER,
                raw_json TEXT
            )
            """.trimIndent()
        )
        db.execSQL("CREATE INDEX idx_planned_exercise_date ON planned_exercise(date)")
        db.execSQL(
            """
            CREATE TABLE planned_set_group(
                source_exercise_id TEXT NOT NULL,
                group_index INTEGER NOT NULL,
                set_count INTEGER,
                reps INTEGER,
                rpe REAL,
                load REAL,
                percent_rm REAL,
                zone TEXT,
                raw_json TEXT,
                PRIMARY KEY(source_exercise_id, group_index)
            )
            """.trimIndent()
        )
        db.execSQL(
            """
            CREATE TABLE performed_set(
                source TEXT NOT NULL,
                source_key TEXT NOT NULL,
                date TEXT NOT NULL,
                exercise_name TEXT NOT NULL,
                set_index INTEGER,
                weight REAL,
                reps INTEGER,
                rpe REAL,
                e1rm REAL,
                status TEXT,
                comment TEXT,
                video_original TEXT,
                coach_reviewed INTEGER,
                category TEXT,
                source_exercise_id TEXT,
                PRIMARY KEY(source, source_key)
            )
            """.trimIndent()
        )
        db.execSQL("CREATE INDEX idx_performed_set_date ON performed_set(date)")
        db.execSQL("CREATE INDEX idx_performed_set_exercise ON performed_set(exercise_name)")
        db.execSQL(
            """
            CREATE TABLE fit_exercise(
                source_id INTEGER PRIMARY KEY,
                name TEXT NOT NULL,
                category TEXT,
                exercise_type_id INTEGER,
                notes TEXT,
                default_rest_time INTEGER,
                favourite INTEGER
            )
            """.trimIndent()
        )
        db.execSQL(
            """
            CREATE TABLE fit_superset(
                date TEXT NOT NULL,
                group_id INTEGER NOT NULL,
                group_name TEXT,
                exercise_id INTEGER NOT NULL,
                exercise_name TEXT NOT NULL,
                PRIMARY KEY(group_id, exercise_id)
            )
            """.trimIndent()
        )
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        db.execSQL("DROP TABLE IF EXISTS fit_superset")
        db.execSQL("DROP TABLE IF EXISTS fit_exercise")
        db.execSQL("DROP TABLE IF EXISTS performed_set")
        db.execSQL("DROP TABLE IF EXISTS planned_set_group")
        db.execSQL("DROP TABLE IF EXISTS planned_exercise")
        db.execSQL("DROP TABLE IF EXISTS import_meta")
        onCreate(db)
    }

    fun loadImportMeta(): Map<String, SourceImportSummary> {
        val db = readableDatabase
        val out = mutableMapOf<String, SourceImportSummary>()
        db.rawQuery("SELECT source,label,details FROM import_meta", null).use { c ->
            while (c.moveToNext()) {
                out[c.getString(0)] = SourceImportSummary(
                    imported = true,
                    label = c.getString(1),
                    details = c.getString(2),
                )
            }
        }
        return out
    }

    fun loadDays(): List<StrengthDaySummary> {
        val db = readableDatabase
        val dates = linkedSetOf<String>()
        db.rawQuery(
            """
            SELECT date FROM planned_exercise
            UNION
            SELECT date FROM performed_set
            ORDER BY date DESC
            LIMIT 900
            """.trimIndent(),
            null,
        ).use { c ->
            while (c.moveToNext()) dates += c.getString(0)
        }

        return dates.map { date ->
            val plannedExercises = scalarInt(
                db,
                "SELECT COUNT(*) FROM planned_exercise WHERE date=?",
                arrayOf(date),
            )
            val plannedGroups = scalarInt(
                db,
                """
                SELECT COUNT(*)
                FROM planned_set_group psg
                JOIN planned_exercise pe ON pe.source_id=psg.source_exercise_id
                WHERE pe.date=?
                """.trimIndent(),
                arrayOf(date),
            )
            val msbSets = scalarInt(
                db,
                "SELECT COUNT(*) FROM performed_set WHERE date=? AND source='MSB'",
                arrayOf(date),
            )
            val fitSets = scalarInt(
                db,
                "SELECT COUNT(*) FROM performed_set WHERE date=? AND source='FITNOTES'",
                arrayOf(date),
            )
            val names = mutableListOf<String>()
            db.rawQuery(
                """
                SELECT display_name FROM planned_exercise WHERE date=?
                UNION
                SELECT exercise_name FROM performed_set WHERE date=?
                LIMIT 12
                """.trimIndent(),
                arrayOf(date, date),
            ).use { c ->
                while (c.moveToNext()) names += c.getString(0)
            }

            StrengthDaySummary(
                date = date,
                plannedExercises = plannedExercises,
                plannedSetGroups = plannedGroups,
                msbActualSets = msbSets,
                fitNotesSets = fitSets,
                exerciseNames = names,
            )
        }
    }

    fun importMsb(input: InputStream): String {
        val jsonText = input.bufferedReader().use { it.readText() }
        val root = JSONObject(jsonText)
        check(root.optInt("schemaVersion", 0) >= 4) {
            "Formato MSB não reconhecido (é esperado schemaVersion 4)."
        }

        val db = writableDatabase
        var exerciseCount = 0
        var groupCount = 0
        var outcomeCount = 0
        val days = linkedSetOf<String>()

        db.beginTransaction()
        try {
            db.delete("planned_set_group", null, null)
            db.delete("planned_exercise", null, null)
            db.delete("performed_set", "source='MSB'", null)

            val apiMonths = root.getJSONObject("apiMonths")
            val monthKeys = apiMonths.keys()
            while (monthKeys.hasNext()) {
                val month = monthKeys.next()
                val rawMonth = apiMonths.get(month)
                val arr = when (rawMonth) {
                    is JSONArray -> rawMonth
                    is JSONObject -> when {
                        rawMonth.optJSONArray("docs") != null -> rawMonth.getJSONArray("docs")
                        rawMonth.optJSONArray("data") != null -> rawMonth.getJSONArray("data")
                        else -> JSONArray()
                    }
                    else -> JSONArray()
                }

                for (i in 0 until arr.length()) {
                    val ex = arr.optJSONObject(i) ?: continue
                    val sourceId = ex.optString("_id")
                    if (sourceId.isBlank()) continue

                    val date = utcDateToIso(ex.opt("utcDate"), ex.optString("date"))
                    if (date.isBlank()) continue
                    days += date

                    val primary = ex.optString("primary").takeIf { it.isNotBlank() }
                    val secondary = ex.optString("secondary").takeIf { it.isNotBlank() && it != "null" }
                    val display = msbDisplayName(primary, secondary)
                    val cycle = ex.optJSONObject("cycle")

                    val values = ContentValues().apply {
                        put("source_id", sourceId)
                        put("date", date)
                        put("order_index", ex.optInt("order", i))
                        put("display_name", display)
                        putNullable("primary_name", primary)
                        putNullable("secondary_name", secondary)
                        putNullable("exercise_type", ex.optString("type").takeIf { it.isNotBlank() })
                        putNullable("primary_muscle", ex.optString("primaryMuscleGroup").takeIf { it.isNotBlank() })
                        putNullable("secondary_muscle", ex.optString("secondaryMuscleGroup").takeIf { it.isNotBlank() })
                        putNullable("notes", ex.optString("notes").takeIf { it.isNotBlank() })
                        putNullable("instructions", ex.optString("instructions").takeIf { it.isNotBlank() })
                        putNullable("cycle_id", cycle?.optString("id")?.takeIf { it.isNotBlank() })
                        putNullable("program", cycle?.optIntOrNull("program"))
                        putNullable("program_week", cycle?.optIntOrNull("programWeek"))
                        putNullable("program_session", cycle?.optIntOrNull("programSession"))
                        putNullable("program_instance", cycle?.optIntOrNull("programInstance"))
                        put("raw_json", ex.toString())
                    }
                    db.insertWithOnConflict(
                        "planned_exercise",
                        null,
                        values,
                        SQLiteDatabase.CONFLICT_REPLACE,
                    )
                    exerciseCount++

                    val sets = ex.optJSONArray("sets") ?: JSONArray()
                    var actualIndex = 0
                    for (g in 0 until sets.length()) {
                        val sg = sets.optJSONObject(g) ?: continue
                        val groupValues = ContentValues().apply {
                            put("source_exercise_id", sourceId)
                            put("group_index", g)
                            putNullable("set_count", sg.optIntOrNull("sets"))
                            putNullable("reps", sg.optIntOrNull("reps"))
                            putNullable("rpe", sg.optDoubleOrNull("rpe"))
                            putNullable("load", sg.optNumberAsDouble("load"))
                            putNullable("percent_rm", sg.optDoubleOrNull("percentRepMax"))
                            putNullable("zone", sg.optString("zone").takeIf { it.isNotBlank() })
                            put("raw_json", sg.toString())
                        }
                        db.insertWithOnConflict(
                            "planned_set_group",
                            null,
                            groupValues,
                            SQLiteDatabase.CONFLICT_REPLACE,
                        )
                        groupCount++

                        val outcomes = sg.optJSONObject("outcomes")
                        if (outcomes != null) {
                            val keys = outcomes.keys().asSequence().toList().sortedBy { it.toIntOrNull() ?: Int.MAX_VALUE }
                            val comments = sg.optJSONObject("comments")
                            val videos = sg.optJSONObject("videos")
                            for (outcomeKey in keys) {
                                val outcomeRecord = outcomes.optJSONObject(outcomeKey) ?: continue
                                val actual = outcomeRecord.optJSONObject("outcome") ?: continue
                                val video = videos?.optJSONObject(outcomeKey)
                                val p = ContentValues().apply {
                                    put("source", "MSB")
                                    put("source_key", "$sourceId:$g:$outcomeKey")
                                    put("date", date)
                                    put("exercise_name", display)
                                    put("set_index", actualIndex++)
                                    putNullable("weight", actual.optNumberAsDouble("load"))
                                    putNullable("reps", actual.optIntOrNull("reps"))
                                    putNullable("rpe", actual.optDoubleOrNull("rpe"))
                                    putNullable("e1rm", actual.optDoubleOrNull("e1rm"))
                                    putNullable("status", outcomeRecord.optString("status").takeIf { it.isNotBlank() })
                                    putNullable("comment", comments?.optString(outcomeKey)?.takeIf { it.isNotBlank() })
                                    putNullable("video_original", video?.optString("videoOriginal")?.takeIf { it.isNotBlank() })
                                    put("coach_reviewed", if (video?.optBoolean("reviewedByTheCoach", false) == true) 1 else 0)
                                    putNullable("category", ex.optString("primaryMuscleGroup").takeIf { it.isNotBlank() })
                                    put("source_exercise_id", sourceId)
                                }
                                db.insertWithOnConflict(
                                    "performed_set",
                                    null,
                                    p,
                                    SQLiteDatabase.CONFLICT_REPLACE,
                                )
                                outcomeCount++
                            }
                        }
                    }
                }
            }

            val details = "$exerciseCount exercícios · $groupCount grupos de séries · $outcomeCount séries realizadas · ${days.size} dias"
            upsertMeta(db, "MSB", "MyStrengthBook", details)
            db.setTransactionSuccessful()
            return "MyStrengthBook importado: $details"
        } finally {
            db.endTransaction()
        }
    }

    fun importFitNotes(input: InputStream, displayName: String?): String {
        val tempDir = File(cacheDir, "fitnotes_import").apply {
            deleteRecursively()
            mkdirs()
        }

        val lowerName = displayName.orEmpty().lowercase()
        val dbFile = if (lowerName.endsWith(".fitnotes")) {
            File(tempDir, "backup.fitnotes").also { out ->
                FileOutputStream(out).use { output -> input.copyTo(output) }
            }
        } else {
            extractFitNotesFromZip(input, tempDir)
        }

        check(dbFile.exists() && dbFile.length() > 0L) { "Backup FitNotes vazio." }
        val source = SQLiteDatabase.openDatabase(
            dbFile.absolutePath,
            null,
            SQLiteDatabase.OPEN_READONLY,
        )

        val target = writableDatabase
        var exerciseCount = 0
        var setCount = 0
        var commentCount = 0
        var groupCount = 0
        var groupLinkCount = 0
        val days = linkedSetOf<String>()

        target.beginTransaction()
        try {
            target.delete("performed_set", "source='FITNOTES'", null)
            target.delete("fit_exercise", null, null)
            target.delete("fit_superset", null, null)

            val exerciseNames = mutableMapOf<Int, Pair<String, String?>>()
            source.rawQuery(
                """
                SELECT e._id,e.name,c.name,e.exercise_type_id,e.notes,e.default_rest_time,e.is_favourite
                FROM exercise e
                LEFT JOIN Category c ON c._id=e.category_id
                """.trimIndent(),
                null,
            ).use { c ->
                while (c.moveToNext()) {
                    val id = c.getInt(0)
                    val name = c.getString(1)
                    val category = if (c.isNull(2)) null else c.getString(2)
                    exerciseNames[id] = name to category
                    val v = ContentValues().apply {
                        put("source_id", id)
                        put("name", name)
                        putNullable("category", category)
                        putNullable("exercise_type_id", if (c.isNull(3)) null else c.getInt(3))
                        putNullable("notes", if (c.isNull(4)) null else c.getString(4))
                        putNullable("default_rest_time", if (c.isNull(5)) null else c.getInt(5))
                        putNullable("favourite", if (c.isNull(6)) null else c.getInt(6))
                    }
                    target.insertWithOnConflict(
                        "fit_exercise",
                        null,
                        v,
                        SQLiteDatabase.CONFLICT_REPLACE,
                    )
                    exerciseCount++
                }
            }

            val comments = mutableMapOf<Long, String>()
            source.rawQuery(
                "SELECT owner_id,comment FROM Comment WHERE owner_type_id=1",
                null,
            ).use { c ->
                while (c.moveToNext()) {
                    comments[c.getLong(0)] = c.getString(1)
                    commentCount++
                }
            }

            source.rawQuery(
                """
                SELECT _id,exercise_id,date,metric_weight,reps,is_complete,distance,duration_seconds
                FROM training_log
                ORDER BY date,_id
                """.trimIndent(),
                null,
            ).use { c ->
                while (c.moveToNext()) {
                    val id = c.getLong(0)
                    val exerciseId = c.getInt(1)
                    val date = c.getString(2)
                    val ex = exerciseNames[exerciseId] ?: continue
                    days += date
                    val v = ContentValues().apply {
                        put("source", "FITNOTES")
                        put("source_key", id.toString())
                        put("date", date)
                        put("exercise_name", ex.first)
                        put("set_index", id)
                        putNullable("weight", if (c.isNull(3)) null else c.getDouble(3))
                        putNullable("reps", if (c.isNull(4)) null else c.getInt(4))
                        putNull("rpe")
                        putNull("e1rm")
                        put("status", if (!c.isNull(5) && c.getInt(5) == 1) "completed" else "logged")
                        putNullable("comment", comments[id])
                        putNull("video_original")
                        put("coach_reviewed", 0)
                        putNullable("category", ex.second)
                        putNull("source_exercise_id")
                    }
                    target.insertWithOnConflict(
                        "performed_set",
                        null,
                        v,
                        SQLiteDatabase.CONFLICT_REPLACE,
                    )
                    setCount++
                }
            }

            source.rawQuery(
                """
                SELECT wg.date,wg._id,wg.name,wge.exercise_id,e.name
                FROM WorkoutGroup wg
                JOIN WorkoutGroupExercise wge ON wge.workout_group_id=wg._id
                JOIN exercise e ON e._id=wge.exercise_id
                """.trimIndent(),
                null,
            ).use { c ->
                val seenGroups = mutableSetOf<Long>()
                while (c.moveToNext()) {
                    val groupId = c.getLong(1)
                    seenGroups += groupId
                    val v = ContentValues().apply {
                        put("date", c.getString(0))
                        put("group_id", groupId)
                        putNullable("group_name", if (c.isNull(2)) null else c.getString(2))
                        put("exercise_id", c.getInt(3))
                        put("exercise_name", c.getString(4))
                    }
                    target.insertWithOnConflict(
                        "fit_superset",
                        null,
                        v,
                        SQLiteDatabase.CONFLICT_REPLACE,
                    )
                    groupLinkCount++
                }
                groupCount = seenGroups.size
            }

            val details = "$exerciseCount exercícios · $setCount séries · ${days.size} dias · $commentCount comentários · $groupCount supersets ($groupLinkCount ligações)"
            upsertMeta(target, "FITNOTES", "FitNotes", details)
            target.setTransactionSuccessful()
            return "FitNotes importado: $details"
        } finally {
            target.endTransaction()
            source.close()
            tempDir.deleteRecursively()
        }
    }

    private fun extractFitNotesFromZip(input: InputStream, tempDir: File): File {
        ZipInputStream(input.buffered()).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                if (!entry.isDirectory && entry.name.lowercase().endsWith(".fitnotes")) {
                    val out = File(tempDir, "backup.fitnotes")
                    FileOutputStream(out).use { zip.copyTo(it) }
                    return out
                }
            }
        }
        error("O ZIP não contém um ficheiro .fitnotes.")
    }

    private fun upsertMeta(db: SQLiteDatabase, source: String, label: String, details: String) {
        val v = ContentValues().apply {
            put("source", source)
            put("imported_at", System.currentTimeMillis())
            put("label", label)
            put("details", details)
        }
        db.insertWithOnConflict("import_meta", null, v, SQLiteDatabase.CONFLICT_REPLACE)
    }

    private fun scalarInt(db: SQLiteDatabase, sql: String, args: Array<String>): Int =
        db.rawQuery(sql, args).use { c -> if (c.moveToFirst()) c.getInt(0) else 0 }
}

private fun ContentValues.putNullable(key: String, value: String?) {
    if (value == null) putNull(key) else put(key, value)
}

private fun ContentValues.putNullable(key: String, value: Int?) {
    if (value == null) putNull(key) else put(key, value)
}

private fun ContentValues.putNullable(key: String, value: Double?) {
    if (value == null || value.isNaN()) putNull(key) else put(key, value)
}

private fun JSONObject.optIntOrNull(key: String): Int? {
    if (!has(key) || isNull(key)) return null
    return when (val v = opt(key)) {
        is Number -> v.toInt()
        is String -> v.toIntOrNull()
        else -> null
    }
}

private fun JSONObject.optDoubleOrNull(key: String): Double? {
    if (!has(key) || isNull(key)) return null
    return when (val v = opt(key)) {
        is Number -> v.toDouble()
        is String -> v.toDoubleOrNull()
        else -> null
    }
}

private fun JSONObject.optNumberAsDouble(key: String): Double? = optDoubleOrNull(key)

private fun utcDateToIso(raw: Any?, fallback: String): String {
    val digits = when (raw) {
        is Number -> raw.toLong().toString()
        is String -> raw.filter { it.isDigit() }
        else -> ""
    }
    if (digits.length >= 8) {
        return "${digits.substring(0, 4)}-${digits.substring(4, 6)}-${digits.substring(6, 8)}"
    }
    return fallback.take(10)
}

private fun msbDisplayName(primary: String?, secondary: String?): String {
    val p = primary.orEmpty()
    return when {
        p.equals("assistant", ignoreCase = true) && !secondary.isNullOrBlank() -> secondary
        !secondary.isNullOrBlank() -> "${p.replaceFirstChar { if (it.isLowerCase()) it.titlecase() else it.toString() }} $secondary".trim()
        p.isNotBlank() -> p.replaceFirstChar { if (it.isLowerCase()) it.titlecase() else it.toString() }
        else -> secondary ?: "Exercício"
    }
}
