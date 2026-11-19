package com.herl.watchremote

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PixelFormat
import android.os.Build
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.WindowManager

/**
 * 平板端的虚拟光标。
 *
 * 手表触摸区拖动 -> 微调光标位置；手表轻点 -> 在光标处点击。
 * 用悬浮窗画一个准星，不拦截触摸（FLAG_NOT_TOUCHABLE），
 * 真正的点击由无障碍 dispatchGesture 在坐标上完成。
 */
object CursorOverlay {

    private const val SIZE = 96
    private const val SCALE = 1.8f   // 手表 1px 位移 = 平板 1.8px

    private var wm: WindowManager? = null
    private var view: View? = null
    private var lp: WindowManager.LayoutParams? = null
    private var screenW = 0
    private var screenH = 0

    var visible = false
        private set

    fun canDraw(ctx: Context): Boolean =
        Build.VERSION.SDK_INT < 23 || Settings.canDrawOverlays(ctx)

    fun toggle(ctx: Context, on: Boolean) {
        if (on) show(ctx) else hide()
    }

    fun show(ctx: Context) {
        if (visible) return
        if (!canDraw(ctx)) return
        val manager = ctx.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val dm = ctx.resources.displayMetrics
        screenW = dm.widthPixels
        screenH = dm.heightPixels

        val v = CrosshairView(ctx)
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
            wm = manager; view = v; lp = p; visible = true
        } catch (e: Exception) {
            visible = false
        }
    }

    fun hide() {
        val v = view ?: return
        try { wm?.removeView(v) } catch (e: Exception) {}
        view = null; lp = null; visible = false
    }

    /** 手表发来的相对位移 */
    fun move(dx: Float, dy: Float) {
        val p = lp ?: return
        val m = wm ?: return
        p.x = (p.x + dx * SCALE).toInt().coerceIn(0, screenW - SIZE)
        p.y = (p.y + dy * SCALE).toInt().coerceIn(0, screenH - SIZE)
        try { m.updateViewLayout(view, p) } catch (e: Exception) {}
    }

    fun centerX(): Float = ((lp?.x ?: 0) + SIZE / 2).toFloat()
    fun centerY(): Float = ((lp?.y ?: 0) + SIZE / 2).toFloat()

    private class CrosshairView(ctx: Context) : View(ctx) {
        private val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = 4f
            color = Color.parseColor("#F59E0B")
        }
        private val dot = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.FILL
            color = Color.parseColor("#F59E0B")
        }
        private val halo = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.FILL
            color = Color.parseColor("#40F59E0B")
        }

        override fun onDraw(canvas: Canvas) {
            val cx = width / 2f
            val cy = height / 2f
            canvas.drawCircle(cx, cy, width / 2.6f, halo)
            canvas.drawCircle(cx, cy, width / 2.9f, ring)
            canvas.drawCircle(cx, cy, 6f, dot)
            canvas.drawLine(cx - 26f, cy, cx - 12f, cy, ring)
            canvas.drawLine(cx + 12f, cy, cx + 26f, cy, ring)
            canvas.drawLine(cx, cy - 26f, cx, cy - 12f, ring)
            canvas.drawLine(cx, cy + 12f, cx, cy + 26f, ring)
        }
    }
}
