package com.example.smarttag.db

import androidx.room.Entity
import androidx.room.PrimaryKey
import com.example.smarttag.model.SmartTag
import com.example.smarttag.model.TagStatus

@Entity(tableName = "smart_tags")
data class SmartTagEntity(
    @PrimaryKey val deviceAddress: String,
    val tagId: String,
    val deviceName: String,
    val rssi: Int,
    val targetPrice: Int,
    val currentPrice: Int,
    val status: TagStatus
)

fun SmartTagEntity.toModel() = SmartTag(
    tagId = tagId,
    deviceAddress = deviceAddress,
    deviceName = deviceName,
    rssi = rssi,
    targetPrice = targetPrice,
    currentPrice = currentPrice,
    status = status
)

fun SmartTag.toEntity() = SmartTagEntity(
    deviceAddress = deviceAddress,
    tagId = tagId,
    deviceName = deviceName,
    rssi = rssi,
    targetPrice = targetPrice,
    currentPrice = currentPrice,
    status = status
)
