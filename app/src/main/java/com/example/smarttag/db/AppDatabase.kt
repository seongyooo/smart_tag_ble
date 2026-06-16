package com.example.smarttag.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverter
import androidx.room.TypeConverters
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.example.smarttag.model.EventType
import com.example.smarttag.model.TagStatus
import java.time.LocalDate

@Database(entities = [SmartTagEntity::class, CategoryEntity::class], version = 4, exportSchema = false)
@TypeConverters(Converters::class)
abstract class AppDatabase : RoomDatabase() {
    abstract fun smartTagDao(): SmartTagDao
    abstract fun categoryDao(): CategoryDao

    companion object {
        @Volatile private var INSTANCE: AppDatabase? = null

        val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "ALTER TABLE smart_tags ADD COLUMN targetName TEXT NOT NULL DEFAULT ''"
                )
            }
        }

        fun getInstance(context: Context): AppDatabase =
            INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "smarttag.db"
                )
                .addMigrations(MIGRATION_3_4)
                .fallbackToDestructiveMigration(dropAllTables = true)
                .build()
                .also { INSTANCE = it }
            }
    }
}

class Converters {

    // TagStatus
    @TypeConverter fun fromTagStatus(v: TagStatus): String = v.name
    @TypeConverter fun toTagStatus(v: String): TagStatus = TagStatus.valueOf(v)

    // EventType
    @TypeConverter fun fromEventType(v: EventType): Int = v.code
    @TypeConverter fun toEventType(v: Int): EventType = EventType.fromCode(v)

    // LocalDate? → "MM-dd" 문자열 (연도 불필요, 월/일만 사용)
    @TypeConverter
    fun fromLocalDate(date: LocalDate?): String? =
        date?.let { "%02d-%02d".format(it.monthValue, it.dayOfMonth) }

    @TypeConverter
    fun toLocalDate(value: String?): LocalDate? {
        if (value == null) return null
        val parts = value.split("-")
        if (parts.size != 2) return null
        return runCatching {
            LocalDate.of(2000, parts[0].toInt(), parts[1].toInt())
        }.getOrNull()
    }
}
