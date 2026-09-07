package com.itantra.data.transport

import android.content.Context
import android.net.wifi.WifiManager
import java.net.Inet4Address
import java.net.NetworkInterface
import java.util.Collections

/**
 * Utility for local IPv4 address resolution, Hotspot gateway detection, and subnet calculation.
 */
object NetworkUtils {

    /**
     * Finds the primary active IPv4 address for this device (Wi-Fi, Hotspot AP, or P2P interface).
     */
    fun getLocalIpv4Address(): String {
        try {
            val interfaces = Collections.list(NetworkInterface.getNetworkInterfaces())
            // Priority order for interfaces
            val sortedInterfaces = interfaces.sortedByDescending { intf ->
                val name = intf.name.lowercase()
                when {
                    name.startsWith("ap") || name.startsWith("softap") || name.startsWith("swlan") -> 4
                    name.startsWith("wlan") -> 3
                    name.startsWith("p2p") -> 2
                    name.startsWith("rndis") || name.startsWith("eth") -> 1
                    else -> 0
                }
            }

            for (intf in sortedInterfaces) {
                if (intf.isLoopback) continue
                val isUp = try { intf.isUp } catch (_: Throwable) { true }
                if (!isUp) continue

                val addrs = Collections.list(intf.inetAddresses)
                for (addr in addrs) {
                    if (!addr.isLoopbackAddress && addr is Inet4Address) {
                        val host = addr.hostAddress ?: continue
                        if (host != "127.0.0.1" && !host.startsWith("169.254.")) {
                            return host
                        }
                    }
                }
            }
        } catch (_: Throwable) {}
        return "127.0.0.1"
    }

    /**
     * Gathers all IPv4 broadcast destinations for current active networks,
     * including subnet-specific broadcast IPs (e.g. 192.168.43.255) and the global 255.255.255.255.
     */
    fun getBroadcastAddresses(): List<java.net.InetAddress> {
        val list = mutableListOf<java.net.InetAddress>()
        try {
            list.add(java.net.InetAddress.getByName("255.255.255.255"))
        } catch (_: Throwable) {}

        try {
            val interfaces = Collections.list(NetworkInterface.getNetworkInterfaces())
            for (intf in interfaces) {
                if (intf.isLoopback) continue
                val isUp = try { intf.isUp } catch (_: Throwable) { true }
                if (!isUp) continue

                for (ia in intf.interfaceAddresses) {
                    val bcast = ia.broadcast
                    if (bcast != null && bcast is Inet4Address) {
                        list.add(bcast)
                    }
                }
            }
        } catch (_: Throwable) {}
        return list.distinct()
    }

    /**
     * Resolves the Wi-Fi DHCP gateway address (which is the Hotspot Host IP when connected as client).
     */
    fun getWifiGatewayIpv4(context: Context?): String? {
        if (context == null) return null
        return try {
            val wm = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
            val gateway = wm?.dhcpInfo?.gateway ?: 0
            if (gateway != 0) {
                String.format(
                    "%d.%d.%d.%d",
                    gateway and 0xff,
                    gateway shr 8 and 0xff,
                    gateway shr 16 and 0xff,
                    gateway shr 24 and 0xff
                )
            } else {
                null
            }
        } catch (_: Throwable) {
            null
        }
    }
}
