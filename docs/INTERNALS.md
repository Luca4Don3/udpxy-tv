# 实现内幕

README 讲怎么用,这里讲为什么这么做 —— 绝大部分是踩坑换来的结论。

## 构建

### 为什么每次清理 app/build

`packageDebug` 阶段出现过 **3 次** `OutOfMemoryError`。已确认不是内存总量不足,
而是**增量打包的偶发内存峰值**(连续两次增量构建可成功,非必然触发)。
每次清 `app/build` 兜底,代价约多 20 秒。

### 为什么有预检

源码写入曾因引号转义**静默失败**(写不进去但不报错),构建出来的东西和预期不符。
预检会 grep 关键符号,写入不成功立即中止。

### strip 门禁

`lib/arm64-v8a/libc++_shared.so` 在 AAR 里带着完整调试符号,**9.2MB**;strip 后 **1.37MB**。

AGP 找不到 NDK 的 strip 工具时会打印 `Unable to strip the following libraries, packaging
them as they are`,然后**静默**原样打包。功能不受影响,只是包白白大 8MB,所以一直
没人注意 —— 历史构建日志里**每个版本**都有这条,直到 2.12.0 才第一次真正 strip 成功。

`build.sh` 第 6 步现在直接量产物里这个文件的大小,超过 2MB 判失败并提示去查 NDK。

安全性已验证:strip 前后 **BuildID 完全相同**、动态符号都是 **2335 个**(一个没少),
`.dynsym` / `.dynstr` / `.text` / `.rodata` 全在,只删了 `.debug_*`。
崩溃栈里 `Java_org_videolan_libvlc_Media_nativeGetStats+52` 这类函数名来自 `.dynsym`,strip 后照常可见。

## 版本策略

按 Linux 内核版本规范:**偶数次=稳定,奇数次=开发,修订号流水递增**。

| 变更 | 版本动作 | 例 |
|---|---|---|
| bug 修复 | 升修订号 | 2.1.1 → 2.1.2 |
| 新增功能 | 升次版本 | 2.1.7 → 2.2.0 |
| 破坏性变更 | 升主版本 | 2.x → 3.0.0 |

`versionCode` 必须单调递增,取 `主×100 + 次×10 + 修订`(如 2.3.5 → 235)。

## 播放内核

### 为什么用 LibVLC 而不是 ExoPlayer

最初用 Media3 ExoPlayer,画面正常但**完全静音**。ffprobe 解析原因:

```
id=0x121  video  h264
id=0x122  audio  mp2   ← MPEG-1 Audio Layer II
```

该流音频是 **MP2**,而 Android 原生解码器只有 `OMX.google.mp3.decoder`,没有 MP2。
ExoPlayer 把 `audio/mpeg` 一律按 MP3 解析,遇 MP2 帧头校验失败后**静默丢弃音频、不报错**。
VLC 用 FFmpeg,自带 MP2 解码器。

### 硬解

```kotlin
media.setHWDecoderEnabled(true, true)   // 视频+音频
media.addOption(":codec=...")           // 3.7.6 才有该变量,3.6.0 上是 no-op
```

确认硬解是否生效(界面不显示,需查日志):

```bash
adb logcat | grep -E "CreateByComponentName|video-debug-dec.*Stats"
# 出现 c2.mtk.avc.decoder 且 Drop:0 即正常
```

**界面刻意不显示硬解/软解标签** —— libvlc 的 `TrackDescription.name` 是流编码名而非解码器
组件名,据此判断必然失真,且 libvlc 不暴露实际生效的解码组件。

### 缓存参数

三层约束,缺一不可:

1. **全局选项必须带 `--` 前缀**,否则裸字符串被 `libvlc_new()` 当成要播放的 MRL
2. **全局只能放 libvlc 核心项**。VLC 3.7.x 对未注册的全局选项是**致命错误**:
   `libvlc_new()` 返回 NULL → can't create LibVLC instance → 应用起不来
3. **模块级选项(vout/avcodec)必须放 media 级**,用单冒号,未知项静默忽略

```kotlin
// 全局:仅 3 项核心项
"--network-caching=1000"  "--live-caching=1000"  "--file-caching=1500"

// media 级:其余全部
media.addOption(":drop-late-frames=20")  // vout 模块,全局传会炸
```

**顺序要求:先设缓存,再开硬解。** `Media.addOption()` 会按前缀置
`mNetworkCachingSet`/`mFileCachingSet`,而 `setHWDecoderEnabled()` 内部检查这些标志
决定是否注入 1500。顺序反了会被它覆盖。

**1000ms 的依据**:800ms 在设备空闲时起播更快,但**观测到 1 次视频 ES 探测失败**;
1500ms 两种场景都能起播但更慢。这个失败伴随刚安装后的 ProfileInstaller 后台 ART 编译,
**无法断言是缓存时长单独导致的** —— 机制上说得通,但缺少对照实验,不能写成定论。
1000 是折中,不是经过证明的最优值。

### 退出释放走后台

libvlc 停止播放要等解码线程和音频输出线程收尾,在主线程同步做会把生命周期事务按住约 3 秒
—— MIUI `APP_SCOUT` 实测到 `EXECUTE_TRANSACTION`(w=159) 3010/3012ms 的**固定**值。

所以退出路径改成:主线程只摘掉视频表面(画面立刻消失),`stop/detachViews/release` 丢到
后台单线程串行执行,`LibVLC` 实例紧随其后。换源/重连仍在主线程同步释放 —— 那时用户还在
播放页,等待可接受。

## 边界行为

### 退后台后不会自己播起来

`isFinishing` **只在 `finish()` 时为 true**。普通退后台(HOME / 切任务)时它仍是 false,
所以只靠它把关的话,已排队的重试照样会触发 `prepareAgain()` → `play(media)`,
表现为「退到后台后自己又播起来,还出声」。现在有独立的 `backgrounded` 标志
(`onStart` 清、`onStop` 置),四个检查点都同时要求 `!isFinishing && !backgrounded`。

### 播放成功后重试预算清零

`retryCount` 以前只在 `startPlayback()` 里清零。一次真的 `Playing` 说明本轮流通了,
不重置的话,多次**彼此独立**的短暂断流会一路累计到 5 次,最后误报「流持续中断」并停止自动重试。

### 地址校验

- 组播地址缺端口时补默认端口 `5140`。udpxy 的 `/udp/<ip>:<port>` 必须带端口
- 端口必须在 `1..65535`。这里必须**三态**区分:没写端口用默认值、写了且合法用规范值、
  写了但越界/非数字判整个地址无效。写成 `normalizePort(...) ?: DEFAULT_PORT` 会把
  `192.168.1.1:99999` 悄悄改成 `:80` —— 用户填的是另一个端口,却被指向了默认端口的服务

  这条是**单元测试第一次运行时当场抓出来的**:5 个用例红,全是同一个 `?: DEFAULT_PORT` 吞掉 null。

### 手动添加也按归一化地址去重

`hasChannelWith()` 以前直接比原始字符串,于是 `233.0.0.1:5140` 和 `udp://@233.0.0.1:5140`
能被当成两个不同频道。批量导入走的是归一化口径,手动添加没走,现在两边统一。

### 音频焦点

`startPlayback` 以前丢弃 `requestAudioFocus()` 的返回值,焦点被拒也照播,会和占用方同时发声。
现在拿不到焦点就不播并给出提示。

### 返回键

连接中或出错时,面板是状态提示而不是用户点出来的遮罩,这时按返回的意图明确是「放弃播放」。
现在这类状态下按一次直接退出,不必按两次。

## 流信息显示

### 编码名拿不到,原因查实了

早先信息层显示成 `Disable + Disable`,是因为这批组播源的 TS 里**视频和音频各被声明了 2 条 ES**:

```
tracks v=2 [Disable, Track 1]   a=2 [Disable, Track 1]
```

第一条是 VLC 给**未选中轨**用的占位名 `Disable`,而 `detectDecoder()` 取的是 `firstOrNull()`,
正好取到占位轨。ffprobe 侧证实源里其实只有 1 条视频流 + 1 条音频流。

想显示真正的编码名,结果查下来 **libvlc-android 3.7.6 的 Java 层没有 codec 查询接口**:

| 想用的 | 实际情况 |
|---|---|
| `MediaPlayer.TrackDescription` | 只有 `id: Int` 和 `name: String` |
| `IMedia.Track` | 有 `codec`/`description`/`fourcc`,但没有对外入口 |
| `Media.getTracks()` / `MediaPlayer.videoCodec` | 属性不存在,编译不过 |

所以规则收敛成:**只显示有意义的轨道名,`Disable` 和 `Track N` 都跳过**,不拿占位名充数。

### 运行时码率

读 **`TrafficStats.getUidRxBytes(Process.myUid())`** —— 整个进程的累计接收字节数,
每 1 秒采一次做差,再取 3 点滑动平均压抖动。

**为什么不用 `Media.getStats()`**:libvlc-android 3.7.6 的 `Media.nativeGetStats` 在主线程调用
**必 SIGSEGV**(2.11.0 真机复现,fault addr 0x8,调用栈
`Java_org_videolan_libvlc_Media_nativeGetStats+52`)。换掉之后崩溃消失。
**帧率也一并去掉了** —— 它依赖同一批 `displayedPictures` 字段,同属坏 API。

**为什么不用 `demuxBitrate`**:那是从 demux 开始到现在的全程平均,起播那一两秒样本不足,
会把起播期的低码率一直摊在均值里,要等很久才收敛。

**不额外建立 udpxy 连接** —— 读的是已经存在的那一路流的计数。口径是整个进程的接收量;
App 同一时刻只播一路流,所以它等于该路的实际速率。

### 交叉验证的基准

**这是一条 4M 的 VBR 流**,码率完全跟着画面走 —— 亮场景约 3.2 Mbps,暗场景可低到 **1.7 Mbps**。
**别拿单个数字当基准**:同一路流在不同时刻能差一倍。

| 测法 | 结果 |
|---|---|
| App 信息层(用户实读) | **3.21 Mbps**,暗场景低至 1.7 |
| 构建机上 curl 直连 udpxy 拉 60 秒 | 428,005 B/s = **3.42 Mbps** |
| 手机 UID 级(dumpsys netstats 的 rb 增量) | 3.45 / 3.65 Mbps |

三处互相吻合,且后两条与 App 内部用的是**同一个数据源**(`TrafficStats`)。

**`ffprobe` 不适合当基准**:它读这条流时 `format.bit_rate` 拿不到,`stream.bit_rate` 对视频是
`N/A`(只有音频 192 kbps)。早先文档里那个「总码率 1.889 Mbps」既不是网络速率、也不可复现。

## 播放器交互

### 音量和亮度都只有一条通道

两者改的都是**系统设置本身**:

| 目标 | 调用的系统接口 | 和系统 UI 的关系 |
|---|---|---|
| 音量 | `AudioManager.setStreamVolume(STREAM_MUSIC)` | 音量键、下拉栏、状态栏指示器都是这一条 |
| 亮度 | `Settings.System.SCREEN_BRIGHTNESS` | 系统设置里的亮度条是同一个值 |

**原先两处都是错的:**

- 滑动音量只改 `player.volume`(libvlc 自己的刻度),而音量键改系统流 `STREAM_MUSIC`。
  两条通道互不干涉,滑到中间任何位置状态栏都纹丝不动
- 滑动亮度只改 `window.attributes.screenBrightness`,那是**窗口级覆盖**:系统亮度条不会被
  它影响,且最终亮度 = 系统亮度 × 窗口系数,永远对不上

代价是两者都落在系统整数级(音量与亮度各有自己的档位),换来的正是两边永远一致。
手势内部用浮点意图保留细碎位移,落回整数级时才取整;每次手势都从系统当前值重新起算,
中途按过音量键也不会失步。

亮度下限留 1 级而不是 0:全黑之后看不见 HUD,也就没法再滑回来。

### libvlc 侧的基线是 256

`MediaPlayer.setVolume(int)` 的 javadoc 没写范围,底层 `libvlc_audio_set_volume()` 按
VLC 3.x 的 `include/vlc_aout.h` 换算:

```c
#define AOUT_VOLUME_DEFAULT  256   /* 0dB 在这里 */
#define AOUT_VOLUME_MAX      512   /* +6dB */
```

而 `aout_VolumeSet()` 收 float,注释写明 `1. = nominal` 且不 clamp。
所以真实增益 = int/256:`100` 是 -8.2dB、`200` 是 -2.1dB、`256` 才是 0dB。
取 256 才真正做到「libvlc 不额外削减」。(duck 静音用 `VLC_VOL_BASELINE / 4` = 64,约 -12dB。)

**仅横屏启用** —— `PlayerActivity` 声明 `sensorLandscape`,并有 `isLandscape()` 运行时守卫。

### 亮度需要一次授权

写 `Settings.System` 属特殊权限 `WRITE_SETTINGS`,得用户在系统里点头。
入口在**设置 → 显示 → 修改系统设置**。未授权时亮度降级为窗口级(此时与系统亮度条不可能一致)
并提示一次。滑动亮度会把亮度模式切成手动,否则自动亮度会按传感器把手写进去的值覆盖回去。

### 退出播放会恢复原状

进入播放时快照系统音量、系统亮度、亮度模式,`onDestroy` 时原样写回,并清掉窗口亮度覆盖。
所以播放期间调过的音量和亮度不会留在手机上 —— **包括用音量键调大的音量**。这是有意为之。

## 兼容性

### 16KB 页

Android 15+ 支持 16KB 页,4KB 对齐的 so 在其上 `dlopen` 失败。

```
libvlc 3.6.0  p_align = 0x1000  ✗
libvlc 3.7.6  p_align = 0x4000  ✓
```

libvlc 3.7.6 四架构均已 16KB 对齐,AGP 默认按 16KB 打包(无需 `zipalign -P`,仅校验)。
`build.sh` 会自动校验这两项。

## 调试

### VLC 的错误不进常规 logcat

VLC 走 **native stderr**(tag `VLC-std`),必须开启重定向才能看到:

```bash
# log. 命名空间的属性,普通 shell 无权设置,需要 root 设备或 adb root(重启失效)。
# 无 root 的机器上看不到 VLC-std 输出(本项目的实测环境就是如此,见 AGENTS.md)。
adb shell setprop log.redirect-stdio true
adb logcat -b all -c
adb logcat -d -b all | grep -E 'VLC-std|unknown option|FATAL|can.t create LibVLC'
```

### 验收

单次测量会骗人,**必须 ≥5 次且包含「刚安装后首次启动」** —— ProfileInstaller 的编译期
是最恶劣场景,首启数值必然比稳定态差。

**`adb` 注入的触摸可能被系统拦在 RecyclerView 区域**(列表既不响应点击也不滚动),
此时涉及频道列表的验证**必须手动做**,依赖 `input tap` 的自动化结论是假失败。
具体的判据阈值与本机的实测数据见 `AGENTS.md`。

### 已知噪声(不是缺陷)

- 起播时约 20 条 `get_buffer() failed` / `no frame!`,集中在 1 秒内之后自愈
  —— 组播从 GOP 中间接入的固有成本
- `CCodecBuffers: Client returned a buffer it does not own`(数千条)
  —— libvlc 与 MTK 硬解 `c2.mtk.avc.decoder` 的记账口径不一致,无用户可见影响

