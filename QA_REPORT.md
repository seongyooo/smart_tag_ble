# SmartTag BLE — QA 리포트

**분석 일자:** 2026-06-16  
**분석 대상 커밋:** `208d300`  
**플랫폼:** Android (Kotlin/Compose) + ESP32 Arduino 펌웨어

---

## 요약

| 심각도 | 건수 |
|--------|------|
| Critical | 7 |
| High | 11 |
| Medium | 9 |
| Low | 3 |
| **합계** | **30** |

---

## Critical — 즉시 수정 필요

---

### QA-01 · g_name 버퍼 오버플로우 (ESP32)

**파일:** `esp32/sketch_jun13a/sketch_jun13a.ino:55, 359-363`

```c
static char g_name[64] = "";          // 64B 배열
...
for (int i = 0; i <= lastFragIdx; i++) {
    memcpy(g_name + nl, nameFrag[i], FRAG_SIZE);  // 최대 4×18=72B 복사
    nl += FRAG_SIZE;
}
```

`MAX_FRAGS=4`, `FRAG_SIZE=18` → 최대 72B 복사, 배열은 64B. **8B 버퍼 오버플로우** 발생.  
스택 오염 또는 메모리 손상으로 ESP32가 재부팅될 수 있다.

**수정:** `static char g_name[MAX_FRAGS * FRAG_SIZE + 1] = "";` (73B)

---

### QA-02 · bleEvents를 StateFlow로 구현 → 동일 이벤트 유실

**파일:** `app/.../ble/BleManager.kt:58-59`

```kotlin
private val _bleEvents = MutableStateFlow<BleEvent?>(null)
```

`StateFlow`는 동일 값을 연속으로 emit하면 두 번째 값을 무시한다(`distinctUntilChanged` 기본 동작). 같은 태그로부터 두 번의 ACK 이벤트, 동일한 가격의 연속 Write 성공 등 빠른 연속 이벤트가 유실된다.

**수정:** `MutableSharedFlow<BleEvent>(replay=0, extraBufferCapacity=64)` 사용

---

### QA-03 · updateDiscoveredTag Lost Update 경쟁 조건

**파일:** `app/.../ble/BleManager.kt:97-122`

```kotlin
val current = _discoveredTags.value.toMutableMap()
// ... 수정 ...
_discoveredTags.value = current  // read-modify-write, 원자적이지 않음
```

`onScanResult`는 BLE 내부 스레드에서 병렬 호출 가능. 두 스레드가 동시에 읽고 쓰면 한 쪽 업데이트가 유실된다.

**수정:** `synchronized(this)` 블록으로 read-modify-write를 보호하거나 `ConcurrentHashMap` 사용

---

### QA-04 · seqMap.remove로 배치 내 나머지 태그 ACK 경로 소멸

**파일:** `app/.../viewmodel/ScanViewModel.kt:233-238`

```kotlin
val priceTagIds = seqMap[lastSeq]               // [T1, T2, T3]
if (priceTagIds != null && tagId in priceTagIds) {
    seqMap.remove(lastSeq)                       // ← T1 ACK 시 T2, T3 경로도 삭제
    dao.updateStatusById(tagId, TagStatus.UPDATED)
}
```

한 배치에 3개 태그가 있을 때 T1의 ACK가 먼저 오면 `seqMap.remove`로 T2, T3의 ACK 추적 경로도 사라진다. T2, T3는 영원히 PENDING 상태를 유지한다.

**수정:** ACK된 tagId만 seqMap 리스트에서 제거하고, 리스트가 빌 때 삭제

```kotlin
val remaining = priceTagIds.filterNot { it == tagId }
if (remaining.isEmpty()) seqMap.remove(lastSeq)
else seqMap[lastSeq] = remaining
```

---

### QA-05 · processSeqAck seqMap 동시 수정 경쟁

**파일:** `app/.../viewmodel/ScanViewModel.kt:230-249`

`processSeqAck`는 suspend 함수이고 여러 BLE 이벤트 처리 코루틴에서 동시 호출될 수 있다. `seqMap`은 일반 `mutableMapOf`이므로 read-after-check 패턴에서 경쟁 조건 발생 → 동일 Seq에 대해 이중 ACK 처리 가능.

**수정:** `Mutex`를 이용한 임계구역 보호

```kotlin
private val seqMapMutex = Mutex()

private suspend fun processSeqAck(tagId: Int, lastSeq: Int): Boolean {
    if (lastSeq == 0) return false
    return seqMapMutex.withLock {
        val priceTagIds = seqMap[lastSeq]
        if (priceTagIds != null && tagId in priceTagIds) {
            val remaining = priceTagIds.filterNot { it == tagId }
            if (remaining.isEmpty()) seqMap.remove(lastSeq) else seqMap[lastSeq] = remaining
            true
        } else nameSeq[tagId] == lastSeq && nameSeq.remove(tagId) != null
    }.also { if (it) dao.updateStatusById(tagId, TagStatus.UPDATED) }
}
```

---

### QA-06 · StateCRC 미구현 — 실제 상태 동기화 확인 불가

**CLAUDE.md 명세:**
> `StateCRC = CRC16(Price | Event | Start | End | Name)`  
> 앱이 목표 상태로 동일한 CRC 계산 → 태그 CRC와 일치 → 완전 동기화 완료

현재 구현은 Seq 기반 ACK만 사용 (패킷 수신 확인). 태그가 0x02를 수신해 내부적으로 상태를 업데이트했는지 검증하지 않는다. 태그 재부팅, NVS 저장 실패 등으로 실제 가격이 다른 상태에서도 앱은 UPDATED로 표시할 수 있다.

**현황:** 펌웨어도 StateCRC를 0x01에 포함하지 않음. Seq 기반 ACK로 대체됐으나 명세 불충족.

---

### QA-07 · Type 0x03 Anchor Beacon 미구현

**CLAUDE.md 필수 기능 3번:**
> 앵커 감지 → 카테고리 자동 전환 → 순차 0x02 전송

앱의 `scanCallback`에서 0x03 파싱 코드 없음. 펌웨어에도 0x03 Anchor Beacon 전송 없음. 현재 `updateZone()`에서 RSSI 합산으로 구역을 추정하는 방식을 사용하지만, 이는 BLE 신호 간섭에 취약하고 명세와 다른 구현이다.

---

## High — 우선 수정 권장

---

### QA-08 · configGattCallback에서 activeGatt 미갱신 → 리소스 누수

**파일:** `app/.../ble/BleManager.kt:269-274`

```kotlin
fun connectAndWriteConfig(address: String, tagId: Int) {
    activeGatt?.close()
    activeGatt = device.connectGatt(context, false, configGattCallback(...), ...)
}
```

`configGattCallback` 내부에서 GATT 연결이 완료되어도 `activeGatt`는 이 새 연결 객체를 참조한다. 그러나 Config 연결 진행 중 `connectAndWrite`가 호출되면 `activeGatt?.close()`가 Config 연결을 닫아버리고, Config 연결의 `BluetoothGatt` 객체는 고아 상태로 남아 close되지 않는다.

**수정:** `configGattCallback`에서 연결 시 `activeGatt`를 별도 변수로 관리하거나, 두 연결 유형을 Sealed class로 구분

---

### QA-09 · Advertising 실패 무음 처리

**파일:** `app/.../ble/BleManager.kt:409-416`

```kotlin
override fun onStartFailure(errorCode: Int) {
    Log.e(TAG, "Advertising failed: $errorCode")
    // ← 앱에 아무 통보 없음
}
```

`ADVERTISE_FAILED_ALREADY_STARTED` 또는 기기 한계로 광고가 시작되지 않아도 `bleEvents`에 Error 이벤트를 보내지 않는다. 그룹 브로드캐스트 루프가 실패를 감지하지 못하고 정상 작동 중이라고 오판한다.

**수정:** `_bleEvents.value = BleEvent.Error("", "광고 시작 실패: $errorCode")` 추가

---

### QA-10 · observeBleEvents 중첩 launch — 코루틴 폭발

**파일:** `app/.../viewmodel/ScanViewModel.kt:172-222`

`collect` 내부에서 이벤트마다 `viewModelScope.launch`를 추가로 생성한다. `TagInfoReceived` 이벤트는 launch를 2개 생성(내부 DB 업데이트 launch + `onTag01Received` 내부 launch). BLE 스캔이 활발한 경우(초당 수십 패킷) 코루틴이 폭발적으로 누적된다.

**수정:** 가능한 경우 suspend 함수를 직접 호출하거나, 이벤트 채널을 actor 패턴으로 직렬화

---

### QA-11 · 그룹 브로드캐스트 루프와 Walk-by 브로드캐스트 동시 충돌

**파일:** `app/.../viewmodel/ScanViewModel.kt:364`

`runGroupBroadcastLoop`에서 `broadcastPriceUpdate(seq, entries, 5000L)` 호출 후 `delay(7000L)` 동안 Walk-by 자동 브로드캐스트가 `startAdvertising → stopBroadcast()`를 호출해 그룹 루프의 현재 패킷을 중단시킨다. 그룹 루프는 이를 감지하지 못하고 7초 후 다음 시도를 한다.

**수정:** `broadcastLoopJob`이 실행 중일 때 Walk-by를 억제하거나, `broadcastLoopJob`을 `isActive` 체크로 보호

---

### QA-12 · broadcastTagName — tagId=0으로 전송 허용

**파일:** `app/.../ui/screen/NameUpdateScreen.kt:47`

```kotlin
val canSend = nameInput.isNotBlank() && !isSending && !isDone && tag != null
// tag.tagId == 0인 경우도 허용됨
```

tagId=0은 CLAUDE.md에서 "End Marker 예약"으로 금지된 값. 0x04 패킷에 tagId=0이 들어가면 ESP32는 `if (d[3] != g_tagId) return`에서 기각하지만, 만약 어떤 ESP32가 g_tagId=0이라면 잘못된 이름이 저장된다.

**수정:** `canSend` 조건에 `(tag?.tagId ?: 0) > 0` 추가

---

### QA-13 · TagDetailScreen LaunchedEffect — tag null 시 초기화 누락

**파일:** `app/.../ui/screen/TagDetailScreen.kt:58-69`

```kotlin
LaunchedEffect(address) {
    tag?.let {   // tag가 null이면 실행 안 됨 — address가 동일하므로 재실행도 없음
        selectedGroupId = it.groupId
        targetPriceInput = ...
    }
}
```

화면 진입 직후 `mergedTags`가 아직 비어있으면 `tag == null`이 되어 초기화가 누락된다. 이후 `tags`가 업데이트되어도 `LaunchedEffect(address)`는 재실행되지 않는다.

**수정:** `LaunchedEffect(address, tag)` 또는 초기화 완료 플래그 사용

---

### QA-14 · BroadcastScreen — 직접 전송 모드 isBroadcasting 동기화 안 됨

**파일:** `app/.../ui/screen/BroadcastScreen.kt:32-33`

```kotlin
var isBroadcasting by remember { mutableStateOf(false) }
```

직접 전송 모드에서 버튼 클릭 시 `isBroadcasting = true`로 설정하지만, 5초 후 브로드캐스트가 자동 종료돼도 `isBroadcasting`은 여전히 `true`를 유지한다. 버튼이 계속 "중지" 상태로 표시된다.

**수정:** ViewModel의 `broadcastQueueState`를 직접 전송 모드에서도 활용해 상태 동기화

---

### QA-15 · needUpdate volatile — ESP32 멀티코어 메모리 배리어 미보장

**파일:** `esp32/sketch_jun13a/sketch_jun13a.ino:59`

```c
static volatile bool needUpdate = false;
```

NimBLE 태스크는 ESP32의 코어 1에서 실행되고 `loop()`는 코어 1(또는 0)에서 실행된다. `volatile`만으로는 멀티코어 캐시 일관성이 보장되지 않는다.

**수정:** `portENTER_CRITICAL`/`portEXIT_CRITICAL` 또는 `atomic_bool` 사용

---

### QA-16 · Preferences begin/end 중첩 호출 가능성 (ESP32)

**파일:** `esp32/sketch_jun13a/sketch_jun13a.ino:89-98, 251-254`

`saveState()`와 `ConfigCallbacks::onWrite`가 각각 `prefs.begin()`을 호출한다. BLE 스캔 콜백(`BroadcastCallbacks::onResult`)과 Config 콜백이 NimBLE 내부에서 동시에 실행될 경우 `prefs.begin` 중첩으로 NVS 핸들이 오염될 수 있다.

**수정:** Preferences 접근을 단일 함수로 캡슐화하고 FreeRTOS Mutex로 보호

---

### QA-17 · 권한 영구 거부 후 복구 불가

**파일:** `app/.../MainActivity.kt:69-71`

사용자가 "다시 묻지 않음"으로 권한을 거부하면 `requestBlePermissions()` 재호출이 무효화되어 앱이 권한 요청 화면에 무한 정체한다.

**수정:** `shouldShowRequestPermissionRationale` 확인 후 거짓이면 앱 설정 화면으로 이동

```kotlin
if (!shouldShowRequestPermissionRationale(Manifest.permission.BLUETOOTH_SCAN)) {
    // 설정 화면으로 이동
    startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, ...))
}
```

---

### QA-18 · 10원 단위 절사 — UI에서 제한 없음

**파일:** `app/.../ui/screen/TagDetailScreen.kt:268-272`

```kotlin
OutlinedTextField(
    value = targetPriceInput,
    onValueChange = { targetPriceInput = it.filter { c -> c.isDigit() } },
    ...
)
```

가격을 10원 단위로 전송하므로 1,501원을 입력하면 1,500원으로 절사된다. UI에서 이를 경고하거나 10원 단위 입력을 강제하지 않는다.

**수정:** 저장 버튼 클릭 시 `price % 10 != 0`이면 "10원 단위로 반올림됩니다" 안내 표시

---

## Medium — 개선 권장

---

### QA-19 · 상품명 72B 초과 입력 시 ESP32에서 조각 무시

**파일:** `app/.../ui/screen/NameUpdateScreen.kt`, `esp32/sketch_jun13a/sketch_jun13a.ino:66`

`MAX_FRAGS=4` → 최대 72B (한글 24자). 앱에서 byte 수를 표시하지만 강제 제한이 없어 73B 이상 입력 시 5번째 조각부터 ESP32가 무시하여 이름이 잘린다.

**수정:** `nameBytes.size > 72`이면 입력 불가 처리 또는 `isError = true`

---

### QA-20 · ensureTagInDb Race Condition — 기존 데이터 덮어쓰기

**파일:** `app/.../viewmodel/ScanViewModel.kt:472-478`

```kotlin
if (dao.getTagByAddress(address) == null) {
    ...
    dao.upsert(tag.toEntity())   // OnConflictStrategy.REPLACE → 기존 row 대체
}
```

`getTagByAddress` 조회와 `upsert` 사이에 다른 코루틴이 동일 address로 먼저 upsert하면, 두 번째 upsert가 `tagId`, `groupId` 등을 스캔 당시 기본값으로 덮어쓴다.

**수정:** `INSERT OR IGNORE` 전략 사용 또는 DB 레벨에서 원자적 처리

---

### QA-21 · targetPrice=0 태그가 영원히 PENDING 유지

**파일:** `app/.../viewmodel/ScanViewModel.kt:390-394`

`readyTags.isEmpty()` (가격 미설정)로 조기 종료 시 FAILED 처리 없이 `BroadcastQueueState.Idle`로 전환. 해당 태그는 영원히 PENDING 상태를 유지하며 Walk-by 쿨다운 이후 스캔할 때마다 skip된다.

**수정:** 가격 미설정 태그에 별도 `UNSET` 상태 추가 또는 그룹 브로드캐스트 시작 전 미설정 태그 목록을 화면에 표시

---

### QA-22 · allocSeqs와 wrapSeq의 순환 경계 논리 불일치

**파일:** `app/.../viewmodel/ScanViewModel.kt:305-311`, `BleManager.kt:385`

```kotlin
// allocSeqs: nextSeq >= 255 이면 1로 순환
// wrapSeq: ((s - 1) % 255) + 1
```

두 함수의 순환 경계가 다른 방식으로 구현돼 있다. 현재는 결과가 우연히 일치하지만 로직 변경 시 Seq 번호 불일치 버그 유발 위험이 높다.

**수정:** 단일 순환 함수로 통일

---

### QA-23 · TopBar title에서 잠재적 null 혼동

**파일:** `app/.../ui/screen/TagDetailScreen.kt:82`

```kotlin
title = { Text(tag?.productName?.ifBlank { "TAG-%03d".format(tag.tagId) } ?: address) }
```

`tag?.productName`이 빈 문자열("")이면 `ifBlank` 람다가 실행되고 `tag.tagId`를 참조한다. `val tag`는 스마트 캐스트로 non-null이 보장되지만, 코드 구조가 혼란스럽다.

**수정:** `tag?.let { if (it.productName.isBlank()) "TAG-%03d".format(it.tagId) else it.productName } ?: address`

---

### QA-24 · NameUpdateScreen — isDone→onBack 중 수동 뒤로가기 시 이중 pop

**파일:** `app/.../ui/screen/NameUpdateScreen.kt:95-99`

```kotlin
LaunchedEffect(isDone) {
    if (isDone) { delay(1200); onBack() }
}
```

`isDone=true` 후 1.2초 딜레이 동안 사용자가 수동으로 뒤로가기 버튼을 누르면 `onBack()`이 두 번 호출되어 의도하지 않은 화면이 닫힐 수 있다.

**수정:** `onBack()` 호출 전 `isActive` 체크 또는 `isDone` 시 수동 뒤로가기 비활성화

---

### QA-25 · 미리보기 패킷이 seq=0으로 빌드

**파일:** `app/.../ui/screen/BroadcastScreen.kt:241`

```kotlin
val payload = BlePackets.buildType02(seq = 0, listOf(sampleEntry))
```

Seq=0은 펌웨어에서 "미수신" 센티넬로 사용된다. 미리보기라도 실제로 전송되지 않도록 명확히 표시해야 한다.

---

### QA-26 · stateCrc 필드에 LastSeq 값을 혼용 — 필드명 불일치

**파일:** `app/.../db/SmartTagDao.kt:76`, `TagDetailScreen.kt:612`

DB 컬럼명 `stateCrc`, DAO 파라미터 `crc: Int`에 실제로는 `lastSeq` 값을 저장한다.  
UI에는 `InfoRow("LastSeq", tag.stateCrc.toString())`으로 표시. 코드 전체에 걸쳐 혼동을 유발한다.

**수정:** DB 컬럼 및 관련 필드명을 `lastSeq`로 통일 (DB 마이그레이션 필요)

---

### QA-27 · 카테고리 삭제 시 연관 태그 groupId 미정리

**파일:** `app/.../viewmodel/ScanViewModel.kt:104-106`

카테고리 삭제 후 해당 groupId를 가진 태그들의 `groupId`가 유지되어 삭제된 카테고리 이름 없이 "Group N"으로 표시된다. PriceUpdateScreen의 카테고리 드롭다운에 해당 그룹이 보이지 않으나, DB에는 여전히 연관 태그들이 존재한다.

---

## Low — 개선 사항

---

### QA-28 · fallbackToDestructiveMigration — 앱 업데이트 시 데이터 삭제

**파일:** `app/.../db/AppDatabase.kt:29`

```kotlin
.fallbackToDestructiveMigration(dropAllTables = true)
```

DB 버전 변경 시 모든 사용자 데이터(태그 설정, 카테고리)가 삭제된다. 실 배포 전 마이그레이션 스크립트 작성 필요.

---

### QA-29 · InfoRow("LastSeq") 사용자 노출 — 내부 구현 정보 노출

**파일:** `app/.../ui/screen/TagDetailScreen.kt:612`

개발 디버그용 정보(`LastSeq`, MAC 주소 등)가 최종 사용자 화면에 노출된다. 릴리즈 빌드에서는 숨기거나 설정 화면으로 이동 권장.

---

### QA-30 · 모든 카테고리 초기 삽입 — 이미 데이터가 있을 때도 실행

**파일:** `app/.../viewmodel/ScanViewModel.kt:87-95`

```kotlin
if (list.isEmpty()) {
    listOf(...).forEach { categoryDao.upsert(it) }
}
```

최초 실행 시 기본 카테고리를 삽입하는 로직은 정상이나, 사용자가 모든 카테고리를 삭제하면 앱 재시작 없이도 기본 카테고리가 다시 삽입된다. 의도된 동작인지 불명확.

---

## 미구현 MVP 기능 현황

| 기능 | CLAUDE.md 명세 | 구현 상태 |
|------|----------------|-----------|
| 태그 스캔 + 상태 배지 | 필수 | ✅ 완료 |
| 개별 가격 수정 (GATT) | 필수 | ✅ 완료 |
| 그룹 일괄 수정 (Broadcast) | 필수 | ✅ 완료 (단, 4개 이상 태그 버그 있음) |
| 상품명 업데이트 (0x04) | 필수 | ✅ 완료 |
| 태그 상태 관리 (PENDING/UPDATED/FAILED) | 필수 | ✅ 완료 |
| StateCRC 기반 동기화 확인 | 필수 | ❌ 미구현 (Seq 기반으로 대체) |
| Type 0x03 Anchor Beacon | 필수 | ❌ 미구현 (RSSI 합산으로 대체) |
| 앵커 태그 자동 구역 전환 | 선택 | ⚠️ 부분 구현 (RSSI 기반 근사치) |
| 이벤트 스케줄러 (WorkManager) | 선택 | ❌ 미구현 |

---

## 수정 우선순위 권고

### 즉시 (배포 전 필수)
1. **QA-01** ESP32 버퍼 오버플로우 — 펌웨어 크래시 위험
2. **QA-04** seqMap.remove 버그 — 3개 이상 태그 브로드캐스트 실패
3. **QA-02** StateFlow 이벤트 유실 — BLE 이벤트 신뢰성 저하

### 단기 (다음 스프린트)
4. **QA-03** Lost Update 경쟁 조건
5. **QA-05** processSeqAck 동시성 문제
6. **QA-08** GATT 리소스 누수
7. **QA-12** tagId=0 이름 전송 방지
8. **QA-13** LaunchedEffect 초기화 누락

### 중기 (기술 부채)
9. **QA-06** StateCRC 구현
10. **QA-07** Anchor Beacon 구현
11. **QA-26** stateCrc → lastSeq 필드명 통일
12. **QA-28** DB 마이그레이션 스크립트 작성
