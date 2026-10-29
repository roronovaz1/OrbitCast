package com.example.orbitcast

import android.content.Context
import android.content.SharedPreferences
import android.os.Build

object Prefs {
    private lateinit var sp: SharedPreferences
    fun init(c: Context) { sp = c.getSharedPreferences("orbit", Context.MODE_PRIVATE) }

    var dark: Boolean
        get() = sp.getBoolean("dark", true)
        set(v) { sp.edit().putBoolean("dark", v).apply() }
    /** Short side of the encoded video in px. 0 = native resolution. */
    var shortSide: Int
        get() = sp.getInt("side", 1080)
        set(v) { sp.edit().putInt("side", v).apply() }
    var mbps: Int
        get() = sp.getInt("mbps", 8)
        set(v) { sp.edit().putInt("mbps", v).apply() }
    var fps: Int
        get() = sp.getInt("fps", 60)
        set(v) { sp.edit().putInt("fps", v).apply() }
    var mic: Boolean
        get() = sp.getBoolean("mic", true)
        set(v) { sp.edit().putBoolean("mic", v).apply() }
    var internal: Boolean
        get() = sp.getBoolean("int", false)
        set(v) { sp.edit().putBoolean("int", v).apply() }
    /** Relay base URL for Internet mode, e.g. wss://my-relay.example.com */
    var relay: String
        get() = sp.getString("relay", "") ?: ""
        set(v) { sp.edit().putString("relay", v).apply() }
    var deviceName: String
        get() = sp.getString("name", Build.MODEL) ?: Build.MODEL
        set(v) { sp.edit().putString("name", v).apply() }
    var trusted: Set<String>
        get() = sp.getStringSet("trusted", emptySet()) ?: emptySet()
        set(v) { sp.edit().putStringSet("trusted", v).apply() }
}

/** name -> (short side, Mbps, fps). Short side 0 = native. */
val PRESETS = listOf(
    "LOW" to Triple(720, 2, 30),
    "BAL" to Triple(1080, 8, 60),
    "HIGH" to Triple(1440, 20, 60),
    "MAX" to Triple(0, 50, 60),
)
