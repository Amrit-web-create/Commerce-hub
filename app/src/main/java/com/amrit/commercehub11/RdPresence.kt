package com.amrit.commercehub11

import android.content.Context
import android.provider.Settings
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.UUID

/**
 * Active status / last seen / device tracking ki alag file.
 *
 * Teen layer ek saath kaam karte hain (koi ek fail ho to baaki sambhal lete hain):
 *  1. Supabase Realtime presence  -> RdNet (turant: online / background / left)
 *  2. Database heartbeat (rd_devices.last_seen) -> yahan: har ~3 sec, realtime band ho tab bhi sahi status
 *  3. Direct P2P DataChannel -> RdP2P ("connected")
 */
object RdPresence {
    class DevRow(val user: String, val dev: String, val seen: Long)

    var dbActive by mutableStateOf(false)   // DB heartbeat se samne wala abhi active
    var rtReady by mutableStateOf(false)    // realtime websocket judi hui hai
    const val ACTIVE_MS = 15000L

    private val JSON = "application/json".toMediaType()

    /** Phone ka apna pakka device id (ANDROID_ID se): har install par wahi rehta hai */
    fun deviceId(ctx: Context): String {
        var d = Prefs.getString("rd_dev", "")
        if (d.isEmpty()) {
            val aid = try { Settings.Secure.getString(ctx.contentResolver, Settings.Secure.ANDROID_ID) ?: "" } catch (_: Throwable) { "" }
            d = "h" + (if (aid.length >= 8) aid.take(14) else UUID.randomUUID().toString().replace("-", "").take(14))
            Prefs.putString("rd_dev", d)
        }
        return d
    }

    fun reachable(): Boolean = RdNet.peerOnline || dbActive

    fun iso(t: Long): String {
        val f = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US)
        f.timeZone = TimeZone.getTimeZone("UTC")
        return f.format(Date(t))
    }

    fun parseIso(s: String): Long {
        return try {
            var x = s.replace("Z", "+00:00")
            x = x.replace(Regex("\\.(\\d{3})\\d*"), ".$1")
            val f = SimpleDateFormat(if (x.contains(".")) "yyyy-MM-dd'T'HH:mm:ss.SSSXXX" else "yyyy-MM-dd'T'HH:mm:ssXXX", Locale.US)
            f.parse(x)?.time ?: 0L
        } catch (_: Throwable) { 0L }
    }

    private fun req(path: String) = Request.Builder().url(RdCfg.SB_URL + path)
        .header("apikey", RdCfg.SB_KEY).header("Authorization", "Bearer " + RdCfg.SB_KEY)

    /** Meri device row refresh (last_seen = ab) */
    suspend fun beat(me: String, dev: String) {
        if (!Conn.isOnline() || me.isEmpty()) return
        withContext(Dispatchers.IO) {
            try {
                val body = JSONObject().put("id", "$me|$dev").put("user_id", me).put("dev_id", dev).put("last_seen", iso(System.currentTimeMillis()))
                Net.http.newCall(
                    req("/rest/v1/${RdCfg.T_DEV}").header("Prefer", "resolution=merge-duplicates,return=minimal")
                        .post(body.toString().toRequestBody(JSON)).build()
                ).await().close()
            } catch (_: Throwable) {}
        }
    }

    /** Dono users ke devices ka taaza last_seen */
    suspend fun poll(): List<DevRow>? = withContext(Dispatchers.IO) {
        try {
            val url = (RdCfg.SB_URL + "/rest/v1/" + RdCfg.T_DEV).toHttpUrl().newBuilder()
                .addQueryParameter("select", "user_id,dev_id,last_seen")
                .addQueryParameter("last_seen", "gte." + iso(System.currentTimeMillis() - RdCfg.DEV_TTL)).build()
            Net.http.newCall(Request.Builder().url(url).header("apikey", RdCfg.SB_KEY).header("Authorization", "Bearer " + RdCfg.SB_KEY).build()).await().use { r ->
                if (!r.isSuccessful) return@withContext null
                val arr = JSONArray(r.body?.string() ?: "[]")
                val out = ArrayList<DevRow>()
                for (i in 0 until arr.length()) {
                    val o = arr.getJSONObject(i)
                    out.add(DevRow(o.s("user_id"), o.s("dev_id"), parseIso(o.s("last_seen"))))
                }
                out
            }
        } catch (_: Throwable) { null }
    }

    /** Connection ki sthiti (profile me dikhti hai) */
    fun diag(): String =
        "Realtime: " + (if (rtReady) "connected" else "off") +
            "  \u2022  P2P: " + (if (RdP2P.open) "connected" else "not connected") +
            "  \u2022  Peer: " + (if (RdNet.peerActive) "active" else if (RdNet.peerOnline) "background" else "offline")
}
