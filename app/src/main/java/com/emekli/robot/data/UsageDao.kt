package com.emekli.robot.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction

@Dao
abstract class UsageDao {
    @Query("UPDATE usage SET txBytes = txBytes + :tx, rxBytes = rxBytes + :rx, requests = requests + :n WHERE hourStart = :hour")
    protected abstract suspend fun bump(hour: Long, tx: Long, rx: Long, n: Int): Int

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    protected abstract suspend fun insert(e: UsageEntity): Long

    @Transaction
    open suspend fun add(hour: Long, tx: Long, rx: Long, n: Int) {
        if (bump(hour, tx, rx, n) == 0) insert(UsageEntity(hour, tx, rx, n))
    }

    @Query("SELECT * FROM usage WHERE hourStart >= :from ORDER BY hourStart ASC")
    abstract suspend fun since(from: Long): List<UsageEntity>

    @Query("DELETE FROM usage")
    abstract suspend fun clear()
}
