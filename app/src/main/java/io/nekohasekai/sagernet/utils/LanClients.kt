package io.nekohasekai.sagernet.utils

import android.content.Context
import android.net.wifi.WifiManager
import io.nekohasekai.sagernet.ktx.Logs
import java.io.File
import java.net.Inet4Address
import java.net.InetAddress
import java.net.NetworkInterface
import java.util.Locale

/**
 * Who is using the shared proxy right now, read from the kernel's TCP table, and whether the phone is the hotspot.
 *
 * The core reports traffic per profile, not per client ([ISagerNetServiceCallback.cbTrafficUpdate]), so the only way to
 * see a LAN client is to read /proc/net/tcp ourselves and keep the rows whose local port is the mixed inbound.
 *
 * What that table gives per socket is the remote address, the state, and the two queue depths. The queue depths are
 * not running byte totals, so there is no per-client speed to be had from here: differencing them yields numbers that
 * only look like speeds. What it does give truthfully is how many established sockets each client holds. Getting real
 * per-client byte counts means asking the core, which tracks every inbound connection, rather than reading this file.
 *
 * Three limits on the address list worth knowing:
 *
 *  - TCP only. SOCKS5 UDP associate traffic is not in the table, so a UDP-only client shows no connections.
 *  - The table is host-wide, so the count is every socket on that port rather than what a specific client opened.
 *  - /proc/net/tcp is denied to apps outright on some Android builds, and there is no fallback for it. [sample] then
 *    reports [Table.UNREADABLE] rather than an empty list, so the screen can say the table is out of reach instead of
 *    claiming that nobody is connected.
 */
object LanClients {

    /** One client's state at a point in time. */
    class Entry(
        /** Dotted-quad of the client's address. */
        val address: String,
        /** Established sockets seen on the inbound port. */
        val connections: Int,
    )

    /** What one read of the table found. */
    sealed class Table {
        /** Rows keyed by client address, in the order the table listed them. */
        class Clients(val entries: Map<String, Entry>) : Table()

        /** The table could not be read, which is not the same as there being no clients. */
        object UNREADABLE : Table()
    }

    /**
     * The interface Android puts the hotspot on, or null when there is none up.
     *
     * There is no API that names it - dumpsys calls it "mApInterfaceName" from inside the platform - so it is matched
     * on the naming conventions the hotspot has carried for years: ap0/ap1, swlan0 on Qualcomm, and wlan1 upwards on
     * devices that run the AP on the second radio. wlan0 is excluded because that is the Wi-Fi side, which is Plan B
     * and has an address of its own. A vendor that names it something else reads as no hotspot, which is why the
     * reflective probe above is asked first.
     */
    fun hotspotInterface(): NetworkInterface? {
        val nics = runCatching { NetworkInterface.getNetworkInterfaces() }.getOrNull() ?: return null
        while (nics.hasMoreElements()) {
            val nic = nics.nextElement()
            if (!nic.isUp || nic.isLoopback) continue
            if (!isHotspotName(nic.name)) continue
            if (shareableAddress(nic) != null) return nic
        }
        return null
    }

    private fun isHotspotName(name: String): Boolean =
        name.startsWith("ap") || name.startsWith("swlan") || (name.startsWith("wlan") && name != "wlan0")

    /**
     * The address a client dials while the phone is the hotspot: the hotspot interface's own.
     *
     * AOSP hands out 192.168.43.1 and the phone holds that address, but the subnet is a carrier and vendor choice -
     * the phone this was written on runs its hotspot on 10.32.206.15/24 - so a constant here would be a wrong address
     * to tell someone to type. Reading the interface gets the address this phone actually uses. Null when there is no
     * hotspot interface up.
     */
    fun hotspotAddress(): Inet4Address? = hotspotInterface()?.let { shareableAddress(it) }

    /** The first shareable IPv4 of [nic]; null when it holds none. */
    private fun shareableAddress(nic: NetworkInterface): Inet4Address? {
        val addresses: java.util.Enumeration<InetAddress> = runCatching { nic.inetAddresses }.getOrNull() ?: return null
        while (addresses.hasMoreElements()) {
            val address = addresses.nextElement()
            if (address is Inet4Address && !address.isLoopbackAddress && !address.isLinkLocalAddress) return address
        }
        return null
    }

    /** Whether the phone is handing out addresses to other devices. */
    enum class Hotspot { ON, OFF, UNKNOWN }

    private val WHITESPACE = Regex("\\s+")

    /** Reads the table once. [port] is the mixed inbound's port. */
    fun sample(port: Int): Table {
        val rows = readTable(port) ?: return Table.UNREADABLE
        val result = LinkedHashMap<String, Entry>()
        for ((address, connections) in rows) {
            result[address] = Entry(address, connections)
        }
        return Table.Clients(result)
    }

    /**
     * Whether the hotspot is on.
     *
     * No public API reports it: TetheringManager's callback wants TETHER_PRIVILEGED, and ConnectivityManager has no
     * tethering accessor at all (checked against the platform's own stubs, not from memory). What is left is
     * WifiManager.getWifiApState, hidden but still on the compatibility list and read by reflection, and the presence
     * of the hotspot interface itself, which is a consequence of the hotspot being on rather than a statement about it.
     *
     * [Hotspot.OFF] is only reported when the reflective call actually answered with a state, because a build that
     * blocks it would otherwise read as "the hotspot is off" while it is on - the same class of mistake as reading a
     * theme attribute and calling it what the screen shows. Without an answer the honest answer is [Hotspot.UNKNOWN].
     */
    fun hotspot(context: Context): Hotspot {
        apState(context)?.let { return if (it == AP_STATE_ENABLED) Hotspot.ON else Hotspot.OFF }
        return if (hotspotInterface() != null) Hotspot.ON else Hotspot.UNKNOWN
    }

    /** WifiManager.WIFI_AP_STATE_ENABLED. */
    private const val AP_STATE_ENABLED = 13

    /**
     * WifiManager.getWifiApState(), or null when the method is missing or the call is refused. The result is not cached:
     * a vendor build that refuses it now may not on the next poll, and a wrong "unknown" costs one badge refresh.
     */
    private fun apState(context: Context): Int? = runCatching {
        val wifi = context.applicationContext.getSystemService(WifiManager::class.java) ?: return null
        val method = WifiManager::class.java.getMethod("getWifiApState")
        method.invoke(wifi) as? Int
    }.getOrNull()

    /**
     * /proc/net/tcp is hex, in columns: sl, the local address/port pair, the remote pair, st (01 = ESTABLISHED), the
     * tx and rx queue depths as one hex:hex field, tr:tm->when, retrnsmt, uid, timeout, inode. Only the first four are
     * read. A readable table always begins with its own "sl" header; some policies let the open succeed and the read
     * return nothing, which without that check would be indistinguishable from an idle table. Null when unreadable.
     */
    private fun readTable(port: Int): Map<String, Int>? {
        val text = try {
            File("/proc/net/tcp").readText()
        } catch (e: Throwable) {
            // SecurityException under a policy that denies it, or the file is absent.
            Logs.w("LanClients: /proc/net/tcp unreadable", e)
            return null
        }
        if (!text.startsWith("sl")) {
            Logs.w("LanClients: /proc/net/tcp carries no header row (${text.length} bytes)")
            return null
        }
        val hexPort = String.format(Locale.ROOT, "%04X", port)
        val result = LinkedHashMap<String, Int>()
        for (line in text.lineSequence()) {
            if (!line.startsWith(" ")) continue
            val parts = line.trim().split(WHITESPACE)
            if (parts.size < 4) continue
            val local = parts[1].split(':')
            val remote = parts[2].split(':')
            if (local.size != 2 || remote.size != 2) continue
            if (!local[1].equals(hexPort, ignoreCase = true)) continue
            if (parts[3] != "01") continue
            val address = decodeAddress(remote[0]) ?: continue
            result[address] = (result[address] ?: 0) + 1
        }
        return result
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
            val addresses: java.util.Enumeration<java.net.InetAddress> = nic.getInetAddresses() ?: continue
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