package com.itantra.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.net.wifi.WifiManager
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat

class TransceiverService : Service() {
  private var wakeLock: PowerManager.WakeLock? = null
  private var multicastLock: WifiManager.MulticastLock? = null

  override fun onCreate() {
    super.onCreate()
    createNotificationChannel()
    try {
      val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
      wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "iTantra::Walkie").apply {
        acquire(24 * 60 * 60 * 1000L)
      }
    } catch (_: Throwable) {}
    try {
      val wm = applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
      multicastLock = wm?.createMulticastLock("iTantra::Multicast")?.apply {
        setReferenceCounted(false)
        acquire()
      }
    } catch (_: Throwable) {}
  }

  private fun createNotificationChannel() {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
      val channel = NotificationChannel(
        "itantra_channel",
        "iTantra Walkie-Talkie",
        NotificationManager.IMPORTANCE_LOW
      ).apply {
        description = "iTantra mesh walkie-talkie background service"
      }
      val manager = getSystemService(NotificationManager::class.java)
      manager?.createNotificationChannel(channel)
    }
  }

  override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
    val notification: Notification = NotificationCompat.Builder(this, "itantra_channel")
      .setContentTitle("iTantra Walkie-Talkie Active")
      .setContentText("Connected to Peer")
      .setSmallIcon(android.R.drawable.presence_online)
      .setOngoing(true)
      .build()
    startForeground(1, notification)
    return START_STICKY
  }

  override fun onDestroy() {
    try {
      if (wakeLock?.isHeld == true) wakeLock?.release()
    } catch (_: Throwable) {}
    try {
      if (multicastLock?.isHeld == true) multicastLock?.release()
    } catch (_: Throwable) {}
    super.onDestroy()
  }

  override fun onBind(intent: Intent?): IBinder? = null
}
