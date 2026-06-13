# SmartTag BLE — Android App 개발 명세서

## 프로젝트 개요

SmartTag BLE는 ESP32 + OLED로 구성된 전자 가격표(ESL)를 BLE로 무선 업데이트하는 IoT 시스템이다.
별도의 서버나 고정 인프라 없이 **직원 스마트폰이 게이트웨이 역할**을 대신한다.
플랫폼: **Android 단일** (iOS 제외 확정).

---

## 프로젝트 설정

```
Package name : com.example.smarttag
Namespace    : com.example.smarttag
Min SDK      : API 26 (Android 8.0)
Target SDK   : API 36
compileSdk   : 36
Language     : Kotlin
Architecture : MVVM
BLE          : Android BLE API (android.bluetooth.le)
DB           : Room (SQLite)
UI           : Jetpack Compose
```

---

## 하드웨어 스펙

- **보드**: LILYGO LORA32 T3_V1.6.1 (ESP32 + SSD1306 OLED)
- **펌웨어 라이브러리**: NimBLE-Arduino, Adafruit_SSD1306
- **BLE 역할**: GATT Server + BLE Scanner 동시 동작

---

## BLE 바이트 예산

```
BLE Advertising 패킷 : 31 byte
 └ BLE 표준 헤더     : 4 byte (고정, 수정 불가)
   └ Manufacturer Specific Data : 27 byte (설계 영역)
     └ 공통 헤더: [FF FF 2B][Type 1B][Group ID 1B] = 4B
```

---

## Type 체계 (4종)

| Type | 이름 | 방향 | 역할 |
|------|------|------|------|
| 0x01 | Tag Info | 태그→앱 | 현재 상태 방송 + Passive ACK |
| 0x02 | Price Update | 앱→태그 | 가격/이벤트/날짜 업데이트 |
| 0x03 | Anchor Beacon | 태그→앱 | Group ID로 구역 알림 |
| 0x04 | Name Update | 앱→태그 | 상품명 업데이트 (분할 전송) |

---

## 패킷 구조 상세

### 공통 헤더 (모든 Type)
```
[FF FF 2B][Type 1B][Group ID 1B] = 4B
```

---

### Type 0x01 — Tag Info / Passive ACK (11B)
```
byte:  0    1    2       3   4   5   6    7       8    9   10
     ┌──────┬─────┬───────┬──────────────┬───────┬──────────┐
     │Comp16│Typ8 │TagID 8│  Price 32    │Event 8│StateCRC16│
     │FF FF │0x01 │1~255  │ (uint32 LE)  │(2bit) │  (2byte) │
     └──────┴─────┴───────┴──────────────┴───────┴──────────┘

StateCRC = CRC16(Price | Event | Start | End | Name)
```

**동작**:
- 태그가 자신의 현재 상태를 지속 방송
- 앱이 목표 상태로 동일한 CRC 계산 → 태그 CRC와 일치 → 완전 동기화 완료
- 가격(0x02) / 이름(0x04) / 이벤트 / 날짜 ACK를 구분 없이 한 번에 확인

---

### Type 0x02 — Price Update (27B) ★ 핵심
```
byte: 0   1   2     3     4 ──────────────────────── 21  22 23 24  25 26
    ┌──────┬─────┬───────┬──────────────────────────┬─────────┬───────┐
    │Comp16│Typ8 │ Grp 8 │      Entry × 3 (18B)     │ HMAC 3B │ Rsv 2B│
    │FF FF │0x02 │ 1byte │       (48bit × 3)        │  24bit  │       │
    └──────┴─────┴───────┴──────────────────────────┴─────────┴───────┘
합계: 헤더 4B + Entry 18B + HMAC 3B + Reserved 2B = 27B ✓
```

**Entry 구조 (48bit = 6B)**
```
bit: 47    40 39         20 19 18 17   9 8    0
    ┌────────┬────────────┬────┬───────┬───────┐
    │ Tag ID │   Price    │Evt │ Start │  End  │
    │  8bit  │   20bit    │2bit│ 9bit  │ 9bit  │
    └────────┴────────────┴────┴───────┴───────┘

Tag ID : 0x01~0xFF (0x00 = End Marker, 이후 파싱 중단)
Price  : 10원 단위, 0~1,048,575 → 최대 약 1,000만원
Event  : 00=없음 / 01=1+1 / 10=2+1 / 11=할인
Start  : [Month 4bit(1~12)][Day 5bit(1~31)] (없으면 0)
End    : [Month 4bit(1~12)][Day 5bit(1~31)] (없으면 0)
```

**보안 — HMAC 3B (24bit)**
```
HMAC(key, [Type | Group | Entry1~3]) 앞 24bit
- 폰·태그가 사전 공유키 보유 (전송 X, 하드코딩 데모)
- 태그가 재계산·비교 → 불일치 시 폐기 (위변조 차단)
- Reserved 2B: 추후 재전송 방지 카운터 확장 예약
```

**패킷 예시** (Group 0x01, 라면 3개)
```
Tag03 신라면 1,500원 · 1+1 · 06/13~06/20 → Price=150, Event=01
Tag07 진라면 2,000원 · 할인 · 06/13~06/30 → Price=200, Event=11
Tag12 너구리  980원 · 없음 · 날짜 0      → Price=98,  Event=00

→ [FFFF][02][01][Entry03][Entry07][Entry12][HMAC 3B][Rsv 2B]
```

---

### Type 0x03 — Anchor Beacon (4B)
```
byte:  0    1    2     3
     ┌──────┬─────┬───────┐
     │Comp16│Typ8 │ Grp 8 │
     │FF FF │0x03 │1~255  │
     └──────┴─────┴───────┘

역할: 앵커 태그가 자신의 Group ID 방송
     → 앱이 RSSI 1위 + 디바운스 → 현재 매대 확정
     → Zone/Category 분리 없음 (Group ID = 매대 = 카테고리)
```

---

### Type 0x04 — Name Update (27B)
```
byte: 0   1   2     3     4      5     6 ──────────── 23   24 25 26
    ┌──────┬─────┬───────┬──────┬──────┬──────────────┬─────────┐
    │Comp16│Typ8 │ Grp 8 │TagID8│Frag 8│ Name 18 byte │ HMAC 3B │
    │FF FF │0x04 │ 1byte │1~255 │      │ UTF-8/0x00패딩│  24bit  │
    └──────┴─────┴───────┴──────┴──────┴──────────────┴─────────┘
합계: 헤더 4B + TagID 1B + Frag 1B + Name 18B + HMAC 3B = 27B ✓
```

**Frag 바이트 (8bit)**
```
[MORE 1bit][Index 7bit]
MORE  = 1 더 있음 / 0 마지막 조각
Index = 조각 순서 (0부터 시작)

예) 상품명 "새우깡 오리지널 130g" (24B, 2패킷):
패킷 1: MORE=1, Index=0, Name="새우깡 오리지" (18B)
패킷 2: MORE=0, Index=1, Name="널 130g\0\0..." (18B)
```

**보안**: `HMAC(key, [Type | Group | TagID | Frag | Name조각])` 앞 24bit, 조각마다 검증

---

## 순차 브로드캐스트 흐름

```
[최초/변경] 상품명 → 0x04 순차 broadcast → 태그 NVS 저장

┌──────────────── 운영 루프 ────────────────┐
│ 1. Anchor(0x03) 스캔                      │
│    → RSSI 1위 + 디바운스 → 현재 매대 확정 │
│ 2. 해당 그룹 미완료 태그 Queue 구성        │
│ 3. Queue에서 3개씩 Entry 구성             │
│    → End Marker 삽입 (3개 미만 시)        │
│    → HMAC 계산 후 0x02 broadcast          │
│ 4. Tag Info(0x01) 스캔                    │
│    → StateCRC 비교 → 일치 → Queue 제거   │
│ 5. Queue 빌 때까지 반복 (불일치 → 재전송) │
└───────────────────────────────────────────┘

OLED = [NVS 상품명] + [수신한 가격·이벤트·날짜]
```

---

## GATT (개별 수정 / 초기 설정)

| 항목 | 값 |
|------|-----|
| Service UUID | `4fafc201-1fb5-459e-8fcc-c5c9c331914b` |
| Price Char (WRITE) | `beb5483e-36e1-4688-b7f5-ea07361b26a8` · uint32 LE 4B |
| ACK Char (NOTIFY) | `12345678-1234-1234-1234-123456789abc` · `[AA][TagID 1B][Price 4B]` |
| Name/설정 Char (WRITE) | 상품명·GroupID NVS 주입 (UUID 미정) |

- 최초 설치 시 상품명·GroupID를 GATT로 주입 권장 (하드코딩 시 재플래시 문제)
- 세팅 전 OLED: 임시 라벨(`TAG-03`) → 세팅 후 실제 상품명 표시

---

## 앱 기능 명세

### 필수 기능 (MVP)

1. **태그 스캔 화면**: BLE 스캔 · Service UUID 필터링 · RSSI 정렬 · 상태 배지
2. **개별 가격 수정 (GATT)**: 태그 선택 → 연결 → Write → ACK Notify
3. **그룹 일괄 수정 (Broadcast)**: 앵커 감지 → 카테고리 자동 전환 → 순차 0x02 전송
4. **상품명 업데이트**: 0x04 분할 broadcast → StateCRC로 완료 확인
5. **태그 상태 관리**: 목표 상태 DB · 미완료 태그 재전송

### 선택 기능 (추후)

6. **앵커 태그 자동화**: RSSI + 디바운스 → 구역 자동 전환
7. **이벤트 스케줄러**: WorkManager로 시작/종료 시간 자동 broadcast

---

## 데이터 모델

```kotlin
data class SmartTag(
    val tagId: Int,             // 1~255
    val deviceAddress: String,
    val rssi: Int,
    val groupId: Int,
    val productName: String,
    val targetPrice: Int,       // 원화
    val currentPrice: Int,
    val event: EventType,
    val startDate: LocalDate?,
    val endDate: LocalDate?,
    val stateCrc: Int,          // 현재 상태 CRC16
    val status: TagStatus
)

enum class TagStatus { PENDING, UPDATED, FAILED }

enum class EventType(val code: Int) {
    NONE(0), ONE_PLUS_ONE(1), TWO_PLUS_ONE(2), DISCOUNT(3)
}
```

---

## build.gradle 현재 상태

```kotlin
android {
    namespace = "com.example.smarttag"
    defaultConfig {
        applicationId = "com.example.smarttag"
        minSdk = 26
        targetSdk = 36
    }
    buildFeatures { compose = true }
}
```

Room, ViewModel, Coroutine 의존성 추가 필요.

---

## 필수 권한 (AndroidManifest.xml)

```xml
<uses-permission android:name="android.permission.BLUETOOTH_SCAN"
    android:usesPermissionFlags="neverForLocation" />
<uses-permission android:name="android.permission.BLUETOOTH_CONNECT" />
<uses-permission android:name="android.permission.BLUETOOTH_ADVERTISE" />
<uses-permission android:name="android.permission.ACCESS_FINE_LOCATION" />
```

---

## 화면 구성

```
MainActivity
├── ScanScreen        : 태그 목록 + 스캔
├── TagDetailScreen   : 태그 상세 + 개별 가격/이벤트 수정
├── BroadcastScreen   : 그룹 일괄 수정
└── NameUpdateScreen  : 상품명 업데이트
```

---

## 미결 사항

- Price 채널 일관성 (broadcast 20bit ↔ GATT uint32)
- 디바운스 파라미터 (몇 초)
- GroupID 프로비저닝 방식
- 재시도 종료 조건 (FAILED 전환 기준)
- 공유키 관리 (하드코딩 → 추후 개선)
- StateCRC 다항식 확정

---

## 참고

- BLE Broadcast는 Android 8.0 이상 non-connectable 지원
- GATT 연결은 백그라운드 Coroutine 처리
- 에뮬레이터 BLE 불안정 → 실기기 테스트 권장
- Tag ID 0x00은 End Marker 예약 (실제 태그는 0x01부터)
