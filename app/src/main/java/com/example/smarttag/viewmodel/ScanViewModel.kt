package com.example.smarttag.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.smarttag.ble.BleEvent
import com.example.smarttag.ble.BleManager
import com.example.smarttag.ble.PriceEntry
import com.example.smarttag.db.AppDatabase
import com.example.smarttag.db.CategoryEntity
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

private const val RSSI_THRESHOLD = -75          // dBm 이상만 "근거리"로 판정
private const val REBROADCAST_COOLDOWN_MS = 5000L  // 동일 TagID 재전송 금지 시간

class ScanViewModel(application: Application) : AndroidViewModel(application) {

    val bleManager = BleManager(application)
    private val dao = AppDatabase.getInstance(application).smartTagDao()
    private val categoryDao = AppDatabase.getInstance(application).categoryDao()

    // ── 카테고리 ──────────────────────────────────────────────────
    private val _categories = MutableStateFlow<List<CategoryEntity>>(emptyList())
    val categories: StateFlow<List<CategoryEntity>> = _categories.asStateFlow()

    val isScanning: StateFlow<Boolean> = bleManager.isScanning
    val discoveredTags: StateFlow<Map<String, SmartTag>> = bleManager.discoveredTags

    private val _savedTags = MutableStateFlow<List<SmartTag>>(emptyList())
    val savedTags: StateFlow<List<SmartTag>> = _savedTags.asStateFlow()

    private val _mergedTags = MutableStateFlow<List<SmartTag>>(emptyList())
    val mergedTags: StateFlow<List<SmartTag>> = _mergedTags.asStateFlow()

    private val _snackbarMessage = MutableStateFlow<String?>(null)
    val snackbarMessage: StateFlow<String?> = _snackbarMessage.asStateFlow()

    // ── RSSI 기반 자동 구역 감지 ──────────────────────────────────
    private val _currentGroupId = MutableStateFlow<Int?>(null)
    val currentGroupId: StateFlow<Int?> = _currentGroupId.asStateFlow()

    // ── 브로드캐스트 큐 상태 ─────────────────────────────────────
    private val _broadcastQueueState = MutableStateFlow<BroadcastQueueState>(BroadcastQueueState.Idle)
    val broadcastQueueState: StateFlow<BroadcastQueueState> = _broadcastQueueState.asStateFlow()

    // ── Seq 번호 관리 ─────────────────────────────────────────────
    private var nextSeq = 1
    private val seqMap = mutableMapOf<Int, List<Int>>()    // seq → [tagId, ...]
    private val nameSeq = mutableMapOf<Int, Int>()          // tagId → 마지막 단편 Seq

    // ── Walk-by 중복 전송 방지 ───────────────────────────────────
    private val lastBroadcastTime = mutableMapOf<Int, Long>()

    // ── 브로드캐스트 루프 Job ─────────────────────────────────────
    private var broadcastLoopJob: Job? = null

    init {
        observeCategories()
        observeDb()
        observeBleEvents()
        observeDiscoveredTags()
    }

    private fun observeCategories() {
        viewModelScope.launch {
            categoryDao.getAllCategories().collect { list ->
                _categories.value = list
                // 최초 실행: 기본 카테고리 삽입
                if (list.isEmpty()) {
                    listOf(
                        CategoryEntity(1, "라면"),
                        CategoryEntity(2, "음료수"),
                        CategoryEntity(3, "과자"),
                        CategoryEntity(4, "과일/채소"),
                        CategoryEntity(5, "유제품")
                    ).forEach { categoryDao.upsert(it) }
                }
            }
        }
    }

    fun addOrUpdateCategory(groupId: Int, name: String) {
        viewModelScope.launch { categoryDao.upsert(CategoryEntity(groupId, name)) }
    }

    fun deleteCategory(groupId: Int) {
        viewModelScope.launch { categoryDao.delete(groupId) }
    }

    fun getCategoryName(groupId: Int): String =
        _categories.value.firstOrNull { it.groupId == groupId }?.name ?: "Group $groupId"

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
            bleManager.discoveredTags.collect {
                mergeTags()
            }
        }
    }

    private fun mergeTags() {
        val scanned = bleManager.discoveredTags.value
        val saved = _savedTags.value.associateBy { it.deviceAddress }

        val merged = scanned.values.map { scannedTag ->
            val savedTag = saved[scannedTag.deviceAddress]
            scannedTag.copy(
                tagId = savedTag?.tagId?.takeIf { it > 0 } ?: scannedTag.tagId,
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
        updateZone()
    }

    // ── RSSI 합산 기반 구역 자동 감지 ────────────────────────────

    private fun updateZone() {
        val visible = _mergedTags.value
        val score = visible
            .filter { it.groupId > 0 && it.rssi >= RSSI_THRESHOLD }
            .groupBy { it.groupId }
            .mapValues { (_, tags) -> tags.sumOf { (it.rssi + 100).coerceAtLeast(0) } }

        val detected = score.maxByOrNull { it.value }?.key ?: return
        if (detected != _currentGroupId.value) {
            _currentGroupId.value = detected
            _snackbarMessage.value = "구역 전환 → ${getCategoryName(detected)}"
        }
    }

    // ── BLE 이벤트 처리 ───────────────────────────────────────────

    private fun observeBleEvents() {
        viewModelScope.launch {
            bleManager.bleEvents.collect { event ->
                when (event) {
                    is BleEvent.TagInfoReceived -> {
                        val tagId   = event.data.tagId
                        val lastSeq = event.data.lastSeq
                        viewModelScope.launch {
                            val existing = dao.getTagByAddress(event.address)
                            if (existing != null) {
                                // tagId가 미설정(0)인 경우에만 0x01 기준으로 채움
                                // GATT로 한 번 설정된 값(>0)은 0x01이 덮어쓰지 않음
                                if (existing.tagId == 0 && tagId > 0) {
                                    dao.setTagId(event.address, tagId)
                                }
                                val effectiveTagId = if (existing.tagId > 0) existing.tagId else tagId
                                dao.updateCurrentState(effectiveTagId, event.data.price, lastSeq)
                            }
                        }
                        onTag01Received(tagId, lastSeq, event.rssi)
                    }
                    is BleEvent.AckReceived -> {
                        viewModelScope.launch {
                            dao.updateStatusByAddress(event.address, TagStatus.UPDATED)
                            dao.updateCurrentState(event.tagId, event.price, crc = 0)
                        }
                        _snackbarMessage.value = "Tag ${event.tagId} 업데이트 완료 (${event.price}원)"
                    }
                    is BleEvent.ConfigWriteSuccess -> {
                        bleManager.updateTagIdInDiscovered(event.address, event.tagId)
                        viewModelScope.launch {
                            val scanned = bleManager.discoveredTags.value[event.address]
                            if (scanned != null) {
                                dao.upsert(scanned.copy(tagId = event.tagId).toEntity())
                            } else {
                                dao.setTagId(event.address, event.tagId)
                            }
                        }
                        _snackbarMessage.value = "설정 완료 → Tag ID: ${event.tagId}"
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
     * Seq 기반 ACK 확인 (suspend — DB 쓰기 완료 후 반환)
     * seqMap[lastSeq]에 tagId 포함 → 가격 업데이트 완료
     * nameSeq[tagId] == lastSeq   → 이름 업데이트 완료
     * @return true이면 ACK 처리 완료 (재전송 불필요)
     */
    private suspend fun processSeqAck(tagId: Int, lastSeq: Int): Boolean {
        if (lastSeq == 0) return false

        val priceTagIds = seqMap[lastSeq]
        if (priceTagIds != null && tagId in priceTagIds) {
            seqMap.remove(lastSeq)
            dao.updateStatusById(tagId, TagStatus.UPDATED)
            val label = dao.getTagById(tagId)?.productName?.ifBlank { "Tag $tagId" } ?: "Tag $tagId"
            _snackbarMessage.value = "$label 동기화 완료"
            return true
        }

        if (nameSeq[tagId] == lastSeq) {
            nameSeq.remove(tagId)
            dao.updateStatusById(tagId, TagStatus.UPDATED)
            return true
        }

        return false
    }

    /**
     * 0x01 수신 처리: ACK 확인 + Walk-by 자동 브로드캐스트 트리거
     *
     * ACK 처리와 Walk-by 재전송 판단을 단일 coroutine 안에서 순차 실행하여
     * "ACK DB 쓰기"와 "Walk-by DB 읽기"의 경쟁 조건을 방지
     */
    private fun onTag01Received(tagId: Int, lastSeq: Int, rssi: Int) {
        if (rssi < RSSI_THRESHOLD) {
            // 원거리 태그: Walk-by는 생략하지만 ACK는 처리
            viewModelScope.launch { processSeqAck(tagId, lastSeq) }
            return
        }

        val now = System.currentTimeMillis()
        val lastSent = lastBroadcastTime[tagId] ?: 0L
        if (now - lastSent < REBROADCAST_COOLDOWN_MS) {
            // 쿨다운 중: Walk-by는 생략하지만 ACK는 처리
            viewModelScope.launch { processSeqAck(tagId, lastSeq) }
            return
        }

        lastBroadcastTime[tagId] = now

        viewModelScope.launch {
            // ACK 처리를 먼저 완료한 뒤 DB 상태 확인 → 경쟁 조건 제거
            val acked = processSeqAck(tagId, lastSeq)
            if (acked) return@launch  // ACK 완료 → 재전송 불필요

            val tag = dao.getTagById(tagId) ?: return@launch
            if (tag.status != TagStatus.PENDING) return@launch
            if (tag.targetPrice == 0) return@launch  // 목표 가격 미설정 → 브로드캐스트 생략

            // PENDING 태그가 근처에 있으면 자동 브로드캐스트
            val seq = allocSeqs(1)
            val entry = PriceEntry(
                tagId = tag.tagId,
                price = tag.targetPrice,
                event = tag.targetEvent,
                startDate = tag.targetStartDate,
                endDate = tag.targetEndDate
            )
            seqMap[seq] = listOf(tagId)
            bleManager.broadcastPriceUpdate(seq, listOf(entry))
            val label = tag.productName.ifBlank { "Tag $tagId" }
            _snackbarMessage.value = "$label 자동 업데이트 중..."
        }
    }

    // ── Seq 번호 할당 ─────────────────────────────────────────────

    /**
     * count개의 Seq 번호를 순서대로 할당하고 첫 번째 Seq를 반환
     * Seq는 1~255 범위에서 순환
     */
    private fun allocSeqs(count: Int): Int {
        val start = nextSeq
        repeat(count) {
            nextSeq = if (nextSeq >= 255) 1 else nextSeq + 1
        }
        return start
    }

    // ── 공개 API ──────────────────────────────────────────────────

    fun toggleScan() {
        if (isScanning.value) bleManager.stopScan() else bleManager.startScan()
    }

    /**
     * GATT 개별 수정 — 가격만 전송
     */
    fun connectAndWrite(address: String, price: Int) {
        viewModelScope.launch {
            dao.setTargetState(address, price, EventType.NONE, null, null)
        }
        bleManager.connectAndWrite(address, price)
    }

    /**
     * 브로드캐스트로 단일 태그의 가격+이벤트+날짜 전송 (0x02)
     */
    fun broadcastTagPrice(
        address: String,
        tagId: Int,
        price: Int,
        event: EventType,
        startDate: LocalDate?,
        endDate: LocalDate?
    ) {
        viewModelScope.launch {
            dao.setTargetState(address, price, event, startDate, endDate)
        }
        broadcastDirect(tagId, price, event, startDate, endDate)
    }

    /**
     * 그룹 일괄 업데이트 (브로드캐스트 루프)
     *
     * 각 태그에 미리 설정된 개별 targetPrice/Event/Date를 그대로 사용.
     * PriceUpdateScreen처럼 동일 가격을 설정한 뒤 전송하려면 applyGroupPriceAndBroadcast를 사용.
     */
    fun startGroupBroadcast(groupId: Int, maxRetries: Int = 15) {
        broadcastLoopJob?.cancel()
        broadcastLoopJob = viewModelScope.launch {
            runGroupBroadcastLoop(groupId, maxRetries)
        }
    }

    private suspend fun runGroupBroadcastLoop(groupId: Int, maxRetries: Int = 15) {
        var retries = 0
        while (retries < maxRetries) {
            val pending = dao.getPendingTagsByGroup(groupId)
            if (pending.isEmpty()) {
                _broadcastQueueState.value = BroadcastQueueState.Done(groupId)
                _snackbarMessage.value = "${getCategoryName(groupId)} 전체 업데이트 완료!"
                return
            }

            _broadcastQueueState.value = BroadcastQueueState.Running(groupId, pending.size)

            // targetPrice=0 태그는 설정 미완료 → 브로드캐스트에서 제외
            val readyTags = pending.filter { it.targetPrice > 0 }
            if (readyTags.isEmpty()) {
                _broadcastQueueState.value = BroadcastQueueState.Idle
                _snackbarMessage.value = "가격이 설정된 태그가 없습니다. 태그 상세에서 설정하세요."
                return
            }

            val entries = readyTags.take(3).map { tag ->
                PriceEntry(tag.tagId, tag.targetPrice, tag.targetEvent, tag.targetStartDate, tag.targetEndDate)
            }
            val seq = allocSeqs(1)
            seqMap[seq] = entries.map { it.tagId }
            bleManager.broadcastPriceUpdate(seq, entries, 5000L)

            delay(7000L)
            retries++
        }

        dao.getPendingTagsByGroup(groupId).forEach { tag ->
            dao.updateStatusByAddress(tag.deviceAddress, TagStatus.FAILED)
        }
        _broadcastQueueState.value = BroadcastQueueState.Idle
        _snackbarMessage.value = "${getCategoryName(groupId)}: 일부 태그 업데이트 실패 (재시도 초과)"
    }

    /**
     * 특정 TagID에 직접 브로드캐스트
     */
    fun broadcastDirect(
        tagId: Int,
        price: Int,
        event: EventType = EventType.NONE,
        startDate: LocalDate? = null,
        endDate: LocalDate? = null,
        durationMs: Long = 5000L
    ) {
        val seq = allocSeqs(1)
        seqMap[seq] = listOf(tagId)
        val entries = listOf(PriceEntry(tagId, price, event, startDate, endDate))
        bleManager.broadcastPriceUpdate(seq, entries, durationMs)
        _snackbarMessage.value = "Tag $tagId: ${"%,d".format(price)}원 브로드캐스트 중..."
    }

    /**
     * 상품명 브로드캐스트 (0x04 단편 순차 전송) + DB 저장
     */
    fun broadcastTagName(
        address: String,
        tagId: Int,
        name: String,
        onComplete: () -> Unit = {}
    ) {
        val nameBytes = name.toByteArray(Charsets.UTF_8)
        val totalFrags = if (nameBytes.isEmpty()) 1 else (nameBytes.size + 17) / 18
        val startSeq = allocSeqs(totalFrags)

        bleManager.broadcastNameUpdate(tagId, name, startSeq, onComplete = { lastFragSeq ->
            nameSeq[tagId] = lastFragSeq
            viewModelScope.launch {
                dao.setProductName(address, name)
                _snackbarMessage.value = "상품명 '$name' 저장 완료"
            }
            onComplete()
        })
    }

    /**
     * GATT로 TagID를 ESP32에 전송 후 DB에 반영
     * (GroupID는 앱 UI 전용 — ESP32에 미전송)
     */
    fun writeConfig(address: String, tagId: Int) {
        bleManager.connectAndWriteConfig(address, tagId)
    }

    /**
     * 태그별 브로드캐스트 목표 상태 저장 (DB → PENDING)
     * 저장 후 Walk-by 자동 브로드캐스트 또는 그룹 브로드캐스트 시 사용됨
     * 태그가 DB에 없으면 먼저 upsert (스캔만 된 태그도 추적 가능)
     */
    fun setTargetState(
        address: String,
        price: Int,
        event: EventType,
        startDate: LocalDate?,
        endDate: LocalDate?
    ) {
        viewModelScope.launch {
            ensureTagInDb(address)
            dao.setTargetState(address, price, event, startDate, endDate)
        }
    }

    fun saveGroupId(address: String, groupId: Int) {
        viewModelScope.launch {
            ensureTagInDb(address)
            dao.setGroupId(address, groupId)
        }
    }

    /** 태그가 DB에 없으면 현재 스캔 정보로 upsert */
    private suspend fun ensureTagInDb(address: String) {
        if (dao.getTagByAddress(address) == null) {
            val tag = _mergedTags.value.firstOrNull { it.deviceAddress == address }
                ?: bleManager.discoveredTags.value[address]
            if (tag != null) dao.upsert(tag.toEntity())
        }
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
