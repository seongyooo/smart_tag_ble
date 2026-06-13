package com.example.smarttag.ui.screen

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Campaign
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.example.smarttag.viewmodel.ScanViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BroadcastScreen(
    viewModel: ScanViewModel,
    onBack: () -> Unit
) {
    var groupIdInput by remember { mutableStateOf("1") }
    var priceInput by remember { mutableStateOf("") }
    var isBroadcasting by remember { mutableStateOf(false) }

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
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Card(shape = RoundedCornerShape(12.dp)) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("Broadcast 설정", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)

                    OutlinedTextField(
                        value = groupIdInput,
                        onValueChange = { groupIdInput = it.filter { c -> c.isDigit() } },
                        label = { Text("Group ID") },
                        modifier = Modifier.fillMaxWidth(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        singleLine = true
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
                }
            }

            // BLE 패킷 미리보기
            if (priceInput.isNotBlank() && groupIdInput.isNotBlank()) {
                BroadcastPreview(
                    groupId = groupIdInput.toIntOrNull() ?: 1,
                    price = priceInput.toIntOrNull() ?: 0
                )
            }

            Spacer(modifier = Modifier.weight(1f))

            // 전송 버튼
            Button(
                onClick = {
                    val groupId = groupIdInput.toIntOrNull() ?: 1
                    val price = priceInput.toIntOrNull() ?: 0
                    if (isBroadcasting) {
                        viewModel.bleManager.stopBroadcast()
                        isBroadcasting = false
                    } else {
                        viewModel.bleManager.startBroadcast(groupId, price, 5000L)
                        isBroadcasting = true
                    }
                },
                enabled = priceInput.isNotBlank() || isBroadcasting,
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
                        modifier = Modifier.fillMaxWidth().padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                        Text("Advertising 중... 5초 후 자동 중지", style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }
        }
    }
}

@Composable
private fun BroadcastPreview(groupId: Int, price: Int) {
    val priceBytes = buildList {
        var v = price
        repeat(4) { add(v and 0xFF); v = v ushr 8 }
    }
    val groupBytes = buildList {
        add(groupId and 0xFF)
        add((groupId ushr 8) and 0xFF)
    }
    val payloadHex = buildString {
        append("FF FF 02 ")
        append("%02X %02X ".format(groupBytes[0], groupBytes[1]))
        priceBytes.forEach { append("%02X ".format(it)) }
    }.trim()

    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
        shape = RoundedCornerShape(8.dp)
    ) {
        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("패킷 미리보기", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold)
            Text(
                text = payloadHex,
                style = MaterialTheme.typography.bodySmall,
                fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                text = "Group $groupId → ${"'%,d원'".format(price)}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.secondary
            )
        }
    }
}
