package com.company.udpxytv.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * udpxy 地址解析的回归测试。
 *
 * 这几个用例对应一次代码复核里查出来的三类问题：缺端口拼出连不上的地址、
 * 完整 URL 分支漏校验 IPv4、端口越界（正则的 \d{1,5} 能匹配 99999）。
 */
class StreamUrlBuilderTest {

    // ---------- normalizeMulticast ----------

    @Test
    fun 裸IP加端口() {
        assertEquals("239.0.0.99:5140", StreamUrlBuilder.normalizeMulticast("239.0.0.99:5140"))
    }

    @Test
    fun udp前缀() {
        assertEquals("239.0.0.99:5140", StreamUrlBuilder.normalizeMulticast("udp://@239.0.0.99:5140"))
    }

    @Test
    fun rtp前缀() {
        assertEquals("239.1.1.1:5000", StreamUrlBuilder.normalizeMulticast("rtp://239.1.1.1:5000"))
    }

    @Test
    fun 完整udpxyURL取尾部() {
        assertEquals(
            "239.0.0.99:5140",
            StreamUrlBuilder.normalizeMulticast("http://192.168.1.1:9999/udp/239.0.0.99:5140")
        )
    }

    /** 缺端口必须补 5140：udpxy 的 /udp/<ip>:<port> 必须带端口，否则必然连不上 */
    @Test
    fun 缺端口补默认() {
        assertEquals("233.0.0.1:5140", StreamUrlBuilder.normalizeMulticast("233.0.0.1"))
    }

    @Test
    fun 非法IPv4判无效() {
        assertNull(StreamUrlBuilder.normalizeMulticast("999.1.1.1:5140"))
    }

    /** 完整 URL 分支以前不校验 IPv4，和非 URL 分支口径不一致 */
    @Test
    fun URL分支同样校验IPv4() {
        assertNull(StreamUrlBuilder.normalizeMulticast("http://192.168.1.1:9999/udp/999.1.1.1:5140"))
    }

    @Test
    fun 端口越界判无效() {
        assertNull(StreamUrlBuilder.normalizeMulticast("233.0.0.1:99999"))
    }

    @Test
    fun 端口为零判无效() {
        assertNull(StreamUrlBuilder.normalizeMulticast("233.0.0.1:0"))
    }

    @Test
    fun 端口上界可用() {
        assertEquals("233.0.0.1:65535", StreamUrlBuilder.normalizeMulticast("233.0.0.1:65535"))
    }

    @Test
    fun 空串判无效() {
        assertNull(StreamUrlBuilder.normalizeMulticast(""))
        assertNull(StreamUrlBuilder.normalizeMulticast("   "))
    }

    @Test
    fun 非地址判无效() {
        assertNull(StreamUrlBuilder.normalizeMulticast("hello"))
    }

    // ---------- normalizeGateway ----------

    @Test
    fun 网关IP加端口() {
        assertEquals("http://192.168.1.1:9999", StreamUrlBuilder.normalizeGateway("192.168.1.1:9999"))
    }

    @Test
    fun 网关缺端口按80() {
        assertEquals("http://192.168.1.1:80", StreamUrlBuilder.normalizeGateway("192.168.1.1"))
    }

    @Test
    fun 网关域名加端口() {
        assertEquals("http://ikuai.lan:9999", StreamUrlBuilder.normalizeGateway("ikuai.lan:9999"))
    }

    @Test
    fun 网关带协议带斜杠() {
        assertEquals("http://ikuai.lan:80", StreamUrlBuilder.normalizeGateway("http://ikuai.lan/"))
    }

    /** 写了越界端口应当判无效，而不是静默退回 80——那是另一个网关的端口 */
    @Test
    fun 网关端口越界判无效() {
        assertNull(StreamUrlBuilder.normalizeGateway("192.168.1.1:99999"))
    }

    @Test
    fun 网关端口为零判无效() {
        assertNull(StreamUrlBuilder.normalizeGateway("192.168.1.1:0"))
    }

    @Test
    fun 网关域名端口越界判无效() {
        assertNull(StreamUrlBuilder.normalizeGateway("ikuai.lan:99999"))
    }

    @Test
    fun 网关非法IP判无效() {
        assertNull(StreamUrlBuilder.normalizeGateway("999.1.1.1"))
    }

    @Test
    fun 网关空串判无效() {
        assertNull(StreamUrlBuilder.normalizeGateway(""))
    }

    // ---------- build ----------

    @Test
    fun 完整拼接() {
        assertEquals(
            "http://192.168.1.1:9999/udp/233.0.0.1:5140",
            StreamUrlBuilder.build("192.168.1.1:9999", "233.0.0.1")
        )
    }

    @Test
    fun 拼接时补缺省斜杠() {
        assertEquals(
            "http://192.168.1.1:9999/udp/233.0.0.1:5140",
            StreamUrlBuilder.build("192.168.1.1:9999", "233.0.0.1:5140", "udp")
        )
    }

    @Test
    fun 坏网关拼不出() {
        assertNull(StreamUrlBuilder.build("???", "233.0.0.1:5140"))
    }

    @Test
    fun 坏频道拼不出() {
        assertNull(StreamUrlBuilder.build("192.168.1.1:9999", "999.1.1.1:5140"))
    }

    /** 预置频道表里的地址形式必须都能解析（防回归） */
    @Test
    fun 预置地址形式都能解析() {
        val samples = listOf(
            "239.0.0.99:5140", "239.0.0.25:5140", "239.1.1.1:5140"
        )
        for (s in samples) {
            assertEquals(s, StreamUrlBuilder.normalizeMulticast(s))
        }
    }
}
