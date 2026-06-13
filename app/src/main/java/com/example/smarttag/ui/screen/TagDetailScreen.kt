package com.example.smarttag.ui.screen

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Campaign
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Send
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.example.smarttag.model.EventType
import com.example.smarttag.model.SmartTag
import com.example.smarttag.model.TagStatus
import com.example.smarttag.viewmodel.ScanViewModel
import java.time.LocalDate

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TagDetailScreen(
    address: String,
    viewModel: ScanViewModel,
    onBack: () -> Unit,
    onNameUpdateClick: (String) -> Unit = {}
) {
    val tags by viewModel.mergedTags.collectAsState()
    val tag = tags.firstOrNull { it.deviceAddress == address }

    // ── 입력 상태 ─────────────────────────────────────────────────
    var groupIdInput by remember { mutableStateOf("") }
    var priceInput by remember { mutableStateOf("") }
    var selectedEvent by remember { mutableStateOf(EventType.NONE) }
    var startMonth by remember { mutableStateOf("") }
    var startDay by remember { mutableStateOf("") }
    var endMonth by remember { mutableStateOf("") }
    var endDay by remember { mutableStateOf("") }
    var showGattDialog by remember { mutableStateOf(false) }
    var showBroadcastDialog by remember { mutableStateOf(false) }

    // 최초 1회만 기존 값으로 초기화
    LaunchedEffect(address) {
        tag?.let {
            groupIdInput = if (it.groupId > 0) it.groupId.toString() else ""
            priceInput = if (it.targetPrice > 0) it.targetPrice.toString() else ""
            selectedEvent = it.targetEvent
            startMonth = it.targetStartDate?.monthValue?.toString() ?: ""
            startDay = it.targetStartDate?.dayOfMonth?.toString() ?: ""
            endMonth = it.targetEndDate?.monthValue?.toString() ?: ""
            endDay = it.targetEndDate?.dayOfMonth?.toString() ?: ""
        }
    }

    val snackbar by viewModel.snackbarMessage.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }
    LaunchedEffect(snackbar) {
        snackbar?.let {
            snackbarHostState.showSnackbar(it)
            viewModel.clearSnackbar()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(tag?.productName?.ifBlank { tag.deviceName } ?: address) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "뒤로")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer
                )
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) }
    ) { padding ->
        if (tag == null) {
            Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                Text("태그 정보를 찾을 수 없습니다.")
            }
            return@Scaffold
        }

        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // ── 태그 현재 상태 ───────────────────────────────────
            TagInfoCard(tag = tag)

            // ── 그룹 설정 ────────────────────────────────────────
            Card(shape = RoundedCornerShape(12.dp)) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("그룹 설정", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    OutlinedTextField(
                        value = groupIdInput,
                        onValueChange = { v ->
                            groupIdInput = v.filter { it.isDigit() }
                            val gid = groupIdInput.toIntOrNull()
                            if (gid != null && gid > 0) viewModel.saveGroupId(address, gid)
                        },
                        label = { Text("Group ID (1~255)") },
                        modifier = Modifier.fillMaxWidth(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        singleLine = true,
                        supportingText = { Text("같은 매대의 태그는 동일한 Group ID 사용") }
                    )
                }
            }

            // ── 가격 · 이벤트 · 날짜 (GATT 전송) ────────────────
            Card(shape = RoundedCornerShape(12.dp)) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("가격 수정 (GATT)", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)

                    // 가격 입력
                    OutlinedTextField(
                        value = priceInput,
                        onValueChange = { priceInput = it.filter { c -> c.isDigit() } },
                        label = { Text("가격 (원)") },
                        modifier = Modifier.fillMaxWidth(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        singleLine = true,
                        suffix = { Text("원") }
                    )

                    // 이벤트 선택
                    Text("이벤트", style = MaterialTheme.typography.labelLarge)
                    EventSelector(
                        selected = selectedEvent,
                        onSelect = { selectedEvent = it }
                    )

                    // 날짜 (이벤트가 있을 때만 표시)
                    if (selectedEvent != EventType.NONE) {
                        Text("이벤트 기간", style = MaterialTheme.typography.labelLarge)
                        DateRangeInput(
                            startMonth = startMonth, startDay = startDay,
                            endMonth = endMonth, endDay = endDay,
                            onStartMonthChange = { if (it.length <= 2) startMonth = it.filter(Char::isDigit) },
                            onStartDayChange   = { if (it.length <= 2) startDay   = it.filter(Char::isDigit) },
                            onEndMonthChange   = { if (it.length <= 2) endMonth   = it.filter(Char::isDigit) },
                            onEndDayChange     = { if (it.length <= 2) endDay     = it.filter(Char::isDigit) }
                        )
                    }

                    // GATT 전송 (가격만 — 이벤트/날짜 전송 불가)
                    Button(
                        onClick = { showGattDialog = true },
                        enabled = priceInput.isNotBlank(),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(Icons.Default.Send, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text("GATT 전송 (가격만)")
                    }

                    // 브로드캐스트 전송 (가격 + 이벤트 + 날짜)
                    val broadcastGroupId = groupIdInput.toIntOrNull() ?: 0
                    OutlinedButton(
                        onClick = { showBroadcastDialog = true },
                        enabled = priceInput.isNotBlank() && broadcastGroupId > 0,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(Icons.Default.Campaign, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text(
                            if (broadcastGroupId == 0) "브로드캐스트 (Group ID 필요)"
                            else "브로드캐스트 전송 (가격+이벤트+날짜)"
                        )
                    }
                }
            }

            // ── 상품명 수정 ──────────────────────────────────────
            Card(shape = RoundedCornerShape(12.dp)) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Column {
                        Text("상품명", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                        Text(
                            tag.productName.ifBlank { "미설정" },
                            style = MaterialTheme.typography.bodyMedium,
                            color = if (tag.productName.isBlank())
                                MaterialTheme.colorScheme.outline
                            else
                                MaterialTheme.colorScheme.onSurface
                        )
                    }
                    OutlinedButton(onClick = { onNameUpdateClick(address) }) {
                        Icon(Icons.Default.Edit, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("수정")
                    }
                }
            }
        }
    }

    // ── GATT 전송 다이얼로그 (가격만) ────────────────────────────
    if (showGattDialog && tag != null) {
        val price = priceInput.toIntOrNull() ?: 0
        AlertDialog(
            onDismissRequest = { showGattDialog = false },
            title = { Text("GATT 전송 확인") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("${tag.productName.ifBlank { tag.deviceName }}에 연결하여 전송")
                    Text("가격: ${"%,d원".format(price)}")
                    Text(
                        "이벤트/날짜는 GATT로 전송할 수 없습니다.\n브로드캐스트 전송을 이용하세요.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.connectAndWrite(address, price)
                    showGattDialog = false
                }) { Text("전송") }
            },
            dismissButton = {
                TextButton(onClick = { showGattDialog = false }) { Text("취소") }
            }
        )
    }

    // ── 브로드캐스트 전송 다이얼로그 (가격+이벤트+날짜) ──────────
    if (showBroadcastDialog && tag != null) {
        val price     = priceInput.toIntOrNull() ?: 0
        val groupId   = groupIdInput.toIntOrNull() ?: 0
        val startDate = buildDate(startMonth, startDay)
        val endDate   = buildDate(endMonth, endDay)
        AlertDialog(
            onDismissRequest = { showBroadcastDialog = false },
            title = { Text("브로드캐스트 전송 확인") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("Group $groupId → TAG-${"%03d".format(tag.tagId)} 로 0x02 전송 (5초)")
                    Text("가격: ${"%,d원".format(price)}")
                    Text("이벤트: ${eventLabel(selectedEvent)}")
                    if (startDate != null || endDate != null) {
                        Text("기간: ${formatDate(startDate)} ~ ${formatDate(endDate)}")
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.broadcastTagPrice(
                        address   = address,
                        groupId   = groupId,
                        tagId     = tag.tagId,
                        price     = price,
                        event     = selectedEvent,
                        startDate = startDate,
                        endDate   = endDate
                    )
                    showBroadcastDialog = false
                }) { Text("전송") }
            },
            dismissButton = {
                TextButton(onClick = { showBroadcastDialog = false }) { Text("취소") }
            }
        )
    }
}

// ── 서브 컴포저블 ──────────────────────────────────────────────────

@Composable
private fun TagInfoCard(tag: SmartTag) {
    Card(shape = RoundedCornerShape(12.dp)) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("태그 정보", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            HorizontalDivider()
            InfoRow("Tag ID", "TAG-%03d".format(tag.tagId))
            InfoRow("MAC", tag.deviceAddress)
            InfoRow("RSSI", "${tag.rssi} dBm")
            InfoRow("그룹", if (tag.groupId > 0) "Group ${tag.groupId}" else "미설정")
            InfoRow("현재 가격", if (tag.currentPrice > 0) "%,d원".format(tag.currentPrice) else "수신 대기")
            InfoRow("목표 가격", if (tag.targetPrice > 0) "%,d원".format(tag.targetPrice) else "미설정")
            if (tag.targetEvent != EventType.NONE) {
                InfoRow("이벤트", eventLabel(tag.targetEvent))
            }
            InfoRow("상태", when (tag.status) {
                TagStatus.UPDATED -> "동기화 완료"
                TagStatus.PENDING -> "업데이트 대기"
                TagStatus.FAILED  -> "실패"
            })
            InfoRow("StateCRC", "0x%04X".format(tag.stateCrc))
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun EventSelector(selected: EventType, onSelect: (EventType) -> Unit) {
    val events = listOf(
        EventType.NONE         to "없음",
        EventType.ONE_PLUS_ONE to "1+1",
        EventType.TWO_PLUS_ONE to "2+1",
        EventType.DISCOUNT     to "할인"
    )
    SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
        events.forEachIndexed { idx, (type, label) ->
            SegmentedButton(
                selected = selected == type,
                onClick  = { onSelect(type) },
                shape    = SegmentedButtonDefaults.itemShape(index = idx, count = events.size)
            ) {
                Text(label)
            }
        }
    }
}

@Composable
private fun DateRangeInput(
    startMonth: String, startDay: String,
    endMonth: String,   endDay: String,
    onStartMonthChange: (String) -> Unit,
    onStartDayChange:   (String) -> Unit,
    onEndMonthChange:   (String) -> Unit,
    onEndDayChange:     (String) -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        OutlinedTextField(
            value = startMonth, onValueChange = onStartMonthChange,
            label = { Text("월") }, placeholder = { Text("MM") },
            modifier = Modifier.weight(1f), singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)
        )
        Text("/")
        OutlinedTextField(
            value = startDay, onValueChange = onStartDayChange,
            label = { Text("일") }, placeholder = { Text("DD") },
            modifier = Modifier.weight(1f), singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)
        )
        Text(" ~ ", style = MaterialTheme.typography.bodyLarge)
        OutlinedTextField(
            value = endMonth, onValueChange = onEndMonthChange,
            label = { Text("월") }, placeholder = { Text("MM") },
            modifier = Modifier.weight(1f), singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)
        )
        Text("/")
        OutlinedTextField(
            value = endDay, onValueChange = onEndDayChange,
            label = { Text("일") }, placeholder = { Text("DD") },
            modifier = Modifier.weight(1f), singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)
        )
    }
}

@Composable
private fun InfoRow(label: String, value: String) {
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.bodyMedium)
        Text(value, fontWeight = FontWeight.Medium, style = MaterialTheme.typography.bodyMedium)
    }
}

// ── 유틸 ──────────────────────────────────────────────────────────

private fun buildDate(month: String, day: String): LocalDate? {
    val m = month.toIntOrNull() ?: return null
    val d = day.toIntOrNull()   ?: return null
    if (m !in 1..12 || d !in 1..31) return null
    return runCatching { LocalDate.of(2000, m, d) }.getOrNull()
}

private fun formatDate(date: LocalDate?): String =
    date?.let { "%02d/%02d".format(it.monthValue, it.dayOfMonth) } ?: "--"

private fun eventLabel(event: EventType) = when (event) {
    EventType.NONE         -> "없음"
    EventType.ONE_PLUS_ONE -> "1+1"
    EventType.TWO_PLUS_ONE -> "2+1"
    EventType.DISCOUNT     -> "할인"
}
