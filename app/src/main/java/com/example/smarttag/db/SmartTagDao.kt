package com.example.smarttag.db

import androidx.room.*
import com.example.smarttag.model.TagStatus
import kotlinx.coroutines.flow.Flow

@Dao
interface SmartTagDao {

    @Query("SELECT * FROM smart_tags ORDER BY rssi DESC")
    fun getAllTags(): Flow<List<SmartTagEntity>>

    @Query("SELECT * FROM smart_tags WHERE deviceAddress = :address LIMIT 1")
    suspend fun getTagByAddress(address: String): SmartTagEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(tag: SmartTagEntity)

    @Query("UPDATE smart_tags SET status = :status, currentPrice = :price WHERE deviceAddress = :address")
    suspend fun updateStatus(address: String, status: TagStatus, price: Int)

    @Query("UPDATE smart_tags SET targetPrice = :price, status = :status WHERE deviceAddress = :address")
    suspend fun setTargetPrice(address: String, price: Int, status: TagStatus = TagStatus.PENDING)

    @Query("DELETE FROM smart_tags WHERE deviceAddress = :address")
    suspend fun delete(address: String)

    @Query("DELETE FROM smart_tags")
    suspend fun deleteAll()
}
