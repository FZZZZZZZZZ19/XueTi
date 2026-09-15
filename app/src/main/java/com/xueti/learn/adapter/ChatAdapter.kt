package com.xueti.learn.adapter

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.core.view.isVisible
import androidx.recyclerview.widget.RecyclerView
import com.xueti.learn.R
import com.xueti.learn.data.SectionChatMessage
import com.xueti.learn.databinding.ItemChatMessageBinding
import com.xueti.learn.util.FormulaRenderer

/**
 * 小节对话列表：学生消息右对齐气泡，AI 回答用 KaTeX 渲染（公式正常显示）。
 */
class ChatAdapter(
    private val onCopy: (String) -> Unit
) : RecyclerView.Adapter<ChatAdapter.VH>() {

    private val items = mutableListOf<SectionChatMessage>()
    private val webViews = mutableListOf<android.webkit.WebView>()

    fun submit(list: List<SectionChatMessage>) {
        items.clear()
        items.addAll(list)
        notifyDataSetChanged()
    }

    /** 页面销毁时回收 WebView */
    fun releaseWebViews() {
        webViews.forEach { web ->
            FormulaRenderer.release(web)
            runCatching { web.destroy() }
        }
        webViews.clear()
    }

    class VH(val binding: ItemChatMessageBinding) : RecyclerView.ViewHolder(binding.root)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH =
        VH(ItemChatMessageBinding.inflate(LayoutInflater.from(parent.context), parent, false))

    override fun onBindViewHolder(holder: VH, position: Int) {
        val item = items[position]
        val b = holder.binding

        if (item.isUser) {
            b.userRow.isVisible = true
            b.aiRow.isVisible = false
            b.userText.text = item.text
        } else {
            b.userRow.isVisible = false
            b.aiRow.isVisible = true
            b.aiActions.text = b.root.context.getString(R.string.chat_copy)
            FormulaRenderer.attach(b.aiWeb, b.root.context)
            if (!webViews.contains(b.aiWeb)) webViews.add(b.aiWeb)
            FormulaRenderer.render(b.aiWeb, item.text, b.root.context) { heightCss ->
                val density = b.root.resources.displayMetrics.density
                val target = (heightCss * density).toInt() + (density * 10).toInt()
                val minimum = (48 * density).toInt()
                val height = maxOf(target, minimum)
                val params = b.aiWeb.layoutParams
                if (params.height != height) {
                    params.height = height
                    b.aiWeb.layoutParams = params
                }
            }
            b.aiActions.setOnClickListener { onCopy(item.text) }
        }
    }

    override fun getItemCount(): Int = items.size
}
