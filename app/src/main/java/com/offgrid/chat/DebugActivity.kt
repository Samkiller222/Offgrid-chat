package com.offgrid.chat

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

/** Field-test screen: live counters and the recent event log, with a button to share it. */
class DebugActivity : Activity() {
    private lateinit var summary: TextView
    private lateinit var log: TextView
    private val refresh: () -> Unit = { render() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val pad = (12 * resources.displayMetrics.density).toInt()
        summary = TextView(this)
        log = TextView(this).apply {
            typeface = android.graphics.Typeface.MONOSPACE
            textSize = 11f
            setTextIsSelectable(true)
        }
        val share = Button(this).apply {
            text = "Share log"
            setOnClickListener {
                startActivity(
                    Intent.createChooser(
                        Intent(Intent.ACTION_SEND).setType("text/plain")
                            .putExtra(Intent.EXTRA_TEXT, summary.text.toString() + "\n\n" + MeshLog.snapshot().joinToString("\n")),
                        "Share OffGrid log",
                    ),
                )
            }
        }
        setContentView(
            LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(pad, pad, pad, pad)
                addView(summary)
                addView(share)
                addView(ScrollView(this@DebugActivity).apply { addView(log) },
                    LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
            },
        )
    }

    override fun onResume() {
        super.onResume()
        Mesh.listen(refresh)
        render()
    }

    override fun onPause() {
        Mesh.unlisten(refresh)
        super.onPause()
    }

    private fun render() {
        val s = Mesh.service
        summary.text = if (s == null) "Mesh not running" else buildString {
            append("My id: ${s.router.myId}\n")
            append("Bluetooth links: ${s.linkCount}\n")
            append("LoRa radio: ${s.lora.state} ${s.lora.deviceName ?: ""}\n")
            append("Carrying ${s.router.carriedCount()} packets, know ${s.router.contacts().size} people")
        }
        log.text = MeshLog.snapshot().asReversed().joinToString("\n")
    }
}
