package com.herl.watchremote

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.WindowManager

/**
 * 平板端的鼠标光标。
 *
 * 手表触摸区拖动 -> 移动箭头；手表轻点 -> 在箭头尖端点击。
 * 关键点：WindowManager.addView 必须在主线程执行，
 * 而 interconnect 的消息回调在 binder 线程，所以所有窗口操作都 post 到主线程。
 */
object CursorOverlay {

    private const val TAG = "CursorOverlay"
    private const val SIZE = 84          // 光标位图边长（px）
    private const val SCALE = 1.6f       // 手表 1px -> 平板 1.6px

    private val main = Handler(Looper.getMainLooper())

    private var wm: WindowManager? = null
    private var view: View? = null
    private var lp: WindowManager.LayoutParams? = null
    private var screenW = 0
    private var screenH = 0

    @Volatile
    var visible = false
        private set

    fun canDraw(ctx: Context): Boolean =
        Build.VERSION.SDK_INT < 23 || Settings.canDrawOverlays(ctx)

    fun toggle(ctx: Context, on: Boolean) {
        if (on) show(ctx) else hide()
    }

    fun show(ctx: Context) = main.post {
        if (visible) return@post
        if (!canDraw(ctx)) {
            Log.e(TAG, "没有悬浮窗权限")
            return@post
        }
        val manager = ctx.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val dm = ctx.resources.displayMetrics
        screenW = dm.widthPixels
        screenH = dm.heightPixels

        val v = ArrowView(ctx)
        val p = WindowManager.LayoutParams(
            SIZE, SIZE,
            if (Build.VERSION.SDK_INT >= 26) WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            else @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_PHONE,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                    or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                    or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        )
        p.gravity = Gravity.TOP or Gravity.START
        p.x = (screenW - SIZE) / 2
        p.y = (screenH - SIZE) / 2

        try {
            manager.addView(v, p)
            wm = manager; view = v; lp = p
            visible = true
            Log.i(TAG, "光标已显示")
        } catch (e: Exception) {
            Log.e(TAG, "addView 失败", e)
            visible = false
        }
    }

    fun hide() = main.post {
        val v = view ?: return@post
        try { wm?.removeView(v) } catch (e: Exception) { Log.e(TAG, "removeView 失败", e) }
        view = null; lp = null; visible = false
    }

    fun move(dx: Float, dy: Float) = main.post {
        val p = lp ?: return@post
        val m = wm ?: return@post
        p.x = (p.x + dx * SCALE).toInt().coerceIn(0, screenW - SIZE)
        p.y = (p.y + dy * SCALE).toInt().coerceIn(0, screenH - SIZE)
        try { m.updateViewLayout(view, p) } catch (e: Exception) { Log.e(TAG, "update 失败", e) }
    }

    /** 箭头尖端坐标（点击落点） */
    fun centerX(): Float = ((lp?.x ?: 0) + 6).toFloat()
    fun centerY(): Float = ((lp?.y ?: 0) + 4).toFloat()

    /** 标准鼠标箭头：白填充 + 黑描边 + 投影 */
    private class ArrowView(ctx: Context) : View(ctx) {
        private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.FILL
            color = Color.WHITE
        }
        private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = 3f
            color = Color.parseColor("#111111")
            strokeJoin = Paint.Join.ROUND
        }
        private val shadow = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.FILL
            color = Color.parseColor("#55000000")
        }

        // 归一化箭头轮廓（25 x 32 的经典指针形状）
        private val pts = floatArrayOf(
            0f, 0f,
            0f, 26f,
            6f, 20f,
            11f, 31f,
            16f, 29f,
            11f, 18f,
            19f, 18f
        )

        private fun build(offset: Float): Path {
            val path = Path()
            val sx = width / 25f
            val sy = height / 34f
            path.moveTo(pts[0] * sx + offset, pts[1] * sy + offset)
            var i = 2
            while (i < pts.size) {
                path.lineTo(pts[i] * sx + offset, pts[i + 1] * sy + offset)
                i += 2
            }
            path.close()
            return path
        }

        override fun onDraw(canvas: Canvas) {
            canvas.drawPath(build(3f), shadow)
            canvas.drawPath(build(0f), fill)
            canvas.drawPath(build(0f), stroke)
        }
    }
}
