package com.example.smarttag.ble

import android.annotation.SuppressLint
import android.bluetooth.*
import android.bluetooth.le.*
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.ParcelUuid
import android.util.Log
import com.example.smarttag.model.SmartTag
import com.example.smarttag.model.TagStatus
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.UUID

private const val TAG = "BleManager"

object BleConstants {
    val SERVICE_UUID: UUID = UUID.fromString("4fafc201-1fb5-459e-8fcc-c5c9c331914b")
    val PRICE_CHAR_UUID: UUID = UUID.fromString("beb5483e-36e1-4688-b7f5-ea07361b26a8")
    val ACK_CHAR_UUID: UUID = UUID.fromString("12345678-1234-1234-1234-123456789abc")
    val CCCD_UUID: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
}

sealed class BleEvent {
    data class AckReceived(val address: String, val tagId: String, val price: Int) : BleEvent()
    data class WriteSuccess(val address: String) : BleEvent()
    data class Error(val address: String, val message: String) : BleEvent()
    object Disconnected : BleEvent()
}

@SuppressLint("MissingPermission")
class BleManager(private val context: Context) {

    private val bluetoothManager = context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
    private val bluetoothAdapter: BluetoothAdapter? get() = bluetoothManager.adapter
    private val scanner: BluetoothLeScanner? get() = bluetoothAdapter?.bluetoothLeScanner

    private val _isScanning = MutableStateFlow(false)
    val isScanning: StateFlow<Boolean> = _isScanning.asStateFlow()

    private val _discoveredTags = MutableStateFlow<Map<String, SmartTag>>(emptyMap())
    val discoveredTags: StateFlow<Map<String, SmartTag>> = _discoveredTags.asStateFlow()

    private val _bleEvents = MutableStateFlow<BleEvent?>(null)
    val bleEvents: StateFlow<BleEvent?> = _bleEvents.asStateFlow()

    private var activeGatt: BluetoothGatt? = null

    // ──────────────────────────────────────────────
    // 스캔
    // ──────────────────────────────────────────────

    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            val device = result.device
            val name = device.name ?: "SmartTag-${device.address.takeLast(5)}"
            val tagId = parseTagId(device)

            val tag = SmartTag(
                tagId = tagId,
                deviceAddress = device.address,
                deviceName = name,
                rssi = result.rssi
            )

            val current = _discoveredTags.value.toMutableMap()
            val existing = current[device.address]
            current[device.address] = tag.copy(
                targetPrice = existing?.targetPrice ?: 0,
                currentPrice = existing?.currentPrice ?: 0,
                status = existing?.status ?: TagStatus.PENDING
            )
            _discoveredTags.value = current
        }

        override fun onScanFailed(errorCode: Int) {
            Log.e(TAG, "Scan failed: $errorCode")
            _isScanning.value = false
        }
    }

    fun startScan() {
        if (_isScanning.value) return
        val filters = listOf(
            ScanFilter.Builder()
                .setServiceUuid(ParcelUuid(BleConstants.SERVICE_UUID))
                .build()
        )
        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .build()
        scanner?.startScan(filters, settings, scanCallback)
        _isScanning.value = true
        Log.d(TAG, "Scan started")
    }

    fun stopScan() {
        if (!_isScanning.value) return
        scanner?.stopScan(scanCallback)
        _isScanning.value = false
        Log.d(TAG, "Scan stopped")
    }

    // ──────────────────────────────────────────────
    // GATT 개별 수정
    // ──────────────────────────────────────────────

    fun connectAndWrite(address: String, price: Int) {
        val device = bluetoothAdapter?.getRemoteDevice(address) ?: run {
            _bleEvents.value = BleEvent.Error(address, "디바이스를 찾을 수 없음")
            return
        }
        activeGatt?.close()
        activeGatt = device.connectGatt(context, false, gattCallback(address, price), BluetoothDevice.TRANSPORT_LE)
    }

    private fun gattCallback(address: String, targetPrice: Int) = object : BluetoothGattCallback() {

        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            when (newState) {
                BluetoothProfile.STATE_CONNECTED -> {
                    Log.d(TAG, "Connected to $address")
                    Handler(Looper.getMainLooper()).postDelayed({ gatt.discoverServices() }, 600)
                }
                BluetoothProfile.STATE_DISCONNECTED -> {
                    Log.d(TAG, "Disconnected from $address")
                    gatt.close()
                    _bleEvents.value = BleEvent.Disconnected
                }
            }
        }

        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            if (status != BluetoothGatt.GATT_SUCCESS) {
                _bleEvents.value = BleEvent.Error(address, "서비스 검색 실패")
                gatt.disconnect()
                return
            }
            val ackChar = gatt.getService(BleConstants.SERVICE_UUID)
                ?.getCharacteristic(BleConstants.ACK_CHAR_UUID)
            if (ackChar != null) {
                gatt.setCharacteristicNotification(ackChar, true)
                val cccd = ackChar.getDescriptor(BleConstants.CCCD_UUID)
                cccd?.let {
                    it.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                    gatt.writeDescriptor(it)
                } ?: writePriceChar(gatt, targetPrice)
            } else {
                writePriceChar(gatt, targetPrice)
            }
        }

        override fun onDescriptorWrite(gatt: BluetoothGatt, descriptor: BluetoothGattDescriptor, status: Int) {
            writePriceChar(gatt, targetPrice)
        }

        override fun onCharacteristicWrite(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic, status: Int) {
            if (status == BluetoothGatt.GATT_SUCCESS) {
                _bleEvents.value = BleEvent.WriteSuccess(address)
                Log.d(TAG, "Price written: $targetPrice")
            } else {
                _bleEvents.value = BleEvent.Error(address, "쓰기 실패 (status=$status)")
                gatt.disconnect()
            }
        }

        @Deprecated("Used for API < 33")
        override fun onCharacteristicChanged(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic) {
            handleAck(address, characteristic.value, gatt)
        }

        override fun onCharacteristicChanged(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic, value: ByteArray) {
            handleAck(address, value, gatt)
        }
    }

    private fun writePriceChar(gatt: BluetoothGatt, price: Int) {
        val char = gatt.getService(BleConstants.SERVICE_UUID)
            ?.getCharacteristic(BleConstants.PRICE_CHAR_UUID) ?: run {
            _bleEvents.value = BleEvent.Error(gatt.device.address, "Price Characteristic 없음")
            gatt.disconnect()
            return
        }
        val bytes = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(price).array()
        char.value = bytes
        char.writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
        gatt.writeCharacteristic(char)
    }

    private fun handleAck(address: String, value: ByteArray, gatt: BluetoothGatt) {
        // [AA][TagID Low][TagID High][Price 4B LE]  → 7바이트
        if (value.size < 7 || value[0] != 0xAA.toByte()) return
        val tagId = ((value[2].toInt() and 0xFF) shl 8) or (value[1].toInt() and 0xFF)
        val price = ByteBuffer.wrap(value, 3, 4).order(ByteOrder.LITTLE_ENDIAN).int

        Log.d(TAG, "ACK: Tag $tagId, Price $price")
        _bleEvents.value = BleEvent.AckReceived(address, "%03d".format(tagId), price)

        Handler(Looper.getMainLooper()).postDelayed({ gatt.disconnect() }, 200)
    }

    fun disconnect() {
        activeGatt?.disconnect()
        activeGatt = null
    }

    // ──────────────────────────────────────────────
    // Broadcast 그룹 수정
    // ──────────────────────────────────────────────

    private var advertiser: BluetoothLeAdvertiser? = null
    private var advertiseCallback: AdvertiseCallback? = null

    fun startBroadcast(groupId: Int, price: Int, durationMs: Long = 5000L) {
        advertiser = bluetoothAdapter?.bluetoothLeAdvertiser ?: return
        val settings = AdvertiseSettings.Builder()
            .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_LOW_LATENCY)
            .setConnectable(false)
            .setTimeout(0)
            .build()

        // [02][GroupID 2B LE][Price 4B LE]  → 7바이트 payload
        val payload = ByteBuffer.allocate(7).order(ByteOrder.LITTLE_ENDIAN)
            .put(0x02)
            .putShort(groupId.toShort())
            .putInt(price)
            .array()

        val data = AdvertiseData.Builder()
            .addManufacturerData(0xFFFF, payload)
            .setIncludeDeviceName(false)
            .build()

        val cb = object : AdvertiseCallback() {
            override fun onStartSuccess(settingsInEffect: AdvertiseSettings) {
                Log.d(TAG, "Broadcast started: group=$groupId price=$price")
            }
            override fun onStartFailure(errorCode: Int) {
                Log.e(TAG, "Broadcast failed: $errorCode")
            }
        }
        advertiseCallback = cb
        advertiser?.startAdvertising(settings, data, cb)

        Handler(Looper.getMainLooper()).postDelayed({ stopBroadcast() }, durationMs)
    }

    fun stopBroadcast() {
        advertiseCallback?.let { advertiser?.stopAdvertising(it) }
        advertiseCallback = null
        Log.d(TAG, "Broadcast stopped")
    }

    // ──────────────────────────────────────────────

    private fun parseTagId(device: BluetoothDevice): String {
        val name = device.name ?: ""
        val match = Regex("(\\d{3})$").find(name)
        return match?.value ?: device.address.takeLast(3).filter { it.isLetterOrDigit() }
    }

    fun isBluetoothEnabled(): Boolean = bluetoothAdapter?.isEnabled == true
}
