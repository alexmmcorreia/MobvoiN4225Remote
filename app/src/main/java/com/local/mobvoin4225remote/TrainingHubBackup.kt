package com.local.mobvoin4225remote

import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

data class BackupInspection(
    val createdAtMs: Long,
    val databaseCount: Int,
    val tableCount: Int,
    val rowCount: Int,
    val videoFiles: Int = 0,
    val videoBytes: Long = 0L,
)

data class BackupRestoreResult(
    val restoredTables: Int,
    val restoredRows: Int,
    val restoredVideos: Int,
    val safetyBackup: File?,
)

object TrainingHubBackup {
    private val databaseNames = listOf(
        "training_hub.db",
        "strength_execution.db",
        "personal_metrics.db",
    )

    fun safetySnapshot(context: Context, reason: String): File {
        val original = export(context)
        val safeReason = reason.lowercase(Locale.ROOT)
            .replace(Regex("[^a-z0-9]+"), "_")
            .trim('_')
            .ifBlank { "snapshot" }
        val target = File(
            original.parentFile,
            "training_hub_safety_${safeReason}_${System.currentTimeMillis()}.zip",
        )
        if (!original.renameTo(target)) {
            original.copyTo(target, overwrite = true)
            original.delete()
        }
        target.parentFile?.listFiles()
            ?.filter { it.name.startsWith("training_hub_safety_") && it.extension == "zip" }
            ?.sortedByDescending { it.lastModified() }
            ?.drop(5)
            ?.forEach { it.delete() }
        return target
    }

    fun export(context: Context): File {
        val root = JSONObject().apply {
            put("schemaVersion", 1)
            put("createdAtMs", System.currentTimeMillis())
            put("app", "Training Hub")
            put("databases", JSONObject())
        }
        val databases = root.getJSONObject("databases")

        databaseNames.forEach { name ->
            val file = context.getDatabasePath(name)
            if (!file.exists()) return@forEach
            val db = SQLiteDatabase.openDatabase(
                file.absolutePath,
                null,
                SQLiteDatabase.OPEN_READONLY,
            )
            try {
                requireQuickCheck(db, name)
                databases.put(name, exportDatabase(db))
            } finally {
                db.close()
            }
        }

        val treadmillFile = File(context.filesDir, "treadmill_sessions.json")
        if (treadmillFile.exists()) {
            root.put(
                "treadmillSessions",
                runCatching { JSONArray(treadmillFile.readText()) }.getOrElse { JSONArray() },
            )
        }

        val videoDir = File(context.filesDir, "workout_videos")
        val videos = videoDir.listFiles()
            ?.filter { it.isFile && it.length() > 0L }
            ?.sortedBy { it.name }
            .orEmpty()
        root.put(
            "videos",
            JSONArray().apply {
                videos.forEach { file ->
                    put(
                        JSONObject().apply {
                            put("name", file.name)
                            put("bytes", file.length())
                        }
                    )
                }
            },
        )

        root.put(
            "privacy",
            JSONObject().apply {
                put("devicePairingSecretsIncluded", false)
                put("bleAddressesIncluded", false)
            },
        )

        val exports = File(context.filesDir, "exports").apply { mkdirs() }
        val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val zip = File(exports, "training_hub_backup_$stamp.zip")
        ZipOutputStream(FileOutputStream(zip)).use { out ->
            out.putNextEntry(ZipEntry("training_hub_backup.json"))
            out.write(root.toString().toByteArray(Charsets.UTF_8))
            out.closeEntry()

            videos.forEach { file ->
                out.putNextEntry(ZipEntry("workout_videos/${file.name}"))
                file.inputStream().use { it.copyTo(out) }
                out.closeEntry()
            }
        }
        return zip
    }

    fun inspect(input: InputStream): BackupInspection {
        var root: JSONObject? = null
        var videoFiles = 0
        var videoBytes = 0L
        ZipInputStream(input.buffered()).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                if (entry.isDirectory) continue
                when {
                    entry.name == "training_hub_backup.json" -> {
                        root = JSONObject(readEntry(zip, 50L * 1024L * 1024L))
                    }
                    entry.name.startsWith("workout_videos/") -> {
                        videoFiles++
                        videoBytes += entry.size.coerceAtLeast(0L)
                    }
                }
                zip.closeEntry()
            }
        }
        val parsed = root ?: error("ZIP sem training_hub_backup.json.")
        validateRoot(parsed)
        val dbs = parsed.optJSONObject("databases") ?: JSONObject()
        var tables = 0
        var rows = 0
        val names = dbs.keys()
        while (names.hasNext()) {
            val db = dbs.optJSONObject(names.next()) ?: continue
            val tableNames = db.keys()
            while (tableNames.hasNext()) {
                val arr = db.optJSONArray(tableNames.next()) ?: continue
                tables++
                rows += arr.length()
            }
        }
        return BackupInspection(
            createdAtMs = parsed.optLong("createdAtMs", 0L),
            databaseCount = dbs.length(),
            tableCount = tables,
            rowCount = rows,
            videoFiles = videoFiles,
            videoBytes = videoBytes,
        )
    }

    fun restore(context: Context, input: InputStream): BackupRestoreResult {
        val temp = File.createTempFile("training_hub_restore_", ".zip", context.cacheDir)
        try {
            input.use { source ->
                temp.outputStream().use { sink ->
                    copyWithLimit(source, sink, 2L * 1024L * 1024L * 1024L)
                }
            }

            ZipFile(temp).use { zip ->
                val rootEntry = zip.getEntry("training_hub_backup.json")
                    ?: error("ZIP sem training_hub_backup.json.")
                val root = zip.getInputStream(rootEntry).use {
                    JSONObject(readEntry(it, 50L * 1024L * 1024L))
                }
                validateRoot(root)

                val safety = runCatching {
                    safetySnapshot(context, "before_restore")
                }.getOrNull()

                val databases = root.optJSONObject("databases") ?: JSONObject()
                var tableCount = 0
                var rowCount = 0

                databaseNames.forEach { name ->
                    val backupDb = databases.optJSONObject(name) ?: return@forEach
                    val file = context.getDatabasePath(name)
                    if (!file.exists()) return@forEach
                    val db = SQLiteDatabase.openDatabase(
                        file.absolutePath,
                        null,
                        SQLiteDatabase.OPEN_READWRITE,
                    )
                    try {
                        db.beginTransaction()
                        try {
                            val tableNames = backupDb.keys()
                            while (tableNames.hasNext()) {
                                val table = tableNames.next()
                                if (!tableExists(db, table)) continue
                                val rows = backupDb.optJSONArray(table) ?: continue
                                val columns = tableColumns(db, table)
                                db.delete(table, null, null)
                                for (i in 0 until rows.length()) {
                                    val row = rows.optJSONObject(i) ?: continue
                                    val values = android.content.ContentValues()
                                    val keys = row.keys()
                                    while (keys.hasNext()) {
                                        val key = keys.next()
                                        if (key !in columns) continue
                                        val value = row.opt(key)
                                        when {
                                            value == null || value === JSONObject.NULL ->
                                                values.putNull(key)
                                            value is Number -> {
                                                if (value is Float || value is Double) {
                                                    values.put(key, value.toDouble())
                                                } else {
                                                    values.put(key, value.toLong())
                                                }
                                            }
                                            value is String -> values.put(key, value)
                                            value is JSONObject &&
                                                value.optString("encoding") == "base64" ->
                                                values.put(
                                                    key,
                                                    Base64.decode(
                                                        value.optString("data"),
                                                        Base64.DEFAULT,
                                                    ),
                                                )
                                            else -> values.put(key, value.toString())
                                        }
                                    }
                                    db.insertOrThrow(table, null, values)
                                    rowCount++
                                }
                                tableCount++
                            }
                            db.setTransactionSuccessful()
                        } finally {
                            db.endTransaction()
                        }
                        requireQuickCheck(db, name)
                    } finally {
                        db.close()
                    }
                }

                root.optJSONArray("treadmillSessions")?.let { sessions ->
                    File(context.filesDir, "treadmill_sessions.json")
                        .writeText(sessions.toString())
                }

                val videoDir = File(context.filesDir, "workout_videos").apply { mkdirs() }
                var restoredVideos = 0
                val entries = zip.entries()
                while (entries.hasMoreElements()) {
                    val entry = entries.nextElement()
                    if (entry.isDirectory || !entry.name.startsWith("workout_videos/")) continue
                    val name = File(entry.name).name
                    if (name.isBlank()) continue
                    val target = File(videoDir, name)
                    zip.getInputStream(entry).use { source ->
                        target.outputStream().use { sink ->
                            copyWithLimit(source, sink, 5L * 1024L * 1024L * 1024L)
                        }
                    }
                    restoredVideos++
                }

                return BackupRestoreResult(
                    restoredTables = tableCount,
                    restoredRows = rowCount,
                    restoredVideos = restoredVideos,
                    safetyBackup = safety,
                )
            }
        } finally {
            temp.delete()
        }
    }

    private fun exportDatabase(db: SQLiteDatabase): JSONObject {
        val result = JSONObject()
        val tables = mutableListOf<String>()
        db.rawQuery(
            """
            SELECT name
            FROM sqlite_master
            WHERE type='table'
              AND name NOT LIKE 'sqlite_%'
              AND name != 'android_metadata'
            ORDER BY name
            """.trimIndent(),
            null,
        ).use { cursor ->
            while (cursor.moveToNext()) tables += cursor.getString(0)
        }

        tables.forEach { table ->
            val rows = JSONArray()
            db.rawQuery("SELECT * FROM ${quoteIdentifier(table)}", null).use { cursor ->
                while (cursor.moveToNext()) rows.put(cursorRow(cursor))
            }
            result.put(table, rows)
        }
        return result
    }

    private fun cursorRow(cursor: Cursor): JSONObject {
        val row = JSONObject()
        for (i in 0 until cursor.columnCount) {
            val name = cursor.getColumnName(i)
            when (cursor.getType(i)) {
                Cursor.FIELD_TYPE_NULL -> row.put(name, JSONObject.NULL)
                Cursor.FIELD_TYPE_INTEGER -> row.put(name, cursor.getLong(i))
                Cursor.FIELD_TYPE_FLOAT -> row.put(name, cursor.getDouble(i))
                Cursor.FIELD_TYPE_STRING -> row.put(name, cursor.getString(i))
                Cursor.FIELD_TYPE_BLOB -> row.put(
                    name,
                    JSONObject().apply {
                        put("encoding", "base64")
                        put("data", Base64.encodeToString(cursor.getBlob(i), Base64.NO_WRAP))
                    },
                )
            }
        }
        return row
    }

    private fun validateRoot(root: JSONObject) {
        require(root.optString("app") == "Training Hub") {
            "Backup não pertence ao Training Hub."
        }
        require(root.optInt("schemaVersion", 0) == 1) {
            "Versão de backup não suportada."
        }
    }

    private fun readEntry(input: InputStream, maxBytes: Long): String {
        val out = ByteArrayOutputStream()
        val buffer = ByteArray(8192)
        var total = 0L
        while (true) {
            val n = input.read(buffer)
            if (n <= 0) break
            total += n
            require(total <= maxBytes) { "Entrada do backup demasiado grande." }
            out.write(buffer, 0, n)
        }
        return out.toString(Charsets.UTF_8.name())
    }

    private fun copyWithLimit(
        input: InputStream,
        output: java.io.OutputStream,
        maxBytes: Long,
    ) {
        val buffer = ByteArray(64 * 1024)
        var total = 0L
        while (true) {
            val n = input.read(buffer)
            if (n <= 0) break
            total += n
            require(total <= maxBytes) { "Ficheiro de backup demasiado grande." }
            output.write(buffer, 0, n)
        }
    }

    private fun requireQuickCheck(db: SQLiteDatabase, name: String) {
        val result = db.rawQuery("PRAGMA quick_check", null).use { cursor ->
            if (cursor.moveToFirst()) cursor.getString(0) else "unknown"
        }
        require(result.equals("ok", ignoreCase = true)) {
            "Falha de integridade em $name: $result"
        }
    }

    private fun tableExists(db: SQLiteDatabase, table: String): Boolean =
        db.rawQuery(
            "SELECT 1 FROM sqlite_master WHERE type='table' AND name=?",
            arrayOf(table),
        ).use { it.moveToFirst() }

    private fun tableColumns(db: SQLiteDatabase, table: String): Set<String> {
        val out = mutableSetOf<String>()
        db.rawQuery("PRAGMA table_info(${quoteIdentifier(table)})", null).use { cursor ->
            while (cursor.moveToNext()) out += cursor.getString(1)
        }
        return out
    }

    private fun quoteIdentifier(value: String): String =
        "\"" + value.replace("\"", "\"\"") + "\""
}
