package com.example.smarttag.db

import androidx.room.*
import com.example.smarttag.model.EventType
import com.example.smarttag.model.TagStatus
import kotlinx.coroutines.flow.Flow
import java.time.LocalDate

@Dao
interface SmartTagDao {

    // ── 조회 ──────────────────────────────────────────────────────

    @Query("SELECT * FROM smart_tags ORDER BY rssi DESC")
    fun getAllTags(): Flow<List<SmartTagEntity>>

    @Query("SELECT * FROM smart_tags WHERE groupId = :groupId ORDER BY tagId ASC")
    fun getTagsByGroup(groupId: Int): Flow<List<SmartTagEntity>>

    @Query("SELECT * FROM smart_tags WHERE groupId = :groupId AND status != 'UPDATED' ORDER BY tagId ASC")
    suspend fun getPendingTagsByGroup(groupId: Int): List<SmartTagEntity>

    @Query("SELECT * FROM smart_tags WHERE deviceAddress = :address LIMIT 1")
    suspend fun getTagByAddress(address: String): SmartTagEntity?

    @Query("SELECT * FROM smart_tags WHERE tagId = :tagId LIMIT 1")
    suspend fun getTagById(tagId: Int): SmartTagEntity?

    // ── 저장 / 삭제 ───────────────────────────────────────────────

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(tag: SmartTagEntity)

    @Query("DELETE FROM smart_tags WHERE deviceAddress = :address")
    suspend fun delete(address: String)

    @Query("DELETE FROM smart_tags")
    suspend fun deleteAll()

    // ── 목표 상태 설정 ────────────────────────────────────────────

    @Query("""
        UPDATE smart_tags
        SET targetPrice = :price,
            targetEvent = :event,
            targetStartDate = :startDate,
            targetEndDate = :endDate,
            status = 'PENDING'
        WHERE deviceAddress = :address
    """)
    suspend fun setTargetState(
        address: String,
        price: Int,
        event: EventType,
        startDate: LocalDate?,
        endDate: LocalDate?
    )

    @Query("UPDATE smart_tags SET tagId = :tagId WHERE deviceAddress = :address")
    suspend fun setTagId(address: String, tagId: Int)

    @Query("UPDATE smart_tags SET groupId = :groupId WHERE deviceAddress = :address")
    suspend fun setGroupId(address: String, groupId: Int)

    @Query("UPDATE smart_tags SET productName = :name WHERE deviceAddress = :address")
    suspend fun setProductName(address: String, name: String)

    // ── 현재 상태 업데이트 (0x01 수신 시) ─────────────────────────

    @Query("""
        UPDATE smart_tags
        SET currentPrice = :price,
            stateCrc = :crc
        WHERE tagId = :tagId
    """)
    suspend fun updateCurrentState(tagId: Int, price: Int, crc: Int)

    @Query("UPDATE smart_tags SET status = :status WHERE tagId = :tagId")
    suspend fun updateStatusById(tagId: Int, status: TagStatus)

    @Query("UPDATE smart_tags SET status = :status WHERE deviceAddress = :address")
    suspend fun updateStatusByAddress(address: String, status: TagStatus)

    // ── 그룹 목표 상태 일괄 설정 ─────────────────────────────────

    /**
     * 그룹 내 모든 태그의 목표 상태를 일괄 설정하고 PENDING으로 전환
     * 브로드캐스트 루프 시작 전 호출
     */
    @Query("""
        UPDATE smart_tags
        SET targetPrice = :price,
            targetEvent = :event,
            targetStartDate = :startDate,
            targetEndDate = :endDate,
            status = 'PENDING'
        WHERE groupId = :groupId
    """)
    suspend fun setTargetStateForGroup(
        groupId: Int,
        price: Int,
        event: EventType,
        startDate: LocalDate?,
        endDate: LocalDate?
    )

    @Query("UPDATE smart_tags SET status = 'PENDING' WHERE groupId = :groupId")
    suspend fun resetGroupStatus(groupId: Int)
}
