package com.example.smarttag.ble

import com.example.smarttag.model.EventType
import java.nio.ByteBuffer
import java.time.LocalDate
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

// ──────────────────────────────────────────────────────────────
// 데이터 클래스
// ──────────────────────────────────────────────────────────────

/** 0x02 패킷에 담을 태그 1개 분량의 목표 상태 */
data class PriceEntry(
    val tagId: Int,           // 1~255
    val price: Int,           // 원화
    val event: EventType,
    val startDate: LocalDate?,
    val endDate: LocalDate?
)

/** 태그가 방송하는 Type 0x01 파싱 결과 */
data class Type01Data(
    val tagId: Int,
    val price: Int,
    val event: EventType,
    val lastSeq: Int           // 태그가 마지막으로 처리 완료한 Seq (0=없음)
)

// ──────────────────────────────────────────────────────────────
// BlePackets 유틸리티 오브젝트
// ──────────────────────────────────────────────────────────────

object BlePackets {

    // 사전 공유키 (데모 하드코딩 — 펌웨어와 동일해야 함)
    private val HMAC_KEY = "SmartTagDemo2026".toByteArray(Charsets.UTF_8)

    // ── HMAC-SHA256 앞 24bit (3바이트) ────────────────────────
    fun hmac24(data: ByteArray): ByteArray {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(HMAC_KEY, "HmacSHA256"))
        return mac.doFinal(data).copyOf(3)
    }

    // ── 날짜 → 9bit 인코딩: [Month 4bit][Day 5bit], 0=없음 ───
    fun encodeDate(date: LocalDate?): Int {
        if (date == null) return 0
        return ((date.monthValue and 0xF) shl 5) or (date.dayOfMonth and 0x1F)
    }

    // ── 날짜 디코딩: 9bit → LocalDate? ───────────────────────
    fun decodeDate(bits: Int): LocalDate? {
        if (bits == 0) return null
        val month = (bits ushr 5) and 0xF
        val day = bits and 0x1F
        return runCatching { LocalDate.of(2000, month, day) }.getOrNull()
    }

    // ── Entry 48bit 패킹 (6바이트 Big-Endian) ─────────────────
    //  bit 47..40 : TagID  (8bit)
    //  bit 39..20 : Price  (20bit, 10원 단위)
    //  bit 19..18 : Event  (2bit)
    //  bit 17..9  : Start  (9bit)
    //  bit 8..0   : End    (9bit)
    fun packEntry(entry: PriceEntry): ByteArray {
        val priceUnits = (entry.price / 10).coerceIn(0, 0xFFFFF)
        val startBits = encodeDate(entry.startDate).toLong()
        val endBits   = encodeDate(entry.endDate).toLong()

        val long =
            ((entry.tagId.toLong() and 0xFFL) shl 40) or
            ((priceUnits.toLong() and 0xFFFFFL) shl 20) or
            ((entry.event.code.toLong() and 0x3L) shl 18) or
            ((startBits and 0x1FFL) shl 9) or
            (endBits and 0x1FFL)

        // Long(8B) → 상위 2바이트 제거 → 6바이트
        return ByteBuffer.allocate(8).putLong(long).array().copyOfRange(2, 8)
    }

    /** End Marker: TagID 0x00 → 이후 파싱 중단 */
    private val END_MARKER = ByteArray(6)

    // ── Type 0x02 페이로드 빌드 (25B, Company ID 이후) ─────────
    //  [0x02][Seq 1B][Entry×3 18B][HMAC 3B][Rsv 2B] = 25B
    //  HMAC: HMAC(key, [0x02 | Seq | Entry×3]) 앞 24bit
    fun buildType02(seq: Int, entries: List<PriceEntry>): ByteArray {
        val buf = ByteBuffer.allocate(25)
        buf.put(0x02.toByte())
        buf.put(seq.toByte())

        val packedEntries = entries.take(3).map { packEntry(it) }
        packedEntries.forEach { buf.put(it) }
        repeat(3 - packedEntries.size) { buf.put(END_MARKER) }  // End Marker 패딩

        // HMAC: [Type | Seq | Entries] 서명
        val toSign = buf.array().copyOf(buf.position())
        buf.put(hmac24(toSign))
        buf.put(ByteArray(2))  // Reserved
        return buf.array()
    }

    // ── Type 0x04 단편 페이로드 빌드 (25B, Company ID 이후) ────
    //  [0x04][TagID][Seq][Frag][Name 18B][HMAC 3B] = 25B
    //  Frag: [MORE 1bit][Index 7bit]
    //  HMAC: HMAC(key, [0x04 | TagID | Seq | Frag | Name18B]) 앞 24bit
    fun buildType04Fragment(
        tagId: Int,
        seq: Int,
        fragIndex: Int,
        hasMore: Boolean,
        nameChunk: ByteArray   // 최대 18B, 부족하면 0x00 패딩
    ): ByteArray {
        val buf = ByteBuffer.allocate(25)
        buf.put(0x04.toByte())
        buf.put(tagId.toByte())
        buf.put(seq.toByte())
        val fragByte = ((if (hasMore) 1 else 0) shl 7) or (fragIndex and 0x7F)
        buf.put(fragByte.toByte())
        buf.put(nameChunk.copyOf(18))  // 18B 고정

        // HMAC
        val toSign = buf.array().copyOf(buf.position())
        buf.put(hmac24(toSign))
        return buf.array()
    }

    // ── Type 0x01 파싱 (Company ID 이후 8B) ───────────────────
    //  [0x01][TagID][Price 3B LE][Event][LastSeq][Rsvd]
    fun parseType01(mfgData: ByteArray): Type01Data? {
        if (mfgData.size < 8) return null
        if (mfgData[0].toInt() and 0xFF != 0x01) return null
        val tagId = mfgData[1].toInt() and 0xFF
        // Price 3B LE
        val price = (mfgData[2].toInt() and 0xFF) or
                    ((mfgData[3].toInt() and 0xFF) shl 8) or
                    ((mfgData[4].toInt() and 0xFF) shl 16)
        val event   = EventType.fromCode(mfgData[5].toInt() and 0x03)
        val lastSeq = mfgData[6].toInt() and 0xFF
        return Type01Data(tagId, price, event, lastSeq)
    }
}
