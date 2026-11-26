package com.emekli.robot

import android.app.Application
import com.emekli.robot.core.CrashLog
import com.emekli.robot.service.WatchdogWorker
import com.emekli.robot.sync.SyncStatus

class EmekliApp : Application() {
    override fun onCreate() {
        super.onCreate()
        CrashLog.install(this)
        SyncStatus.init(this)
        com.emekli.robot.net.UsageMeter.init(this)
        WatchdogWorker.schedule(this)
    }
}
