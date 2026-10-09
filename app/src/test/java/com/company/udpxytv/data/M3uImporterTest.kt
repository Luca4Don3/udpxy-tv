package com.company.udpxytv.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 批量导入解析的回归测试。
 *
 * 重点覆盖 skipped：以前 parse() 遇到认不出的行直接 continue，
 * 而 parseWithReport 的 skipped 又硬编码成 emptyList()，
 * 等于"无效行静默丢弃"——用户贴 50 行只进 20 行，也不知道另外 30 行出了什么事。
 */
class M3uImporterTest {

    private fun ch(id: String, name: String, addr: String) = Channel(id, name, addr)

    @Test
    fun m3u标准格式() {
        val r = M3uImporter.parseWithReport(
            "#EXTINF:-1,CCTV-1综合\nudp://@239.0.0.99:5140",
            emptyList()
        )
        assertEquals(1, r.imported.size)
        assertEquals("CCTV-1综合", r.imported[0].name)
        assertEquals("udp://@239.0.0.99:5140", r.imported[0].multicast)
        assertTrue(r.skipped.isEmpty())
    }

    @Test
    fun extinf里带逗号不被误切() {
        val r = M3uImporter.parseWithReport(
            "#EXTINF:-1 tvg-id=\"ch1\", CCTV-1综合\nudp://@239.0.0.99:5140",
            emptyList()
        )
        assertEquals(1, r.imported.size)
        assertTrue(r.imported[0].name.contains("CCTV-1综合"))
    }

    @Test
    fun 名称逗号地址() {
        val r = M3uImporter.parseWithReport("CCTV-5,239.0.0.5:5140", emptyList())
        assertEquals("CCTV-5", r.imported[0].name)
        assertEquals("239.0.0.5:5140", r.imported[0].multicast)
    }

    @Test
    fun 名称空格地址() {
        val r = M3uImporter.parseWithReport("CCTV-5   239.0.0.5:5140", emptyList())
        assertEquals("CCTV-5", r.imported[0].name)
        assertEquals("239.0.0.5:5140", r.imported[0].multicast)
    }

    @Test
    fun 裸地址名称自取() {
        val r = M3uImporter.parseWithReport("239.0.0.5:5140", emptyList())
        assertEquals(1, r.imported.size)
        assertEquals("239.0.0.5:5140", r.imported[0].multicast)
    }

    @Test
    fun 缺端口的地址也能导入() {
        val r = M3uImporter.parseWithReport("239.0.0.5", emptyList())
        assertEquals(1, r.imported.size)
        assertEquals("239.0.0.5", r.imported[0].multicast)
    }

    /** 认不出的行必须进 skipped，不能静默消失 */
    @Test
    fun 无效行计入skipped() {
        val r = M3uImporter.parseWithReport(
            """
            CCTV-1,239.0.0.99:5140
            这行完全不是地址
            CCTV-2,999.1.1.1:5140
            CCTV-3,239.0.0.3:5140
            """.trimIndent(),
            emptyList()
        )
        assertEquals(2, r.imported.size)
        assertEquals(2, r.skipped.size)
        assertTrue(r.skipped.any { it.contains("完全不是地址") })
    }

    @Test
    fun 与已有重复计入duplicated() {
        val existing = listOf(ch("a", "已有的", "239.0.0.99:5140"))
        val r = M3uImporter.parseWithReport(
            """
            CCTV-1,239.0.0.99:5140
            CCTV-2,239.0.0.2:5140
            """.trimIndent(),
            existing
        )
        assertEquals(1, r.imported.size)
        assertEquals(1, r.duplicated)
        assertEquals("239.0.0.2:5140", r.imported[0].multicast)
    }

    /** 已有频道用 udp:// 写法时，新加的裸地址仍应判为重复 */
    @Test
    fun 重复判定按归一化地址() {
        val existing = listOf(ch("a", "已有的", "udp://@239.0.0.99:5140"))
        val r = M3uImporter.parseWithReport("CCTV-1,239.0.0.99:5140", existing)
        assertEquals(0, r.imported.size)
        assertEquals(1, r.duplicated)
    }

    @Test
    fun 注释与空行被忽略() {
        val r = M3uImporter.parseWithReport(
            """
            #EXTM3U
            #EXTVLCOPT:http-user-agent=Mozilla

            CCTV-1,239.0.0.99:5140
            """.trimIndent(),
            emptyList()
        )
        assertEquals(1, r.imported.size)
        assertTrue(r.skipped.isEmpty())
    }

    @Test
    fun 空文本() {
        val r = M3uImporter.parseWithReport("", emptyList())
        assertTrue(r.imported.isEmpty())
        assertTrue(r.skipped.isEmpty())
    }

    @Test
    fun 全部无效时报出跳过数() {
        val r = M3uImporter.parseWithReport("abc\nxyz", emptyList())
        assertTrue(r.imported.isEmpty())
        assertEquals(2, r.skipped.size)
    }

    /** parse() 保持只返回有效频道的旧行为（预览在用） */
    @Test
    fun parse只返回有效频道() {
        val list = M3uImporter.parse("abc\nCCTV-1,239.0.0.99:5140")
        assertEquals(1, list.size)
    }
}
