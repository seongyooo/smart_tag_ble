package com.example.smarttag.ui.screen

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
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
        snackbarHost = { SnackbarHost(snackbarHostState) },
        floatingActionButton = {
            val isRunning = queueState is BroadcastQueueState.Running
            ExtendedFloatingActionButton(
                onClick = {
                    if (isRunning) viewModel.stopBroadcast()
                    else viewModel.startBroadcast()
                },
                icon = {
                    Icon(
                        if (isRunning) Icons.Default.Stop else Icons.Default.Campaign,
                        contentDescription = null
                    )
                },
                text = { Text(if (isRunning) "중지" else "브로드캐스트") },
                containerColor = if (isRunning) MaterialTheme.colorScheme.errorContainer
                                 else MaterialTheme.colorScheme.primaryContainer,
                contentColor   = if (isRunning) MaterialTheme.colorScheme.onErrorContainer
                                 else MaterialTheme.colorScheme.onPrimaryContainer
            )
        }
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
    isScanning: Boolean,
    onPriceUpdateClick: (Int?) -> Unit
) {
    val tagsByGroup = tags.groupBy { it.groupId }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 88.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        if (categories.isNotEmpty()) {
            item {
                Row(verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("카테고리", style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                    if (isScanning) {
                        CircularProgressIndicator(modifier = Modifier.size(12.dp), strokeWidth = 2.dp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    if (currentGroupId != null) {
                        val name = categories.firstOrNull { it.groupId == currentGroupId }?.name
                        if (name != null) {
                            Spacer(Modifier.weight(1f))
                            Row(verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                Icon(Icons.Default.LocationOn, contentDescription = null,
                                    modifier = Modifier.size(14.dp),
                                    tint = MaterialTheme.colorScheme.primary)
                                Text(name,
                                    style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.primary)
                            }
                        }
                    }
                }
            }
            items(categories, key = { it.groupId }) { cat ->
                val groupTags = tagsByGroup[cat.groupId] ?: emptyList()
                val pending = groupTags.count { it.status == TagStatus.PENDING }
                val isCurrentZone = cat.groupId == currentGroupId
                CategoryCard(
                    category      = cat,
                    tagCount      = groupTags.size,
                    pendingCount  = pending,
                    isCurrentZone = isCurrentZone,
                    onClick       = { onPriceUpdateClick(cat.groupId) }
                )
            }
        } else if (!isScanning) {
            item { EmptyZoneHint() }
        }
    }
}

@Composable
private fun CategoryCard(
    category: CategoryEntity,
    tagCount: Int,
    pendingCount: Int,
    isCurrentZone: Boolean,
    onClick: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (isCurrentZone)
                MaterialTheme.colorScheme.primaryContainer
            else
                MaterialTheme.colorScheme.surface
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = if (isCurrentZone) 3.dp else 1.dp)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Icon(
                Icons.Default.LocationOn,
                contentDescription = null,
                tint = if (isCurrentZone) MaterialTheme.colorScheme.primary
                       else MaterialTheme.colorScheme.outline,
                modifier = Modifier.size(20.dp)
            )
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    category.name,
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = if (isCurrentZone) FontWeight.Bold else FontWeight.Normal,
                    color = if (isCurrentZone) MaterialTheme.colorScheme.onPrimaryContainer
                            else MaterialTheme.colorScheme.onSurface
                )
                if (tagCount > 0) {
                    Text(
                        "태그 ${tagCount}개  •  대기 ${pendingCount}개",
                        style = MaterialTheme.typography.bodySmall,
                        color = if (isCurrentZone) MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.7f)
                                else MaterialTheme.colorScheme.onSurfaceVariant
                    )
                } else {
                    Text(
                        "등록된 태그 없음",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.outline
                    )
                }
            }
            Icon(
                Icons.AutoMirrored.Filled.ArrowForward,
                contentDescription = null,
                tint = if (isCurrentZone) MaterialTheme.colorScheme.primary
                       else MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(18.dp)
            )
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
            Text("${tags.size}개 태그",
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
    val offline = rssi <= -100
    val color = when {
        offline     -> Color(0xFF9E9E9E)
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
        if (offline) {
            Text("—", fontSize = 14.sp, fontWeight = FontWeight.Bold, color = color)
        } else {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(rssi.toString(), fontSize = 10.sp, fontWeight = FontWeight.Bold, color = color)
                Text("dBm", fontSize = 7.sp, color = color)
            }
        }
    }
}
