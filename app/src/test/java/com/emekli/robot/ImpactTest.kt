package com.emekli.robot

import com.emekli.robot.core.Config
import com.emekli.robot.core.GpsPoint
import com.emekli.robot.core.ImpactDetector
import com.emekli.robot.core.ImpactExport
import com.emekli.robot.core.ImpactLabel
import com.emekli.robot.core.ImpactPolicy
import com.emekli.robot.core.ImpactPrune
import com.emekli.robot.core.ImpactStage
import com.emekli.robot.core.ImpactUpdate
import com.emekli.robot.core.SampleRing
import com.emekli.robot.core.Slice
import com.emekli.robot.core.ThermalState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.assertFalse
import org.junit.Test

/** Sentetik sinyaller 200 Hz'dir. Telefon düz duruyor: yerçekimi +Z. */
class ImpactTest {
    private val g = ImpactDetector.G

    private class Sim(maxRange: Float = 8 * ImpactDetector.G, capacity: Int = Config.IMPACT_RING_SAMPLES) {
        val updates = ArrayList<ImpactUpdate>()
        val detector = ImpactDetector(maxRange, { updates.add(it) }, capacity)
        var t = 1_000_000_000_000L

        /** `seconds` boyunca sabit ham ivme (G biriminde). */
        fun hold(seconds: Double, x: Double = 0.0, y: Double = 0.0, z: Double = 1.0, gyro: Boolean = false) {
            repeat(Math.round(seconds * 200).toInt()) {
                t += 5_000_000
                if (gyro) detector.onGyro(t, 0.1f, 0f, 0f)
                detector.onAccel(
                    t, (x * ImpactDetector.G).toFloat(), (y * ImpactDetector.G).toFloat(), (z * ImpactDetector.G).toFloat(),
                )
            }
        }

        fun gps(speed: Double?, rejected: Boolean = false, at: Long = t) =
            detector.onGps(GpsPoint(at, 41.0, 29.0, speed, 90.0, 5.0, 9, rejected))

        /** 1 sn'de bir GPS ile sürüş. */
        fun drive(seconds: Int, speed: Double?, gyro: Boolean = false) = repeat(seconds) {
            hold(1.0, gyro = gyro)
            if (speed != null) gps(speed)
        }

        val keys get() = updates.map { it.key }.distinct()
        val last get() = updates.last()
    }

    @Test fun gravityOnly_noCandidate() {
        val s = Sim()
        s.hold(60.0)
        assertTrue(s.updates.isEmpty())
    }

    @Test fun tiltedAndFlippedPhone_noCandidate() {
        val s = Sim()
        s.hold(5.0)
        s.hold(5.0, y = 1.0, z = 0.0)
        s.hold(5.0, z = -1.0) // tam ters dönme bile en çok 2 G sapma yapar
        assertTrue(s.updates.isEmpty())
    }

    @Test fun hardBraking_lowGLongDuration_noCandidate() {
        val s = Sim()
        s.drive(20, 60.0)
        s.hold(3.0, x = 0.9)
        s.drive(10, 0.0)
        assertTrue(s.updates.isEmpty())
    }

    @Test fun shortSpikeWithSpeedLoss_isPossibleImpact() {
        val s = Sim()
        s.drive(20, 50.0, gyro = true)
        s.hold(0.03, x = 5.0)
        s.drive(150, 0.0, gyro = true)
        assertEquals(1, s.keys.size)
        assertEquals(ImpactStage.OZET, s.updates.first().stage)
        assertTrue(s.updates.any { it.stage == ImpactStage.DALGA && it.accel != null })
        val u = s.last
        assertEquals(ImpactStage.TAMAM, u.stage)
        assertEquals(ImpactLabel.OLASI_DARBE, u.label)
        assertEquals(5.0, u.metrics.peakG, 0.2)
        assertEquals(30.0, u.metrics.durationMs, 6.0)
        assertFalse(u.metrics.singleSample)
        assertFalse(u.metrics.saturated)
        assertEquals(50.0, u.speed.beforeKmh!!, 0.01)
        assertEquals(0.0, u.speed.after120Kmh!!, 0.01)
        assertEquals(true, u.speed.significantLoss)
        assertNotNull(u.metrics.maxGyroDps)
        assertTrue(u.gps.any { it.tNanos < u.peakNanos } && u.gps.any { it.tNanos > u.peakNanos + 100_000_000_000L })
    }

    @Test fun waveCoversTenSecondsBeforeAndAfter() {
        val s = Sim()
        s.hold(30.0)
        s.hold(0.02, x = 4.5)
        s.hold(15.0)
        val wave = s.updates.first { it.accel != null }
        val spanMs = (wave.accel!!.times.last() - wave.accel!!.times.first()) / 1e6
        assertEquals(20_000.0, spanMs, 20.0)
    }

    @Test fun hundredMillisecondImpact_durationAndDeltaV() {
        val s = Sim()
        s.drive(10, 40.0)
        s.hold(0.1, x = 3.0)
        s.drive(40, 40.0)
        val u = s.last
        assertEquals(100.0, u.metrics.durationMs, 6.0)
        assertEquals(3.0 * g * 0.1 * 3.6, u.metrics.deltaVKmh, 0.8)
        assertEquals(ImpactLabel.BELIRLENEMEDI, u.label) // tepe 4 G altı, hız kaybı yok
    }

    @Test fun deltaVIsVectorIntegral() {
        val s = Sim()
        s.hold(5.0)
        s.hold(0.05, x = 3.0)
        s.hold(0.05, x = -3.0)
        s.hold(15.0)
        val u = s.updates.last { it.accel != null }
        assertTrue("zıt yönler birbirini götürmeli: ${u.metrics.deltaVKmh}", u.metrics.deltaVKmh < 1.0)
    }

    private fun drop(speedAfter: Double): Sim {
        val s = Sim()
        s.drive(20, 50.0)
        s.hold(0.2, z = 0.05)
        s.hold(0.02, z = 6.0)
        s.drive(150, speedAfter)
        return s
    }

    @Test fun freeFallThenImpact_withoutSpeedLoss_isPhoneDropSuspicion() {
        val u = drop(50.0).last
        assertTrue(u.metrics.freeFallMs >= 120)
        assertEquals(ImpactLabel.TELEFON_DUSMESI_SUPHESI, u.label)
    }

    @Test fun freeFallWithSpeedLoss_isContradictory() {
        assertEquals(ImpactLabel.BELIRLENEMEDI, drop(0.0).last.label)
    }

    @Test fun saturatedSignal_isMarkedAndUndetermined() {
        val s = Sim(maxRange = 4 * g)
        s.drive(20, 60.0)
        s.hold(0.03, x = 4.0, z = 1.0)
        s.hold(0.005, x = 4.0, z = 4.0)
        s.drive(150, 0.0)
        assertTrue(s.last.metrics.saturated)
        assertEquals(ImpactLabel.BELIRLENEMEDI, s.last.label)
    }

    @Test fun singleSample_isKeptButMarked() {
        val s = Sim()
        s.drive(20, 60.0)
        s.hold(0.005, x = 6.0)
        s.drive(150, 0.0)
        assertEquals(1, s.keys.size)
        assertTrue(s.last.metrics.singleSample)
        assertEquals(ImpactLabel.BELIRLENEMEDI, s.last.label)
    }

    @Test fun missingGyroAndPressure_stillWorks() {
        val s = Sim()
        s.hold(10.0)
        s.hold(0.03, x = 5.0)
        s.hold(15.0)
        val u = s.updates.last { it.accel != null }
        assertNull(u.metrics.maxGyroDps)
        assertNull(u.metrics.pressureDeltaHpa)
        assertNull(u.gyro)
    }

    @Test fun noGps_candidateKeptAsUndetermined() {
        val s = Sim()
        s.hold(20.0)
        s.hold(0.03, x = 6.0)
        s.hold(150.0)
        assertEquals(ImpactStage.TAMAM, s.last.stage)
        assertNull(s.last.speed.beforeKmh)
        assertNull(s.last.speed.significantLoss)
        assertEquals(ImpactLabel.BELIRLENEMEDI, s.last.label)
    }

    @Test fun staleSpeedAndRejectedFixes_areNotUsedButRejectedAreStored() {
        val s = Sim()
        s.drive(10, 60.0)
        s.hold(10.0) // hız 10 sn bayat
        s.gps(80.0, rejected = true)
        s.hold(0.03, x = 5.0)
        s.drive(150, 0.0)
        assertNull(s.last.speed.beforeKmh)
        assertEquals(ImpactLabel.BELIRLENEMEDI, s.last.label)
        assertTrue(s.updates.first { it.accel != null }.gps.any { it.rejected })
    }

    @Test fun repeatedTriggersMerge_highestPeakKept() {
        val s = Sim()
        s.hold(20.0)
        s.hold(0.03, x = 3.0)
        s.hold(5.0)
        s.hold(0.03, x = 6.0)
        s.hold(20.0)
        s.hold(0.03, x = 3.5)
        s.hold(150.0)
        assertEquals(1, s.keys.size)
        assertEquals(3, s.last.triggers)
        assertEquals(6.0, s.last.metrics.peakG, 0.3)
    }

    @Test fun triggersMoreThanThirtySecondsApart_areSeparate() {
        val s = Sim()
        s.hold(20.0)
        s.hold(0.03, x = 3.0)
        s.hold(45.0)
        s.hold(0.03, x = 3.0)
        s.hold(150.0)
        assertEquals(2, s.keys.size)
        assertEquals(2, s.updates.count { it.stage == ImpactStage.TAMAM })
    }

    @Test fun flushWritesPendingWave() {
        val s = Sim()
        s.hold(15.0)
        s.hold(0.03, x = 5.0)
        s.hold(2.0)
        s.detector.flush()
        assertTrue(s.updates.any { it.accel != null })
        assertEquals(ImpactStage.TAMAM, s.last.stage)
    }

    @Test fun ringOverflow_keepsNewestWithFixedCapacity() {
        val r = SampleRing(5, 1)
        for (i in 1..12) r.add(i.toLong(), floatArrayOf(i.toFloat()))
        assertEquals(5, r.size)
        assertEquals(8L, r.time(0))
        assertEquals(12f, r.value(4, 0), 0f)
        assertEquals(3, r.slice(9, 11).size)
    }

    @Test fun smallRingStillDetects() {
        val s = Sim(capacity = 600) // 3 sn
        s.hold(60.0)
        s.hold(0.03, x = 5.0)
        s.hold(150.0)
        val u = s.updates.first { it.accel != null }
        assertTrue(u.accel!!.size <= 600)
        assertEquals(ImpactStage.TAMAM, s.last.stage)
    }

    @Test fun sliceEncodeDecodeAndExport() {
        val slice = Slice(longArrayOf(1_000_000_000, 1_005_000_000), floatArrayOf(1f, 2f, 3f, 4f, 5f, 6f), 3)
        val rows = Slice.decode(slice.encode(1_000_000_000), 3)
        assertEquals(2, rows.size)
        assertEquals(5f, rows[1][0], 0.001f)
        assertEquals(6f, rows[1][3], 0f)
        assertTrue(ImpactExport.series(slice.encode(0), ImpactExport.GYRO_COLUMNS).toString().contains("ornekler"))
    }

    @Test fun pruneRemovesOldestButProtectsKeptAndCurrent() {
        val items = (1..6).map { ImpactPrune.Item("e$it", it.toLong(), 100, keep = it == 1) }
        assertEquals(listOf("e3", "e4"), ImpactPrune.toDelete(items, current = "e2", maxEvents = 4, maxBytes = 10_000))
        assertEquals(listOf("e2", "e3", "e4"), ImpactPrune.toDelete(items, current = "e6", maxEvents = 50, maxBytes = 300))
        assertTrue(ImpactPrune.toDelete(items, current = null, maxEvents = 6, maxBytes = 600).isEmpty())
    }

    @Test fun policy() {
        val base = ImpactPolicy.Inputs(true, ThermalState.NORMAL, false, 80, false, null, null)
        fun on(i: ImpactPolicy.Inputs) = ImpactPolicy.offReason(i) == null
        assertFalse(on(base))
        assertTrue(on(base.copy(plugged = true)))
        assertTrue(on(base.copy(plugged = true, thermal = ThermalState.ISINMIS)))
        assertFalse(on(base.copy(plugged = true, thermal = ThermalState.KRITIK)))
        assertFalse(on(base.copy(plugged = true, enabled = false)))
        assertTrue(on(base.copy(moving = true, lastFixAgeMs = 60_000)))
        assertFalse(on(base.copy(moving = true, lastFixAgeMs = 16 * 60_000)))
        assertFalse(on(base.copy(moving = true)))
        assertFalse(on(base.copy(moving = true, lastFixAgeMs = 1_000, batteryPct = 3)))
        assertTrue(on(base.copy(sinceStopMs = 179_000)))
        assertFalse(on(base.copy(sinceStopMs = 181_000)))
    }
}
