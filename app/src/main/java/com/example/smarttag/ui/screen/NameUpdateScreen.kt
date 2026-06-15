package com.example.smarttag.ui.screen

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Send
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.smarttag.ble.BlePackets
import com.example.smarttag.viewmodel.ScanViewModel
import kotlin.math.ceil

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NameUpdateScreen(
    address: String,
    viewModel: ScanViewModel,
    onBack: () -> Unit
) {
    val tags by viewModel.mergedTags.collectAsState()
    val tag = tags.firstOrNull { it.deviceAddress == address }

    var nameInput by remember { mutableStateOf("") }
    var isSending by remember { mutableStateOf(false) }
    var isDone by remember { mutableStateOf(false) }

    val snackbar by viewModel.snackbarMessage.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }
    LaunchedEffect(snackbar) {
        snackbar?.let {
            snackbarHostState.showSnackbar(it)
            viewModel.clearSnackbar()
        }
    }

    LaunchedEffect(tag?.deviceAddress) {
        tag?.let {
            if (it.productName.isNotBlank() && nameInput.isBlank()) nameInput = it.productName
        }
    }

    // 전송 완료 시 뒤로 이동
    LaunchedEffect(isDone) {
        if (isDone) {
            kotlinx.coroutines.delay(1200)
            onBack()
        }
    }

    // 단편 수 계산 (18B 청크)
    val nameBytes = nameInput.toByteArray(Charsets.UTF_8)
    val fragCount = if (nameBytes.isEmpty()) 1
                   else ceil(nameBytes.size / 18.0).toInt()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("상품명 수정") },
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
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // 태그 정보
            Card(shape = RoundedCornerShape(12.dp)) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("대상 태그", style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                    if (tag != null) {
                        Text(
                            "TAG-%03d  •  %s".format(tag.tagId, tag.deviceAddress),
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold
                        )
                        if (tag.groupId > 0) {
                            Text(
                                "카테고리: ${viewModel.getCategoryName(tag.groupId)}",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }

            // 상품명 입력
            Card(shape = RoundedCornerShape(12.dp)) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("상품명 (0x04 Broadcast)", style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold)

                    OutlinedTextField(
                        value = nameInput,
                        onValueChange = { if (!isSending) nameInput = it },
                        label = { Text("상품명") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        enabled = !isSending,
                        supportingText = {
                            Text("${nameBytes.size}B  •  단편 ${fragCount}개  •  최대 72B")
                        }
                    )

                    // 패킷 미리보기 (Seq=0 사용, 실제 전송 시에는 자동 할당됨)
                    if (nameInput.isNotBlank() && tag != null) {
                        val chunk = nameBytes.copyOf(minOf(18, nameBytes.size))
                        val payload = BlePackets.buildType04Fragment(
                            tagId     = tag.tagId,
                            seq       = 0,
                            fragIndex = 0,
                            hasMore   = fragCount > 1,
                            nameChunk = chunk
                        )
                        val hex = payload.joinToString(" ") { "%02X".format(it.toInt() and 0xFF) }
                        Surface(
                            color = MaterialTheme.colorScheme.surfaceVariant,
                            shape = RoundedCornerShape(8.dp)
                        ) {
                            Column(modifier = Modifier.padding(10.dp),
                                verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                Text("첫 번째 단편 미리보기 (Seq는 전송 시 자동 할당)",
                                    style = MaterialTheme.typography.labelSmall)
                                Text(
                                    "FF FF $hex",
                                    style = MaterialTheme.typography.bodySmall,
                                    fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace
                                )
                            }
                        }
                    }

                    // 전송 버튼
                    val canSend = nameInput.isNotBlank() && !isSending && !isDone && tag != null
                    Button(
                        onClick = {
                            if (tag == null) return@Button
                            isSending = true
                            isDone    = false
                            viewModel.broadcastTagName(
                                address  = address,
                                tagId    = tag.tagId,
                                name     = nameInput,
                                onComplete = {
                                    isSending = false
                                    isDone    = true
                                }
                            )
                        },
                        enabled = canSend,
                        modifier = Modifier.fillMaxWidth(),
                        colors = if (isDone) ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.secondary
                        ) else ButtonDefaults.buttonColors()
                    ) {
                        when {
                            isDone    -> {
                                Icon(Icons.Default.Check, contentDescription = null,
                                    modifier = Modifier.size(18.dp))
                                Spacer(Modifier.width(8.dp))
                                Text("전송 완료")
                            }
                            isSending -> {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(18.dp), strokeWidth = 2.dp,
                                    color = MaterialTheme.colorScheme.onPrimary
                                )
                                Spacer(Modifier.width(8.dp))
                                Text("전송 중... ($fragCount 단편)")
                            }
                            else -> {
                                Icon(Icons.Default.Send, contentDescription = null,
                                    modifier = Modifier.size(18.dp))
                                Spacer(Modifier.width(8.dp))
                                Text("상품명 전송 ($fragCount 단편 × ${fragCount * 1}초)")
                            }
                        }
                    }
                }
            }

            // 안내
            Card(
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant
                ),
                shape = RoundedCornerShape(8.dp)
            ) {
                Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("전송 방식 안내", style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.SemiBold)
                    Text(
                        "• 18B씩 분할해 단편마다 1초간 광고\n" +
                        "• 태그가 모든 단편 수집 후 NVS 저장\n" +
                        "• 그룹 내 같은 Tag ID 태그만 수신",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}
