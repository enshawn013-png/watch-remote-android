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
                "tap" -> svc.tap(o.optDouble("x", 0.5).toFloat(), o.optDouble("y", 0.5).toFloat())
                "key" -> {
                    val k = o.optString("k")
                    if (k == "back" || k == "home" || k == "recents" || k == "notifications") svc.global(k)
                    else svc.media(k)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "bad message: $json", e)
        }
    }
}
