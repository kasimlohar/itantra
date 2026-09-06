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
  var onConnected: (() -> Unit)? = null
  var onDisconnected: (() -> Unit)? = null
  private val _incoming = MutableSharedFlow<Frame>(extraBufferCapacity = 64)
  override val incomingFrames: Flow<Frame> = _incoming
  @Volatile private var server: ServerSocket? = null
  @Volatile private var socket: Socket? = null
  private var acceptJob: Job? = null
  override val isConnected: Boolean get() = socket?.let { it.isConnected && !it.isClosed } == true
  fun getPort(): Int = server?.localPort ?: port

  private fun isAndroid(): Boolean = try { Class.forName("android.os.Build") != null } catch (_: Exception) { false }

  private fun log(msg: String, err: Throwable? = null) {
    try {
      if (err != null) {
        android.util.Log.e("iTantra", msg, err)
      } else {
        android.util.Log.d("iTantra", msg)
      }
    } catch (_: Throwable) {
      println("[iTantra] $msg ${err ?: ""}")
    }
  }

  suspend fun startServer(): Result<Unit> = withContext(Dispatchers.IO) {
    try {
      if (server != null && server?.isClosed == false) {
        log("startServer: server already running on port $port")
        return@withContext Result.success(Unit)
      }
      log("startServer: starting on port $port")
      if (isAndroid()) {
        try {
          val mgrClazz = Class.forName("android.net.wifi.p2p.WifiP2pManager")
          mgrClazz.getMethod("discoverPeers", Class.forName("android.net.wifi.p2p.WifiP2pManager\$Channel"), Class.forName("android.net.wifi.p2p.WifiP2pManager\$ActionListener"))
        } catch (_: Exception) {}
      }
      server = ServerSocket(if (port == 0) 0 else port).apply { reuseAddress = true }
      log("startServer: server bound to localPort ${server?.localPort}")
      acceptJob = scope.launch {
        while (isActive) {
          try {
            log("acceptJob: waiting for accept()...")
            val s = server?.accept() ?: break
            s.tcpNoDelay = true
            socket = s
            log("acceptJob: accepted socket from ${s.inetAddress?.hostAddress}")
            onConnected?.invoke()
            launch { readLoop(s) }
          } catch (e: Exception) {
            log("acceptJob: accept error", e)
            break
          }
        }
      }
      Result.success(Unit)
    } catch (e: Exception) {
      log("startServer: failed to bind", e)
      Result.failure(e)
    }
  }

  suspend fun connectTo(host: String, port: Int): Result<Unit> = withContext(Dispatchers.IO) {
    try {
      log("connectTo: connecting to $host:$port...")
      if (socket != null && isConnected) {
        log("connectTo: already connected")
        return@withContext Result.success(Unit)
      }
      if (isAndroid()) {
        try {
          val mgrClazz = Class.forName("android.net.wifi.p2p.WifiP2pManager")
          mgrClazz.getMethod("connect", Class.forName("android.net.wifi.p2p.WifiP2pManager\$Channel"), Class.forName("android.net.wifi.p2p.WifiP2pConfig"), Class.forName("android.net.wifi.p2p.WifiP2pManager\$ActionListener"))
        } catch (_: Exception) {}
      }
      val s = Socket(host, port).apply { tcpNoDelay = true }
      socket = s
      log("connectTo: connected successfully to $host:$port")
      onConnected?.invoke()
      scope.launch { readLoop(s) }
      Result.success(Unit)
    } catch (e: Exception) {
      log("connectTo: failed to $host:$port", e)
      Result.failure(e)
    }
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
            log("readLoop: received frame seqId=${frame.seqId} text='${frame.payloadText}' isAlert=${frame.isAlert}")
            _incoming.emit(frame)
          } catch (e: Exception) {
            log("readLoop: error decoding frame", e)
          }
          pending = pending.copyOfRange(total, pending.size)
        }
      }
    } catch (e: Exception) {
      log("readLoop: exit with exception", e)
    } finally {
      log("readLoop: socket disconnected, cleaning up")
      try { s.close() } catch (_: Exception) {}
      if (socket == s) {
        socket = null
        onDisconnected?.invoke()
      }
    }
  }

  override suspend fun send(frame: Frame): Result<Unit> = withContext(Dispatchers.IO) {
    try {
      val s = socket ?: run {
        log("send: failed - socket is null!")
        return@withContext Result.failure(IllegalStateException("not connected"))
      }
      s.tcpNoDelay = true
      val bytes = FrameCodec.encode(frame)
      log("send: writing ${bytes.size} bytes (seqId=${frame.seqId}, text='${frame.payloadText}')")
      s.getOutputStream().write(bytes)
      s.getOutputStream().flush()
      log("send: write and flush successful!")
      Result.success(Unit)
    } catch (e: Exception) {
      log("send: failed with exception", e)
      Result.failure(e)
    }
  }

  override fun disconnect() {
    try { acceptJob?.cancel() } catch (_: Exception) {}
    try { scope.coroutineContext.cancelChildren() } catch (_: Exception) {}
    try { server?.close() } catch (_: Exception) {}
    try { socket?.close() } catch (_: Exception) {}
    server = null
    socket = null
    onDisconnected?.invoke()
  }
}
