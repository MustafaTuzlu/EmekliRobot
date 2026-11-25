package com.emekli.robot.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface LogDao {
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(entity: LogEntity): Long

    /** Gönderim sırası: en eskiden yeniye. */
    @Query("SELECT * FROM logs WHERE quarantined = 0 ORDER BY id ASC LIMIT :limit")
    suspend fun nextBatch(limit: Int): List<LogEntity>

    @Query("DELETE FROM logs WHERE id IN (:ids)")
    suspend fun deleteByIds(ids: List<Long>)

    @Query("UPDATE logs SET quarantined = 1 WHERE uuid IN (:uuids)")
    suspend fun quarantine(uuids: List<String>)

    @Query("SELECT COUNT(*) FROM logs")
    suspend fun total(): Int

    /** Kuyruk dolarsa en eski KONUM kayıtları silinir; şarj/araç/termal olayları korunur. */
    @Query("DELETE FROM logs WHERE id IN (SELECT id FROM logs WHERE logType = 'KONUM' ORDER BY id ASC LIMIT :count)")
    suspend fun deleteOldestKonum(count: Int)

    @Query("SELECT COUNT(*) FROM logs WHERE quarantined = 0")
    fun pendingCount(): Flow<Int>

    @Query("SELECT * FROM logs ORDER BY id DESC LIMIT 1")
    fun lastRecord(): Flow<LogEntity?>

    /** Bekleyen loglar ekranı: yeniden eskiye, isteğe bağlı tip filtresi. */
    @Query("SELECT * FROM logs WHERE (:type IS NULL OR logType = :type) ORDER BY id DESC LIMIT :limit")
    fun newestFirst(type: String?, limit: Int): Flow<List<LogEntity>>
}
