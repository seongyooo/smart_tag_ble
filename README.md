# SmartTag BLE — Android App

ESP32 + OLED로 구성된 전자 가격표(ESL)를 BLE로 무선 업데이트하는 IoT 시스템의 Android 앱입니다.
별도의 서버 없이 **직원 스마트폰이 게이트웨이 역할**을 합니다.

---

## 프로젝트 설정

| 항목 | 값 |
|---|---|
| Package name | `com.example.smarttag` |
| Min SDK | API 26 (Android 8.0) |
| Target / Compile SDK | API 37 |
| Language | Kotlin |
| Architecture | MVVM |
| UI | Jetpack Compose |
| DB | Room (SQLite, v4) |
| BLE | Android BLE API (`android.bluetooth.le`) |

---

## 파일 구조

```
app/src/main/java/com/example/smarttag/
├── MainActivity.kt                  # 권한 요청 + Navigation 진입점
├── model/
│   └── SmartTag.kt                  # SmartTag, TagStatus, EventType 데이터 클래스
├── db/
│   ├── AppDatabase.kt               # Room DB 싱글톤 + TypeConverter (v4)
│   ├── SmartTagEntity.kt            # Entity + toModel/toEntity 변환
│   ├── SmartTagDao.kt               # DAO (getAllTags, getTagsByGroup, setTargetState 등)
│   └── CategoryEntity.kt            # 카테고리 Entity + CategoryDao
├── ble/
│   ├── BleManager.kt                # BLE 스캔, GATT 연결/쓰기/ACK, Broadcast
│   └── BlePackets.kt                # 패킷 빌더(0x02/0x04), 파서(0x01), HMAC
├── viewmodel/
│   └── ScanViewModel.kt             # 스캔 제어, DB 병합, BLE 이벤트, 브로드캐스트 루프
└── ui/
    ├── navigation/
    │   └── NavGraph.kt              # Compose Navigation 라우팅
    └── screen/
        ├── ScanScreen.kt            # 태그 목록 (운영/태그설정 탭)
        ├── TagDetailScreen.kt       # 태그 상세 + 개별 가격·이름·ID 설정
        ├── BroadcastScreen.kt       # 그룹 일괄 가격 수정 + 직접 브로드캐스트
        ├── NameUpdateScreen.kt      # 상품명 분할 브로드캐스트
        ├── PriceUpdateScreen.kt     # 그룹 가격 일괄 설정
        └── CategoryScreen.kt        # 카테고리(매대) 관리
```

---

## 구현된 기능

### 1. 태그 스캔 (`ScanScreen`)
- BLE 스캔 시작 / 중지
- Service UUID `4fafc201-1fb5-459e-8fcc-c5c9c331914b` + Type 0x01 Manufacturer Data 필터링
- 발견 태그 목록 (Tag ID, RSSI, 상태 배지 PENDING/UPDATED/FAILED)
- **운영 탭**: 현재 감지된 매대(RSSI 기반 자동 감지), 미완료 태그 수, 브로드캐스트 FAB
- **태그설정 탭**: 전체 태그 목록, 그룹 필터

### 2. 태그 상세 / 개별 설정 (`TagDetailScreen`)
- 태그 정보 (Tag ID, MAC, 그룹, 상품명)
- **GATT 개별 가격 수정**: Price Characteristic Write → ACK Notify 수신 → DB 갱신
- **GATT TagID 설정**: Config Characteristic Write (1바이트 TagID 주입)
- **목표 상태 설정**: 가격 / 이벤트(없음/1+1/2+1/할인) / 시작일·종료일 → DB 저장 (PENDING)
- **상품명 업데이트**: NameUpdateScreen으로 이동

### 3. 그룹 일괄 브로드캐스트 (`BroadcastScreen` + `ScanViewModel`)
- DB의 PENDING 태그를 Seq 번호 기반으로 묶어 0x02 패킷 broadcast
- 최대 15회 재시도, 모든 ACK 수신 시 UPDATED 전환
- 직접 브로드캐스트: 특정 TagID·가격을 즉시 1회 전송 (HEX 미리보기 포함)

### 4. 상품명 업데이트 (`NameUpdateScreen`)
- 상품명을 18바이트 단위로 분할 → 0x04 조각 순차 broadcast
- 바이트 카운트 표시, 마지막 조각 수신 후 완료

### 5. 카테고리 관리 (`CategoryScreen`)
- 매대(그룹) 이름 추가 / 수정 / 삭제
- 기본 카테고리 자동 초기화 (라면, 음료수, 과자, 과일/채소, 유제품)

### 6. RSSI 기반 매대 자동 감지
- 그룹별 RSSI 점수 합산 (-75 dBm 임계값), 1초 디바운스
- 현재 매대 전환 시 Snackbar 알림

### 7. 테스트 데이터셋
- 상단 메뉴 → 테스트 세트 A / B 적용 (Tag 1~9 사전 정의 데이터)

---

## BLE 프로토콜

### GATT (개별 수정 / 초기 설정)

| 항목 | UUID | 용도 |
|---|---|---|
| Service | `4fafc201-1fb5-459e-8fcc-c5c9c331914b` | 필터링 기준 |
| Price Char | `beb5483e-36e1-4688-b7f5-ea07361b26a8` | 가격 Write (uint32 LE 4B) |
| ACK Char | `12345678-1234-1234-1234-123456789abc` | ACK Notify `[AA][TagID 2B][Price 4B]` |
| Config Char | `12345678-1234-1234-1234-123456789def` | TagID Write (1B) |

### Broadcast 패킷

#### Type 0x01 — Tag Info / Seq Echo (태그→앱, 11B)
```
[FF FF][0x01][TagID 8b][LastSeq 8b][Padding 7B]
```
- 태그가 마지막으로 처리한 Seq 번호를 echo → 앱이 ACK로 인식

#### Type 0x02 — Price Update (앱→태그, 27B)
```
[FF FF][0x02][Seq 8b][Entry×3 18B][HMAC 3B][Rsvd 2B]
Entry(6B): [TagID 8b][Price 20b][Event 2b][Start 9b][End 9b]
```
- Price: 10원 단위 (20bit, 최대 약 1,000만원)
- Event: 00=없음 / 01=1+1 / 10=2+1 / 11=할인
- Start/End: [Month 4b][Day 5b] (0 = 미설정)
- End Marker: TagID=0x00 (3개 미만 시 삽입)
- HMAC: `HMAC-SHA256(key, [Type|Seq|Entry1~3])` 앞 24bit

#### Type 0x04 — Name Update (앱→태그, 27B)
```
[FF FF][0x04][TagID 8b][Seq 8b][Frag 8b][Name 18B][HMAC 3B]
Frag: [MORE 1b][Index 7b]
```
- 18바이트 단위 분할, 조각마다 HMAC 검증

### 보안
- 공유키: `"SmartTagDemo2026"` (하드코딩, 데모용)
- 태그가 HMAC 재계산 후 불일치 시 패킷 폐기

---

## 권한

| 권한 | 용도 |
|---|---|
| `BLUETOOTH_SCAN` | BLE 스캔 (Android 12+, `neverForLocation`) |
| `BLUETOOTH_CONNECT` | GATT 연결 (Android 12+) |
| `BLUETOOTH_ADVERTISE` | Broadcast 전송 (Android 12+) |
| `ACCESS_FINE_LOCATION` | BLE 스캔 (Android 11 이하) |

---

## 빌드 환경

| 항목 | 버전 |
|---|---|
| AGP | 9.1.1 |
| Kotlin | 2.2.10 |
| KSP | 2.2.10-2.0.2 |
| Compose BOM | 2026.02.01 |
| Room | 2.7.1 |
| Navigation Compose | 2.9.0 |

---

## 데이터 모델

```kotlin
data class SmartTag(
    val tagId: Int,              // 1~255 (0 = 미설정)
    val deviceAddress: String,   // MAC 주소
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
    val stateCrc: Int = 0,       // 내부적으로 lastSeq 저장
    val status: TagStatus = TagStatus.PENDING
)

enum class TagStatus { PENDING, UPDATED, FAILED }
enum class EventType(val code: Int) {
    NONE(0), ONE_PLUS_ONE(1), TWO_PLUS_ONE(2), DISCOUNT(3)
}
```

---

## 알려진 제한 사항

- **동시성 미흡**: `_discoveredTags` 읽기-수정-쓰기 비원자적 (race condition 가능)
- **seqMap 버그**: 배치 ACK 시 동일 Seq의 다른 태그 ACK 경로 삭제 가능
- **StateCRC 미구현**: 전체 상태 동기화 검증 없음 (Seq ACK만 동작)
- **앵커 비콘(0x03) 미구현**: RSSI 추정으로 대체 (불안정)
- **Walk-by 자동 트리거 미완성**: 설계는 있으나 완전히 연결되지 않음
- **GATT 리소스 누수**: `activeGatt` 콜백 내 미정리
- **상품명 72바이트 제한**: UI에서 강제하지 않음

자세한 이슈 목록은 [QA_REPORT.md](QA_REPORT.md) 참조.
