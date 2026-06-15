package com.example.smarttag.ui.screen

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
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
    val tags       by viewModel.mergedTags.collectAsState()
    val categories by viewModel.categories.collectAsState()
    val tag = tags.firstOrNull { it.deviceAddress == address }

    // ── 카테고리 드롭다운 상태 ──────────────────────────────────────
    var categoryExpanded by remember { mutableStateOf(false) }
    var selectedGroupId by remember { mutableStateOf(0) }

    // ── GATT 가격 입력 상태 ─────────────────────────────────────────
    var priceInput by remember { mutableStateOf("") }
    var showGattDialog by remember { mutableStateOf(false) }

    // ── 브로드캐스트 목표 설정 상태 ─────────────────────────────────
    var targetPriceInput  by remember { mutableStateOf("") }
    var targetEvent       by remember { mutableStateOf(EventType.NONE) }
    var targetStartMonth  by remember { mutableStateOf("") }
    var targetStartDay    by remember { mutableStateOf("") }
    var targetEndMonth    by remember { mutableStateOf("") }
    var targetEndDay      by remember { mutableStateOf("") }
    var targetSaved       by remember { mutableStateOf(false) }

    // ── 초기 설정 (TagID) 상태 ─────────────────────────────────────
    var tagIdInput by remember { mutableStateOf("") }
    var showConfigDialog by remember { mutableStateOf(false) }

    // 최초 1회만 기존 값으로 초기화
    LaunchedEffect(address) {
        tag?.let {
            selectedGroupId = it.groupId
            priceInput = if (it.targetPrice > 0) it.targetPrice.toString() else ""
            targetPriceInput = if (it.targetPrice > 0) it.targetPrice.toString() else ""
            targetEvent = it.targetEvent
            targetStartMonth = it.targetStartDate?.monthValue?.toString() ?: ""
            targetStartDay   = it.targetStartDate?.dayOfMonth?.toString() ?: ""
            targetEndMonth   = it.targetEndDate?.monthValue?.toString() ?: ""
            targetEndDay     = it.targetEndDate?.dayOfMonth?.toString() ?: ""
        }
    }

    val snackbar by viewModel.snackbarMessage.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }
    LaunchedEffect(snackbar) {
        snackbar?.let { snackbarHostState.showSnackbar(it); viewModel.clearSnackbar() }
    }

    val selectedCategoryName = categories.firstOrNull { it.groupId == selectedGroupId }?.name

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(tag?.productName?.ifBlank { "TAG-%03d".format(tag.tagId) } ?: address) },
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

            // ── 태그 기본 정보 (읽기 전용) ───────────────────────────
            TagInfoCard(tag = tag, categoryName = selectedCategoryName)

            // ── 초기 설정 (TagID) ────────────────────────────────────
            TagConfigCard(
                currentTagId = tag.tagId,
                tagIdInput = tagIdInput,
                onTagIdChange = { tagIdInput = it.filter { c -> c.isDigit() }.take(3) },
                onSendClick = { showConfigDialog = true }
            )

            // ── 상품명 설정 ──────────────────────────────────────────
            Card(shape = RoundedCornerShape(12.dp)) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("상품명", style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold)
                        Spacer(Modifier.height(4.dp))
                        Text(
                            tag.productName.ifBlank { "미설정" },
                            style = MaterialTheme.typography.bodyMedium,
                            color = if (tag.productName.isBlank())
                                MaterialTheme.colorScheme.outline
                            else
                                MaterialTheme.colorScheme.onSurface
                        )
                        if (tag.productName.isBlank()) {
                            Text(
                                "상품명을 설정해야 가격표에 표시됩니다.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.error
                            )
                        }
                    }
                    Spacer(Modifier.width(8.dp))
                    Button(
                        onClick = { onNameUpdateClick(address) },
                        colors = if (tag.productName.isBlank())
                            ButtonDefaults.buttonColors()
                        else
                            ButtonDefaults.outlinedButtonColors()
                    ) {
                        Icon(Icons.Default.Edit, contentDescription = null,
                            modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(4.dp))
                        Text(if (tag.productName.isBlank()) "설정" else "수정")
                    }
                }
            }

            // ── 카테고리 (매대) 설정 ─────────────────────────────────
            Card(shape = RoundedCornerShape(12.dp)) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Text("카테고리 (매대)", style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold)
                    Text(
                        "같은 매대의 태그는 동일한 카테고리를 선택하세요.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )

                    if (categories.isEmpty()) {
                        Text("카테고리가 없습니다. 설정에서 추가하세요.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error)
                    } else {
                        ExposedDropdownMenuBox(
                            expanded = categoryExpanded,
                            onExpandedChange = { categoryExpanded = it }
                        ) {
                            OutlinedTextField(
                                value = if (selectedGroupId > 0)
                                    "${selectedCategoryName ?: "Group $selectedGroupId"} (Group $selectedGroupId)"
                                else "카테고리를 선택하세요",
                                onValueChange = {},
                                readOnly = true,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .menuAnchor(MenuAnchorType.PrimaryNotEditable),
                                trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = categoryExpanded) },
                                colors = ExposedDropdownMenuDefaults.outlinedTextFieldColors(),
                                isError = selectedGroupId == 0
                            )
                            ExposedDropdownMenu(
                                expanded = categoryExpanded,
                                onDismissRequest = { categoryExpanded = false }
                            ) {
                                categories.forEach { cat ->
                                    DropdownMenuItem(
                                        text = {
                                            Row(
                                                verticalAlignment = Alignment.CenterVertically,
                                                horizontalArrangement = Arrangement.SpaceBetween,
                                                modifier = Modifier.fillMaxWidth()
                                            ) {
                                                Text(cat.name)
                                                Text(
                                                    "Group ${cat.groupId}",
                                                    style = MaterialTheme.typography.bodySmall,
                                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                                )
                                            }
                                        },
                                        onClick = {
                                            selectedGroupId = cat.groupId
                                            viewModel.saveGroupId(address, cat.groupId)
                                            categoryExpanded = false
                                        },
                                        leadingIcon = if (selectedGroupId == cat.groupId) {{
                                            Icon(Icons.Default.Check, contentDescription = null,
                                                modifier = Modifier.size(16.dp))
                                        }} else null
                                    )
                                }
                            }
                        }
                    }
                }
            }

            // ── 브로드캐스트 목표 설정 (개별 가격/이벤트/날짜) ──────────
            Card(shape = RoundedCornerShape(12.dp)) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Icon(Icons.Default.Campaign,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(18.dp))
                        Text("브로드캐스트 목표 설정",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold)
                    }
                    Text(
                        "이 태그에만 적용할 가격·이벤트를 설정합니다.\n저장 후 스캔 중 근처에 오면 자동으로 업데이트됩니다.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )

                    OutlinedTextField(
                        value = targetPriceInput,
                        onValueChange = { targetPriceInput = it.filter { c -> c.isDigit() }; targetSaved = false },
                        label = { Text("목표 가격 (원)") },
                        modifier = Modifier.fillMaxWidth(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        singleLine = true,
                        suffix = { Text("원") }
                    )

                    Text("이벤트", style = MaterialTheme.typography.labelLarge)
                    val eventOptions = listOf(
                        EventType.NONE to "없음",
                        EventType.ONE_PLUS_ONE to "1+1",
                        EventType.TWO_PLUS_ONE to "2+1",
                        EventType.DISCOUNT to "할인"
                    )
                    SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                        eventOptions.forEachIndexed { idx, (type, label) ->
                            SegmentedButton(
                                selected = targetEvent == type,
                                onClick = { targetEvent = type; targetSaved = false },
                                shape = SegmentedButtonDefaults.itemShape(index = idx, count = eventOptions.size)
                            ) { Text(label) }
                        }
                    }

                    if (targetEvent != EventType.NONE) {
                        Text("이벤트 기간", style = MaterialTheme.typography.labelLarge)
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(4.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            OutlinedTextField(
                                value = targetStartMonth,
                                onValueChange = { if (it.length <= 2) { targetStartMonth = it.filter(Char::isDigit); targetSaved = false } },
                                label = { Text("월") }, placeholder = { Text("MM") },
                                modifier = Modifier.weight(1f), singleLine = true,
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)
                            )
                            Text("/")
                            OutlinedTextField(
                                value = targetStartDay,
                                onValueChange = { if (it.length <= 2) { targetStartDay = it.filter(Char::isDigit); targetSaved = false } },
                                label = { Text("일") }, placeholder = { Text("DD") },
                                modifier = Modifier.weight(1f), singleLine = true,
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)
                            )
                            Text(" ~ ")
                            OutlinedTextField(
                                value = targetEndMonth,
                                onValueChange = { if (it.length <= 2) { targetEndMonth = it.filter(Char::isDigit); targetSaved = false } },
                                label = { Text("월") }, placeholder = { Text("MM") },
                                modifier = Modifier.weight(1f), singleLine = true,
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)
                            )
                            Text("/")
                            OutlinedTextField(
                                value = targetEndDay,
                                onValueChange = { if (it.length <= 2) { targetEndDay = it.filter(Char::isDigit); targetSaved = false } },
                                label = { Text("일") }, placeholder = { Text("DD") },
                                modifier = Modifier.weight(1f), singleLine = true,
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)
                            )
                        }
                    }

                    val targetPrice = targetPriceInput.toIntOrNull() ?: 0
                    Button(
                        onClick = {
                            val startDate = buildDate(targetStartMonth, targetStartDay)
                            val endDate   = buildDate(targetEndMonth, targetEndDay)
                            viewModel.setTargetState(address, targetPrice, targetEvent, startDate, endDate)
                            targetSaved = true
                        },
                        enabled = targetPrice > 0,
                        modifier = Modifier.fillMaxWidth(),
                        colors = if (targetSaved) ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.secondary
                        ) else ButtonDefaults.buttonColors()
                    ) {
                        Icon(
                            if (targetSaved) Icons.Default.Check else Icons.Default.Save,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(if (targetSaved) "저장됨 (PENDING)" else "목표 저장 (브로드캐스트 대기)")
                    }
                }
            }

            // ── GATT 개별 가격 업데이트 ──────────────────────────────
            Card(shape = RoundedCornerShape(12.dp)) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Icon(Icons.Default.Bluetooth,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(18.dp))
                        Text("GATT 개별 가격 업데이트",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold)
                    }
                    Text(
                        "이 태그에만 직접 연결해 가격을 전송합니다.\n이벤트/날짜는 가격 업데이트 화면에서 설정하세요.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    OutlinedTextField(
                        value = priceInput,
                        onValueChange = { priceInput = it.filter { c -> c.isDigit() } },
                        label = { Text("가격 (원)") },
                        modifier = Modifier.fillMaxWidth(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        singleLine = true,
                        suffix = { Text("원") }
                    )
                    Button(
                        onClick = { showGattDialog = true },
                        enabled = priceInput.isNotBlank() && (priceInput.toIntOrNull() ?: 0) > 0,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(Icons.Default.Send, contentDescription = null,
                            modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text("이 태그에 가격 전송")
                    }
                }
            }

            // ── 현재 상태 요약 ───────────────────────────────────────
            if (tag.currentPrice > 0 || tag.status != TagStatus.PENDING) {
                SyncStatusCard(tag = tag)
            }
        }
    }

    // ── 초기 설정 전송 다이얼로그 ────────────────────────────────────
    if (showConfigDialog && tag != null) {
        val newTagId = tagIdInput.toIntOrNull() ?: 0
        val isChanging = tag.tagId > 0  // 이미 설정된 태그를 변경하는 경우
        AlertDialog(
            onDismissRequest = { showConfigDialog = false },
            title = { Text(if (isChanging) "Tag ID 변경" else "Tag ID 설정") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (isChanging) {
                        Text("현재 Tag ID: TAG-%03d".format(tag.tagId))
                    }
                    OutlinedTextField(
                        value = tagIdInput,
                        onValueChange = { tagIdInput = it.filter { c -> c.isDigit() }.take(3) },
                        label = { Text("새 Tag ID (1~255)") },
                        modifier = Modifier.fillMaxWidth(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        singleLine = true,
                        isError = tagIdInput.isNotEmpty() && newTagId !in 1..255
                    )
                    Text(
                        "※ ESP32에 GATT로 전송됩니다.\n전송 후 태그가 새 ID로 재광고합니다.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        viewModel.writeConfig(address, newTagId)
                        showConfigDialog = false
                    },
                    enabled = newTagId in 1..255
                ) { Text("전송") }
            },
            dismissButton = {
                TextButton(onClick = { showConfigDialog = false }) { Text("취소") }
            }
        )
    }

    // ── GATT 전송 확인 다이얼로그 ────────────────────────────────────
    if (showGattDialog && tag != null) {
        val price = priceInput.toIntOrNull() ?: 0
        AlertDialog(
            onDismissRequest = { showGattDialog = false },
            title = { Text("GATT 전송 확인") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("${tag.productName.ifBlank { "TAG-%03d".format(tag.tagId) }}에 직접 연결")
                    Text("가격: ${"%,d원".format(price)}")
                    Text(
                        "※ GATT 전송은 가격만 업데이트됩니다.\n이벤트/날짜는 '가격 업데이트' 화면을 이용하세요.",
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
}

// ── 서브 컴포저블 ──────────────────────────────────────────────────

@Composable
private fun TagConfigCard(
    currentTagId: Int,
    tagIdInput: String,
    onTagIdChange: (String) -> Unit,
    onSendClick: () -> Unit
) {
    val newTagId = tagIdInput.toIntOrNull() ?: 0
    val isValid  = newTagId in 1..255
    // DB에 저장된 tagId가 있으면 설정 완료 상태 (화면 재진입 시에도 유지)
    val isDone   = currentTagId > 0

    Card(
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(
            containerColor = when {
                isDone -> MaterialTheme.colorScheme.secondaryContainer
                currentTagId == 0 -> MaterialTheme.colorScheme.errorContainer
                else -> MaterialTheme.colorScheme.surfaceVariant
            }
        )
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Icon(
                    if (isDone) Icons.Default.CheckCircle else Icons.Default.Settings,
                    contentDescription = null,
                    tint = when {
                        isDone -> MaterialTheme.colorScheme.secondary
                        currentTagId == 0 -> MaterialTheme.colorScheme.error
                        else -> MaterialTheme.colorScheme.onSurfaceVariant
                    },
                    modifier = Modifier.size(18.dp)
                )
                Text(
                    "초기 설정 (Tag ID)",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold
                )
            }

            if (isDone) {
                // 설정 완료: Tag ID 크게 표시 + 변경 버튼
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text(
                            "설정된 Tag ID",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.7f)
                        )
                        Text(
                            "TAG-%03d".format(currentTagId),
                            style = MaterialTheme.typography.headlineSmall,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSecondaryContainer
                        )
                    }
                    OutlinedButton(
                        onClick = onSendClick,
                        colors = ButtonDefaults.outlinedButtonColors(
                            contentColor = MaterialTheme.colorScheme.onSecondaryContainer
                        )
                    ) {
                        Icon(Icons.Default.Edit, contentDescription = null,
                            modifier = Modifier.size(14.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("변경", style = MaterialTheme.typography.labelMedium)
                    }
                }
                Text(
                    "이 값이 ESP32의 MY_TAG_ID와 일치해야 합니다.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.7f)
                )
            } else {
                Text(
                    "Tag ID가 미설정입니다. 아래에서 설정 후 전송하세요.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error
                )
                OutlinedTextField(
                    value = tagIdInput,
                    onValueChange = onTagIdChange,
                    label = { Text("Tag ID (1~255)") },
                    placeholder = { Text("1~255") },
                    modifier = Modifier.fillMaxWidth(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    singleLine = true,
                    isError = tagIdInput.isNotEmpty() && newTagId !in 1..255
                )
                Button(
                    onClick = onSendClick,
                    enabled = isValid,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(Icons.Default.SettingsRemote, contentDescription = null,
                        modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("ESP32에 Tag ID 전송")
                }
            }
        }
    }
}

@Composable
private fun TagInfoCard(tag: SmartTag, categoryName: String?) {
    Card(shape = RoundedCornerShape(12.dp)) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Text("태그 정보", style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold)
            HorizontalDivider()
            InfoRow("Tag ID",   "TAG-%03d".format(tag.tagId))
            InfoRow("MAC",      tag.deviceAddress)
            InfoRow("RSSI",     "${tag.rssi} dBm")
            InfoRow("카테고리", categoryName ?: if (tag.groupId > 0) "Group ${tag.groupId}" else "미설정")
            InfoRow("현재 가격", if (tag.currentPrice > 0) "%,d원".format(tag.currentPrice) else "수신 대기")
            InfoRow("LastSeq",  tag.stateCrc.toString())
        }
    }
}

@Composable
private fun SyncStatusCard(tag: SmartTag) {
    val (label, containerColor) = when (tag.status) {
        TagStatus.UPDATED -> "동기화 완료" to MaterialTheme.colorScheme.secondaryContainer
        TagStatus.PENDING -> "업데이트 대기 중" to MaterialTheme.colorScheme.tertiaryContainer
        TagStatus.FAILED  -> "업데이트 실패" to MaterialTheme.colorScheme.errorContainer
    }
    Card(
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = containerColor)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Icon(
                when (tag.status) {
                    TagStatus.UPDATED -> Icons.Default.CheckCircle
                    TagStatus.PENDING -> Icons.Default.Sync
                    TagStatus.FAILED  -> Icons.Default.Error
                },
                contentDescription = null
            )
            Column {
                Text(label, style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold)
                if (tag.targetPrice > 0) {
                    Text(
                        "목표: ${"%,d원".format(tag.targetPrice)}" +
                        if (tag.targetEvent != EventType.NONE) " / ${eventLabel(tag.targetEvent)}" else "",
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }
        }
    }
}

@Composable
private fun InfoRow(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(label,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.bodyMedium)
        Text(value,
            fontWeight = FontWeight.Medium,
            style = MaterialTheme.typography.bodyMedium)
    }
}

private fun buildDate(month: String, day: String): LocalDate? {
    val m = month.toIntOrNull() ?: return null
    val d = day.toIntOrNull()   ?: return null
    if (m !in 1..12 || d !in 1..31) return null
    return runCatching { LocalDate.of(2000, m, d) }.getOrNull()
}

private fun eventLabel(event: EventType) = when (event) {
    EventType.NONE         -> "없음"
    EventType.ONE_PLUS_ONE -> "1+1"
    EventType.TWO_PLUS_ONE -> "2+1"
    EventType.DISCOUNT     -> "할인"
}
