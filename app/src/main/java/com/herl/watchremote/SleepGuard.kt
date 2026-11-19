package com.herl.watchremote

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.media.AudioManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.util.Log
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * 看困了就停。
 *
 * 平板端判定（S3 不给快应用加速度计/心率，所以传感器只能放在平板）：
 *   1. 有音频在播（isMusicActive）
 *   2. 屏幕是亮的
 *   3. 连续 12 分钟没有任何无障碍交互事件（点击/滑动/切页）
 *   4. 平板加速度计持续静止（你在动就重置）
 * 满足后：震动手表预警 30 秒 -> 仍无动作 -> 暂停 + 静音 + 锁屏 + 记时间。
 */
object SleepGuard {

    private const val TAG = "SleepGuard"
    const val IDLE_MS = 12 * 60 * 1000L      // 12 分钟没操作
    const val WARN_MS = 30 * 1000L           // 预警 30 秒
    private const val STILL_THRESHOLD = 0.35f // 加速度标准差阈值，越小越敏感
    private const val TICK_MS = 15 * 1000L

    private val main = Handler(Looper.getMainLooper())

    @Volatile var enabled = false
        private set
    @Volatile private var lastActivity = 0L
    @Volatile private var lastMotion = 0L
    @Volatile private var warnStartedAt = 0L
    private var lastLog = ""

    private var sensorManager: SensorManager? = null
    private val accelBuf = FloatArray(3)
    private var accelSamples = 0

    private val accelListener = object : SensorEventListener {
        override fun onSensorChanged(e: SensorEvent) {
            // 用相邻样本差值的模长衡量"抖动"：拿在手里会持续有变化
            val dx = e.values[0] - accelBuf[0]
            val dy = e.values[1] - accelBuf[1]
            val dz = e.values[2] - accelBuf[2]
            accelBuf[0] = e.values[0]; accelBuf[1] = e.values[1]; accelBuf[2] = e.values[2]
            if (accelSamples++ < 3) return
            val delta = sqrt(dx * dx + dy * dy + dz * dz)
            if (delta > STILL_THRESHOLD) {
                lastMotion = System.currentTimeMillis()
                // 明显在动 = 有人在用，直接算一次活动
                if (delta > 1.2f) lastActivity = System.currentTimeMillis()
            }
        }
        override fun onAccuracyChanged(s: Sensor?, a: Int) {}
    }

    private val ticker = object : Runnable {
        override fun run() {
            try { evaluate() } catch (e: Exception) { Log.e(TAG, "evaluate", e) }
            if (enabled) main.postDelayed(this, TICK_MS)
        }
    }

    fun noteActivity() {
        lastActivity = System.currentTimeMillis()
        if (warnStartedAt > 0) {
            warnStartedAt = 0
            Log.i(TAG, "预警期间检测到操作，取消")
        }
    }

    fun setEnabled(ctx: Context, on: Boolean) {
        enabled = on
        main.removeCallbacks(ticker)
        if (on) {
            lastActivity = System.currentTimeMillis()
            lastMotion = System.currentTimeMillis()
            startSensor(ctx)
            main.postDelayed(ticker, TICK_MS)
            Log.i(TAG, "睡前模式开启")
        } else {
            stopSensor()
            warnStartedAt = 0
            Log.i(TAG, "睡前模式关闭")
        }
    }

    private fun startSensor(ctx: Context) {
        if (sensorManager != null) return
        val sm = ctx.getSystemService(Context.SENSOR_SERVICE) as SensorManager
        val accel = sm.getDefaultSensor(Sensor.TYPE_ACCELEROMETER) ?: return
        sm.registerListener(accelListener, accel, SensorManager.SENSOR_DELAY_NORMAL)
        sensorManager = sm
    }

    private fun stopSensor() {
        sensorManager?.unregisterListener(accelListener)
        sensorManager = null
        accelSamples = 0
    }

    private fun evaluate() {
        if (!enabled) return
        val svc = RemoteAccessibilityService.instance ?: return
        val ctx = svc.applicationContext
        val now = System.currentTimeMillis()

        // 屏幕必须亮着
        val pm = ctx.getSystemService(Context.POWER_SERVICE) as PowerManager
        if (!pm.isInteractive) { warnStartedAt = 0; return }

        // 必须有音频在播（视频/音乐）
        val am = ctx.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        if (!am.isMusicActive) { warnStartedAt = 0; return }

        val idleFor = now - lastActivity
        val stillFor = now - lastMotion
        if (idleFor < IDLE_MS || stillFor < IDLE_MS) { warnStartedAt = 0; return }

        if (warnStartedAt == 0L) {
            warnStartedAt = now
            WearBridge.sendToWatch("""{"t":"warn"}""")
            Log.i(TAG, "静止 ${idleFor / 60000} 分钟，开始预警")
            return
        }

        if (now - warnStartedAt < WARN_MS) return

        // 预警期内没被取消 -> 执行停止
        doStop(svc, am, pm)
    }

    private fun doStop(svc: RemoteAccessibilityService, am: AudioManager, pm: PowerManager) {
        Log.i(TAG, "判定入睡，停止播放并锁屏")
        try {
            svc.media("voldown")   // 降一格
            am.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_MUTE, 0)
            svc.pauseMedia()
            WearBridge.sendToWatch("""{"t":"slept"}""")
        } catch (e: Exception) {
            Log.e(TAG, "停止播放失败", e)
        }
        val stamp = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.CHINA).format(Date())
        lastLog = stamp
        try {
            val f = File(svc.filesDir, "sleep.log")
            f.appendText("$stamp 自动停止播放（静止 ${IDLE_MS / 60000} 分钟）\n")
        } catch (e: Exception) { }

        if (Build.VERSION.SDK_INT >= 28) {
            try { svc.performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_LOCK_SCREEN) } catch (e: Exception) { }
        }
        warnStartedAt = 0
        lastActivity = System.currentTimeMillis()
    }

    fun lastTrigger(): String = lastLog
}
