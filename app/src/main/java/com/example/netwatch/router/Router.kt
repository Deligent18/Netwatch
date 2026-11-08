package com.example.netwatch.router

import android.content.Context
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

data class WifiClient(val mac: String, val signal: Int?, val rxBytes: Long?, val txBytes: Long?)
data class Lease(val mac: String, val ip: String, val hostname: String?)

/**
 * Talks to a router YOU administer. Add more implementations (OpenWrt is the first)
 * for other firmware; the rest of the app only uses this interface.
 */
interface RouterClient {
    suspend fun wifiClients(): List<WifiClient>
    suspend fun leases(): List<Lease>

    /** Disconnects the Wi-Fi client and bans it from re-associating for [banSeconds]. */
    suspend fun kick(mac: String, banSeconds: Int = 3600): Boolean
}

class RouterSettings(context: Context) {
    private val prefs = EncryptedSharedPreferences.create(
        context,
        "router_prefs",
        MasterKey.Builder(context).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build(),
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
    )

    var url: String
        get() = prefs.getString("url", "") ?: ""
        set(v) = prefs.edit().putString("url", v.trim()).apply()
    var user: String
        get() = prefs.getString("user", "root") ?: "root"
        set(v) = prefs.edit().putString("user", v.trim()).apply()
    var password: String
        get() = prefs.getString("pass", "") ?: ""
        set(v) = prefs.edit().putString("pass", v).apply()

    fun client(): RouterClient? = if (url.isBlank()) null else OpenWrtClient(url, user, password)
}

/** OpenWrt via its ubus JSON-RPC endpoint (http://router/ubus). Needs the uhttpd-mod-ubus package (default on OpenWrt). */
class OpenWrtClient(
    private val baseUrl: String,
    private val user: String,
    private val pass: String,
) : RouterClient {

    private val http = OkHttpClient.Builder()
        .connectTimeout(4, TimeUnit.SECONDS)
        .readTimeout(8, TimeUnit.SECONDS)
        .build()
    private var sid: String? = null

    private fun post(method: String, params: JSONArray): JSONObject {
        val body = JSONObject().put("jsonrpc", "2.0").put("id", 1).put("method", method).put("params", params)
        val req = Request.Builder()
            .url(baseUrl.trimEnd('/') + "/ubus")
            .post(body.toString().toRequestBody("application/json".toMediaType()))
            .build()
        http.newCall(req).execute().use { r ->
            return JSONObject(r.body?.string() ?: error("empty response"))
        }
    }

    private fun login(): String {
        val res = post(
            "call",
            JSONArray().put(ANON).put("session").put("login")
                .put(JSONObject().put("username", user).put("password", pass)),
        )
        val arr = res.optJSONArray("result")
        check(arr != null && arr.getInt(0) == 0) { "Router login failed" }
        return arr.getJSONObject(1).getString("ubus_rpc_session")
    }

    private fun call(obj: String, fn: String, args: JSONObject = JSONObject()): JSONObject? {
        repeat(2) {
            val s = sid ?: login().also { sid = it }
            val arr = post("call", JSONArray().put(s).put(obj).put(fn).put(args)).optJSONArray("result")
            if (arr != null && arr.getInt(0) == 0) return arr.optJSONObject(1) ?: JSONObject()
            if (arr != null && arr.getInt(0) == 6) sid = null else return null // 6 = session expired
        }
        return null
    }

    private fun hostapdObjects(): List<String> {
        val s = sid ?: login().also { sid = it }
        val res = post("list", JSONArray().put(s).put("hostapd.*")).optJSONObject("result") ?: return emptyList()
        return res.keys().asSequence().toList()
    }

    override suspend fun wifiClients(): List<WifiClient> = withContext(Dispatchers.IO) {
        hostapdObjects().flatMap { iface ->
            val clients = call(iface, "get_clients")?.optJSONObject("clients") ?: return@flatMap emptyList()
            clients.keys().asSequence().map { mac ->
                val c = clients.getJSONObject(mac)
                val bytes = c.optJSONObject("bytes")
                WifiClient(
                    mac = mac.lowercase(),
                    signal = if (c.has("signal")) c.optInt("signal") else null,
                    rxBytes = bytes?.optLong("rx"),
                    txBytes = bytes?.optLong("tx"),
                )
            }.toList()
        }
    }

    override suspend fun leases(): List<Lease> = withContext(Dispatchers.IO) {
        val arr = call("luci-rpc", "getDHCPLeases")?.optJSONArray("dhcp_leases") ?: return@withContext emptyList()
        (0 until arr.length()).mapNotNull { i ->
            val o = arr.getJSONObject(i)
            val mac = o.optString("macaddr")
            val ip = o.optString("ipaddr")
            if (mac.isBlank() || ip.isBlank()) null
            else Lease(mac.lowercase(), ip, o.optString("hostname").takeIf { it.isNotBlank() && it != "*" })
        }
    }

    override suspend fun kick(mac: String, banSeconds: Int): Boolean = withContext(Dispatchers.IO) {
        var ok = false
        for (iface in hostapdObjects()) {
            val args = JSONObject()
                .put("addr", mac)
                .put("reason", 5)
                .put("deauth", true)
                .put("ban_time", banSeconds * 1000)
            if (call(iface, "del_client", args) != null) ok = true
        }
        ok
    }

    private companion object {
        const val ANON = "00000000000000000000000000000000"
    }
}
