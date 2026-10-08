package com.local.mobvoin4225remote

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import java.io.BufferedReader
import java.io.BufferedWriter
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.security.SecureRandom

const val WATCH_BRIDGE_PORT = 18765
const val WATCH_PROTOCOL_VERSION = 2
private const val WATCH_CHANNEL_ID = "training_hub_watch_bridge"
private const val WATCH_NOTIFICATION_ID = 2407

data class WatchBridgeState(
    val running: Boolean = false,
    val paired: Boolean = false,
    val lastSeenMs: Long? = null,
    val lastHeartRateBpm: Int? = null,
    val dailyContext: DailyBodyContext? = null,
    val lastHistorySamples: Int = 0,
    val demoMode: Boolean = false,
    val lastError: String? = null,
)

object TrainingHubRuntime {
    @Volatile
    var strengthRepository: StrengthExecutionRepository? = null

    @Volatile
    var treadmillController: TreadmillController? = null
}

object WatchBridgeRuntime {
    val state = MutableStateFlow(WatchBridgeState())

    fun pairingCode(context: Context): String = WatchBridgeConfig.pairingCode(context)

    fun isEnabled(context: Context): Boolean = WatchBridgeConfig.isEnabled(context)

    fun start(context: Context) {
        WatchBridgeConfig.setEnabled(context, true)
        val intent = Intent(context, WatchBridgeService::class.java)
        ContextCompat.startForegroundService(context, intent)
    }

    fun stop(context: Context) {
        WatchBridgeConfig.setEnabled(context, false)
        context.stopService(Intent(context, WatchBridgeService::class.java))
        state.value = state.value.copy(running = false, paired = false)
    }

    fun setDemoMode(context: Context, enabled: Boolean) {
        WatchBridgeConfig.setDemoMode(context, enabled)
        WatchDemoRuntime.reset()
        state.value = state.value.copy(demoMode = enabled)
    }

    fun isDemoMode(context: Context): Boolean = WatchBridgeConfig.isDemoMode(context)
}

private object WatchBridgeConfig {
    private const val PREFS = "watch_bridge"
    private const val KEY_ENABLED = "enabled"
    private const val KEY_PAIRING = "pairing_code"
    private const val KEY_DEMO = "demo_mode"

    fun isEnabled(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getBoolean(KEY_ENABLED, false)

    fun setEnabled(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putBoolean(KEY_ENABLED, enabled).apply()
    }

    fun isDemoMode(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getBoolean(KEY_DEMO, false)

    fun setDemoMode(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putBoolean(KEY_DEMO, enabled).apply()
    }

    fun pairingCode(context: Context): String {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        prefs.getString(KEY_PAIRING, null)?.let { return it }
        val code = (SecureRandom().nextInt(900_000) + 100_000).toString()
        prefs.edit().putString(KEY_PAIRING, code).apply()
        return code
    }
}

private object WatchDemoRuntime {
    private val exercises = listOf(
        Triple("Paused Sumo Deadlift", 215.0, 2),
        Triple("Bench Press", 120.0, 4),
        Triple("Chest Supported Row", 75.0, 8),
    )
    private var exerciseIndex = 0
    private var setIndex = 0
    private var completedSets = 0
    private var restEndMs: Long? = null

    fun reset() {
        exerciseIndex = 0
        setIndex = 0
        completedSets = 0
        restEndMs = null
    }

    fun action(action: String, payload: JSONObject) {
        when (action) {
            "COMPLETE_CURRENT" -> {
                if (exerciseIndex >= exercises.size) return
                completedSets++
                setIndex++
                if (setIndex >= 4) {
                    exerciseIndex++
                    setIndex = 0
                }
                restEndMs = System.currentTimeMillis() + 90_000L
            }
            "EXTEND_REST" -> {
                val base = restEndMs ?: System.currentTimeMillis()
                restEndMs = base + payload.optInt("seconds", 30).coerceIn(5, 600) * 1000L
            }
            "SKIP_REST" -> restEndMs = null
            "UNDO_LAST_SET" -> {
                if (completedSets <= 0) return
                if (setIndex == 0 && exerciseIndex > 0) {
                    exerciseIndex--
                    setIndex = 3
                } else if (setIndex > 0) {
                    setIndex--
                }
                completedSets--
                restEndMs = null
            }
        }
    }

    fun state(): JSONObject {
        val complete = exerciseIndex >= exercises.size
        val current = if (complete) null else exercises[exerciseIndex]
        val next = if (complete) null else exercises.getOrNull(exerciseIndex + 1)
        val rest = restEndMs?.let {
            ((it - System.currentTimeMillis() + 999L) / 1000L).toInt().coerceAtLeast(0)
        } ?: 0
        if (rest == 0) restEndMs = null
        return JSONObject().apply {
            put("ok", true)
            put("protocolVersion", WATCH_PROTOCOL_VERSION)
            put("demoMode", true)
            put("date", java.time.LocalDate.now().toString())
            put("busy", false)
            put("complete", complete)
            put("completedSets", completedSets)
            put("totalSets", exercises.size * 4)
            put("program", 99)
            put("week", 1)
            put("session", 1)
            put("exerciseIndex", if (complete) exercises.size - 1 else exerciseIndex)
            put("exerciseCount", exercises.size)
            putNullable("exerciseName", current?.first)
            putNullable("nextExerciseName", next?.first)
            put("setIndex", if (complete) 3 else setIndex)
            put("setCount", 4)
            putNullable("load", current?.second)
            putNullable("reps", current?.third)
            put("rpe", 7.0)
            put("restRemainingSec", rest)
            put("pendingSyncSets", 0)
            put("watchHeartRateBpm", 118)
            put("cardio", JSONObject().apply {
                put("connected", false)
                put("active", false)
                put("paused", false)
                put("durationSec", 0)
            })
        }
    }
}

class WatchBridgeService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var server: ServerSocket? = null
    private var fallbackRepository: StrengthExecutionRepository? = null
    private lateinit var metrics: PersonalMetricsStore

    override fun onCreate() {
        super.onCreate()
        metrics = PersonalMetricsStore(this)
        WatchBridgeRuntime.state.value = WatchBridgeRuntime.state.value.copy(
            running = true,
            dailyContext = metrics.latestWatchDaily(),
            demoMode = WatchBridgeConfig.isDemoMode(this),
            lastError = null,
        )
        startForeground(WATCH_NOTIFICATION_ID, buildNotification())
        scope.launch { serve() }
        scope.launch { monitorWatchPresence() }
    }

    override fun onDestroy() {
        runCatching { server?.close() }
        server = null
        scope.cancel()
        WatchBridgeRuntime.state.value = WatchBridgeRuntime.state.value.copy(
            running = false,
            paired = false,
        )
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private suspend fun monitorWatchPresence() {
        while (true) {
            delay(5_000L)
            val current = WatchBridgeRuntime.state.value
            val last = current.lastSeenMs
            if (current.paired && last != null && System.currentTimeMillis() - last > 15_000L) {
                WatchBridgeRuntime.state.value = current.copy(paired = false)
            }
        }
    }

    private fun buildNotification(): Notification {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(
                NotificationChannel(
                    WATCH_CHANNEL_ID,
                    "Training Hub · Active 2",
                    NotificationManager.IMPORTANCE_LOW,
                ).apply {
                    description = "Mantém a ligação local entre o Active 2 e o Training Hub."
                }
            )
        }
        return NotificationCompat.Builder(this, WATCH_CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_menu_info_details)
            .setContentTitle("Training Hub · Active 2")
            .setContentText("Bridge local ativo para o relógio")
            .setOngoing(true)
            .setSilent(true)
            .build()
    }

    private fun repository(): StrengthExecutionRepository {
        TrainingHubRuntime.strengthRepository?.let { return it }
        fallbackRepository?.let { return it }
        return StrengthExecutionRepository(applicationContext).also {
            fallbackRepository = it
            TrainingHubRuntime.strengthRepository = it
        }
    }

    private fun serve() {
        try {
            server = ServerSocket(
                WATCH_BRIDGE_PORT,
                12,
                InetAddress.getByName("127.0.0.1"),
            )
            WatchBridgeRuntime.state.value = WatchBridgeRuntime.state.value.copy(
                running = true,
                lastError = null,
            )
            while (!Thread.currentThread().isInterrupted && server?.isClosed == false) {
                val client = server?.accept() ?: break
                scope.launch { handleClient(client) }
            }
        } catch (e: Exception) {
            if (server?.isClosed != true) {
                WatchBridgeRuntime.state.value = WatchBridgeRuntime.state.value.copy(
                    running = false,
                    lastError = e.message ?: "Falha no bridge local",
                )
            }
        }
    }

    private fun handleClient(socket: Socket) {
        socket.use { client ->
            client.soTimeout = 7_000
            val reader = BufferedReader(InputStreamReader(client.getInputStream(), Charsets.UTF_8))
            val requestLine = reader.readLine() ?: return
            val parts = requestLine.split(" ")
            if (parts.size < 2) {
                respond(client, 400, jsonError("Pedido inválido"))
                return
            }
            val method = parts[0].uppercase()
            val path = parts[1].substringBefore("?")
            var contentLength = 0
            var pairing: String? = null
            while (true) {
                val line = reader.readLine() ?: break
                if (line.isBlank()) break
                val split = line.indexOf(':')
                if (split <= 0) continue
                val name = line.substring(0, split).trim().lowercase()
                val value = line.substring(split + 1).trim()
                when (name) {
                    "content-length" -> contentLength = value.toIntOrNull() ?: 0
                    "x-training-hub-key" -> pairing = value
                }
            }
            val body = if (contentLength > 0) {
                val chars = CharArray(contentLength)
                var total = 0
                while (total < contentLength) {
                    val n = reader.read(chars, total, contentLength - total)
                    if (n <= 0) break
                    total += n
                }
                String(chars, 0, total)
            } else ""

            if (path == "/v1/watch/ping") {
                respond(
                    client,
                    200,
                    JSONObject().apply {
                        put("ok", true)
                        put("service", "Training Hub")
                        put("version", 1)
                        put("protocolVersion", WATCH_PROTOCOL_VERSION)
                    },
                )
                return
            }

            if (pairing != WatchBridgeConfig.pairingCode(this)) {
                WatchBridgeRuntime.state.value = WatchBridgeRuntime.state.value.copy(paired = false)
                respond(client, 401, jsonError("Código de emparelhamento inválido"))
                return
            }

            val now = System.currentTimeMillis()
            WatchBridgeRuntime.state.value = WatchBridgeRuntime.state.value.copy(
                paired = true,
                lastSeenMs = now,
                lastError = null,
            )

            val payload = if (body.isBlank()) JSONObject() else runCatching {
                JSONObject(body)
            }.getOrElse {
                respond(client, 400, jsonError("JSON inválido"))
                return
            }

            when {
                method == "GET" && path == "/v1/watch/state" -> {
                    val result = if (WatchBridgeConfig.isDemoMode(this)) {
                        WatchDemoRuntime.state()
                    } else {
                        buildWorkoutState(repository())
                    }
                    respond(client, 200, result)
                }

                method == "POST" && path == "/v1/watch/action" -> {
                    val result = if (WatchBridgeConfig.isDemoMode(this)) {
                        WatchDemoRuntime.action(payload.optString("action").uppercase(), payload)
                        WatchDemoRuntime.state()
                    } else {
                        runBlocking { applyWatchAction(repository(), payload) }
                    }
                    respond(client, 200, result)
                }

                method == "POST" && path == "/v1/watch/telemetry" -> {
                    val hr = payload.optInt("heartRate", 0).takeIf { it in 20..250 }
                    val timestamp = payload.optLong("timestampMs", now)
                    if (hr != null) {
                        metrics.recordMetric(
                            source = "AMAZFIT_ACTIVE_2",
                            metric = "heart_rate",
                            value = hr.toDouble(),
                            unit = "bpm",
                            timestampMs = timestamp,
                            rawJson = payload.toString(),
                        )
                        WatchBridgeRuntime.state.value = WatchBridgeRuntime.state.value.copy(
                            lastHeartRateBpm = hr,
                            lastSeenMs = now,
                            paired = true,
                        )
                    }
                    respond(client, 200, JSONObject().put("ok", true))
                }

                method == "POST" && path == "/v1/watch/daily" -> {
                    val daily = metrics.saveWatchDaily(payload)
                    WatchBridgeRuntime.state.value = WatchBridgeRuntime.state.value.copy(
                        dailyContext = daily,
                        lastSeenMs = now,
                        paired = true,
                    )
                    respond(client, 200, JSONObject().put("ok", true))
                }

                method == "POST" && path == "/v1/watch/history" -> {
                    val count = metrics.saveWatchHistory(payload)
                    WatchBridgeRuntime.state.value = WatchBridgeRuntime.state.value.copy(
                        lastHistorySamples = count,
                        lastSeenMs = now,
                        paired = true,
                    )
                    respond(
                        client,
                        200,
                        JSONObject().apply {
                            put("ok", true)
                            put("stored", count)
                        },
                    )
                }

                else -> respond(client, 404, jsonError("Endpoint desconhecido"))
            }
        }
    }

    private suspend fun applyWatchAction(
        repository: StrengthExecutionRepository,
        payload: JSONObject,
    ): JSONObject {
        when (payload.optString("action").uppercase()) {
            "REFRESH" -> repository.refresh()
            "COMPLETE_CURRENT" -> {
                val current = repository.state.value.workout
                    ?.exercises
                    ?.asSequence()
                    ?.flatMap { it.sets.asSequence() }
                    ?.firstOrNull { !it.completed }
                if (current != null) {
                    val load = payload.optNullableDouble("load") ?: current.actualLoad ?: current.prescribedLoad
                    val reps = payload.optNullableInt("reps") ?: current.actualReps ?: current.prescribedReps
                    val rpe = payload.optNullableDouble("rpe") ?: current.actualRpe ?: current.prescribedRpe
                    repository.completeSet(
                        set = current,
                        weight = load,
                        reps = reps,
                        rpe = rpe?.coerceIn(5.0, 10.0),
                        comment = payload.optString("comment").takeIf { it.isNotBlank() },
                    )
                }
            }
            "EXTEND_REST" -> repository.extendRest(payload.optInt("seconds", 30).coerceIn(5, 600))
            "SKIP_REST" -> repository.clearRest()
            "UNDO_LAST_SET" -> {
                val ordered = repository.state.value.workout
                    ?.exercises
                    ?.flatMap { it.sets }
                    .orEmpty()
                val firstIncomplete = ordered.indexOfFirst { !it.completed }
                val candidate = when {
                    ordered.isEmpty() -> null
                    firstIncomplete > 0 -> ordered[firstIncomplete - 1]
                    firstIncomplete == -1 -> ordered.lastOrNull()
                    else -> null
                }
                candidate?.let { repository.undoSet(it.key) }
                repository.clearRest()
            }
            "TREADMILL_SPEED_DELTA" -> {
                val delta = payload.optDouble("delta", 0.0).coerceIn(-2.0, 2.0)
                TrainingHubRuntime.treadmillController?.changeSpeed(delta)
            }
            "TREADMILL_PAUSE" -> TrainingHubRuntime.treadmillController?.pause()
            "TREADMILL_RESUME" -> {
                val live = TrainingHubRuntime.treadmillController?.state?.value?.liveSession
                if (live?.active == true && live.paused) {
                    TrainingHubRuntime.treadmillController?.start()
                }
            }
            "TREADMILL_STOP" -> TrainingHubRuntime.treadmillController?.stop()
        }
        return buildWorkoutState(repository)
    }

    private fun buildWorkoutState(repository: StrengthExecutionRepository): JSONObject {
        val s = repository.state.value
        val workout = s.workout
        val currentExercise = workout?.exercises?.firstOrNull { ex -> ex.sets.any { !it.completed } }
        val currentSet = currentExercise?.sets?.firstOrNull { !it.completed }
        val exerciseIndex = if (currentExercise == null) -1 else workout.exercises.indexOfFirst {
            it.sourceId == currentExercise.sourceId
        }
        val setIndex = if (currentSet == null || currentExercise == null) -1 else {
            currentExercise.sets.indexOfFirst { it.key == currentSet.key }
        }
        val nextExercise = if (workout != null && exerciseIndex >= 0) {
            workout.exercises.drop(exerciseIndex + 1).firstOrNull { ex -> ex.sets.any { !it.completed } }
        } else null
        val now = System.currentTimeMillis()
        val restRemaining = s.restEndMs?.let {
            ((it - now + 999L) / 1000L).toInt().coerceAtLeast(0)
        } ?: 0

        val treadmill = TrainingHubRuntime.treadmillController?.state?.value

        return JSONObject().apply {
            put("ok", true)
            put("protocolVersion", WATCH_PROTOCOL_VERSION)
            put("demoMode", false)
            put(
                "cardio",
                JSONObject().apply {
                    put("connected", treadmill?.controlReady == true)
                    put("active", treadmill?.liveSession?.active == true)
                    put("paused", treadmill?.liveSession?.paused == true)
                    putNullable("speedKmh", treadmill?.telemetry?.speedKmh)
                    putNullable("targetSpeedKmh", treadmill?.targetSpeed)
                    putNullable("distanceKm", treadmill?.liveSession?.distanceKm)
                    put("durationSec", treadmill?.liveSession?.durationSec ?: 0)
                }
            )
            put("date", s.selectedDate)
            put("busy", s.busy)
            put("complete", workout?.complete == true)
            put("completedSets", workout?.completedSets ?: 0)
            put("totalSets", workout?.totalSets ?: 0)
            putNullable("program", workout?.program)
            putNullable("week", workout?.week)
            putNullable("session", workout?.session)
            put("exerciseIndex", exerciseIndex)
            put("exerciseCount", workout?.exercises?.size ?: 0)
            putNullable("exerciseName", currentExercise?.name)
            putNullable("nextExerciseName", nextExercise?.name)
            put("setIndex", setIndex)
            put("setCount", currentExercise?.sets?.size ?: 0)
            putNullable("load", currentSet?.actualLoad ?: currentSet?.prescribedLoad)
            putNullable("reps", currentSet?.actualReps ?: currentSet?.prescribedReps)
            putNullable("rpe", currentSet?.actualRpe ?: currentSet?.prescribedRpe)
            put("restRemainingSec", restRemaining)
            putNullable("lastPerformance", currentExercise?.lastPerformance)
            putNullable("bestRecentE1rm", currentExercise?.bestRecentE1rm)
            put("pendingSyncSets", s.analytics.pendingSyncSets)
            putNullable("watchHeartRateBpm", WatchBridgeRuntime.state.value.lastHeartRateBpm)
        }
    }

    private fun respond(socket: Socket, status: Int, json: JSONObject) {
        val body = json.toString()
        val label = when (status) {
            200 -> "OK"
            400 -> "Bad Request"
            401 -> "Unauthorized"
            404 -> "Not Found"
            else -> "Error"
        }
        val writer = BufferedWriter(OutputStreamWriter(socket.getOutputStream(), Charsets.UTF_8))
        writer.write("HTTP/1.1 $status $label\r\n")
        writer.write("Content-Type: application/json; charset=utf-8\r\n")
        writer.write("Content-Length: ${body.toByteArray(Charsets.UTF_8).size}\r\n")
        writer.write("Connection: close\r\n")
        writer.write("\r\n")
        writer.write(body)
        writer.flush()
    }

    private fun jsonError(message: String): JSONObject =
        JSONObject().apply {
            put("ok", false)
            put("error", message)
        }
}

private fun JSONObject.putNullable(key: String, value: Any?) {
    if (value == null) put(key, JSONObject.NULL) else put(key, value)
}

private fun JSONObject.optNullableInt(key: String): Int? =
    if (!has(key) || isNull(key)) null else optInt(key)

private fun JSONObject.optNullableDouble(key: String): Double? =
    if (!has(key) || isNull(key)) null else optDouble(key)
