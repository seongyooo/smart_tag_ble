package com.example.smarttag.db

import androidx.room.*
import kotlinx.coroutines.flow.Flow

@Entity(tableName = "categories")
data class CategoryEntity(
    @PrimaryKey val groupId: Int,   // = SmartTag.groupId (1~255)
    val name: String                // "라면", "음료수", "과자" …
)

@Dao
interface CategoryDao {
    @Query("SELECT * FROM categories ORDER BY groupId ASC")
    fun getAllCategories(): Flow<List<CategoryEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(category: CategoryEntity)

    @Query("DELETE FROM categories WHERE groupId = :groupId")
    suspend fun delete(groupId: Int)
}
