package com.local.mobvoin4225remote

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothGattService
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.provider.OpenableColumns
import androidx.core.content.FileProvider
import java.io.File
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.health.connect.client.PermissionController
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.update
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID
import kotlin.math.max
import kotlin.math.roundToInt

private val FTMS_SERVICE: UUID = uuid16(0x1826)
private val FEATURE_UUID: UUID = uuid16(0x2ACC)
private val TREADMILL_DATA_UUID: UUID = uuid16(0x2ACD)
private val TRAINING_STATUS_UUID: UUID = uuid16(0x2AD3)
private val SPEED_RANGE_UUID: UUID = uuid16(0x2AD4)
private val INCLINE_RANGE_UUID: UUID = uuid16(0x2AD5)
private val RESISTANCE_RANGE_UUID: UUID = uuid16(0x2AD6)
private val HR_RANGE_UUID: UUID = uuid16(0x2AD7)
private val POWER_RANGE_UUID: UUID = uuid16(0x2AD8)
private val CONTROL_POINT_UUID: UUID = uuid16(0x2AD9)
private val MACHINE_STATUS_UUID: UUID = uuid16(0x2ADA)
private val CCCD_UUID: UUID = uuid16(0x2902)

private fun uuid16(value: Int): UUID =
    UUID.fromString("0000%04x-0000-1000-8000-00805f9b34fb".format(value))

data class Range3(val min: Double, val max: Double, val step: Double)

data class Telemetry(
    val speedKmh: Double? = null,
    val averageSpeedKmh: Double? = null,
    val distanceKm: Double? = null,
    val inclinationPercent: Double? = null,
    val positiveElevationM: Double? = null,
    val negativeElevationM: Double? = null,
    val calories: Int? = null,
    val heartRate: Int? = null,
    val met: Double? = null,
    val elapsedSeconds: Int? = null,
    val remainingSeconds: Int? = null,
    val forceN: Int? = null,
    val powerW: Int? = null,
)

data class LiveSession(
    val active: Boolean = false,
    val paused: Boolean = false,
    val startedAtMs: Long? = null,
    val durationSec: Int = 0,
    val distanceKm: Double = 0.0,
    val caloriesKcal: Int? = null,
    val averageSpeedKmh: Double = 0.0,
    val maxSpeedKmh: Double = 0.0,
    val averageHeartRateBpm: Int? = null,
    val maxHeartRateBpm: Int? = null,
)

data class AppState(
    val scanning: Boolean = false,
    val candidateName: String? = null,
    val candidateAddress: String? = null,
    val connectedName: String? = null,
    val connectedAddress: String? = null,
    val connection: String = "Desligada",
    val controlReady: Boolean = false,
    val targetSpeed: Double = 1.0,
    val speedRange: Range3 = Range3(1.0, 6.0, 0.5),
    val inclineRange: Range3? = null,
    val resistanceRange: Range3? = null,
    val heartRateRange: Range3? = null,
    val powerRange: Range3? = null,
    val machineFeatureBits: UInt = 0u,
    val targetFeatureBits: UInt = 0u,
    val telemetry: Telemetry = Telemetry(),
    val machineStatus: String = "—",
    val trainingStatus: String = "—",
    val lastControlResponse: String = "—",
    val savedAddress: String? = null,
    val liveSession: LiveSession = LiveSession(),
    val history: List<WorkoutSession> = emptyList(),
    val logs: List<String> = emptyList(),
)

class MainActivity : ComponentActivity() {
    private lateinit var controller: TreadmillController
    private lateinit var healthConnect: HealthConnectBridge
    private lateinit var heartRateMonitor: HeartRateMonitor
    private lateinit var trainingRepository: TrainingRepository
    private lateinit var strengthExecutionRepository: StrengthExecutionRepository

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        controller = TreadmillController(this)
        healthConnect = HealthConnectBridge(this)
        heartRateMonitor = HeartRateMonitor(this)
        trainingRepository = TrainingRepository(this)
        strengthExecutionRepository = StrengthExecutionRepository(this)
        setContent {
            MaterialTheme {
                N4225Screen(controller, healthConnect, heartRateMonitor, trainingRepository, strengthExecutionRepository)
            }
        }
    }

    override fun onDestroy() {
        heartRateMonitor.close()
        controller.close()
        super.onDestroy()
    }
}

@SuppressLint("MissingPermission")
class TreadmillController(private val context: Context) {
    private val manager = context.getSystemService(BluetoothManager::class.java)
    private val adapter: BluetoothAdapter? get() = manager?.adapter
    private val prefs = context.getSharedPreferences("n4225", Context.MODE_PRIVATE)
    private val sessionStore = SessionStore(context)

    val state = MutableStateFlow(
        AppState(
            savedAddress = prefs.getString("saved_address", null),
            history = sessionStore.load(),
        )
    )

    private var candidate: BluetoothDevice? = null
    private var gatt: BluetoothGatt? = null
    private var controlPoint: BluetoothGattCharacteristic? = null
    private var treadmillData: BluetoothGattCharacteristic? = null
    private var machineStatus: BluetoothGattCharacteristic? = null
    private var trainingStatus: BluetoothGattCharacteristic? = null

    private data class Subscription(
        val characteristic: BluetoothGattCharacteristic,
        val indication: Boolean,
    )

    private val subscriptionQueue = ArrayDeque<Subscription>()
    private val readQueue = ArrayDeque<BluetoothGattCharacteristic>()

    private var sessionBaseElapsed: Int? = null
    private var sessionBaseDistance: Double? = null
    private var sessionBaseCalories: Int? = null
    private var sessionMaxSpeed = 0.0
    private var hrSum = 0L
    private var hrSamples = 0
    private var hrMax: Int? = null
    private var pendingPause = false
    private var pendingStop = false

    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            val advertisedName = result.scanRecord?.deviceName
            val serviceMatch = result.scanRecord?.serviceUuids?.any { it.uuid == FTMS_SERVICE } == true
            val nameMatch = advertisedName?.contains("Mobvoi", ignoreCase = true) == true ||
                advertisedName?.contains("WTMP", ignoreCase = true) == true
            if (!serviceMatch && !nameMatch) return

            val device = result.device
            candidate = device
            val address = device.address
            val currentSaved = state.value.savedAddress
            if (currentSaved == null) {
                prefs.edit().putString("saved_address", address).apply()
            }

            state.update {
                it.copy(
                    candidateName = advertisedName ?: "Mobvoi WTMP",
                    candidateAddress = address,
                    savedAddress = currentSaved ?: address,
                    scanning = false,
                    connection = "Encontrada",
                )
            }
            stopScan()
            log("Encontrada ${advertisedName ?: "Mobvoi WTMP"} em $address")

            if (currentSaved == null || currentSaved == address) {
                connect(device)
            }
        }

        override fun onScanFailed(errorCode: Int) {
            state.update { it.copy(scanning = false, connection = "Falha na procura ($errorCode)") }
            log("Scan failed: $errorCode")
        }
    }

    private val callback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                gatt = g
                state.update {
                    it.copy(
                        connection = "Ligada; a preparar…",
                        connectedName = runCatching { g.device.name }.getOrNull() ?: "Mobvoi WTMP",
                        connectedAddress = g.device.address,
                        controlReady = false,
                    )
                }
                log("Ligada, GATT status $status")
                g.discoverServices()
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                if (state.value.liveSession.active) finishSession()
                if (gatt === g) gatt = null
                controlPoint = null
                state.update {
                    it.copy(
                        connection = "Desligada",
                        controlReady = false,
                        connectedName = null,
                        connectedAddress = null,
                    )
                }
                log("Desligada, GATT status $status")
                runCatching { g.close() }
            }
        }

        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
            if (status != BluetoothGatt.GATT_SUCCESS) {
                state.update { it.copy(connection = "Falha ao descobrir serviços ($status)") }
                return
            }
            val service = g.getService(FTMS_SERVICE)
            if (service == null) {
                state.update { it.copy(connection = "Serviço FTMS não encontrado") }
                return
            }
            prepareService(g, service)
        }

        override fun onDescriptorWrite(g: BluetoothGatt, descriptor: BluetoothGattDescriptor, status: Int) {
            log("CCCD ${descriptor.characteristic.uuid.short()} status=$status")
            processNextSubscription(g)
        }

        @Deprecated("Deprecated in API 33")
        override fun onCharacteristicRead(
            g: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            status: Int,
        ) {
            if (status == BluetoothGatt.GATT_SUCCESS) {
                handleRead(characteristic.uuid, characteristic.value ?: byteArrayOf())
            } else {
                log("Read ${characteristic.uuid.short()} failed: $status")
            }
            processNextRead(g)
        }

        @Deprecated("Deprecated in API 33")
        override fun onCharacteristicChanged(g: BluetoothGatt, characteristic: BluetoothGattCharacteristic) {
            handleIncoming(characteristic.uuid, characteristic.value ?: byteArrayOf())
        }

        override fun onCharacteristicWrite(
            g: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            status: Int,
        ) {
            if (status != BluetoothGatt.GATT_SUCCESS) {
                log("Write ${characteristic.uuid.short()} failed: $status")
            }
        }
    }

    fun hasPermissions(): Boolean = requiredPermissions().all {
        ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED
    }

    fun requiredPermissions(): Array<String> =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            arrayOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
        } else {
            arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)
        }

    fun autoConnect() {
        if (!hasPermissions()) return
        startScan()
    }

    fun startScan() {
        if (!hasPermissions()) {
            state.update { it.copy(connection = "É necessária permissão Bluetooth") }
            return
        }
        val a = adapter ?: run {
            state.update { it.copy(connection = "Bluetooth indisponível") }
            return
        }
        if (!a.isEnabled) {
            state.update { it.copy(connection = "Liga o Bluetooth") }
            return
        }
        stopScan()
        candidate = null
        state.update {
            it.copy(
                scanning = true,
                candidateName = null,
                candidateAddress = null,
                connection = "À procura da passadeira…",
            )
        }
        log("Procura iniciada")
        a.bluetoothLeScanner.startScan(scanCallback)
    }

    fun stopScan() {
        if (!hasPermissions()) return
        runCatching { adapter?.bluetoothLeScanner?.stopScan(scanCallback) }
        state.update { it.copy(scanning = false) }
    }

    fun connectCandidate() {
        candidate?.let { connect(it) } ?: startScan()
    }

    fun connectSaved() {
        val address = state.value.savedAddress ?: run {
            startScan()
            return
        }
        if (!hasPermissions()) return
        val device = runCatching { adapter?.getRemoteDevice(address) }.getOrNull()
        if (device == null) {
            startScan()
            return
        }
        connect(device)
    }

    fun forgetSaved() {
        disconnect()
        prefs.edit().remove("saved_address").apply()
        state.update { it.copy(savedAddress = null) }
        log("Passadeira guardada removida")
    }

    private fun connect(device: BluetoothDevice) {
        if (!hasPermissions()) return
        stopScan()
        runCatching { gatt?.close() }
        state.update { it.copy(connection = "A ligar…", controlReady = false) }
        log("A ligar a ${device.address}")
        gatt = device.connectGatt(context, false, callback, BluetoothDevice.TRANSPORT_LE)
    }

    fun disconnect() {
        stopScan()
        if (state.value.liveSession.active) finishSession()
        runCatching { gatt?.disconnect() }
        runCatching { gatt?.close() }
        gatt = null
        state.update {
            it.copy(
                connection = "Desligada",
                connectedName = null,
                connectedAddress = null,
                controlReady = false,
            )
        }
    }

    fun close() {
        disconnect()
    }

    private fun prepareService(g: BluetoothGatt, service: BluetoothGattService) {
        controlPoint = service.getCharacteristic(CONTROL_POINT_UUID)
        treadmillData = service.getCharacteristic(TREADMILL_DATA_UUID)
        machineStatus = service.getCharacteristic(MACHINE_STATUS_UUID)
        trainingStatus = service.getCharacteristic(TRAINING_STATUS_UUID)

        if (controlPoint == null) {
            state.update { it.copy(connection = "Control Point FTMS não encontrado") }
            return
        }

        subscriptionQueue.clear()
        controlPoint?.let { subscriptionQueue.add(Subscription(it, indication = true)) }
        treadmillData?.let { subscriptionQueue.add(Subscription(it, indication = false)) }
        machineStatus?.let { subscriptionQueue.add(Subscription(it, indication = false)) }
        trainingStatus?.let { subscriptionQueue.add(Subscription(it, indication = false)) }

        readQueue.clear()
        listOf(
            FEATURE_UUID,
            SPEED_RANGE_UUID,
            INCLINE_RANGE_UUID,
            RESISTANCE_RANGE_UUID,
            HR_RANGE_UUID,
            POWER_RANGE_UUID,
        ).mapNotNull { service.getCharacteristic(it) }.forEach { readQueue.add(it) }

        state.update { it.copy(connection = "A configurar FTMS…") }
        processNextSubscription(g)
    }

    private fun processNextSubscription(g: BluetoothGatt) {
        val next = subscriptionQueue.removeFirstOrNull()
        if (next == null) {
            processNextRead(g)
            return
        }

        val c = next.characteristic
        g.setCharacteristicNotification(c, true)
        val descriptor = c.getDescriptor(CCCD_UUID)
        if (descriptor == null) {
            processNextSubscription(g)
            return
        }
        descriptor.value = if (next.indication) {
            BluetoothGattDescriptor.ENABLE_INDICATION_VALUE
        } else {
            BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
        }
        if (!g.writeDescriptor(descriptor)) {
            processNextSubscription(g)
        }
    }

    private fun processNextRead(g: BluetoothGatt) {
        val next = readQueue.removeFirstOrNull()
        if (next == null) {
            state.update { it.copy(connection = "FTMS pronto; a pedir controlo…") }
            requestControl()
            return
        }
        if (!g.readCharacteristic(next)) processNextRead(g)
    }

    private fun handleRead(uuid: UUID, value: ByteArray) {
        when (uuid) {
            FEATURE_UUID -> {
                if (value.size >= 8) {
                    val machine = value.u32(0)
                    val targets = value.u32(4)
                    state.update {
                        it.copy(machineFeatureBits = machine, targetFeatureBits = targets)
                    }
                    log("Features machine=0x${machine.toString(16)} targets=0x${targets.toString(16)}")
                }
            }
            SPEED_RANGE_UUID -> parseRange16(value, 100.0)?.let { range ->
                state.update {
                    it.copy(
                        speedRange = range,
                        targetSpeed = it.targetSpeed.coerceIn(range.min, range.max),
                    )
                }
            }
            INCLINE_RANGE_UUID -> parseSignedRange16(value, 10.0)?.let { r ->
                state.update { it.copy(inclineRange = r) }
            }
            RESISTANCE_RANGE_UUID -> parseSignedRange16(value, 10.0)?.let { r ->
                state.update { it.copy(resistanceRange = r) }
            }
            HR_RANGE_UUID -> if (value.size >= 3) {
                state.update {
                    it.copy(
                        heartRateRange = Range3(
                            (value[0].toInt() and 0xFF).toDouble(),
                            (value[1].toInt() and 0xFF).toDouble(),
                            (value[2].toInt() and 0xFF).toDouble(),
                        )
                    )
                }
            }
            POWER_RANGE_UUID -> parseSignedRange16(value, 1.0)?.let { r ->
                state.update { it.copy(powerRange = r) }
            }
        }
    }

    private fun handleIncoming(uuid: UUID, value: ByteArray) {
        when (uuid) {
            CONTROL_POINT_UUID -> handleControlResponse(value)
            TREADMILL_DATA_UUID -> {
                val telemetry = parseTreadmillData(value)
                updateSessionFromTelemetry(telemetry)
                state.update { it.copy(telemetry = telemetry) }
            }
            MACHINE_STATUS_UUID -> {
                val opcode = value.firstOrNull()?.toInt()?.and(0xFF)
                val text = describeMachineStatus(value)
                state.update { it.copy(machineStatus = text) }
                when (opcode) {
                    0x04 -> {
                        if (!state.value.liveSession.active) beginSession()
                        else state.update { it.copy(liveSession = it.liveSession.copy(paused = false)) }
                        pendingPause = false
                    }
                    0x02 -> {
                        if (pendingPause) {
                            state.update { it.copy(liveSession = it.liveSession.copy(paused = true)) }
                            pendingPause = false
                        } else if (state.value.liveSession.active) {
                            finishSession()
                        }
                    }
                }
                log("Status: $text")
            }
            TRAINING_STATUS_UUID -> {
                val text = describeTrainingStatus(value)
                state.update { it.copy(trainingStatus = text) }
            }
        }
    }

    private fun handleControlResponse(value: ByteArray) {
        if (value.size < 3 || (value[0].toInt() and 0xFF) != 0x80) return
        val request = value[1].toInt() and 0xFF
        val result = value[2].toInt() and 0xFF
        val label = resultLabel(result)
        val message = "${opcodeName(request)}: $label"
        state.update {
            it.copy(
                controlReady = if (request == 0x00) result == 0x01 else it.controlReady,
                connection = if (request == 0x00 && result == 0x01) "Control ready" else it.connection,
                lastControlResponse = message,
            )
        }
        if (result == 0x01) {
            when (request) {
                0x07 -> {
                    if (!state.value.liveSession.active) beginSession()
                    else state.update { it.copy(liveSession = it.liveSession.copy(paused = false)) }
                }
                0x08 -> {
                    if (pendingStop) {
                        finishSession()
                        pendingStop = false
                    }
                }
            }
        }
        log("Control response $message")
    }

    fun requestControl() = sendControl(byteArrayOf(0x00))

    fun start() {
        pendingPause = false
        pendingStop = false
        sendControl(byteArrayOf(0x07))
    }

    fun stop() {
        pendingStop = true
        pendingPause = false
        sendControl(byteArrayOf(0x08, 0x01))
    }

    fun pause() {
        pendingPause = true
        pendingStop = false
        sendControl(byteArrayOf(0x08, 0x02))
    }

    fun setSpeed(kmh: Double) {
        val r = state.value.speedRange
        val rounded = (((kmh - r.min) / r.step).roundToInt() * r.step + r.min).coerceIn(r.min, r.max)
        val raw = (rounded * 100.0).roundToInt()
        state.update { it.copy(targetSpeed = rounded) }
        sendControl(byteArrayOf(0x02, (raw and 0xFF).toByte(), ((raw ushr 8) and 0xFF).toByte()))
    }

    fun changeSpeed(delta: Double) = setSpeed(state.value.targetSpeed + delta)

    fun deleteSession(id: Long) {
        val updated = state.value.history.filterNot { it.id == id }
        sessionStore.save(updated)
        state.update { it.copy(history = updated) }
    }

    fun markHealthExported(id: Long) {
        val updated = state.value.history.map {
            if (it.id == id) it.copy(healthConnectExported = true) else it
        }
        sessionStore.save(updated)
        state.update { it.copy(history = updated) }
    }

    fun updateExternalHeartRate(bpm: Int?) {
        bpm ?: return
        if (bpm !in 20..250) return

        val live = state.value.liveSession
        state.update { it.copy(telemetry = it.telemetry.copy(heartRate = bpm)) }

        if (!live.active || live.paused) return

        hrSum += bpm
        hrSamples += 1
        hrMax = max(hrMax ?: bpm, bpm)

        state.update {
            it.copy(
                liveSession = it.liveSession.copy(
                    averageHeartRateBpm = (hrSum / hrSamples).toInt(),
                    maxHeartRateBpm = hrMax,
                )
            )
        }
    }

    private fun beginSession() {
        if (state.value.liveSession.active) return
        val t = state.value.telemetry
        sessionBaseElapsed = t.elapsedSeconds
        sessionBaseDistance = t.distanceKm
        sessionBaseCalories = t.calories
        sessionMaxSpeed = t.speedKmh ?: 0.0
        hrSum = 0L
        hrSamples = 0
        hrMax = null
        val started = System.currentTimeMillis()
        state.update {
            it.copy(
                liveSession = LiveSession(
                    active = true,
                    paused = false,
                    startedAtMs = started,
                    maxSpeedKmh = sessionMaxSpeed,
                )
            )
        }
        log("Sessão iniciada")
    }

    private fun updateSessionFromTelemetry(t: Telemetry) {
        val live = state.value.liveSession
        if (!live.active || live.paused) return

        val duration = deltaCounter(t.elapsedSeconds, sessionBaseElapsed)
            ?: ((System.currentTimeMillis() - (live.startedAtMs ?: System.currentTimeMillis())) / 1000L).toInt()
        val distance = deltaCounter(t.distanceKm, sessionBaseDistance) ?: live.distanceKm
        val calories = deltaCounter(t.calories, sessionBaseCalories)

        t.speedKmh?.let { sessionMaxSpeed = max(sessionMaxSpeed, it) }
        t.heartRate?.let { hr ->
            hrSum += hr
            hrSamples += 1
            hrMax = max(hrMax ?: hr, hr)
        }

        val avgSpeed = if (duration > 0) distance * 3600.0 / duration else 0.0
        state.update {
            it.copy(
                liveSession = it.liveSession.copy(
                    durationSec = duration.coerceAtLeast(0),
                    distanceKm = distance.coerceAtLeast(0.0),
                    caloriesKcal = calories?.coerceAtLeast(0),
                    averageSpeedKmh = avgSpeed.coerceAtLeast(0.0),
                    maxSpeedKmh = sessionMaxSpeed,
                    averageHeartRateBpm = if (hrSamples > 0) (hrSum / hrSamples).toInt() else null,
                    maxHeartRateBpm = hrMax,
                )
            )
        }
    }

    private fun finishSession() {
        val live = state.value.liveSession
        if (!live.active) return

        val end = System.currentTimeMillis()
        val duration = if (live.durationSec > 0) live.durationSec
        else ((end - (live.startedAtMs ?: end)) / 1000L).toInt()

        if (duration >= 5 || live.distanceKm >= 0.005) {
            val session = WorkoutSession(
                id = live.startedAtMs ?: end,
                startedAtMs = live.startedAtMs ?: end,
                endedAtMs = end,
                durationSec = duration,
                distanceKm = live.distanceKm,
                averageSpeedKmh = live.averageSpeedKmh,
                maxSpeedKmh = live.maxSpeedKmh,
                caloriesKcal = live.caloriesKcal,
                averageHeartRateBpm = live.averageHeartRateBpm,
                maxHeartRateBpm = live.maxHeartRateBpm,
                healthConnectExported = false,
            )
            val updated = (listOf(session) + state.value.history).distinctBy { it.id }.sortedByDescending { it.startedAtMs }
            sessionStore.save(updated)
            state.update { it.copy(history = updated, liveSession = LiveSession()) }
            log("Sessão gravada")
        } else {
            state.update { it.copy(liveSession = LiveSession()) }
            log("Sessão curta descartada")
        }

        sessionBaseElapsed = null
        sessionBaseDistance = null
        sessionBaseCalories = null
        pendingPause = false
        pendingStop = false
    }

    private fun sendControl(bytes: ByteArray) {
        val g = gatt ?: run {
            state.update { it.copy(connection = "Não ligada") }
            return
        }
        val c = controlPoint ?: return
        c.writeType = BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
        c.value = bytes
        val started = g.writeCharacteristic(c)
        log("TX ${bytes.toHex()} ${if (started) "" else "(não iniciado)"}")
        if (!started) state.update { it.copy(lastControlResponse = "Android não iniciou o write") }
    }

    private fun log(message: String) {
        val line = "${System.currentTimeMillis() % 100000}: $message"
        state.update { s -> s.copy(logs = (listOf(line) + s.logs).take(80)) }
    }
}

@Composable
private fun N4225Screen(
    controller: TreadmillController,
    healthConnect: HealthConnectBridge,
    heartRateMonitor: HeartRateMonitor,
    trainingRepository: TrainingRepository,
    strengthExecutionRepository: StrengthExecutionRepository,
) {
    val state by controller.state.collectAsState()
    val watch by heartRateMonitor.state.collectAsState()
    val strength by trainingRepository.state.collectAsState()
    val execution by strengthExecutionRepository.state.collectAsState()
    val context = LocalContext.current
    var page by remember { mutableIntStateOf(0) }
    var showStartConfirm by remember { mutableStateOf(false) }
    var healthAvailable by remember { mutableStateOf(false) }
    var healthGranted by remember { mutableStateOf(false) }
    var healthMessage by remember { mutableStateOf<String?>(null) }

    val importScope = rememberCoroutineScope()

    val msbImportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            val stream = context.contentResolver.openInputStream(uri)
            if (stream != null) {
                importScope.launch {
                    stream.use { trainingRepository.importMsb(it) }
                    strengthExecutionRepository.refresh()
                }
            }
        }
    }

    val fitNotesImportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            val name = queryDisplayName(context, uri)
            val stream = context.contentResolver.openInputStream(uri)
            if (stream != null) {
                importScope.launch {
                    stream.use { trainingRepository.importFitNotes(it, name) }
                    strengthExecutionRepository.refresh()
                }
            }
        }
    }

    var pendingVideoSet by remember { mutableStateOf<WorkoutSetPlan?>(null) }
    var pendingVideoUri by remember { mutableStateOf<android.net.Uri?>(null) }

    val videoLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CaptureVideo()
    ) { success ->
        val set = pendingVideoSet
        val uri = pendingVideoUri
        if (success && set != null && uri != null) {
            importScope.launch {
                strengthExecutionRepository.attachVideo(set, uri.toString())
            }
        }
        pendingVideoSet = null
        pendingVideoUri = null
    }

    var pendingChosenVideoSet by remember { mutableStateOf<WorkoutSetPlan?>(null) }
    val chooseVideoLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        val set = pendingChosenVideoSet
        if (uri != null && set != null) {
            runCatching {
                context.contentResolver.takePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION,
                )
            }
            importScope.launch {
                strengthExecutionRepository.attachVideo(set, uri.toString())
            }
        }
        pendingChosenVideoSet = null
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) {
        if (controller.hasPermissions()) {
            controller.autoConnect()
            if (watch.savedAddress != null) heartRateMonitor.autoConnect()
        }
    }

    val healthPermissionLauncher = rememberLauncherForActivityResult(
        PermissionController.createRequestPermissionResultContract()
    ) { granted ->
        healthGranted = granted.containsAll(healthConnect.permissions)
        healthMessage = if (healthGranted) "Health Connect ativo." else "Permissões do Health Connect não concedidas."
    }

    LaunchedEffect(Unit) {
        if (!controller.hasPermissions()) {
            permissionLauncher.launch(controller.requiredPermissions())
        } else {
            if (watch.savedAddress != null) heartRateMonitor.autoConnect()
        }
        healthAvailable = healthConnect.isAvailable()
        healthGranted = if (healthAvailable) healthConnect.hasPermissions() else false
    }

    LaunchedEffect(watch.heartRateBpm) {
        controller.updateExternalHeartRate(watch.heartRateBpm)
    }

    LaunchedEffect(page) {
        if (page == 1) trainingRepository.refresh()
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .navigationBarsPadding()
            .padding(horizontal = 14.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Column {
                Text("Training Hub", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
                Text(
                    execution.workout?.let { workout ->
                        "Treino de força · ${workout.completedSets}/${workout.totalSets} séries"
                    } ?: watch.heartRateBpm?.let { bpm ->
                        "Relógio · $bpm bpm"
                    } ?: "Treino, cardio e histórico",
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
            if (page == 2 && !state.controlReady) {
                OutlinedButton(onClick = {
                    if (controller.hasPermissions()) controller.autoConnect()
                    else permissionLauncher.launch(controller.requiredPermissions())
                }) {
                    Text("Ligar passadeira")
                }
            }
        }

        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(2.dp)) {
            NavButton("Hoje", page == 0, Modifier.weight(1f)) { page = 0 }
            NavButton("Calendário", page == 1, Modifier.weight(1f)) { page = 1 }
            NavButton("Cardio", page == 2, Modifier.weight(1f)) { page = 2 }
            NavButton("Mais", page == 3, Modifier.weight(1f)) { page = 3 }
        }

        when (page) {
            0 -> TodayStrengthPage(
                execution = execution,
                watch = watch,
                repository = strengthExecutionRepository,
                onFilmSet = { set ->
                    val uri = createWorkoutVideoUri(context, set.key)
                    pendingVideoSet = set
                    pendingVideoUri = uri
                    videoLauncher.launch(uri)
                },
                onChooseVideo = { set ->
                    pendingChosenVideoSet = set
                    chooseVideoLauncher.launch(arrayOf("video/*"))
                },
            )
            1 -> StrengthPage(
                s = strength,
                execution = execution,
                executionRepository = strengthExecutionRepository,
                onOpenDay = { date ->
                    importScope.launch {
                        strengthExecutionRepository.refresh(date)
                        page = 0
                    }
                },
                importMsb = { msbImportLauncher.launch(arrayOf("application/json", "text/plain", "*/*")) },
                importFitNotes = { fitNotesImportLauncher.launch(arrayOf("application/zip", "application/octet-stream", "*/*")) },
            )
            2 -> WorkoutPage(state, watch, controller) { showStartConfirm = true }
            else -> MorePage(
                s = state,
                watch = watch,
                execution = execution,
                strengthRepository = strengthExecutionRepository,
                c = controller,
                heartRateMonitor = heartRateMonitor,
                healthAvailable = healthAvailable,
                healthGranted = healthGranted,
                requestHealthPermissions = { healthPermissionLauncher.launch(healthConnect.permissions) },
                healthMessage = healthMessage,
            )
        }
    }

    if (showStartConfirm) {
        AlertDialog(
            onDismissRequest = { showStartConfirm = false },
            title = { Text("Iniciar passadeira?") },
            text = { Text("Confirma que a zona do tapete está livre e que consegues alcançar o interruptor físico.") },
            confirmButton = {
                Button(onClick = {
                    showStartConfirm = false
                    controller.start()
                }) { Text("START") }
            },
            dismissButton = {
                TextButton(onClick = { showStartConfirm = false }) { Text("Cancelar") }
            }
        )
    }
}

@Composable
private fun NavButton(label: String, selected: Boolean, modifier: Modifier = Modifier, onClick: () -> Unit) {
    if (selected) {
        Button(onClick = onClick, modifier = modifier) { Text(label) }
    } else {
        TextButton(onClick = onClick, modifier = modifier) { Text(label) }
    }
}

@Composable
private fun WorkoutPage(
    s: AppState,
    watch: HeartRateState,
    c: TreadmillController,
    startWithConfirm: () -> Unit,
) {
    val live = s.liveSession
    val actualSpeed = s.telemetry.speedKmh ?: 0.0

    LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(if (live.active) "TREINO EM CURSO" else "VELOCIDADE", style = MaterialTheme.typography.labelLarge)
                    Text(
                        "%.1f km/h".format(Locale.US, actualSpeed),
                        style = MaterialTheme.typography.displayLarge,
                        fontWeight = FontWeight.Bold,
                    )
                    Text("Alvo: %.1f km/h".format(Locale.US, s.targetSpeed))
                    Text(
                        when {
                            watch.heartRateBpm != null -> "Relógio: ${watch.heartRateBpm} bpm"
                            watch.connectedAddress != null -> "Relógio ligado · à espera de FC"
                            else -> "Relógio não ligado"
                        },
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Button(
                            onClick = { c.changeSpeed(-s.speedRange.step) },
                            enabled = s.controlReady,
                            modifier = Modifier.weight(1f),
                        ) { Text("− 0,5") }
                        Button(
                            onClick = { c.changeSpeed(s.speedRange.step) },
                            enabled = s.controlReady,
                            modifier = Modifier.weight(1f),
                        ) { Text("+ 0,5") }
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        var v = s.speedRange.min
                        while (v <= s.speedRange.max + 0.001) {
                            val speed = v
                            OutlinedButton(
                                onClick = { c.setSpeed(speed) },
                                enabled = s.controlReady,
                            ) { Text("%.1f".format(Locale.US, speed)) }
                            v += s.speedRange.step
                        }
                    }
                }
            }
        }

        item {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                SmallMetric("Tempo", formatTime(live.durationSec), Modifier.weight(1f))
                SmallMetric("Distância", "%.2f km".format(Locale.US, live.distanceKm), Modifier.weight(1f))
                SmallMetric("FC", watch.heartRateBpm?.let { "$it bpm" } ?: "—", Modifier.weight(1f))
            }
        }

        item {
            if (!live.active) {
                Button(
                    onClick = startWithConfirm,
                    enabled = s.controlReady,
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("START") }
            } else {
                Button(
                    onClick = { c.stop() },
                    enabled = s.controlReady,
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("STOP E GRAVAR") }

                if (live.paused) {
                    OutlinedButton(
                        onClick = startWithConfirm,
                        enabled = s.controlReady,
                        modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
                    ) { Text("RETOMAR") }
                } else {
                    OutlinedButton(
                        onClick = { c.pause() },
                        enabled = s.controlReady,
                        modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
                    ) { Text("PAUSA") }
                }
            }
        }

        item {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("Sessão", fontWeight = FontWeight.Bold)
                    Text("Média: %.1f km/h".format(Locale.US, live.averageSpeedKmh))
                    Text("Máxima: %.1f km/h".format(Locale.US, live.maxSpeedKmh))
                    Text("Calorias: ${live.caloriesKcal?.let { "$it kcal" } ?: "—"}")
                    Text("FC média: ${live.averageHeartRateBpm?.let { "$it bpm" } ?: "—"}")
                    Text("FC máxima: ${live.maxHeartRateBpm?.let { "$it bpm" } ?: "—"}")
                    if (!live.active) {
                        Text("Ao carregar START, a sessão começa a ser gravada automaticamente.")
                    }
                }
            }
        }
    }
}

@Composable
private fun SmallMetric(label: String, value: String, modifier: Modifier = Modifier) {
    Card(modifier) {
        Column(Modifier.padding(10.dp)) {
            Text(label, style = MaterialTheme.typography.labelMedium)
            Text(value, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        }
    }
}

@Composable
private fun TodayStrengthPage(
    execution: StrengthExecutionState,
    watch: HeartRateState,
    repository: StrengthExecutionRepository,
    onFilmSet: (WorkoutSetPlan) -> Unit,
    onChooseVideo: (WorkoutSetPlan) -> Unit,
) {
    val scope = rememberCoroutineScope()
    val haptic = LocalHapticFeedback.current
    var freeExerciseName by remember { mutableStateOf("") }
    val exerciseSuggestions = remember(freeExerciseName, execution.knownExercises) {
        if (freeExerciseName.length < 2) emptyList()
        else execution.knownExercises
            .filter { it.contains(freeExerciseName, ignoreCase = true) }
            .take(6)
    }
    var restEndMs by remember { mutableStateOf<Long?>(null) }
    var restRemaining by remember { mutableIntStateOf(0) }

    LaunchedEffect(restEndMs) {
        while (restEndMs != null) {
            val remaining = (((restEndMs ?: 0L) - System.currentTimeMillis() + 999L) / 1000L).toInt()
            restRemaining = remaining.coerceAtLeast(0)
            if (remaining <= 0) {
                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                restEndMs = null
                break
            }
            delay(1000)
        }
    }

    LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                OutlinedButton(onClick = { scope.launch { repository.previousDay() } }) { Text("‹") }
                Column {
                    Text(execution.selectedDate, fontWeight = FontWeight.Bold)
                    TextButton(onClick = { scope.launch { repository.goToday() } }) { Text("Hoje") }
                }
                OutlinedButton(onClick = { scope.launch { repository.nextDay() } }) { Text("›") }
            }
        }

        item {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    Text("Últimos 30 dias", fontWeight = FontWeight.Bold)
                    Text(
                        "${execution.analytics.trainingDays30} dias · ${execution.analytics.sets30} séries · " +
                            "%.0f kg de volume".format(Locale.US, execution.analytics.tonnage30)
                    )
                    if (execution.analytics.pendingSyncSets > 0) {
                        Text(
                            "${execution.analytics.pendingSyncSets} séries locais pendentes de sincronização MSB",
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
            }
        }

        if (restEndMs != null) {
            item {
                Card(Modifier.fillMaxWidth()) {
                    Row(
                        Modifier.fillMaxWidth().padding(14.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Column {
                            Text("Descanso", fontWeight = FontWeight.Bold)
                            Text(formatTime(restRemaining), style = MaterialTheme.typography.headlineMedium)
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            OutlinedButton(onClick = {
                                restEndMs = (restEndMs ?: System.currentTimeMillis()) + 30_000L
                            }) { Text("+30s") }
                            TextButton(onClick = { restEndMs = null }) { Text("Saltar") }
                        }
                    }
                }
            }
        }

        val workout = execution.workout
        if (workout == null) {
            item {
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("Sem treino planeado", fontWeight = FontWeight.Bold)
                        Text("Podes treinar na mesma sem depender do MyStrengthBook.")
                        OutlinedTextField(
                            value = freeExerciseName,
                            onValueChange = { freeExerciseName = it },
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true,
                            label = { Text("Adicionar exercício livre") },
                        )
                        if (exerciseSuggestions.isNotEmpty()) {
                            Row(
                                Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                                horizontalArrangement = Arrangement.spacedBy(4.dp),
                            ) {
                                exerciseSuggestions.forEach { suggestion ->
                                    TextButton(onClick = { freeExerciseName = suggestion }) {
                                        Text(suggestion)
                                    }
                                }
                            }
                        }
                        Button(
                            onClick = {
                                val name = freeExerciseName
                                freeExerciseName = ""
                                scope.launch { repository.addFreeExercise(name) }
                            },
                            enabled = freeExerciseName.isNotBlank(),
                        ) { Text("Começar treino livre") }
                        execution.lastMessage?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                    }
                }
            }
        } else {
            item {
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(
                            if (workout.complete) "Treino completo" else "Treino de força",
                            fontWeight = FontWeight.Bold,
                        )
                        Text("${workout.completedSets}/${workout.totalSets} séries concluídas")
                        val planBits = buildList {
                            workout.program?.let { add("Programa $it") }
                            workout.week?.let { add("Semana $it") }
                            workout.session?.let { add("Sessão $it") }
                        }
                        if (planBits.isNotEmpty()) {
                            Text(planBits.joinToString(" · "), style = MaterialTheme.typography.bodySmall)
                        }
                        Text("FC: ${watch.heartRateBpm?.let { "$it bpm" } ?: "—"}")
                        execution.lastMessage?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                    }
                }
            }

            items(workout.exercises, key = { it.sourceId }) { exercise ->
                StrengthExerciseCard(
                    exercise = exercise,
                    repository = repository,
                    onRest = { seconds ->
                        restEndMs = System.currentTimeMillis() + seconds * 1000L
                    },
                    onFilmSet = onFilmSet,
                    onChooseVideo = onChooseVideo,
                )
            }

            item {
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text("Adicionar exercício extra", fontWeight = FontWeight.Bold)
                        OutlinedTextField(
                            value = freeExerciseName,
                            onValueChange = { freeExerciseName = it },
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true,
                            label = { Text("Exercício") },
                        )
                        if (exerciseSuggestions.isNotEmpty()) {
                            Row(
                                Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                                horizontalArrangement = Arrangement.spacedBy(4.dp),
                            ) {
                                exerciseSuggestions.forEach { suggestion ->
                                    TextButton(onClick = { freeExerciseName = suggestion }) {
                                        Text(suggestion)
                                    }
                                }
                            }
                        }
                        Button(
                            onClick = {
                                val name = freeExerciseName
                                freeExerciseName = ""
                                scope.launch { repository.addFreeExercise(name) }
                            },
                            enabled = freeExerciseName.isNotBlank(),
                        ) { Text("Adicionar") }
                    }
                }
            }
        }
    }
}

@Composable
private fun StrengthExerciseCard(
    exercise: WorkoutExercisePlan,
    repository: StrengthExecutionRepository,
    onRest: (Int) -> Unit,
    onFilmSet: (WorkoutSetPlan) -> Unit,
    onChooseVideo: (WorkoutSetPlan) -> Unit,
) {
    val scope = rememberCoroutineScope()
    val nextIncomplete = exercise.sets.firstOrNull { !it.completed }

    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(exercise.name, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            exercise.notes?.takeIf { it.isNotBlank() }?.let {
                Text(it, style = MaterialTheme.typography.bodySmall)
            }
            if (exercise.mappedFitNotesName != null) {
                Text(
                    "FitNotes: ${exercise.mappedFitNotesName} · descanso ${formatTime(exercise.restSeconds)}",
                    style = MaterialTheme.typography.bodySmall,
                )
            } else {
                Text("Descanso: ${formatTime(exercise.restSeconds)}", style = MaterialTheme.typography.bodySmall)
            }
            exercise.lastPerformance?.let {
                Text(it, style = MaterialTheme.typography.bodySmall)
            }
            if (exercise.isFree) {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    TextButton(onClick = {
                        scope.launch { repository.addFreeSet(exercise.sourceId) }
                    }) { Text("+ série") }
                    TextButton(onClick = {
                        scope.launch { repository.deleteFreeExercise(exercise.sourceId) }
                    }) { Text("Remover exercício") }
                }
            }
            exercise.bestRecentE1rm?.let {
                Text(
                    "Melhor e1RM recente: %.1f kg".format(Locale.US, it),
                    style = MaterialTheme.typography.bodySmall,
                )
            }

            if (nextIncomplete != null) {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    OutlinedButton(onClick = { onFilmSet(nextIncomplete) }) {
                        Text(if (nextIncomplete.videoUri != null) "Filmar de novo" else "Filmar próxima")
                    }
                    OutlinedButton(onClick = { onChooseVideo(nextIncomplete) }) {
                        Text("Escolher vídeo")
                    }
                }
            }

            exercise.sets.forEachIndexed { index, set ->
                StrengthSetRow(
                    number = index + 1,
                    set = set,
                    historicalBestE1rm = exercise.bestRecentE1rm,
                    repository = repository,
                    onRest = onRest,
                )
            }
        }
    }
}

@Composable
private fun StrengthSetRow(
    number: Int,
    set: WorkoutSetPlan,
    historicalBestE1rm: Double?,
    repository: StrengthExecutionRepository,
    onRest: (Int) -> Unit,
) {
    val scope = rememberCoroutineScope()
    var weight by remember(set.key, set.actualLoad, set.prescribedLoad) {
        mutableStateOf(formatEditable(set.actualLoad ?: set.prescribedLoad))
    }
    var reps by remember(set.key, set.actualReps, set.prescribedReps) {
        mutableStateOf((set.actualReps ?: set.prescribedReps)?.toString().orEmpty())
    }
    var rpe by remember(set.key, set.actualRpe, set.prescribedRpe) {
        mutableStateOf(formatEditable(set.actualRpe ?: set.prescribedRpe))
    }
    var comment by remember(set.key, set.comment) { mutableStateOf(set.comment.orEmpty()) }

    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("Série $number", fontWeight = FontWeight.Bold)
                Text(if (set.completed) "✓ feita" else "por fazer")
            }

            Text(
                "Prescrito: ${formatPrescription(set)}",
                style = MaterialTheme.typography.bodySmall,
            )

            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                OutlinedTextField(
                    value = weight,
                    onValueChange = { weight = it },
                    modifier = Modifier.weight(1f),
                    singleLine = true,
                    label = { Text("kg") },
                )
                OutlinedTextField(
                    value = reps,
                    onValueChange = { reps = it },
                    modifier = Modifier.weight(1f),
                    singleLine = true,
                    label = { Text("reps") },
                )
                OutlinedTextField(
                    value = rpe,
                    onValueChange = { rpe = it },
                    modifier = Modifier.weight(1f),
                    singleLine = true,
                    label = { Text("RPE") },
                )
            }

            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                TextButton(onClick = {
                    val value = weight.replace(",", ".").toDoubleOrNull() ?: 0.0
                    weight = formatEditable((value - 2.5).coerceAtLeast(0.0))
                }) { Text("−2,5 kg") }
                TextButton(onClick = {
                    val value = weight.replace(",", ".").toDoubleOrNull() ?: 0.0
                    weight = formatEditable(value + 2.5)
                }) { Text("+2,5 kg") }
                TextButton(onClick = {
                    val value = reps.toIntOrNull() ?: 0
                    reps = (value - 1).coerceAtLeast(0).toString()
                }) { Text("−1 rep") }
                TextButton(onClick = {
                    val value = reps.toIntOrNull() ?: 0
                    reps = (value + 1).toString()
                }) { Text("+1 rep") }
            }

            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                OutlinedButton(onClick = {
                    val value = (rpe.replace(",", ".").toDoubleOrNull() ?: 5.0)
                    rpe = formatEditable((value - 0.5).coerceAtLeast(5.0))
                }) { Text("RPE −0,5") }
                OutlinedButton(onClick = {
                    val value = (rpe.replace(",", ".").toDoubleOrNull() ?: 5.0)
                    rpe = formatEditable((value + 0.5).coerceAtMost(10.0))
                }) { Text("RPE +0,5") }
            }

            OutlinedTextField(
                value = comment,
                onValueChange = { comment = it },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                label = { Text("Nota (opcional)") },
            )

            if (set.videoUri != null) {
                Text("🎥 vídeo associado", style = MaterialTheme.typography.bodySmall)
            }

            if (set.completed) {
                val e1rm = estimateE1rm(set.actualLoad, set.actualReps, set.actualRpe)
                if (e1rm != null) {
                    Text("e1RM estimado: %.1f kg".format(Locale.US, e1rm))
                    if (historicalBestE1rm != null && e1rm > historicalBestE1rm + 0.1) {
                        Text("NOVO PR de e1RM", fontWeight = FontWeight.Bold)
                    }
                }
                val deltas = buildList {
                    if (set.actualLoad != null && set.prescribedLoad != null) {
                        val d = set.actualLoad - set.prescribedLoad
                        if (kotlin.math.abs(d) >= 0.01) add("carga %+.1f kg".format(Locale.US, d))
                    }
                    if (set.actualReps != null && set.prescribedReps != null) {
                        val d = set.actualReps - set.prescribedReps
                        if (d != 0) add("reps %+d".format(d))
                    }
                    if (set.actualRpe != null && set.prescribedRpe != null) {
                        val d = set.actualRpe - set.prescribedRpe
                        if (kotlin.math.abs(d) >= 0.01) add("RPE %+.1f".format(Locale.US, d))
                    }
                }
                if (deltas.isNotEmpty()) {
                    Text("Vs prescrito: ${deltas.joinToString(" · ")}", style = MaterialTheme.typography.bodySmall)
                }
                TextButton(onClick = { scope.launch { repository.undoSet(set.key) } }) {
                    Text("Desfazer")
                }
            } else {
                Button(
                    onClick = {
                        val w = weight.replace(",", ".").toDoubleOrNull()
                        val rp = reps.toIntOrNull()
                        val r = rpe.replace(",", ".").toDoubleOrNull()?.coerceIn(5.0, 10.0)
                        scope.launch {
                            val rest = repository.completeSet(set, w, rp, r, comment)
                            onRest(rest)
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text("✓ Concluir série")
                }
            }
        }
    }
}

private fun formatPrescription(set: WorkoutSetPlan): String {
    val parts = mutableListOf<String>()
    set.prescribedLoad?.let { parts += "${formatEditable(it)} kg" }
    set.prescribedReps?.let { parts += "× $it" }
    set.prescribedRpe?.let { parts += "@${formatEditable(it)}" }
    return if (parts.isEmpty()) "livre" else parts.joinToString(" ")
}

private fun formatEditable(value: Double?): String =
    value?.let {
        if (it % 1.0 == 0.0) it.toInt().toString()
        else "%.1f".format(Locale.US, it)
    }.orEmpty()

private fun estimateE1rm(weight: Double?, reps: Int?, rpe: Double?): Double? {
    if (weight == null || reps == null || weight <= 0 || reps <= 0) return null
    val rir = if (rpe == null) 0.0 else (10.0 - rpe).coerceIn(0.0, 5.0)
    val equivalentReps = reps + rir
    return weight * (1.0 + equivalentReps / 30.0)
}

@Composable
private fun StrengthPage(
    s: StrengthDataState,
    execution: StrengthExecutionState,
    executionRepository: StrengthExecutionRepository,
    onOpenDay: (String) -> Unit,
    importMsb: () -> Unit,
    importFitNotes: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Dados de força", fontWeight = FontWeight.Bold)
                    Text(
                        "Os dados ficam apenas no telemóvel. Os backups pessoais não são enviados para o repositório GitHub.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = importMsb, enabled = !s.busy) { Text("Importar MSB") }
                        Button(onClick = importFitNotes, enabled = !s.busy) { Text("Importar FitNotes") }
                    }
                    if (s.busy) Text("A importar…")
                    s.lastMessage?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                }
            }
        }

        item {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("Fontes", fontWeight = FontWeight.Bold)
                    Text(
                        if (s.msb.imported) "MyStrengthBook ✓ — ${s.msb.details}"
                        else "MyStrengthBook — ainda não importado"
                    )
                    Text(
                        if (s.fitNotes.imported) "FitNotes ✓ — ${s.fitNotes.details}"
                        else "FitNotes — ainda não importado"
                    )
                }
            }
        }

        item {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("Matching MSB ↔ FitNotes", fontWeight = FontWeight.Bold)
                    Text("${execution.autoMappedCount} exercícios ligados automaticamente com ≥95% de confiança")
                    if (execution.reviewMappings.isNotEmpty()) {
                        Text("${execution.reviewMappings.size} candidatos por confirmar", style = MaterialTheme.typography.bodyMedium)
                        execution.reviewMappings.take(8).forEach { candidate ->
                            Card(Modifier.fillMaxWidth()) {
                                Column(
                                    Modifier.padding(8.dp),
                                    verticalArrangement = Arrangement.spacedBy(4.dp),
                                ) {
                                    Text(candidate.msbName, fontWeight = FontWeight.Bold)
                                    Text(
                                        "Sugestão: ${candidate.fitNotesName ?: "sem candidato"} · ${(candidate.confidence * 100).toInt()}%",
                                        style = MaterialTheme.typography.bodySmall,
                                    )
                                    if (candidate.fitNotesName != null) {
                                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                            TextButton(onClick = {
                                                scope.launch {
                                                    executionRepository.acceptSuggestedMapping(candidate.msbName)
                                                }
                                            }) { Text("Confirmar") }
                                            TextButton(onClick = {
                                                scope.launch {
                                                    executionRepository.rejectSuggestedMapping(candidate.msbName)
                                                }
                                            }) { Text("Não corresponde") }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }

        item {
            Text("Calendário / reconciliação", fontWeight = FontWeight.Bold)
        }

        if (s.days.isEmpty()) {
            item {
                Text("Importa primeiro o msb_capture.json e o backup .fitnotes/.zip.")
            }
        } else {
            items(s.days.take(120), key = { it.date }) { day ->
                StrengthDayCard(day, onOpenDay)
            }
        }
    }
}

@Composable
private fun StrengthDayCard(day: StrengthDaySummary, onOpen: (String) -> Unit) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(day.date, fontWeight = FontWeight.Bold)
            val status = when {
                day.localSets > 0 && day.hasPlan -> "Em execução no Training Hub"
                day.localSets > 0 -> "Executado no Training Hub"
                day.hasPlan && day.fitNotesSets > 0 -> "Planeado + execução FitNotes"
                day.hasPlan && day.msbActualSets > 0 -> "Planeado + execução MSB"
                day.hasPlan -> "Planeado"
                day.hasActual -> "Executado"
                else -> "—"
            }
            Text(status)
            if (day.hasPlan) {
                Text("${day.plannedExercises} exercícios · ${day.plannedSetGroups} grupos prescritos")
            }
            if (day.msbActualSets > 0 || day.fitNotesSets > 0 || day.localSets > 0) {
                Text(
                    "Séries: Hub ${day.localSets} · MSB ${day.msbActualSets} · FitNotes ${day.fitNotesSets}"
                )
            }
            if (day.localFreeExercises > 0) {
                Text("${day.localFreeExercises} exercícios livres", style = MaterialTheme.typography.bodySmall)
            }
            if (day.exerciseNames.isNotEmpty()) {
                Text(
                    day.exerciseNames.take(6).joinToString(" · "),
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            if (day.hasPlan) {
                TextButton(onClick = { onOpen(day.date) }) { Text("Abrir treino") }
            }
        }
    }
}

@Composable
private fun HistoryPage(
    s: AppState,
    c: TreadmillController,
    healthConnect: HealthConnectBridge,
    healthAvailable: Boolean,
    healthGranted: Boolean,
    requestHealthPermissions: () -> Unit,
    onHealthMessage: (String) -> Unit,
    healthMessage: String?,
) {
    val totalDistance = s.history.sumOf { it.distanceKm }
    val totalSeconds = s.history.sumOf { it.durationSec }
    val scope = rememberCoroutineScope()

    LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    Text("Resumo", fontWeight = FontWeight.Bold)
                    Text("${s.history.size} sessões · %.1f km · %s".format(Locale.US, totalDistance, formatLongDuration(totalSeconds)))
                    if (healthMessage != null) Text(healthMessage, style = MaterialTheme.typography.bodySmall)
                }
            }
        }

        if (s.history.isEmpty()) {
            item {
                Text("Ainda não há sessões gravadas. A próxima fica guardada automaticamente quando terminares com STOP.")
            }
        } else {
            items(s.history, key = { it.id }) { session ->
                SessionCard(
                    session = session,
                    onDelete = { c.deleteSession(session.id) },
                    onExport = {
                        when {
                            session.healthConnectExported -> onHealthMessage("Esta sessão já foi enviada para o Health Connect.")
                            !healthAvailable -> onHealthMessage("Health Connect não está disponível neste telemóvel.")
                            !healthGranted -> {
                                onHealthMessage("Autoriza primeiro o Health Connect e depois toca novamente em Sincronizar.")
                                requestHealthPermissions()
                            }
                            else -> scope.launch {
                                val result = healthConnect.export(session)
                                if (result.isSuccess) {
                                    c.markHealthExported(session.id)
                                    onHealthMessage("Sessão enviada para o Health Connect.")
                                } else {
                                    onHealthMessage("Falha no Health Connect: ${result.exceptionOrNull()?.message ?: "erro desconhecido"}")
                                }
                            }
                        }
                    },
                )
            }
        }
    }
}

@Composable
private fun SessionCard(session: WorkoutSession, onDelete: () -> Unit, onExport: () -> Unit) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(formatDate(session.startedAtMs), fontWeight = FontWeight.Bold)
            Text("${formatTime(session.durationSec)} · %.2f km".format(Locale.US, session.distanceKm))
            Text(
                "Média %.1f km/h · Máx %.1f km/h".format(
                    Locale.US,
                    session.averageSpeedKmh,
                    session.maxSpeedKmh,
                )
            )
            val extras = buildList {
                session.caloriesKcal?.let { add("$it kcal") }
                session.averageHeartRateBpm?.let { add("FC média $it") }
                session.maxHeartRateBpm?.let { add("FC máx $it") }
            }
            if (extras.isNotEmpty()) Text(extras.joinToString(" · "))
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                if (session.healthConnectExported) {
                    TextButton(onClick = {}) { Text("Health Connect ✓") }
                } else {
                    TextButton(onClick = onExport) { Text("Sincronizar") }
                }
                TextButton(onClick = onDelete) { Text("Apagar") }
            }
        }
    }
}

@Composable
private fun MorePage(
    s: AppState,
    watch: HeartRateState,
    execution: StrengthExecutionState,
    strengthRepository: StrengthExecutionRepository,
    c: TreadmillController,
    heartRateMonitor: HeartRateMonitor,
    healthAvailable: Boolean,
    healthGranted: Boolean,
    requestHealthPermissions: () -> Unit,
    healthMessage: String?,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                    Text("MyStrengthBook sync", fontWeight = FontWeight.Bold)
                    Text(
                        "${execution.analytics.pendingSyncSets} séries locais pendentes",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Text(
                        "O write-back automático ainda não está ativo. Este export serve para validar o payload antes de ligarmos aos endpoints do MSB.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    Button(
                        onClick = {
                            scope.launch {
                                val file = strengthRepository.exportPendingSync()
                                shareJsonFile(context, file)
                            }
                        },
                        enabled = execution.analytics.pendingSyncSets > 0,
                    ) { Text("Exportar payload pendente") }
                }
            }
        }

        item {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                    Text("Relógio / frequência cardíaca", fontWeight = FontWeight.Bold)
                    Text("Estado: ${watch.status}")
                    Text("Dispositivo: ${watch.connectedName ?: watch.candidateName ?: "—"}")
                    Text("FC: ${watch.heartRateBpm?.let { "$it bpm" } ?: "—"}")
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Button(onClick = { heartRateMonitor.startScan() }) {
                            Text(if (watch.scanning) "A procurar…" else "Procurar relógio")
                        }
                        if (watch.savedAddress != null) {
                            OutlinedButton(onClick = { heartRateMonitor.forget() }) {
                                Text("Esquecer")
                            }
                        }
                    }
                    if (watch.savedAddress == null) {
                        Text(
                            "A app procura dispositivos que emitam o serviço Bluetooth padrão de frequência cardíaca (0x180D).",
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
            }
        }

        item {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                    Text("Health Connect", fontWeight = FontWeight.Bold)
                    Text(
                        when {
                            !healthAvailable -> "Indisponível neste dispositivo"
                            healthGranted -> "Ativo — podes sincronizar sessões no Histórico"
                            else -> "Disponível, mas ainda sem permissão"
                        }
                    )
                    if (healthAvailable && !healthGranted) {
                        Button(onClick = requestHealthPermissions) { Text("Ativar Health Connect") }
                    }
                    if (healthMessage != null) Text(healthMessage, style = MaterialTheme.typography.bodySmall)
                }
            }
        }

        item {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                    Text("Passadeira", fontWeight = FontWeight.Bold)
                    Text("Estado: ${if (s.controlReady) "Ligada e pronta" else s.connection}")
                    Text("Dispositivo: ${s.connectedName ?: "—"}")
                    Text("Endereço: ${s.savedAddress ?: "—"}")
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        OutlinedButton(onClick = { c.autoConnect() }) { Text("Reconectar") }
                        OutlinedButton(onClick = { c.disconnect() }) { Text("Desligar") }
                    }
                    TextButton(onClick = { c.forgetSaved() }) { Text("Esquecer passadeira") }
                }
            }
        }

        item {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("Diagnóstico FTMS", fontWeight = FontWeight.Bold)
                    Text("Machine features: 0x${s.machineFeatureBits.toString(16)}")
                    Text("Target features: 0x${s.targetFeatureBits.toString(16)}")
                    Text("Velocidade: ${s.speedRange.min}–${s.speedRange.max} km/h · passo ${s.speedRange.step}")
                    Text("Machine status: ${s.machineStatus}")
                    Text("Training status: ${s.trainingStatus}")
                    Text("Última resposta: ${s.lastControlResponse}")
                }
            }
        }

        item { Text("Log BLE", fontWeight = FontWeight.Bold) }
        items(s.logs) { Text(it, style = MaterialTheme.typography.bodySmall) }
    }
}

private fun formatDate(epochMs: Long): String =
    SimpleDateFormat("dd MMM yyyy · HH:mm", Locale("pt", "PT")).format(Date(epochMs))

private fun formatTime(seconds: Int): String {
    val h = seconds / 3600
    val m = (seconds % 3600) / 60
    val s = seconds % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%02d:%02d".format(m, s)
}

private fun formatLongDuration(seconds: Int): String {
    val h = seconds / 3600
    val m = (seconds % 3600) / 60
    return if (h > 0) "${h}h ${m}m" else "${m} min"
}

private fun deltaCounter(current: Int?, base: Int?): Int? {
    current ?: return null
    base ?: return current
    return if (current >= base) current - base else current
}

private fun deltaCounter(current: Double?, base: Double?): Double? {
    current ?: return null
    base ?: return current
    return if (current >= base) current - base else current
}

private fun parseRange16(value: ByteArray, divisor: Double): Range3? {
    if (value.size < 6) return null
    return Range3(value.u16(0) / divisor, value.u16(2) / divisor, value.u16(4) / divisor)
}

private fun parseSignedRange16(value: ByteArray, divisor: Double): Range3? {
    if (value.size < 6) return null
    return Range3(value.s16(0) / divisor, value.s16(2) / divisor, value.u16(4) / divisor)
}

private fun parseTreadmillData(value: ByteArray): Telemetry {
    if (value.size < 2) return Telemetry()
    val flags = value.u16(0)
    var i = 2
    fun can(n: Int) = i + n <= value.size
    fun u8(): Int? = if (can(1)) (value[i++].toInt() and 0xFF) else null
    fun u16(): Int? = if (can(2)) value.u16(i).also { i += 2 } else null
    fun s16(): Int? = if (can(2)) value.s16(i).also { i += 2 } else null
    fun u24(): Int? = if (can(3)) value.u24(i).also { i += 3 } else null

    var speed: Double? = null
    var avg: Double? = null
    var distance: Double? = null
    var incline: Double? = null
    var posElev: Double? = null
    var negElev: Double? = null
    var calories: Int? = null
    var hr: Int? = null
    var met: Double? = null
    var elapsed: Int? = null
    var remaining: Int? = null
    var force: Int? = null
    var power: Int? = null

    if (flags and (1 shl 0) == 0) speed = u16()?.div(100.0)
    if (flags and (1 shl 1) != 0) avg = u16()?.div(100.0)
    if (flags and (1 shl 2) != 0) distance = u24()?.div(1000.0)
    if (flags and (1 shl 3) != 0) {
        incline = s16()?.div(10.0)
        s16()
    }
    if (flags and (1 shl 4) != 0) {
        posElev = u16()?.div(10.0)
        negElev = u16()?.div(10.0)
    }
    if (flags and (1 shl 5) != 0) u16()
    if (flags and (1 shl 6) != 0) u16()
    if (flags and (1 shl 7) != 0) {
        calories = u16()?.takeUnless { it == 0xFFFF }
        u16()
        u8()
    }
    if (flags and (1 shl 8) != 0) hr = u8()
    if (flags and (1 shl 9) != 0) met = u8()?.div(10.0)
    if (flags and (1 shl 10) != 0) elapsed = u16()
    if (flags and (1 shl 11) != 0) remaining = u16()
    if (flags and (1 shl 12) != 0) {
        force = s16()
        power = s16()
    }

    return Telemetry(
        speedKmh = speed,
        averageSpeedKmh = avg,
        distanceKm = distance,
        inclinationPercent = incline,
        positiveElevationM = posElev,
        negativeElevationM = negElev,
        calories = calories,
        heartRate = hr,
        met = met,
        elapsedSeconds = elapsed,
        remainingSeconds = remaining,
        forceN = force,
        powerW = power,
    )
}

private fun describeMachineStatus(value: ByteArray): String {
    if (value.isEmpty()) return "—"
    val op = value[0].toInt() and 0xFF
    return when (op) {
        0x01 -> "Reset"
        0x02 -> "Parada / pausa"
        0x03 -> "Parada pela chave de segurança"
        0x04 -> "Iniciada / retomada"
        0x05 -> "Velocidade alvo alterada"
        0x06 -> "Inclinação alvo alterada"
        0x07 -> "Resistência alvo alterada"
        0x08 -> "Potência alvo alterada"
        0x09 -> "FC alvo alterada"
        0x0A -> "Energia alvo alterada"
        0x0B -> "Passos alvo alterados"
        0x0C -> "Passadas alvo alteradas"
        0x0D -> "Distância alvo alterada"
        0x0E -> "Tempo alvo alterado"
        0xFF -> "Permissão de controlo perdida"
        else -> "Status 0x${op.toString(16)}"
    }
}

private fun describeTrainingStatus(value: ByteArray): String {
    if (value.size < 2) return "—"
    return when (val s = value[1].toInt() and 0xFF) {
        0x00 -> "Outro"
        0x01 -> "Idle"
        0x02 -> "Aquecimento"
        0x03 -> "Intervalo baixa intensidade"
        0x04 -> "Intervalo alta intensidade"
        0x05 -> "Recuperação"
        0x06 -> "Isométrico"
        0x07 -> "Controlo por FC"
        0x08 -> "Teste fitness"
        0x09 -> "Velocidade abaixo da zona"
        0x0A -> "Velocidade acima da zona"
        0x0B -> "Retorno à calma"
        0x0C -> "Controlo por watts"
        0x0D -> "Modo manual"
        0x0E -> "Pré-treino"
        0x0F -> "Pós-treino"
        else -> "Training status 0x${s.toString(16)}"
    }
}

private fun opcodeName(op: Int): String = when (op) {
    0x00 -> "Request Control"
    0x02 -> "Set Target Speed"
    0x07 -> "Start / Resume"
    0x08 -> "Stop / Pause"
    else -> "Opcode 0x${op.toString(16)}"
}

private fun resultLabel(code: Int): String = when (code) {
    0x01 -> "Success"
    0x02 -> "Opcode not supported"
    0x03 -> "Invalid parameter"
    0x04 -> "Operation failed"
    0x05 -> "Control not permitted"
    else -> "Unknown result 0x${code.toString(16)}"
}

private fun ByteArray.u16(offset: Int): Int =
    (this[offset].toInt() and 0xFF) or ((this[offset + 1].toInt() and 0xFF) shl 8)

private fun ByteArray.s16(offset: Int): Int {
    val v = u16(offset)
    return if (v and 0x8000 != 0) v - 0x10000 else v
}

private fun ByteArray.u24(offset: Int): Int =
    (this[offset].toInt() and 0xFF) or
        ((this[offset + 1].toInt() and 0xFF) shl 8) or
        ((this[offset + 2].toInt() and 0xFF) shl 16)

private fun ByteArray.u32(offset: Int): UInt =
    (this[offset].toUInt() and 0xFFu) or
        ((this[offset + 1].toUInt() and 0xFFu) shl 8) or
        ((this[offset + 2].toUInt() and 0xFFu) shl 16) or
        ((this[offset + 3].toUInt() and 0xFFu) shl 24)

private fun ByteArray.toHex(): String = joinToString(" ") { "%02X".format(it.toInt() and 0xFF) }
private fun UUID.short(): String = toString().substring(4, 8)


private fun createWorkoutVideoUri(context: Context, setKey: String): android.net.Uri {
    val dir = File(context.filesDir, "workout_videos").apply { mkdirs() }
    val safe = setKey.replace(Regex("[^A-Za-z0-9_-]"), "_")
    val file = File(dir, "${System.currentTimeMillis()}_${safe}.mp4")
    return FileProvider.getUriForFile(
        context,
        "${context.packageName}.fileprovider",
        file,
    )
}

private fun shareJsonFile(context: Context, file: File) {
    val uri = FileProvider.getUriForFile(
        context,
        "${context.packageName}.fileprovider",
        file,
    )
    val intent = Intent(Intent.ACTION_SEND).apply {
        type = "application/json"
        putExtra(Intent.EXTRA_STREAM, uri)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    context.startActivity(Intent.createChooser(intent, "Partilhar payload MSB"))
}

private fun queryDisplayName(context: Context, uri: android.net.Uri): String? {
    return runCatching {
        context.contentResolver.query(
            uri,
            arrayOf(OpenableColumns.DISPLAY_NAME),
            null,
            null,
            null,
        )?.use { cursor ->
            if (cursor.moveToFirst()) cursor.getString(0) else null
        }
    }.getOrNull()
}
