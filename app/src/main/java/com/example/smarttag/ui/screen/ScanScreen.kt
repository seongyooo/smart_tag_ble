package com.example.smarttag.ui.screen

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BluetoothSearching
import androidx.compose.material.icons.filled.Campaign
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.smarttag.model.SmartTag
import com.example.smarttag.model.TagStatus
import com.example.smarttag.viewmodel.BroadcastQueueState
import com.example.smarttag.viewmodel.ScanViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ScanScreen(
    viewModel: ScanViewModel,
    onTagClick: (String) -> Unit,
    onBroadcastClick: () -> Unit = {}
) {
    val isScanning by viewModel.isScanning.collectAsState()
    val tags by viewModel.mergedTags.collectAsState()
    val snackbar by viewModel.snackbarMessage.collectAsState()
    val currentGroupId by viewModel.currentGroupId.collectAsState()
    val queueState by viewModel.broadcastQueueState.collectAsState()

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
                title = { Text("SmartTag BLE", fontWeight = FontWeight.Bold) },
                actions = {
                    IconButton(onClick = onBroadcastClick) {
                        Icon(Icons.Default.Campaign, contentDescription = "그룹 브로드캐스트")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer
                )
            )
        },
        floatingActionButton = {
            FloatingActionButton(
                onClick = { viewModel.toggleScan() },
                containerColor = if (isScanning) MaterialTheme.colorScheme.error
                else MaterialTheme.colorScheme.primary
            ) {
                Icon(
                    imageVector = if (isScanning) Icons.Default.Stop else Icons.Default.BluetoothSearching,
                    contentDescription = if (isScanning) "스캔 중지" else "스캔 시작"
                )
            }
        },
        snackbarHost = { SnackbarHost(snackbarHostState) }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            // 상태 바
            ScanStatusBar(isScanning = isScanning, tagCount = tags.size)

            // 앵커 감지 배너
            currentGroupId?.let { gid ->
                AnchorBanner(groupId = gid)
            }

            // 브로드캐스트 큐 진행 배너
            if (queueState is BroadcastQueueState.Running) {
                val running = queueState as BroadcastQueueState.Running
                QueueProgressBanner(groupId = running.groupId, pendingCount = running.pendingCount)
            }

            if (tags.isEmpty()) {
                EmptyState(isScanning = isScanning)
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(tags, key = { it.deviceAddress }) { tag ->
                        TagItem(
                            tag = tag,
                            onClick = { onTagClick(tag.deviceAddress) }
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ScanStatusBar(isScanning: Boolean, tagCount: Int) {
    val bgColor by animateColorAsState(
        targetValue = if (isScanning) MaterialTheme.colorScheme.primaryContainer
        else MaterialTheme.colorScheme.surfaceVariant,
        label = "scanBarColor"
    )
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(bgColor)
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (isScanning) {
                CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
            }
            Text(
                text = if (isScanning) "스캔 중..." else "스캔 대기",
                style = MaterialTheme.typography.labelLarge
            )
        }
        Text(
            text = "발견된 태그: ${tagCount}개",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun AnchorBanner(groupId: Int) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.secondaryContainer)
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Icon(
            Icons.Default.LocationOn,
            contentDescription = null,
            modifier = Modifier.size(16.dp),
            tint = MaterialTheme.colorScheme.onSecondaryContainer
        )
        Text(
            "현재 구역: Group $groupId",
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSecondaryContainer
        )
    }
}

@Composable
private fun QueueProgressBanner(groupId: Int, pendingCount: Int) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.tertiaryContainer)
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        CircularProgressIndicator(
            modifier = Modifier.size(14.dp),
            strokeWidth = 2.dp,
            color = MaterialTheme.colorScheme.onTertiaryContainer
        )
        Text(
            "Group $groupId 업데이트 중… 잔여 ${pendingCount}개",
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onTertiaryContainer
        )
    }
}

@Composable
private fun EmptyState(isScanning: Boolean) {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Icon(
                imageVector = Icons.Default.BluetoothSearching,
                contentDescription = null,
                modifier = Modifier.size(64.dp),
                tint = MaterialTheme.colorScheme.outline
            )
            Text(
                text = if (isScanning) "SmartTag 검색 중..." else "우측 하단 버튼으로 스캔을 시작하세요",
                color = MaterialTheme.colorScheme.outline
            )
        }
    }
}

@Composable
private fun TagItem(tag: SmartTag, onClick: () -> Unit) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(12.dp),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // RSSI 인디케이터
            RssiIndicator(rssi = tag.rssi)

            // 태그 정보
            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(
                        text = tag.productName.ifBlank { tag.deviceName },
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold
                    )
                    if (tag.groupId > 0) {
                        Text(
                            text = "G${tag.groupId}",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = androidx.compose.ui.Modifier
                                .clip(RoundedCornerShape(4.dp))
                                .background(MaterialTheme.colorScheme.primaryContainer)
                                .padding(horizontal = 4.dp, vertical = 1.dp)
                        )
                    }
                }
                Text(
                    text = "TAG-${"%03d".format(tag.tagId)}  •  ${tag.deviceAddress}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                if (tag.currentPrice > 0 || tag.targetPrice > 0) {
                    Spacer(Modifier.height(4.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (tag.currentPrice > 0) {
                            Text(
                                text = "현재 ${"%,d".format(tag.currentPrice)}원",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        if (tag.targetPrice > 0 && tag.targetPrice != tag.currentPrice) {
                            Text(
                                text = "→ ${"%,d".format(tag.targetPrice)}원",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.secondary
                            )
                        }
                    }
                }
            }

            // 상태 배지
            StatusBadge(status = tag.status)
        }
    }
}

@Composable
private fun RssiIndicator(rssi: Int) {
    val color = when {
        rssi >= -60 -> Color(0xFF4CAF50)
        rssi >= -75 -> Color(0xFFFF9800)
        else -> Color(0xFFF44336)
    }
    Box(
        modifier = Modifier
            .size(44.dp)
            .clip(CircleShape)
            .background(color.copy(alpha = 0.15f)),
        contentAlignment = Alignment.Center
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                text = rssi.toString(),
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold,
                color = color
            )
            Text(text = "dBm", fontSize = 8.sp, color = color)
        }
    }
}

@Composable
private fun StatusBadge(status: TagStatus) {
    val (text, color) = when (status) {
        TagStatus.UPDATED -> "완료" to Color(0xFF4CAF50)
        TagStatus.PENDING -> "대기" to Color(0xFFFF9800)
        TagStatus.FAILED  -> "실패" to Color(0xFFF44336)
    }
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(8.dp))
            .background(color.copy(alpha = 0.15f))
            .padding(horizontal = 10.dp, vertical = 4.dp)
    ) {
        Text(text = text, color = color, fontSize = 12.sp, fontWeight = FontWeight.Medium)
    }
}
