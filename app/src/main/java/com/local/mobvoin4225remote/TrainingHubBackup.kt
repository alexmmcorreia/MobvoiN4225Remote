package com.local.mobvoin4225remote

import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

object TrainingHubBackup {
    private val databaseNames = listOf(
        "training_hub.db",
        "strength_execution.db",
        "personal_metrics.db",
    )

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
        }
        return zip
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

    private fun quoteIdentifier(value: String): String =
        "\"" + value.replace("\"", "\"\"") + "\""
}
