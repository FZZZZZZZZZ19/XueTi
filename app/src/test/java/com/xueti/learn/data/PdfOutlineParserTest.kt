package com.xueti.learn.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * PDF 目录解析的单元测试（纯 JVM，不依赖 Android）：
 * 验证「几百页教材」场景下能否把目录解析成 章 / 节 + 物理页范围。
 */
class PdfOutlineParserTest {

    /** 模拟一本 40 页的书：目录跨第 1-2 页，正文从第 5 页开始（前置 4 页） */
    private fun sampleBook(): List<String> {
        val pages = MutableList(40) { "第 ${it + 1} 页正文内容占位" }

        pages[0] = """
            目录
            第一章 绪论 ................................ 1
            1.1 研究背景 ............................... 3
            1.2 研究方法 ............................... 8
        """.trimIndent()

        pages[1] = """
            第二章 基础理论 ............................ 15
            2.1 集合与映射 ............................. 16
            2.2 关系与函数 ............................. 24
        """.trimIndent()

        // 正文：物理页 = 印刷页 + 4
        pages[4] = "第一章 绪论\n本章介绍研究的意义。"       // 印刷 1
        pages[6] = "1.1 研究背景\n背景说明……"              // 印刷 3
        pages[11] = "1.2 研究方法\n方法说明……"             // 印刷 8
        pages[18] = "第二章 基础理论\n理论概述……"          // 印刷 15
        pages[19] = "2.1 集合与映射\n定义与例子……"         // 印刷 16
        pages[27] = "2.2 关系与函数\n关系定义……"           // 印刷 24
        return pages
    }

    @Test
    fun `能识别目录页并解析出条目`() {
        val pages = sampleBook()
        val toc = PdfOutlineParser.detectTocPages(pages)
        assertEquals(listOf(1, 2), toc)

        val parsed = PdfOutlineParser.parse(pages)
        assertNotNull("应当能解析出目录", parsed)
        parsed!!
        assertEquals(6, parsed.entries.size)
        assertEquals("第一章 绪论", parsed.entries.first().title)
        assertEquals(0, parsed.entries.first().level)
        assertTrue("应识别出小节层级", parsed.entries.any { it.level == 1 })
    }

    @Test
    fun `能把印刷页码换算成物理页码并算出每节页范围`() {
        val pages = sampleBook()
        val parsed = PdfOutlineParser.parse(pages)!!
        // 目录里"第一章 绪论"印刷页 1，正文物理页 5 → 偏移 4
        assertEquals(4, parsed.pageOffset)
        val firstChapter = parsed.entries.first { it.title == "第一章 绪论" }
        assertEquals(5, firstChapter.page)

        val outline = PdfOutlineParser.buildOutline(parsed, pages.size)
        assertEquals(2, outline.size)

        val (chapter1Title, sections1) = outline[0]
        assertEquals("第一章 绪论", chapter1Title)
        assertEquals(2, sections1.size)
        // 1.1：物理页 3+4=7 → 1.2 起点 8+4=12 → 结束 11
        assertEquals("1.1 研究背景", sections1[0].first)
        assertEquals(7, sections1[0].second)
        assertEquals(11, sections1[0].third)
        assertEquals(12, sections1[1].second)

        val (chapter2Title, sections2) = outline[1]
        assertEquals("第二章 基础理论", chapter2Title)
        assertEquals("2.1 集合与映射", sections2[0].first)
        assertEquals(20, sections2[0].second)
        assertEquals(27, sections2[0].third)
        assertEquals("2.2 关系与函数", sections2[1].first)
        assertEquals(28, sections2[1].second)
    }

    @Test
    fun `标题定位会跳过目录页`() {
        val pages = sampleBook()
        // 目录页里也有"1.1 研究背景"，但定位必须落在正文第 7 页
        val found = PdfTextExtractor.findSectionStart(
            pages,
            "1.1 研究背景",
            PdfOutlineParser.detectTocPages(pages).toSet()
        )
        assertEquals(7, found)
    }

    @Test
    fun `没有目录的扫描版返回 null`() {
        val pages = List(10) { "第 ${it + 1} 页\n（扫描图片，无文字层）" }
        assertNull(PdfOutlineParser.parse(pages))
    }

    @Test
    fun `按页范围取原文只返回对应页`() {
        val pages = sampleBook()
        val text = PdfTextExtractor.pagesInRange(pages, com.xueti.learn.model.PageRange(6, 7))
        assertTrue(text.contains("第 6 页"))
        assertTrue(text.contains("第 7 页"))
        assertTrue(!text.contains("第 8 页"))
    }

    @Test
    fun `页码索引包含每一页的开头文字`() {
        val pages = sampleBook()
        val digest = PdfTextExtractor.pageIndexDigest(pages, maxChars = 2000)
        assertTrue(digest.contains("第 5 页"))
        assertTrue(digest.contains("第一章 绪论"))
    }
}
