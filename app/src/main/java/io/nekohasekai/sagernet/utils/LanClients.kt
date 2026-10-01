package io.nekohasekai.sagernet.utils

import java.io.File
import java.net.Inet4Address
import java.net.InetAddress
import java.net.NetworkInterface
import java.util.Locale

/**
 * Who is using the shared proxy right now, read from the kernel's TCP table.
 *
 * The core reports traffic per profile, not per client ([ISagerNetServiceCallback.cbTrafficUpdate]), so the only way to
 * attribute bytes to a LAN client is to read /proc/net/tcp ourselves and keep the rows whose local port is the mixed
 * inbound. That has three limits worth knowing:
 *
 *  - TCP only. SOCKS5 UDP associate traffic is not in the table, so a UDP-only client shows no connections.
 *  - The table is host-wide, so the count is every socket on that port rather than what a specific client opened.
 *  - /proc/net/tcp is unreadable for some SELinux policies, in which case [sample] returns null and the caller shows
 *    the device list without counters rather than failing.
 *
 * Speeds come from differencing two samples, so [rate] is only meaningful once two [sample] calls have been made.
 */
object LanClients {

    /** One client's state at a point in time. */
    class Entry(
        /** Dotted-quad or the scope-6 form for link-local v6. */
        val address: String,
        /** Sockets seen on the inbound port. */
        val connections: Int,
        /** Bytes since the previous sample; null on the first one. */
        val up: Long?,
        val down: Long?,
    )

    /**
     * Reads the table once. [port] is the mixed inbound's port. Returns null when the table cannot be read, which is
     * the caller's signal to drop the counters.
     */
    fun sample(port: Int): Map<String, Entry>? {
        val table = readTable(port) ?: return null
        // "rx" is what the client sent us (upload for them), "tx" is what we sent back (download).
        val previous = lastSample
        lastSample = table
        val result = LinkedHashMap<String, Entry>()
        for ((address, counters) in table) {
            val before = previous?.get(address)
            result[address] = Entry(
                address = address,
                connections = counters.connections,
                up = counters.rx?.let { now -> before?.let { now - it.rx } },
                down = counters.tx?.let { now -> before?.let { now - it.tx } },
            )
        }
        return result
    }

    private class Counter(var connections: Int = 0, var rx: Long? = null, var tx: Long? = null)

    private var lastSample: Map<String, Counter>? = null

    fun reset() {
        lastSample = null
    }

    /**
     * /proc/net/tcp is hex: the local address/port pair, the remote pair, then st (01 = ESTABLISHED), tx and rx queue
     * sizes as hex. Rows are keyed by the remote address so a client's sockets collapse into one entry.
     */
    private fun readTable(port: Int): Map<String, Counter>? {
        val lines = try {
            File("/proc/net/tcp").useLines { it.filter { line -> line.startsWith("sl") || line.contains("  01 ") }.toList() }
        } catch (e: Throwable) {
            // SecurityException on a locked-down SELinux policy, or the file is absent.
            null
        } ?: return null
        val hexPort = String.format(Locale.ROOT, "%04X", port)
        val result = LinkedHashMap<String, Counter>()
        for (line in lines) {
            val parts = line.trim().split(Regex("\\s+"))
            if (parts.size < 10) continue
            val local = parts[1].split(':')
            val remote = parts[2].split(':')
            if (local.size != 2 || remote.size != 2) continue
            if (!local[1].equals(hexPort, ignoreCase = true)) continue
            if (parts[3] != "01") continue
            val address = decodeAddress(remote[0]) ?: continue
            val counter = result.getOrPut(address) { Counter() }
            counter.connections++
            counter.tx = parseHex(parts[9])
            counter.rx = parseHex(parts[10])
        }
        return result
    }

    private fun parseHex(value: String): Long? = try {
        value.toLong(16)
    } catch (e: NumberFormatException) {
        null
    }

    /** /proc stores the address as little-endian hex; loopback and any-multicast are of no use to a client. */
    private fun decodeAddress(hex: String): String? {
        if (hex.length != 8) return null
        val bytes = ByteArray(4)
        for (i in 0 until 4) {
            val pair = hex.substring(i * 2, i * 2 + 2)
            bytes[3 - i] = pair.toInt(16).toByte()
        }
        val address = InetAddress.getByAddress(bytes) as? Inet4Address ?: return null
        if (address.isAnyLocalAddress || address.isLoopbackAddress || address.isMulticastAddress) return null
        return address.hostAddress
    }

    /**
     * Addresses a client could dial. Sorted so the list does not jump around between refreshes, and excludes the
     * interfaces that cannot be reached from another device.
     */
    fun shareableAddresses(): List<Inet4Address> {
        val result = LinkedHashSet<Inet4Address>()
        val nics = try {
            NetworkInterface.getNetworkInterfaces()
        } catch (e: Throwable) {
            return emptyList()
        }
        // getNetworkInterfaces and getInetAddresses both hand back Enumerations, walked explicitly rather than through
        // the for-loop extension.
        while (nics.hasMoreElements()) {
            val nic = nics.nextElement()
            if (!nic.isUp || nic.isLoopback || nic.isVirtual) continue
            val addresses = nic.inetAddresses ?: continue
            while (addresses.hasMoreElements()) {
                val address = addresses.nextElement()
                if (address !is Inet4Address) continue
                if (address.isLoopbackAddress || address.isLinkLocalAddress) continue
                result.add(address)
            }
        }
        return result.sortedBy { it.hostAddress }
    }
}