/*
 * SmartTag BLE Firmware
 * Board : LILYGO LORA32 T3_V1.6.1 (ESP32 + SSD1306)
 * Lib   : NimBLE-Arduino, Adafruit_SSD1306
 *
 * ★ 보드마다 MY_TAG_ID 변경 (GroupID는 앱 UI 전용, 펌웨어 불필요)
 */

#include <Wire.h>
#include <Adafruit_GFX.h>
#include <Adafruit_SSD1306.h>
#include <NimBLEDevice.h>
#include <Preferences.h>
#include <mbedtls/md.h>

// ─────────────────────────────────────────────────────
// OLED
// ─────────────────────────────────────────────────────
#define OLED_SDA      21
#define OLED_SCL      22
#define SCREEN_WIDTH  128
#define SCREEN_HEIGHT  64
Adafruit_SSD1306 display(SCREEN_WIDTH, SCREEN_HEIGHT, &Wire, -1);

// ─────────────────────────────────────────────────────
// 태그 설정 기본값 (NVS에 저장된 값이 있으면 그 값을 사용)
// ─────────────────────────────────────────────────────
#define TAG_ID_DEFAULT   2    // NVS 미설정 시 사용할 기본 Tag ID

#define COMPANY_ID   0xFFFF

// 이름 단편 조립 상수 (g_name 배열 크기 계산에 사용)
#define MAX_FRAGS  4
#define FRAG_SIZE 18

// ─────────────────────────────────────────────────────
// BLE UUIDs
// ─────────────────────────────────────────────────────
#define SERVICE_UUID    "4fafc201-1fb5-459e-8fcc-c5c9c331914b"
#define PRICE_CHAR_UUID "beb5483e-36e1-4688-b7f5-ea07361b26a8"
#define ACK_CHAR_UUID   "12345678-1234-1234-1234-123456789abc"
#define CONFIG_CHAR_UUID "12345678-1234-1234-1234-123456789def"  // [TagID 1B] Write

// ─────────────────────────────────────────────────────
// HMAC 공유키 (앱과 완전히 동일해야 함)
// ─────────────────────────────────────────────────────
static const uint8_t HMAC_KEY[]   = "SmartTagDemo2026";
static const size_t  HMAC_KEY_LEN = 16;

// ─────────────────────────────────────────────────────
// 전역 상태
// ─────────────────────────────────────────────────────
static uint8_t  g_tagId   = TAG_ID_DEFAULT;    // NVS 로드 후 결정
static uint8_t  g_lastSeq = 0;                 // 마지막으로 처리 완료한 Seq (0=없음)
static uint32_t g_price  = 0;
static uint8_t  g_event  = 0;   // 0=없음 1=1+1 2=2+1 3=할인
static uint8_t  g_startM = 0, g_startD = 0;
static uint8_t  g_endM   = 0, g_endD   = 0;
static char     g_name[MAX_FRAGS * FRAG_SIZE + 1] = "";  // 73B (MAX_FRAGS×FRAG_SIZE 오버플로우 방지)

static NimBLECharacteristic* pAckChar = nullptr;
static Preferences prefs;
static volatile bool needUpdate = false;  // loop()에서 OLED+Adv 갱신 트리거

// ─────────────────────────────────────────────────────
// 이름 단편 조립 버퍼
// ─────────────────────────────────────────────────────
static uint8_t nameFrag[MAX_FRAGS][FRAG_SIZE];
static bool    fragRcvd[MAX_FRAGS] = {};
static int     lastFragIdx = -1;
static uint8_t nameFragSeq = 0;  // 마지막 단편의 Seq

// ─────────────────────────────────────────────────────
// HMAC-SHA256 앞 3바이트 검증
// ─────────────────────────────────────────────────────
static bool verifyHmac24(const uint8_t* data, size_t len, const uint8_t* exp3) {
    uint8_t digest[32];
    mbedtls_md_context_t ctx;
    mbedtls_md_init(&ctx);
    mbedtls_md_setup(&ctx, mbedtls_md_info_from_type(MBEDTLS_MD_SHA256), 1);
    mbedtls_md_hmac_starts(&ctx, HMAC_KEY, HMAC_KEY_LEN);
    mbedtls_md_hmac_update(&ctx, data, len);
    mbedtls_md_hmac_finish(&ctx, digest);
    mbedtls_md_free(&ctx);
    return memcmp(digest, exp3, 3) == 0;
}

// ─────────────────────────────────────────────────────
// NVS 저장
// ─────────────────────────────────────────────────────
static void saveState() {
    prefs.begin("smarttag", false);
    prefs.putUChar("tagId",   g_tagId);
    prefs.putUInt("price",    g_price);
    prefs.putUChar("event",   g_event);
    prefs.putUChar("sm",      g_startM);
    prefs.putUChar("sd",      g_startD);
    prefs.putUChar("em",      g_endM);
    prefs.putUChar("ed",      g_endD);
    prefs.putUChar("lastSeq", g_lastSeq);  // 재부팅 후에도 ACK seq 유지
    prefs.end();
}

// ─────────────────────────────────────────────────────
// Type 0x01 Advertising 업데이트
// [CompID 2B][0x01][TagID][LastSeq] = 5B
// ─────────────────────────────────────────────────────
static void updateAdvertising() {
    uint8_t mfg[5];
    mfg[0] = 0xFF;  mfg[1] = 0xFF;  // Company ID LE
    mfg[2] = 0x01;                   // Type
    mfg[3] = g_tagId;
    mfg[4] = g_lastSeq;

    NimBLEAdvertising* pAdv = NimBLEDevice::getAdvertising();
    pAdv->stop();

    NimBLEAdvertisementData advData;
    advData.setManufacturerData(std::string((char*)mfg, 5));
    pAdv->setAdvertisementData(advData);

    pAdv->start(0);
}

// ─────────────────────────────────────────────────────
// OLED 업데이트
// ─────────────────────────────────────────────────────
static void updateOLED() {
    display.clearDisplay();
    display.setTextColor(WHITE);
    display.setTextSize(1);

    // 상단: 상품명 (없으면 TAG-XXX)
    char tagLabel[12];
    snprintf(tagLabel, sizeof(tagLabel), "TAG-%03d", g_tagId);
    display.setCursor(0, 0);
    display.print(strlen(g_name) > 0 ? g_name : tagLabel);

    display.drawLine(0, 11, 127, 11, WHITE);

    // 이벤트 배지
    if (g_event > 0) {
        const char* evtLabel[] = {"", "1+1", "2+1", "SALE"};
        display.setCursor(0, 14);
        display.print(evtLabel[g_event]);
    }

    // 날짜 표시
    if (g_startM > 0 || g_endM > 0) {
        char db[20];
        if (g_startM > 0 && g_endM > 0)
            snprintf(db, sizeof(db), "%02d/%02d~%02d/%02d",
                     g_startM, g_startD, g_endM, g_endD);
        else if (g_startM > 0)
            snprintf(db, sizeof(db), "from%02d/%02d", g_startM, g_startD);
        else
            snprintf(db, sizeof(db), "~%02d/%02d", g_endM, g_endD);
        display.setCursor(50, 14);
        display.print(db);
    }

    // 가격 레이블
    display.setCursor(0, 26);
    display.print("PRICE ");

    // 가격 (2배 크기)
    display.setTextSize(2);
    if (g_price == 0) {
        display.setCursor(28, 40);
        display.print("---");
    } else {
        // 콤마 포맷 (예: 1500 → "1,500")
        char tmp[12], out[16];
        sprintf(tmp, "%lu", (unsigned long)g_price);
        int len = strlen(tmp);
        int commas = (len - 1) / 3;
        int outLen = len + commas;
        out[outLen] = '\0';
        int j = outLen - 1;
        for (int i = len - 1, cnt = 0; i >= 0; i--, cnt++) {
            if (cnt > 0 && cnt % 3 == 0) out[j--] = ',';
            out[j--] = tmp[i];
        }
        int px = (SCREEN_WIDTH - (int)strlen(out) * 12) / 2;
        display.setCursor(max(0, px), 40);
        display.print(out);

        display.setTextSize(2);
        display.print("$");
    }

    display.display();
}

// ─────────────────────────────────────────────────────
// GATT 콜백
// ─────────────────────────────────────────────────────
class PriceCallbacks : public NimBLECharacteristicCallbacks {
    void onWrite(NimBLECharacteristic* pChar, NimBLEConnInfo& connInfo) override {
        std::string val = pChar->getValue();
        if (val.length() < 4) return;

        memcpy(&g_price, val.data(), 4);
        // GATT 개별 수정은 가격만 → 이벤트/날짜 초기화
        g_event = g_startM = g_startD = g_endM = g_endD = 0;
        saveState();

        Serial.printf("[GATT] Price=%lu\n", (unsigned long)g_price);

        // ACK Notify: [AA][TagID Low][TagID High][Price 4B LE] = 7B
        if (pAckChar) {
            uint8_t ack[7] = {
                0xAA,
                (uint8_t)(g_tagId & 0xFF),
                (uint8_t)(g_tagId >> 8)
            };
            memcpy(ack + 3, &g_price, 4);
            pAckChar->setValue(ack, 7);
            pAckChar->notify();
        }

        needUpdate = true;
    }
};

class ServerCallbacks : public NimBLEServerCallbacks {
    void onDisconnect(NimBLEServer*, NimBLEConnInfo&, int) override {
        // 연결 해제 후 최신 상태로 재광고
        updateAdvertising();
        Serial.println("[GATT] Disconnected, adv restarted");
    }
};

// ─────────────────────────────────────────────────────
// GATT Config 콜백  ([TagID 1B] → NVS 저장 → 재광고)
// ─────────────────────────────────────────────────────
class ConfigCallbacks : public NimBLECharacteristicCallbacks {
    void onWrite(NimBLECharacteristic* pChar, NimBLEConnInfo& connInfo) override {
        std::string val = pChar->getValue();
        if (val.length() < 1) {
            Serial.println("[Config] 패킷 길이 오류 (최소 1B 필요)");
            return;
        }

        uint8_t newTagId = (uint8_t)val[0];

        if (newTagId == 0) {
            Serial.println("[Config] TagID는 0이 될 수 없음 → 무시");
            return;
        }

        g_tagId = newTagId;

        // NVS 저장
        prefs.begin("smarttag", false);
        prefs.putUChar("tagId", g_tagId);
        prefs.end();

        Serial.printf("[Config] TagID=%d → NVS 저장 완료\n", g_tagId);

        needUpdate = true;  // OLED + Advertising 갱신
    }
};

// ─────────────────────────────────────────────────────
// BLE 스캔 콜백  (Type 0x02 가격 업데이트 / Type 0x04 이름 업데이트)
// ─────────────────────────────────────────────────────
class BroadcastCallbacks : public NimBLEScanCallbacks {
    void onResult(const NimBLEAdvertisedDevice* dev) override {
        if (!dev->haveManufacturerData()) return;

        std::string mfg = dev->getManufacturerData();
        size_t len = mfg.length();
        if (len < 4) return;
        const uint8_t* d = (const uint8_t*)mfg.data();

        // Company ID 확인 (LE: 0xFF 0xFF)
        if ((uint16_t)(d[0] | (d[1] << 8)) != COMPANY_ID) return;

        uint8_t type = d[2];

        // ── Type 0x02: 가격/이벤트/날짜 업데이트 ────────────────
        //  [CompID 2B][0x02][Seq][Entry×3 18B][HMAC 3B][Rsv 2B] = 27B
        if (type == 0x02) {
            if (len < 27) return;

            uint8_t rxSeq = d[3];  // Seq 번호

            // HMAC 검증: sign = d[2..21] (Type+Seq+Entries = 20B)
            if (!verifyHmac24(d + 2, 20, d + 22)) {
                Serial.println("[0x02] HMAC 불일치 → 폐기");
                return;
            }

            // Entry 3개 파싱 (48bit 빅엔디언 × 3)
            bool matched = false;
            for (int i = 0; i < 3; i++) {
                const uint8_t* ent = d + 4 + i * 6;

                // 48비트 빅엔디언 → uint64_t
                uint64_t v = 0;
                for (int b = 0; b < 6; b++) v = (v << 8) | ent[b];

                uint8_t tagId = (v >> 40) & 0xFF;
                if (tagId == 0x00) break;           // End Marker
                if (tagId != g_tagId) continue;     // 내 태그 아님

                g_price  = (uint32_t)(((v >> 20) & 0xFFFFF) * 10);
                g_event  = (v >> 18) & 0x03;
                uint16_t sb = (v >> 9) & 0x1FF;
                uint16_t eb = v & 0x1FF;
                g_startM = (sb >> 5) & 0xF;  g_startD = sb & 0x1F;
                g_endM   = (eb >> 5) & 0xF;  g_endD   = eb & 0x1F;

                saveState();
                g_lastSeq = rxSeq;  // ACK용 Seq 기록
                matched = true;
                needUpdate = true;
                Serial.printf("[0x02] Tag%d Seq=%d Price=%lu Event=%d Date=%02d/%02d~%02d/%02d\n",
                              g_tagId, rxSeq, (unsigned long)g_price, g_event,
                              g_startM, g_startD, g_endM, g_endD);
                break;
            }
            (void)matched;
            return;
        }

        // ── Type 0x04: 상품명 업데이트 (단편 조립) ───────────────
        //  [CompID 2B][0x04][TagID][Seq][Frag][Name 18B][HMAC 3B] = 27B
        if (type == 0x04) {
            if (len < 27) return;
            if (d[3] != g_tagId) return;  // TagID 확인

            uint8_t rxSeq = d[4];  // Seq 번호

            // HMAC 검증: sign = d[2..23] (Type+TagID+Seq+Frag+Name = 22B)
            if (!verifyHmac24(d + 2, 22, d + 24)) {
                Serial.println("[0x04] HMAC 불일치 → 폐기");
                return;
            }

            uint8_t fragByte = d[5];
            bool    hasMore  = (fragByte >> 7) & 1;
            int     fragIdx  = fragByte & 0x7F;
            if (fragIdx >= MAX_FRAGS) return;

            memcpy(nameFrag[fragIdx], d + 6, FRAG_SIZE);
            fragRcvd[fragIdx] = true;
            if (!hasMore) {
                lastFragIdx = fragIdx;
                nameFragSeq = rxSeq;  // 마지막 단편 Seq 기록
            }

            Serial.printf("[0x04] Frag %d Seq=%d %s\n", fragIdx, rxSeq, hasMore ? "(more)" : "(last)");

            // 마지막 단편까지 모두 수신되면 조립
            if (lastFragIdx >= 0) {
                bool done = true;
                for (int i = 0; i <= lastFragIdx; i++)
                    if (!fragRcvd[i]) { done = false; break; }

                if (done) {
                    size_t nl = 0;
                    for (int i = 0; i <= lastFragIdx; i++) {
                        memcpy(g_name + nl, nameFrag[i], FRAG_SIZE);
                        nl += FRAG_SIZE;
                    }
                    // 뒤 null 패딩 제거
                    while (nl > 0 && g_name[nl - 1] == '\0') nl--;
                    g_name[nl] = '\0';

                    // NVS 저장
                    prefs.begin("smarttag", false);
                    prefs.putString("name", g_name);
                    prefs.end();

                    g_lastSeq = nameFragSeq;  // ACK용 Seq 기록

                    // 단편 버퍼 초기화
                    memset(fragRcvd, false, sizeof(fragRcvd));
                    lastFragIdx = -1;
                    nameFragSeq = 0;

                    needUpdate = true;
                    Serial.printf("[0x04] Name saved: %s (lastSeq=%d)\n", g_name, g_lastSeq);
                }
            }
        }
    }
};

// ─────────────────────────────────────────────────────
// setup
// ─────────────────────────────────────────────────────
void setup() {
    Serial.begin(115200);
    delay(500);

    // OLED 초기화
    Wire.begin(OLED_SDA, OLED_SCL);
    if (!display.begin(SSD1306_SWITCHCAPVCC, 0x3C)) {
        Serial.println("SSD1306 init failed");
        while (1);
    }
    display.clearDisplay();
    display.setTextSize(1);
    display.setTextColor(WHITE);
    display.setCursor(20, 28);
    display.print("Initializing...");
    display.display();

    // NVS에서 이전 상태 복원 (BLE 초기화 전 tagId 확보 필요)
    prefs.begin("smarttag", true);
    g_tagId   = prefs.getUChar("tagId",   TAG_ID_DEFAULT);
    g_price   = prefs.getUInt("price",   0);
    g_event   = prefs.getUChar("event",  0);
    g_startM  = prefs.getUChar("sm",     0);
    g_startD  = prefs.getUChar("sd",     0);
    g_endM    = prefs.getUChar("em",     0);
    g_endD    = prefs.getUChar("ed",     0);
    g_lastSeq = prefs.getUChar("lastSeq", 0);
    String nameStr = prefs.getString("name", "");
    strncpy(g_name, nameStr.c_str(), sizeof(g_name) - 1);
    prefs.end();
    Serial.printf("[NVS] TagID=%d Price=%lu Event=%d LastSeq=%d Name=%s\n",
                  g_tagId, (unsigned long)g_price, g_event, g_lastSeq, g_name);

    // BLE 초기화 (장치명은 TagID 기반)
    char deviceName[16];
    snprintf(deviceName, sizeof(deviceName), "SmartTag-%03d", g_tagId);
    NimBLEDevice::init(deviceName);

    // GATT Server
    NimBLEServer* pServer = NimBLEDevice::createServer();
    pServer->setCallbacks(new ServerCallbacks());
    NimBLEService* pService = pServer->createService(SERVICE_UUID);

    // Price Char (WRITE)
    NimBLECharacteristic* pPriceChar = pService->createCharacteristic(
        PRICE_CHAR_UUID, NIMBLE_PROPERTY::WRITE
    );
    pPriceChar->setCallbacks(new PriceCallbacks());

    // ACK Char (NOTIFY)
    pAckChar = pService->createCharacteristic(
        ACK_CHAR_UUID, NIMBLE_PROPERTY::NOTIFY
    );

    // Config Char (WRITE) — [TagID 1B]
    NimBLECharacteristic* pConfigChar = pService->createCharacteristic(
        CONFIG_CHAR_UUID, NIMBLE_PROPERTY::WRITE
    );
    pConfigChar->setCallbacks(new ConfigCallbacks());

    // NimBLE-Arduino 2.x: pServer->start() 를 호출해야 GATT 등록이 완료됨.
    pServer->start();

    // Scan Response : Service UUID — Android GATT 연결 필터용
    NimBLEAdvertising* pAdv = NimBLEDevice::getAdvertising();
    NimBLEAdvertisementData scanResp;
    scanResp.addServiceUUID(SERVICE_UUID);
    pAdv->setScanResponseData(scanResp);

    // BLE Scanner (0x02 / 0x04 수신, GATT 서버와 동시 동작)
    NimBLEScan* pScan = NimBLEDevice::getScan();
    pScan->setScanCallbacks(new BroadcastCallbacks());
    pScan->setActiveScan(false);
    pScan->setInterval(100);
    pScan->setWindow(99);
    pScan->start(0, false);

    // 초기 Type 0x01 방송 시작 (스캔 시작 후)
    updateAdvertising();
    updateOLED();

    Serial.println("[Init] Ready!");
}

// ─────────────────────────────────────────────────────
// loop
// ─────────────────────────────────────────────────────
void loop() {
    // 콜백에서 플래그 세팅 → 메인 루프에서 OLED + Adv 갱신
    if (needUpdate) {
        needUpdate = false;
        updateOLED();
        updateAdvertising();
    }
    delay(100);
}
