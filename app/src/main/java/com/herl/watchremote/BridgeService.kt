package com.herl.watchremote

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.os.IBinder

/**
 * 常驻前台服务：开机/启动后自动把 interconnect 监听挂上，
 * 这样用户只需要在手表上打开「手表遥控」就能用，不用每次先点平板。
 */
class BridgeService : Service() {

    override fun onCreate() {
        super.onCreate()
        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        nm.createNotificationChannel(
            NotificationChannel(CH_ID, "手表遥控通道", NotificationManager.IMPORTANCE_LOW).apply {
                description = "保持手表指令通道在线"
                setShowBadge(false)
            }
        )
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForeground(NOTI_ID, buildNotification())
        WearBridge.connect(this)
        if (WearBridge.isSleepEnabled(this) && !SleepGuard.enabled) {
            SleepGuard.setEnabled(this, true)
        }
        return START_STICKY
    }

    private fun buildNotification(): Notification =
        Notification.Builder(this, CH_ID)
            .setContentTitle("手表遥控")
            .setContentText("通道常驻中，手表可直接使用")
            .setSmallIcon(android.R.drawable.ic_menu_compass)
            .setOngoing(true)
            .setPriority(Notification.PRIORITY_LOW)
            .build()

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        const val CH_ID = "watchremote_bridge"
        const val NOTI_ID = 1001
    }
}
