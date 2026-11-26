package com.emekli.robot.data

import androidx.room.Entity
import androidx.room.PrimaryKey

/** Saatlik veri kullanımı kovası (yerel saat başı, epoch ms). Siteye gönderilmez. */
@Entity(tableName = "usage")
data class UsageEntity(
    @PrimaryKey val hourStart: Long,
    val txBytes: Long,
    val rxBytes: Long,
    val requests: Int,
)
