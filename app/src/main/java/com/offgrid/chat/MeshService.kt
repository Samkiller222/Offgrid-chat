package com.offgrid.chat

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.bluetooth.BluetoothAdapter
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Base64
import com.offgrid.mesh.ChatMessage
import com.offgrid.mesh.Contact
import com.offgrid.mesh.FilePacketStore
import com.offgrid.mesh.Identity
import com.offgrid.mesh.MeshListener
import com.offgrid.mesh.MeshRouter
import java.io.File
import java.util.concurrent.Executors

/**
 * Keeps the mesh running while the screen is off or the app is in the background.
 * That matters: a phone in someone's pocket is exactly what carries messages along.
 *
 * Runs everything on the main thread. The UI reads state through [Mesh].
 */
class MeshService : Service(), BleTransport.Events, MeshListener {

    companion object {
        private const val CHANNEL_SERVICE = "mesh"
        private const val CHANNEL_MESSAGES = "messages"
        private const val NOTIFY_ID = 1
        private const val ANNOUNCE_EVERY_MS = 30 * 60_000L
        private const val PRUNE_EVERY_MS = 10 * 60_000L

        /** Stop relaying entirely (saves battery; this phone stops carrying messages for others). */
        fun stop(context: Context) {
            context.getSharedPreferences("offgrid", MODE_PRIVATE).edit().putBoolean("enabled", false).apply()
            context.stopService(Intent(context, MeshService::class.java))
        }

        fun isEnabled(context: Context) =
            context.getSharedPreferences("offgrid", MODE_PRIVATE).getBoolean("enabled", true)

        fun start(context: Context) {
            context.getSharedPreferences("offgrid", MODE_PRIVATE).edit().putBoolean("enabled", true).apply()
            val intent = Intent(context, MeshService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) context.startForegroundService(intent)
            else context.startService(intent)
        }
    }

    private val main = Handler(Looper.getMainLooper())
    /** All disk writes go through one background thread, in order. */
    private val io = Executors.newSingleThreadExecutor()
    lateinit var router: MeshRouter
        private set
    lateinit var chats: ChatStore
        private set
    private lateinit var transport: BleTransport
    private var nextNotificationId = 100

    val linkCount: Int get() = if (::transport.isInitialized) transport.linkCount else 0

    var myName: String
        get() = prefs().getString("name", null) ?: "Phone ${router.myId.take(4)}"
        set(value) {
            prefs().edit().putString("name", value.trim().take(40)).apply()
            router.announce(myName)
            Mesh.changed()
        }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createChannels()
        goForeground()

        chats = ChatStore(File(filesDir, "chat"), io)
        router = MeshRouter(loadIdentity(), FilePacketStore(File(filesDir, "packets"), io = io), this)
        chats.contacts().forEach { router.rememberContact(it) }
        transport = BleTransport(this, this)
        router.transport = transport

        registerReceiver(bluetoothState, IntentFilter(BluetoothAdapter.ACTION_STATE_CHANGED))
        transport.start()
        router.announce(myName)
        main.postDelayed(reannounce, ANNOUNCE_EVERY_MS)
        main.postDelayed(prune, PRUNE_EVERY_MS)

        Mesh.service = this
        Mesh.changed()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY

    override fun onDestroy() {
        main.removeCallbacksAndMessages(null)
        unregisterReceiver(bluetoothState)
        transport.stop()
        io.shutdown()
        Mesh.service = null
        Mesh.changed()
        super.onDestroy()
    }

    // ------------------------------------------------------------------ actions from the UI

    /** Send to one person, or to everyone nearby when [toId] is null. */
    fun send(text: String, toId: String?) {
        val message = router.sendText(text, myName, toId)
        chats.add(message)
        Mesh.changed()
    }

    /**
     * A code you can text, show or read out to someone, so they can message you before
     * your announcement has reached them through the mesh.
     */
    val myContactCode: String
        get() = "offgrid:" + Base64.encodeToString(router.myPublicKey, Base64.NO_WRAP or Base64.URL_SAFE) +
            "#" + myName

    /** Returns the added contact, or null if the code isn't valid. */
    fun addContactFromCode(code: String): Contact? {
        val body = code.trim().substringAfter("offgrid:", "")
        if (body.isEmpty()) return null
        val key = runCatching {
            Base64.decode(body.substringBefore("#"), Base64.NO_WRAP or Base64.URL_SAFE)
        }.getOrNull() ?: return null
        if (runCatching { com.offgrid.mesh.Crypto.publicKey(key) }.isFailure) return null
        val name = body.substringAfter("#", "").trim().take(40).ifEmpty { "Unknown" }
        val contact = Contact(com.offgrid.mesh.NodeId.of(key), name, key, System.currentTimeMillis())
        if (contact.id == router.myId) return null
        router.rememberContact(contact)
        chats.putContact(contact)
        Mesh.changed()
        return contact
    }

    // ------------------------------------------------------------------ transport -> router

    override fun onLinkUp(link: String) {
        router.linkUp(link)
        Mesh.changed()
    }

    override fun onLinkDown(link: String) {
        router.linkDown(link)
        Mesh.changed()
    }

    override fun onLinkData(link: String, bytes: ByteArray) {
        router.onLinkData(link, bytes)
        Mesh.changed()  // carried count may have changed
    }

    // ------------------------------------------------------------------ router -> app

    override fun onMessage(message: ChatMessage) {
        if (chats.add(message)) {
            notifyMessage(message)
            Mesh.changed()
        }
    }

    override fun onDelivered(messageId: String) {
        chats.markDelivered(messageId)
        Mesh.changed()
    }

    override fun onContact(contact: Contact) {
        chats.putContact(contact)
        Mesh.changed()
    }

    // ------------------------------------------------------------------ plumbing

    private val reannounce = object : Runnable {
        override fun run() {
            router.announce(myName)
            main.postDelayed(this, ANNOUNCE_EVERY_MS)
        }
    }

    private val prune = object : Runnable {
        override fun run() {
            router.pruneExpired()
            Mesh.changed()
            main.postDelayed(this, PRUNE_EVERY_MS)
        }
    }

    private val bluetoothState = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, -1)) {
                BluetoothAdapter.STATE_ON -> transport.start()
                BluetoothAdapter.STATE_TURNING_OFF, BluetoothAdapter.STATE_OFF -> {
                    transport.stop()
                    Mesh.changed()
                }
            }
        }
    }

    private fun prefs() = getSharedPreferences("offgrid", MODE_PRIVATE)

    /** Same keypair every launch, so your address (and everyone's chats with you) stays stable. */
    private fun loadIdentity(): Identity {
        val p = prefs()
        val priv = p.getString("priv", null)
        val pub = p.getString("pub", null)
        if (priv != null && pub != null) {
            runCatching {
                return Identity.restore(Base64.decode(priv, Base64.NO_WRAP), Base64.decode(pub, Base64.NO_WRAP))
            }
        }
        val id = Identity.generate()
        p.edit()
            .putString("priv", Base64.encodeToString(id.privateEncoded, Base64.NO_WRAP))
            .putString("pub", Base64.encodeToString(id.publicEncoded, Base64.NO_WRAP))
            .apply()
        return id
    }

    private fun createChannels() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val nm = getSystemService(NotificationManager::class.java) ?: return
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_SERVICE, "Mesh running", NotificationManager.IMPORTANCE_LOW),
        )
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_MESSAGES, "Messages", NotificationManager.IMPORTANCE_HIGH),
        )
    }

    private fun openAppIntent(): PendingIntent = PendingIntent.getActivity(
        this, 0, Intent(this, MainActivity::class.java),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    private fun goForeground() {
        val notification = Notification.Builder(this, CHANNEL_SERVICE)
            .setContentTitle("OffGrid Chat is relaying")
            .setContentText("Passing messages between nearby phones")
            .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
            .setContentIntent(openAppIntent())
            .setOngoing(true)
            .build()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFY_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)
        } else {
            startForeground(NOTIFY_ID, notification)
        }
    }

    private fun notifyMessage(message: ChatMessage) {
        if (Mesh.visibleConversation == (message.toId?.let { message.fromId } ?: ChatStore.EVERYONE)) return
        val title = if (message.toId == null) "${message.fromName} (everyone)" else message.fromName
        val n = Notification.Builder(this, CHANNEL_MESSAGES)
            .setContentTitle(title)
            .setContentText(message.text)
            .setSmallIcon(android.R.drawable.stat_notify_chat)
            .setContentIntent(openAppIntent())
            .setAutoCancel(true)
            .build()
        runCatching { getSystemService(NotificationManager::class.java)?.notify(nextNotificationId++, n) }
    }
}

/** The bridge between the running service and whatever screen is open. */
object Mesh {
    var service: MeshService? = null
    /** Conversation currently on screen, so we don't notify for it. */
    var visibleConversation: String? = null
    private val listeners = LinkedHashSet<() -> Unit>()

    fun listen(l: () -> Unit) { listeners.add(l) }
    fun unlisten(l: () -> Unit) { listeners.remove(l) }
    private val main = Handler(Looper.getMainLooper())
    private var pending = false

    /** Coalesces bursts of mesh traffic into one UI refresh. */
    fun changed() {
        if (pending) return
        pending = true
        main.postDelayed({
            pending = false
            listeners.toList().forEach { it() }
        }, 150)
    }
}
