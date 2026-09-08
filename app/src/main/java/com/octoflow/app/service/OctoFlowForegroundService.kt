package com.octoflow.app.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import com.octoflow.R
import com.octoflow.agent.OctoFlowAgentImpl
import com.octoflow.agent.OctoFlowApplication
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking

/**
 * Foreground service to keep OctoFlow running in background
 */
class OctoFlowForegroundService : Service() {
    
    companion object {
        const val CHANNEL_ID = "octoflow_foreground"
        const val NOTIFICATION_ID = 1001
        const val ACTION_START_LISTENING = "com.octoflow.START_LISTENING"
        const val ACTION_STOP = "com.octoflow.STOP"
    }
    
    private val scope = CoroutineScope(Dispatchers.Main + Job())
    private var wakeLock: PowerManager.WakeLock? = null
    private var agent: OctoFlowAgentImpl? = null
    
    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        acquireWakeLock()
        
        agent = (application as OctoFlowApplication).getAgent()
        scope.launch {
            agent?.start()
        }
    }
    
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        intent?.action?.let { action ->
            when (action) {
                ACTION_START_LISTENING -> {
                    scope.launch {
                        agent?.listenAndExecute()
                    }
                }
                ACTION_STOP -> {
                    stopSelf()
                }
                else -> {}
            }
        }
        
        updateNotification("OctoFlow running", "Tap to open")
        return START_STICKY
    }
    
    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "OctoFlow Background Service",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Keeps OctoFlow running for voice commands"
                setShowBadge(false)
                enableVibration(false)
                setSound(null, null)
            }
            
            val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            manager.createNotificationChannel(channel)
        }
    }
    
    private fun acquireWakeLock() {
        val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = powerManager.newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK,
            "OctoFlow::WakeLock"
        ).apply {
            setReferenceCounted(false)
            acquire()
        }
    }
    
    private fun updateNotification(title: String, text: String) {
        val intent = Intent(this, com.octoflow.ui.main.MainActivity::class.java)
            .apply { flags = Intent.FLAG_ACTIVITY_SINGLE_TOP }
        
        val pendingIntent = PendingIntent.getActivity(
            this, 0, intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        
        val stopIntent = Intent(this, OctoFlowForegroundService::class.java)
            .apply { action = ACTION_STOP }
        val stopPendingIntent = PendingIntent.getService(
            this, 0, stopIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        
        val listenIntent = Intent(this, OctoFlowForegroundService::class.java)
            .apply { action = ACTION_START_LISTENING }
        val listenPendingIntent = PendingIntent.getService(
            this, 1, listenIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        
        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(title)
            .setContentText(text)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .addAction(R.drawable.ic_mic, "Listen", listenPendingIntent)
            .addAction(R.drawable.ic_close, "Stop", stopPendingIntent)
            .build()
        
        startForeground(NOTIFICATION_ID, notification)
    }
    
    override fun onDestroy() {
        runCatching { runBlocking { agent?.stop() } }
        scope.cancel()
        wakeLock?.release()
        wakeLock = null
        stopForeground(STOP_FOREGROUND_REMOVE)
        super.onDestroy()
    }
    
    override fun onBind(intent: Intent?): IBinder? = null
}