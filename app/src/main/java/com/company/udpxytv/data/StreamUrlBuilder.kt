package com.company.udpxytv.data

/**
 * udpxy 地址拼接。
 *
 * udpxy 把局域网的 UDP 组播转成 HTTP 单播，地址形如：
 *     http://<网关>:<端口>/udp/<组播IP>:<端口>
 *
 * 网关写法不限，以下都能识别：
 *   <internal_ip>:9999 / http://<internal_ip>:9999 / <internal_ip>
 *   ikuai.lan:9999 / ikuai.lan / http://ikuai.lan/
 * 端口省略按 80 处理。
 */
object StreamUrlBuilder {

    /** IP[:端口] */
    private val HOST_PORT = Regex("""(\d{1,3}\.\d{1,3}\.\d{1,3}\.\d{1,3})(?:\D+(\d{1,5}))?""")

    /** 域名[:端口] */
    private val DOMAIN_PORT = Regex("""([A-Za-z0-9_-]+(?:\.[A-Za-z0-9_-]+)+)(?::(\d{1,5}))?""")

    /** 未指定端口时使用 */
    private const val DEFAULT_PORT = "80"

    /** 组播地址未指定端口时使用——IPTV 组播惯例，预置频道全部是 5140 */
    private const val DEFAULT_MCAST_PORT = "5140"

    /**
     * 归一化单个组播地址为 "IP:端口"。
     * 支持 233.x.x.x:5140、udp://@233.x.x.x:5140、rtp://239.x.x.x:5000，
     * 以及完整 udpxy URL（自动取 /udp/ 后面的部分）。
     */
    fun normalizeMulticast(raw: String): String? {
        val s = raw.trim()
        if (s.isEmpty()) return null

        if (s.startsWith("http://") || s.startsWith("https://")) {
            val tail = s.substringAfterLast('/')
            val m = HOST_PORT.find(tail) ?: return null
            // 这条分支以前不校验 IPv4，与下面那条不一致，
            // 999.1.1.1 也能从这里混进来
            if (!isValidIpv4(m.groupValues[1])) return null
            val port = normalizePort(m.groupValues.getOrNull(2), DEFAULT_MCAST_PORT) ?: return null
            return m.groupValues[1] + ":" + port
        }

        val cleaned = s.substringAfterLast('@')
        val m = HOST_PORT.find(cleaned) ?: return null
        val ip = m.groupValues[1]
        if (!isValidIpv4(ip)) return null
        // 缺端口时补默认端口：udpxy 的 /udp/<ip>:<port> 必须带端口，
        // 以前直接返回不带端口的 IP，拼出来的 URL 必然连不上
        val port = normalizePort(m.groupValues.getOrNull(2), DEFAULT_MCAST_PORT) ?: return null
        return ip + ":" + port
    }

    /**
     * 端口归一化，必须落在 1..65535。
     *
     * 三态区分，缺一不可：
     *   没写端口        -> 用 default
     *   写了且合法      -> 规范化后的值
     *   写了但越界/非数字 -> null，调用方判整个地址无效
     *
     * 【为什么不能一律 ?: default】正则的 \d{1,5} 能匹配到 99999/00000。
     * 早先写成 normalizePort(...) ?: DEFAULT_PORT，结果 <internal_ip>:99999
     * 被悄悄改成 :80 —— 用户填的是另一个端口，却被指向了默认端口的服务，
     * 报错信息还完全看不出是自己写错了。
     */
    private fun normalizePort(raw: String?, default: String): String? {
        if (raw.isNullOrEmpty()) return default
        val p = raw.toIntOrNull() ?: return null
        return if (p in 1..65535) p.toString() else null
    }

    /**
     * 组装最终播放 URL。
     */
    fun build(gateway: String, channel: String, udpPath: String = "/udp/"): String? {
        val gw = normalizeGateway(gateway) ?: return null
        val ch = normalizeMulticast(channel) ?: return null
        val path = if (udpPath.startsWith("/")) udpPath else "/" + udpPath
        val path2 = if (path.endsWith("/")) path else path + "/"
        return gw + path2 + ch
    }

    /** 网关归一化 -> "http://host:port" */
    fun normalizeGateway(raw: String): String? {
        var s = raw.trim()
        if (s.isEmpty()) return null

        s = s.replace(Regex("^(?i)https?://"), "")
        s = s.substringBefore('?').trim().trimEnd('/')
        if (s.isEmpty()) return null

        s = s.substringBefore('/')
        if (s.isEmpty()) return null

        val ipPort = HOST_PORT.find(s)
        if (ipPort != null) {
            // 形如数字的 host 必须是合法 IPv4，否则不认（避免把 999.1.1.1 当地址）
            if (!isValidIpv4(ipPort.groupValues[1])) return null
            val port = normalizePort(ipPort.groupValues.getOrNull(2), DEFAULT_PORT) ?: return null
            return "http://" + ipPort.groupValues[1] + ":" + port
        }

        val hostPort = DOMAIN_PORT.find(s)
        if (hostPort != null) {
            val host = hostPort.groupValues[1].trimEnd('.')
            if (host.isNotEmpty() && host.contains('.')) {
                val port = normalizePort(hostPort.groupValues.getOrNull(2), DEFAULT_PORT) ?: return null
                return "http://" + host + ":" + port
            }
        }

        return null
    }

    private fun isValidIpv4(ip: String): Boolean {
        val parts = ip.split(".")
        if (parts.size != 4) return false
        return parts.all { part ->
            part.isNotEmpty() && part.length <= 3 &&
                part.toIntOrNull()?.let { it in 0..255 } == true
        }
    }
}
