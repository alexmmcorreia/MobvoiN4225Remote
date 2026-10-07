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
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import java.util.Locale
import java.util.UUID
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

data class AppState(
    val scanning: Boolean = false,
    val candidateName: String? = null,
    val candidateAddress: String? = null,
    val connectedName: String? = null,
    val connectedAddress: String? = null,
    val connection: String = "Disconnected",
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
    val logs: List<String> = emptyList(),
)

class MainActivity : ComponentActivity() {
    private lateinit var controller: TreadmillController

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        controller = TreadmillController(this)
        setContent {
            MaterialTheme {
                N4225Screen(controller)
            }
        }
    }

    override fun onDestroy() {
        controller.close()
        super.onDestroy()
    }
}

@SuppressLint("MissingPermission")
class TreadmillController(private val context: Context) {
    private val manager = context.getSystemService(BluetoothManager::class.java)
    private val adapter: BluetoothAdapter? get() = manager?.adapter
    private val prefs = context.getSharedPreferences("n4225", Context.MODE_PRIVATE)

    val state = MutableStateFlow(
        AppState(savedAddress = prefs.getString("saved_address", null))
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
            state.update {
                it.copy(
                    candidateName = advertisedName ?: "Mobvoi WTMP",
                    candidateAddress = address,
                    scanning = false,
                    connection = "Treadmill found",
                )
            }
            stopScan()
            log("Found ${advertisedName ?: "Mobvoi WTMP"} at $address")

            if (state.value.savedAddress == address) {
                connect(device)
            }
        }

        override fun onScanFailed(errorCode: Int) {
            state.update { it.copy(scanning = false, connection = "Scan failed ($errorCode)") }
            log("Scan failed: $errorCode")
        }
    }

    private val callback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                gatt = g
                state.update {
                    it.copy(
                        connection = "Connected; discovering services…",
                        connectedName = runCatching { g.device.name }.getOrNull() ?: "Mobvoi WTMP",
                        connectedAddress = g.device.address,
                        controlReady = false,
                    )
                }
                log("Connected, GATT status $status")
                g.discoverServices()
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                if (gatt === g) gatt = null
                controlPoint = null
                state.update {
                    it.copy(
                        connection = "Disconnected",
                        controlReady = false,
                        connectedName = null,
                        connectedAddress = null,
                    )
                }
                log("Disconnected, GATT status $status")
                runCatching { g.close() }
            }
        }

        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
            if (status != BluetoothGatt.GATT_SUCCESS) {
                state.update { it.copy(connection = "Service discovery failed ($status)") }
                return
            }
            val service = g.getService(FTMS_SERVICE)
            if (service == null) {
                state.update { it.copy(connection = "FTMS service 0x1826 not found") }
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

    fun startScan() {
        if (!hasPermissions()) {
            state.update { it.copy(connection = "Bluetooth permission required") }
            return
        }
        val a = adapter ?: run {
            state.update { it.copy(connection = "Bluetooth unavailable") }
            return
        }
        if (!a.isEnabled) {
            state.update { it.copy(connection = "Turn Bluetooth on") }
            return
        }
        stopScan()
        candidate = null
        state.update {
            it.copy(
                scanning = true,
                candidateName = null,
                candidateAddress = null,
                connection = "Scanning for Mobvoi WTMP…",
            )
        }
        log("Scan started")
        a.bluetoothLeScanner.startScan(scanCallback)
    }

    fun stopScan() {
        if (!hasPermissions()) return
        runCatching { adapter?.bluetoothLeScanner?.stopScan(scanCallback) }
        state.update { it.copy(scanning = false) }
    }

    fun connectCandidate() {
        candidate?.let { connect(it) }
            ?: state.update { it.copy(connection = "Scan for the treadmill first") }
    }

    fun connectSaved() {
        val address = state.value.savedAddress ?: run {
            state.update { it.copy(connection = "No treadmill saved yet") }
            return
        }
        if (!hasPermissions()) return
        val device = runCatching { adapter?.getRemoteDevice(address) }.getOrNull()
        if (device == null) {
            state.update { it.copy(connection = "Could not open saved Bluetooth device") }
            return
        }
        connect(device)
    }

    fun saveCurrent() {
        val address = state.value.connectedAddress ?: state.value.candidateAddress ?: return
        prefs.edit().putString("saved_address", address).apply()
        state.update { it.copy(savedAddress = address) }
        log("Saved treadmill $address")
    }

    fun forgetSaved() {
        prefs.edit().remove("saved_address").apply()
        state.update { it.copy(savedAddress = null) }
        log("Saved treadmill removed")
    }

    private fun connect(device: BluetoothDevice) {
        if (!hasPermissions()) return
        stopScan()
        runCatching { gatt?.close() }
        state.update {
            it.copy(connection = "Connecting…", controlReady = false)
        }
        log("Connecting to ${device.address}")
        gatt = device.connectGatt(context, false, callback, BluetoothDevice.TRANSPORT_LE)
    }

    fun disconnect() {
        stopScan()
        runCatching { gatt?.disconnect() }
        runCatching { gatt?.close() }
        gatt = null
        state.update {
            it.copy(
                connection = "Disconnected",
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
            state.update { it.copy(connection = "FTMS Control Point 0x2AD9 not found") }
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

        state.update { it.copy(connection = "Configuring FTMS…") }
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
            state.update { it.copy(connection = "FTMS ready; requesting control…") }
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
                            value[0].toInt() and 0xFF.toInt(),
                            value[1].toInt() and 0xFF.toInt(),
                            value[2].toInt() and 0xFF.toInt(),
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
                state.update { it.copy(telemetry = telemetry) }
            }
            MACHINE_STATUS_UUID -> {
                val text = describeMachineStatus(value)
                state.update { it.copy(machineStatus = text) }
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
        log("Control response $message")
    }

    fun requestControl() = sendControl(byteArrayOf(0x00))
    fun reset() = sendControl(byteArrayOf(0x01))
    fun start() = sendControl(byteArrayOf(0x07))
    fun stop() = sendControl(byteArrayOf(0x08, 0x01))
    fun pause() = sendControl(byteArrayOf(0x08, 0x02))

    fun setSpeed(kmh: Double) {
        val r = state.value.speedRange
        val rounded = (((kmh - r.min) / r.step).roundToInt() * r.step + r.min).coerceIn(r.min, r.max)
        val raw = (rounded * 100.0).roundToInt()
        state.update { it.copy(targetSpeed = rounded) }
        sendControl(byteArrayOf(0x02, (raw and 0xFF).toByte(), ((raw ushr 8) and 0xFF).toByte()))
    }

    fun changeSpeed(delta: Double) = setSpeed(state.value.targetSpeed + delta)

    fun setInclination(percent: Double) = sendSigned16(0x03, (percent * 10.0).roundToInt())
    fun setResistance(level: Double) = sendSigned16(0x04, (level * 10.0).roundToInt())
    fun setPower(watts: Int) = sendSigned16(0x05, watts)
    fun setHeartRate(bpm: Int) = sendControl(byteArrayOf(0x06, bpm.coerceIn(0, 255).toByte()))
    fun setEnergy(kcal: Int) = sendUnsigned16(0x09, kcal)
    fun setSteps(steps: Int) = sendUnsigned16(0x0A, steps)
    fun setStrides(strides: Int) = sendUnsigned16(0x0B, strides)
    fun setDistance(metres: Int) {
        val v = metres.coerceIn(0, 0xFFFFFF)
        sendControl(
            byteArrayOf(
                0x0C,
                (v and 0xFF).toByte(),
                ((v ushr 8) and 0xFF).toByte(),
                ((v ushr 16) and 0xFF).toByte(),
            )
        )
    }
    fun setTrainingTime(seconds: Int) = sendUnsigned16(0x0D, seconds)

    private fun sendUnsigned16(opcode: Int, value: Int) {
        val v = value.coerceIn(0, 0xFFFF)
        sendControl(byteArrayOf(opcode.toByte(), (v and 0xFF).toByte(), ((v ushr 8) and 0xFF).toByte()))
    }

    private fun sendSigned16(opcode: Int, value: Int) {
        val v = value.coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()) and 0xFFFF
        sendControl(byteArrayOf(opcode.toByte(), (v and 0xFF).toByte(), ((v ushr 8) and 0xFF).toByte()))
    }

    private fun sendControl(bytes: ByteArray) {
        val g = gatt ?: run {
            state.update { it.copy(connection = "Not connected") }
            return
        }
        val c = controlPoint ?: return
        c.writeType = BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
        c.value = bytes
        val started = g.writeCharacteristic(c)
        log("TX ${bytes.toHex()} ${if (started) "" else "(not started)"}")
        if (!started) state.update { it.copy(lastControlResponse = "Android did not start write") }
    }

    private fun log(message: String) {
        val line = "${System.currentTimeMillis() % 100000}: $message"
        state.update { s -> s.copy(logs = (listOf(line) + s.logs).take(80)) }
    }
}

@Composable
private fun N4225Screen(controller: TreadmillController) {
    val state by controller.state.collectAsState()
    val context = LocalContext.current
    var page by remember { mutableIntStateOf(0) }
    var showStartConfirm by remember { mutableStateOf(false) }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { }

    LaunchedEffect(Unit) {
        if (!controller.hasPermissions()) {
            permissionLauncher.launch(controller.requiredPermissions())
        }
    }

    DisposableEffect(Unit) {
        onDispose { }
    }

    Column(
        modifier = Modifier.fillMaxSize().padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text("Mobvoi N4225 Remote", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
        Text(state.connection)

        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            TextButton(onClick = { page = 0 }) { Text("Control") }
            TextButton(onClick = { page = 1 }) { Text("Dados") }
            TextButton(onClick = { page = 2 }) { Text("Diagnóstico") }
        }

        when (page) {
            0 -> ControlPage(state, controller, {
                permissionLauncher.launch(controller.requiredPermissions())
            }, { showStartConfirm = true })
            1 -> DataPage(state)
            else -> DiagnosticsPage(state)
        }
    }

    if (showStartConfirm) {
        AlertDialog(
            onDismissRequest = { showStartConfirm = false },
            title = { Text("Iniciar passadeira?") },
            text = {
                Text("Confirma que a zona do tapete está livre e que consegues alcançar o interruptor físico.")
            },
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
private fun ControlPage(
    s: AppState,
    c: TreadmillController,
    requestPermissions: () -> Unit,
    startWithConfirm: () -> Unit,
) {
    LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Ligação", fontWeight = FontWeight.Bold)
                    Text("Encontrada: ${s.candidateName ?: "—"}  ${s.candidateAddress ?: ""}")
                    Text("Ligada: ${s.connectedName ?: "—"}  ${s.connectedAddress ?: ""}")
                    Text("Guardada: ${s.savedAddress ?: "—"}")
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        if (!c.hasPermissions()) {
                            Button(onClick = requestPermissions) { Text("Permissões") }
                        }
                        Button(onClick = { c.startScan() }) { Text(if (s.scanning) "A procurar…" else "Procurar") }
                        OutlinedButton(onClick = { c.connectCandidate() }) { Text("Ligar") }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        OutlinedButton(onClick = { c.connectSaved() }, enabled = s.savedAddress != null) { Text("Ligar guardada") }
                        OutlinedButton(onClick = { c.saveCurrent() }, enabled = s.connectedAddress != null || s.candidateAddress != null) { Text("Guardar") }
                        OutlinedButton(onClick = { c.disconnect() }) { Text("Desligar") }
                    }
                    if (s.savedAddress != null) {
                        TextButton(onClick = { c.forgetSaved() }) { Text("Esquecer passadeira guardada") }
                    }
                }
            }
        }

        item {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("Velocidade", fontWeight = FontWeight.Bold)
                    Text(
                        String.format(Locale.US, "%.1f km/h", s.targetSpeed),
                        style = MaterialTheme.typography.displaySmall,
                    )
                    val r = s.speedRange
                    Slider(
                        value = s.targetSpeed.toFloat(),
                        onValueChange = { c.setSpeed(it.toDouble()) },
                        valueRange = r.min.toFloat()..r.max.toFloat(),
                        steps = (((r.max - r.min) / r.step).roundToInt() - 1).coerceAtLeast(0),
                        enabled = s.controlReady,
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = { c.changeSpeed(-s.speedRange.step) }, enabled = s.controlReady) { Text("−0,5") }
                        Button(onClick = { c.changeSpeed(s.speedRange.step) }, enabled = s.controlReady) { Text("+0,5") }
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        var v = r.min
                        while (v <= r.max + 0.001) {
                            val speed = v
                            OutlinedButton(onClick = { c.setSpeed(speed) }, enabled = s.controlReady) {
                                Text(String.format(Locale.US, "%.1f", speed))
                            }
                            v += r.step
                        }
                    }
                }
            }
        }

        item {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Motor", fontWeight = FontWeight.Bold)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = startWithConfirm, enabled = s.controlReady) { Text("START / RESUME") }
                        Button(onClick = { c.stop() }, enabled = s.controlReady) { Text("STOP") }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = { c.pause() }, enabled = s.controlReady) { Text("PAUSE") }
                        OutlinedButton(onClick = { c.requestControl() }, enabled = s.connectedAddress != null) { Text("Request Control") }
                        OutlinedButton(onClick = { c.reset() }, enabled = s.controlReady) { Text("Reset") }
                    }
                    Text("Última resposta: ${s.lastControlResponse}")
                }
            }
        }

        item {
            AdvancedControls(s, c)
        }
    }
}

@Composable
private fun AdvancedControls(s: AppState, c: TreadmillController) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Opções FTMS anunciadas pela passadeira", fontWeight = FontWeight.Bold)
            if (s.targetFeatureBits == 0u) {
                Text("A N4225 não anunciou targets avançados, ou a leitura de 0x2ACC ainda não terminou.")
            }

            if (s.targetFeatureBits.hasBit(1)) NumericControl("Inclinação (%)", s.inclineRange) {
                it.toDoubleOrNull()?.let(c::setInclination)
            }
            if (s.targetFeatureBits.hasBit(2)) NumericControl("Resistência", s.resistanceRange) {
                it.toDoubleOrNull()?.let(c::setResistance)
            }
            if (s.targetFeatureBits.hasBit(3)) NumericControl("Potência alvo (W)", s.powerRange) {
                it.toIntOrNull()?.let(c::setPower)
            }
            if (s.targetFeatureBits.hasBit(4)) NumericControl("Frequência cardíaca alvo (bpm)", s.heartRateRange) {
                it.toIntOrNull()?.let(c::setHeartRate)
            }
            if (s.targetFeatureBits.hasBit(5)) NumericControl("Energia alvo (kcal)") {
                it.toIntOrNull()?.let(c::setEnergy)
            }
            if (s.targetFeatureBits.hasBit(6)) NumericControl("Passos alvo") {
                it.toIntOrNull()?.let(c::setSteps)
            }
            if (s.targetFeatureBits.hasBit(7)) NumericControl("Passadas alvo") {
                it.toIntOrNull()?.let(c::setStrides)
            }
            if (s.targetFeatureBits.hasBit(8)) NumericControl("Distância alvo (m)") {
                it.toIntOrNull()?.let(c::setDistance)
            }
            if (s.targetFeatureBits.hasBit(9)) NumericControl("Tempo alvo (s)") {
                it.toIntOrNull()?.let(c::setTrainingTime)
            }
        }
    }
}

@Composable
private fun NumericControl(
    label: String,
    range: Range3? = null,
    onSend: (String) -> Unit,
) {
    var value by remember(label) { mutableStateOf("") }
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(if (range == null) label else "$label  [${range.min}…${range.max}; passo ${range.step}]")
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(
                value = value,
                onValueChange = { value = it },
                modifier = Modifier.weight(1f),
                singleLine = true,
                label = { Text("Valor") },
            )
            Button(onClick = { onSend(value) }, enabled = value.isNotBlank()) { Text("Enviar") }
        }
    }
}

@Composable
private fun DataPage(s: AppState) {
    val t = s.telemetry
    LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        item { MetricCard("Velocidade", t.speedKmh?.let { "%.2f km/h".format(Locale.US, it) } ?: "—") }
        item { MetricCard("Velocidade média", t.averageSpeedKmh?.let { "%.2f km/h".format(Locale.US, it) } ?: "—") }
        item { MetricCard("Distância", t.distanceKm?.let { "%.3f km".format(Locale.US, it) } ?: "—") }
        item { MetricCard("Tempo", t.elapsedSeconds?.let(::formatTime) ?: "—") }
        item { MetricCard("Calorias", t.calories?.let { "$it kcal" } ?: "—") }
        item { MetricCard("FC", t.heartRate?.let { "$it bpm" } ?: "—") }
        item { MetricCard("Inclinação", t.inclinationPercent?.let { "%.1f %%".format(Locale.US, it) } ?: "—") }
        item { MetricCard("Elevação + / −", "${t.positiveElevationM ?: "—"} / ${t.negativeElevationM ?: "—"} m") }
        item { MetricCard("MET", t.met?.let { "%.1f".format(Locale.US, it) } ?: "—") }
        item { MetricCard("Potência / força", "${t.powerW ?: "—"} W / ${t.forceN ?: "—"} N") }
        item { MetricCard("Estado", s.machineStatus) }
        item { MetricCard("Training status", s.trainingStatus) }
    }
}

@Composable
private fun MetricCard(label: String, value: String) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp)) {
            Text(label, style = MaterialTheme.typography.labelLarge)
            Text(value, style = MaterialTheme.typography.headlineSmall)
        }
    }
}

@Composable
private fun DiagnosticsPage(s: AppState) {
    LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        item {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("FTMS", fontWeight = FontWeight.Bold)
                    Text("Machine features: 0x${s.machineFeatureBits.toString(16)}")
                    Text("Target features: 0x${s.targetFeatureBits.toString(16)}")
                    Text("Speed: ${s.speedRange}")
                    Text("Inclination: ${s.inclineRange ?: "not advertised/read"}")
                    Text("Resistance: ${s.resistanceRange ?: "not advertised/read"}")
                    Text("Heart rate: ${s.heartRateRange ?: "not advertised/read"}")
                    Text("Power: ${s.powerRange ?: "not advertised/read"}")
                    Text("Machine status: ${s.machineStatus}")
                    Text("Training status: ${s.trainingStatus}")
                    Text("Last control response: ${s.lastControlResponse}")
                }
            }
        }
        item { Text("Log BLE", fontWeight = FontWeight.Bold) }
        items(s.logs) { Text(it, style = MaterialTheme.typography.bodySmall) }
    }
}

private fun UInt.hasBit(bit: Int): Boolean = (this and (1u shl bit)) != 0u

private fun formatTime(seconds: Int): String {
    val h = seconds / 3600
    val m = (seconds % 3600) / 60
    val s = seconds % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%02d:%02d".format(m, s)
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
        0x02 -> "Stopped / paused by user"
        0x03 -> "Stopped by safety key"
        0x04 -> "Started / resumed"
        0x05 -> "Target speed changed"
        0x06 -> "Target inclination changed"
        0x07 -> "Target resistance changed"
        0x08 -> "Target power changed"
        0x09 -> "Target heart rate changed"
        0x0A -> "Target energy changed"
        0x0B -> "Target steps changed"
        0x0C -> "Target strides changed"
        0x0D -> "Target distance changed"
        0x0E -> "Target time changed"
        0xFF -> "Control permission lost"
        else -> "Status 0x${op.toString(16)}"
    }
}

private fun describeTrainingStatus(value: ByteArray): String {
    if (value.size < 2) return "—"
    return when (val s = value[1].toInt() and 0xFF) {
        0x00 -> "Other"
        0x01 -> "Idle"
        0x02 -> "Warming up"
        0x03 -> "Low intensity interval"
        0x04 -> "High intensity interval"
        0x05 -> "Recovery interval"
        0x06 -> "Isometric"
        0x07 -> "Heart-rate control"
        0x08 -> "Fitness test"
        0x09 -> "Speed below control region"
        0x0A -> "Speed above control region"
        0x0B -> "Cool down"
        0x0C -> "Watt control"
        0x0D -> "Manual mode"
        0x0E -> "Pre-workout"
        0x0F -> "Post-workout"
        else -> "Training status 0x${s.toString(16)}"
    }
}

private fun opcodeName(op: Int): String = when (op) {
    0x00 -> "Request Control"
    0x01 -> "Reset"
    0x02 -> "Set Target Speed"
    0x03 -> "Set Target Inclination"
    0x04 -> "Set Target Resistance"
    0x05 -> "Set Target Power"
    0x06 -> "Set Target Heart Rate"
    0x07 -> "Start / Resume"
    0x08 -> "Stop / Pause"
    0x09 -> "Set Target Energy"
    0x0A -> "Set Target Steps"
    0x0B -> "Set Target Strides"
    0x0C -> "Set Target Distance"
    0x0D -> "Set Target Time"
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
