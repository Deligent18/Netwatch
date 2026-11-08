package com.example.netwatch.scan

import android.content.Context
import android.net.ConnectivityManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.io.File
import java.net.ConnectException
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import kotlin.math.max

data class Found(val ip: String, val mac: String?, val hostname: String?)

class NetworkScanner(private val context: Context) {

    private val probePorts = intArrayOf(80, 443, 445, 22, 53, 8080, 62078, 5353)

    /** Returns (own IPv4 as Int, prefix length) of the active network, or null if offline. */
    private fun localSubnet(): Pair<Int, Int>? {
        val cm = context.getSystemService(ConnectivityManager::class.java) ?: return null
        val network = cm.activeNetwork ?: return null
        val lp = cm.getLinkProperties(network) ?: return null
        val la = lp.linkAddresses.firstOrNull { it.address is Inet4Address } ?: return null
        return toInt(la.address.address) to la.prefixLength
    }

    suspend fun scan(): List<Found> = coroutineScope {
        val (own, prefix) = localSubnet() ?: return@coroutineScope emptyList()
        // Cap at /22 (1022 hosts) so a huge subnet doesn't take minutes.
        val p = max(prefix, 22)
        val mask = if (p == 0) 0 else -1 shl (32 - p)
        val network = own and mask
        val broadcast = network or mask.inv()

        val sem = Semaphore(96)
        val alive = ((network + 1) until broadcast)
            .filter { it != own }
            .map { addr ->
                async(Dispatchers.IO) { sem.withPermit { if (probe(toIp(addr))) toIp(addr) else null } }
            }
            .awaitAll()
            .filterNotNull()

        // Probing populates the ARP cache; read it back (best effort, restricted on Android 10+).
        val arp = readArp()
        val ips = (alive + arp.keys.filter { inSubnet(it, network, mask) }).distinct()

        ips.map { ip ->
            async(Dispatchers.IO) { Found(ip, arp[ip], reverseDns(ip)) }
        }.awaitAll()
    }

    /** A host is "alive" if it answers ping/echo or a TCP port answers/refuses quickly. */
    private fun probe(ip: String): Boolean {
        try {
            if (InetAddress.getByName(ip).isReachable(400)) return true
        } catch (_: Exception) {
        }
        for (port in probePorts) {
            try {
                Socket().use { it.connect(InetSocketAddress(ip, port), 250) }
                return true
            } catch (e: ConnectException) {
                // "Connection refused" means a live host actively rejected us.
                if (e.message?.contains("refused", ignoreCase = true) == true) return true
            } catch (_: Exception) {
            }
        }
        return false
    }

    private fun reverseDns(ip: String): String? = try {
        val name = InetAddress.getByName(ip).canonicalHostName
        if (name == ip) null else name
    } catch (_: Exception) {
        null
    }

    private fun readArp(): Map<String, String> = try {
        File("/proc/net/arp").readLines().drop(1).mapNotNull { line ->
            val c = line.trim().split(Regex("\\s+"))
            if (c.size >= 4 && c[2] != "0x0" && c[3] != "00:00:00:00:00:00") c[0] to c[3].lowercase() else null
        }.toMap()
    } catch (_: Exception) {
        emptyMap()
    }

    private fun inSubnet(ip: String, network: Int, mask: Int): Boolean = try {
        (toInt(InetAddress.getByName(ip).address) and mask) == network
    } catch (_: Exception) {
        false
    }

    private fun toInt(b: ByteArray): Int =
        ((b[0].toInt() and 0xff) shl 24) or ((b[1].toInt() and 0xff) shl 16) or
            ((b[2].toInt() and 0xff) shl 8) or (b[3].toInt() and 0xff)

    private fun toIp(i: Int) = "${i ushr 24 and 0xff}.${i ushr 16 and 0xff}.${i ushr 8 and 0xff}.${i and 0xff}"
}

object PortScanner {
    val commonPorts = listOf(21, 22, 23, 25, 53, 80, 110, 139, 143, 443, 445, 554, 993, 1883, 3389, 5900, 8080, 8443, 9100)

    suspend fun scan(ip: String, ports: List<Int> = commonPorts): List<Int> = coroutineScope {
        val sem = Semaphore(32)
        ports.map { port ->
            async(Dispatchers.IO) {
                sem.withPermit {
                    try {
                        Socket().use { it.connect(InetSocketAddress(ip, port), 600) }
                        port
                    } catch (_: Exception) {
                        null
                    }
                }
            }
        }.awaitAll().filterNotNull().sorted()
    }
}
