package com.company.udpxytv.ui

import android.content.Context
import android.media.AudioManager
import android.view.MotionEvent
import android.widget.TextView
import android.net.Uri
import android.provider.Settings
import android.widget.Toast
import android.media.AudioFocusRequest
import android.media.AudioAttributes
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import android.os.Bundle
import android.view.View
import android.view.WindowManager
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.company.udpxytv.databinding.ActivityPlayerBinding
import org.videolan.libvlc.LibVLC
import org.videolan.libvlc.Media
import org.videolan.libvlc.MediaPlayer
import org.videolan.libvlc.interfaces.AbstractVLCEvent
import org.videolan.libvlc.util.DisplayManager
import java.util.concurrent.CopyOnWriteArrayList

class PlayerActivity : AppCompatActivity() {

    private lateinit var b: ActivityPlayerBinding
    private var libVlc: LibVLC? = null
    private var player: MediaPlayer? = null
    private var displayManager: DisplayManager? = null
    private var retryCount = 0
    private var currentUrl: String = ""
    private var waitingForPlayback = false

    /**
     * libvlc 音量基线：恒定 256，对应 **0dB（标称增益 1.0）**。
     *
     * 【为什么是 256，而不是 100 或 200】
     * MediaPlayer.setVolume(int) 的 javadoc 没写范围，但底层
     * libvlc_audio_set_volume() 按 VLC 3.x 的 include/vlc_aout.h 换算：
     *     #define AOUT_VOLUME_DEFAULT  256   <- 0dB 在这里
     *     #define AOUT_VOLUME_MAX      512   <- +6dB
     * 而 aout_VolumeSet() 收的是 float，其注释写明 "1. = nominal"，且不 clamp。
     * 所以真实增益 = int/256：100 是 -8.2dB，200 是 -2.1dB，256 才是 0dB。
     *
     * 真正决定响度的仍是系统 STREAM_MUSIC——音量键改它、状态栏显示它、
     * 这里的滑动手势也改它，三者同一条路径。libvlc 这一侧只保证"不额外削减"，
     * 不作为独立的音量轴。
     */
    private val VLC_VOL_BASELINE = 256

    /** 手势音量意图 0f~100f；-1f 表示本次手势尚未从系统当前值起算 */
    private var volumeIntent = -1f

    /** 本次触摸是否已判定为滑动手势（避免滑动结束被误判为单击） */
    private var slideTriggered = false

    /** 退出时释放 libvlc 用的后台单线程，保证 player 先于 LibVLC 实例释放 */
    private val releaseExec by lazy {
        java.util.concurrent.Executors.newSingleThreadExecutor()
    }

    /**
     * 是否已切到后台。
     *
     * isFinishing 只在 finish() 时为 true，普通退后台（HOME / 切任务）它仍是 false，
     * 所以光靠 isFinishing 把关，已经排队的重试照样会在后台触发 prepareAgain()
     * ——表现为"退到后台后自己又播起来，还出声"。
     */
    private var backgrounded = false

    /** 手势亮度意图 0f~100f；-1f 表示本次手势尚未从系统当前值起算 */
    private var brightnessIntent = -1f

    /** 未授予「修改系统设置」时降级到窗口亮度，只提示一次 */
    private var warnedBrightnessPerm = false

    /** 进入播放时的系统音量，退出时原样写回 */
    private var preVolume = -1

    /** 进入播放时的系统亮度（0..max），退出时原样写回 */
    private var preBrightness = -1

    /** 进入播放时的亮度模式：0=手动 1=自动，退出时原样写回 */
    private var preBrightnessMode = -1

    /**
     * LibVLC 全局选项（libvlc_new 的 argv）。
     *
     * 【重要】LibVLC(Context, List) 会把列表原样传给 libvlc_new(argc, argv)，
     * 而 VLC 只把带 "--" 前缀的字符串识别为选项。裸字符串会被当成待播放的 MRL，
     * 导致 "unable to open the MRL 'file:////xxx'" 错误，且该选项完全不生效。
     * 所以这里每一项都必须带 "--"；media 级选项（media.addOption(":xxx")）
     * 用单冒号，不要加 "--"。
     *
     * 【关键约束】这里只允许放 libvlc **核心层**的命令行选项。
     *
     * 踩过的坑：VLC 3.7.x 对未注册的全局选项是**致命错误**——
     * libvlc_new() 直接返回 NULL，Java 侧抛 "can't create LibVLC instance"，
     * 应用起不来。drop-late-frames / skip-frames / deinterlace* /
     * video-title-show / avcodec-threads 这些是**模块级**选项
     * （vout / avcodec），在 libvlc_new 解析阶段尚未注册，传全局必炸。
     *
     * 判断依据不能是"字符串在 so 里存在"——那只说明代码里有这个名字，
     * 不代表它注册为全局命令行参数。必须查 libvlc-module.c 的核心项。
     *
     * 模块级选项请放到 applyMediaOptions()，那里用单冒号，
     * 未知项会被静默忽略，不会导致启动失败。
     */
    private val options by lazy {
        CopyOnWriteArrayList(arrayOf(
            // 仅这三项是 libvlc 核心项，全局传递安全
            "--network-caching=1000",  // 见下方 applyMediaOptions 的实测取舍说明
            "--live-caching=1000",       // 直播缓冲
            "--file-caching=1500"       // http/udpxy 走不到此参数，实际生效值由 media 级决定
        ))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        b = ActivityPlayerBinding.inflate(layoutInflater)
        setContentView(b.root)

        val name = intent.getStringExtra(EXTRA_NAME).orEmpty()
        val url = intent.getStringExtra(EXTRA_URL).orEmpty()
        b.tvChannelName.text = name
        b.tvUrl.text = url
        b.btnBack.setOnClickListener { finish() }
        b.btnReconnect.setOnClickListener { retryCount = 0; startPlayback(url) }
        b.btnPlayPause.setOnClickListener { togglePlayPause() }

        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowInsetsControllerCompat(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }

        // 亮度与音量一样以系统为唯一权威：先记下进入时的状态（退出时还原），
        // 再让窗口回到「跟随系统」，否则残留的窗口覆盖会在系统值之上再乘一层
        captureSystemState()
        val lp0 = window.attributes
        if (lp0.screenBrightness != WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE) {
            lp0.screenBrightness = WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE
            window.attributes = lp0
        }
        brightness = currentSystemBrightness() / screenBrightnessMax().toFloat()
        setupGesture()
        showPanel()

        libVlc = LibVLC(this, options)
        displayManager = DisplayManager(this, null, false, true, false)

        if (url.isEmpty()) {
            // 地址为空时重连多少次都不会成功，不给「重连」按钮
            showError("没有拿到播放地址", retryable = false)
        } else if (!isOnLan()) {
            // udpxy 在局域网内，蜂窝下必然连不上——直接说清楚，别让人对着错误猜
            showError("当前不在局域网\n请连接 WiFi 后重试（udpxy 走局域网单播）")
            b.btnReconnect.visibility = View.VISIBLE
        } else {
            startPlayback(url)
        }
    }

    private fun startPlayback(url: String) {
        // 空地址是唯一的不可重试情形。这里挡在入口而不是只挡按钮回调，
        // 顺带覆盖 prepareAgain() 的自动重试路径。
        if (url.isEmpty()) {
            showError("没有拿到播放地址", retryable = false)
            return
        }
        val lc = libVlc ?: return
        releasePlayer()
        currentUrl = url
        retryCount = 0
        b.btnReconnect.visibility = View.GONE
        waitingForPlayback = true
        showPanel()
        showStatus("正在连接…", com.company.udpxytv.R.color.player_muted)
        // 起播兜底：waitingForPlayback 一直为 true 时（比如源根本起不来，
        // 既不 Playing 也不报错）不能永远停在"正在连接…"
        b.root.postDelayed({
            if (waitingForPlayback && !isFinishing && !backgrounded) {
                handleError("起播超时")
            }
        }, START_TIMEOUT_MS)

        val media = Media(lc, Uri.parse(url))
        // 硬解优先：优先尝试硬件，VLC 在硬解初始化失败时会自动回退软解
        // 顺序要求：先设缓存（置标志），再开硬解，否则会被注入 1500 覆盖
        applyMediaOptions(media)
        media.setHWDecoderEnabled(true, true)
        // 焦点拿不到就别播：否则会和占用方同时发声。
        // 恢复播放那条路径（requestFocusThenResume）本来就检查了，这里以前漏了。
        if (!requestAudioFocus()) {
            showError("音频被其它应用占用\n请先暂停占用音频的应用", retryable = true)
            return
        }

        val p = MediaPlayer(lc)
        player = p
        p.setVideoScale(MediaPlayer.ScaleType.SURFACE_BEST_FIT)
        p.attachViews(b.videoLayout, displayManager, true, false)
        p.setEventListener { ev: AbstractVLCEvent -> onVlcEvent(ev) }
        p.play(media)
        // play() 内部已对 Media 持引用，本地这份可以放手；
        // 不放的话每次换源/重试都泄一个 native Media
        media.release()
    }

    private fun onVlcEvent(ev: AbstractVLCEvent) {
        when (ev.type) {
            MediaPlayer.Event.Playing -> runOnUiThread {
                waitingForPlayback = false
                // 真的播起来了才开始采样，之前采到的都是起播期的噪声
                startStatsSampling()
                // 这次真的播起来了 = 本轮流通了，重试预算重新计算。
                // 否则多次彼此独立的短暂断流会一路累计到 5 次，
                // 最后误报"流持续中断"并停止自动重试。
                retryCount = 0
                showPanel()
                updatePlayPauseButton()
                showStatus("● 播放中", com.company.udpxytv.R.color.player_accent)
                // 解码器信息延后查询，避免影响起播速度
                b.root.postDelayed({
                    if (!isFinishing && !backgrounded) {
                        val tag = detectDecoder()
                        if (tag.isNotEmpty()) {
                            showStatus("● 播放中  $tag", com.company.udpxytv.R.color.player_accent)
                        }
                    }
                }, 800L)
            }
            MediaPlayer.Event.Buffering -> runOnUiThread {
                // 缓冲事件在正常播放时也会周期性到达（缓冲已填满的稳态）。
                // 所以只在起播阶段显示：一旦盖住「● 播放中 + 解码器信息」，
                // 那行就再也看不到了（实测画面正常播放，状态却长期停在"缓冲中…"）。
                // 播中真断流由 EndReached / EncounteredError 负责，不靠这个事件。
                if (waitingForPlayback) {
                    showStatus("缓冲中…", com.company.udpxytv.R.color.player_muted)
                }
            }
            MediaPlayer.Event.EndReached -> runOnUiThread { handleStreamEnd() }
            MediaPlayer.Event.EncounteredError -> runOnUiThread { handleError("播放错误") }
        }
    }

    private fun handleStreamEnd() {
        waitingForPlayback = true
        showPanel()
        if (retryCount < MAX_RETRY) {
            retryCount++
            showStatus("流中断，重连中 ($retryCount/$MAX_RETRY)…", com.company.udpxytv.R.color.player_error)
            b.root.postDelayed(
                { if (!isFinishing && !backgrounded) prepareAgain() },
                1500L * retryCount
            )
        } else {
            showError("流持续中断，请检查组播源或网关")
        }
    }

    private fun handleError(msg: String) {
        waitingForPlayback = true
        showPanel()
        if (retryCount < MAX_RETRY) {
            retryCount++
            showStatus("$msg，重试 $retryCount/$MAX_RETRY", com.company.udpxytv.R.color.player_error)
            b.root.postDelayed(
                { if (!isFinishing && !backgrounded) prepareAgain() },
                1200L * retryCount
            )
        } else {
            showError("无法播放：$msg\n请确认手机与 udpxy 网关在同一网段，且 udpxy 已开启")
        }
    }

    private fun prepareAgain() {
        val lc = libVlc ?: return
        val p = player ?: return
        val media = Media(lc, Uri.parse(currentUrl))
        // 顺序要求：先设缓存（置标志），再开硬解，否则会被注入 1500 覆盖
        applyMediaOptions(media)
        media.setHWDecoderEnabled(true, true)
        p.play(media)
        media.release()
    }

    /**
     * 播放信息层里显示的流信息。
     *
     * 【踩过的坑】这批组播源的 TS 里，视频和音频**各被声明了 2 条 ES**：
     *     v=2 [Disable, Track 1]   a=2 [Disable, Track 1]
     * 第一条是 VLC 给未选中轨用的占位名 "Disable"。原先取 firstOrNull() 的 name，
     * 正好取到占位轨，信息层就显示成 "Disable + Disable"。
     * ffprobe 侧证实源里其实只有 1 条视频流 + 1 条音频流，第二条是 VLC 自己造的。
     *
     * 【为什么不显示编码名】想把编码名显示准一点，查下来 libvlc-android 3.7.6
     * 的 Java 层压根没有 codec 查询接口：getVideoTracks()/getAudioTracks() 返回的
     * TrackDescription 只有 id 和 name 两个字段（javap 确认）；codec / description /
     * fourcc 都在 IMedia.Track 上，没有对外入口。真轨的 name 也只是 "Track 1"。
     *
     * 所以规则：**只显示有意义的轨道名，"Disable" 和 "Track N" 都跳过，
     * 一个都没有就不显示**。不拿占位名充数，也不编。
     *
     * 【刻意不显示硬解/软解】曾按 track 名里是否含 OMX/C2./MTK 判断，
     * 但 name 是**流编码名**，不是解码器组件名，判断必然失真。
     * 要确认硬解是否生效请看 logcat：
     *   adb logcat | grep -E "CreateByComponentName|video-debug-dec.*Stats"
     */
    private fun detectDecoder(): String {
        val p = player ?: return ""
        return try {
            val v = pickTrack(p.videoTracks)
            val a = pickTrack(p.audioTracks)
            val out = listOf(v, a).filter { it.isNotEmpty() }.joinToString(" + ")
            android.util.Log.i(
                "LanTv",
                "tracks v=" + p.videoTracks?.joinToString { it.name } +
                    " a=" + p.audioTracks?.joinToString { it.name } + " -> '" + out + "'"
            )
            out
        } catch (e: Exception) {
            ""
        }
    }

    /**
     * 挑一条有意义的轨道名。
     * "Disable" 是未选中轨的占位名，"Track N" 是 demux 给的序号，两者都跳过。
     */
    private fun pickTrack(tracks: Array<MediaPlayer.TrackDescription>?): String {
        if (tracks == null) return ""
        for (t in tracks) {
            val n = t.name?.trim().orEmpty()
            if (n.isEmpty()) continue
            if (n.equals("Disable", ignoreCase = true)) continue
            if (n.startsWith("Track ")) continue
            return n
        }
        return ""
    }

    // ---- 运行时码率 / 帧率：3 秒滑动窗口 ----
    // 复用 VLC 已经在拉的那一路流，不额外建立 udpxy 连接
    private data class RateSample(val bytes: Long, val at: Long)

    private val statsHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private val rateSamples = ArrayDeque<RateSample>()
    private var lastReadBytes = -1L
    private var currentKbps = 0
    private var statsSettled = false

    private val statsTicker = object : Runnable {
        override fun run() {
            sampleStreamStats()
            // 面板隐藏时继续采样，打开即可见最新值；可见时才刷文字
            if (b.infoPanel.visibility == View.VISIBLE) refreshStreamInfo()
            statsHandler.postDelayed(this, STATS_SAMPLE_MS)
        }
    }

    /** 刷新信息层里的实时码率行；没有内容就隐藏 */
    private fun refreshStreamInfo() {
        val txt = streamStatsText()
        if (txt.isEmpty()) {
            b.tvStreamInfo.visibility = View.GONE
        } else {
            b.tvStreamInfo.text = txt
            b.tvStreamInfo.visibility = View.VISIBLE
        }
    }

    /**
     * 采样实时接收码率。
     *
     * 【踩过的坑：不能用 libvlc 的 stats】原本用 Media.getStats().readBytes，
     * 真机直接崩：
     *     signal 11 (SIGSEGV), code 1 (SEGV_MAPERR), fault addr 0x8
     *     #00 Java_org_videolan_libvlc_Media_nativeGetStats+52
     *     Cause: null pointer dereference
     * 那个 native 调用要 VLC 自己的锁，从 Android 主线程的 Handler 调会竞态，
     * nativeGetStats 里解引用了空指针。libvlc-android 3.7.6 这个 API 不可用。
     *
     * 所以改走 Android 自己的
     *     TrafficStats.getUidRxBytes(Process.myUid())
     * 读本进程的网络接收字节数。播放期间 App 只有这一条流，口径等价，
     * 而且完全不进 libvlc，没有 native 崩溃面。对自己 uid 查询不需要权限。
     *
     * 【窗口怎么算】窗口内**总字节增量 x 8 / 总实际毫秒数**，而不是
     * "取 3 个速率的平均"。主线程偶尔卡顿时各次采样间隔不相等，
     * 简单平均会把快慢不均的样本混在一起；用总增量除以总时长才成立。
     * 公式：kbps = dBytes * 8 / dMillis
     *
     * 【帧率拿不到】原本打算用 displayedPictures，同属 IMedia.Stats，
     * 一起放弃了。显示文本里因此只给码率。
     */
    private fun sampleStreamStats() {
        val now = android.os.SystemClock.elapsedRealtime()
        // 接收字节数走 Android 自己的流量统计，**不碰 libvlc**（见上方说明）
        val bytes = android.net.TrafficStats.getUidRxBytes(android.os.Process.myUid())
        if (bytes < 0) return
        val frames = 0L

        if (lastReadBytes >= 0 && bytes < lastReadBytes) {
            rateSamples.clear()
        }
        lastReadBytes = bytes
        rateSamples.addLast(RateSample(bytes, now))

        val cutoff = now - STATS_WINDOW_MS
        while (rateSamples.size > 2 && rateSamples.first().at < cutoff) {
            rateSamples.removeFirst()
        }

        val first = rateSamples.first()
        val dt = now - first.at
        if (dt <= 0) return
        val dBytes = bytes - first.bytes
        if (dBytes < 0) return
        currentKbps = (dBytes * 8.0 / dt).toInt()
        statsSettled = true
    }

    /** 起播后开始采样；重连时旧样本全部作废 */
    private fun startStatsSampling() {
        stopStatsSampling()
        rateSamples.clear()
        lastReadBytes = -1
        currentKbps = 0
        statsSettled = false
        statsHandler.postDelayed(statsTicker, STATS_SAMPLE_MS)
    }

    private fun stopStatsSampling() {
        statsHandler.removeCallbacks(statsTicker)
    }

    /** 信息层里显示的内容，区分统计中 / 已暂停 / 断流归零三种状态 */
    private fun streamStatsText(): String {
        if (isFinishing || backgrounded) return ""
        if (userPaused) return "已暂停"
        if (!statsSettled) return "统计中"
        if (currentKbps <= 0) return "0 kbps"
        val mb = currentKbps / 1000.0
        return if (mb >= 1.0) String.format("%.2f Mbps", mb) else "$currentKbps kbps"
    }



    // ==================== 手势：单击显隐 / 左右滑动调亮度音量 ====================

    private val audioManager by lazy { getSystemService(Context.AUDIO_SERVICE) as AudioManager }
    private var downX = 0f
    private var downY = 0f
    private var brightness = 0.5f
    /**
     * 每个 HUD 各自的自动隐藏任务，按 View 存放。
     *
     * 以前是单个字段：1.2 秒内先滑音量再滑亮度，第二次会 removeCallbacks 掉
     * 第一次的 runnable，于是先显示的那个 HUD 永远不会自动隐藏，
     * 得等下一次点击或切后台才清掉。
     */
    private val hudHideRunnables = mutableMapOf<View, Runnable>()

    /** 是否处于横屏——音量/亮度滑动只在横屏播放时启用 */
    private fun isLandscape(): Boolean {
        // 只认明确的横屏。Activity 已声明 sensorLandscape，正常不会走到
        // UNDEFINED；把"未知"当成横屏会在竖屏场景误开滑动手势。
        return resources.configuration.orientation ==
            android.content.res.Configuration.ORIENTATION_LANDSCAPE
    }

    private fun setupGesture() {
        b.root.setOnTouchListener { _, ev ->
            when (ev.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    // 有操作，取消自动隐藏
                    panelHideRunnable?.let { b.root.removeCallbacks(it) }
                    // 非横屏不保留滑动态留下的 HUD
                    if (!isLandscape()) hideHuds()
                    downX = ev.x
                    downY = ev.y
                    slideTriggered = false
                    // 每次按下都是新起点，从系统当前值重新起算，
                    // 中途按过音量键/在设置里改过亮度都不会失步
                    volumeIntent = -1f
                    brightnessIntent = -1f
                    true
                }

                MotionEvent.ACTION_MOVE -> {
                    val dx = ev.x - downX
                    val dy = ev.y - downY
                    // 垂直位移明显大于水平位移才判定为滑动手势
                    if (isLandscape() &&
                        kotlin.math.abs(dy) > kotlin.math.abs(dx) &&
                        kotlin.math.abs(dy) > SLIDE_THRESHOLD
                    ) {
                        val rightSide = ev.x > b.root.width / 2
                        val delta = -dy / b.root.height * 100f
                        slideTriggered = true
                        if (rightSide) adjustVolume(delta) else adjustBrightness(delta)
                        // 消费掉后续 MOVE，避免重复处理
                        downX = ev.x
                        downY = ev.y
                    }
                    true
                }

                MotionEvent.ACTION_UP -> {
                    val dx = ev.x - downX
                    val dy = ev.y - downY
                    if (!slideTriggered &&
                        kotlin.math.abs(dx) < TAP_THRESHOLD &&
                        kotlin.math.abs(dy) < TAP_THRESHOLD
                    ) {
                        // 判定为单击：显隐信息层
                        togglePanel()
                    }
                    slideTriggered = false
                    volumeIntent = -1f
                    brightnessIntent = -1f
                    true
                }
                else -> {
                    slideTriggered = false
                    volumeIntent = -1f
                    brightnessIntent = -1f
                    false
                }
            }
        }
    }

    /**
     * 调整音量——与音量键走完全相同的路径。
     *
     * 【改的是什么】原先这里只改 player.volume（libvlc 自己的 0..200 刻度），
     * 而音量键改的是系统 STREAM_MUSIC（整数刻度，该机 0..15），两条通道互不
     * 干涉，只有滑到 0 或 200 边界才顺手回写一次系统音量。结果就是滑动到中间
     * 任何位置，状态栏音量都纹丝不动，按音量键后 HUD 也不动。
     *
     * 【现在的规则】系统 STREAM_MUSIC 是唯一权威：
     *   - 滑动手势 -> setStreamVolume(STREAM_MUSIC)
     *   - 音量键    -> 系统自己改同一个流
     *   - libvlc volume 锁在基线（增益 1.0），只保证不额外削减
     * 于是实际响度 = 系统流音量，HUD 显示值也和状态栏完全相同。
     *
     * 【手感】系统流是整数级，滑到底只有 getStreamMaxVolume() 档。
     * 这里用 volumeIntent 保留浮点意图、手势结束后再落回整数级，
     * 避免逐帧取整把细碎位移全部吃掉；同时每次手势都从系统当前值重新起算，
     * 所以中途按过音量键也不会与实际音量脱节。
     */
    private fun adjustVolume(delta: Float) {
        if (delta == 0f || !isLandscape()) return
        val p = player ?: return
        // 归一：libvlc 侧不参与音量轴
        if (p.volume != VLC_VOL_BASELINE) p.volume = VLC_VOL_BASELINE

        val am = audioManager
        val max = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC).coerceAtLeast(1)
        val cur = am.getStreamVolume(AudioManager.STREAM_MUSIC)
        if (volumeIntent < 0f) volumeIntent = cur.toFloat() / max * 100f
        volumeIntent = (volumeIntent + delta).coerceIn(0f, 100f)
        val next = (volumeIntent / 100f * max + 0.5f).toInt().coerceIn(0, max)
        if (next != cur) am.setStreamVolume(AudioManager.STREAM_MUSIC, next, 0)
        showVolumeHud(next.toFloat() / max)
    }

    /**
     * 调整系统亮度——与系统设置里的亮度条是同一个值。
     *
     * 【改的是什么】原先只改 window.attributes.screenBrightness，那是**窗口级**
     * 覆盖：系统亮度条纹丝不动，且最终屏幕亮度 = 系统亮度 x 窗口系数，
     * 两者永远对不上。现在直接写 Settings.System.SCREEN_BRIGHTNESS，
     * 系统下拉栏的亮度滑条会跟着一起动。
     *
     * 【权限】写这个值需要「修改系统设置」(WRITE_SETTINGS) 特殊权限，
     * 设置页有授权入口。没授权时降级为窗口亮度并提示一次。
     *
     * 【两个附带处理】
     *   - 自动亮度会把手写进去的值按传感器覆盖回去，所以先切成手动模式；
     *     退出播放时 preBrightnessMode 会把原来的模式还原。
     *   - 下限留 1 级而不是 0：全黑之后看不见 HUD，也就没法再滑回来。
     */
    private fun adjustBrightness(delta: Float) {
        if (delta == 0f || !isLandscape()) return
        val max = screenBrightnessMax()
        if (!Settings.System.canWrite(this)) {
            if (!warnedBrightnessPerm) {
                warnedBrightnessPerm = true
                Toast.makeText(
                    this,
                    "未授予「修改系统设置」，亮度暂只在本应用内生效",
                    Toast.LENGTH_LONG
                ).show()
            }
            applyWindowBrightness((brightness + delta / 100f).coerceIn(0.02f, 1f))
            return
        }
        val cur = currentSystemBrightness().coerceIn(0, max)
        if (brightnessIntent < 0f) brightnessIntent = cur.toFloat() / max * 100f
        brightnessIntent = (brightnessIntent + delta).coerceIn(0f, 100f)
        val next = (brightnessIntent / 100f * max + 0.5f).toInt().coerceIn(1, max)
        Settings.System.putInt(contentResolver, Settings.System.SCREEN_BRIGHTNESS_MODE, 0)
        Settings.System.putInt(contentResolver, Settings.System.SCREEN_BRIGHTNESS, next)
        brightness = next.toFloat() / max
        showBrightnessHud(brightness)
    }

    /** 降级路径：只改本窗口亮度（此时与系统亮度条不可能一致） */
    private fun applyWindowBrightness(value: Float) {
        brightness = value
        val lp = window.attributes
        lp.screenBrightness = value
        window.attributes = lp
        showBrightnessHud(value)
    }

    /**
     * 系统亮度的上限（0..config_screenBrightnessSettingMaximum）。
     * 这个值因 ROM 而异（常见 255，也有 1023），写死会越界。
     */
    private fun screenBrightnessMax(): Int {
        return try {
            val id = resources.getIdentifier(
                "config_screenBrightnessSettingMaximum", "integer", "android"
            )
            if (id > 0) resources.getInteger(id) else 255
        } catch (t: Throwable) {
            255
        }
    }

    /**
     * 当前系统亮度（0..max），只读不写。
     *
     * 系统里还没被写过时（getInt 返回 -1），本窗口若还留着亮度覆盖就沿用它，
     * 否则取中间值。注意 Display.getBrightnessInfo() 不在 public SDK 里，
     * 不能直接问显示管理层要当前实际亮度。
     */
    private fun currentSystemBrightness(): Int {
        val max = screenBrightnessMax()
        val raw = try {
            Settings.System.getInt(contentResolver, Settings.System.SCREEN_BRIGHTNESS, -1)
        } catch (t: Throwable) {
            -1
        }
        if (raw in 0..max) return raw
        val win = window.attributes.screenBrightness
        if (win >= 0f) return (win * max).toInt().coerceIn(0, max)
        return max / 2
    }

    /** 记录进入播放前的系统音量与亮度，退出时原样还原 */
    private fun captureSystemState() {
        preVolume = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)
        preBrightness = currentSystemBrightness()
        preBrightnessMode = try {
            Settings.System.getInt(contentResolver, Settings.System.SCREEN_BRIGHTNESS_MODE, -1)
        } catch (t: Throwable) {
            -1
        }
    }

    /**
     * 退出播放时恢复进入前的系统音量与亮度。
     * 窗口覆盖也一并清掉，否则会污染之后的页面。
     */
    private fun restoreSystemState() {
        if (preVolume >= 0) {
            val am = audioManager
            val max = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
            am.setStreamVolume(AudioManager.STREAM_MUSIC, preVolume.coerceIn(0, max), 0)
        }
        if (Settings.System.canWrite(this)) {
            if (preBrightnessMode >= 0) {
                Settings.System.putInt(
                    contentResolver, Settings.System.SCREEN_BRIGHTNESS_MODE, preBrightnessMode
                )
            }
            if (preBrightness >= 0) {
                Settings.System.putInt(
                    contentResolver, Settings.System.SCREEN_BRIGHTNESS, preBrightness
                )
            }
        }
        val lp = window.attributes
        if (lp.screenBrightness != WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE) {
            lp.screenBrightness = WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE
            window.attributes = lp
        }
    }

    /** 显示音量 HUD（竖条），level 为 0f~1f */
    private fun showVolumeHud(level: Float) {
        b.volumeHud.visibility = View.VISIBLE
        b.volumeHud.animate().cancel()
        b.volumeHud.alpha = 1f
        b.volumeBar.setProgressCompat((level * 100).toInt(), true)
        scheduleHudHide(b.volumeHud)
    }

    /** 显示亮度 HUD（竖条），level 为 0f~1f */
    private fun showBrightnessHud(level: Float) {
        b.brightnessHud.visibility = View.VISIBLE
        b.brightnessHud.animate().cancel()
        b.brightnessHud.alpha = 1f
        b.brightnessBar.setProgressCompat((level * 100).toInt(), true)
        scheduleHudHide(b.brightnessHud)
    }

    /** HUD 显示后 1.2s 自动淡出 */
    private fun scheduleHudHide(hud: View) {
        hudHideRunnables.remove(hud)?.let { b.root.removeCallbacks(it) }
        val r = Runnable {
            hudHideRunnables.remove(hud)
            hud.animate().alpha(0f).setDuration(280).withEndAction {
                hud.visibility = View.GONE
                hud.alpha = 1f
            }.start()
        }
        hudHideRunnables[hud] = r
        b.root.postDelayed(r, 1200L)
    }

    /** 隐藏所有 HUD */
    private fun hideHuds() {
        hudHideRunnables.keys.toList().forEach { hud ->
            hudHideRunnables.remove(hud)?.let { b.root.removeCallbacks(it) }
        }
        listOf(b.volumeHud, b.brightnessHud).forEach { it.visibility = View.GONE }
    }

    private var panelHideRunnable: Runnable? = null

    /**
     * media 级选项。**调用顺序至关重要**。
     *
     * 【刻意不指定解码器】video-decoder / audio-decoder / hw-decoder
     * 在 libvlc 中从来不是变量名（so 里检索 0 次），VLC 3 选解码器用 --codec=<模块>
     * （mediacodec_ndk / iomx / avcodec），那是"后端"命名空间，
     * 与 Android MediaCodec 组件名（c2.mtk.avc.decoder）不是一回事，
     * 无法指定到组件级。硬解统一由 Media.setHWDecoderEnabled 控制。
     *
     * Media.addOption() 会按前缀把 mNetworkCachingSet / mFileCachingSet / mCodecOptionSet 置 true，
     * 而 setHWDecoderEnabled() 内部会先检查这些标志：
     *     58: getfield mFileCachingSet
     *     62: ifne 72          ← 已设过就跳过 :file-caching=1500
     *     72: getfield mNetworkCachingSet
     *     76: ifne 86          ← 已设过就跳过 :network-caching=1500
     *
     * 所以本方法必须在 setHWDecoderEnabled() 之前调用，
     * 缓存值才不会被它重新注入 1500 覆盖。
     *
     * 缓存值不要盲目调小：曾降到 800 想缩短"起播后前 5 秒帧率爬升"，
     * 结果视频 ES 探测直接失败（音频能起、解码器不创建、AudioFlinger underrun）。
     * 那 5 秒的瓶颈在 demux/ES 探测，不在输出缓冲，压缓冲只会压死起播。
     */
    private fun applyMediaOptions(media: Media) {
        // 直播缓冲。必须先设，才能阻止 setHWDecoderEnabled 注入 1500。
        //
        // 【实测数据】800ms 与 1500ms 的取舍，取决于设备负载：
        //   800ms  设备空闲：起播 1.3s，首条 Stats Qin 109-117 / Render 83-92（更快）
        //   800ms  有负载：视频 ES 探测失败，解码器不创建 → AudioFlinger underrun
        //   1500ms 两种场景都能起播，但前 5 秒 Qin 100 / Render 73（更慢）
        //
        // 失败全部出现在"刚安装 + ProfileInstaller 后台编译"期间
        // （VLC 消费变慢，缓冲被抽干，demux 拿不到 H.264 的 SPS/PPS/CSD，
        //   音频不需要 CSD 所以先起来，视频解码器打不开）。
        // 1000 是折中：保留大部分收益，换回余量。
        media.addOption(":network-caching=1000")
        // 刻意不设 :file-caching —— 那是 file:// access 的参数，http/udpxy 走不到；
        // 留着 false 让 setHWDecoderEnabled 按原生行为注入，不引入无意义覆盖。

        // ---- 以下均为模块级选项（vout / avcodec）----
        // 放 media 级而非全局：全局传未注册选项会让 libvlc_new() 返回 NULL，
        // 直接抛 "can't create LibVLC instance" 起不来。
        // media 级用单冒号，未知项静默忽略。
        media.addOption(":drop-late-frames=20")      // 温和丢帧，避免积压
        media.addOption(":skip-frames=10")
        media.addOption(":clock-jitter=0")
        media.addOption(":clock-synchro=0")
        media.addOption(":deinterlace=0")            // 本流 1080p 逐行，无需去隔行
        media.addOption(":deinterlace-mode=blend")
        media.addOption(":video-title-show=0")       // 不显示文件名
        // 软解回退路径的 CPU 限制
        media.addOption(":avcodec-threads=2")
        media.addOption(":avcodec-skiploopfilter=0")
    }

    // ==================== 生命周期：后台暂停 / 回来恢复 ====================

    /** 用户主动暂停（点按钮）时置位，返回前台不自动恢复 */
    private var userPaused = false
    /** 从后台返回时自动恢复播放 */
    private var resumeOnForeground = false

    @Suppress("DEPRECATION")
    override fun onStart() {
        super.onStart()
        backgrounded = false
        if (resumeOnForeground && !userPaused) {
            resumeOnForeground = false
            requestFocusThenResume()
        }
    }

    @Suppress("DEPRECATION")
    override fun onResume() {
        super.onResume()
        if (resumeOnForeground && !userPaused) {
            resumeOnForeground = false
            requestFocusThenResume()
        }
    }

    /** 重新申请音频焦点；拿到后恢复播放 */
    private fun requestFocusThenResume() {
        if (requestAudioFocus()) {
            player?.play()
            updatePlayPauseButton()
        } else {
            // 焦点被系统长期占用时不自动抢，先告知用户
            showStatus("音频被其它应用占用，请手动恢复", com.company.udpxytv.R.color.player_error)
        }
    }

    // ==================== 音频焦点 ====================

    private var audioFocusRequest: AudioFocusRequest? = null
    private var hasAudioFocus = false

    /**
     * API 24/25 走 legacy 申请接口，那个接口靠 listener 实例识别焦点持有者。
     * 早先放弃焦点时传的是 abandonAudioFocus(null)，等于什么都没放弃。
     */
    private var legacyFocusListener: AudioManager.OnAudioFocusChangeListener? = null

    /**
     * 申请音频焦点。minSdk 24：API 26+ 用 AudioFocusRequest，24/25 走 legacy。
     */
    private fun requestAudioFocus(): Boolean {
        val am = audioManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            if (audioFocusRequest == null) {
                audioFocusRequest = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
                    .setAudioAttributes(
                        AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_MEDIA)
                            .setContentType(AudioAttributes.CONTENT_TYPE_MOVIE)
                            .build()
                    )
                    // 系统暂时打断（来电/闹钟）自动暂停，转为可播放时回调
                    .setOnAudioFocusChangeListener { change ->
                        when (change) {
                            AudioManager.AUDIOFOCUS_LOSS -> {
                                userPaused = true
                                player?.pause()
                                updatePlayPauseButton()
                            }
                            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> {
                                player?.pause()
                            }
                            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> {
                                player?.volume = VLC_VOL_BASELINE / 4
                            }
                            AudioManager.AUDIOFOCUS_GAIN -> {
                                player?.volume = VLC_VOL_BASELINE
                                if (!userPaused) player?.play()
                                updatePlayPauseButton()
                            }
                        }
                    }
                    .build()
            }
            hasAudioFocus = am.requestAudioFocus(audioFocusRequest!!) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
        } else {
            val listener = AudioManager.OnAudioFocusChangeListener { change ->
                    when (change) {
                        AudioManager.AUDIOFOCUS_LOSS -> {
                            userPaused = true
                            player?.pause()
                            updatePlayPauseButton()
                        }
                        AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> player?.pause()
                        AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> player?.volume = VLC_VOL_BASELINE / 4
                        AudioManager.AUDIOFOCUS_GAIN -> {
                            player?.volume = VLC_VOL_BASELINE
                            if (!userPaused) player?.play()
                            updatePlayPauseButton()
                        }
                }
            }
            legacyFocusListener = listener
            @Suppress("DEPRECATION")
            val result = am.requestAudioFocus(
                listener,
                AudioManager.STREAM_MUSIC,
                AudioManager.AUDIOFOCUS_GAIN
            )
            @Suppress("DEPRECATION")
            hasAudioFocus = result == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
        }
        return hasAudioFocus
    }

    /** 放弃音频焦点 */
    private fun abandonAudioFocus() {
        if (!hasAudioFocus) return
        hasAudioFocus = false
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            audioFocusRequest?.let { audioManager.abandonAudioFocusRequest(it) }
        } else {
            legacyFocusListener?.let {
                @Suppress("DEPRECATION")
                audioManager.abandonAudioFocus(it)
            }
            legacyFocusListener = null
        }
    }

    /** 播放/暂停按钮 */
    private fun updatePlayPauseButton() {
        val playing = player?.isPlaying == true
        b.btnPlayPause.setText(if (playing) "暂停" else "播放")
    }

    private fun togglePlayPause() {
        if (player?.isPlaying == true) {
            userPaused = true
            player?.pause()
            abandonAudioFocus()
        } else {
            userPaused = false
            if (requestAudioFocus()) {
                player?.play()
            } else {
                showStatus("音频被其它应用占用", com.company.udpxytv.R.color.player_error)
            }
        }
        updatePlayPauseButton()
    }

    // ==================== 网络类型检测 ====================

    /**
     * udpxy 是局域网服务，蜂窝数据下必然连不上。
     * 播放前先判断，非 WiFi 时给可执行提示，而不是让人对着"无法播放"猜原因。
     */
    private fun isOnLan(): Boolean {
        val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return true
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                val n = cm.activeNetwork ?: return true
                val caps = cm.getNetworkCapabilities(n) ?: return true
                caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ||
                    caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)
            } else {
                @Suppress("DEPRECATION")
                val info = cm.activeNetworkInfo ?: return true
                info.type == ConnectivityManager.TYPE_WIFI ||
                    info.type == ConnectivityManager.TYPE_ETHERNET
            }
        } catch (e: Exception) {
            true
        }
    }

    private fun togglePanel() {
        hideHuds()
        if (b.infoPanel.visibility == View.VISIBLE) {
            hidePanel()
        } else {
            showPanel()
        }
    }

    private fun showPanel() {
        b.playerTopBar.animate().cancel()
        b.playerTopBar.alpha = 1f
        b.playerTopBar.visibility = View.VISIBLE
        b.infoPanel.animate().cancel()
        b.infoPanel.alpha = 0f
        b.infoPanel.visibility = View.VISIBLE
        b.liveBadge.visibility = View.VISIBLE
        b.infoPanel.animate().alpha(1f).setDuration(220).start()
        refreshStreamInfo()

        panelHideRunnable?.let { b.root.removeCallbacks(it) }
        val r = Runnable { hidePanel() }
        panelHideRunnable = r
        b.root.postDelayed(r, 3500L)
    }

    private fun hidePanel() {
        if (b.infoPanel.visibility != View.VISIBLE || waitingForPlayback || b.btnReconnect.visibility == View.VISIBLE) return
        b.playerTopBar.animate().alpha(0f).setDuration(280).withEndAction {
            b.playerTopBar.visibility = View.GONE
            b.playerTopBar.alpha = 1f
        }.start()
        b.infoPanel.animate().alpha(0f).setDuration(280).withEndAction {
            b.infoPanel.visibility = View.GONE
            b.liveBadge.visibility = View.GONE
            b.infoPanel.alpha = 1f
        }.start()
    }

    private fun showStatus(text: String, colorRes: Int) {
        b.tvStatus.visibility = View.VISIBLE
        b.tvStatus.text = text
        b.tvStatus.setTextColor(getColor(colorRes))
    }

    /**
     * 显示错误。
     *
     * retryable=false 时不显示「重连」：重试不可能改变结果时留着按钮，
     * 只会让用户反复点一个必然失败的动作。
     */
    private fun showError(text: String, retryable: Boolean = true) {
        showStatus(text, com.company.udpxytv.R.color.player_error)
        b.btnReconnect.visibility = if (retryable) View.VISIBLE else View.GONE
        showPanel()
        panelHideRunnable?.let { b.root.removeCallbacks(it) }
        panelHideRunnable = null
    }

    /** 换源/重连时同步释放：用户还在播放页，短暂等待可接受 */
    private fun releasePlayer() {
        player?.let {
            it.stop()
            it.detachViews()
            it.release()
        }
        player = null
    }

    /**
     * 退出时异步释放。
     *
     * libvlc 停止播放要等解码线程和音频输出线程收尾，在主线程同步做会把生命周期
     * 事务按住约 3 秒——MIUI APP_SCOUT 实测到 EXECUTE_TRANSACTION 3010/3012ms
     * 的**固定**值（不是随机抖动）。
     * 改法：主线程只摘掉视频表面（画面立刻消失），stop/detachViews/release 丢到后台
     * 单线程串行执行。进程本来就要结束，尾部没跑完的那点释放没有实际代价。
     */
    private fun releasePlayerAsync() {
        val p = player ?: return
        player = null
        b.videoLayout.removeAllViews()
        releaseExec.execute {
            runCatching { p.stop() }
            runCatching { p.detachViews() }
            runCatching { p.release() }
        }
    }

    override fun onStop() {
        super.onStop()
        stopStatsSampling()
        if (isFinishing) {
            // 真正退出才释放，且不阻塞主线程
            abandonAudioFocus()
            releasePlayerAsync()
        } else {
            // 切后台：暂停并标记，返回时自动恢复。
            // 同时挡住已排队的重试，否则会在后台自己播起来。
            backgrounded = true
            resumeOnForeground = true
            player?.pause()
            abandonAudioFocus()
            updatePlayPauseButton()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        stopStatsSampling()
        // 挂起的 HUD 自动隐藏任务一并撤掉：Activity 已经销毁，
        // 让它们继续跑只会去碰 detached 的 View
        hideHuds()
        // 退出播放 = 恢复进入前的系统音量与亮度
        restoreSystemState()
        abandonAudioFocus()
        // onStop 已经异步放过 player；LibVLC 实例同样走后台，
        // 免得紧接着再卡一次
        releasePlayerAsync()
        val lc = libVlc
        if (lc != null) {
            libVlc = null
            releaseExec.execute { runCatching { lc.release() } }
        }
        // 单线程池用完即关。newSingleThreadExecutor 建的是**非 daemon** 线程、
        // 核心线程不超时；不 shutdown 的话每次进出播放器都会漏一个线程。
        // 放在最后：上面两个任务已提交，shutdown 会等它们跑完再收线。
        releaseExec.shutdown()
    }

    @Deprecated("Deprecated in Java")
    @Suppress("DEPRECATION")
    override fun onBackPressed() {
        // 连接中或出错时，面板本来就是状态提示而不是用户点出来的遮罩，
        // 这时按返回的意图明确是"放弃播放"，不该只把面板关掉要按第二次
        val aborting = waitingForPlayback || b.btnReconnect.visibility == View.VISIBLE
        if (b.infoPanel.visibility == View.VISIBLE && !aborting) {
            hidePanel()
        } else {
            super.onBackPressed()
        }
    }

    companion object {
        const val EXTRA_NAME = "extra_name"
        const val EXTRA_URL = "extra_url"
        private const val MAX_RETRY = 5
        private const val START_TIMEOUT_MS = 15000L
        private const val STATS_SAMPLE_MS = 1000L
        private const val STATS_WINDOW_MS = 3000L
        private const val SLIDE_THRESHOLD = 40f
        private const val TAP_THRESHOLD = 24f
    }
}
