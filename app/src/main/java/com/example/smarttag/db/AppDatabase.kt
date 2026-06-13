package com.example.smarttag.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverter
import androidx.room.TypeConverters
import com.example.smarttag.model.TagStatus

@Database(entities = [SmartTagEntity::class], version = 1, exportSchema = false)
@TypeConverters(Converters::class)
abstract class AppDatabase : RoomDatabase() {
    abstract fun smartTagDao(): SmartTagDao

    companion object {
        @Volatile private var INSTANCE: AppDatabase? = null

        fun getInstance(context: Context): AppDatabase =
            INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "smarttag.db"
                ).build().also { INSTANCE = it }
            }
    }
}

class Converters {
    @TypeConverter
    fun fromTagStatus(value: TagStatus): String = value.name

    @TypeConverter
    fun toTagStatus(value: String): TagStatus = TagStatus.valueOf(value)
}
