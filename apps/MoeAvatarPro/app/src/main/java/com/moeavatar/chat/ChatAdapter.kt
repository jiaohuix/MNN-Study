package com.moeavatar.chat

import android.view.LayoutInflater
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.moeavatar.R
import com.moeavatar.llm.ChatTurn

class ChatAdapter : RecyclerView.Adapter<ChatAdapter.VH>() {

    private val items = mutableListOf<ChatTurn>()

    class VH(itemView: android.view.View) : RecyclerView.ViewHolder(itemView) {
        val bubble: TextView = itemView.findViewById(R.id.tv_bubble)
    }

    override fun getItemViewType(position: Int): Int =
        if (items[position].role == ChatTurn.Role.USER) TYPE_USER else TYPE_ASSISTANT

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val layout = if (viewType == TYPE_USER) R.layout.item_msg_user else R.layout.item_msg_assistant
        val v = LayoutInflater.from(parent.context).inflate(layout, parent, false)
        return VH(v)
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        holder.bubble.text = items[position].content
    }

    override fun getItemCount(): Int = items.size

    fun appendUser(text: String): Int {
        items.add(ChatTurn(ChatTurn.Role.USER, text))
        notifyItemInserted(items.size - 1)
        return items.size - 1
    }

    fun appendAssistantPlaceholder(): Int {
        items.add(ChatTurn(ChatTurn.Role.ASSISTANT, ""))
        notifyItemInserted(items.size - 1)
        return items.size - 1
    }

    fun updateAt(idx: Int, newText: String) {
        if (idx !in items.indices) return
        items[idx] = items[idx].copy(content = newText)
        notifyItemChanged(idx)
    }

    fun snapshot(): List<ChatTurn> = items.toList()

    fun clear() {
        val n = items.size
        items.clear()
        notifyItemRangeRemoved(0, n)
    }

    companion object {
        private const val TYPE_USER = 1
        private const val TYPE_ASSISTANT = 2
    }
}
