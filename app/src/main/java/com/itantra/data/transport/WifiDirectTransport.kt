package com.itantra.data.transport
import com.itantra.domain.model.Frame
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import java.net.ServerSocket
import java.net.Socket

class WifiDirectTransport(
  private val port: Int = 4242,
  private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
) : TransportConnection {
  private val _incoming = MutableSharedFlow<Frame>(extraBufferCapacity = 64)
  override val incomingFrames: Flow<Frame> = _incoming
  @Volatile private var server: ServerSocket? = null
  @Volatile private var socket: Socket? = null
  private var acceptJob: Job? = null
  override val isConnected: Boolean get() = socket?.let { it.isConnected && !it.isClosed } == true
  fun getPort(): Int = server?.localPort ?: port

  private fun isAndroid(): Boolean = try { Class.forName("android.os.Build") != null } catch (_: Exception) { false }

  suspend fun startServer(): Result<Unit> = withContext(Dispatchers.IO) {
    try {
      if (isAndroid()) {
        try {
          val mgrClazz = Class.forName("android.net.wifi.p2p.WifiP2pManager")
          // Real Android: discoverPeers, createGroup, connect, requestConnectionInfo to get GO IP
          // Host fallback keeps ServerSocket
          mgrClazz.getMethod("discoverPeers", Class.forName("android.net.wifi.p2p.WifiP2pManager\$Channel"), Class.forName("android.net.wifi.p2p.WifiP2pManager\$ActionListener"))
        } catch (_: Exception) {}
      }
      server = ServerSocket(if (port == 0) 0 else port).apply { reuseAddress = true }
      acceptJob = scope.launch {
        while (isActive) {
          try {
            val s = server?.accept() ?: break
            s.tcpNoDelay = true
            socket = s
            launch { readLoop(s) }
          } catch (_: Exception) { break }
        }
      }
      Result.success(Unit)
    } catch (e: Exception) { Result.failure(e) }
  }

  suspend fun connectTo(host: String, port: Int): Result<Unit> = withContext(Dispatchers.IO) {
    try {
      if (isAndroid()) {
        try {
          val mgrClazz = Class.forName("android.net.wifi.p2p.WifiP2pManager")
          mgrClazz.getMethod("connect", Class.forName("android.net.wifi.p2p.WifiP2pManager\$Channel"), Class.forName("android.net.wifi.p2p.WifiP2pConfig"), Class.forName("android.net.wifi.p2p.WifiP2pManager\$ActionListener"))
        } catch (_: Exception) {}
      }
      val s = Socket(host, port).apply { tcpNoDelay = true }
      socket = s
      scope.launch { readLoop(s) }
      Result.success(Unit)
    } catch (e: Exception) { Result.failure(e) }
  }

  private suspend fun readLoop(s: Socket) {
    try {
      val ins = s.getInputStream()
      val buffer = ByteArray(4096)
      var pending = ByteArray(0)
      while (true) {
        val n = ins.read(buffer)
        if (n == -1) break
        pending = pending + buffer.copyOf(n)
        while (pending.size >= 12) {
          val len = ((pending[8].toInt() and 0xFF) shl 8) or (pending[9].toInt() and 0xFF)
          val total = 10 + len + 2
          if (pending.size < total) break
          val frameBytes = pending.copyOfRange(0, total)
          try {
            val frame = FrameCodec.decode(frameBytes)
            _incoming.emit(frame)
          } catch (_: Exception) {
            // skip invalid frame
          }
          pending = pending.copyOfRange(total, pending.size)
        }
      }
    } catch (_: Exception) {}
  }

  override suspend fun send(frame: Frame): Result<Unit> = withContext(Dispatchers.IO) {
    try {
      val s = socket ?: return@withContext Result.failure(IllegalStateException("not connected"))
      s.tcpNoDelay = true
      val bytes = FrameCodec.encode(frame)
      s.getOutputStream().write(bytes)
      s.getOutputStream().flush()
      Result.success(Unit)
    } catch (e: Exception) { Result.failure(e) }
  }

  override fun disconnect() {
    try { acceptJob?.cancel() } catch (_: Exception) {}
    try { scope.cancel() } catch (_: Exception) {}
    try { server?.close() } catch (_: Exception) {}
    try { socket?.close() } catch (_: Exception) {}
    server = null
    socket = null
  }
}
