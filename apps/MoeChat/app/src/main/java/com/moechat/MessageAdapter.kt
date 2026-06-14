package com.moechat

import android.graphics.Typeface
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView

class MessageAdapter(private val messages: MutableList<ChatMessage>) :
    RecyclerView.Adapter<MessageAdapter.VH>() {

    companion object {
        private const val TYPE_USER = 0
        private const val TYPE_ASSISTANT = 1
    }

    class VH(view: View) : RecyclerView.ViewHolder(view) {
        val text: TextView = view.findViewById(R.id.message_text)
    }

    override fun getItemViewType(position: Int): Int =
        if (messages[position].role == ChatMessage.Role.USER) TYPE_USER else TYPE_ASSISTANT

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val layout = if (viewType == TYPE_USER) R.layout.item_message_user
        else R.layout.item_message_assistant
        val v = LayoutInflater.from(parent.context).inflate(layout, parent, false)
        return VH(v)
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        val msg = messages[position]
        holder.text.text = if (msg.content.isEmpty() && msg.isStreaming) "..." else msg.content
        holder.text.setTypeface(null, if (msg.isStreaming) Typeface.ITALIC else Typeface.NORMAL)
    }

    override fun getItemCount(): Int = messages.size

    fun appendMessage(msg: ChatMessage) {
        messages.add(msg)
        notifyItemInserted(messages.size - 1)
    }

    fun updateLast(text: String, streaming: Boolean) {
        if (messages.isEmpty()) return
        val last = messages.last()
        last.content = text
        last.isStreaming = streaming
        notifyItemChanged(messages.size - 1)
    }
}
