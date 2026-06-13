# SmartTag BLE — Android App 개발 명세서

## 프로젝트 개요

SmartTag BLE는 ESP32 + OLED로 구성된 전자 가격표(ESL)를 BLE로 무선 업데이트하는 IoT 시스템이다.
별도의 서버나 고정 인프라 없이 **직원 스마트폰이 게이트웨이 역할**을 대신한다.

---

## 하드웨어 스펙

- **보드**: LILYGO LORA32 T3_V1.6.1 (ESP32 + SSD1306 OLED)
- **펌웨어 라이브러리**: NimBLE-Arduino, Adafruit_SSD1306
- **BLE 역할**: GATT Server + BLE Scanner 동시 동작

---

## BLE 프로토콜

### GATT (개별 가격 수정)

| 항목 | 값 |
|---|---|
| Service UUID | `4fafc201-1fb5-459e-8fcc-c5c9c331914b` |
| Price Characteristic UUID | `beb5483e-36e1-4688-b7f5-ea07361b26a8` |
| Price Property | WRITE |
| ACK Characteristic UUID | `12345678-1234-1234-1234-123456789abc` |
| ACK Property | NOTIFY |

**Price Write 포맷**
```
uint32_t (4바이트, Little Endian)
예) 1000원 → E8 03 00 00
예) 2000원 → D0 07 00 00
```

**ACK Notify 포맷**
```
[AA][TagID Low][TagID High][Price 4B (LE)]
총 7바이트
예) AA 01 00 D0 07 00 00
  → Tag 001, 2000원 업데이트 완료
```

### Broadcast (그룹 일괄 수정)

**Advertising 패킷 — Manufacturer Specific Data**
```
[Company ID 2B][Type 1B][Group ID 2B][Price 4B]
총 9바이트 (Little Endian)

Company ID : 0xFFFF (테스트용)
Type       : 0x02 (Broadcast)
Group ID   : 0x0001 (기본 그룹)
Price      : uint32 LE

예) FF FF 02 01 00 D0 07 00 00 → Group 1, 2000원
```

**Advertising 패킷 — 앵커 태그 (구역 식별)**
```
[Company ID 2B][Type 1B][Zone ID 2B][Category ID 2B]
총 7바이트

Type        : 0x03 (Anchor Beacon)
Zone ID     : 구역 번호
Category ID : 카테고리 번호

예) FF FF 03 01 00 01 00 → Zone 1, Category 1 (과자)
```

---

## 앱 기능 명세

### 필수 기능 (MVP)

#### 1. 태그 스캔 화면
- BLE 스캔 시작/중지
- 주변 SmartTag 목록 표시 (RSSI 기반 정렬)
- 태그 항목: 이름, Tag ID, RSSI, 현재 상태 (완료/대기/미수신)
- 필터: Service UUID `4fafc201...` 기준으로만 표시

#### 2. 개별 가격 수정 (GATT)
- 태그 선택 → GATT 연결
- 가격 입력 다이얼로그
- Price Characteristic Write
- ACK Notify 수신 → 완료 처리
- 연결 해제

#### 3. 그룹 일괄 수정 (Broadcast)
- 그룹 ID 선택
- 가격 입력
- BluetoothLeAdvertiser로 패킷 방송
- 일정 시간(5초) 후 자동 중지

#### 4. 태그 상태 관리
- 태그별 목표 가격 / 현재 가격 / 상태 저장
- 이미 업데이트 완료된 태그는 브로드캐스트 스킵
- 미완료 태그만 재전송

### 선택 기능 (추후 구현)

#### 5. 앵커 태그 기반 자동화
- 앵커 태그 패킷(Type 0x03) 감지
- Zone ID → Category ID 매핑
- 해당 카테고리 태그만 자동 브로드캐스트 전환

#### 6. 태그 DB 관리
- 태그 등록/수정/삭제
- 상품명, 카테고리, 가격 관리

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

## build.gradle 현재 상태

```kotlin
plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "com.example.smarttag"
    compileSdk {
        version = release(36) {
            minorApiLevel = 1
        }
    }

    defaultConfig {
        applicationId = "com.example.smarttag"
        minSdk = 26        // BLE Broadcast 필수
        targetSdk = 36
        versionCode = 1
        versionName = "1.0"
    }

    buildFeatures {
        compose = true
    }
}
```

Room, ViewModel, Coroutine 의존성은 직접 추가 필요.

---

## 필수 권한 (AndroidManifest.xml)

```xml
<!-- BLE 기본 -->
<uses-permission android:name="android.permission.BLUETOOTH" />
<uses-permission android:name="android.permission.BLUETOOTH_ADMIN" />

<!-- Android 12+ -->
<uses-permission android:name="android.permission.BLUETOOTH_SCAN"
    android:usesPermissionFlags="neverForLocation" />
<uses-permission android:name="android.permission.BLUETOOTH_CONNECT" />
<uses-permission android:name="android.permission.BLUETOOTH_ADVERTISE" />

<!-- Android 11 이하 위치 권한 (BLE 스캔 필수) -->
<uses-permission android:name="android.permission.ACCESS_FINE_LOCATION" />
<uses-permission android:name="android.permission.ACCESS_COARSE_LOCATION" />
```

---

## 데이터 모델

```kotlin
// 태그 정보
data class SmartTag(
    val tagId: String,          // "001"
    val deviceAddress: String,  // BLE MAC 주소
    val rssi: Int,              // 신호 강도
    val targetPrice: Int,       // 목표 가격
    val currentPrice: Int,      // 현재 가격 (ACK로 확인된 값)
    val status: TagStatus       // PENDING / UPDATED / FAILED
)

enum class TagStatus {
    PENDING,   // 업데이트 대기
    UPDATED,   // 완료
    FAILED     // 실패
}

// 카테고리
data class Category(
    val categoryId: Int,
    val name: String            // "과자", "생필품", "음료"
)

// 구역 (앵커 태그 연동)
data class Zone(
    val zoneId: Int,
    val anchorTagId: String,
    val categoryId: Int
)
```

---

## BLE 주요 플로우

### GATT 개별 수정 플로우

```
1. BluetoothLeScanner로 스캔
2. Service UUID 필터링 → SmartTag 감지
3. connectGatt() 호출
4. onConnectionStateChange → STATE_CONNECTED
5. discoverServices()
6. onServicesDiscovered
   → ACK Characteristic에 setCharacteristicNotification(true)
   → CCCD Descriptor에 ENABLE_NOTIFICATION_VALUE 설정
7. Price Characteristic에 writeCharacteristic(price bytes)
8. onCharacteristicWrite 콜백 확인
9. ACK Notify 수신 → onCharacteristicChanged
   → AA + TagID + Price 파싱 → 완료 처리
10. disconnect()
```

### Broadcast 플로우

```
1. BluetoothLeAdvertiser 인스턴스 획득
2. AdvertiseSettings 설정
   - AdvertiseMode: ADVERTISE_MODE_LOW_LATENCY
   - Connectable: false
3. AdvertiseData 구성
   - Manufacturer Specific Data 추가
   - Company ID: 0xFFFF
   - Data: [02][GroupID 2B][Price 4B]
4. startAdvertising()
5. 5초 후 stopAdvertising()
```

---

## 화면 구성 (최소)

```
MainActivity
├── ScanFragment       : 태그 목록 + 스캔 버튼
├── TagDetailFragment  : 태그 상세 + 개별 가격 수정
└── BroadcastFragment  : 그룹 선택 + 일괄 가격 수정
```

---

## 참고 사항

- GATT 연결은 메인 스레드가 아닌 백그라운드에서 처리
- BLE 콜백은 비동기 → Coroutine 또는 Handler로 처리
- Android 12+ 에서 런타임 권한 요청 필수
  (BLUETOOTH_SCAN, BLUETOOTH_CONNECT, BLUETOOTH_ADVERTISE)
- Broadcast는 Android 8.0 이상에서 non-connectable 지원
- 에뮬레이터에서 BLE 불안정 → 실기기 테스트 권장
