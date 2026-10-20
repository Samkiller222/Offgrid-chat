package com.offgrid.chat

import android.util.Log
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Recent mesh events, kept in memory for the Debug screen, so field tests with real
 * phones and radios can show what happened without a laptop attached. Also goes to logcat.
 */
object MeshLog {
    private const val MAX = 500
    private val lines = ArrayDeque<String>()
    private val time = SimpleDateFormat("HH:mm:ss", Locale.US)

    @Synchronized
    private fun add(level: String, tag: String, msg: String) {
        lines.addLast("${time.format(Date())} $level $tag: $msg")
        while (lines.size > MAX) lines.removeFirst()
    }

    @Synchronized
    fun snapshot(): List<String> = lines.toList()

    fun i(tag: String, msg: String) { Log.i(tag, msg); add("I", tag, msg) }

    fun w(tag: String, msg: String, e: Throwable? = null) {
        Log.w(tag, msg, e)
        add("W", tag, msg + (e?.let { " ($it)" } ?: ""))
    }

    fun e(tag: String, msg: String, e: Throwable? = null) {
        Log.e(tag, msg, e)
        add("E", tag, msg + (e?.let { " ($it)" } ?: ""))
    }
}
