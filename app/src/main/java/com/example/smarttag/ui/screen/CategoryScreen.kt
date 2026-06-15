package com.example.smarttag.ui.screen

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
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
import com.example.smarttag.viewmodel.ScanViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CategoryScreen(
    viewModel: ScanViewModel,
    onBack: () -> Unit
) {
    val categories by viewModel.categories.collectAsState()
    val allTags    by viewModel.mergedTags.collectAsState()

    var showAddDialog by remember { mutableStateOf(false) }
    var deleteTarget  by remember { mutableStateOf<Int?>(null) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("카테고리 관리", fontWeight = FontWeight.Bold) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "뒤로")
                    }
                },
                actions = {
                    IconButton(onClick = { showAddDialog = true }) {
                        Icon(Icons.Default.Add, contentDescription = "카테고리 추가")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer
                )
            )
        }
    ) { padding ->
        if (categories.isEmpty()) {
            Box(
                modifier = Modifier.fillMaxSize().padding(padding),
                contentAlignment = Alignment.Center
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Icon(Icons.Default.Category, contentDescription = null,
                        modifier = Modifier.size(48.dp),
                        tint = MaterialTheme.colorScheme.outline)
                    Text("카테고리가 없습니다.",
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.outline)
                    FilledTonalButton(onClick = { showAddDialog = true }) {
                        Icon(Icons.Default.Add, contentDescription = null,
                            modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("카테고리 추가")
                    }
                }
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize().padding(padding),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                item {
                    Text(
                        "카테고리는 매대(구역)를 나타냅니다.\n" +
                        "각 태그에 카테고리를 지정하면 그룹으로 묶어 한 번에 업데이트할 수 있습니다.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                items(categories, key = { it.groupId }) { cat ->
                    val tagCount = allTags.count { it.groupId == cat.groupId }
                    Card(
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp, vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            // 그룹 번호 뱃지
                            Surface(
                                shape = RoundedCornerShape(8.dp),
                                color = MaterialTheme.colorScheme.primaryContainer,
                                modifier = Modifier.size(40.dp)
                            ) {
                                Box(contentAlignment = Alignment.Center) {
                                    Text(
                                        cat.groupId.toString(),
                                        style = MaterialTheme.typography.labelLarge,
                                        fontWeight = FontWeight.Bold,
                                        color = MaterialTheme.colorScheme.onPrimaryContainer
                                    )
                                }
                            }

                            Column(modifier = Modifier.weight(1f)) {
                                Text(cat.name,
                                    style = MaterialTheme.typography.bodyLarge,
                                    fontWeight = FontWeight.SemiBold)
                                Text(
                                    if (tagCount > 0) "태그 ${tagCount}개 연결됨" else "연결된 태그 없음",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }

                            IconButton(
                                onClick = { deleteTarget = cat.groupId }
                            ) {
                                Icon(Icons.Default.DeleteOutline, contentDescription = "삭제",
                                    tint = MaterialTheme.colorScheme.error)
                            }
                        }
                    }
                }

                item {
                    OutlinedButton(
                        onClick = { showAddDialog = true },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(Icons.Default.Add, contentDescription = null,
                            modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("카테고리 추가")
                    }
                }
            }
        }
    }

    // ── 추가 다이얼로그 ───────────────────────────────────────────────
    if (showAddDialog) {
        AddCategoryDialog(
            existingGroupIds = categories.map { it.groupId }.toSet(),
            onConfirm = { groupId, name ->
                viewModel.addOrUpdateCategory(groupId, name)
                showAddDialog = false
            },
            onDismiss = { showAddDialog = false }
        )
    }

    // ── 삭제 확인 다이얼로그 ─────────────────────────────────────────
    deleteTarget?.let { groupId ->
        val cat = categories.firstOrNull { it.groupId == groupId }
        val tagCount = allTags.count { it.groupId == groupId }
        AlertDialog(
            onDismissRequest = { deleteTarget = null },
            title = { Text("카테고리 삭제") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("'${cat?.name}' (Group $groupId)를 삭제하시겠습니까?")
                    if (tagCount > 0) {
                        Text(
                            "이 카테고리에 속한 태그 ${tagCount}개의 그룹 설정은 유지됩니다.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        viewModel.deleteCategory(groupId)
                        deleteTarget = null
                    },
                    colors = ButtonDefaults.textButtonColors(
                        contentColor = MaterialTheme.colorScheme.error
                    )
                ) { Text("삭제") }
            },
            dismissButton = {
                TextButton(onClick = { deleteTarget = null }) { Text("취소") }
            }
        )
    }
}

// ── 카테고리 추가 다이얼로그 ─────────────────────────────────────────

@Composable
private fun AddCategoryDialog(
    existingGroupIds: Set<Int>,
    onConfirm: (Int, String) -> Unit,
    onDismiss: () -> Unit
) {
    var groupIdInput by remember { mutableStateOf("") }
    var nameInput    by remember { mutableStateOf("") }

    val groupId = groupIdInput.toIntOrNull() ?: 0
    val isDuplicate = groupId > 0 && existingGroupIds.contains(groupId)
    val isValid = groupId in 1..255 && nameInput.isNotBlank() && !isDuplicate

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("카테고리 추가") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(
                    value = groupIdInput,
                    onValueChange = { groupIdInput = it.filter { c -> c.isDigit() } },
                    label = { Text("Group ID (1~255)") },
                    modifier = Modifier.fillMaxWidth(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    singleLine = true,
                    isError = groupIdInput.isNotBlank() && (groupId !in 1..255 || isDuplicate),
                    supportingText = when {
                        isDuplicate -> {{ Text("이미 사용 중인 Group ID입니다.", color = MaterialTheme.colorScheme.error) }}
                        groupIdInput.isNotBlank() && groupId !in 1..255 -> {{ Text("1~255 범위로 입력하세요.", color = MaterialTheme.colorScheme.error) }}
                        else -> null
                    }
                )
                OutlinedTextField(
                    value = nameInput,
                    onValueChange = { nameInput = it },
                    label = { Text("카테고리 이름") },
                    placeholder = { Text("예: 라면, 음료수, 과자") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onConfirm(groupId, nameInput.trim()) },
                enabled = isValid
            ) { Text("추가") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("취소") }
        }
    )
}
