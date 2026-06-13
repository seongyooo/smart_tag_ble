package com.example.smarttag.ui.screen

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Send
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.example.smarttag.model.SmartTag
import com.example.smarttag.model.TagStatus
import com.example.smarttag.viewmodel.ScanViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TagDetailScreen(
    address: String,
    viewModel: ScanViewModel,
    onBack: () -> Unit
) {
    val tags by viewModel.mergedTags.collectAsState()
    val tag = tags.firstOrNull { it.deviceAddress == address }

    var priceInput by remember { mutableStateOf("") }
    var showDialog by remember { mutableStateOf(false) }
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
                title = { Text(tag?.deviceName ?: address) },
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
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // 태그 정보 카드
            TagInfoCard(tag = tag)

            // 가격 입력
            Card(shape = RoundedCornerShape(12.dp)) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("개별 가격 수정 (GATT)", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
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
                        onClick = { showDialog = true },
                        enabled = priceInput.isNotBlank(),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(Icons.Default.Send, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text("BLE로 전송")
                    }
                }
            }
        }
    }

    if (showDialog) {
        val price = priceInput.toIntOrNull() ?: 0
        AlertDialog(
            onDismissRequest = { showDialog = false },
            title = { Text("가격 전송 확인") },
            text = { Text("${tag?.deviceName}에 ${"%,d".format(price)}원을 전송합니다.") },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.connectAndWrite(address, price)
                    showDialog = false
                }) { Text("전송") }
            },
            dismissButton = {
                TextButton(onClick = { showDialog = false }) { Text("취소") }
            }
        )
    }
}

@Composable
private fun TagInfoCard(tag: SmartTag) {
    Card(shape = RoundedCornerShape(12.dp)) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("태그 정보", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            HorizontalDivider()
            InfoRow("Tag ID", tag.tagId)
            InfoRow("MAC 주소", tag.deviceAddress)
            InfoRow("RSSI", "${tag.rssi} dBm")
            InfoRow("목표 가격", if (tag.targetPrice > 0) "%,d원".format(tag.targetPrice) else "미설정")
            InfoRow("현재 가격", if (tag.currentPrice > 0) "%,d원".format(tag.currentPrice) else "미확인")
            InfoRow("상태", when (tag.status) {
                TagStatus.UPDATED -> "업데이트 완료"
                TagStatus.PENDING -> "대기 중"
                TagStatus.FAILED -> "실패"
            })
        }
    }
}

@Composable
private fun InfoRow(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(label, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodyMedium)
        Text(value, fontWeight = FontWeight.Medium, style = MaterialTheme.typography.bodyMedium)
    }
}
