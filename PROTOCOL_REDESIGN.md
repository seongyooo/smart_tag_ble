# SmartTag BLE — 프로토콜 재설계 & 구현 계획

---

## 1. 확정된 변경 결정

| # | 항목 | 기존 | 변경 | 이유 |
|---|------|------|------|------|
| 1 | Group ID | 0x02·0x04 헤더에 포함 | **제거** | Tag ID 단독 필터로 충분, 1B 확보 |
| 2 | 확보 1B 활용 | — | 0x02: HMAC 3B→**4B** / 0x04: **Seq** 추가 | 보안↑ + ACK 통합 |
| 3 | ACK 방식 | StateCRC (CRC16) | **Seq 번호 에코** (1B) | 구현 단순화 |
| 4 | 0x01 방송 시점 | 항상 방송 | **항상 방송** (변경 없음) | 상시 방송 유지, 브로드캐스트 스톰은 허용된 한계 |
| 5 | 앵커 태그 0x03 | 전용 하드웨어 | **Type 제거** | 가격 태그 RSSI로 구역 감지 대체 |
| 6 | 세션 제어 0x05 | 신규 예정 | **제거** | 상시 방송이므로 Wake/Sleep 불필요 |
| 7 | Config GATT | TagID + GroupID | **TagID만** | ESP32는 GroupID 불필요 |
| 8 | groupId (앱 DB) | ESP32 동기화 필요 | **앱 UI 전용** | 카테고리 분류용, 태그에 미전송 |
| 9 | 구역 감지 | 앵커(0x03) RSSI | **가격 태그(0x01) RSSI 합산** | 별도 하드웨어 불필요 |
| 10 | 수정 트리거 | 수동 세션 시작 | **RSSI 기반 자동** (walk-by) | 관리자가 매대를 지나가기만 해도 자동 업데이트 |

---

## 2. 패킷 구조 정의

### 공통 규칙

- Company ID: `0xFFFF` (2B, 고정)
- 공통 헤더: `[FF FF][Type 1B]` = 3B (기존 4B에서 Group ID 제거)
- HMAC 키: `"SmartTagDemo2026"` 16B (하드코딩, 데모)
- Tag ID: 1~255, 매장 전체에서 **전역 유일**

---

### Type 0x01 — Tag Info · Seq ACK (태그→앱)

```
 0    1    2       3     4   5    6        7      8
┌──────┬─────┬───────┬──────────┬───────┬─────────┬──────┐
│FF FF │0x01 │TagID  │ Price 3B │Event  │LastSeq  │Rsvd  │
│      │     │ 1~255 │  LE      │ 1B    │  1B     │ 1B   │
└──────┴─────┴───────┴──────────┴───────┴─────────┴──────┘
총 10B  (Price 4B→3B, 최대 16,777,215원 ≈ 1,677만원)
```

- **LastSeq**: 태그가 마지막으로 처리 완료한 패킷의 Seq 번호
  - 0x02 수신 후 가격 NVS 저장 완료 → 해당 Seq 기록
  - 0x04 마지막 단편 수신 후 이름 조립·저장 완료 → 해당 Seq 기록
- **Rsvd**: 미정 (현재 0x00)
- **동작 시점**: **항상** 방송 (조건 없음)

---

### Type 0x02 — Price Update (앱→태그)

```
 0    1    2     3       4 ──────────────────── 21   22 ── 24   25  26
┌──────┬─────┬─────┬──────────────────────────┬──────────┬────────┐
│FF FF │0x02 │Seq  │      Entry × 3 (18B)     │ HMAC 3B  │Rsvd 2B │
│      │     │ 1B  │       48bit × 3          │  24bit   │        │
└──────┴─────┴─────┴──────────────────────────┴──────────┴────────┘
                    ↑ 변경 (GroupID→Seq)
총 27B  ✓
```

**HMAC 범위**: `HMAC(key, [0x02 | Seq | Entry×3])` 앞 24bit

**Entry 구조 (48bit = 6B, 변경 없음)**:

```
bit  47     40  39          20  19 18  17    9  8     0
    ┌─────────┬─────────────┬──────┬────────┬────────┐
    │ Tag ID  │    Price    │ Evt  │ Start  │  End   │
    │  8bit   │   20bit     │ 2bit │  9bit  │  9bit  │
    └─────────┴─────────────┴──────┴────────┴────────┘
    Tag ID 0x00 = End Marker (3개 미만 시 패딩)
    Price: 10원 단위 (원화 / 10)
    Evt:   00=없음 / 01=1+1 / 10=2+1 / 11=할인
    Start/End: [Month 4bit][Day 5bit], 0=없음
```

---

### Type 0x03 — Anchor Beacon → **완전 제거**

가격 태그(0x01) RSSI로 구역 감지 대체. 관련 코드 전부 삭제.

---

### Type 0x04 — Name Update (앱→태그)

```
 0    1    2       3     4      5       6 ────────── 23   24 ── 26
┌──────┬─────┬───────┬─────┬──────┬────────────────┬──────────┐
│FF FF │0x04 │TagID  │Seq  │Frag  │  Name 18B      │ HMAC 3B  │
│      │     │ 1~255 │ 1B  │ 1B   │ UTF-8/0x00패딩 │  24bit   │
└──────┴─────┴───────┴─────┴──────┴────────────────┴──────────┘
                            ↑ 변경 (GroupID→Seq 위치 조정)
총 27B  ✓
```

**HMAC 범위**: `HMAC(key, [0x04 | TagID | Seq | Frag | Name18B])` 앞 24bit

**Frag 바이트**: `[MORE 1bit][Index 7bit]`

---

### Type 0x05 — Session Control → **완전 제거**

태그 상시 방송 채택으로 Wake/Sleep 세션 제어 불필요. 관련 코드 없음.

---

## 3. Walk-by 자동 업데이트 흐름

```
[항상]
  태그: 0x01 상시 방송 (현재 가격 + lastSeq)
  앱  : BLE 스캔 중

[관리자 사전 작업]
  앱에서 태그별 목표 가격·이벤트·이름 입력 → DB에 PENDING 저장

[관리자가 매대를 지나갈 때]
  앱 → 0x01 수신 (TagID, RSSI, lastSeq)
      → RSSI ≥ 임계값(-75 dBm)? → "근처 태그"로 판정
      → DB에서 해당 TagID PENDING 상태 확인
      → PENDING이면 0x02 브로드캐스트 자동 트리거

  태그 → 0x02 수신 → 내 TagID 매칭 → NVS 저장 → lastSeq 갱신
       → 다음 0x01 방송에 lastSeq 포함

  앱 → 0x01 수신 (lastSeq=N)
      → seqMap[N]에 TagID 포함? → UPDATED 처리 ✅

[다음 매대로 이동 → 반복]
```

---

## 4. 구역 자동 감지 (RSSI 합산)

0x01 수신된 태그들의 앱 groupId(카테고리) 기준으로 실시간 계산.

```kotlin
const val RSSI_THRESHOLD = -75  // dBm, 이 값 미만은 "원거리" 제외

val score = visibleTags
    .filter { it.groupId > 0 && it.rssi >= RSSI_THRESHOLD }
    .groupBy { it.groupId }
    .mapValues { (_, tags) ->
        tags.sumOf { (it.rssi + 100).coerceAtLeast(0) }
    }

val detectedZone = score.maxByOrNull { it.value }?.key
```

- Category(groupId)는 앱 UI 조직화 전용 — 태그 필터링에 사용 안 함
- 구역 Chip으로 수동 오버라이드 가능

---

## 5. Seq 번호 ACK 동작

```
앱 측
  nextSeq: Int = 1  (1~255 순환, 0은 "미수신" 센티넬)
  seqMap:  Map<Int, List<Int>>   // seq → [tagId, ...]
  nameSeq: Map<Int, Int>         // tagId → 마지막 단편 Seq

0x02 전송 시:
  seq = nextSeq++
  seqMap[seq] = entries.map { it.tagId }
  broadcast(seq, entries)

0x04 전송 시 (N개 단편):
  각 단편마다 seq = nextSeq++ 부여
  nameSeq[tagId] = 마지막 단편의 seq

0x01 수신 시 (tagId, lastSeq):
  seqMap[lastSeq]에 tagId 포함 → 가격 ACK → UPDATED
  nameSeq[tagId] == lastSeq     → 이름 ACK → UPDATED
```

---

## 6. Walk-by 자동 트리거 세부 로직

```kotlin
// 중복 전송 방지: tagId별 마지막 전송 시각
private val lastBroadcastTime = mutableMapOf<Int, Long>()
private const val REBROADCAST_COOLDOWN_MS = 5000L

fun onTag01Received(tagId: Int, lastSeq: Int, rssi: Int) {
    checkSeqAck(tagId, lastSeq)   // ACK 확인 먼저

    if (rssi < RSSI_THRESHOLD) return   // 원거리 태그 무시

    val now = System.currentTimeMillis()
    val lastSent = lastBroadcastTime[tagId] ?: 0L
    if (now - lastSent < REBROADCAST_COOLDOWN_MS) return  // 쿨다운 중

    val tag = dao.getTagByTagId(tagId) ?: return
    if (tag.status != TagStatus.PENDING) return

    lastBroadcastTime[tagId] = now
    scheduleBroadcast(tag)   // 브로드캐스트 큐에 추가
}
```

브로드캐스트 스톰 완화 장치: **동일 TagID 5초 내 재전송 금지**

---

## 7. 구현 Phase

### Phase 1 — BlePackets.kt (코덱, 최우선)

| 대상 | 변경 |
|------|------|
| `Type01Data` | `stateCrc` → `lastSeq: Int` |
| `buildType02(groupId, entries)` | → `buildType02(seq, entries)`, HMAC 4B |
| `buildType04Fragment(groupId, tagId, ...)` | → `buildType04Fragment(tagId, seq, ...)` |
| `buildType05()` | **없음** (제거됨) |
| `parseType01()` | lastSeq 파싱 |
| `parseType03()` | **삭제** |
| `calculateTargetCrc()` | **삭제** |
| `crc16()` | **삭제** (사용처 없음) |
| `hmac24()` | 유지 (0x02·0x04·공통 3B) |
| `hmac32()` | **추가 안 함** (HMAC 3B 고정) |

---

### Phase 2 — ESP32 펌웨어 (Phase 1과 병렬 가능)

**삭제**
- `g_groupId`, NVS `"groupId"` 키
- `g_awake`, `g_wakeTime` (세션 상태 변수)
- 0x02·0x04 파서의 GroupID 체크 라인
- 0x05 파서 (처음부터 추가 안 함)
- `loop()` 타임아웃 체크 (세션 없으므로 불필요)

**추가**
- `g_lastSeq = 0` — 마지막 처리 Seq
- `verifyHmac24()` — 0x02·0x04 공통 3B HMAC 검증

**수정**
- Config Char: `[TagID 1B][GroupID 1B]` → `[TagID 1B]`
- 0x01 광고: StateCRC → LastSeq + Rsvd (**조건 없이 항상 포함**)
- 0x02 파서: GroupID 라인 삭제, `d[3]`→Seq, HMAC 4B 검증
- 0x04 파서: 오프셋 조정 (`d[3]`→TagID, `d[4]`→Seq, `d[5]`→Frag)

---

### Phase 3 — BleManager.kt (Phase 1 완료 후)

| 대상 | 변경 |
|------|------|
| 스캔 필터 | 0x03 필터 제거 |
| `BleEvent` | `AnchorDetected` 삭제 / `SessionStarted` **추가 안 함** |
| `BleEvent.ConfigWriteSuccess` | `groupId` 파라미터 제거 |
| `broadcastPriceUpdate(groupId, entries)` | → `(seq, entries)` |
| `broadcastNameUpdate(groupId, tagId, ...)` | groupId 제거, startSeq 추가 |
| `connectAndWriteConfig(address, tagId, groupId)` | → `(address, tagId)`, 1바이트 전송 |
| `sendWake()` / `sendSleep()` | **추가 안 함** |
| `updateTagIdInDiscovered(address, tagId, groupId)` | groupId 유지 (앱 in-memory 조직화용) |
| `scanCallback` | 0x03 케이스 제거 |

---

### Phase 4 — ScanViewModel.kt (Phase 3 완료 후)

**삭제**
- `handleAnchorDetected()`, `anchorRssiMap`, `anchorLastSeen`, `anchorDebounceJob`
- `checkAndConfirmSync()`
- `_sessionState`, `startSession()`, `stopSession()` (세션 없음)

**추가**
- `nextSeq = 1`, `seqMap`, `nameSeq` — Seq 관리
- `lastBroadcastTime: Map<Int, Long>` — 쿨다운 추적
- `checkSeqAck(tagId, lastSeq)` — ACK 확인
- `_currentGroupId` 실시간 갱신 — 0x01 수신마다 RSSI 점수 재계산
- walk-by 자동 트리거 로직 (0x01 수신 시 RSSI + PENDING 체크)

**수정**
- `observeBleEvents()` — `TagInfoReceived` 핸들러에 walk-by 트리거 추가
- `BleEvent.AnchorDetected` 핸들러 제거
- `BleEvent.ConfigWriteSuccess` 핸들러 — groupId 제거
- `startGroupBroadcast(groupId, price)` — Seq 부여 로직 추가, groupId는 DB 조회용으로 유지
- `broadcastDirect(groupId, tagId, price)` — groupId 제거 (TagID만으로 전송)
- `broadcastTagName(address, groupId, tagId, name)` — groupId 제거, Seq 내부 관리
- `writeConfig(address, tagId, groupId)` — groupId 제거

---

### Phase 5 — DB / 모델 (변경 없음)

`SmartTagEntity.groupId`는 앱 조직화 전용으로 현행 유지.
`SmartTagEntity.stateCrc`는 구조 유지, 앱에서 더 이상 갱신 안 함 (항상 0).

---

### Phase 6 — UI

| 화면 | 변경 |
|------|------|
| `ScanScreen.kt` | "수정 시작"/"수정 완료" 버튼 **제거**, 앵커 관련 텍스트 수정, 구역 Chip 수동 오버라이드 유지 |
| `TagDetailScreen.kt` | Config Card에서 GroupID 입력 제거 (TagID만 전송), "CRC" InfoRow → "LastSeq"로 변경 |
| `NameUpdateScreen.kt` | GroupID 입력 제거 (앱 DB에서 자동 조회) |
| `BroadcastScreen.kt` | `groupId` → `seq` 파라미터 변경 (패킷 미리보기 수정) |
| `CategoryScreen.kt` | 변경 없음 |

---

## 8. 파일별 변경 규모

```
esp32/
└── sketch_jun13a.ino        ★★★  파서 구조 수정, 세션 로직 제거

app/src/main/java/com/example/smarttag/
├── ble/
│   ├── BlePackets.kt        ★★★  코덱 전면 재작성
│   └── BleManager.kt        ★★   Seq 파라미터, 0x03 제거, 세션 관련 제거
├── viewmodel/
│   └── ScanViewModel.kt     ★★   Walk-by 트리거, Seq ACK, 세션 로직 제거
└── ui/screen/
    ├── ScanScreen.kt        ★    세션 버튼 제거, 텍스트 수정
    ├── TagDetailScreen.kt   ★    GroupID 입력 제거, CRC→LastSeq
    ├── NameUpdateScreen.kt  ★    GroupID 입력 제거
    └── BroadcastScreen.kt   ★    groupId→seq 파라미터 변경
```

---

## 9. 구현 순서

```
[1단계] Phase 1 (BlePackets) ──────┐
[1단계] Phase 2 (ESP32)            │ 병렬 가능
                                   ↓
[2단계] Phase 3 (BleManager) ──────┐
                                   ↓
[3단계] Phase 4 (ViewModel) ───────┐
                                   ↓
[4단계] Phase 5/6 (DB · UI)
```

---

## 10. 허용된 한계점

- 태그 상시 방송 → 채널 혼잡 (다수 태그 밀집 시 수신 신뢰성 저하)
- 관리자가 빠르게 이동 시 ACK 미수신 → PENDING 잔존 (재방문 시 자동 재전송)
- 브로드캐스트 스톰 미완화 (설계상 허용)

---

## 11. 미결 사항

| 항목 | 현재 방침 | 결정 필요 |
|------|----------|----------|
| RSSI 임계값 | -75 dBm | 실기기 테스트 후 조정 |
| 재전송 쿨다운 | 5초 | 조정 가능 |
| Seq 초기값 | 앱 시작 시 1 | 랜덤 시작 여부 |
| 이름 단편 누락 재전송 | 처음부터 재전송 | 누락 단편만 재전송 가능 |
| 0x01 Rsvd 1B | 미사용 (0x00) | 배터리 잔량 등 활용 가능 |
