package com.xueti.learn.data

import com.xueti.learn.model.PageRange
import kotlin.system.exitProcess

/**
 * 临时自检入口（不随 APK 发布，验证完即删除）。
 * 直接在 JVM 上跑真实的目录解析逻辑，确认「几百页 PDF → 章节页范围」可用。
 */
object TocSelfCheck {

    private var failed = 0

    private fun check(name: String, condition: Boolean, detail: String = "") {
        if (condition) {
            println("PASS  $name  $detail")
        } else {
            failed++
            println("FAIL  $name  $detail")
        }
    }

    /** 目录跨 2 页（第 1-2 页），正文从第 5 页开始（前置 4 页） */
    private fun sampleBook(): List<String> {
        val pages = MutableList(40) { "第 ${it + 1} 页正文内容占位" }
        pages[0] = listOf(
            "目录",
            "第一章 绪论 ................................ 1",
            "1.1 研究背景 ............................... 3",
            "1.2 研究方法 ............................... 8"
        ).joinToString("\n")
        pages[1] = listOf(
            "第二章 基础理论 ............................ 15",
            "2.1 集合与映射 ............................. 16",
            "2.2 关系与函数 ............................. 24"
        ).joinToString("\n")
        pages[4] = "第一章 绪论\n本章介绍研究的意义。"
        pages[6] = "1.1 研究背景\n背景说明……"
        pages[11] = "1.2 研究方法\n方法说明……"
        pages[18] = "第二章 基础理论\n理论概述……"
        pages[19] = "2.1 集合与映射\n定义与例子……"
        pages[27] = "2.2 关系与函数\n关系定义……"
        return pages
    }

    @JvmStatic
    fun main(args: Array<String>) {
        val pages = sampleBook()

        val tocPages = PdfOutlineParser.detectTocPages(pages)
        check("识别目录页（跨 2 页）", tocPages == listOf(1, 2), "→ $tocPages")

        val parsed = PdfOutlineParser.parse(pages)
        check("解析出目录", parsed != null)
        if (parsed == null) {
            println("\n结果: 失败（无法解析目录）")
            exitProcess(1)
        }

        check("条目数 = 6", parsed!!.entries.size == 6, "→ ${parsed.entries.size}")
        check(
            "识别章层级",
            parsed.entries.first().level == 0 && parsed.entries.any { it.level == 1 }
        )
        check("页码偏移 = 4", parsed.pageOffset == 4, "→ ${parsed.pageOffset}")
        val firstChapter = parsed.entries.first { it.title == "第一章 绪论" }
        check("第一章物理页 = 5", firstChapter.page == 5, "→ ${firstChapter.page}")

        val outline = PdfOutlineParser.buildOutline(parsed, pages.size)
        check("章数 = 2", outline.size == 2, "→ ${outline.map { it.first }}")
        if (outline.size == 2) {
            val (t1, s1) = outline[0]
            check("第 1 章标题", t1 == "第一章 绪论", "→ $t1")
            check("第 1 章节数 = 2", s1.size == 2, "→ ${s1.size}")
            if (s1.size == 2) {
                check("1.1 页范围 7-11", s1[0].second == 7 && s1[0].third == 11, "→ ${s1[0].second}-${s1[0].third}")
                check("1.2 起始 12", s1[1].second == 12, "→ ${s1[1].second}")
            }
            val (t2, s2) = outline[1]
            check("第 2 章标题", t2 == "第二章 基础理论", "→ $t2")
            if (s2.size == 2) {
                check("2.1 页范围 20-27", s2[0].second == 20 && s2[0].third == 27, "→ ${s2[0].second}-${s2[0].third}")
                check("2.2 起始 28", s2[1].second == 28, "→ ${s2[1].second}")
            }
        }

        // 标题定位必须跳过目录页（第 1、2 页）
        val found = PdfTextExtractor.findSectionStart(
            pages,
            "1.1 研究背景",
            PdfOutlineParser.detectTocPages(pages).toSet()
        )
        check("定位小节跳过目录页 = 7", found == 7, "→ $found")

        // 按页范围取原文
        val text = PdfTextExtractor.pagesInRange(pages, PageRange(6, 7))
        check(
            "按页范围取原文只含 6、7 页",
            text.contains("第 6 页") && text.contains("第 7 页") && !text.contains("第 8 页")
        )

        // 页码索引
        val digest = PdfTextExtractor.pageIndexDigest(pages, maxChars = 2000)
        check(
            "页码索引含页码与开头文字",
            digest.contains("第 5 页") && digest.contains("第一章 绪论")
        )

        // 扫描版（无文字层）应解析失败
        val scanned = List(10) { "第 ${it + 1} 页\n（扫描图片，无文字层）" }
        check("扫描版目录解析返回 null", PdfOutlineParser.parse(scanned) == null)

        println("\n结果: " + if (failed == 0) "全部通过" else "$failed 项失败")
        exitProcess(if (failed == 0) 0 else 1)
    }
}
