package com.company.udpxytv.data

/**
 * 一个直播频道。
 *
 * [multicast] 支持三种写法，都能被 [StreamUrlBuilder] 归一化：
 *  - 纯 IP:端口         239.0.1.198:5140
 *  - udp:// 前缀        udp://@239.0.1.198:5140
 *  - 完整 udpxy URL    http://<internal_ip>:9999/udp/239.0.1.198:5140
 */
data class Channel(
    val id: String,
    val name: String,
    val multicast: String
)
