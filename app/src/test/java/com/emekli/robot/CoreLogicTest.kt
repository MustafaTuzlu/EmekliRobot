package com.emekli.robot

import com.emekli.robot.core.Config
import com.emekli.robot.core.Fix
import com.emekli.robot.core.LogTypes
import com.emekli.robot.core.MovementDetector
import com.emekli.robot.core.MovementDetector.Change
import com.emekli.robot.core.Speed
import com.emekli.robot.core.StableValue
import com.emekli.robot.core.ThermalGovernor
import com.emekli.robot.core.ThermalState
import com.emekli.robot.core.TimeFmt
import com.emekli.robot.data.LogEntity
import com.emekli.robot.sync.PayloadBuilder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneId

class CoreLogicTest {

    // ---- StableValue (debounce) ----

    @Test fun stable_commitsOnlyAfterWindow() {
        val v = StableValue(false, 15_000)
        assertFalse(v.update(true, 0))
        assertFalse(v.update(true, 14_999))
        assertTrue(v.update(true, 15_000))
        assertTrue(v.committed)
    }

    @Test fun stable_flickerResetsWindow() {
        val v = StableValue(false, 15_000)
        v.update(true, 0)
        v.update(false, 10_000)          // titreme: eski değere döndü
        assertFalse(v.hasPending)
        v.update(true, 12_000)
        assertFalse(v.update(true, 20_000)) // yalnızca 8 sn sabit
        assertTrue(v.update(true, 27_000))
    }

    // ---- Termal ----

    @Test fun thermal_hysteresis() {
        var s = ThermalState.NORMAL
        s = ThermalGovernor.target(44.9, s); assertEquals(ThermalState.NORMAL, s)
        s = ThermalGovernor.target(45.0, s); assertEquals(ThermalState.ISINMIS, s)
        s = ThermalGovernor.target(43.0, s); assertEquals(ThermalState.ISINMIS, s) // 41 üstü: kal
        s = ThermalGovernor.target(48.0, s); assertEquals(ThermalState.KRITIK, s)
        s = ThermalGovernor.target(46.5, s); assertEquals(ThermalState.KRITIK, s)   // 46 altına inmedi
        s = ThermalGovernor.target(45.9, s); assertEquals(ThermalState.ISINMIS, s)
        s = ThermalGovernor.target(41.0, s); assertEquals(ThermalState.NORMAL, s)
    }

    @Test fun thermal_logTypes() {
        assertEquals(LogTypes.CIHAZ_ISINDI, ThermalGovernor.logTypeFor(ThermalState.NORMAL, ThermalState.ISINMIS))
        assertEquals(LogTypes.CIHAZ_KRITIK_ISINDI, ThermalGovernor.logTypeFor(ThermalState.ISINMIS, ThermalState.KRITIK))
        assertEquals(LogTypes.CIHAZ_KRITIK_ISINDI, ThermalGovernor.logTypeFor(ThermalState.NORMAL, ThermalState.KRITIK))
        assertEquals(LogTypes.CIHAZ_SOGUDU, ThermalGovernor.logTypeFor(ThermalState.ISINMIS, ThermalState.NORMAL))
        assertNull(ThermalGovernor.logTypeFor(ThermalState.KRITIK, ThermalState.ISINMIS))
    }

    // ---- Hareket ----

    @Test fun movement_needsTwoFastSamplesToStart() {
        val m = MovementDetector()
        assertEquals(Change.NONE, m.onSpeed(30.0, 0))
        assertEquals(Change.STARTED, m.onSpeed(31.0, 5_000))
        assertTrue(m.moving)
    }

    @Test fun movement_singleSpikeIgnored() {
        val m = MovementDetector()
        assertEquals(Change.NONE, m.onSpeed(30.0, 0))
        assertEquals(Change.NONE, m.onSpeed(0.0, 5_000))
        assertEquals(Change.NONE, m.onSpeed(30.0, 10_000))
        assertFalse(m.moving)
    }

    @Test fun movement_stopsAfterTwoMinutesSlow() {
        val m = MovementDetector()
        m.onSpeed(40.0, 0); m.onSpeed(40.0, 5_000)
        assertEquals(Change.NONE, m.onSpeed(0.0, 60_000))      // trafik ışığı
        assertEquals(Change.NONE, m.onSpeed(0.0, 124_000))     // 119 sn
        assertEquals(Change.STOPPED, m.onSpeed(0.0, 125_000))  // 120 sn
        assertFalse(m.moving)
    }

    @Test fun movement_fastSampleResetsStopTimer() {
        val m = MovementDetector()
        m.onSpeed(40.0, 0); m.onSpeed(40.0, 5_000)
        m.onSpeed(0.0, 100_000)
        m.onSpeed(20.0, 110_000)                                // tekrar hareket
        assertEquals(Change.NONE, m.onSpeed(0.0, 200_000))      // 90 sn
        assertTrue(m.moving)
    }

    @Test fun movement_unknownSpeedNeverStops() {
        val m = MovementDetector()
        m.onSpeed(40.0, 0); m.onSpeed(40.0, 5_000)
        assertEquals(Change.NONE, m.onSpeed(null, 1_000_000))   // tünel: fix yok
        assertTrue(m.moving)
    }

    // ---- Hız hesabı ----

    @Test fun speed_prefersGpsSpeed() {
        val f = Fix(41.0, 29.0, 5.0, 1_000_000_000L, true, 10.0)
        assertEquals(36.0, Speed.kmh(f, null)!!, 0.001)
    }

    @Test fun speed_computedFromTwoFixes() {
        // ~111 m kuzeye 10 sn'de = ~40 km/s
        val a = Fix(41.0, 29.0, 5.0, 0L, false, 0.0)
        val b = Fix(41.001, 29.0, 5.0, 10_000_000_000L, false, 0.0)
        assertEquals(40.0, Speed.kmh(b, a)!!, 1.0)
    }

    @Test fun speed_unreliableFixGivesNull() {
        val a = Fix(41.0, 29.0, 80.0, 0L, false, 0.0)
        val b = Fix(41.001, 29.0, 80.0, 10_000_000_000L, false, 0.0)
        assertNull(Speed.kmh(b, a))
        assertNull(Speed.kmh(b, null))
    }

    // ---- Zaman ----

    @Test fun time_isoWithOffset() {
        // 2026-11-25T11:03:07Z = İstanbul'da 14:03:07+03:00, kesir atılır
        val epoch = java.time.OffsetDateTime.parse("2026-11-25T11:03:07.987Z").toInstant().toEpochMilli()
        assertEquals("2026-11-25T14:03:07+03:00", TimeFmt.iso(epoch, ZoneId.of("Europe/Istanbul")))
    }

    @Test fun time_isoKeepsZeroSeconds() {
        val epoch = java.time.OffsetDateTime.parse("2026-11-25T11:03:00Z").toInstant().toEpochMilli()
        assertEquals("2026-11-25T14:03:00+03:00", TimeFmt.iso(epoch, ZoneId.of("Europe/Istanbul")))
    }

    // ---- Gönderim gövdesi (API sözleşmesi) ----

    private fun entity(type: String, lat: Double?, lon: Double?) = LogEntity(
        id = 7, uuid = "3f2b8c9e-6a1d-4c7e-9b52-0e8a1d4f7c33", logType = type,
        lat = lat, lon = lon, accuracy = if (lat != null) 6.5 else null,
        batteryPct = 83, charging = true, batteryTempC = 31.44, moving = true,
        eventTime = "2026-11-25T14:03:07+03:00", eventEpochMs = 1L,
        elapsedRealtimeMs = 85_200_000L, locationAgeMs = if (lat != null) 1200L else null,
    )

    @Test fun payload_matchesContract() {
        val body = PayloadBuilder.build(listOf(entity(LogTypes.KONUM, 41.0082376, 28.9783589)), 86_400_000L, "2026-11-25T14:30:00+03:00")
        assertEquals(86_400_000L, body.getLong("gonderimElapsedRealtime"))
        assertEquals("2026-11-25T14:30:00+03:00", body.getString("gonderimZamani"))
        val r = body.getJSONArray("kayitlar").getJSONObject(0)
        assertEquals("EmektarNote9", r.getString("cihazAdi"))
        assertEquals("KONUM", r.getString("logTipi"))
        assertEquals(41.0082376, r.getDouble("enlem"), 1e-9)
        assertEquals(83, r.getInt("pilYuzdesi"))
        assertEquals(true, r.getBoolean("sarjdaMi"))
        assertEquals(31.4, r.getDouble("pilSicakligi"), 1e-9)       // 0.1 hassasiyet
        assertEquals(true, r.getBoolean("aracHareketli"))
        assertEquals("2026-11-25T14:03:07+03:00", r.getString("konumZamani"))
        assertEquals(85_200_000L, r.getLong("elapsedRealtime"))
        assertEquals(1200L, r.getLong("konumYasiMs"))
    }

    @Test fun payload_noLocationSendsNullsTogether() {
        val r = PayloadBuilder.record(entity(LogTypes.KONUM_ALINAMADI, null, null))
        assertTrue(r.isNull("enlem")); assertTrue(r.isNull("boylam"))
        assertTrue(r.isNull("dogruluk")); assertTrue(r.isNull("konumYasiMs"))
        assertNotNull(r.getString("konumZamani"))
    }

    @Test fun payload_halfCoordinatesAreDropped() {
        val r = PayloadBuilder.record(entity(LogTypes.DURUM, 41.0, null))
        assertTrue(r.isNull("enlem")); assertTrue(r.isNull("boylam"))
    }

    @Test fun logTypes_matchServerList() {
        val server = setOf(
            "SARJ_BASLADI", "SARJ_KESILDI", "ARAC_HAREKET", "ARAC_DURUYOR", "KONUM", "DURUM",
            "KONUM_ALINAMADI", "CIHAZ_ISINDI", "CIHAZ_KRITIK_ISINDI", "CIHAZ_SOGUDU",
        )
        assertEquals(server, LogTypes.ALL.toSet())
        assertTrue(LogTypes.ALL.all { t -> t.all { it in 'A'..'Z' || it == '_' } })
    }

    @Test fun config_thresholdsAreConsistent() {
        assertTrue(Config.HOT_OFF_C < Config.HOT_ON_C)
        assertTrue(Config.CRIT_OFF_C < Config.CRIT_ON_C)
        assertTrue(Config.CRIT_OFF_C > Config.HOT_OFF_C)
    }
}
