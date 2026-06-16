package com.example.smarttag.ui.screen

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.smarttag.model.EventType
import com.example.smarttag.model.SmartTag
import com.example.smarttag.model.TagStatus
import com.example.smarttag.viewmodel.BroadcastQueueState
import com.example.smarttag.viewmodel.ScanViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PriceUpdateScreen(
    initialGroupId: Int?,
    viewModel: ScanViewModel,
    onBack: () -> Unit
) {
    val categories  by viewModel.categories.collectAsState()
    val allTags     by viewModel.mergedTags.collectAsState()
    val queueState  by viewModel.broadcastQueueState.collectAsState()
    val snackbar    by viewModel.snackbarMessage.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }

    LaunchedEffect(snackbar) {
        snackbar?.let { snackbarHostState.showSnackbar(it); viewModel.clearSnackbar() }
    }

    // ── 카테고리 필터 (표시 전용 — 브로드캐스트와 무관) ─────────────
    // 0 = 전체 보기
    var filterGroupId by remember { mutableStateOf(initialGroupId ?: 0) }
    var categoryExpanded by remember { mutableStateOf(false) }

    val allPendingTags = allTags.filter { it.status != TagStatus.UPDATED && it.targetPrice > 0 }
    val displayTags = if (filterGroupId > 0)
        allTags.filter { it.groupId == filterGroupId }
    else
        allTags

    val isRunning = queueState is BroadcastQueueState.Running
    val canBroadcast = allPendingTags.isNotEmpty() && !isRunning

    val filterCategoryName = categories.firstOrNull { it.groupId == filterGroupId }?.name

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("가격 브로드캐스트", style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold)
                        Text(
                            if (isRunning) "전송 중..."
                            else "전체 PENDING: ${allPendingTags.size}개",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.7f)
                        )
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "뒤로")
                    }
                },
                actions = {
                    if (isRunning) {
                        IconButton(onClick = { viewModel.stopBroadcast() }) {
                            Icon(Icons.Default.Stop, contentDescription = "중지",
                                tint = MaterialTheme.colorScheme.error)
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer
                )
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) }
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {

            // ── 브로드캐스트 시작 버튼 ────────────────────────────────
            item {
                Button(
                    onClick = { viewModel.startBroadcast() },
                    enabled = canBroadcast,
                    modifier = Modifier.fillMaxWidth().height(52.dp)
                ) {
                    Icon(Icons.Default.Campaign, contentDescription = null,
                        modifier = Modifier.size(20.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(
                        when {
                            isRunning            -> "브로드캐스트 진행 중..."
                            allPendingTags.isEmpty() -> "모든 태그가 최신 상태입니다"
                            else -> "전체 PENDING ${allPendingTags.size}개 브로드캐스트"
                        },
                        style = MaterialTheme.typography.bodyLarge
                    )
                }
            }

            // ── 브로드캐스트 진행 상태 ────────────────────────────────
            if (isRunning) {
                item {
                    val state = queueState as BroadcastQueueState.Running
                    Card(
                        shape = RoundedCornerShape(12.dp),
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.tertiaryContainer
                        )
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(16.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(24.dp), strokeWidth = 2.dp,
                                color = MaterialTheme.colorScheme.onTertiaryContainer
                            )
                            Column {
                                Text("브로드캐스트 전송 중…",
                                    style = MaterialTheme.typography.bodyMedium,
                                    fontWeight = FontWeight.SemiBold,
                                    color = MaterialTheme.colorScheme.onTertiaryContainer)
                                Text("잔여 ${state.pendingCount}개 태그 대기 중",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onTertiaryContainer)
                            }
                        }
                    }
                }
            }

            // ── 안내 문구 ─────────────────────────────────────────────
            item {
                Card(
                    shape = RoundedCornerShape(8.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant
                    )
                ) {
                    Text(
                        "카테고리 무관하게 PENDING 상태인 모든 태그에 순차 전송합니다.\n" +
                        "가격이 설정되지 않은 태그는 건너뜁니다.\n" +
                        "개별 설정은 태그 상세 화면에서 변경하세요.",
                        modifier = Modifier.padding(12.dp),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            // ── 카테고리 필터 (목록 표시 전용) ───────────────────────
            item {
                Card(shape = RoundedCornerShape(12.dp)) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Icon(Icons.Default.FilterList,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(18.dp))
                            Text("목록 필터 (카테고리)",
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.SemiBold)
                            Spacer(Modifier.weight(1f))
                            SuggestionChip(
                                onClick = {},
                                label = { Text("표시 전용", fontSize = 10.sp) }
                            )
                        }

                        ExposedDropdownMenuBox(
                            expanded = categoryExpanded,
                            onExpandedChange = { categoryExpanded = it }
                        ) {
                            OutlinedTextField(
                                value = if (filterGroupId > 0)
                                    "${filterCategoryName ?: "Group $filterGroupId"}"
                                else "전체 태그 보기",
                                onValueChange = {},
                                readOnly = true,
                                modifier = Modifier.fillMaxWidth()
                                    .menuAnchor(MenuAnchorType.PrimaryNotEditable),
                                trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = categoryExpanded) },
                                colors = ExposedDropdownMenuDefaults.outlinedTextFieldColors()
                            )
                            ExposedDropdownMenu(
                                expanded = categoryExpanded,
                                onDismissRequest = { categoryExpanded = false }
                            ) {
                                // "전체" 옵션
                                DropdownMenuItem(
                                    text = {
                                        Row(
                                            modifier = Modifier.fillMaxWidth(),
                                            verticalAlignment = Alignment.CenterVertically,
                                            horizontalArrangement = Arrangement.SpaceBetween
                                        ) {
                                            Text("전체 태그 보기")
                                            Text(
                                                "${allTags.size}개",
                                                style = MaterialTheme.typography.bodySmall,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant
                                            )
                                        }
                                    },
                                    onClick = { filterGroupId = 0; categoryExpanded = false },
                                    leadingIcon = if (filterGroupId == 0) {{
                                        Icon(Icons.Default.Check, contentDescription = null,
                                            modifier = Modifier.size(16.dp))
                                    }} else null
                                )
                                HorizontalDivider()
                                categories.forEach { cat ->
                                    val count = allTags.count { it.groupId == cat.groupId }
                                    val pending = allTags.count { it.groupId == cat.groupId && it.status != TagStatus.UPDATED }
                                    DropdownMenuItem(
                                        text = {
                                            Row(
                                                modifier = Modifier.fillMaxWidth(),
                                                verticalAlignment = Alignment.CenterVertically,
                                                horizontalArrangement = Arrangement.SpaceBetween
                                            ) {
                                                Text(cat.name)
                                                Text(
                                                    "${count}개" + if (pending > 0) "  (대기 $pending)" else "",
                                                    style = MaterialTheme.typography.bodySmall,
                                                    color = if (pending > 0)
                                                        MaterialTheme.colorScheme.primary
                                                    else
                                                        MaterialTheme.colorScheme.onSurfaceVariant
                                                )
                                            }
                                        },
                                        onClick = {
                                            filterGroupId = cat.groupId
                                            categoryExpanded = false
                                        },
                                        leadingIcon = if (filterGroupId == cat.groupId) {{
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

            // ── 태그 목록 ─────────────────────────────────────────────
            item {
                val label = if (filterGroupId > 0)
                    "${filterCategoryName ?: "Group $filterGroupId"} 태그 (${displayTags.size}개)"
                else
                    "전체 태그 (${displayTags.size}개)"
                Text(label,
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }

            if (displayTags.isNotEmpty()) {
                items(displayTags, key = { it.deviceAddress }) { tag ->
                    TagBroadcastItem(tag, viewModel.getCategoryName(tag.groupId))
                }
            } else {
                item {
                    Card(
                        shape = RoundedCornerShape(12.dp),
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.surfaceVariant
                        )
                    ) {
                        Column(
                            modifier = Modifier.fillMaxWidth().padding(20.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Icon(Icons.Default.SearchOff, contentDescription = null,
                                modifier = Modifier.size(32.dp),
                                tint = MaterialTheme.colorScheme.outline)
                            Text("표시할 태그가 없습니다.",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.outline)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun TagBroadcastItem(tag: SmartTag, categoryName: String) {
    val (statusText, statusColor) = when (tag.status) {
        TagStatus.UPDATED -> "완료" to Color(0xFF4CAF50)
        TagStatus.PENDING -> "대기" to Color(0xFFFF9800)
        TagStatus.FAILED  -> "실패" to Color(0xFFF44336)
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    tag.productName.ifBlank { "TAG-%03d".format(tag.tagId) },
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium
                )
                if (tag.groupId > 0) {
                    Text(
                        categoryName,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier
                            .clip(RoundedCornerShape(4.dp))
                            .background(MaterialTheme.colorScheme.surface)
                            .padding(horizontal = 4.dp, vertical = 1.dp)
                    )
                }
            }
            if (tag.targetPrice > 0) {
                val eventStr = when (tag.targetEvent) {
                    EventType.NONE         -> ""
                    EventType.ONE_PLUS_ONE -> "  1+1"
                    EventType.TWO_PLUS_ONE -> "  2+1"
                    EventType.DISCOUNT     -> "  할인"
                }
                Text(
                    "목표: ${"%,d원".format(tag.targetPrice)}$eventStr",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary
                )
            } else {
                Text(
                    "목표 미설정 — 태그 상세에서 설정하세요",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error
                )
            }
            if (tag.currentPrice > 0) {
                Text(
                    "현재: ${"%,d원".format(tag.currentPrice)}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        Box(
            modifier = Modifier
                .clip(RoundedCornerShape(6.dp))
                .background(statusColor.copy(alpha = 0.15f))
                .padding(horizontal = 8.dp, vertical = 3.dp)
        ) {
            Text(statusText, color = statusColor, fontSize = 12.sp,
                fontWeight = FontWeight.Medium)
        }
    }
}
