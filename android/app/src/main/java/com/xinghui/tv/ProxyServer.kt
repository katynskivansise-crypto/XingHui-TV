package com.xinghui.tv

import android.util.Log
import fi.iki.elonen.NanoHTTPD
import java.io.InputStream

/**
 * 本地播放代理服务（对齐原版 server/process/Proxy.java + NanoHTTPD 9978）。
 *
 * csp / js 站播放接口常返回 `http://127.0.0.1:9978/proxy?do=xxx&...`：
 * 本服务解析 query 与 header 成 params，转发给 jar 内反射得到的 Proxy.proxy(Map)，
 * 再把返回的 {status, contentType, InputStream, headers} 写回播放器。
 *
 * 与 CspLoader 的分工：
 * - CspLoader：负责 jar 加载与 Proxy.proxy 反射句柄（proxyLocal）
 * - ProxyServer：只负责 HTTP 服务与响应拼装
 */
object ProxyServer {

    @Volatile
    private var http: NanoHTTPD? = null

    /** 启动本地代理（幂等，端口 9978）；失败只记日志，不影响主流程 */
    fun startServer() {
        if (http != null) return
        synchronized(this) {
            if (http != null) return
            val server = object : NanoHTTPD(9978) {
                override fun serve(session: IHTTPSession): Response {
                    if (session.uri != "/proxy") {
                        return newFixedLengthResponse(
                            Response.Status.NOT_FOUND, MIME_PLAINTEXT, "not found"
                        )
                    }
                    val params = HashMap<String, String>()
                    runCatching {
                        session.parameters.forEach { (k, v) -> v.firstOrNull()?.let { params[k] = it } }
                        session.headers?.forEach { (k, v) -> params[k] = v }
                    }
                    val rs = CspLoader.proxyLocal(params)
                    if (rs == null || rs.isEmpty()) {
                        Log.e("XingHuiTV", "[proxy.server] proxyLocal 返回空 params=$params")
                        return newFixedLengthResponse(
                            Response.Status.INTERNAL_ERROR, MIME_PLAINTEXT, "proxy null"
                        )
                    }
                    // 对齐原版 Proxy.doResponse：rs[0] 若是 Response 对象则直接透出
                    val first = rs[0]
                    if (first is NanoHTTPD.Response) return first
                    return try {
                        val status = (rs[0] as? Number)?.toInt() ?: 200
                        val contentType = rs.getOrNull(1) as? String ?: "application/octet-stream"
                        val stream = rs.getOrNull(2) as? InputStream
                        val headers = rs.getOrNull(3) as? Map<*, *>
                        val resp: Response = if (stream != null) {
                            newChunkedResponse(Response.Status.lookup(status), contentType, stream)
                        } else {
                            newFixedLengthResponse(Response.Status.lookup(status), contentType, "")
                        }
                        headers?.forEach { (k, v) -> if (k is String && v is String) resp.addHeader(k, v) }
                        resp
                    } catch (e: Exception) {
                        Log.e("XingHuiTV", "[proxy.server] 响应构造失败", e)
                        newFixedLengthResponse(
                            Response.Status.INTERNAL_ERROR, MIME_PLAINTEXT, e.toString()
                        )
                    }
                }
            }
            runCatching { server.start(NanoHTTPD.SOCKET_READ_TIMEOUT, true) }
                .onSuccess {
                    http = server
                    Log.e("XingHuiTV", "[proxy.server] 已启动 127.0.0.1:9978")
                }
                .onFailure { Log.e("XingHuiTV", "[proxy.server] 启动失败", it) }
        }
    }
}
