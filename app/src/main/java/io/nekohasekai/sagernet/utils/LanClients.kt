package io.nekohasekai.sagernet.utils

import android.content.Context
import android.net.wifi.WifiManager
import java.net.Inet4Address
import java.net.InetAddress
import java.net.NetworkInterface

/**
 * What the LAN screen needs to know about the phone's interfaces: whether the hotspot is on, what address a client
 * dials to reach it, and what address the Wi-Fi side holds.
 *
 * This used to also answer "who is connected", by reading /proc/net/tcp. It cannot: the file carries
 * proc_net_tcp_udp and an enforcing policy refuses it to apps, and /proc/net/arp, /proc/net/dev and
 * /proc/net/route are refused the same way. There is no public API behind them either. That question is why
 * the client list was taken off the screen rather than relabelled - see LanShareActivity.
 *
 * What is left has no API either, but it has a fallback that works: WifiManager.getWifiApState is hidden yet
 * still on the compatibility list, and the hotspot interface shows up when the hotspot is on. Nothing else in
 * the system will say whether the hotspot is on.
 */
object LanClients {

    /** Whether the phone is handing out addresses to other devices. */
    enum class Hotspot { ON, OFF, UNKNOWN }

    /** WifiManager.WIFI_AP_STATE_ENABLED. */
    private const val AP_STATE_ENABLED = 13

    /** Android's default hotspot gateway. Kept only as documentation: no code path should dial it (see [hotspotAddress]). */
    const val HOTSPOT_GATEWAY = "192.168.43.1"

    /**
     * The three answers, from one walk of the interfaces.
     *
     * They used to be asked separately and each asked the platform on its own: getNetworkInterfaces() is a kernel
     * round trip rather than a cached getter, so four of them a poll cost 0.53% of a core for a screen the user
     * might have walked away from.
     */
    class Snapshot(
        val hotspot: Hotspot,
        /** The hotspot interface's own address; null when there is no hotspot interface up. */
        val hotspotAddress: Inet4Address?,
        /** The address a client on the same router dials; null when no interface holds a shareable address. */
        val wlanAddress: String?,
    )

    /** One walk of the interfaces, answering all three questions at once. */
    fun snapshot(context: Context): Snapshot {
        // Interface name -> its shareable IPv4 addresses, in the order the platform listed them. Loopback and
        // link-local are left out: neither is an address another device can reach this phone on.
        val byName = LinkedHashMap<String, MutableList<Inet4Address>>()
        val nics = runCatching { NetworkInterface.getNetworkInterfaces() }.getOrNull()
        while (nics != null && nics.hasMoreElements()) {
            val nic = nics.nextElement()
            if (!nic.isUp || nic.isLoopback) continue
            val addresses: java.util.Enumeration<InetAddress> = runCatching { nic.inetAddresses }.getOrNull() ?: continue
            while (addresses.hasMoreElements()) {
                val address = addresses.nextElement()
                if (address !is Inet4Address) continue
                if (address.isLoopbackAddress || address.isLinkLocalAddress) continue
                byName.getOrPut(nic.name) { ArrayList() }.add(address)
            }
        }

        val apName = byName.keys.firstOrNull { isHotspotName(it) }
        // "wlan1" upwards is the AP on a second radio rather than the Wi-Fi side, which is why isHotspotName claims
        // those names and this lookup does not.
        val wlanName = byName.keys.firstOrNull { it.startsWith("wlan") && it != "wlan0" }
        val wlan = wlanName?.let { byName[it]?.firstOrNull() }
            // No wlan interface at all: the first address any interface holds, which is what Plan B falls back to.
            ?: byName.values.firstOrNull()?.firstOrNull()

        return Snapshot(
            hotspot = hotspotState(context, apName != null),
            hotspotAddress = apName?.let { byName[it]?.firstOrNull() },
            wlanAddress = wlan?.hostAddress,
        )
    }

    /**
     * Whether the hotspot is on.
     *
     * No public API reports it: TetheringManager's callback wants TETHER_PRIVILEGED, and ConnectivityManager has no
     * tethering accessor at all (checked against the platform's own stubs, not from memory). What is left is
     * WifiManager.getWifiApState, hidden but still on the compatibility list and read by reflection; and the presence
     * of the hotspot interface, which is a consequence of the hotspot being on rather than a statement about it.
     *
     * [Hotspot.OFF] is only reported when the reflective call answered with a state. A build that blocks it would
     * otherwise read as "the hotspot is off" while it is on - the same class of mistake as reading a theme attribute
     * and calling it what the screen shows. Without an answer, and with no hotspot interface either, the honest
     * answer is [Hotspot.UNKNOWN].
     */
    fun hotspot(context: Context): Hotspot = hotspotState(context, hotspotInterface() != null)

    private fun hotspotState(context: Context, interfacePresent: Boolean): Hotspot {
        apState(context)?.let { return if (it == AP_STATE_ENABLED) Hotspot.ON else Hotspot.OFF }
        return if (interfacePresent) Hotspot.ON else Hotspot.UNKNOWN
    }

    /**
     * WifiManager.getWifiApState(), or null when the method is missing or the call is refused. Not cached: a vendor
     * build that refuses it now may not on the next poll, and a wrong "unknown" costs one badge refresh.
     */
    private fun apState(context: Context): Int? = runCatching {
        val wifi = context.applicationContext.getSystemService(WifiManager::class.java) ?: return null
        val method = WifiManager::class.java.getMethod("getWifiApState")
        method.invoke(wifi) as? Int
    }.getOrNull()

    /**
     * The interface Android puts the hotspot on, or null when there is none up.
     *
     * There is no API that names it - dumpsys calls it "mApInterfaceName" from inside the platform - so it is matched
     * on the naming conventions the hotspot has carried for years: ap0/ap1, swlan0 on Qualcomm, and wlan1 upwards on
     * devices that run the AP on the second radio. wlan0 is excluded because that is the Wi-Fi side, which is Plan B
     * and has an address of its own. A vendor that names it something else reads as no hotspot, which is why the
     * reflective probe is asked first.
     */
    fun hotspotInterface(): NetworkInterface? {
        val nics = runCatching { NetworkInterface.getNetworkInterfaces() }.getOrNull() ?: return null
        while (nics.hasMoreElements()) {
            val nic = nics.nextElement()
            if (!nic.isUp || nic.isLoopback) continue
            if (isHotspotName(nic.name) && shareableAddress(nic) != null) return nic
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
     * to tell someone to type. Reading the interface gets the address this phone actually uses, and [HOTSPOT_GATEWAY]
     * is never dialled. Null when there is no hotspot interface up.
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