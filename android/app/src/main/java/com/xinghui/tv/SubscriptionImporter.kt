package com.xinghui.tv

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log

/**
 * 订阅导入统一链路（对齐原版 ConfigDialog.onPositive → Config.find → VodConfig.load）。
 *
 * 原版 VodConfig.loadConfig 里是 `Decoder.getJson(config.getUrl())` —— **每次都真拉**，
 * 不走任何缓存；只有拉取抛异常才回退 `config.getJson()`（上次成功的原文）。成功后
 * `config.json(object.toString()).update()` 把新原文写回并刷新 time。
 *
 * 我们这边 JsonFetcher.get 本来就是直连 HttpURLConnection、无磁盘缓存，
 * 所以"清除缓存拉新数据"天然成立；补的是失败回退与配置历史落库。
 */
object SubscriptionImporter {

    private const val TAG = "XingHuiTV"

    /**
     * @param url  订阅地址
     * @param name 配置名（原版 Config.name，留空则显示 URL）
     * @param done 主线程回调：(成功, 提示文案)
     */
    fun import(ctx: Context, url: String, name: String = "", done: (Boolean, String) -> Unit) {
        Thread {
            var ok = false
            var msg = ""
            try {
                val store = SubscriptionStore(ctx)
                var text = JsonFetcher.get(url)
                // 原版 loadCache：同一配置拉取失败时，用上次成功的原文兜底
                if (text == null && url == store.subscribeUrl() && store.subscriptionText().isNotBlank()) {
                    text = store.subscriptionText()
                    Log.e(TAG, "[sub] 拉取失败，回退已存原文")
                }
                if (text == null) {
                    msg = "下载失败，请检查地址或网络"
                } else {
                    val previousUrl = store.subscribeUrl()
                    val jarRaw = SubscriptionParser.parseJar(text)
                    val jarFull = SubscriptionParser.jarUrl(jarRaw, url)
                    val sites = SubscriptionParser.parse(text, jarFull, url)
                    if (sites.isEmpty()) {
                        msg = "未解析到站点（需 JSON 订阅格式）"
                    } else {
                        store.addAll(sites)
                        if (jarFull.isNotBlank()) {
                            store.setJar(jarFull)
                            store.attachJar(jarFull)
                        }
                        // 原版 LiveConfig:94 —— sync = config.getUrl().equals(VodConfig.getUrl())
                        runCatching {
                            val settings = Settings(ctx)
                            if (settings.liveUrl.isBlank() || settings.liveUrl == previousUrl) settings.liveUrl = url
                        }
                        // 保存订阅 URL：drpy2.js 规则源（./js/*.js / ./lib/*.js）相对路径要基于它解析
                        store.setSubscribeUrl(url)
                        // 保存订阅原文：进程重启后恢复 rules/ads（嗅探白名单与点击脚本）
                        store.setSubscriptionText(text)
                        // 配置历史（原版 Config.find(url,type)）：点播与直播各记一条
                        store.saveConfig(url, name, 0)
                        store.saveConfig(url, name, 1)
                        // 换订阅=换 jar 与站点集合，旧 spider 实例（各挂一个 DexClassLoader）必须放掉
                        CspLoader.destroy()
                        ok = true
                        msg = "已添加 ${sites.size} 个站点：" + sites.joinToString("、") { it.name }
                        Log.e(TAG, "[sub] 导入完成 url=$url sites=${sites.size} jar=${jarFull.take(60)}")
                    }
                }
            } catch (e: Exception) {
                msg = "导入异常：${e.message}"
                Log.e(TAG, "[sub] 导入异常", e)
            }
            Handler(Looper.getMainLooper()).post { done(ok, msg) }
        }.start()
    }
}
