package com.example.smarttag.viewmodel

import android.app.Application
import android.util.Log
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

private const val LTAG = "ScanVM_ACK"

// ── 브로드캐스트 큐 상태 ──────────────────────────────────────────
sealed class BroadcastQueueState {
    object Idle : BroadcastQueueState()
    data class Running(val pendingCount: Int) : BroadcastQueueState()
    object Done : BroadcastQueueState()
}


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

    // ── B안: 이름 ACK 전에 가격 ACK가 먼저 온 태그 주소 집합 ──────
    private val priceAckedAddresses = mutableSetOf<String>()

    // ── 브로드캐스트 오프셋 (순환 전송용) ────────────────────────
    private var broadcastOffset = 0

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
                status = savedTag?.status ?: TagStatus.PENDING,
                targetName = savedTag?.targetName ?: ""
            )
        }.sortedByDescending { it.rssi }

        _mergedTags.value = merged
        updateZone()
    }

    // ── RSSI 합산 기반 구역 자동 감지 ────────────────────────────

    private fun updateZone() {
        val visible = _mergedTags.value
        val score = visible
            .filter { it.groupId > 0 && it.rssi >= -75 }
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
                            if (existing == null) {
                                Log.d(LTAG, "[0x01] addr=${event.address} → DB에 없음, 스킵")
                                return@launch
                            }

                            // tagId가 미설정(0)인 경우에만 0x01 기준으로 채움
                            if (existing.tagId == 0 && tagId > 0) {
                                dao.setTagId(event.address, tagId)
                            }
                            val effectiveTagId = if (existing.tagId > 0) existing.tagId else tagId
                            dao.updateLastSeq(effectiveTagId, lastSeq)

                            // ── 수신 패킷 전체 덤프 ──────────────────────────────────
                            Log.d(LTAG, "┌─[0x01 수신]─────────────────────────────────────")
                            Log.d(LTAG, "│ addr       = ${event.address}")
                            Log.d(LTAG, "│ rxTagId    = $tagId  →  effectiveTagId = $effectiveTagId")
                            Log.d(LTAG, "│ rxLastSeq  = $lastSeq")
                            Log.d(LTAG, "│ dbStatus   = ${existing.status}")
                            Log.d(LTAG, "│ seqMap     = $seqMap")
                            Log.d(LTAG, "│ nameSeq    = $nameSeq")

                            if (existing.status != TagStatus.PENDING) {
                                Log.d(LTAG, "└─ 스킵: status=${existing.status} (PENDING 아님)")
                                return@launch
                            }

                            val label = existing.productName.ifBlank { "Tag $effectiveTagId" }

                            // ── 1순위: 이름 Seq ACK ────────────────────────────────
                            if (lastSeq > 0) {
                                val expectedNameSeq = nameSeq[effectiveTagId]
                                Log.d(LTAG, "│ [NameSeqACK] nameSeq[$effectiveTagId]=$expectedNameSeq vs lastSeq=$lastSeq")
                                if (expectedNameSeq != null && expectedNameSeq == lastSeq) {
                                    nameSeq.remove(effectiveTagId)
                                    val targetName = existing.targetName
                                    if (targetName.isNotEmpty()) {
                                        dao.confirmName(event.address, targetName)
                                        Log.d(LTAG, "│ ✅ Name ACK → productName='$targetName' 저장")
                                    }
                                    // B안: 가격이 이미 ACK됐다면 지금 UPDATED
                                    if (event.address in priceAckedAddresses) {
                                        priceAckedAddresses.remove(event.address)
                                        dao.updateStatusByAddress(event.address, TagStatus.UPDATED)
                                        Log.d(LTAG, "└─ ✅ Name+Price 모두 완료 → UPDATED")
                                        _snackbarMessage.value = "$label 동기화 완료"
                                    } else {
                                        Log.d(LTAG, "└─ ⏳ Name ACK 완료, Price ACK 대기 중")
                                    }
                                    return@launch
                                }
                            }

                            // ── 2순위: 가격 Seq ACK ────────────────────────────────
                            if (lastSeq > 0) {
                                val priceTagIds = seqMap[lastSeq]
                                Log.d(LTAG, "│ [PriceSeqACK] lastSeq=$lastSeq → seqMap[$lastSeq]=$priceTagIds  effectiveTagId=$effectiveTagId")
                                if (priceTagIds != null && effectiveTagId in priceTagIds) {
                                    val remaining = priceTagIds.filterNot { it == effectiveTagId }
                                    if (remaining.isEmpty()) seqMap.remove(lastSeq)
                                    else seqMap[lastSeq] = remaining
                                    // B안: targetName이 비어야 UPDATED
                                    val freshTargetName = dao.getTagByAddress(event.address)?.targetName ?: ""
                                    if (freshTargetName.isEmpty()) {
                                        priceAckedAddresses.remove(event.address)
                                        dao.updateStatusByAddress(event.address, TagStatus.UPDATED)
                                        Log.d(LTAG, "└─ ✅ Price Seq ACK 완료 (seq=$lastSeq)")
                                        _snackbarMessage.value = "$label 동기화 완료"
                                    } else {
                                        priceAckedAddresses.add(event.address)
                                        Log.d(LTAG, "└─ ⏳ Price ACK 완료, Name ACK 대기 중 (targetName='$freshTargetName')")
                                    }
                                    return@launch
                                }
                                Log.d(LTAG, "│ [PriceSeqACK] → 미일치")
                            } else {
                                Log.d(LTAG, "│ [SeqACK] lastSeq=0 → Seq 기반 ACK 건너뜀")
                            }

                            Log.d(LTAG, "└─ ❌ Seq 미일치 (lastSeq=$lastSeq 매핑 없음)")
                        }
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
                            val existing = dao.getTagByAddress(event.address)
                            if (existing != null) {
                                // 기존 행이 있으면 tagId 컬럼만 수정 — currentPrice·targetPrice 등 보존
                                dao.setTagId(event.address, event.tagId)
                            } else {
                                // 처음 보는 태그: 스캔 데이터로 전체 삽입
                                val scanned = bleManager.discoveredTags.value[event.address]
                                if (scanned != null) {
                                    dao.upsert(scanned.copy(tagId = event.tagId).toEntity())
                                }
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
     * 전체 PENDING 태그 일괄 브로드캐스트 루프
     *
     * 카테고리 무관하게 DB의 모든 PENDING 태그를 대상으로 순차 전송.
     * 각 태그에 미리 설정된 개별 targetPrice/Event/Date를 사용.
     */
    fun startBroadcast(maxRetries: Int = 15) {
        broadcastLoopJob?.cancel()
        broadcastOffset = 0
        broadcastLoopJob = viewModelScope.launch {
            runBroadcastLoop(maxRetries)
        }
    }

    // Seq 순환 래핑 (1..255)
    private fun wrapSeqLocal(s: Int): Int = ((s - 1) % 255) + 1

    private fun nameFragCount(name: String): Int =
        maxOf(1, (name.toByteArray(Charsets.UTF_8).size + 17) / 18)

    private suspend fun runBroadcastLoop(maxRetries: Int = 15) {
        var retries = 0
        while (retries < maxRetries) {
            val pending = dao.getAllPendingTags()
            if (pending.isEmpty()) {
                _broadcastQueueState.value = BroadcastQueueState.Done
                _snackbarMessage.value = "전체 태그 업데이트 완료!"
                return
            }

            // targetPrice=0 태그는 설정 미완료 → 브로드캐스트에서 제외
            val readyTags = pending.filter { it.targetPrice > 0 }
            if (readyTags.isEmpty()) {
                _broadcastQueueState.value = BroadcastQueueState.Idle
                _snackbarMessage.value = "가격이 설정된 태그가 없습니다. 태그 상세에서 설정하세요."
                return
            }

            _broadcastQueueState.value = BroadcastQueueState.Running(readyTags.size)

            // ── Step A: 이름 브로드캐스트 (targetName 있는 태그) ─────────
            // 이름이 아직 미확인인 태그만 대상 (nameSeq에 없으면 아직 전송 안 했거나 ACK 완료)
            val namePendingTags = readyTags.filter { it.targetName.isNotEmpty() }
            for (tag in namePendingTags) {
                val fragCount = nameFragCount(tag.targetName)
                val startSeq  = allocSeqs(fragCount)
                val lastFragSeq = wrapSeqLocal(startSeq + fragCount - 1)
                nameSeq[tag.tagId] = lastFragSeq

                bleManager.stopScan()
                bleManager.broadcastNameUpdate(tag.tagId, tag.targetName, startSeq)
                Log.d(LTAG, "[name broadcast] tagId=${tag.tagId} name='${tag.targetName}' frags=$fragCount startSeq=$startSeq lastFragSeq=$lastFragSeq")

                // 모든 단편 전송 완료까지 대기 (1s/단편 + 여유 500ms)
                delay(fragCount * 1000L + 500L)

                // 이름 ACK 수신 스캔 (2s)
                bleManager.startScan()
                delay(2000L)
                bleManager.stopScan()
            }

            // ── Step B: 가격 브로드캐스트 (0x02) ───────────────────────
            val startIdx = broadcastOffset % readyTags.size
            val entries = (0 until minOf(3, readyTags.size)).map { i ->
                val tag = readyTags[(startIdx + i) % readyTags.size]
                PriceEntry(tag.tagId, tag.targetPrice, tag.targetEvent, tag.targetStartDate, tag.targetEndDate)
            }
            broadcastOffset += 3
            Log.d(LTAG, "[price broadcast] seq=${nextSeq} entries=${entries.joinToString { "tagId=${it.tagId} price=${it.price} event=${it.event}" }}")
            val seq = allocSeqs(1)
            seqMap[seq] = entries.map { it.tagId }

            bleManager.stopScan()
            bleManager.broadcastPriceUpdate(seq, entries, 5000L)

            // 5s 광고 종료까지 대기 + ESP32가 0x01 갱신할 시간 확보
            delay(5500L)

            // 스캔 재시작 → 업데이트된 0x01 수신 (가격+이름 ACK 동시 처리)
            bleManager.startScan()
            delay(3000L)

            retries++
        }

        dao.getAllPendingTags().forEach { tag ->
            dao.updateStatusByAddress(tag.deviceAddress, TagStatus.FAILED)
        }
        _broadcastQueueState.value = BroadcastQueueState.Idle
        _snackbarMessage.value = "일부 태그 업데이트 실패 (재시도 초과)"
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

    /**
     * 브로드캐스트 시 상품명 변경 예약 (DB → PENDING, targetName 설정)
     * 빈 문자열 전달 시 예약 취소
     */
    fun setTargetName(address: String, name: String) {
        viewModelScope.launch {
            ensureTagInDb(address)
            dao.setTargetName(address, name.trim())
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
