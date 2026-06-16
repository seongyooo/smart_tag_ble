package com.example.smarttag.ui.screen

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.smarttag.db.CategoryEntity
import com.example.smarttag.model.SmartTag
import com.example.smarttag.model.TagStatus
import com.example.smarttag.viewmodel.BroadcastQueueState
import com.example.smarttag.viewmodel.ScanViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ScanScreen(
    viewModel: ScanViewModel,
    onTagClick: (String) -> Unit,
    onPriceUpdateClick: (Int?) -> Unit,
    onCategoryManageClick: () -> Unit
) {
    var selectedTab by remember { mutableStateOf(0) }

    val isScanning    by viewModel.isScanning.collectAsState()
    val tags          by viewModel.mergedTags.collectAsState()
    val categories    by viewModel.categories.collectAsState()
    val currentGroupId by viewModel.currentGroupId.collectAsState()
    val queueState    by viewModel.broadcastQueueState.collectAsState()
    val snackbar      by viewModel.snackbarMessage.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }

    LaunchedEffect(snackbar) {
        snackbar?.let { snackbarHostState.showSnackbar(it); viewModel.clearSnackbar() }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("SmartTag", fontWeight = FontWeight.Bold) },
                actions = {
                    IconButton(onClick = { viewModel.toggleScan() }) {
                        Icon(
                            if (isScanning) Icons.Default.Stop
                            else Icons.Default.BluetoothSearching,
                            contentDescription = if (isScanning) "스캔 중지" else "스캔 시작",
                            tint = if (isScanning) MaterialTheme.colorScheme.error
                            else MaterialTheme.colorScheme.onPrimaryContainer
                        )
                    }
                    var menuExpanded by remember { mutableStateOf(false) }
                    Box {
                        IconButton(onClick = { menuExpanded = true }) {
                            Icon(Icons.Default.MoreVert, contentDescription = "메뉴")
                        }
                        DropdownMenu(
                            expanded = menuExpanded,
                            onDismissRequest = { menuExpanded = false }
                        ) {
                            DropdownMenuItem(
                                text = { Text("카테고리 관리") },
                                leadingIcon = { Icon(Icons.Default.Settings, contentDescription = null, modifier = Modifier.size(18.dp)) },
                                onClick = { menuExpanded = false; onCategoryManageClick() }
                            )
                            HorizontalDivider()
                            DropdownMenuItem(
                                text = { Text("테스트 세트 A 적용") },
                                leadingIcon = { Icon(Icons.Default.Science, contentDescription = null, modifier = Modifier.size(18.dp)) },
                                onClick = { menuExpanded = false; viewModel.applyTestDataset("A") }
                            )
                            DropdownMenuItem(
                                text = { Text("테스트 세트 B 적용") },
                                leadingIcon = { Icon(Icons.Default.Science, contentDescription = null, modifier = Modifier.size(18.dp)) },
                                onClick = { menuExpanded = false; viewModel.applyTestDataset("B") }
                            )
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
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            PrimaryTabRow(selectedTabIndex = selectedTab) {
                Tab(selected = selectedTab == 0, onClick = { selectedTab = 0 },
                    text = { Text("운영") },
                    icon = { Icon(Icons.Default.Campaign, contentDescription = null, modifier = Modifier.size(18.dp)) }
                )
                Tab(selected = selectedTab == 1, onClick = { selectedTab = 1 },
                    text = { Text("태그 설정") },
                    icon = { Icon(Icons.Default.Tune, contentDescription = null, modifier = Modifier.size(18.dp)) }
                )
            }

            when (selectedTab) {
                0 -> OperationTab(
                    currentGroupId   = currentGroupId,
                    categories       = categories,
                    tags             = tags,
                    queueState       = queueState,
                    isScanning       = isScanning,
                    onPriceUpdateClick = onPriceUpdateClick
                )
                1 -> TagSetupTab(
                    tags       = tags,
                    categories = categories,
                    isScanning = isScanning,
                    onTagClick = onTagClick
                )
            }
        }
    }
}

// ── 운영 탭 ──────────────────────────────────────────────────────

@Composable
private fun OperationTab(
    currentGroupId: Int?,
    categories: List<CategoryEntity>,
    tags: List<SmartTag>,
    queueState: BroadcastQueueState,
    isScanning: Boolean,
    onPriceUpdateClick: (Int?) -> Unit
) {
    val zoneTags = if (currentGroupId != null) tags.filter { it.groupId == currentGroupId } else emptyList()
    val pendingCount = zoneTags.count { it.status == TagStatus.PENDING }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        // 현재 구역 카드
        item {
            ZoneCard(
                currentGroupId = currentGroupId,
                categories     = categories,
                pendingCount   = pendingCount,
                isScanning     = isScanning,
                onUpdateClick  = { onPriceUpdateClick(currentGroupId) }
            )
        }

        // 카테고리 빠른 선택
        if (categories.isNotEmpty()) {
            item {
                Text("카테고리", style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            item {
                CategoryChipRow(
                    categories     = categories,
                    selectedGroupId = currentGroupId,
                    tagsByGroup    = tags.groupBy { it.groupId },
                    onSelect       = { onPriceUpdateClick(it) }
                )
            }
        }

        // 브로드캐스트 큐 진행
        if (queueState is BroadcastQueueState.Running) {
            item {
                QueueProgressCard(queueState as BroadcastQueueState.Running)
            }
        }

        // 현재 구역 태그 상태
        if (zoneTags.isNotEmpty()) {
            item {
                Text(
                    "현재 구역 태그 (${zoneTags.size}개)",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            items(zoneTags, key = { it.deviceAddress }) { tag ->
                ZoneTagItem(tag)
            }
        } else if (currentGroupId == null && !isScanning) {
            item {
                EmptyZoneHint()
            }
        }
    }
}

@Composable
private fun ZoneCard(
    currentGroupId: Int?,
    categories: List<CategoryEntity>,
    pendingCount: Int,
    isScanning: Boolean,
    onUpdateClick: () -> Unit
) {
    val categoryName = categories.firstOrNull { it.groupId == currentGroupId }?.name

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (currentGroupId != null)
                MaterialTheme.colorScheme.primaryContainer
            else
                MaterialTheme.colorScheme.surfaceVariant
        )
    ) {
        Column(modifier = Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Icon(Icons.Default.LocationOn,
                    contentDescription = null,
                    tint = if (currentGroupId != null)
                        MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.outline
                )
                Text(
                    "현재 구역",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                if (isScanning) {
                    Spacer(Modifier.weight(1f))
                    CircularProgressIndicator(modifier = Modifier.size(14.dp), strokeWidth = 2.dp)
                }
            }

            if (currentGroupId != null && categoryName != null) {
                Text(
                    categoryName,
                    style = MaterialTheme.typography.headlineMedium,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    "Group $currentGroupId  •  대기 ${pendingCount}개",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Button(
                    onClick = onUpdateClick,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(Icons.Default.Campaign, contentDescription = null,
                        modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("가격 / 이벤트 업데이트")
                }
            } else {
                Text(
                    "감지 대기 중",
                    style = MaterialTheme.typography.headlineSmall,
                    color = MaterialTheme.colorScheme.outline
                )
                Text(
                    if (isScanning) "가격 태그 RSSI를 분석하고 있습니다…"
                    else "스캔을 시작하면 구역이 자동으로 감지됩니다.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
private fun CategoryChipRow(
    categories: List<CategoryEntity>,
    selectedGroupId: Int?,
    tagsByGroup: Map<Int, List<SmartTag>>,
    onSelect: (Int) -> Unit
) {
    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        items(categories) { cat ->
            val count = tagsByGroup[cat.groupId]?.size ?: 0
            FilterChip(
                selected = cat.groupId == selectedGroupId,
                onClick  = { onSelect(cat.groupId) },
                label    = { Text("${cat.name}  $count") },
                leadingIcon = if (cat.groupId == selectedGroupId) {{
                    Icon(Icons.Default.Check, contentDescription = null,
                        modifier = Modifier.size(16.dp))
                }} else null
            )
        }
    }
}

@Composable
private fun QueueProgressCard(state: BroadcastQueueState.Running) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.tertiaryContainer),
        shape = RoundedCornerShape(12.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp,
                color = MaterialTheme.colorScheme.onTertiaryContainer)
            Column {
                Text("전체 태그 업데이트 진행 중",
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onTertiaryContainer)
                Text("잔여 ${state.pendingCount}개 태그",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onTertiaryContainer)
            }
        }
    }
}

@Composable
private fun ZoneTagItem(tag: SmartTag) {
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
        Column(modifier = Modifier.weight(1f)) {
            Text(tag.productName.ifBlank { "TAG-%03d".format(tag.tagId) },
                style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
            if (tag.currentPrice > 0) {
                Text("%,d원".format(tag.currentPrice),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        Box(
            modifier = Modifier
                .clip(RoundedCornerShape(6.dp))
                .background(statusColor.copy(alpha = 0.15f))
                .padding(horizontal = 8.dp, vertical = 3.dp)
        ) {
            Text(statusText, color = statusColor, fontSize = 12.sp, fontWeight = FontWeight.Medium)
        }
    }
}

@Composable
private fun EmptyZoneHint() {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
        shape = RoundedCornerShape(12.dp)
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(20.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Icon(Icons.Default.BluetoothSearching, contentDescription = null,
                modifier = Modifier.size(40.dp), tint = MaterialTheme.colorScheme.outline)
            Text("위의 스캔 버튼을 눌러 시작하세요",
                color = MaterialTheme.colorScheme.outline,
                style = MaterialTheme.typography.bodyMedium)
            Text("가격 태그 RSSI 기반으로 구역이 자동 전환됩니다.",
                color = MaterialTheme.colorScheme.outline,
                style = MaterialTheme.typography.bodySmall)
        }
    }
}

// ── 태그 설정 탭 ─────────────────────────────────────────────────

@Composable
private fun TagSetupTab(
    tags: List<SmartTag>,
    categories: List<CategoryEntity>,
    isScanning: Boolean,
    onTagClick: (String) -> Unit
) {
    if (tags.isEmpty()) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Icon(Icons.Default.BluetoothSearching, null,
                    modifier = Modifier.size(56.dp), tint = MaterialTheme.colorScheme.outline)
                Text(
                    if (isScanning) "태그를 검색 중입니다…" else "스캔을 시작해 태그를 찾으세요.",
                    color = MaterialTheme.colorScheme.outline
                )
            }
        }
        return
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        item {
            Text("${tags.size}개 태그 발견",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        items(tags, key = { it.deviceAddress }) { tag ->
            val categoryName = categories.firstOrNull { it.groupId == tag.groupId }?.name
            TagSetupItem(tag = tag, categoryName = categoryName, onClick = { onTagClick(tag.deviceAddress) })
        }
    }
}

@Composable
private fun TagSetupItem(tag: SmartTag, categoryName: String?, onClick: () -> Unit) {
    val needsSetup = tag.productName.isBlank() || tag.groupId == 0

    Card(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (needsSetup)
                MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.3f)
            else MaterialTheme.colorScheme.surface
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
    ) {
        Row(
            modifier = Modifier.padding(14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // RSSI 인디케이터
            RssiDot(rssi = tag.rssi)

            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(
                        tag.productName.ifBlank { "TAG-%03d".format(tag.tagId) },
                        style = MaterialTheme.typography.bodyLarge,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    if (needsSetup) {
                        Text("설정 필요",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.error)
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (tag.tagId > 0) {
                        Text(
                            "Tag ID: %03d".format(tag.tagId),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.primary,
                            fontWeight = androidx.compose.ui.text.font.FontWeight.Medium
                        )
                    } else {
                        Text(
                            "Tag ID 미설정",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error
                        )
                    }
                    if (categoryName != null) {
                        Text("• $categoryName",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    } else if (tag.groupId > 0) {
                        Text("• Group ${tag.groupId}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }

            Icon(Icons.AutoMirrored.Filled.ArrowForward, null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(18.dp))
        }
    }
}

@Composable
private fun RssiDot(rssi: Int) {
    val color = when {
        rssi >= -60 -> Color(0xFF4CAF50)
        rssi >= -75 -> Color(0xFFFF9800)
        else        -> Color(0xFFF44336)
    }
    Box(
        modifier = Modifier
            .size(40.dp)
            .clip(CircleShape)
            .background(color.copy(alpha = 0.12f)),
        contentAlignment = Alignment.Center
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(rssi.toString(), fontSize = 10.sp, fontWeight = FontWeight.Bold, color = color)
            Text("dBm", fontSize = 7.sp, color = color)
        }
    }
}
