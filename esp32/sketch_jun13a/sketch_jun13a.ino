/*
 * SmartTag BLE Firmware
 * Board : LILYGO LORA32 T3_V1.6.1 (ESP32 + SSD1306)
 * Lib   : NimBLE-Arduino, Adafruit_SSD1306
 *
 * ★ 보드마다 MY_TAG_ID / MY_GROUP_ID 변경
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
// 태그 설정 (보드마다 변경!)
// ─────────────────────────────────────────────────────
#define MY_TAG_ID     2       // 1~255
#define MY_TAG_ID_STR "002"  // MY_TAG_ID와 항상 맞출 것
#define MY_GROUP_ID   1      // 같은 매대는 동일

#define COMPANY_ID   0xFFFF

// ─────────────────────────────────────────────────────
// BLE UUIDs
// ─────────────────────────────────────────────────────
#define SERVICE_UUID    "4fafc201-1fb5-459e-8fcc-c5c9c331914b"
#define PRICE_CHAR_UUID "beb5483e-36e1-4688-b7f5-ea07361b26a8"
#define ACK_CHAR_UUID   "12345678-1234-1234-1234-123456789abc"

// ─────────────────────────────────────────────────────
// HMAC 공유키 (앱과 완전히 동일해야 함)
// ─────────────────────────────────────────────────────
static const uint8_t HMAC_KEY[]   = "SmartTagDemo2026";
static const size_t  HMAC_KEY_LEN = 16;

// ─────────────────────────────────────────────────────
// 전역 상태
// ─────────────────────────────────────────────────────
static uint32_t g_price  = 0;
static uint8_t  g_event  = 0;   // 0=없음 1=1+1 2=2+1 3=할인
static uint8_t  g_startM = 0, g_startD = 0;
static uint8_t  g_endM   = 0, g_endD   = 0;
static char     g_name[64] = "";

static NimBLECharacteristic* pAckChar = nullptr;
static Preferences prefs;
static volatile bool needUpdate = false;  // loop()에서 OLED+Adv 갱신 트리거

// ─────────────────────────────────────────────────────
// 이름 단편 조립 버퍼
// ─────────────────────────────────────────────────────
#define MAX_FRAGS  4
#define FRAG_SIZE 18
static uint8_t nameFrag[MAX_FRAGS][FRAG_SIZE];
static bool    fragRcvd[MAX_FRAGS] = {};
static int     lastFragIdx = -1;

// ─────────────────────────────────────────────────────
// CRC16-CCITT  (poly 0x1021, init 0xFFFF)
// ─────────────────────────────────────────────────────
static uint16_t crc16(const uint8_t* d, size_t len) {
    uint16_t crc = 0xFFFF;
    for (size_t i = 0; i < len; i++) {
        crc ^= (uint16_t)d[i] << 8;
        for (int j = 0; j < 8; j++)
            crc = (crc & 0x8000) ? (crc << 1) ^ 0x1021 : crc << 1;
    }
    return crc;
}

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
// StateCRC = CRC16(Price 4B LE | Event | Start 2B LE | End 2B LE | Name)
// 앱의 BlePackets.calculateTargetCrc() 와 완전히 동일한 계산
// ─────────────────────────────────────────────────────
static uint16_t calcStateCrc() {
    uint8_t buf[4 + 1 + 2 + 2 + sizeof(g_name)];
    size_t off = 0;

    memcpy(buf + off, &g_price, 4);  off += 4;
    buf[off++] = g_event;

    uint16_t s = (uint16_t)(((g_startM & 0xF) << 5) | (g_startD & 0x1F));
    buf[off++] = s & 0xFF;  buf[off++] = s >> 8;

    uint16_t e = (uint16_t)(((g_endM & 0xF) << 5) | (g_endD & 0x1F));
    buf[off++] = e & 0xFF;  buf[off++] = e >> 8;

    size_t nl = strlen(g_name);
    memcpy(buf + off, g_name, nl);  off += nl;

    return crc16(buf, off);
}

// ─────────────────────────────────────────────────────
// NVS 저장
// ─────────────────────────────────────────────────────
static void saveState() {
    prefs.begin("smarttag", false);
    prefs.putUInt("price",  g_price);
    prefs.putUChar("event", g_event);
    prefs.putUChar("sm",    g_startM);
    prefs.putUChar("sd",    g_startD);
    prefs.putUChar("em",    g_endM);
    prefs.putUChar("ed",    g_endD);
    prefs.end();
}

// ─────────────────────────────────────────────────────
// Type 0x01 Advertising 업데이트
// Primary: [CompID 2B][0x01][TagID][Price 4B LE][Event][CRC 2B LE] = 11B
// Scan Response: Service UUID (18B)
// ─────────────────────────────────────────────────────
static void updateAdvertising() {
    uint16_t crc = calcStateCrc();
    uint8_t mfg[11];
    mfg[0] = 0xFF;  mfg[1] = 0xFF;  // Company ID LE
    mfg[2] = 0x01;                   // Type
    mfg[3] = MY_TAG_ID;
    memcpy(mfg + 4, &g_price, 4);
    mfg[8]  = g_event;
    mfg[9]  = crc & 0xFF;
    mfg[10] = crc >> 8;

    NimBLEAdvertising* pAdv = NimBLEDevice::getAdvertising();
    pAdv->stop();
    pAdv->setManufacturerData(std::string((char*)mfg, 11));
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
    snprintf(tagLabel, sizeof(tagLabel), "TAG-%03d", MY_TAG_ID);
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
    display.print("PRICE (KRW)");

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
                (uint8_t)(MY_TAG_ID & 0xFF),
                (uint8_t)(MY_TAG_ID >> 8)
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
        //  [CompID 2B][0x02][GroupID][Entry×3 18B][HMAC 3B][Rsv 2B] = 27B
        if (type == 0x02) {
            if (len < 27) return;
            if (d[3] != MY_GROUP_ID) return;

            // HMAC 검증: sign = d[2..21] (Type+GroupID+Entries = 20B)
            if (!verifyHmac24(d + 2, 20, d + 22)) {
                Serial.println("[0x02] HMAC 불일치 → 폐기");
                return;
            }

            // Entry 3개 파싱 (48bit 빅엔디언 × 3)
            for (int i = 0; i < 3; i++) {
                const uint8_t* ent = d + 4 + i * 6;

                // 48비트 빅엔디언 → uint64_t
                uint64_t v = 0;
                for (int b = 0; b < 6; b++) v = (v << 8) | ent[b];

                uint8_t tagId = (v >> 40) & 0xFF;
                if (tagId == 0x00) break;           // End Marker
                if (tagId != MY_TAG_ID) continue;   // 내 태그 아님

                g_price  = (uint32_t)(((v >> 20) & 0xFFFFF) * 10);
                g_event  = (v >> 18) & 0x03;
                uint16_t sb = (v >> 9) & 0x1FF;
                uint16_t eb = v & 0x1FF;
                g_startM = (sb >> 5) & 0xF;  g_startD = sb & 0x1F;
                g_endM   = (eb >> 5) & 0xF;  g_endD   = eb & 0x1F;

                saveState();
                needUpdate = true;
                Serial.printf("[0x02] Tag%d Price=%lu Event=%d Date=%02d/%02d~%02d/%02d\n",
                              MY_TAG_ID, (unsigned long)g_price, g_event,
                              g_startM, g_startD, g_endM, g_endD);
                break;
            }
            return;
        }

        // ── Type 0x04: 상품명 업데이트 (단편 조립) ───────────────
        //  [CompID 2B][0x04][GroupID][TagID][Frag][Name 18B][HMAC 3B] = 27B
        if (type == 0x04) {
            if (len < 27) return;
            if (d[3] != MY_GROUP_ID) return;
            if (d[4] != MY_TAG_ID)   return;

            // HMAC 검증: sign = d[2..23] (Type+GroupID+TagID+Frag+Name = 22B)
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
            if (!hasMore) lastFragIdx = fragIdx;

            Serial.printf("[0x04] Frag %d %s\n", fragIdx, hasMore ? "(more)" : "(last)");

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

                    // 단편 버퍼 초기화
                    memset(fragRcvd, false, sizeof(fragRcvd));
                    lastFragIdx = -1;

                    needUpdate = true;
                    Serial.printf("[0x04] Name saved: %s\n", g_name);
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

    // BLE 초기화 (NVS보다 먼저 — 순서 중요)
    NimBLEDevice::init("SmartTag-" MY_TAG_ID_STR);

    // NVS에서 이전 상태 복원
    prefs.begin("smarttag", true);
    g_price  = prefs.getUInt("price",  0);
    g_event  = prefs.getUChar("event", 0);
    g_startM = prefs.getUChar("sm",    0);
    g_startD = prefs.getUChar("sd",    0);
    g_endM   = prefs.getUChar("em",    0);
    g_endD   = prefs.getUChar("ed",    0);
    String nameStr = prefs.getString("name", "");
    strncpy(g_name, nameStr.c_str(), sizeof(g_name) - 1);
    prefs.end();
    Serial.printf("[NVS] Price=%lu Event=%d Name=%s\n",
                  (unsigned long)g_price, g_event, g_name);

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

    // NimBLE-Arduino 2.x: pService->start() 는 no-op.
    // 반드시 pServer->start() 를 호출해야 GATT 등록이 완료됨.
    // 스캔/광고 시작 전에 호출해야 함 (mutable 상태).
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
