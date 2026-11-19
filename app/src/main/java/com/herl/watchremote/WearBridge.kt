package com.herl.watchremote

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.xiaomi.xms.wearable.Wearable
import com.xiaomi.xms.wearable.auth.Permission
import com.xiaomi.xms.wearable.message.OnMessageReceivedListener
import org.json.JSONObject

/**
 * 手表 <-> 手机通道：小米穿戴开放 SDK 的 MessageApi。
 * 官方要求：applicationId 与手表快应用包名一致、签名一致，否则连不上。
 */
object WearBridge {

    private const val TAG = "WearBridge"

    @Volatile
    var lastStatus: String = "未连接"
        private set

    private val listener = OnMessageReceivedListener { nodeId, message ->
        val text = String(message, Charsets.UTF_8)
        Log.d(TAG, "recv from $nodeId: $text")
        handle(text)
    }

    private val handler = Handler(Looper.getMainLooper())
    private var attached = false
    private var polling = false
    private var nodeId: String? = null

    /** 手表可能还没就绪，所以每 2 秒重试一次，直到挂上监听 */
    fun connect(ctx: Context) {
        if (polling) return
        polling = true
        val nodeApi = Wearable.getNodeApi(ctx)

        val poll = object : Runnable {
            override fun run() {
                if (attached) { polling = false; return }
                nodeApi.connectedNodes
                    .addOnSuccessListener { nodes ->
                        if (!nodes.isNullOrEmpty()) {
                            val node = nodes.first()
                            lastStatus = "发现设备: " + (node.name ?: node.id)
                            ensurePermission(ctx, node.id)
                        } else {
                            lastStatus = "等待手表连接…"
                            handler.postDelayed(this, 2000)
                        }
                    }
                    .addOnFailureListener { e ->
                        lastStatus = "查询失败: " + e.message
                        handler.postDelayed(this, 2000)
                    }
            }
        }
        poll.run()
    }

    /** interconnect 必须先拿 DEVICE_MANAGER 授权，手表上会弹确认 */
    private fun ensurePermission(ctx: Context, id: String) {
        val auth = Wearable.getAuthApi(ctx)
        auth.checkPermission(id, Permission.DEVICE_MANAGER)
            .addOnSuccessListener { granted ->
                if (granted) {
                    attach(ctx, id)
                } else {
                    lastStatus = "等待手表上确认授权…"
                    auth.requestPermission(id, Permission.DEVICE_MANAGER)
                        .addOnSuccessListener {
                            lastStatus = "已授权，正在挂监听"
                            attach(ctx, id)
                        }
                        .addOnFailureListener { e ->
                            lastStatus = "授权失败: " + e.message
                            Log.e(TAG, "requestPermission fail", e)
                            handler.postDelayed({ ensurePermission(ctx, id) }, 5000)
                        }
                }
            }
            .addOnFailureListener { e ->
                lastStatus = "权限查询失败: " + e.message
                Log.w(TAG, "checkPermission fail", e)
                attach(ctx, id)
            }
    }

    private fun attach(ctx: Context, id: String) {
        nodeId = id
        Wearable.getMessageApi(ctx).addListener(id, listener)
            .addOnSuccessListener {
                attached = true
                polling = false
                lastStatus = "通道就绪 ✓"
                Log.i(TAG, "listener attached")
            }
            .addOnFailureListener { e ->
                lastStatus = "监听失败: " + e.message
                Log.e(TAG, "addListener fail", e)
            }
    }

    fun handle(json: String) {
        val svc = RemoteAccessibilityService.instance
        if (svc == null) {
            lastStatus = "无障碍服务未开启"
            return
        }
        try {
            val o = JSONObject(json)
            when (o.optString("t")) {
                "swipe" -> svc.swipe(o.optInt("dx", 0), o.optInt("dy", 0), o.optInt("ms", 300))
                "tap" -> svc.tapCenter()
                "key" -> {
                    val k = o.optString("k")
                    if (k == "back" || k == "home" || k == "recents" || k == "notifications") svc.global(k)
                    else svc.media(k)
                }
                // 光标模式
                "cursor" -> {
                    val on = o.optBoolean("on", false)
                    CursorOverlay.toggle(svc, on)
                    lastStatus = if (on) "光标模式 ✓" else "滑动模式"
                }
                "move" -> CursorOverlay.move(
                    o.optDouble("dx", 0.0).toFloat(),
                    o.optDouble("dy", 0.0).toFloat()
                )
                "click" -> {
                    if (CursorOverlay.visible) svc.tapAt(CursorOverlay.centerX(), CursorOverlay.centerY())
                    else svc.tapCenter()
                }
                "long" -> {
                    if (CursorOverlay.visible) svc.longPressAt(CursorOverlay.centerX(), CursorOverlay.centerY())
                    else svc.longPressCenter()
                }
                "dbl" -> {
                    if (CursorOverlay.visible) svc.doubleTap(CursorOverlay.centerX(), CursorOverlay.centerY())
                    else svc.doubleTapCenter()
                }
                "burst" -> svc.burst(o.optInt("n", 5), o.optInt("dx", 0), o.optInt("dy", -600))
                "hello" -> lastStatus = "手表已握手 ✓"
                // 睡前模式开关 / 用户表示自己还醒着
                "sleep" -> {
                    val on = o.optBoolean("on", false)
                    SleepGuard.setEnabled(svc, on)
                    saveSleepFlag(on)
                    lastStatus = if (on) "睡前模式 ✓" else "睡前模式关闭"
                }
                "awake" -> {
                    SleepGuard.noteActivity()
                    lastStatus = "收到「我醒着」，计时重置"
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "bad message: $json", e)
        }
    }

    /** 平板 -> 手表：用于预警("t":"warn")和已停止("t":"slept") */
    fun sendToWatch(json: String) {
        val id = nodeId ?: return
        val ctx = RemoteAccessibilityService.instance?.applicationContext ?: return
        try {
            Wearable.getMessageApi(ctx).sendMessage(id, json.toByteArray(Charsets.UTF_8))
                .addOnFailureListener { e -> Log.e(TAG, "sendMessage fail", e) }
        } catch (e: Exception) {
            Log.e(TAG, "sendMessage exception", e)
        }
    }

    fun saveSleepFlag(on: Boolean) {
        val ctx = RemoteAccessibilityService.instance?.applicationContext ?: return
        ctx.getSharedPreferences("watchremote", Context.MODE_PRIVATE)
            .edit().putBoolean("sleep_enabled", on).apply()
    }

    fun isSleepEnabled(ctx: Context): Boolean =
        ctx.getSharedPreferences("watchremote", Context.MODE_PRIVATE)
            .getBoolean("sleep_enabled", false)
}
