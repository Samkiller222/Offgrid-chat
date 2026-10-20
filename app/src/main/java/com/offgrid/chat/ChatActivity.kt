package com.offgrid.chat

import android.app.Activity
import android.os.Bundle
import android.text.format.DateFormat
import android.view.Gravity
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import com.offgrid.mesh.MeshRouter

/** One conversation: the public "everyone" room, or a private chat with one person. */
class ChatActivity : Activity() {

    companion object {
        const val EXTRA_CONVERSATION = "conversation"
    }

    private lateinit var conversation: String
    private lateinit var messagesView: LinearLayout
    private lateinit var scroll: ScrollView
    private lateinit var input: EditText
    private val refresh: () -> Unit = { render() }
    private var shownCount = -1

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        conversation = intent.getStringExtra(EXTRA_CONVERSATION) ?: ChatStore.EVERYONE
        val pad = dp(12)

        messagesView = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        scroll = ScrollView(this).apply { addView(messagesView) }
        input = EditText(this).apply {
            hint = "Message"
            maxLines = 4
        }
        val send = Button(this).apply {
            text = "Send"
            setOnClickListener { send() }
        }
        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(input, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            addView(send)
        }
        setContentView(
            LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(pad, pad, pad, pad)
                addView(TextView(this@ChatActivity).apply { text = title(); textSize = 18f })
                addView(scroll, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
                addView(bar)
            },
        )
    }

    override fun onResume() {
        super.onResume()
        Mesh.visibleConversation = conversation
        Mesh.listen(refresh)
        render()
    }

    override fun onPause() {
        Mesh.visibleConversation = null
        Mesh.unlisten(refresh)
        super.onPause()
    }

    private fun title(): String {
        if (conversation == ChatStore.EVERYONE) return "Everyone (public, not encrypted)"
        val c = Mesh.service?.chats?.contact(conversation)
        return "${c?.name ?: "Unknown"} · end-to-end encrypted"
    }

    private fun send() {
        val s = Mesh.service ?: return
        val text = input.text.toString().trim()
        if (text.isEmpty()) return
        if (text.toByteArray().size > MeshRouter.MAX_TEXT_BYTES) {
            Toast.makeText(this, "Message is too long", Toast.LENGTH_SHORT).show()
            return
        }
        try {
            s.send(text, if (conversation == ChatStore.EVERYONE) null else conversation)
            input.setText("")
        } catch (e: IllegalArgumentException) {
            Toast.makeText(this, "Can't reach this person yet: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    private fun render() {
        val s = Mesh.service ?: return
        val messages = s.chats.messages(conversation)
        messagesView.removeAllViews()
        for (m in messages) {
            val msg = m.message
            val time = DateFormat.getTimeFormat(this).format(msg.sentAt)
            val meta = when {
                msg.outgoing && conversation == ChatStore.EVERYONE -> "$time · sent"
                msg.outgoing -> "$time · " + if (m.delivered) "delivered ✓✓" else "on its way ✓"
                else -> "$time · ${msg.hops} hop${if (msg.hops == 1) "" else "s"}"
            }
            val who = if (msg.outgoing) "You" else msg.fromName
            messagesView.addView(
                TextView(this).apply {
                    text = "$who\n${msg.text}\n$meta"
                    gravity = if (msg.outgoing) Gravity.END else Gravity.START
                    setPadding(dp(8), dp(6), dp(8), dp(6))
                },
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT),
            )
        }
        if (messages.size != shownCount) {
            shownCount = messages.size
            scroll.post { scroll.fullScroll(ScrollView.FOCUS_DOWN) }
        }
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
}
