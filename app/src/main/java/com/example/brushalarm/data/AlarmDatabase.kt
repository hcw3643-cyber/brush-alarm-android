package com.example.brushalarm.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverter
import androidx.room.TypeConverters
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

class AlarmConverters {
    @TypeConverter fun fromMode(value: AlarmMode) = value.name
    @TypeConverter fun toMode(value: String) = AlarmMode.valueOf(value)
}

@Database(entities = [AlarmEntity::class], version = 2, exportSchema = false)
@TypeConverters(AlarmConverters::class)
abstract class AlarmDatabase : RoomDatabase() {
    abstract fun alarms(): AlarmDao
    companion object {
        private val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "ALTER TABLE alarms ADD COLUMN weekdays INTEGER NOT NULL DEFAULT 127"
                )
            }
        }
        fun create(context: Context) =
            Room.databaseBuilder(context, AlarmDatabase::class.java, "brush-alarm.db")
                .addMigrations(MIGRATION_1_2)
                .build()
    }
}
