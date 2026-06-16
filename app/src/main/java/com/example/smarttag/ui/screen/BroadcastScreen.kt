package com.example.smarttag.ui.screen

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Campaign
import androidx.compose.material.icons.filled.Science
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.example.smarttag.ble.BlePackets
import com.example.smarttag.ble.PriceEntry
import com.example.smarttag.model.EventType
import com.example.smarttag.viewmodel.BroadcastQueueState
import com.example.smarttag.viewmodel.ScanViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BroadcastScreen(
    viewModel: ScanViewModel,
    onBack: () -> Unit
) {
    var tagIdInput by remember { mutableStateOf("2") }
    var priceInput by remember { mutableStateOf("") }   // 직접 전송 모드에서만 사용
    var isBroadcasting by remember { mutableStateOf(false) }
    var directMode by remember { mutableStateOf(true) }  // true = 특정 TagID 직접 전송

    val queueState by viewModel.broadcastQueueState.collectAsState()

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
                title = { Text("그룹 일괄 수정") },
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
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // ── 테스트 데이터 세트 ──────────────────────────────────
            Card(shape = RoundedCornerShape(12.dp)) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Icon(Icons.Default.Science, contentDescription = null,
                            tint = MaterialTheme.colorScheme.tertiary,
                            modifier = Modifier.size(18.dp))
                        Text("테스트 데이터 세트",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold)
                    }
                    Text(
                        "DB에 있는 Tag 1~9의 목표 가격/이벤트를 일괄 적용합니다. (status → PENDING)",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(
                            onClick = { viewModel.applyTestDataset("A") },
                            modifier = Modifier.weight(1f)
                        ) {
                            Text("세트 A")
                        }
                        OutlinedButton(
                            onClick = { viewModel.applyTestDataset("B") },
                            modifier = Modifier.weight(1f)
                        ) {
                            Text("세트 B")
                        }
                    }
                    // 세트 요약표
                    TestDatasetSummary()
                }
            }

            // 모드 선택
            Card(shape = RoundedCornerShape(12.dp)) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("전송 모드", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        RadioButton(selected = directMode, onClick = { directMode = true })
                        Text("직접 전송 (Tag ID 지정)", modifier = Modifier.padding(start = 4.dp))
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        RadioButton(selected = !directMode, onClick = { directMode = false })
                        Text("전체 PENDING 태그 전송 (DB 조회)", modifier = Modifier.padding(start = 4.dp))
                    }
                }
            }

            Card(shape = RoundedCornerShape(12.dp)) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("Broadcast 설정", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)

                    if (directMode) {
                        OutlinedTextField(
                            value = tagIdInput,
                            onValueChange = { tagIdInput = it.filter { c -> c.isDigit() } },
                            label = { Text("Tag ID (1~255)") },
                            modifier = Modifier.fillMaxWidth(),
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                            singleLine = true,
                            supportingText = { Text("ESP32에 설정된 MY_TAG_ID와 일치해야 함") }
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
                    } else {
                        Text(
                            "DB에 저장된 모든 PENDING 태그를 카테고리 무관하게 순차 전송합니다.\n" +
                            "각 태그의 태그 상세 화면에서 설정한 가격/이벤트가 사용됩니다.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }

            // BLE 패킷 미리보기 (직접 전송 모드에서만)
            val tagId = tagIdInput.toIntOrNull() ?: 2
            val price = priceInput.toIntOrNull() ?: 0
            if (directMode && priceInput.isNotBlank()) {
                BroadcastPreview(tagId = tagId, price = price)
            }

            // 전송 버튼
            val canBroadcast = if (directMode) priceInput.isNotBlank() && tagIdInput.isNotBlank()
                               else true
            Button(
                onClick = {
                    if (isBroadcasting) {
                        viewModel.stopBroadcast()
                        isBroadcasting = false
                    } else {
                        if (directMode) {
                            viewModel.broadcastDirect(tagId, price)
                        } else {
                            viewModel.startBroadcast()
                        }
                        isBroadcasting = true
                    }
                },
                enabled = canBroadcast || isBroadcasting,
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColors(
                    containerColor = if (isBroadcasting) MaterialTheme.colorScheme.error
                    else MaterialTheme.colorScheme.primary
                )
            ) {
                Icon(
                    imageVector = if (isBroadcasting) Icons.Default.Stop else Icons.Default.Campaign,
                    contentDescription = null,
                    modifier = Modifier.size(20.dp)
                )
                Spacer(Modifier.width(8.dp))
                Text(if (isBroadcasting) "브로드캐스트 중지" else "브로드캐스트 시작 (5초)")
            }

            if (isBroadcasting) {
                Card(
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.errorContainer
                    ),
                    shape = RoundedCornerShape(8.dp)
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                        Text(
                            if (directMode)
                                "Tag $tagId → ${"%,d원".format(price)} Advertising 중... 5초 후 자동 중지"
                            else
                                "전체 PENDING 태그 순차 전송 중...",
                            style = MaterialTheme.typography.bodyMedium
                        )
                    }
                }
            }

            // 그룹 모드: 큐 진행 상태
            if (!directMode) {
                when (val qs = queueState) {
                    is BroadcastQueueState.Running -> Card(
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.tertiaryContainer
                        ),
                        shape = RoundedCornerShape(8.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp,
                                color = MaterialTheme.colorScheme.onTertiaryContainer)
                            Text("잔여 ${qs.pendingCount}개 업데이트 중...",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onTertiaryContainer)
                        }
                    }
                    BroadcastQueueState.Done -> Card(
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.secondaryContainer
                        ),
                        shape = RoundedCornerShape(8.dp)
                    ) {
                        Text(
                            "전체 태그 업데이트 완료!",
                            modifier = Modifier.padding(12.dp),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSecondaryContainer
                        )
                    }
                    else -> {}
                }
            }
        }
    }
}

@Composable
private fun TestDatasetSummary() {
    val rows = listOf(
        // tagId, priceA, eventA, priceB, eventB
        listOf("Tag", "세트 A", "", "세트 B", ""),
        listOf("1", "1,100원", "-",    "5,000원",  "SALE 6/1~30"),
        listOf("2", "1,500원", "1+1 6/1~30", "8,900원", "-"),
        listOf("3", "2,000원", "-",    "12,000원", "1+1 6/20~30"),
        listOf("4", "980원",   "SALE 6/15~20", "3,500원", "2+1"),
        listOf("5", "3,200원", "2+1 6/1~15",  "15,000원", "-"),
        listOf("6", "500원",   "-",    "2,200원",  "1+1 6/1~15"),
        listOf("7", "1,800원", "1+1",  "9,800원",  "SALE 6/15~20"),
        listOf("8", "4,500원", "-",    "22,000원", "-"),
        listOf("9", "780원",   "SALE 6/10~25", "4,400원", "2+1 6/1~30"),
    )
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 4.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp)
    ) {
        rows.forEachIndexed { idx, row ->
            val isHeader = idx == 0
            Row(modifier = Modifier.fillMaxWidth()) {
                Text(
                    row[0],
                    modifier = Modifier.width(36.dp),
                    style = if (isHeader) MaterialTheme.typography.labelSmall
                            else MaterialTheme.typography.bodySmall,
                    fontWeight = if (isHeader) FontWeight.Bold else FontWeight.Normal,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        row[1],
                        style = if (isHeader) MaterialTheme.typography.labelSmall
                                else MaterialTheme.typography.bodySmall,
                        fontWeight = if (isHeader) FontWeight.Bold else FontWeight.Normal,
                        color = MaterialTheme.colorScheme.primary
                    )
                    if (row[2].isNotEmpty()) Text(
                        row[2],
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        row[3],
                        style = if (isHeader) MaterialTheme.typography.labelSmall
                                else MaterialTheme.typography.bodySmall,
                        fontWeight = if (isHeader) FontWeight.Bold else FontWeight.Normal,
                        color = MaterialTheme.colorScheme.secondary
                    )
                    if (row[4].isNotEmpty()) Text(
                        row[4],
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            if (isHeader) HorizontalDivider(modifier = Modifier.padding(vertical = 2.dp))
        }
    }
}

@Composable
private fun BroadcastPreview(tagId: Int, price: Int) {
    val sampleEntry = PriceEntry(
        tagId = tagId,
        price = price,
        event = EventType.NONE,
        startDate = null,
        endDate = null
    )
    val payload = BlePackets.buildType02(seq = 0, listOf(sampleEntry))  // seq=0: 미리보기용
    val payloadHex = payload.joinToString(" ") { "%02X".format(it.toInt() and 0xFF) }

    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
        shape = RoundedCornerShape(8.dp)
    ) {
        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("패킷 미리보기 (Type 0x02)", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold)
            Text(
                text = "FF FF $payloadHex",
                style = MaterialTheme.typography.bodySmall,
                fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                text = "Tag $tagId → ${"%,d원".format(price)}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.secondary
            )
        }
    }
}
