package com.itantra.service

import android.app.Notification
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat

class TransceiverService : Service() {
  private lateinit var wakeLock: PowerManager.WakeLock

  override fun onCreate() {
    super.onCreate()
    val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
    wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "iTantra::Walkie")
    wakeLock.acquire()
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
    if (::wakeLock.isInitialized && wakeLock.isHeld) wakeLock.release()
    super.onDestroy()
  }

  override fun onBind(intent: Intent?): IBinder? = null
}
