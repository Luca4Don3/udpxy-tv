package com.company.udpxytv.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 导出的回归测试。
 *
 * 重点是与 [M3uImporter] 的往返一致：导出的唯一用途就是"以后能导回来"，
 * 只要有一处格式对不上，备份就是废纸。
 */
class M3uExporterTest {

    private fun ch(name: String, addr: String) = Channel("id-" + name, name, addr)

    @Test
    fun 空列表只有头部() {
        assertEquals("#EXTM3U\n", M3uExporter.export(emptyList()))
    }

    @Test
    fun 导出再导入能还原名称与地址() {
        val src = listOf(
            ch("CCTV-1HD", "239.0.0.99:5140"),
            ch("CCTV-2HD", "udp://@239.0.1.198:5140"),
            ch("CCTV-3HD", "http://192.168.1.1:9999/udp/239.0.0.25:5140")
        )
        val back = M3uImporter.parse(M3uExporter.export(src))
        assertEquals(src.size, back.size)
        src.forEachIndexed { i, s ->
            assertEquals(s.name, back[i].name)
            assertEquals(s.multicast, back[i].multicast)
        }
    }

    @Test
    fun 名称含换行不破坏行结构() {
        val text = M3uExporter.export(listOf(ch("坏\n名字", "239.0.0.99:5140")))
        // 头部 + EXTINF + 地址，恰好三行；换行若没被抹掉就会多出一行
        assertEquals(3, text.trimEnd('\n').split("\n").size)
        val back = M3uImporter.parse(text)
        assertEquals(1, back.size)
        assertEquals("239.0.0.99:5140", back[0].multicast)
    }

    @Test
    fun 名称含逗号能完整还原() {
        val back = M3uImporter.parse(
            M3uExporter.export(listOf(ch("CCTV-1,高清", "239.0.0.99:5140")))
        )
        assertEquals(1, back.size)
        assertEquals("CCTV-1,高清", back[0].name)
    }

    @Test
    fun 空地址频道被跳过() {
        val back = M3uImporter.parse(
            M3uExporter.export(listOf(ch("空地址", ""), ch("正常", "239.0.0.99:5140")))
        )
        assertEquals(1, back.size)
        assertEquals("正常", back[0].name)
    }

    @Test
    fun 各种地址写法原样保留() {
        for (addr in listOf(
            "239.0.0.99:5140",
            "udp://@239.0.1.198:5140",
            "rtp://239.1.1.1:5000",
            "http://192.168.1.1:9999/udp/239.0.0.25:5140"
        )) {
            val text = M3uExporter.export(listOf(ch("X", addr)))
            assertTrue("应原样写出: " + addr, text.contains(addr))
            val back = M3uImporter.parse(text)
            assertEquals(1, back.size)
            assertEquals(addr, back[0].multicast)
        }
    }

    @Test
    fun 建议文件名格式正确() {
        val f = M3uExporter.suggestedFileName()
        assertTrue(f, Regex("""^channels-\d{8}\.m3u$""").matches(f))
    }
}
