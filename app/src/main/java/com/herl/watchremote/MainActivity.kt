package com.herl.watchremote

import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.Gravity
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView

class MainActivity : Activity() {

    private lateinit var status: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(60, 80, 60, 60)
            setBackgroundColor(Color.parseColor("#0d0f12"))
        }

        val title = TextView(this).apply {
            text = "手表遥控"
            textSize = 26f
            setTextColor(Color.parseColor("#e8e8ea"))
        }
        root.addView(title)

        status = TextView(this).apply {
            textSize = 15f
            setTextColor(Color.parseColor("#8f96a3"))
            setPadding(0, 30, 0, 30)
        }
        root.addView(status, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))

        root.addView(button("① 开启无障碍服务") {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        })
        root.addView(button("② 连接手表") {
            WearBridge.connect(this)
            status.text = WearBridge.lastStatus
        })
        root.addView(button("③ 自检：向上滑一下") {
            val ok = RemoteAccessibilityService.instance?.swipe(0, -600, 300) ?: false
            status.text = if (ok) "已发送一次上滑（成功）" else "发送失败：无障碍服务没开"
        })

        val hint = TextView(this).apply {
            text = "用法：手表上打开「手表遥控」→ 手指上下滑 = 平板翻页\n返回/主页/音量在手表底部的按键条上。"
            textSize = 13f
            setTextColor(Color.parseColor("#6b7280"))
            setPadding(0, 40, 0, 0)
        }
        root.addView(hint, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))

        setContentView(root)
    }

    override fun onResume() {
        super.onResume()
        val h = Handler(Looper.getMainLooper())
        val ticker = object : Runnable {
            override fun run() {
                val on = RemoteAccessibilityService.instance != null
                status.text = (if (on) "无障碍：已开启" else "无障碍：未开启") + "\n" + WearBridge.lastStatus
                h.postDelayed(this, 1000)
            }
        }
        h.post(ticker)
    }

    private fun button(label: String, onClick: () -> Unit): Button =
        Button(this).apply {
            text = label
            textSize = 16f
            setOnClickListener { onClick() }
            layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = 24 }
        }
}
