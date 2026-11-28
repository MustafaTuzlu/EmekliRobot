package com.emekli.robot.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction

@Dao
abstract class ImpactDao {
    @Query("SELECT * FROM impacts ORDER BY wallMs DESC")
    abstract suspend fun all(): List<ImpactEntity>

    @Query("SELECT * FROM impacts WHERE uuid = :uuid")
    abstract suspend fun byId(uuid: String): ImpactEntity?

    @Query("SELECT * FROM impact_waves WHERE uuid = :uuid")
    abstract suspend fun wave(uuid: String): ImpactWaveEntity?

    @Query("SELECT uuid, wallMs, sizeBytes, keep FROM impacts")
    abstract suspend fun sizes(): List<ImpactSize>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    protected abstract suspend fun put(e: ImpactEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    protected abstract suspend fun putWave(w: ImpactWaveEntity)

    /** Ölçüm alanlarını yazar; kullanıcının seçimi, notu ve "sakla" işareti korunur. */
    @Transaction
    open suspend fun save(e: ImpactEntity, wave: ImpactWaveEntity?) {
        val old = byId(e.uuid)
        val waveBytes = wave?.let { (it.accel.size + (it.gyro?.size ?: 0) + (it.pressure?.size ?: 0)).toLong() }
            ?: this.wave(e.uuid)?.let { (it.accel.size + (it.gyro?.size ?: 0) + (it.pressure?.size ?: 0)).toLong() }
            ?: 0L
        put(
            e.copy(
                wallMs = old?.wallMs ?: e.wallMs,
                sizeBytes = waveBytes + e.gpsJson.length + 512,
                verdict = old?.verdict,
                note = old?.note ?: "",
                keep = old?.keep ?: false,
            ),
        )
        if (wave != null) putWave(wave)
    }

    @Query("UPDATE impacts SET verdict = :verdict, note = :note, keep = :keep WHERE uuid = :uuid")
    abstract suspend fun annotate(uuid: String, verdict: String?, note: String, keep: Boolean)

    @Query("DELETE FROM impacts WHERE uuid IN (:uuids)")
    protected abstract suspend fun deleteRows(uuids: List<String>)

    @Query("DELETE FROM impact_waves WHERE uuid IN (:uuids)")
    protected abstract suspend fun deleteWaves(uuids: List<String>)

    @Transaction
    open suspend fun delete(uuids: List<String>) {
        deleteRows(uuids)
        deleteWaves(uuids)
    }
}
