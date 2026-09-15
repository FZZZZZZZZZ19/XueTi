package com.xueti.learn.util

import android.content.Context
import android.graphics.Color
import android.util.TypedValue
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.core.content.ContextCompat
import org.json.JSONObject
import java.util.Collections
import java.util.WeakHashMap

/**
 * 公式渲染：把 AI 输出的 Markdown + LaTeX（$...$ / $$...$$）渲染成图文。
 *
 * 用内置在 `assets/render/` 的 KaTeX + marked 在 WebView 里离线渲染，
 * 不依赖网络、不需要第三方服务，数学公式不会再显示成天书。
 */
object FormulaRenderer {

    private const val PAGE_URL = "file:///android_asset/render/render.html"
    private const val FONT_SIZE_PX = 14.5

    /** 已加载完成的 WebView（弱引用，跟随页面回收） */
    private val readyViews: MutableSet<WebView> =
        Collections.newSetFromMap(WeakHashMap<WebView, Boolean>())

    /** 页面还没就绪时先缓存待渲染内容与回调 */
    private class Pending(val text: String, val onHeight: ((Int) -> Unit)?)

    private val pendingText = WeakHashMap<WebView, Pending>()

    /** 初始化 WebView 并加载渲染页；加载完成后会自动渲染最近一次内容 */
    fun attach(webView: WebView, context: Context) {
        // 重新 attach（列表复用同一个 WebView）时必须先撤销「已就绪」状态，
        // 否则 render() 会在新页面加载完成前就执行 JS，内容会丢
        readyViews.remove(webView)
        pendingText.remove(webView)
        webView.settings.apply {
            javaScriptEnabled = true
            @Suppress("DEPRECATION")
            allowFileAccess = true
            @Suppress("DEPRECATION")
            allowFileAccessFromFileURLs = true
        }
        webView.setBackgroundColor(Color.TRANSPARENT)
        webView.isVerticalScrollBarEnabled = true
        webView.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView, url: String?) {
                readyViews.add(view)
                pendingText.remove(view)?.let { renderNow(view, it.text, context, it.onHeight) }
            }
        }
        webView.loadUrl(PAGE_URL)
    }

    /** 渲染文本（Markdown + LaTeX）；页面未就绪时自动排队 */
    fun render(webView: WebView, text: String, context: Context) {
        render(webView, text, context, null)
    }

    /**
     * 渲染并回报内容高度（单位：CSS px ≈ dp）。
     * 供需要「自适应高度」的页面使用（例如小章学习的三个模块卡片）。
     */
    fun render(webView: WebView, text: String, context: Context, onHeight: ((Int) -> Unit)?) {
        if (readyViews.contains(webView)) {
            renderNow(webView, text, context, onHeight)
        } else {
            pendingText[webView] = Pending(text, onHeight)
        }
    }

    private fun renderNow(
        webView: WebView,
        text: String,
        context: Context,
        onHeight: ((Int) -> Unit)?
    ) {
        val color = hex(resolveColor(context, com.google.android.material.R.attr.colorOnSurface))
        val script = "window.renderContent(" +
            JSONObject.quote(text) + ", " +
            JSONObject.quote(color) + ", " +
            FONT_SIZE_PX + ");"
        if (onHeight == null) {
            runCatching { webView.evaluateJavascript(script, null) }
        } else {
            runCatching {
                webView.evaluateJavascript(script) { value ->
                    val height = value?.trim('"')?.toFloatOrNull()?.toInt() ?: 0
                    if (height > 0) onHeight(height)
                }
            }
        }
    }

    fun release(webView: WebView) {
        readyViews.remove(webView)
        pendingText.remove(webView)
    }

    /** 解析主题颜色（失败时退回深灰，保证在深浅色主题下都能看清） */
    private fun resolveColor(context: Context, attr: Int): Int {
        val value = TypedValue()
        val found = context.theme.resolveAttribute(attr, value, true)
        if (!found) return Color.DKGRAY
        return if (value.resourceId != 0) {
            runCatching { ContextCompat.getColor(context, value.resourceId) }.getOrDefault(Color.DKGRAY)
        } else {
            value.data
        }
    }

    private fun hex(color: Int): String = String.format("#%06X", 0xFFFFFF and color)
}
