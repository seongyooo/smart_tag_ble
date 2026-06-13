package com.example.smarttag.model

import java.time.LocalDate

data class SmartTag(
    val tagId: Int,              // 1~255
    val deviceAddress: String,
    val deviceName: String,      // BLE 광고 이름 (표시용)
    val rssi: Int,
    val groupId: Int = 0,
    val productName: String = "",
    // 목표 상태 (앱 → 태그로 보낼 값)
    val targetPrice: Int = 0,
    val targetEvent: EventType = EventType.NONE,
    val targetStartDate: LocalDate? = null,
    val targetEndDate: LocalDate? = null,
    // 현재 상태 (태그 0x01 방송에서 수신한 값)
    val currentPrice: Int = 0,
    val stateCrc: Int = 0,       // 태그에서 수신한 StateCRC16
    val status: TagStatus = TagStatus.PENDING
)

enum class TagStatus {
    PENDING,   // 업데이트 대기
    UPDATED,   // StateCRC 일치 확인 완료
    FAILED     // 전송 실패
}

enum class EventType(val code: Int) {
    NONE(0),
    ONE_PLUS_ONE(1),
    TWO_PLUS_ONE(2),
    DISCOUNT(3);

    companion object {
        fun fromCode(code: Int) = entries.firstOrNull { it.code == code } ?: NONE
    }
}
