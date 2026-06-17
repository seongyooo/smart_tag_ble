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
Target SDK   : API 37
compileSdk   : 37
Language     : Kotlin
Architecture : MVVM
BLE          : Android BLE API (android.bluetooth.le)
DB           : Room (SQLite, 버전 4)
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
     └ 공통 헤더: [FF FF 2B][Type 1B] = 3B  ← Group ID 제거됨
```

> **주의**: CLAUDE.md 초안의 공통 헤더는 `[FF FF][Type][Group]` 4B였으나,
> 프로토콜 재설계(PROTOCOL_REDESIGN.md Phase 1)로 **Group ID가 헤더에서 제거**되었다.
> 현재 0x02/0x04는 헤더 3B + Seq 1B 구조로 동작한다.

---

## Type 체계 (3종, 현재 구현)

| Type | 이름 | 방향 | 역할 | 구현 상태 |
|------|------|------|------|-----------|
| 0x01 | Tag Info / Seq Echo | 태그→앱 | 현재 상태 방송 + Seq echo ACK | ✅ 완료 |
| 0x02 | Price Update | 앱→태그 | 가격/이벤트/날짜 업데이트 (Seq 포함) | ✅ 완료 |
| 0x03 | Anchor Beacon | 태그→앱 | ~~Group ID로 구역 알림~~ **제거됨** | ❌ 미구현 |
| 0x04 | Name Update | 앱→태그 | 상품명 업데이트 (분할 전송, Seq 포함) | ✅ 완료 |

> Type 0x03(앵커 비콘)은 PROTOCOL_REDESIGN Phase 4에서 제거 결정.
> 매대 감지는 일반 태그 RSSI 점수합 + 1초 디바운스로 대체.

---

## 패킷 구조 상세 (현재 구현 기준)

### 공통 헤더
```
[FF FF 2B][Type 1B] = 3B
Company ID: 0xFFFF
```

---

### Type 0x01 — Tag Info / Seq Echo (태그→앱, 11B)
```
byte:  0    1    2       3        4 ─── 10
     ┌──────┬─────┬───────┬──────────┬────────┐
     │Comp16│Typ8 │TagID 8│ LastSeq 8│Padding │
     │FF FF │0x01 │1~255  │  0~255   │ (7B)   │
     └──────┴─────┴───────┴──────────┴────────┘
```

**동작**:
- 태그가 마지막으로 성공 처리한 Seq 번호를 echo
- 앱이 `seqMap[lastSeq]`에 해당 TagID가 있으면 ACK 처리 → UPDATED

> **이전 설계(StateCRC)**와의 차이: StateCRC(CRC16 전체 상태 비교)는 **미구현** 상태.
> 현재는 Seq echo 방식만 동작한다.

---

### Type 0x02 — Price Update (앱→태그, 27B) ★ 핵심
```
byte: 0   1   2     3     4 ──────────────────────── 21  22 23 24  25 26
    ┌──────┬─────┬───────┬──────────────────────────┬─────────┬───────┐
    │Comp16│Typ8 │ Seq 8B│      Entry × 3 (18B)     │ HMAC 3B │ Rsv 2B│
    │FF FF │0x02 │ 1~255 │       (48bit × 3)        │  24bit  │       │
    └──────┴─────┴───────┴──────────────────────────┴─────────┴───────┘
합계: 헤더 3B + Seq 1B + Entry 18B + HMAC 3B + Reserved 2B = 27B ✓
```

> **변경 사항**: 초안의 `[Group ID 1B]` 위치가 `[Seq 1B]`로 교체되었다.

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
HMAC-SHA256(key, [Type | Seq | Entry1~3]) 앞 24bit
key = "SmartTagDemo2026" (하드코딩, 데모용)
태그가 재계산·비교 → 불일치 시 폐기
```

---

### Type 0x04 — Name Update (앱→태그, 27B)
```
byte: 0   1   2     3     4      5     6 ──────────── 23   24 25 26
    ┌──────┬─────┬───────┬──────┬──────┬──────────────┬─────────┐
    │Comp16│Typ8 │TagID 8│Seq 8 │Frag 8│ Name 18 byte │ HMAC 3B │
    │FF FF │0x04 │1~255  │1~255 │      │ UTF-8/0x00패딩│  24bit  │
    └──────┴─────┴───────┴──────┴──────┴──────────────┴─────────┘
합계: 헤더 3B + TagID 1B + Seq 1B + Frag 1B + Name 18B + HMAC 3B = 27B ✓
```

> **변경 사항**: 초안의 `[Group ID 1B]`가 제거되고 `[Seq 1B]`가 추가되었다.

**Frag 바이트 (8bit)**
```
[MORE 1bit][Index 7bit]
MORE  = 1 더 있음 / 0 마지막 조각
Index = 조각 순서 (0부터 시작)
```

**보안**: `HMAC(key, [Type | TagID | Seq | Frag | Name조각])` 앞 24bit, 조각마다 검증  
**ACK**: 마지막 조각의 Seq를 태그가 0x01로 echo → `nameSeq[tagId] == lastSeq` 일치 시 완료

---

## ACK 메커니즘 (Seq Echo 방식)

```
[앱] Seq 할당 (nextSeq, 1~255 순환)
  → seqMap[seq] = [tagId 목록]  (0x02 Price ACK 추적)
  → nameSeq[tagId] = lastFragSeq (0x04 Name ACK 추적)

[태그] 0x01 broadcast에 LastSeq echo

[앱] 0x01 수신 시:
  1. nameSeq[tagId] == lastSeq  → 이름 ACK 완료
  2. seqMap[lastSeq]에 tagId    → 가격 ACK 완료
  둘 다 완료 시 → DB status = UPDATED
```

---

## 브로드캐스트 흐름 (현재 구현)

```
startBroadcast(maxRetries=15)
├── 모든 PENDING 태그 로드 (targetPrice > 0 필터)
├── Step A: 이름 브로드캐스트
│   └── targetName 있는 태그 RSSI 내림차순
│       → buildType04Fragment() × N 조각, 1초/조각
├── Step B: 가격 브로드캐스트
│   └── RSSI 상위 3개 → buildType02() → 1초 광고
│       → ACK 수신 대기 2초
├── UPDATED 수렴 시 종료
└── maxRetries 초과 → 남은 PENDING → FAILED
```

---

## GATT (개별 수정 / 초기 설정)

| 항목 | UUID | 형식 |
|------|------|------|
| Service | `4fafc201-1fb5-459e-8fcc-c5c9c331914b` | - |
| Price Char (WRITE) | `beb5483e-36e1-4688-b7f5-ea07361b26a8` | uint32 LE 4B |
| ACK Char (NOTIFY) | `12345678-1234-1234-1234-123456789abc` | `[AA][TagID 2B][Price 4B]` |
| Config Char (WRITE) | `12345678-1234-1234-1234-123456789def` | TagID 1B |

---

## 앱 기능 명세

### 구현 완료

1. **태그 스캔 화면** (`ScanScreen`): BLE 스캔, Service UUID + 0x01 필터, RSSI 정렬, 상태 배지, 운영/태그설정 탭
2. **개별 가격 수정** (`TagDetailScreen`): GATT Price Write → ACK Notify
3. **TagID 설정** (`TagDetailScreen`): GATT Config Write (1바이트)
4. **그룹 일괄 수정** (`BroadcastScreen`): Seq 기반 0x02 큐 브로드캐스트 (최대 15회 재시도)
5. **상품명 업데이트** (`NameUpdateScreen`): 0x04 분할 broadcast, Seq echo ACK
6. **카테고리 관리** (`CategoryScreen`): 매대 CRUD
7. **RSSI 매대 감지**: 그룹별 RSSI 점수합 + 1초 디바운스 → 현재 매대 자동 결정
8. **테스트 데이터셋**: 세트 A / B (ScanScreen MoreVert 메뉴)
9. **직접 브로드캐스트**: 특정 TagID·가격 즉시 전송, HEX 미리보기

### 미완성 / 제거됨

- ~~앵커 태그(Type 0x03)~~: 제거됨 (RSSI 대체)
- ~~StateCRC 전체 상태 검증~~: 미구현, Seq ACK로 대체
- ~~Session Control(0x05)~~: 제거됨
- Walk-by 자동 트리거: 설계 완료, 코드 미연결

---

## 데이터 모델

```kotlin
data class SmartTag(
    val tagId: Int,              // 1~255 (0 = 미설정)
    val deviceAddress: String,   // MAC 주소 (PK)
    val deviceName: String,
    val rssi: Int,
    val groupId: Int = 0,        // 카테고리/매대 (0 = 미분류)
    val productName: String = "",
    val targetPrice: Int = 0,    // 목표 가격 (원화)
    val targetEvent: EventType = EventType.NONE,
    val targetStartDate: LocalDate? = null,
    val targetEndDate: LocalDate? = null,
    val targetName: String = "", // 다음 브로드캐스트용 상품명
    val currentPrice: Int = 0,
    val stateCrc: Int = 0,       // 내부적으로 lastSeq 저장 (컬럼명 불일치 — QA-26)
    val status: TagStatus = TagStatus.PENDING
)

enum class TagStatus { PENDING, UPDATED, FAILED }

enum class EventType(val code: Int) {
    NONE(0), ONE_PLUS_ONE(1), TWO_PLUS_ONE(2), DISCOUNT(3)
}
```

**Room DB 버전 이력**
- v1~v3: 초기 스키마
- v4: `targetName` 컬럼 추가 (마이그레이션 3→4 적용)

---

## 화면 구성

```
MainActivity
├── ScanScreen          : 태그 목록 + 스캔 (운영/태그설정 탭)
├── TagDetailScreen     : 태그 상세 + 개별 가격/이름/ID 수정
├── BroadcastScreen     : 그룹 일괄 수정 + 직접 브로드캐스트
├── NameUpdateScreen    : 상품명 분할 브로드캐스트
├── PriceUpdateScreen   : 그룹 가격 일괄 설정
└── CategoryScreen      : 카테고리(매대) 관리
```

---

## build.gradle 현재 상태

```kotlin
android {
    namespace = "com.example.smarttag"
    compileSdk = 37
    defaultConfig {
        applicationId = "com.example.smarttag"
        minSdk = 26
        targetSdk = 37
    }
    buildFeatures { compose = true }
}

// 주요 의존성
// Room 2.7.1 (KSP 2.2.10-2.0.2)
// Navigation Compose 2.9.0
// Compose BOM 2026.02.01
// Coroutines (android scope)
// Lifecycle ViewModelCompose
```

---

## 필수 권한 (AndroidManifest.xml)

```xml
<uses-permission android:name="android.permission.BLUETOOTH_SCAN"
    android:usesPermissionFlags="neverForLocation" />
<uses-permission android:name="android.permission.BLUETOOTH_CONNECT" />
<uses-permission android:name="android.permission.BLUETOOTH_ADVERTISE" />
<uses-permission android:name="android.permission.ACCESS_FINE_LOCATION" />
<uses-permission android:name="android.permission.ACCESS_COARSE_LOCATION" />
<uses-feature android:name="android.hardware.bluetooth_le" android:required="true" />
```

---

## 알려진 이슈 (QA_REPORT.md 요약)

### Critical (블로킹)
- **QA-01**: ESP32 `g_name[64]` 버퍼 — 0x04 재조립 시 최대 72B → 8B 오버플로
- **QA-02**: 동일 태그 0x01 반복 수신 시 StateFlow 이벤트 유실
- **QA-03**: `_discoveredTags` 읽기-수정-쓰기 비원자적 (lost update)
- **QA-04**: `seqMap.remove` 배치 ACK 시 다른 태그 ACK 경로 삭제
- **QA-05**: `seqMap` 멀티 코루틴 동시 접근 (double-ACK 가능)
- **QA-06**: StateCRC 미구현 (Seq ACK만 동작)
- **QA-07**: Type 0x03 미구현 (RSSI 대체 불안정)

### High
- GATT `activeGatt` 리소스 누수
- 광고 실패 시 에러 이벤트 미발행
- `observeBleEvents` 코루틴 중첩 누적
- 상품명 72바이트 제한 UI 미강제
- `targetPrice=0` 태그 PENDING 상태 고착

자세한 이슈 목록은 **QA_REPORT.md** 참조.

---

## 미결 사항

- StateCRC 다항식 확정 및 구현 (현재 Seq ACK로 임시 대체)
- 공유키 관리 (하드코딩 → 추후 개선)
- 재시도 종료 조건 세분화 (현재 15회 고정)
- GATT Config Char UUID 확정 (현재 임시 UUID)
- Walk-by 자동 트리거 완전 연결
- 영구 권한 거부 처리 (설정 화면 유도)

---

## 참고

- BLE Broadcast는 Android 8.0 이상 non-connectable 지원
- GATT 연결은 백그라운드 Coroutine 처리
- 에뮬레이터 BLE 불안정 → 실기기 테스트 권장
- Tag ID 0x00은 End Marker 예약 (실제 태그는 0x01부터)
- `stateCrc` DB 컬럼은 현재 `lastSeq` 값을 저장 (컬럼명 재사용, 추후 분리 권장)
- Room `fallbackToDestructiveMigration()` 설정됨 → 누락 마이그레이션 시 데이터 손실 주의
