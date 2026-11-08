package com.example.netwatch.data

import android.content.Context
import com.example.netwatch.router.RouterSettings
import com.example.netwatch.scan.Found
import com.example.netwatch.scan.NetworkScanner
import kotlinx.coroutines.flow.Flow

class MonitorRepository(context: Context) {
    private val dao = AppDb.get(context).deviceDao()
    private val scanner = NetworkScanner(context)
    val settings = RouterSettings(context)

    val devices: Flow<List<Device>> = dao.observeAll()

    /**
     * Scans the network, updates the database and returns devices seen for the first time
     * that are not trusted. The very first scan is a silent baseline (returns empty).
     */
    suspend fun scan(): List<Device> {
        val now = System.currentTimeMillis()
        val existing = dao.getAll()
        val firstScan = existing.isEmpty()

        // Router data (optional): supplies MACs on Android 11+, where ARP is unreadable,
        // and finds sleeping Wi-Fi devices that ignore probes.
        val router = settings.client()
        val leases = runCatching { router?.leases() }.getOrNull().orEmpty()
        val wifi = runCatching { router?.wifiClients() }.getOrNull().orEmpty()
        val leaseByIp = leases.associateBy { it.ip }
        val leaseByMac = leases.associateBy { it.mac }

        val entries = scanner.scan().map { f ->
            val lease = leaseByIp[f.ip]
            Found(f.ip, f.mac ?: lease?.mac, f.hostname ?: lease?.hostname)
        }.toMutableList()
        val knownMacs = entries.mapNotNull { it.mac }.toSet()
        wifi.filter { it.mac !in knownMacs }.forEach { w ->
            val lease = leaseByMac[w.mac]
            entries += Found(lease?.ip ?: "unknown", w.mac, lease?.hostname)
        }

        val byKey = existing.associateBy { it.key }.toMutableMap()
        val seen = mutableSetOf<String>()
        val fresh = mutableListOf<Device>()

        for (e in entries) {
            val key = e.mac ?: "ip:${e.ip}"
            var prev = byKey[key]
            if (prev == null && e.mac != null) {
                // We now know the MAC of a device previously tracked by IP only: migrate it.
                byKey["ip:${e.ip}"]?.let { old ->
                    dao.delete(old)
                    byKey.remove(old.key)
                    prev = old.copy(key = key, mac = e.mac)
                }
            }
            val p = prev
            val device = (p ?: Device(key = key, ip = e.ip, firstSeen = now, lastSeen = now)).copy(
                ip = e.ip,
                mac = e.mac ?: p?.mac,
                hostname = e.hostname ?: p?.hostname,
                online = true,
                lastSeen = now,
            )
            if (p == null) fresh += device
            dao.upsert(device)
            seen += key
        }

        byKey.values.filter { it.key !in seen && it.online }.forEach { dao.upsert(it.copy(online = false)) }

        return if (firstScan) emptyList() else fresh.filter { !it.trusted }
    }

    suspend fun setTrusted(key: String, trusted: Boolean) {
        dao.get(key)?.let { dao.upsert(it.copy(trusted = trusted)) }
    }

    suspend fun rename(key: String, name: String) {
        dao.get(key)?.let { dao.upsert(it.copy(name = name.trim().ifBlank { null })) }
    }

    /** Kicks a Wi-Fi device via the router. Returns an error message, or null on success. */
    suspend fun disconnect(device: Device): String? {
        val mac = device.mac ?: return "MAC address unknown for this device."
        val router = settings.client() ?: return "Set up your router in Settings first."
        return try {
            if (router.kick(mac)) {
                dao.upsert(device.copy(blocked = true, online = false))
                null
            } else {
                "Router did not accept the request (is it a Wi-Fi client of this router?)."
            }
        } catch (e: Exception) {
            "Router error: ${e.message}"
        }
    }

    suspend fun trafficFor(mac: String): Pair<Long, Long>? {
        val c = runCatching { settings.client()?.wifiClients() }.getOrNull()?.firstOrNull { it.mac == mac }
        return if (c?.rxBytes != null && c.txBytes != null) c.rxBytes to c.txBytes else null
    }
}
