package com.example.smarttag.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.smarttag.ble.BleEvent
import com.example.smarttag.ble.BleManager
import com.example.smarttag.db.AppDatabase
import com.example.smarttag.db.toEntity
import com.example.smarttag.db.toModel
import com.example.smarttag.model.SmartTag
import com.example.smarttag.model.TagStatus
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class ScanViewModel(application: Application) : AndroidViewModel(application) {

    val bleManager = BleManager(application)
    private val dao = AppDatabase.getInstance(application).smartTagDao()

    val isScanning: StateFlow<Boolean> = bleManager.isScanning

    // BLE 스캔으로 발견된 태그 (메모리, RSSI 포함)
    val discoveredTags: StateFlow<Map<String, SmartTag>> = bleManager.discoveredTags

    // DB에 저장된 태그 (targetPrice / status 포함)
    private val _savedTags = MutableStateFlow<List<SmartTag>>(emptyList())
    val savedTags: StateFlow<List<SmartTag>> = _savedTags.asStateFlow()

    // 병합된 최종 태그 목록 (스캔 + DB)
    private val _mergedTags = MutableStateFlow<List<SmartTag>>(emptyList())
    val mergedTags: StateFlow<List<SmartTag>> = _mergedTags.asStateFlow()

    private val _snackbarMessage = MutableStateFlow<String?>(null)
    val snackbarMessage: StateFlow<String?> = _snackbarMessage.asStateFlow()

    init {
        observeDb()
        observeBleEvents()
        observeDiscoveredTags()
    }

    private fun observeDb() {
        viewModelScope.launch {
            dao.getAllTags().collect { entities ->
                _savedTags.value = entities.map { it.toModel() }
                mergeTags()
            }
        }
    }

    private fun observeDiscoveredTags() {
        viewModelScope.launch {
            bleManager.discoveredTags.collect { mergeTags() }
        }
    }

    private fun mergeTags() {
        val scanned = bleManager.discoveredTags.value
        val saved = _savedTags.value.associateBy { it.deviceAddress }

        val merged = scanned.values.map { scannedTag ->
            val savedTag = saved[scannedTag.deviceAddress]
            scannedTag.copy(
                targetPrice = savedTag?.targetPrice ?: 0,
                currentPrice = savedTag?.currentPrice ?: 0,
                status = savedTag?.status ?: TagStatus.PENDING
            )
        }.sortedByDescending { it.rssi }

        _mergedTags.value = merged
    }

    private fun observeBleEvents() {
        viewModelScope.launch {
            bleManager.bleEvents.collect { event ->
                when (event) {
                    is BleEvent.AckReceived -> {
                        viewModelScope.launch {
                            dao.updateStatus(event.address, TagStatus.UPDATED, event.price)
                        }
                        _snackbarMessage.value = "Tag ${event.tagId} 업데이트 완료 (${event.price}원)"
                    }
                    is BleEvent.Error -> {
                        viewModelScope.launch {
                            dao.updateStatus(event.address, TagStatus.FAILED, 0)
                        }
                        _snackbarMessage.value = "오류: ${event.message}"
                    }
                    else -> {}
                }
            }
        }
    }

    fun toggleScan() {
        if (isScanning.value) bleManager.stopScan() else bleManager.startScan()
    }

    fun connectAndWrite(address: String, price: Int) {
        viewModelScope.launch {
            dao.setTargetPrice(address, price, TagStatus.PENDING)
        }
        bleManager.connectAndWrite(address, price)
    }

    fun upsertTag(tag: SmartTag) {
        viewModelScope.launch { dao.upsert(tag.toEntity()) }
    }

    fun clearSnackbar() {
        _snackbarMessage.value = null
    }

    override fun onCleared() {
        bleManager.stopScan()
        bleManager.disconnect()
        super.onCleared()
    }
}
