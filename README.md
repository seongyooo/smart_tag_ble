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
| DB | Room (SQLite) |
| BLE | Android BLE API (`android.bluetooth.le`) |

---

## 구현된 기능

### 1. 태그 스캔 화면 (`ScanScreen`)
- BLE 스캔 시작 / 중지 (FAB 버튼)
- Service UUID `4fafc201-1fb5-459e-8fcc-c5c9c331914b` 필터링
- 발견된 SmartTag 목록 표시 (RSSI 내림차순 정렬)
- 태그 항목: 기기명, Tag ID, RSSI(dBm), 상태 배지(완료/대기/실패)
- 스캔 상태 표시바 (진행 인디케이터 + 발견 개수)
- 우상단 📣 아이콘으로 Broadcast 화면 이동

### 2. 개별 가격 수정 화면 (`TagDetailScreen`)
- 태그 상세 정보 표시 (Tag ID, MAC 주소, RSSI, 목표 가격, 현재 가격, 상태)
- 가격 입력 후 확인 다이얼로그 → GATT 연결 → Price Characteristic Write
- ACK Notify 수신 시 자동 완료 처리 및 DB 업데이트
- 오류 발생 시 Snackbar 알림

### 3. 그룹 일괄 수정 화면 (`BroadcastScreen`)
- Group ID 및 가격 입력
- BLE Manufacturer Specific Data 패킷 미리보기 (HEX)
- `BluetoothLeAdvertiser`로 5초간 브로드캐스트 후 자동 중지
- 수동 중지 버튼 제공

### 4. 태그 상태 관리 (Room DB)
- 태그별 목표 가격 / 현재 가격 / 상태(`PENDING` / `UPDATED` / `FAILED`) 영속 저장
- 스캔으로 발견된 태그와 DB 저장 데이터 자동 병합 표시

---

## 파일 구조

```
app/src/main/java/com/example/smarttag/
├── MainActivity.kt                  # 권한 요청 + Navigation 진입점
├── model/
│   └── SmartTag.kt                  # SmartTag, TagStatus, Category, Zone 데이터 클래스
├── db/
│   ├── SmartTagEntity.kt            # Room Entity + toModel/toEntity 변환
│   ├── SmartTagDao.kt               # DAO (getAllTags Flow, upsert, updateStatus)
│   └── AppDatabase.kt               # Room DB 싱글톤 + TypeConverter
├── ble/
│   └── BleManager.kt                # BLE 스캔, GATT 연결/쓰기/ACK, Broadcast
├── viewmodel/
│   └── ScanViewModel.kt             # 스캔 제어, DB 머지, BLE 이벤트 처리
└── ui/
    ├── navigation/
    │   └── NavGraph.kt              # Compose Navigation (scan / detail / broadcast)
    └── screen/
        ├── ScanScreen.kt            # 태그 목록 화면
        ├── TagDetailScreen.kt       # 태그 상세 + 개별 가격 수정
        └── BroadcastScreen.kt       # 그룹 일괄 가격 수정
```

---

## BLE 프로토콜

### GATT (개별 가격 수정)

| 항목 | 값 |
|---|---|
| Service UUID | `4fafc201-1fb5-459e-8fcc-c5c9c331914b` |
| Price Characteristic UUID | `beb5483e-36e1-4688-b7f5-ea07361b26a8` (WRITE) |
| ACK Characteristic UUID | `12345678-1234-1234-1234-123456789abc` (NOTIFY) |

**Price Write:** `uint32_t` 4바이트 Little Endian  
**ACK Notify:** `[AA][TagID Low][TagID High][Price 4B LE]` (7바이트)

### Broadcast (그룹 일괄 수정)

Manufacturer Specific Data: `[Company ID 2B][0x02][Group ID 2B][Price 4B]` (9바이트, LE)

---

## 권한

| 권한 | 용도 |
|---|---|
| `BLUETOOTH_SCAN` | BLE 스캔 (Android 12+) |
| `BLUETOOTH_CONNECT` | GATT 연결 (Android 12+) |
| `BLUETOOTH_ADVERTISE` | Broadcast 전송 (Android 12+) |
| `ACCESS_FINE_LOCATION` | BLE 스캔 (Android 11 이하) |

---

## 빌드 환경

| 항목 | 버전 |
|---|---|
| AGP | 9.1.1 |
| Kotlin | 2.2.10 (built-in) |
| KSP | 2.2.10-2.0.2 |
| Compose BOM | 2026.02.01 |
| Room | 2.7.1 |
| Navigation Compose | 2.9.0 |

---

## 미구현 (추후 예정)

- 앵커 태그(Type `0x03`) 감지 → Zone/Category 자동 브로드캐스트
- 태그 DB 등록/수정/삭제 관리 화면
