package com.miro.agent

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.graphics.Color
import android.graphics.PixelFormat
import android.os.Build
import android.os.IBinder
import android.provider.Settings
import android.view.Gravity
import android.view.WindowManager
import android.widget.TextView

class AgentOverlayService : Service() {
    private var windowManager: WindowManager? = null
    private var bubble: TextView? = null

    override fun onCreate() {
        super.onCreate()
        createChannel()
        startForeground(NOTIFICATION_ID, Notification.Builder(this, CHANNEL_ID).setContentTitle("Miro Agent").setContentText("Ready for a task").setSmallIcon(R.drawable.ic_agent).build())
        if (!Settings.canDrawOverlays(this)) return
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        bubble = TextView(this).apply {
            text = "M"
            textSize = 18f
            setTextColor(Color.WHITE)
            setBackgroundColor(Color.rgb(11, 110, 105))
            gravity = Gravity.CENTER
            setOnClickListener { startActivity(Intent(this@AgentOverlayService, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
        }
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY else WindowManager.LayoutParams.TYPE_PHONE
        windowManager?.addView(bubble, WindowManager.LayoutParams(60, 60, type, WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE, PixelFormat.TRANSLUCENT).apply { gravity = Gravity.TOP or Gravity.END; y = 180 })
    }

    override fun onDestroy() {
        bubble?.let { windowManager?.removeView(it) }
        bubble = null
        super.onDestroy()
    }
    override fun onBind(intent: Intent?): IBinder? = null

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) getSystemService(NotificationManager::class.java).createNotificationChannel(NotificationChannel(CHANNEL_ID, "Agent overlay", NotificationManager.IMPORTANCE_LOW))
    }
    companion object { private const val CHANNEL_ID = "agent_overlay"; private const val NOTIFICATION_ID = 7 }
}
