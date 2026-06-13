package com.example.smarttag.ble

import android.annotation.SuppressLint
import android.bluetooth.*
import android.bluetooth.le.*
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.ParcelUuid
import android.util.Log
import com.example.smarttag.model.EventType
import com.example.smarttag.model.SmartTag
import com.example.smarttag.model.TagStatus
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.UUID

private const val TAG = "BleManager"
private const val COMPANY_ID = 0xFFFF

object BleConstants {
    val SERVICE_UUID: UUID = UUID.fromString("4fafc201-1fb5-459e-8fcc-c5c9c331914b")
    val PRICE_CHAR_UUID: UUID = UUID.fromString("beb5483e-36e1-4688-b7f5-ea07361b26a8")
    val ACK_CHAR_UUID: UUID = UUID.fromString("12345678-1234-1234-1234-123456789abc")
    val CCCD_UUID: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
}

sealed class BleEvent {
    data class TagInfoReceived(
        val address: String,
        val data: Type01Data
    ) : BleEvent()
    data class AnchorDetected(
        val address: String,
        val groupId: Int,
        val rssi: Int
    ) : BleEvent()
    data class AckReceived(val address: String, val tagId: Int, val price: Int) : BleEvent()
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
            val record = result.scanRecord

            // ── 제조사 데이터 파싱 (0x01 Tag Info, 0x03 Anchor Beacon) ──
            val mfgData = record?.getManufacturerSpecificData(COMPANY_ID)
            if (mfgData != null) {
                when (mfgData[0].toInt() and 0xFF) {
                    0x01 -> {
                        val parsed = BlePackets.parseType01(mfgData)
                        if (parsed != null) {
                            _bleEvents.value = BleEvent.TagInfoReceived(device.address, parsed)
                            updateDiscoveredTag(device, result.rssi, parsed.tagId)
                        }
                        return
                    }
                    0x03 -> {
                        val groupId = BlePackets.parseType03(mfgData)
                        if (groupId != null) {
                            _bleEvents.value = BleEvent.AnchorDetected(device.address, groupId, result.rssi)
                        }
                        return
                    }
                }
            }

            // ── Service UUID 기반 GATT 서버 태그 ──
            val name = device.name ?: "SmartTag-${device.address.takeLast(5)}"
            val tagId = parseTagId(device)
            updateDiscoveredTag(device, result.rssi, tagId, name)
        }

        override fun onScanFailed(errorCode: Int) {
            Log.e(TAG, "Scan failed: $errorCode")
            _isScanning.value = false
        }
    }

    private fun updateDiscoveredTag(
        device: BluetoothDevice,
        rssi: Int,
        tagId: Int,
        name: String? = null
    ) {
        val current = _discoveredTags.value.toMutableMap()
        val existing = current[device.address]
        val resolvedName = name ?: existing?.deviceName ?: "SmartTag-${device.address.takeLast(5)}"
        current[device.address] = SmartTag(
            tagId = tagId,
            deviceAddress = device.address,
            deviceName = resolvedName,
            rssi = rssi,
            groupId = existing?.groupId ?: 0,
            productName = existing?.productName ?: "",
            targetPrice = existing?.targetPrice ?: 0,
            targetEvent = existing?.targetEvent ?: EventType.NONE,
            targetStartDate = existing?.targetStartDate,
            targetEndDate = existing?.targetEndDate,
            currentPrice = existing?.currentPrice ?: 0,
            stateCrc = existing?.stateCrc ?: 0,
            status = existing?.status ?: TagStatus.PENDING
        )
        _discoveredTags.value = current
    }

    fun startScan() {
        if (_isScanning.value) return

        // ── 필터 1: GATT Service UUID (연결 가능한 ESL 태그) ──
        val serviceFilter = ScanFilter.Builder()
            .setServiceUuid(ParcelUuid(BleConstants.SERVICE_UUID))
            .build()

        // ── 필터 2: Type 0x01 제조사 데이터 (태그 상태 방송) ──
        val type01Filter = ScanFilter.Builder()
            .setManufacturerData(
                COMPANY_ID,
                byteArrayOf(0x01.toByte()),
                byteArrayOf(0xFF.toByte())
            )
            .build()

        // ── 필터 3: Type 0x03 제조사 데이터 (앵커 비콘) ──
        val type03Filter = ScanFilter.Builder()
            .setManufacturerData(
                COMPANY_ID,
                byteArrayOf(0x03.toByte()),
                byteArrayOf(0xFF.toByte())
            )
            .build()

        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .build()

        scanner?.startScan(listOf(serviceFilter, type01Filter, type03Filter), settings, scanCallback)
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
        _bleEvents.value = BleEvent.AckReceived(address, tagId, price)

        Handler(Looper.getMainLooper()).postDelayed({ gatt.disconnect() }, 200)
    }

    fun disconnect() {
        activeGatt?.disconnect()
        activeGatt = null
    }

    // ──────────────────────────────────────────────
    // Broadcast 그룹 일괄 수정
    // ──────────────────────────────────────────────

    private var advertiser: BluetoothLeAdvertiser? = null
    private var advertiseCallback: AdvertiseCallback? = null

    /**
     * Type 0x02 가격 업데이트 브로드캐스트
     * entries는 최대 3개 (초과분은 자동으로 잘림)
     */
    fun broadcastPriceUpdate(
        groupId: Int,
        entries: List<PriceEntry>,
        durationMs: Long = 5000L
    ) {
        val payload = BlePackets.buildType02(groupId, entries)
        startAdvertising(payload, durationMs)
        Log.d(TAG, "broadcastPriceUpdate: group=$groupId entries=${entries.size}")
    }

    /**
     * Type 0x04 상품명 업데이트 브로드캐스트 (다단편 순차 전송)
     * 각 단편을 fragDurationMs 동안 방송 후 다음 단편으로 진행
     */
    fun broadcastNameUpdate(
        groupId: Int,
        tagId: Int,
        name: String,
        fragDurationMs: Long = 1000L,
        onComplete: () -> Unit = {}
    ) {
        val nameBytes = name.toByteArray(Charsets.UTF_8)
        val chunkSize = 18
        val totalFrags = if (nameBytes.isEmpty()) 1 else (nameBytes.size + chunkSize - 1) / chunkSize

        fun sendFragment(index: Int) {
            if (index >= totalFrags) {
                onComplete()
                return
            }
            val start = index * chunkSize
            val chunk = nameBytes.copyOfRange(start, minOf(start + chunkSize, nameBytes.size))
            val hasMore = index < totalFrags - 1
            val payload = BlePackets.buildType04Fragment(groupId, tagId, index, hasMore, chunk)
            startAdvertising(payload, fragDurationMs) {
                sendFragment(index + 1)
            }
            Log.d(TAG, "broadcastNameUpdate: frag=$index/$totalFrags hasMore=$hasMore")
        }

        sendFragment(0)
    }

    private fun startAdvertising(
        payload: ByteArray,
        durationMs: Long,
        onStopped: () -> Unit = {}
    ) {
        stopBroadcast()
        advertiser = bluetoothAdapter?.bluetoothLeAdvertiser ?: run {
            Log.e(TAG, "BluetoothLeAdvertiser not available")
            return
        }

        val settings = AdvertiseSettings.Builder()
            .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_LOW_LATENCY)
            .setConnectable(false)
            .setTimeout(0)
            .build()

        val data = AdvertiseData.Builder()
            .addManufacturerData(COMPANY_ID, payload)
            .setIncludeDeviceName(false)
            .build()

        val cb = object : AdvertiseCallback() {
            override fun onStartSuccess(settingsInEffect: AdvertiseSettings) {
                Log.d(TAG, "Advertising started (${payload[0].toInt() and 0xFF} type)")
            }
            override fun onStartFailure(errorCode: Int) {
                Log.e(TAG, "Advertising failed: $errorCode")
            }
        }
        advertiseCallback = cb
        advertiser?.startAdvertising(settings, data, cb)

        Handler(Looper.getMainLooper()).postDelayed({
            stopBroadcast()
            onStopped()
        }, durationMs)
    }

    fun stopBroadcast() {
        advertiseCallback?.let { advertiser?.stopAdvertising(it) }
        advertiseCallback = null
    }

    // ──────────────────────────────────────────────

    private fun parseTagId(device: BluetoothDevice): Int {
        val name = device.name ?: ""
        val match = Regex("(\\d{1,3})$").find(name)
        return match?.value?.toIntOrNull()?.coerceIn(1, 255)
            ?: (device.address.filter { it.isDigit() }.takeLast(3).toIntOrNull()?.and(0xFF) ?: 0)
    }

    fun isBluetoothEnabled(): Boolean = bluetoothAdapter?.isEnabled == true
}
