package com.offgrid.chat

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.text.format.DateUtils
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.TextView

/**
 * Home screen: who you are, how many phones are linked right now, and your conversations.
 * Deliberately plain; visuals come later.
 */
class MainActivity : Activity() {

    private data class Row(val conversation: String, val title: String, val subtitle: String)

    private lateinit var status: TextView
    private lateinit var me: TextView
    private lateinit var list: ListView
    private lateinit var adapter: ArrayAdapter<Row>
    private lateinit var toggle: Button
    private val refresh: () -> Unit = { render() }

    private val requiredPermissions: Array<String>
        get() = buildList {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                add(Manifest.permission.BLUETOOTH_SCAN)
                add(Manifest.permission.BLUETOOTH_ADVERTISE)
                add(Manifest.permission.BLUETOOTH_CONNECT)
            } else {
                add(Manifest.permission.ACCESS_FINE_LOCATION)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) add(Manifest.permission.POST_NOTIFICATIONS)
        }.toTypedArray()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val pad = dp(16)
        me = TextView(this).apply { textSize = 18f }
        status = TextView(this).apply { setPadding(0, dp(4), 0, dp(8)) }
        val rename = Button(this).apply {
            text = "Change my name"
            setOnClickListener { askName() }
        }
        toggle = Button(this).apply {
            setOnClickListener {
                if (MeshService.isEnabled(this@MainActivity)) {
                    MeshService.stop(this@MainActivity)
                } else {
                    MeshService.start(this@MainActivity)
                }
                render()
            }
        }
        adapter = object : ArrayAdapter<Row>(this, android.R.layout.simple_list_item_2, android.R.id.text1) {
            override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
                val v = super.getView(position, convertView, parent)
                val row = getItem(position)!!
                v.findViewById<TextView>(android.R.id.text1)?.text = row.title
                v.findViewById<TextView>(android.R.id.text2)?.text = row.subtitle
                return v
            }
        }
        list = ListView(this).apply {
            adapter = this@MainActivity.adapter
            setOnItemClickListener { _, _, position, _ ->
                val row = this@MainActivity.adapter.getItem(position)!!
                startActivity(
                    Intent(this@MainActivity, ChatActivity::class.java)
                        .putExtra(ChatActivity.EXTRA_CONVERSATION, row.conversation),
                )
            }
        }
        setContentView(
            LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(pad, pad, pad, 0)
                addView(me)
                addView(status)
                addView(
                    LinearLayout(this@MainActivity).apply {
                        orientation = LinearLayout.HORIZONTAL
                        addView(rename)
                        addView(toggle)
                    },
                )
                addView(
                    TextView(this@MainActivity).apply {
                        text = "Conversations (people appear here once their phone is reached through the mesh)"
                        setPadding(0, dp(12), 0, dp(4))
                    },
                )
                addView(list, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
            },
        )
    }

    override fun onResume() {
        super.onResume()
        Mesh.listen(refresh)
        ensureRunning()
        render()
    }

    override fun onPause() {
        Mesh.unlisten(refresh)
        super.onPause()
    }

    private fun ensureRunning() {
        val missing = requiredPermissions.filter { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }
        if (missing.isNotEmpty()) {
            requestPermissions(missing.toTypedArray(), 1)
            return
        }
        val adapter = getSystemService(BluetoothManager::class.java)?.adapter
        if (adapter == null) {
            status.text = "This phone has no Bluetooth."
            return
        }
        if (!adapter.isEnabled) {
            @Suppress("MissingPermission")
            startActivity(Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE))
        }
        if (Mesh.service == null && MeshService.isEnabled(this)) MeshService.start(this)
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, results: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, results)
        val bluetoothDenied = permissions.indices.any {
            permissions[it] != Manifest.permission.POST_NOTIFICATIONS && results[it] != PackageManager.PERMISSION_GRANTED
        }
        if (bluetoothDenied) {
            status.text = "Bluetooth permission is needed to reach nearby phones. Allow it in Settings > Apps."
        } else {
            ensureRunning()
        }
    }

    private fun render() {
        val enabled = MeshService.isEnabled(this)
        toggle.text = if (enabled) "Stop relaying" else "Start relaying"
        val s = Mesh.service
        if (s == null) {
            me.text = if (enabled) "Starting…" else "Relaying is off. Messages can't reach you or pass through you."
            adapter.clear()
            return
        }
        me.text = "${s.myName}  ·  id ${s.router.myId.take(8)}"
        status.text = "Phones linked right now: ${s.linkCount}   ·   Carrying ${s.router.carriedCount()} packets"

        val rows = mutableListOf<Row>()
        val everyoneLast = s.chats.lastMessage(ChatStore.EVERYONE)
        rows += Row(
            ChatStore.EVERYONE, "Everyone (public)",
            everyoneLast?.let { "${it.message.fromName}: ${it.message.text}" } ?: "Messages anyone in the mesh can read",
        )
        for (c in s.chats.contacts()) {
            val last = s.chats.lastMessage(c.id)
            val seen = DateUtils.getRelativeTimeSpanString(c.lastSeen).toString()
            rows += Row(
                c.id, "${c.name}  (${c.id.take(6)})",
                last?.let { (if (it.message.outgoing) "You: " else "") + it.message.text } ?: "Last heard $seen",
            )
        }
        adapter.clear()
        adapter.addAll(rows)
    }

    private fun askName() {
        val s = Mesh.service ?: return
        val input = EditText(this).apply { setText(s.myName); setSingleLine() }
        AlertDialog.Builder(this)
            .setTitle("Your name on the mesh")
            .setView(input)
            .setPositiveButton("Save") { _, _ ->
                val name = input.text.toString().trim()
                if (name.isNotEmpty()) s.myName = name
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
}
