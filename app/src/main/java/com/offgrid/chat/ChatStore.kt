package com.offgrid.chat

import android.util.Base64
import com.offgrid.mesh.ChatMessage
import com.offgrid.mesh.Contact
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.Executor

/** A message as shown in the UI, plus its delivery state. */
data class StoredMessage(val message: ChatMessage, val delivered: Boolean) {
    /** Which conversation it belongs to: [ChatStore.EVERYONE] or the other person's id. */
    val conversation: String
        get() = when {
            message.toId == null -> ChatStore.EVERYONE
            message.outgoing -> message.toId!!
            else -> message.fromId
        }
}

/** Chat history and known people, saved as small JSON files in the app's private storage. */
class ChatStore(dir: File, private val io: Executor = Executor { it.run() }) {
    companion object {
        const val EVERYONE = "everyone"
        private const val MAX_MESSAGES = 5000
    }

    private val messagesFile = File(dir, "messages.json")
    private val contactsFile = File(dir, "contacts.json")

    private val messages = LinkedHashMap<String, StoredMessage>()
    private val contacts = LinkedHashMap<String, Contact>()

    init {
        dir.mkdirs()
        runCatching {
            val arr = JSONArray(messagesFile.readText())
            for (i in 0 until arr.length()) {
                val m = messageFrom(arr.getJSONObject(i))
                messages[m.message.id] = m
            }
        }
        runCatching {
            val arr = JSONArray(contactsFile.readText())
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                val c = Contact(
                    o.getString("id"), o.getString("name"),
                    Base64.decode(o.getString("key"), Base64.NO_WRAP), o.getLong("seen"),
                )
                contacts[c.id] = c
            }
        }
    }

    fun contacts(): List<Contact> = contacts.values.sortedByDescending { it.lastSeen }

    fun contact(id: String): Contact? = contacts[id]

    fun messages(conversation: String): List<StoredMessage> =
        messages.values.filter { it.conversation == conversation }.sortedBy { it.message.sentAt }

    fun lastMessage(conversation: String): StoredMessage? = messages(conversation).lastOrNull()

    /** Returns false if we already had it (the mesh can deliver the same message again after a restart). */
    fun add(message: ChatMessage): Boolean {
        if (message.id in messages) return false
        messages[message.id] = StoredMessage(message, delivered = false)
        while (messages.size > MAX_MESSAGES) messages.remove(messages.keys.first())
        saveMessages()
        return true
    }

    fun markDelivered(id: String) {
        val m = messages[id] ?: return
        if (m.delivered) return
        messages[id] = m.copy(delivered = true)
        saveMessages()
    }

    fun putContact(contact: Contact) {
        val known = contacts[contact.id]
        if (known != null && known.lastSeen > contact.lastSeen) return
        contacts[contact.id] = contact
        saveContacts()
    }

    private fun saveMessages() {
        val arr = JSONArray()
        messages.values.forEach { arr.put(messageTo(it)) }
        writeAtomically(messagesFile, arr.toString())
    }

    private fun saveContacts() {
        val arr = JSONArray()
        contacts.values.forEach {
            arr.put(
                JSONObject().put("id", it.id).put("name", it.name)
                    .put("key", Base64.encodeToString(it.publicKey, Base64.NO_WRAP)).put("seen", it.lastSeen),
            )
        }
        writeAtomically(contactsFile, arr.toString())
    }

    private fun writeAtomically(file: File, text: String) = io.execute {
        val tmp = File(file.parentFile, file.name + ".tmp")
        tmp.writeText(text)
        tmp.renameTo(file)
    }

    private fun messageTo(s: StoredMessage) = JSONObject().apply {
        val m = s.message
        put("id", m.id); put("from", m.fromId); put("fromName", m.fromName)
        put("to", m.toId ?: JSONObject.NULL); put("text", m.text); put("at", m.sentAt)
        put("hops", m.hops); put("out", m.outgoing); put("delivered", s.delivered)
    }

    private fun messageFrom(o: JSONObject) = StoredMessage(
        ChatMessage(
            id = o.getString("id"),
            fromId = o.getString("from"),
            fromName = o.getString("fromName"),
            toId = if (o.isNull("to")) null else o.getString("to"),
            text = o.getString("text"),
            sentAt = o.getLong("at"),
            hops = o.getInt("hops"),
            outgoing = o.getBoolean("out"),
        ),
        delivered = o.optBoolean("delivered"),
    )
}
