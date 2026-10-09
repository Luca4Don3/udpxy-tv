# 局域网直播 App（udpxy）

通过 iKuai 的 udpxy 在手机上看局域网组播直播。原生 arm64，LibVLC 播放内核。

> 源码与构建均在 <build_host> 服务器 `<project_root>`，本机只存成品 APK。

## 产物

| 架构 | 文件 | 体积 | sha256 |
|---|---|---|---|
| arm64-v8a | `lan-tv-2.12.1-arm64.apk` | 54 MB | `97a18db4…104d` |
| armeabi-v7a | `lan-tv-2.12.1-v7a.apk` | 47 MB | `03b3b2a4…a254` |

- 包名 `com.company.udpxytv` / versionCode 245 / versionName 2.12.1
- minSdk 24（Android 7.0）/ targetSdk 34 / compileSdk 36
- 双架构：arm64-v8a + armeabi-v7a（另有 universal 包，体积较大未随仓库分发）
- 播放内核 **LibVLC 3.7.6**

## 环境

| 项 | 值 |
|---|---|
| 构建机 | <build_host> (`<internal_ip>`) |
| 工程路径 | `<project_root>` |
| JDK | `<jdk_home>`（Temurin 17.0.20.1） |
| Android SDK | `<android_sdk>` |
| Gradle | 9.7.1（系统自带 `/usr/local/bin/gradle`，无 wrapper） |
| AGP | 9.0.0（内置 Kotlin 支持，**不要**再声明 kotlin 插件） |
| build-tools | 36.0.0 |

## 构建

```bash
ssh <build_host>
cd <project_root>
./build.sh
```

脚本做六件事：预检构建配置 → 预检源码符号 → **清理 app/build** → 单元测试 → 构建 → 校验产物（含符号与 strip 两道门禁）。

### 为什么必须清理

`packageDebug` 阶段出现过 **3 次** `OutOfMemoryError`。已确认 31G 物理内存 / 8G 堆并不不足，是**增量打包的偶发内存峰值**（连续两次增量构建可成功，非必然触发）。每次清 `app/build` 兜底，代价约多 20 秒。

### 为什么有预检

源码写入曾因引号转义**静默失败**（写不进去但不报错），导致构建出来的东西和预期不符。预检会 grep 关键符号，写入不成功立即中止。

### strip 门禁

`lib/arm64-v8a/libc++_shared.so` 在 AAR 里带着完整调试符号，**9.2MB**；strip 之后是 **1.37MB**。

AGP 找不到 NDK 的 strip 工具时会打印 `Unable to strip the following libraries, packaging them as they are`，然后**静默**原样打包。功能不受影响，只是包白白大 8MB，所以一直没人注意——历史构建日志里**每个版本**都有这条，直到 2.12.0 才第一次真正 strip 成功。

`build.sh` 第 6 步现在直接量产物里这个文件的大小，超过 2MB 判失败并提示去查 NDK（期望 `<android_sdk>/ndk`），不让它再静默降级。

strip 只删 `.debug_*` 段。实测 strip 前后 **BuildID 完全相同**、动态符号都是 **2335 个**（一个没少），`.dynsym` / `.dynstr` / `.text` / `.rodata` 全在。崩溃栈里 `Java_org_videolan_libvlc_Media_nativeGetStats+52` 这类函数名来自 `.dynsym`，strip 后照常可见。

## 版本策略

按 Linux 内核版本规范：**偶数次=稳定，奇数次=开发，修订号流水递增**。

| 变更 | 版本动作 | 例 |
|---|---|---|
| bug 修复 | 升修订号 | 2.1.1 → 2.1.2 |
| 新增功能 | 升次版本 | 2.1.7 → 2.2.0 |
| 破坏性变更 | 升主版本 | 2.x → 3.0.0 |

versionCode 必须单调递增，取 `主×100 + 次×10 + 修订`（如 2.3.5 → 235）。

## 使用

1. 打开 App，**主界面顶部**填网关地址（本局域网 iKuai 的 IP 和 udpxy 端口），点 ✓ 或失焦即存
2. 点右下角 **添加频道**，填名称 + 组播地址
3. 点频道播放，自动横屏

### 网关格式不限

```
<internal_ip>:9999        http://<internal_ip>:9999      <internal_ip>（端口按 80）
ikuai.local:9999       ikuai.local                 http://ikuai.local/udp/
```

### 频道地址支持四种写法

```
#EXTINF:-1,CCTV1 综合          ← m3u 清单
udp://@233.1.1.1:5140

CCTV6 电影,233.1.1.1:5141      ← 逗号分隔

体育频道  239.1.1.1:5000       ← 空格/制表符

233.1.1.1:6000                  ← 纯地址

CCTV1,http://192.168.1.1:9999/udp/233.1.1.1:5140   ← 完整 URL，自动提取
```

**预置频道是示例**：仓库里的 `PresetChannels.kt` 只放了 5 个占位地址
（`239.0.0.x:5140`）。各地 IPTV 的组播地址由运营商决定，人人不同，所以没有写死。
把手上的清单导进去即可：设置 → 批量导入频道。预置只在**首次启动且频道为空**时写入一次，
之后清空了也不会再灌回来。

**批量导入**：设置 → 批量导入频道，实时预览识别结果，自动跳过重复地址。

**频道导出**：设置 → 导出频道，存成 m3u（建议文件名形如 `channels-20261008.m3u`）。导出格式与导入完全一致，备份文件可以直接再导回来。走系统文件选择器保存，**不需要任何存储权限**；文件名里混进的换行会被折成空格，免得把清单的行结构弄坏。

## 播放器

### 为什么用 LibVLC 而不是 ExoPlayer

最初用 Media3 ExoPlayer，画面正常但**完全静音**。ffprobe 解析原因：

```
id=0x121  video  h264
id=0x122  audio  mp2   ← MPEG-1 Audio Layer II
```

该流音频是 **MP2**，而 Android 原生解码器只有 `OMX.google.mp3.decoder`，没有 MP2。ExoPlayer 把 `audio/mpeg` 一律按 MP3 解析，遇 MP2 帧头校验失败后**静默丢弃音频、不报错**。VLC 用 FFmpeg，自带 MP2 解码器。

### 硬解

```kotlin
media.setHWDecoderEnabled(true, true)   // 视频+音频
media.addOption(":codec=...")           // 3.7.6 才有该变量，3.6.0 上是 no-op
```

确认硬解是否生效（界面不显示，需查日志）：

```bash
adb logcat | grep -E "CreateByComponentName|video-debug-dec.*Stats"
# 出现 c2.mtk.avc.decoder 且 Drop:0 即正常
```

**界面刻意不显示硬解/软解标签** —— libvlc 的 `TrackDescription.name` 是流编码名而非解码器组件名，据此判断必然失真，且 libvlc 不暴露实际生效的解码组件（javap 确认无此 API）。

## 缓存参数（血泪教训）

### 三层约束

1. **全局选项必须带 `--` 前缀**，否则裸字符串被 `libvlc_new()` 当成要播放的 MRL
2. **全局只能放 libvlc 核心项**（`src/libvlc-module.c`）。VLC 3.7.x 对未注册的全局选项是**致命错误**：`libvlc_new()` 返回 NULL → `can't create LibVLC instance` → 应用起不来
3. **模块级选项（vout/avcodec）必须放 media 级**，用单冒号，未知项静默忽略

```kotlin
// 全局：仅 3 项核心项
"--network-caching=1000"  "--live-caching=1000"  "--file-caching=1500"

// media 级：其余全部
media.addOption(":drop-late-frames=20")  // vout 模块，全局传会炸
```

### 顺序要求：先设缓存，再开硬解

`Media.addOption()` 会按前缀置 `mNetworkCachingSet`/`mFileCachingSet`/`mCodecOptionSet`，而 `setHWDecoderEnabled()` 内部会检查这些标志决定是否注入 1500：

```
58: getfield  mFileCachingSet
62: ifne      72        ← 已设过 → 跳过 :file-caching=1500
72: getfield  mNetworkCachingSet
76: ifne      86        ← 已设过 → 跳过 :network-caching=1500
```

**`applyMediaOptions(media)` 必须在 `setHWDecoderEnabled()` 之前调用。**

### 1000ms 的实测依据

| 场景 | 结果 |
|---|---|
| 800ms 设备空闲 | 起播 1.3s，首条 Stats `Qin 109-117 / Render 83-92`（更快） |
| 800ms 设备有负载 | **观测到 1 次**视频 ES 探测失败，解码器不创建 → AudioFlinger underrun |
| 1500ms 两种场景 | 都能起播，但前 5 秒 `Qin 100 / Render 73`（更慢） |

**别把那次失败当成"800ms 在负载下必挂"**：随后在同一台设备做了 8 次独立重复，
空闲状态下 800ms 每次都正常起播。那次失败同时伴随刚安装后的 ProfileInstaller
后台 ART 编译，**无法断言是缓存时长单独导致的**——机制上"视频缺 SPS/PPS/CSD
打不开解码器"说得通，但缺少对照实验，不能写成定论。

另外帧率爬升也不是输出缓冲造成的：同样这 8 次里 `Qin` 只有 20fps，
说明瓶颈在解复用/ES 探测侧，不在渲染缓冲。

**1000 是折中（800 收益不稳定、1500 起播延迟明显），不是经过证明的最优值。**

### 退出释放走后台

libvlc 停止播放要等解码线程和音频输出线程收尾，在主线程同步做会把生命周期
事务按住约 3 秒——MIUI `APP_SCOUT` 实测到 `EXECUTE_TRANSACTION`(w=159)
3010/3012ms 的**固定**值，不是随机抖动。

所以退出路径改成：主线程只摘掉视频表面（画面立刻消失），
`stop/detachViews/release` 丢到后台单线程串行执行，`LibVLC` 实例紧随其后。
换源/重连仍在主线程同步释放——那时用户还在播放页，等待可接受。

判据：连测 3 次退出，`APP_SCOUT_WARNING` 归零。

## 边界行为（都是复核出来的，不是设想的）

### 退后台后不会自己播起来

`isFinishing` **只在 `finish()` 时为 true**。普通退后台（HOME / 切任务）时它仍是
false，所以只靠 `isFinishing` 把关的话，已经排队的重试照样会触发
`prepareAgain()` → `play(media)`，表现为"退到后台后自己又播起来，还出声"。

现在有独立的 `backgrounded` 标志（`onStart` 清、`onStop` 置），四个
检查点——起播超时、面板自动隐藏、两处自动重试——都同时要求
`!isFinishing && !backgrounded`。

### 播放成功后重试预算清零

`retryCount` 以前只在 `startPlayback()` 里清零。一次真的 `Playing` 说明本轮流通了，
不重置的话，多次**彼此独立**的短暂断流会一路累计到 5 次，最后误报
"流持续中断"并停止自动重试。现在 `Playing` 事件里一并清零。

### 地址校验

- 组播地址缺端口时补默认端口 `5140`。以前直接返回不带端口的 IP，
  而 udpxy 的 `/udp/<ip>:<port>` 必须带端口，拼出来的 URL 必然连不上。
- 完整 URL 分支以前**不校验 IPv4**（非 URL 分支校验了），两边口径不一致。
- 端口必须在 `1..65535`。正则的 `\d{1,5}` 能匹配到 `99999`/`00000` 这类越界值。
  这里必须**三态**区分：没写端口用默认值、写了且合法用规范值、写了但越界/非数字
  判整个地址无效。写成 `normalizePort(...) ?: DEFAULT_PORT` 会把
  `<internal_ip>:99999` 悄悄改成 `:80`——用户填的是另一个端口，却被指向了
  默认端口的服务，而且报错完全看不出是自己写错了。

  **这条是单元测试第一次运行时当场抓出来的**，不是想出来的：5 个用例红，
  其中 3 个网关、2 个组播，全是同一个 `?: DEFAULT_PORT` 吞掉 null。

### 手动添加也按归一化地址去重

`hasChannelWith()` 以前直接比原始字符串，于是 `233.0.0.1:5140` 和
`udp://@233.0.0.1:5140` 能被当成两个不同频道重复保存——批量导入走的是
归一化口径，手动添加没走。现在两边统一。

### 音频焦点

首次起播（`startPlayback`）以前丢弃 `requestAudioFocus()` 的返回值，
焦点被拒也照播，会和占用方同时发声。现在拿不到焦点就不播并给出提示；
恢复播放那条路径本来就检查了。

### 返回键

连接中或出错时，面板是状态提示而不是用户点出来的遮罩，这时按返回的意图
明确是"放弃播放"。现在这类状态下按一次直接退出，不必按两次。

## 单元测试

项目原先没有任何测试，地址解析这种纯函数只能靠真机验证，问题往往一路带到
播放失败才暴露。现在有一组 JVM 单测（不需要设备、不需要 Robolectric）：

```
app/src/test/java/com/company/udpxytv/data/StreamUrlBuilderTest.kt
```

现在两个测试类共 **39 个用例**：

| 测试类 | 用例 | 覆盖 |
|---|---|---|
| `StreamUrlBuilderTest` | 26 | 三种地址写法、缺端口补默认、IPv4 校验、端口越界、空串、最终拼接 |
| `M3uImporterTest` | 13 | m3u/逗号/空格/裸地址四种写法、EXTINF 内含逗号、注释忽略、**skipped 不再静默丢弃**、重复判定按归一化地址 |

跑法：

```bash
gradle :app:testDebugUnitTest
```

`build.sh` 已把它设成**出包门禁**——测试不过就不构建（第 4/6 步），
所以这类回归不会再流到手机上。

### 这批修的其余几项

| 问题 | 修法 |
|---|---|
| HUD 自动隐藏任务冲突 | 以前是单个 `hudHideRunnable`：1.2 秒内先滑音量再滑亮度，第二次会 `removeCallbacks` 掉第一次的任务，先显示的 HUD 就永远不会自动隐藏。现在按 View 分别持有 |
| 编辑频道路径不判重 | `updateChannel()` 返回 false 表示"新地址与其它频道重复"，不写入。把 A 改成 B 的地址以前完全不拦 |
| `abandonAudioFocus(null)` | API 24/25 的 legacy 接口靠 listener 实例识别持有者，传 null 等于什么都没放弃。现在申请时把 listener 存起来，放弃时用它 |
| `isLandscape()` 把 UNDEFINED 当横屏 | 只认明确的 LANDSCAPE。Activity 已声明 `sensorLandscape`，正常不会走到 UNDEFINED，把"未知"当横屏会在竖屏误开滑动手势 |
| `Media` 从不 release | `p.play(media)` 之后本地这份就放手（play 内部已持引用），否则每次换源/重试泄一个 native Media |
| `M3uImporter.parseWithReport` 死代码 | 真正接上了。`skipped` 以前硬编码成 `emptyList()`，配上 `parse()` 里的 `continue`，等于无效行静默丢弃；现在导入结果会同时报出导入数、重复数、认不出的行数，预览里也显示 |
| 残留文件 | `app/src/main/res/AndroidManifest.xml`（9/29 的旧 manifest，缺 WRITE_SETTINGS）和 `app/build.gradle.kts.bak`（还带着会让 AGP 9 冲突的 kotlin 插件）已归档到 `.bak/`，不是删除 |

## 流信息显示

### 编码名拿不到，原因查实了

早先信息层显示成 `Disable + Disable`，是因为这批组播源的 TS 里
**视频和音频各被声明了 2 条 ES**：

```
tracks v=2 [Disable, Track 1]   a=2 [Disable, Track 1]
```

第一条是 VLC 给**未选中轨**用的占位名 `Disable`，而 `detectDecoder()` 取的是
`firstOrNull()`，正好取到占位轨。ffprobe 侧证实源里其实只有 1 条视频流 +
1 条音频流，第二条是 VLC 自己造的。

想显示真正的编码名，结果查下来 **libvlc-android 3.7.6 的 Java 层没有 codec
查询接口**：

| 想用的 | 实际情况 |
|---|---|
| `MediaPlayer.TrackDescription` | 只有 `id: Int` 和 `name: String` 两个字段 |
| `IMedia.Track` | 有 `codec`/`description`/`fourcc`，但没有对外入口 |
| `Media.getTracks()` / `MediaPlayer.videoCodec` | 属性不存在，编译不过 |

所以规则收敛成：**只显示有意义的轨道名，`Disable` 和 `Track N` 都跳过，
一个都没有就不显示**。不拿占位名充数，也不编。

### 运行时码率：读进程级网卡计数，零额外连接

信息层底部一行显示实时码率（如 `3.42 Mbps`）。

读的是 **`TrafficStats.getUidRxBytes(Process.myUid())`** —— 整个进程的累计接收字节数，
每 1 秒采一次做差，再取 3 点滑动平均压抖动。

**为什么不用 `Media.getStats()`**：libvlc-android 3.7.6 的 `Media.nativeGetStats`
在主线程调用**必 SIGSEGV**（2.11.0 真机复现，fault addr 0x8，调用栈
`Java_org_videolan_libvlc_Media_nativeGetStats+52` ← `statsTicker` 的 run）。
换掉之后崩溃消失。**帧率也一并去掉了** —— 它依赖同一批 `displayedPictures` 字段，同属坏 API。

**为什么不用 libvlc 自带的 `demuxBitrate`** —— 那是从 demux 开始到现在的全程平均，
起播那一两秒样本不足，会把起播期的低码率一直摊在均值里，要等很久才收敛。

**不额外建立 udpxy 连接** —— 读的是已经存在的那一路流的计数。

口径是**整个进程的接收量**。App 同一时刻只播一路流，所以它等于该路的实际速率；
代价是将来若加了别的网络功能，这个数就不再是纯视频码率。

采样在 `Playing` 事件后才启动（之前采到的都是起播期噪声），
`onStop` / `onDestroy` 关闭，`onStop` 的检查还带 `!backgrounded`。

### 交叉验证的基准

**这是一条 4M 的 VBR 流**，码率完全跟着画面走 —— 亮场景约 3.2 Mbps，
暗场景可低到 **1.7 Mbps**。**别拿单个数字当基准**：同一路流在不同时刻能差一倍。

判断「码率显示准不准」要看它**是否随画面波动、量级对不对**，而不是盯某个定值。

2026-10-08 实测（CCTV-1，`239.0.0.99:5140`）：

| 测法 | 结果 |
|---|---|
| App 信息层（用户实读） | **3.21 Mbps**，暗场景低至 1.7 |
| <build_host> 上 `curl` 直连 udpxy 拉 60 秒 | 428,005 B/s = **3.42 Mbps** |
| 手机 UID 级（`dumpsys netstats` 里 uid=10274 的 rb 增量） | 3.45 / 3.65 Mbps |

三处互相吻合，且后两条与 App 内部用的是**同一个数据源**（`TrafficStats`）。

**`ffprobe` 不适合当基准**：它读这条流时 `format.bit_rate` 拿不到，`stream.bit_rate`
对视频是 `N/A`（只有音频 192 kbps）。早先文档里那个「总码率 1.889 Mbps」
既不是网络速率、也不可复现，已作废。

## 已知限制

### 起播超时兜底 15s

`waitingForPlayback` 一直为 true 时（源根本起不来，既不 `Playing` 也不报错）
会永远停在"正在连接…"。现在 `startPlayback` 里挂了 15 秒兜底检查，
超时按 `handleError` 走重试（最多 5 次）后给出明确错误。

### 起播固有延迟 0.5–1.5s

纯组播接入**没有 FCC（Fast Channel Change）**，每次起播都从 GOP 中间接入，等下一个 SPS/PPS + IDR：

```
[h264] non-existing PPS 0 referenced
[h264] no frame!
```

音频不需要 CSD 所以先响，画面稍后。这是**组播接入的固有成本，非缺陷**。

2026-10-08 实测时还看到 20 条 `get_buffer() failed` / `thread_get_buffer() failed`，
全部集中在起播后 1 秒内，之后自愈，用户无感知 —— 同属这个接入过程
（解码器在等 IDR 时拿不到可输出的帧）。**不是缺陷，不需要处理。**

**根治方案：FCC** —— 服务端先用单播把"从最近 IDR 开始的数据"推给客户端让画面秒出，10s 后切回组播省带宽。当前未实现。

### 其他

- 只能在同一局域网使用，手机需与 iKuai 路由通
- 切换到蜂窝网络会提示"当前不在局域网"（udpxy 是局域网服务）
- MP2 音频为 FFmpeg 软解（该编码无硬件解码器）
- 未做 EPG 节目单、频道 logo、搜索排序
- 亮度手势需要「修改系统设置」授权（设置页 → 显示），未授权时只在应用内生效
- 退出播放会恢复进入前的音量与亮度，包括用音量键调大的音量

## 工程结构

```
<project_root>/
├── build.sh                          构建脚本
├── build.gradle.kts                  AGP 9.0.0
├── app/
│   ├── build.gradle.kts              依赖/ABI 拆分
│   └── src/main/
│       ├── AndroidManifest.xml
│       ├── java/com/company/udpxytv/
│       │   ├── data/
│       │   │   ├── Channel.kt              频道模型
│       │   │   ├── ChannelStore.kt         持久化 + 批量写入
│       │   │   ├── StreamUrlBuilder.kt     udpxy URL 拼接（格式不限定）
│       │   │   └── M3uImporter.kt          批量导入解析
│       │   └── ui/
│       │       ├── MainActivity.kt         频道网格 + 内联网关框
│       │       ├── ChannelAdapter.kt
│       │       ├── SettingsActivity.kt     网关配置 + 批量导入
│       │       └── PlayerActivity.kt       LibVLC 播放 + 手势
│       └── res/layout/                6 个布局
└── app/build/outputs/apk/debug/     产物
```

## 播放器交互

| 手势 | 行为 |
|---|---|
| 右半屏上下滑 | 系统媒体音量（与音量键同一条路径） |
| 左半屏上下滑 | 系统屏幕亮度（与系统设置是同一个值） |
| 单击 | 显隐播放信息层（3.5s 自动淡出） |

音量/亮度 HUD 为**贴边竖向进度条**（音量右、亮度左），不显示百分比。

### 音量和亮度都只有一条通道

两者改的都是**系统设置本身**，不是各调各的：

| 目标 | 调用的系统接口 | 和系统 UI 的关系 |
|---|---|---|
| 音量 | `AudioManager.setStreamVolume(STREAM_MUSIC)` | 音量键、下拉栏、状态栏指示器都是这一条 |
| 亮度 | `Settings.System.SCREEN_BRIGHTNESS` | 系统设置里的亮度条是同一个值 |

**原先两处都是错的：**

- 滑动音量只改 `player.volume`（libvlc 自己的刻度），而音量键改系统流
  `STREAM_MUSIC`（整数 0..15）。两条通道互不干涉，只在滑到边界才顺手回写一次——
  所以滑到中间任何位置状态栏都纹丝不动，按音量键画面里的音量条毫无反应。
- 滑动亮度只改 `window.attributes.screenBrightness`，那是**窗口级覆盖**：
  系统亮度条不会被它影响，且最终屏幕亮度 = 系统亮度 × 窗口系数，永远对不上。

代价是两者都落在系统整数级（该机音量 0..15 共 16 档，亮度 0..255），
换来的正是两边永远一致。手势内部用 `volumeIntent` / `brightnessIntent`
保留浮点意图、落回整数级时才取整，避免逐帧取整把细碎位移全部吃掉；
每次手势都从系统当前值重新起算，中途按过音量键也不会失步。

亮度下限留 1 级而不是 0：全黑之后看不见 HUD，也就没法再滑回来。

### libvlc 侧的基线是 256，不是 200 也不是 100

`MediaPlayer.setVolume(int)` 的 javadoc 没写范围，底层
`libvlc_audio_set_volume()` 按 VLC 3.x 的 `include/vlc_aout.h` 换算：

```c
#define AOUT_VOLUME_DEFAULT  256   /* 0dB 在这里 */
#define AOUT_VOLUME_MAX      512   /* +6dB */
```

而 `aout_VolumeSet()` 收 float，注释写明 `1. = nominal` 且不 clamp。
所以真实增益 = int/256：`100` 是 -8.2dB、`200` 是 -2.1dB、`256` 才是 0dB。
取 256 才真正做到"libvlc 不额外削减"。

（duck 静音用 `VLC_VOL_BASELINE / 4` = 64，约 -12dB。）

**仅横屏启用** —— `PlayerActivity` 声明 `sensorLandscape`，并有 `isLandscape()` 运行时守卫。

### 亮度需要一次授权

写 `Settings.System` 属特殊权限 `WRITE_SETTINGS`，得用户在系统里点头。
入口在**设置 → 显示 → 修改系统设置**。未授权时亮度降级为窗口级
（此时与系统亮度条不可能一致）并提示一次。滑动亮度会把亮度模式切成手动，
否则自动亮度会按传感器把手写进去的值覆盖回去。

### 退出播放会恢复原状

进入播放时快照系统音量、系统亮度、亮度模式，`onDestroy` 时原样写回，
并清掉窗口亮度覆盖（否则会污染之后的页面）。所以播放期间调过的音量和亮度
不会留在手机上——**包括用音量键调大的音量**。这是有意为之，不是副作用。

## 16KB 页兼容

Android 15+ 支持 16KB 页，4KB 对齐的 so 在其上 `dlopen` 失败。

```
libvlc 3.6.0  p_align = 0x1000  ✗
libvlc 3.7.6  p_align = 0x4000  ✓
```

libvlc 3.7.6 四架构均已 16KB 对齐，AGP 默认按 16KB 打包（无需 `zipalign -P`，仅校验）。`build.sh` 会自动校验这两项。

## 调试要点

### VLC 的错误不进常规 logcat

VLC 走 **native stderr**（tag `VLC-std`），必须开启重定向才能看到：

```bash
# log. 命名空间的属性，普通 shell 无权设置，需要 root 设备或 adb root（重启失效）。
# 实测：测试机（Redmi 2409BRN2CC，无 su）执行本命令报 Failed to set property，
# 因此本机看不到 VLC-std 输出；排查 VLC 初始化问题需换 root 设备。
adb shell setprop log.redirect-stdio true
adb logcat -b all -c
# 操作后
adb logcat -d -b all | grep -E 'VLC-std|unknown option|FATAL|can.t create LibVLC'
```

### 验收

单次测量会骗人，**必须 ≥5 次且包含"刚安装后首次启动"**（ProfileInstaller 编译期是最恶劣场景）：

```bash
adb install -r /sdcard/Download/lan-tv.apk
for i in 1 2 3 4 5; do
  adb shell am force-stop com.company.udpxytv; adb logcat -b main -c
  adb shell am start -n com.company.udpxytv/.ui.MainActivity; sleep 6
  adb shell input tap <坐标>; sleep 18
  echo "RUN$i 解码器=$(adb logcat -d | grep -c CreateByComponentName) underrun=$(adb logcat -d | grep -c underrun)"
done
```

判据：解码器 5/5、underrun ≈ 0、首条 Stats `Render ≥ 85`。

**注意**：用固定坐标 tap 在高负载下会点空（3/5），这是**测试方法问题不是应用缺陷** —— 真实用户看屏幕点不存在此问题。严谨做法是 `uiautomator dump` 后按控件 ID 取中心点。
