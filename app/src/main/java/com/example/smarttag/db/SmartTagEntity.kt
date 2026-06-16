package com.example.smarttag.db

import androidx.room.Entity
import androidx.room.PrimaryKey
import com.example.smarttag.model.EventType
import com.example.smarttag.model.SmartTag
import com.example.smarttag.model.TagStatus
import java.time.LocalDate

@Entity(tableName = "smart_tags")
data class SmartTagEntity(
    @PrimaryKey val deviceAddress: String,
    val tagId: Int,
    val deviceName: String,
    val rssi: Int,
    val groupId: Int,
    val productName: String,
    // 목표 상태
    val targetPrice: Int,
    val targetEvent: EventType,
    val targetStartDate: LocalDate?,
    val targetEndDate: LocalDate?,
    val targetName: String = "",  // 변경 예약된 상품명 ("" = 변경 없음)
    // 현재 상태 (태그 0x01 수신)
    val currentPrice: Int,
    val stateCrc: Int,
    val status: TagStatus
)

fun SmartTagEntity.toModel() = SmartTag(
    tagId = tagId,
    deviceAddress = deviceAddress,
    deviceName = deviceName,
    rssi = rssi,
    groupId = groupId,
    productName = productName,
    targetPrice = targetPrice,
    targetEvent = targetEvent,
    targetStartDate = targetStartDate,
    targetEndDate = targetEndDate,
    targetName = targetName,
    currentPrice = currentPrice,
    stateCrc = stateCrc,
    status = status
)

fun SmartTag.toEntity() = SmartTagEntity(
    deviceAddress = deviceAddress,
    tagId = tagId,
    deviceName = deviceName,
    rssi = rssi,
    groupId = groupId,
    productName = productName,
    targetPrice = targetPrice,
    targetEvent = targetEvent,
    targetStartDate = targetStartDate,
    targetEndDate = targetEndDate,
    targetName = targetName,
    currentPrice = currentPrice,
    stateCrc = stateCrc,
    status = status
)
