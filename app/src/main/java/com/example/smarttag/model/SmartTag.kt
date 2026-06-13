package com.example.smarttag.model

data class SmartTag(
    val tagId: String,          // "001"
    val deviceAddress: String,  // BLE MAC 주소
    val deviceName: String,
    val rssi: Int,
    val targetPrice: Int = 0,
    val currentPrice: Int = 0,
    val status: TagStatus = TagStatus.PENDING
)

enum class TagStatus {
    PENDING,
    UPDATED,
    FAILED
}

data class Category(
    val categoryId: Int,
    val name: String
)

data class Zone(
    val zoneId: Int,
    val anchorTagId: String,
    val categoryId: Int
)
