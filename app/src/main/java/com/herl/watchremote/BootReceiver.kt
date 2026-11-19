package com.herl.watchremote

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build

/** 开机自启：把通道服务拉起来 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED ||
            intent.action == Intent.ACTION_MY_PACKAGE_REPLACED
        ) {
            val svc = Intent(context, BridgeService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(svc)
            } else {
                context.startService(svc)
            }
        }
    }
}
