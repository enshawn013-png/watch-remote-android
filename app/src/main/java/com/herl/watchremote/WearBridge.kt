package com.herl.watchremote

import android.content.Context
import android.util.Log
import com.xiaomi.xms.wearable.Wearable
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

    fun connect(ctx: Context) {
        Wearable.getNodeApi(ctx).connectedNodes
            .addOnSuccessListener { nodes ->
                if (nodes.isNullOrEmpty()) {
                    lastStatus = "没有已连接的设备（先用小米运动健康连手表）"
                    return@addOnSuccessListener
                }
                val node = nodes.first()
                lastStatus = "已连接: " + (node.name ?: node.id)
                Wearable.getMessageApi(ctx)
                    .addListener(node.id, listener)
                    .addOnSuccessListener { Log.i(TAG, "listener attached") }
                    .addOnFailureListener { e ->
                        lastStatus = "监听失败: " + e.message
                        Log.e(TAG, "addListener fail", e)
                    }
            }
            .addOnFailureListener { e ->
                lastStatus = "连接失败: " + e.message
                Log.e(TAG, "connectedNodes fail", e)
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
