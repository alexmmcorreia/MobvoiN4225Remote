package com.local.mobvoin4225remote

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import java.util.UUID

private val HEART_RATE_SERVICE_UUID: UUID =
    UUID.fromString("0000180d-0000-1000-8000-00805f9b34fb")
private val HEART_RATE_MEASUREMENT_UUID: UUID =
    UUID.fromString("00002a37-0000-1000-8000-00805f9b34fb")
private val HEART_RATE_CCCD_UUID: UUID =
    UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

data class HeartRateState(
    val scanning: Boolean = false,
    val candidateName: String? = null,
    val candidateAddress: String? = null,
    val connectedName: String? = null,
    val connectedAddress: String? = null,
    val savedAddress: String? = null,
    val heartRateBpm: Int? = null,
    val status: String = "Relógio não ligado",
)

@SuppressLint("MissingPermission")
class HeartRateMonitor(private val context: Context) {
    private val manager = context.getSystemService(BluetoothManager::class.java)
    private val adapter: BluetoothAdapter? get() = manager?.adapter
    private val prefs = context.getSharedPreferences("n4225", Context.MODE_PRIVATE)

    val state = MutableStateFlow(
        HeartRateState(savedAddress = prefs.getString("watch_saved_address", null))
    )

    private var candidate: BluetoothDevice? = null
    private var gatt: BluetoothGatt? = null

    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            val hasHeartRateService =
                result.scanRecord?.serviceUuids?.any { it.uuid == HEART_RATE_SERVICE_UUID } == true
            if (!hasHeartRateService) return

            val device = result.device
            val address = device.address
            val saved = state.value.savedAddress

            if (saved != null && saved != address) return

            candidate = device
            val name = result.scanRecord?.deviceName
                ?: runCatching { device.name }.getOrNull()
                ?: "Monitor de FC"

            stopScan()

            if (saved == null) {
                prefs.edit().putString("watch_saved_address", address).apply()
            }

            state.update {
                it.copy(
                    scanning = false,
                    candidateName = name,
                    candidateAddress = address,
                    savedAddress = saved ?: address,
                    status = "Monitor de FC encontrado",
                )
            }
            connect(device)
        }

        override fun onScanFailed(errorCode: Int) {
            state.update {
                it.copy(scanning = false, status = "Falha ao procurar relógio ($errorCode)")
            }
        }
    }

    private val callback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
            when (newState) {
                BluetoothProfile.STATE_CONNECTED -> {
                    gatt = g
                    state.update {
                        it.copy(
                            connectedName = runCatching { g.device.name }.getOrNull()
                                ?: it.candidateName
                                ?: "Monitor de FC",
                            connectedAddress = g.device.address,
                            heartRateBpm = null,
                            status = "Relógio ligado; a preparar FC…",
                        )
                    }
                    g.discoverServices()
                }

                BluetoothProfile.STATE_DISCONNECTED -> {
                    if (gatt === g) gatt = null
                    state.update {
                        it.copy(
                            connectedName = null,
                            connectedAddress = null,
                            heartRateBpm = null,
                            status = "Relógio desligado",
                        )
                    }
                    runCatching { g.close() }
                }
            }
        }

        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
            if (status != BluetoothGatt.GATT_SUCCESS) {
                state.update { it.copy(status = "Falha a ler serviços do relógio ($status)") }
                return
            }

            val characteristic = g
                .getService(HEART_RATE_SERVICE_UUID)
                ?.getCharacteristic(HEART_RATE_MEASUREMENT_UUID)

            if (characteristic == null) {
                state.update {
                    it.copy(status = "Ligado, mas sem serviço Bluetooth padrão de FC")
                }
                return
            }

            g.setCharacteristicNotification(characteristic, true)
            val cccd = characteristic.getDescriptor(HEART_RATE_CCCD_UUID)
            if (cccd == null) {
                state.update { it.copy(status = "FC encontrada, mas sem notificações") }
                return
            }

            cccd.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
            if (!g.writeDescriptor(cccd)) {
                state.update { it.copy(status = "Não foi possível ativar a FC em tempo real") }
            }
        }

        override fun onDescriptorWrite(
            g: BluetoothGatt,
            descriptor: BluetoothGattDescriptor,
            status: Int,
        ) {
            if (descriptor.uuid == HEART_RATE_CCCD_UUID) {
                state.update {
                    it.copy(
                        status = if (status == BluetoothGatt.GATT_SUCCESS)
                            "Relógio ligado — à espera de FC"
                        else
                            "Falha ao ativar FC ($status)"
                    )
                }
            }
        }

        @Deprecated("Deprecated in API 33")
        override fun onCharacteristicChanged(
            g: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
        ) {
            if (characteristic.uuid == HEART_RATE_MEASUREMENT_UUID) {
                handleHeartRate(characteristic.value ?: byteArrayOf())
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
        if (!hasPermissions()) {
            state.update { it.copy(status = "É necessária permissão Bluetooth") }
            return
        }
        if (state.value.connectedAddress != null) return
        startScan()
    }

    fun startScan() {
        if (!hasPermissions()) {
            state.update { it.copy(status = "É necessária permissão Bluetooth") }
            return
        }

        val a = adapter ?: run {
            state.update { it.copy(status = "Bluetooth indisponível") }
            return
        }

        if (!a.isEnabled) {
            state.update { it.copy(status = "Liga o Bluetooth") }
            return
        }

        stopScan()
        candidate = null
        state.update {
            it.copy(
                scanning = true,
                candidateName = null,
                candidateAddress = null,
                status = "À procura de FC Bluetooth…",
            )
        }
        a.bluetoothLeScanner.startScan(scanCallback)
    }

    fun connectCandidate() {
        candidate?.let(::connect) ?: startScan()
    }

    fun disconnect() {
        stopScan()
        runCatching { gatt?.disconnect() }
        runCatching { gatt?.close() }
        gatt = null
        state.update {
            it.copy(
                connectedName = null,
                connectedAddress = null,
                heartRateBpm = null,
                status = "Relógio desligado",
            )
        }
    }

    fun forget() {
        disconnect()
        prefs.edit().remove("watch_saved_address").apply()
        state.update {
            it.copy(
                savedAddress = null,
                candidateName = null,
                candidateAddress = null,
                status = "Relógio esquecido",
            )
        }
    }

    fun close() = disconnect()

    private fun stopScan() {
        if (!hasPermissions()) return
        runCatching { adapter?.bluetoothLeScanner?.stopScan(scanCallback) }
        state.update { it.copy(scanning = false) }
    }

    private fun connect(device: BluetoothDevice) {
        stopScan()
        runCatching { gatt?.close() }
        state.update { it.copy(status = "A ligar ao relógio…") }
        gatt = device.connectGatt(context, false, callback, BluetoothDevice.TRANSPORT_LE)
    }

    private fun handleHeartRate(value: ByteArray) {
        if (value.size < 2) return

        val flags = value[0].toInt() and 0xFF
        val is16Bit = flags and 0x01 != 0
        val bpm = if (is16Bit) {
            if (value.size < 3) return
            (value[1].toInt() and 0xFF) or ((value[2].toInt() and 0xFF) shl 8)
        } else {
            value[1].toInt() and 0xFF
        }

        if (bpm !in 20..250) return

        state.update {
            it.copy(
                heartRateBpm = bpm,
                status = "Relógio ligado",
            )
        }
    }
}
