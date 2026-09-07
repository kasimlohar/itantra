package com.itantra.data.transport

import android.content.Context
import android.net.wifi.WifiManager
import android.os.Build
import android.util.Log
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress

data class DiscoveredPeer(
    val name: String,
    val ip: String,
    val port: Int = 4242,
    val lastSeen: Long = System.currentTimeMillis()
)

/**
 * Lightweight Zero-Config UDP Peer Discovery over local Wi-Fi / Hotspot.
 * Broadcasts beacon every 2s on UDP port 4243 so peers automatically discover
 * each other's IP without manual entry.
 */
class PeerDiscoveryManager(
    private val context: Context? = null,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
) {
    companion object {
        const val DISCOVERY_PORT = 4243
        private const val BEACON_PREFIX = "iTantra:BEACON:"
    }

    private val _discoveredPeers = MutableStateFlow<List<DiscoveredPeer>>(emptyList())
    val discoveredPeers: StateFlow<List<DiscoveredPeer>> = _discoveredPeers

    private var broadcastJob: Job? = null
    private var listenJob: Job? = null
    private var multicastLock: WifiManager.MulticastLock? = null

    @Volatile private var isRunning = false

    fun start(onPeerDiscovered: ((DiscoveredPeer) -> Unit)? = null) {
        if (isRunning) return
        isRunning = true

        try {
            val wm = context?.applicationContext?.getSystemService(Context.WIFI_SERVICE) as? WifiManager
            multicastLock = wm?.createMulticastLock("iTantraMulticast")?.apply {
                setReferenceCounted(true)
                acquire()
            }
        } catch (_: Throwable) {}

        // 1. Receiver Job
        listenJob = scope.launch {
            var socket: DatagramSocket? = null
            try {
                val s = DatagramSocket(DISCOVERY_PORT).apply {
                    reuseAddress = true
                    broadcast = true
                }
                socket = s
                val buffer = ByteArray(512)
                val packet = DatagramPacket(buffer, buffer.size)

                while (isActive && isRunning) {
                    try {
                        s.receive(packet)
                        val text = String(packet.data, packet.offset, packet.length).trim()
                        if (text.startsWith(BEACON_PREFIX)) {
                            val parts = text.removePrefix(BEACON_PREFIX).split(":")
                            if (parts.size >= 3) {
                                val name = parts[0]
                                val ip = parts[1]
                                val port = parts[2].toIntOrNull() ?: 4242
                                val myIp = NetworkUtils.getLocalIpv4Address()

                                if (ip != myIp && ip != "127.0.0.1") {
                                    val peer = DiscoveredPeer(name = name, ip = ip, port = port)
                                    val current = _discoveredPeers.value.filter { it.ip != ip }
                                    _discoveredPeers.value = current + peer
                                    Log.d("iTantra", "Peer discovered via UDP beacon: $name ($ip:$port)")
                                    onPeerDiscovered?.invoke(peer)
                                }
                            }
                        }
                    } catch (_: Throwable) {}
                }
            } catch (e: Throwable) {
                Log.w("iTantra", "UDP discovery listen error", e)
            } finally {
                try { socket?.close() } catch (_: Throwable) {}
            }
        }

        // 2. Broadcaster Job
        broadcastJob = scope.launch {
            val deviceName = try { Build.MODEL ?: "Android" } catch (_: Throwable) { "Android" }
            var socket: DatagramSocket? = null

            try {
                val s = DatagramSocket().apply { broadcast = true }
                socket = s
                val targetAddr = InetAddress.getByName("255.255.255.255")

                while (isActive && isRunning) {
                    val myIp = NetworkUtils.getLocalIpv4Address()
                    if (myIp != "127.0.0.1") {
                        val message = "$BEACON_PREFIX$deviceName:$myIp:4242"
                        val bytes = message.toByteArray()
                        val targets = NetworkUtils.getBroadcastAddresses()
                        for (targetAddr in targets) {
                            try {
                                val packet = DatagramPacket(bytes, bytes.size, targetAddr, DISCOVERY_PORT)
                                s.send(packet)
                            } catch (_: Throwable) {}
                        }
                    }
                    delay(2000L)
                }
            } catch (e: Throwable) {
                Log.w("iTantra", "UDP discovery broadcast error", e)
            } finally {
                try { socket?.close() } catch (_: Throwable) {}
            }
        }
    }

    fun stop() {
        isRunning = false
        broadcastJob?.cancel()
        listenJob?.cancel()
        broadcastJob = null
        listenJob = null
        try {
            if (multicastLock?.isHeld == true) {
                multicastLock?.release()
            }
        } catch (_: Throwable) {}
        multicastLock = null
    }
}
