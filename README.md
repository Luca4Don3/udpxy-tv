# 局域网直播 App(udpxy)

在 Android 手机上通过 **udpxy** 观看局域网组播 IPTV。
原生 Kotlin + LibVLC,不依赖任何在线服务,流量全在局域网内。

## 特性

- 局域网组播 → HTTP 单播(udpxy),无需公网
- LibVLC 3.7.6 播放内核,硬解 h264
- 双架构:arm64-v8a + armeabi-v7a
- 频道增删改 + m3u 批量导入 / 导出
- 滑动调系统音量与亮度,退出自动恢复
- 实时码率显示

## 环境要求

| 项 | 值 |
|---|---|
| minSdk | 24(Android 7.0) |
| targetSdk | 34 / compileSdk 36 |
| 构建 | AGP 9.0.0 + Gradle 9.7.1 + JDK 17 |
| 播放内核 | LibVLC 3.7.6 |

## 构建

```bash
./build.sh
```

## 使用

1. 进**设置**填 udpxy 网关地址(本局域网路由器的 IP 与 udpxy 端口),
   路径前缀一般保持 `/udp/`,点**保存连接**
2. 回主界面,点右下角**添加频道**;或用 **设置 → 批量导入频道** 一次导入多个
3. 点频道播放,自动横屏

频道列表可以导出备份:**设置 → 导出频道**,存成 m3u。

### 网关写法

```
192.168.1.1:9999        http://192.168.1.1:9999      192.168.1.1(端口按 80)
router.local:9999        router.local
```

### 频道地址写法

```
#EXTINF:-1,CCTV1 综合          ← m3u 清单
udp://@233.1.1.1:5140

CCTV6 电影,233.1.1.1:5141      ← 逗号分隔
体育频道  239.1.1.1:5000       ← 空格 / 制表符
233.1.1.1:6000                  ← 纯地址
CCTV1,http://192.168.1.1:9999/udp/233.1.1.1:5140   ← 完整 URL
```

> **预置频道只是示例。** `PresetChannels.kt` 里放的是 5 个占位地址 ——
> 各地 IPTV 的组播地址由运营商决定,人人不同,所以没有写死。请导入自己的清单。

### 手势

| 手势 | 行为 |
|---|---|
| 右半屏上下滑 | 系统媒体音量 |
| 左半屏上下滑 | 系统屏幕亮度 |
| 单击 | 显隐信息层(3.5s 自动淡出) |

## 工程结构

```
app/src/main/java/com/company/udpxytv/
├── data/
│   ├── Channel.kt              频道模型
│   ├── ChannelStore.kt         SharedPreferences 持久化
│   ├── StreamUrlBuilder.kt     udpxy URL 拼接与校验
│   ├── M3uImporter.kt          m3u / 文本批量导入
│   ├── M3uExporter.kt          导出 m3u
│   └── PresetChannels.kt       预置频道(示例占位)
└── ui/
    ├── MainActivity.kt         频道列表
    ├── ChannelAdapter.kt
    ├── SettingsActivity.kt     网关配置 + 导入导出
    └── PlayerActivity.kt       LibVLC 播放 + 手势
```

## 单元测试

```bash
gradle :app:testDebugUnitTest
```

46 个 JVM 用例(不需要设备),覆盖地址解析与 m3u 导入导出的往返一致性,
已设为出包门禁 —— 测试不过就不构建。

## 已知限制

- 仅限同一局域网;切到蜂窝网络会提示「当前不在局域网」
- MP2 音频走 FFmpeg 软解(Android 没有该编码的硬件解码器)
- 起播有 0.5–1.5s 固有延迟:组播从 GOP 中间接入,要等 SPS/PPS + IDR
- 亮度手势需要「修改系统设置」授权,未授权时降级为窗口级
- 未做 EPG、频道 logo、搜索排序

## 实现要点

几个不直观、但会影响正确性的决定,详见 [docs/INTERNALS.md](docs/INTERNALS.md):

- libvlc 的音量基线是 **256**(0dB)
- 缓存选项必须**先设 media 级、再开硬解**,顺序反了会被覆盖
- 退出释放放后台线程,否则主线程会卡住约 3 秒
- 码率读 `TrafficStats`,不用会 SIGSEGV 的 `Media.getStats()`
- 音量和亮度都改**系统设置本身**,保证与系统 UI 永远一致

## 许可证

[MIT](LICENSE)

