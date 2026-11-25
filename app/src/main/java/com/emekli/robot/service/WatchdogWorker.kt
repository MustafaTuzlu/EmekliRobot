package com.emekli.robot.service

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.emekli.robot.core.RuntimeState
import com.emekli.robot.core.ThermalState
import com.emekli.robot.sync.SyncEngine
import java.util.concurrent.TimeUnit

/**
 * 15 dk'da bir: servis ölmüşse ayağa kaldırır, internet varsa kuyruğu boşaltmayı dener.
 * (Servis zaten alarm ve START_STICKY ile korunur; bu ikinci güvenlik ağıdır.)
 */
class WatchdogWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val ctx = applicationContext
        val granted = ctx.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED
        val state = RuntimeState.flow.value
        if (granted && !state.running) TrackerService.start(ctx)
        if (state.thermal == ThermalState.NORMAL && isOnline(ctx)) SyncEngine.run(ctx, force = false)
        return Result.success()
    }

    private fun isOnline(ctx: Context): Boolean {
        val cm = ctx.getSystemService(ConnectivityManager::class.java)
        val caps = cm.getNetworkCapabilities(cm.activeNetwork) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }

    companion object {
        private const val NAME = "watchdog"

        fun schedule(context: Context) {
            val req = PeriodicWorkRequestBuilder<WatchdogWorker>(15, TimeUnit.MINUTES).build()
            WorkManager.getInstance(context)
                .enqueueUniquePeriodicWork(NAME, ExistingPeriodicWorkPolicy.KEEP, req)
        }
    }
}
