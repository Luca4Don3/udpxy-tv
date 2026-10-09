#!/bin/bash
# 局域网直播 App 构建脚本（在 199 上执行）
# 用法: ./build.sh [版本注释]
#
# 固化两个已实测的问题：
#   1. packageDebug 阶段 OOM（增量打包瞬时峰值，内存充裕也会触发）
#      -> 每次构建前清 app/build，触发条件是增量而非资源不足
#   2. 源码写入静默失败（引号转义层层丢失）
#      -> 关键符号在构建前 grep 校验，写入不成功立刻中止

set -euo pipefail

PROJ=${PROJECT_ROOT:?set PROJECT_ROOT}
BT=${ANDROID_SDK:?set ANDROID_SDK}/build-tools/36.0.0
APK_DIR=$PROJ/app/build/outputs/apk/debug
ARM64=$APK_DIR/app-arm64-v8a-debug.apk

cd "$PROJ"

echo "===== [1/6] 预检：构建配置 ====="
grep -q "com.android.application.*9.0.0" build.gradle.kts \
  || { echo "✗ 根构建文件异常（AGP 应为 9.0.0）"; exit 1; }
if grep -q "org.jetbrains.kotlin.android" app/build.gradle.kts; then
  echo "✗ app/build.gradle.kts 残留 kotlin 插件（AGP 9 会冲突）"; exit 1
fi
echo "  ✓ 配置正常"
echo "  libvlc: $(grep -oE 'libvlc-all:[0-9.]+' app/build.gradle.kts)"
echo "  版本:   $(grep -oE 'versionName = "[^"]+"' app/build.gradle.kts)"

echo
echo "===== [2/6] 预检：关键源码符号 ====="
SRC=app/src/main/java/com/company/udpxytv/ui/PlayerActivity.kt
for sym in applyMediaOptions setHWDecoderEnabled isLandscape togglePlayPause; do
  n=$(grep -c "$sym" "$SRC" 2>/dev/null || echo 0)
  echo "  $sym: $n"
done
if grep -q 'addOption(":network-caching' "$SRC"; then
  echo "  ✓ 缓存配置存在"

# 备份文件不能留在 res/ 里：AGP 会扫描整个 res 目录，
# .xml.bak 扩展名不合法会导致 mergeDebugResources 失败
BAK=$(find app/src/main/res -name "*.bak*" 2>/dev/null | head -5)
if [ -n "$BAK" ]; then
  echo "  ✗ res/ 下有备份文件（AGP 会扫描并报错）:"
  echo "$BAK" | sed 's/^/      /'
  echo "     请移到项目根的 .bak/ 目录"
  exit 1
fi
echo "  ✓ res/ 无备份文件"
else
  echo "  ✗ 未找到 media 级 network-caching，源码可能被覆盖丢失"; exit 1
fi

echo
echo "===== [3/6] 清理增量产物（规避 packageDebug OOM）====="
gradle --stop >/dev/null 2>&1 || true
rm -rf app/build

echo
echo "===== [4/6] 单元测试 ====="
# 放在构建之前：StreamUrlBuilder 这类纯函数出问题会一路带到真机才暴露
if ! gradle :app:testDebugUnitTest --no-daemon; then
  echo "✗ 单元测试未通过，拒绝出包"; exit 1
fi

echo
echo "===== [5/6] 构建 ====="
if ! gradle assembleDebug --no-daemon; then
  echo "✗ 构建失败，日志尾部："; tail -30 build.log 2>/dev/null; exit 1
fi

echo
echo "===== [6/6] 产物校验 ====="
[ -f "$ARM64" ] || { echo "✗ 未生成 arm64 APK"; exit 1; }

# 产物符号检查：源码里在，不代表编译进 APK 的还在。
# 教训——曾用 index() 做区间替换时把 end 落错位置，整段删掉 onCreate /
# startPlayback / onVlcEvent / options / detectDecoder，Kotlin 照样 BUILD SUCCESSFUL
# （成员连引用一起消失，override 缺失也合法），装上去根本播不了。
# 所以要对着**产物**再核一遍。
echo "  —— 产物符号检查 ——"
TMPD=$(mktemp -d)
( cd "$TMPD" && unzip -o -q "$ARM64" "classes*.dex" && cat classes*.dex | strings -n 4 > s.txt )
MISSING=""
for sym in onCreate startPlayback onVlcEvent detectDecoder sampleStreamStats             releasePlayerAsync restoreSystemState applyMediaOptions             adjustVolume adjustBrightness normalizePort parseWithReport addChannelsBulk suggestedFileName; do
  grep -qx "$sym" "$TMPD/s.txt" || MISSING="$MISSING $sym"
done
rm -rf "$TMPD"
if [ -n "$MISSING" ]; then
  echo "  ✗ APK 里缺少关键符号：$MISSING"; exit 1
fi
echo "  ✓ 关键符号齐全（onCreate / startPlayback / onVlcEvent / detectDecoder / 采样器 …）"

# strip 门禁：AGP 找不到 NDK 的 strip 工具时会打印
#   "Unable to strip ... packaging them as they are"
# 然后**静默**原样打包，arm64 的 libc++_shared.so 带着调试信息进包，白涨 8MB。
# 历史日志里每个版本都有这条，直到 2.12.0 才第一次真正 strip 成功。
# 不影响功能，但属于静默降级——按项目准则要显式暴露。
echo "  —— strip 门禁 ——"
LIBCXX_SIZE=$(unzip -l "$ARM64" | grep 'lib/arm64-v8a/libc++_shared.so' | awk '{print $1}')
if [ -z "$LIBCXX_SIZE" ]; then
  echo "  ✗ 产物里找不到 lib/arm64-v8a/libc++_shared.so"; exit 1
fi
if [ "$LIBCXX_SIZE" -gt 2000000 ]; then
  echo "  ✗ libc++_shared.so 未 strip：$LIBCXX_SIZE 字节（strip 后约 1.4MB）"
  echo "    多半是 NDK 的 strip 工具不可用，检查 ${ANDROID_SDK:?set ANDROID_SDK}/ndk"
  echo "    注意：不影响功能，但包会白白大 8MB，不要让它静默降级。"
  exit 1
fi
echo "  ✓ libc++_shared.so 已 strip（$LIBCXX_SIZE 字节）"

VER=$($BT/aapt2 dump badging "$ARM64" 2>/dev/null | sed -n "1p")
echo "  $VER"
echo "  大小: $(ls -lh "$ARM64" | awk '{print $5}')"
echo "  sha256: $(sha256sum "$ARM64" | cut -c1-32)"

# 16KB 页：zip 对齐（APK 内 so 偏移）
ALIGN=$($BT/zipalign -c -P 16 -v 4 "$ARM64" 2>&1 | grep -iE "verification|bad" | tail -1 || true)
echo "  16KB zip 对齐: ${ALIGN:-(无输出=通过)}"

# 16KB 页：ELF p_align（需解包 so）
TMP=$(mktemp -d)
unzip -o -q "$ARM64" "lib/arm64-v8a/libvlc.so" -d "$TMP" 2>/dev/null
if [ -f "$TMP/lib/arm64-v8a/libvlc.so" ]; then
  P=$(readelf -lW "$TMP/lib/arm64-v8a/libvlc.so" 2>/dev/null | grep LOAD | awk '{print $NF}' | sort -u | tr '\n' ' ' || true)
  echo "  libvlc p_align: $P"
  case "$P" in
    *0x4000*) ;;
    *) echo "  ✗ p_align 不是 0x4000，16KB 设备会 dlopen 失败"; rm -rf "$TMP"; exit 1 ;;
  esac
fi
rm -rf "$TMP"

echo
echo "✓ 构建完成: $ARM64"
echo
echo "推送手机: adb -s <设备> push $ARM64 /sdcard/Download/lan-tv.apk"
