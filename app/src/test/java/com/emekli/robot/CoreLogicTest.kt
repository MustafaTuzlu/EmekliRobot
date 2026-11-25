package com.emekli.robot

import com.emekli.robot.core.Config
import com.emekli.robot.core.Fix
import com.emekli.robot.core.FixFilter
import com.emekli.robot.core.LogTypes
import com.emekli.robot.core.MovementDetector
import com.emekli.robot.core.MovementDetector.Change
import com.emekli.robot.core.Pos
import com.emekli.robot.core.Speed
import com.emekli.robot.core.StableValue
import com.emekli.robot.core.ThermalGovernor
import com.emekli.robot.core.ThermalState
import com.emekli.robot.core.TimeFmt
import com.emekli.robot.data.LogEntity
import com.emekli.robot.sync.PayloadBuilder
import com.emekli.robot.sync.SyncPolicy
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

    // ---- Yer değiştirme kuralı (50 m) ----

    /** Enlem farkı: 0.00045° ≈ 50 m. */
    private fun at(northM: Double, acc: Double = 10.0) = Pos(41.0 + northM / 111_195.0, 29.0, acc)

    @Test fun displacement_50mIsMovementImmediately() {
        val m = MovementDetector()
        assertEquals(Change.NONE, m.onFix(0.0, at(0.0), 0))          // referans noktası
        assertEquals(Change.NONE, m.onFix(3.0, at(40.0), 15_000))    // 40 m: henüz değil
        assertEquals(Change.STARTED, m.onFix(3.0, at(55.0), 30_000)) // 55 m, tek örnek
        assertTrue(m.moving)
    }

    @Test fun displacement_worksAtTrafficSpeedBelow5kmh() {
        val m = MovementDetector()
        m.onFix(0.0, at(0.0), 0)
        // 4 km/h, 60 sn'de ~67 m: hız eşiğin altında ama yer değiştirme var
        assertEquals(Change.STARTED, m.onFix(4.0, at(67.0), 60_000))
    }

    @Test fun displacement_gpsJumpAtZeroSpeedIsIgnored() {
        val m = MovementDetector()
        m.onFix(0.0, at(0.0), 0)
        // Park halinde GPS 80 m zıpladı ama Doppler hızı ~0: hareket sayılmaz
        assertEquals(Change.NONE, m.onFix(0.3, at(80.0), 5_000))
        assertFalse(m.moving)
    }

    @Test fun displacement_poorAccuracyFixIsNotReliable() {
        val m = MovementDetector()
        m.onFix(0.0, at(0.0), 0)
        assertEquals(Change.NONE, m.onFix(null, at(200.0, acc = 120.0), 5_000)) // doğruluk 120 m
        assertFalse(m.moving)
    }

    @Test fun displacement_thresholdGrowsWithAccuracy() {
        val m = MovementDetector()
        m.onFix(0.0, at(0.0, acc = 40.0), 0)
        // 60 m ama doğruluk toplamı 70 m: sapma olabilir
        assertEquals(Change.NONE, m.onFix(null, at(60.0, acc = 30.0), 5_000))
        assertEquals(Change.STARTED, m.onFix(null, at(75.0, acc = 30.0), 10_000))
    }

    @Test fun displacement_unknownSpeedStillCounts() {
        val m = MovementDetector()
        m.onFix(null, at(0.0), 0)
        assertEquals(Change.STARTED, m.onFix(null, at(60.0), 10_000))
    }

    @Test fun displacement_anchorDoesNotFollowSlowDrift() {
        val m = MovementDetector()
        m.onFix(1.0, at(0.0), 0)
        // 3 km/h ile ilerliyor: her fix önceki fix'e yakın (12 m) ama referans sabit
        var startedAtStep = -1
        for (i in 1..6) {
            if (m.onFix(3.0, at(i * 12.0), i * 15_000L) == Change.STARTED) startedAtStep = i
        }
        assertEquals(5, startedAtStep) // 5. adımda referanstan 60 m uzaklaşıldı
        assertTrue(m.moving)
    }

    @Test fun displacement_slowTrafficDoesNotStopWhileProgressing() {
        val m = MovementDetector()
        m.onFix(0.0, at(0.0), 0)
        m.onFix(4.0, at(60.0), 15_000)            // STARTED
        // 4 km/h ile 5 dk daha ilerliyor: hız hep < 5 ama her ~45 sn'de 50 m ilerleme var
        var stopped = false
        var north = 60.0
        for (i in 1..20) {
            north += 17.0
            if (m.onFix(4.0, at(north), 15_000L + i * 15_000L) == Change.STOPPED) stopped = true
        }
        assertFalse(stopped)
        assertTrue(m.moving)
    }

    @Test fun displacement_stopsWhenNeitherFastNorProgressing() {
        val m = MovementDetector()
        m.onFix(0.0, at(0.0), 0)
        m.onFix(40.0, at(100.0), 5_000); m.onFix(40.0, at(200.0), 10_000)   // hızlı, STARTED
        assertTrue(m.moving)
        assertEquals(Change.NONE, m.onFix(0.0, at(200.0), 100_000))
        assertEquals(Change.STOPPED, m.onFix(0.0, at(201.0), 135_000))      // 2 dk dolmuş, ilerleme yok
        assertFalse(m.moving)
    }

    @Test fun displacement_newAnchorIsWhereItStopped() {
        val m = MovementDetector()
        m.onFix(0.0, at(0.0), 0)
        m.onFix(40.0, at(100.0), 5_000); m.onFix(40.0, at(200.0), 10_000)
        m.onFix(0.0, at(200.0), 20_000)
        m.onFix(0.0, at(200.0), 140_000)                                    // STOPPED, referans = 200 m
        assertFalse(m.moving)
        assertEquals(Change.NONE, m.onFix(0.2, at(230.0), 150_000))         // 30 m: hareket değil
        assertEquals(Change.STARTED, m.onFix(3.0, at(260.0), 160_000))      // 60 m
    }

    // ---- Konum kalite filtresi (gerçek saha verisiyle) ----

    private fun fx(lat: Double, lon: Double, acc: Double, tSec: Double) =
        Fix(lat, lon, acc, (tSec * 1e9).toLong(), false, 0.0)

    @Test fun filter_networkEstimateIsRejected() {
        // Saha kaydı 59: doğruluk 200 m (Wi-Fi/baz tahmini), gerçekte gidilmeyen yer
        val prev = fx(41.0015356, 28.8711493, 8.0, 0.0)
        val bad = fx(40.9958775, 28.8653239, 200.0, 23.0)
        assertEquals(FixFilter.Verdict.POOR_ACCURACY, FixFilter.check(prev, bad))
    }

    @Test fun filter_accuracyBoundary() {
        assertEquals(FixFilter.Verdict.OK, FixFilter.check(null, fx(41.0, 29.0, 50.0, 1.0)))
        assertEquals(FixFilter.Verdict.POOR_ACCURACY, FixFilter.check(null, fx(41.0, 29.0, 50.1, 1.0)))
        assertEquals(FixFilter.Verdict.POOR_ACCURACY, FixFilter.check(null, fx(41.0, 29.0, 9999.0, 1.0)))
    }

    @Test fun filter_impossibleJumpBetweenGoodFixesIsRejected() {
        // ~800 m / 23 sn = ~125 km/s ama yolun gerçek hızı düşük; iki fix de "iyi" doğruluklu
        val prev = fx(41.0015356, 28.8711493, 8.0, 0.0)
        val jump = fx(40.9958775, 28.8653239, 8.0, 23.0)
        assertEquals(FixFilter.Verdict.OK, FixFilter.check(prev, fx(41.0017, 28.8711493, 8.0, 15.0))) // 18 m: normal
        assertEquals(FixFilter.Verdict.IMPLAUSIBLE_JUMP, FixFilter.check(prev, fx(40.9908, 28.8711493, 8.0, 10.0))) // ~1.2 km / 10 sn
        // 800 m / 23 sn = 125 km/s sınırın altında kaldığı için kabul edilir (150 km/s eşiği)
        assertEquals(FixFilter.Verdict.OK, FixFilter.check(prev, jump))
    }

    @Test fun filter_longGapIsNotAJump() {
        val prev = fx(41.0015356, 28.8711493, 8.0, 0.0)
        // GPS kapalıyken araç 800 m gitti, 10 dk sonra ilk fix: sıçrama değil
        assertEquals(FixFilter.Verdict.OK, FixFilter.check(prev, fx(40.9958775, 28.8653239, 8.0, 600.0)))
    }

    @Test fun filter_sameFixDeliveredTwiceIsDuplicate() {
        // Saha kayıtları 46/47 ve 74/75: ARAC_HAREKET'ten hemen sonra aynı konum KONUM olarak tekrar yazılmıştı
        val a = fx(41.0058384, 28.8701308, 7.0, 100.0)
        assertEquals(FixFilter.Verdict.DUPLICATE, FixFilter.check(a, a.copy()))
        assertEquals(FixFilter.Verdict.OK, FixFilter.check(a, a.copy(elapsedNanos = a.elapsedNanos + 15_000_000_000L)))
    }

    @Test fun movement_poorFixesDoNotKeepVehicleMoving() {
        // Saha: kötü doğruluklu sıçrama konumlar "ilerleme" sayılıp ARAC_DURUYOR'u engelliyordu
        val m = MovementDetector()
        m.onFix(0.0, at(0.0), 0)
        m.onFix(40.0, at(100.0), 5_000); m.onFix(40.0, at(200.0), 10_000)
        assertTrue(m.moving)
        assertEquals(Change.NONE, m.onFix(0.0, at(1_000.0, acc = 100.0), 100_000))
        assertEquals(Change.STOPPED, m.onFix(0.0, at(2_000.0, acc = 100.0), 135_000))
        assertFalse(m.moving)
    }

    @Test fun stable_committedSinceIsFirstObservation() {
        val v = StableValue(false, 15_000)
        v.update(true, 1_000)       // ilk gözlem
        v.update(true, 9_000)
        assertTrue(v.update(true, 16_500))
        assertEquals(1_000L, v.committedSince)  // kayıt 15 sn geç değil, ilk görüldüğü ana çekilir
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

    // ---- Gönderim kararı ----

    private fun decide(t: ThermalState, online: Boolean, urgent: Boolean, since: Long) =
        SyncPolicy.decide(t, online, urgent, since)

    @Test fun syncPolicy_normalSendsImmediatelyWhenGapPassed() {
        assertEquals(SyncPolicy.Decision.NOW, decide(ThermalState.NORMAL, true, false, 61_000))
        assertEquals(SyncPolicy.Decision.LATER, decide(ThermalState.NORMAL, true, false, 20_000))
        assertEquals(40_000L, SyncPolicy.delayMs(20_000))
    }

    @Test fun syncPolicy_offlineNeverSends() {
        assertEquals(SyncPolicy.Decision.SKIP, decide(ThermalState.NORMAL, false, false, 999_000))
        assertEquals(SyncPolicy.Decision.SKIP, decide(ThermalState.NORMAL, false, true, 999_000))
    }

    @Test fun syncPolicy_hotDeviceHoldsNormalRecords() {
        assertEquals(SyncPolicy.Decision.SKIP, decide(ThermalState.ISINMIS, true, false, 999_000))
        assertEquals(SyncPolicy.Decision.SKIP, decide(ThermalState.KRITIK, true, false, 999_000))
    }

    @Test fun syncPolicy_heatAlertGoesOutEvenWhenHotAndWithinGap() {
        assertEquals(SyncPolicy.Decision.NOW, decide(ThermalState.ISINMIS, true, true, 1_000))
        assertEquals(SyncPolicy.Decision.NOW, decide(ThermalState.KRITIK, true, true, 0))
    }

    @Test fun syncPolicy_onlyHeatAlertTypesAreUrgent() {
        assertTrue(SyncPolicy.isUrgentType(LogTypes.CIHAZ_ISINDI))
        assertTrue(SyncPolicy.isUrgentType(LogTypes.CIHAZ_KRITIK_ISINDI))
        assertFalse(SyncPolicy.isUrgentType(LogTypes.CIHAZ_SOGUDU))   // NORMAL'e dönünce zaten gönderilir
        assertFalse(SyncPolicy.isUrgentType(LogTypes.KONUM))
        assertFalse(SyncPolicy.isUrgentType(LogTypes.DURUM))
    }

    @Test fun config_probeWindowsFitTheirPeriods() {
        // Şarjlı yoklama penceresi, iki yoklama arasına sığmalı; GPS sürekli açık kalmasın
        assertTrue(Config.PLUGGED_PROBE_WINDOW_MS < Config.PLUGGED_PROBE_PERIOD_MS)
        assertTrue(Config.FALLBACK_PROBE_WINDOW_MS < Config.FALLBACK_PROBE_MS)
        // Hız için en az 2 örnek gerekir (PROBE_INTERVAL_MS aralıkla)
        assertTrue(Config.PLUGGED_PROBE_WINDOW_MS >= Config.PROBE_INTERVAL_MS * (Config.START_CONFIRM_SAMPLES + 1))
        assertTrue(Config.HEARTBEAT_PROBE_WINDOW_MS >= Config.PROBE_INTERVAL_MS * (Config.START_CONFIRM_SAMPLES + 1))
    }

    @Test fun config_thresholdsAreConsistent() {
        assertTrue(Config.HOT_OFF_C < Config.HOT_ON_C)
        assertTrue(Config.CRIT_OFF_C < Config.CRIT_ON_C)
        assertTrue(Config.CRIT_OFF_C > Config.HOT_OFF_C)
    }
}
