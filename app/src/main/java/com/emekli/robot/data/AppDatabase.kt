package com.emekli.robot.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(entities = [LogEntity::class, UsageEntity::class], version = 3, exportSchema = false)
abstract class AppDatabase : RoomDatabase() {
    abstract fun logDao(): LogDao
    abstract fun usageDao(): UsageDao

    companion object {
        /** v1 -> v2: hız ve yön sütunları (kuyruktaki eski kayıtlar korunur). */
        private val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE logs ADD COLUMN speedKmh REAL")
                db.execSQL("ALTER TABLE logs ADD COLUMN bearingDeg REAL")
            }
        }

        /** v2 -> v3: saatlik veri kullanımı tablosu. */
        private val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `usage` (`hourStart` INTEGER NOT NULL, `txBytes` INTEGER NOT NULL, " +
                        "`rxBytes` INTEGER NOT NULL, `requests` INTEGER NOT NULL, PRIMARY KEY(`hourStart`))",
                )
            }
        }

        @Volatile
        private var instance: AppDatabase? = null

        fun get(context: Context): AppDatabase =
            instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext, AppDatabase::class.java, "emekli_queue.db",
                ).addMigrations(MIGRATION_1_2, MIGRATION_2_3).build().also { instance = it }
            }
    }
}
