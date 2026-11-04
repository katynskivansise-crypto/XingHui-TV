package com.xinghui.tv

import android.app.Activity
import android.os.Bundle
import android.view.KeyEvent
import android.view.View
import android.widget.TextView
import android.widget.Toast
import androidx.media3.common.MediaItem
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import com.xinghui.tv.dialog.EpisodeDialog
import org.json.JSONObject

/**
 * 播放页：
 * - 解析播放地址 → 按设置内核播放（内置 ExoPlayer / 外部 mpv）
 * - 遥控器【菜单键】弹出选集/换源面板（EpisodeDialog），切换线路/集数即时重新解析播放
 * - 播放成功自动写入历史（含线路+集地址，供历史页续播）
 * 入参（Intent extra）：
 *   vod_id / vod_name / vod_pic / flag(线路名) / ep_url(集地址) / direct_url(直播直链，可空)
 */
class PlayActivity : Activity() {

    private var player: ExoPlayer? = null

    private var vodId = ""
    private var vodName = ""
    private var vodPic = ""
    private var flag = ""
    private var epUrl = ""
    private var directUrl = ""
    private var siteKey = ""

    private var vodLines: List<PlayLine> = emptyList()

    // 倍速档位（对齐原项目点击循环 addSpeed；Up/Down 加减 0.25）
    private val speedSteps = listOf(0.5f, 0.75f, 1.0f, 1.25f, 1.5f, 2.0f)
    private var speedIdx = 2 // 默认 1.0x
    private var fastForwarding = false
    private var savedSpeedIdx = 2
    private var playerView: PlayerView? = null
    private var scaleIdx = 0
    private val scaleModes = intArrayOf(
        PlayerView.RESIZE_MODE_FIT,
        PlayerView.RESIZE_MODE_FILL,
        PlayerView.RESIZE_MODE_ZOOM,
    )
    private val scaleNames = arrayOf("等比", "拉伸", "裁剪")

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_play)

        vodId = intent.getStringExtra("vod_id") ?: ""
        vodName = intent.getStringExtra("vod_name") ?: ""
        vodPic = intent.getStringExtra("vod_pic") ?: ""
        flag = intent.getStringExtra("flag") ?: ""
        epUrl = intent.getStringExtra("ep_url") ?: ""
        directUrl = intent.getStringExtra("direct_url") ?: ""
        siteKey = SubscriptionStore(this).currentKey() ?: MainActivity.DEFAULT_KEY

        playerView = findViewById<PlayerView>(R.id.playView)
        player = ExoPlayer.Builder(this).build().also { playerView?.player = it }
        findViewById<TextView>(R.id.playTitle).text = vodName.ifBlank { "播放中" }

        findViewById<View>(R.id.btnBack).setOnClickListener { finish() }
        // 倍速：点击循环档位，对齐原项目 addSpeed()
        val speedBtn = findViewById<TextView>(R.id.btnSpeed)
        speedBtn.setOnClickListener { toggleSpeed() }
        // 长按倍速快进（对齐原项目 onSpeedUp：长按 3x，松开恢复）
        speedBtn.setOnLongClickListener {
            if (PlayerManager.shouldUseMpv(this)) return@setOnLongClickListener true
            fastForwarding = true
            savedSpeedIdx = speedIdx
            speedBtn.text = "快进 3x（松开恢复）"
            player?.playbackSpeed = 3f
            true
        }
        // 缩放档位循环（对齐原项目 onScale/setScale）
        findViewById<TextView>(R.id.btnScale).setOnClickListener { toggleScale() }

        // 直播直链：直接播放；影片：先拉详情拿线路（供选集面板），再解析播放
        if (directUrl.isNotBlank()) {
            playUrl(directUrl)
            recordHistory()
        } else {
            Thread { fetchAndPlay(vodId, flag, epUrl) }.start()
        }
    }

    /** 拉详情（取线路列表）→ 解析指定线路/集播放 */
    private fun fetchAndPlay(vid: String, fl: String, ep: String) {
        runCatching {
            val detail = Engine.execute("detail", siteKey, JSONObject().put("ids", vid).toString())
            val v = Vod.parseList(detail).firstOrNull()
            v?.let { vodLines = it.lines() }
        }
        resolvePlay(fl, ep)
    }

    /** 调用引擎 player 接口解析出播放地址 */
    private fun resolvePlay(fl: String, ep: String) {
        runCatching {
            val params = JSONObject()
                .put("flag", fl)
                .put("id", if (ep.isNotBlank()) ep else vodId)
                .toString()
            val play = Engine.execute("player", siteKey, params)
            val url = JSONObject(play).getString("url")
            flag = fl
            epUrl = ep
            runOnUiThread {
                playUrl(url)
                recordHistory()
            }
        }.onFailure { e ->
            runOnUiThread { Toast.makeText(this, "解析失败：${e.message}", Toast.LENGTH_LONG).show() }
        }
    }

    private fun playUrl(url: String) {
        if (url.isBlank()) {
            Toast.makeText(this, "未解析到播放地址", Toast.LENGTH_LONG).show()
            return
        }
        // mpv 内核：交给外部播放器
        if (PlayerManager.shouldUseMpv(this)) {
            if (PlayerManager.launchMpv(this, url, vodName)) {
                finish()
                return
            }
        }
        // 内置 ExoPlayer
        player?.apply {
            setMediaItem(MediaItem.fromUri(url))
            prepare()
            playWhenReady = true
        }
    }

    private fun recordHistory() {
        HistoryStore(this).addHistory(vodId, vodName, vodPic, "", flag, epUrl)
    }

    /** 倍速：点击循环档位（对齐原项目 onSpeed()/addSpeed()） */
    private fun toggleSpeed() {
        speedIdx = (speedIdx + 1) % speedSteps.size
        applySpeed()
    }

    /** 缩放：点击循环档位（对齐原项目 onScale/setScale） */
    private fun toggleScale() {
        if (PlayerManager.shouldUseMpv(this)) return // 外部播放器由 mpv 控制
        scaleIdx = (scaleIdx + 1) % scaleModes.size
        playerView?.resizeMode = scaleModes[scaleIdx]
        findViewById<TextView>(R.id.btnScale).text = "缩放 ${scaleNames[scaleIdx]}"
    }

    private fun applySpeed() {
        val s = speedSteps[speedIdx]
        findViewById<TextView>(R.id.btnSpeed).text = "倍速 ${s}x"
        // mpv 内核交给外部，倍速由外部播放器控制
        if (PlayerManager.shouldUseMpv(this)) return
        player?.playbackSpeed = s
    }

    /** 松开 OK 键 → 若处于长按快进，恢复原速（对齐原项目 onSpeedEnd） */
    override fun onKeyUp(keyCode: Int, event: KeyEvent): Boolean {
        if (fastForwarding && (keyCode == KeyEvent.KEYCODE_DPAD_CENTER || keyCode == KeyEvent.KEYCODE_ENTER)) {
            fastForwarding = false
            speedIdx = savedSpeedIdx
            applySpeed()
            return true
        }
        return super.onKeyUp(keyCode, event)
    }

    /** 菜单键 → 选集/换源面板 */
    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        if (keyCode == KeyEvent.KEYCODE_MENU) {
            if (vodLines.isNotEmpty()) {
                val cl = vodLines.indexOfFirst { it.name == flag }
                val clIdx = if (cl >= 0) cl else 0
                val ce = runCatching {
                    vodLines.getOrNull(clIdx)?.episodes?.indexOfFirst { it.second == epUrl }
                }.getOrDefault(-1)
                EpisodeDialog(this, vodLines, { li, ei ->
                    val line = vodLines[li]
                    val ep = line.episodes[ei].second
                    Thread { resolvePlay(line.name, ep) }.start()
                }, clIdx, ce).show()
                return true
            } else {
                Toast.makeText(this, "暂未获取到线路信息（请稍候再按菜单键）", Toast.LENGTH_LONG).show()
                return true
            }
        }
        return super.onKeyDown(keyCode, event)
    }

    override fun onDestroy() {
        super.onDestroy()
        player?.release()
        player = null
    }
}
