package com.example.smarttag.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.smarttag.ble.BleEvent
import com.example.smarttag.ble.BleManager
import com.example.smarttag.ble.BlePackets
import com.example.smarttag.ble.PriceEntry
import com.example.smarttag.db.AppDatabase
import com.example.smarttag.db.toEntity
import com.example.smarttag.db.toModel
import com.example.smarttag.model.EventType
import com.example.smarttag.model.SmartTag
import com.example.smarttag.model.TagStatus
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.time.LocalDate

// ── 브로드캐스트 큐 상태 ──────────────────────────────────────────
sealed class BroadcastQueueState {
    object Idle : BroadcastQueueState()
    data class Running(val groupId: Int, val pendingCount: Int) : BroadcastQueueState()
    data class Done(val groupId: Int) : BroadcastQueueState()
}

class ScanViewModel(application: Application) : AndroidViewModel(application) {

    val bleManager = BleManager(application)
    private val dao = AppDatabase.getInstance(application).smartTagDao()

    val isScanning: StateFlow<Boolean> = bleManager.isScanning
    val discoveredTags: StateFlow<Map<String, SmartTag>> = bleManager.discoveredTags

    private val _savedTags = MutableStateFlow<List<SmartTag>>(emptyList())
    val savedTags: StateFlow<List<SmartTag>> = _savedTags.asStateFlow()

    private val _mergedTags = MutableStateFlow<List<SmartTag>>(emptyList())
    val mergedTags: StateFlow<List<SmartTag>> = _mergedTags.asStateFlow()

    private val _snackbarMessage = MutableStateFlow<String?>(null)
    val snackbarMessage: StateFlow<String?> = _snackbarMessage.asStateFlow()

    // ── 앵커 감지로 확정된 현재 그룹 ─────────────────────────────
    private val _currentGroupId = MutableStateFlow<Int?>(null)
    val currentGroupId: StateFlow<Int?> = _currentGroupId.asStateFlow()

    // ── 브로드캐스트 큐 상태 ─────────────────────────────────────
    private val _broadcastQueueState = MutableStateFlow<BroadcastQueueState>(BroadcastQueueState.Idle)
    val broadcastQueueState: StateFlow<BroadcastQueueState> = _broadcastQueueState.asStateFlow()

    // ── 앵커 디바운스 내부 상태 ───────────────────────────────────
    private val anchorRssiMap = mutableMapOf<Int, Int>()   // groupId → rssi
    private val anchorLastSeen = mutableMapOf<Int, Long>() // groupId → epochMs
    private var anchorDebounceJob: Job? = null

    // ── 브로드캐스트 루프 Job ─────────────────────────────────────
    private var broadcastLoopJob: Job? = null

    init {
        observeDb()
        observeBleEvents()
        observeDiscoveredTags()
    }

    // ── DB / 스캔 태그 병합 ────────────────────────────────────────

    private fun observeDb() {
        viewModelScope.launch {
            dao.getAllTags().collect { entities ->
                _savedTags.value = entities.map { it.toModel() }
                mergeTags()
            }
        }
    }

    private fun observeDiscoveredTags() {
        viewModelScope.launch {
            bleManager.discoveredTags.collect { mergeTags() }
        }
    }

    private fun mergeTags() {
        val scanned = bleManager.discoveredTags.value
        val saved = _savedTags.value.associateBy { it.deviceAddress }

        val merged = scanned.values.map { scannedTag ->
            val savedTag = saved[scannedTag.deviceAddress]
            scannedTag.copy(
                groupId = savedTag?.groupId ?: 0,
                productName = savedTag?.productName ?: "",
                targetPrice = savedTag?.targetPrice ?: 0,
                targetEvent = savedTag?.targetEvent ?: EventType.NONE,
                targetStartDate = savedTag?.targetStartDate,
                targetEndDate = savedTag?.targetEndDate,
                currentPrice = savedTag?.currentPrice ?: 0,
                stateCrc = savedTag?.stateCrc ?: 0,
                status = savedTag?.status ?: TagStatus.PENDING
            )
        }.sortedByDescending { it.rssi }

        _mergedTags.value = merged
    }

    // ── BLE 이벤트 처리 ───────────────────────────────────────────

    private fun observeBleEvents() {
        viewModelScope.launch {
            bleManager.bleEvents.collect { event ->
                when (event) {
                    is BleEvent.TagInfoReceived -> {
                        viewModelScope.launch {
                            dao.updateCurrentState(
                                event.data.tagId,
                                event.data.price,
                                event.data.stateCrc
                            )
                            // StateCRC 비교 → PENDING 태그 자동 UPDATED 전환
                            checkAndConfirmSync(event.data.tagId, event.data.stateCrc)
                        }
                    }
                    is BleEvent.AnchorDetected -> {
                        handleAnchorDetected(event.groupId, event.rssi)
                    }
                    is BleEvent.AckReceived -> {
                        viewModelScope.launch {
                            dao.updateStatusByAddress(event.address, TagStatus.UPDATED)
                            dao.updateCurrentState(event.tagId, event.price, crc = 0)
                        }
                        _snackbarMessage.value = "Tag ${event.tagId} 업데이트 완료 (${event.price}원)"
                    }
                    is BleEvent.Error -> {
                        viewModelScope.launch {
                            dao.updateStatusByAddress(event.address, TagStatus.FAILED)
                        }
                        _snackbarMessage.value = "오류: ${event.message}"
                    }
                    else -> {}
                }
            }
        }
    }

    /**
     * 수신된 StateCRC와 목표 CRC 비교 → 일치 시 UPDATED 전환
     * PENDING 태그에만 적용 (UPDATED / FAILED 는 스킵)
     */
    private suspend fun checkAndConfirmSync(tagId: Int, receivedCrc: Int) {
        val tag = dao.getTagById(tagId) ?: return
        if (tag.status != TagStatus.PENDING) return

        val targetCrc = BlePackets.calculateTargetCrc(
            price = tag.targetPrice,
            event = tag.targetEvent,
            startDate = tag.targetStartDate,
            endDate = tag.targetEndDate,
            productName = tag.productName
        )

        if (targetCrc == receivedCrc) {
            dao.updateStatusByAddress(tag.deviceAddress, TagStatus.UPDATED)
            val label = tag.productName.ifBlank { "Tag ${tag.tagId}" }
            _snackbarMessage.value = "$label 동기화 완료"
        }
    }

    /**
     * 앵커 비콘(0x03) 감지 → RSSI 기록 → 3초 디바운스 후 최강 그룹 확정
     *
     * 디바운스 로직:
     * - 앵커가 감지될 때마다 Job을 취소·재시작
     * - 3초간 새 감지가 없으면 확정: 최근 5초 내 감지된 그룹 중 RSSI 최강 선택
     */
    private fun handleAnchorDetected(groupId: Int, rssi: Int) {
        anchorRssiMap[groupId] = rssi
        anchorLastSeen[groupId] = System.currentTimeMillis()

        anchorDebounceJob?.cancel()
        anchorDebounceJob = viewModelScope.launch {
            delay(3000L)

            val now = System.currentTimeMillis()
            val bestGroup = anchorLastSeen
                .filter { now - it.value < 5000L }           // 5초 이내 감지
                .maxByOrNull { anchorRssiMap[it.key] ?: Int.MIN_VALUE }
                ?.key

            if (bestGroup != null && bestGroup != _currentGroupId.value) {
                _currentGroupId.value = bestGroup
                _snackbarMessage.value = "구역 전환 → Group $bestGroup"
            }
        }
    }

    // ── 공개 API ──────────────────────────────────────────────────

    fun toggleScan() {
        if (isScanning.value) bleManager.stopScan() else bleManager.startScan()
    }

    /**
     * GATT 개별 수정 — 가격만 전송
     * ESP32 GATT 핸들러가 이벤트/날짜를 0으로 초기화하므로
     * targetEvent = NONE, 날짜 = null 로 저장
     */
    fun connectAndWrite(address: String, price: Int) {
        viewModelScope.launch {
            dao.setTargetState(address, price, EventType.NONE, null, null)
        }
        bleManager.connectAndWrite(address, price)
    }

    /**
     * 브로드캐스트로 단일 태그의 가격+이벤트+날짜 전송 (0x02)
     * DB 목표 상태 저장 후 broadcastDirect 호출
     */
    fun broadcastTagPrice(
        address: String,
        groupId: Int,
        tagId: Int,
        price: Int,
        event: EventType,
        startDate: LocalDate?,
        endDate: LocalDate?
    ) {
        viewModelScope.launch {
            dao.setTargetState(address, price, event, startDate, endDate)
        }
        broadcastDirect(groupId, tagId, price, event, startDate, endDate)
    }

    /**
     * 그룹 일괄 업데이트 (브로드캐스트 루프)
     *
     * 1. 그룹 내 모든 태그의 목표 상태를 DB에 기록 (→ PENDING)
     * 2. PENDING 태그가 없어질 때까지 3개씩 0x02 반복 전송
     * 3. 각 루프 주기: 5초 광고 + 2초 CRC 확인 여유
     * 4. maxRetries 초과 시 남은 PENDING → FAILED
     */
    fun startGroupBroadcast(
        groupId: Int,
        price: Int,
        event: EventType = EventType.NONE,
        startDate: LocalDate? = null,
        endDate: LocalDate? = null,
        maxRetries: Int = 15
    ) {
        broadcastLoopJob?.cancel()
        broadcastLoopJob = viewModelScope.launch {
            // 그룹 내 태그 전체 목표 상태 일괄 설정
            dao.setTargetStateForGroup(groupId, price, event, startDate, endDate)

            var retries = 0
            while (retries < maxRetries) {
                val pending = dao.getPendingTagsByGroup(groupId)
                if (pending.isEmpty()) {
                    _broadcastQueueState.value = BroadcastQueueState.Done(groupId)
                    _snackbarMessage.value = "Group $groupId 전체 업데이트 완료!"
                    return@launch
                }

                _broadcastQueueState.value = BroadcastQueueState.Running(groupId, pending.size)

                // 3개 배치 → 각 태그 개별 목표 상태로 Entry 구성
                val entries = pending.take(3).map { tag ->
                    PriceEntry(
                        tagId = tag.tagId,
                        price = price,
                        event = event,
                        startDate = startDate,
                        endDate = endDate
                    )
                }
                bleManager.broadcastPriceUpdate(groupId, entries, 5000L)

                delay(7000L)   // 5초 광고 + 2초 여유
                retries++
            }

            // 최대 재시도 초과 → 남은 PENDING을 FAILED로
            dao.getPendingTagsByGroup(groupId).forEach { tag ->
                dao.updateStatusByAddress(tag.deviceAddress, TagStatus.FAILED)
            }
            _broadcastQueueState.value = BroadcastQueueState.Idle
            _snackbarMessage.value = "Group $groupId: 일부 태그 업데이트 실패 (재시도 초과)"
        }
    }

    /**
     * 특정 TagID에 직접 브로드캐스트 (DB 조회 없이 테스트·단건 전송용)
     */
    fun broadcastDirect(
        groupId: Int,
        tagId: Int,
        price: Int,
        event: EventType = EventType.NONE,
        startDate: LocalDate? = null,
        endDate: LocalDate? = null,
        durationMs: Long = 5000L
    ) {
        val entries = listOf(
            PriceEntry(tagId = tagId, price = price, event = event,
                       startDate = startDate, endDate = endDate)
        )
        bleManager.broadcastPriceUpdate(groupId, entries, durationMs)
        _snackbarMessage.value = "Group $groupId → Tag $tagId: ${"%,d".format(price)}원 브로드캐스트 중..."
    }

    /**
     * 상품명 브로드캐스트 (0x04 단편 순차 전송) + DB 저장
     */
    fun broadcastTagName(
        address: String,
        groupId: Int,
        tagId: Int,
        name: String,
        onComplete: () -> Unit = {}
    ) {
        bleManager.broadcastNameUpdate(groupId, tagId, name, onComplete = {
            viewModelScope.launch {
                dao.setProductName(address, name)
                _snackbarMessage.value = "상품명 '$name' 저장 완료"
            }
            onComplete()
        })
    }

    fun saveGroupId(address: String, groupId: Int) {
        viewModelScope.launch { dao.setGroupId(address, groupId) }
    }

    fun stopBroadcast() {
        broadcastLoopJob?.cancel()
        _broadcastQueueState.value = BroadcastQueueState.Idle
        bleManager.stopBroadcast()
    }

    fun upsertTag(tag: SmartTag) {
        viewModelScope.launch { dao.upsert(tag.toEntity()) }
    }

    fun clearSnackbar() {
        _snackbarMessage.value = null
    }

    override fun onCleared() {
        bleManager.stopScan()
        bleManager.disconnect()
        bleManager.stopBroadcast()
        super.onCleared()
    }
}
