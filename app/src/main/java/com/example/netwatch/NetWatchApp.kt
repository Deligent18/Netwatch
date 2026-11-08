package com.example.netwatch

import android.app.Application
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import com.example.netwatch.work.ScanWorker
import java.util.concurrent.TimeUnit

class NetWatchApp : Application() {
    override fun onCreate() {
        super.onCreate()
        // Android's minimum period for background work is 15 minutes.
        val request = PeriodicWorkRequestBuilder<ScanWorker>(15, TimeUnit.MINUTES)
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.UNMETERED).build())
            .build()
        WorkManager.getInstance(this)
            .enqueueUniquePeriodicWork("scan", ExistingPeriodicWorkPolicy.KEEP, request)
    }
}
