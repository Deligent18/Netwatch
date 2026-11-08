package com.example.netwatch.work

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.app.PendingIntent
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.example.netwatch.MainActivity
import com.example.netwatch.data.Device
import com.example.netwatch.data.MonitorRepository

object Notifier {
    private const val CHANNEL = "new_devices"

    fun newDevice(context: Context, device: Device) {
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) return

        val nm = context.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL, "New devices", NotificationManager.IMPORTANCE_HIGH),
        )
        val open = PendingIntent.getActivity(
            context, 0, Intent(context, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val n = NotificationCompat.Builder(context, CHANNEL)
            .setSmallIcon(android.R.drawable.stat_sys_warning)
            .setContentTitle("New device on your network")
            .setContentText("${device.displayName} (${device.mac ?: device.ip})")
            .setContentIntent(open)
            .setAutoCancel(true)
            .build()
        nm.notify(device.key.hashCode(), n)
    }
}

class ScanWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {
    override suspend fun doWork(): Result {
        val fresh = runCatching { MonitorRepository(applicationContext).scan() }
            .getOrElse { return Result.retry() }
        fresh.forEach { Notifier.newDevice(applicationContext, it) }
        return Result.success()
    }
}
