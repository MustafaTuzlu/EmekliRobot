package com.emekli.robot.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

/**
 * Darbe adayları AYRI bir veritabanı dosyasındadır: gönderim kuyruğunun veritabanı (AppDatabase) değişmez,
 * kuyruk için migrasyon riski yoktur ve eski sürüme geri dönüş mümkün kalır.
 */
@Database(entities = [ImpactEntity::class, ImpactWaveEntity::class], version = 1, exportSchema = false)
abstract class ImpactDatabase : RoomDatabase() {
    abstract fun impactDao(): ImpactDao

    companion object {
        @Volatile
        private var instance: ImpactDatabase? = null

        fun get(context: Context): ImpactDatabase =
            instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext, ImpactDatabase::class.java, "emekli_impacts.db",
                ).build().also { instance = it }
            }
    }
}
