package com.herl.watchremote

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.Context
import android.graphics.Path
import android.media.AudioManager
import android.os.Handler
import android.os.Looper
import android.view.KeyEvent
import android.view.accessibility.AccessibilityEvent
import kotlin.math.abs

/**
 * 执行端：把手表发来的指令翻译成真实的触摸/按键。
 * 全部走 AccessibilityService.dispatchGesture()，所以抖音、浏览器、任何 App 都通用。
 */
class RemoteAccessibilityService : AccessibilityService() {

    companion object {
        @Volatile
        var instance: RemoteAccessibilityService? = null
            private set
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
    }

    private val handler = Handler(Looper.getMainLooper())

    override fun onDestroy() {
        instance = null
        super.onDestroy()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}

    override fun onInterrupt() {}

    /** dy < 0 = 手指向上推 = 内容向上滚 = 抖音下一条 */
    fun swipe(dx: Int, dy: Int, ms: Int = 300): Boolean {
        val w = resources.displayMetrics.widthPixels
        val h = resources.displayMetrics.heightPixels
        val vertical = abs(dy) > abs(dx)

        val sx: Float
        val sy: Float
        val ex: Float
        val ey: Float
        if (vertical) {
            sx = w * 0.5f
            ex = sx
            sy = if (dy < 0) h * 0.72f else h * 0.32f
            ey = if (dy < 0) h * 0.32f else h * 0.72f
        } else {
            sy = h * 0.5f
            ey = sy
            sx = if (dx < 0) w * 0.78f else w * 0.22f
            ex = if (dx < 0) w * 0.22f else w * 0.78f
        }

        val path = Path().apply {
            moveTo(sx, sy)
            lineTo(ex, ey)
        }
        val stroke = GestureDescription.StrokeDescription(path, 0, ms.toLong().coerceIn(60L, 1200L))
        return dispatchGesture(GestureDescription.Builder().addStroke(stroke).build(), null, null)
    }

    fun tap(xRatio: Float = 0.5f, yRatio: Float = 0.5f): Boolean {
        val w = resources.displayMetrics.widthPixels
        val h = resources.displayMetrics.heightPixels
        return tapAt(w * xRatio, h * yRatio)
    }

    /** 在屏幕绝对坐标点击（光标模式用） */
    fun tapAt(x: Float, y: Float): Boolean {
        val path = Path().apply { moveTo(x, y) }
        val stroke = GestureDescription.StrokeDescription(path, 0, 60)
        return dispatchGesture(GestureDescription.Builder().addStroke(stroke).build(), null, null)
    }

    /** 长按：抖音里长按 = 倍速播放 */
    fun longPressAt(x: Float, y: Float, ms: Long = 600): Boolean {
        val path = Path().apply { moveTo(x, y) }
        val stroke = GestureDescription.StrokeDescription(path, 0, ms.coerceIn(300L, 2000L))
        return dispatchGesture(GestureDescription.Builder().addStroke(stroke).build(), null, null)
    }

    fun tapCenter(): Boolean {
        val w = resources.displayMetrics.widthPixels
        val h = resources.displayMetrics.heightPixels
        return tapAt(w / 2f, h / 2f)
    }

    fun longPressCenter(): Boolean {
        val w = resources.displayMetrics.widthPixels
        val h = resources.displayMetrics.heightPixels
        return longPressAt(w / 2f, h / 2f)
    }

    /** 双击：两次点击间隔 90ms（太短系统会当单击） */
    fun doubleTap(x: Float, y: Float) {
        tapAt(x, y)
        handler.postDelayed({ tapAt(x, y) }, 90)
    }

    fun doubleTapCenter() {
        val w = resources.displayMetrics.widthPixels
        val h = resources.displayMetrics.heightPixels
        doubleTap(w / 2f, h / 2f)
    }

    /** 连滑 n 条：每条间隔 420ms，留出动画时间 */
    fun burst(n: Int, dx: Int, dy: Int) {
        val count = n.coerceIn(1, 30)
        for (i in 0 until count) {
            handler.postDelayed({ swipe(dx, dy, 260) }, i * 420L)
        }
    }

    fun global(action: String): Boolean {
        val code = when (action) {
            "back" -> GLOBAL_ACTION_BACK
            "home" -> GLOBAL_ACTION_HOME
            "recents" -> GLOBAL_ACTION_RECENTS
            "notifications" -> GLOBAL_ACTION_NOTIFICATIONS
            else -> return false
        }
        return performGlobalAction(code)
    }

    fun media(what: String) {
        val am = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        when (what) {
            "volup" -> am.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_RAISE, AudioManager.FLAG_SHOW_UI)
            "voldown" -> am.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_LOWER, AudioManager.FLAG_SHOW_UI)
            "play" -> {
                // 用两参数构造（time 版在 Kotlin 里重载解析会挑错分支）
                am.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE))
                am.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE))
            }
        }
    }
}
