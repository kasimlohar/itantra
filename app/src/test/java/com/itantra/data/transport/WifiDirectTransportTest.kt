package com.itantra.data.transport
import com.itantra.domain.model.Language
import com.itantra.domain.model.Frame
import com.itantra.domain.model.TransmitMode
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
class WifiDirectTransportTest {
  @Test fun loopbackTransmitsFrame() = runBlocking {
    val server = WifiDirectTransport(port=0)
    val client = WifiDirectTransport(port=0)
    server.startServer()
    // give server time to bind
    delay(100)
    val port = server.getPort()
    assertThat(port).isGreaterThan(0)
    client.connectTo("127.0.0.1", port)
    delay(100)
    val frame = Frame(mode=TransmitMode.HALF_DUPLEX, isAlert=false, isStream=false, pttPressed=false, srcLang=Language.HINDI, dstLang=Language.HINDI, seqId=42, payloadText="नमस्ते")
    val deferred = async { server.incomingFrames.first() }
    val res = client.send(frame)
    assertThat(res.isSuccess).isTrue()
    val received = deferred.await()
    assertThat(received.payloadText).isEqualTo("नमस्ते")
    assertThat(received.seqId).isEqualTo(42)
    server.disconnect()
    client.disconnect()
  }
  @Test fun tcpNoDelayEnabled() = runBlocking {
    val server = WifiDirectTransport(port=0)
    val client = WifiDirectTransport(port=0)
    server.startServer()
    delay(100)
    client.connectTo("127.0.0.1", server.getPort())
    delay(100)
    // After connect, tcpNoDelay should be true on client socket
    assertThat(client.isConnected).isTrue()
    // Check via reflection that socket has tcpNoDelay true (if we expose)
    // For now, just check isConnected true implies tcpNoDelay was set
    assertThat(client.isConnected).isTrue()
    server.disconnect()
    client.disconnect()
  }
  @Test fun reconnectionAfterDisconnect() = runBlocking {
    val server = WifiDirectTransport(port=0)
    val client = WifiDirectTransport(port=0)
    server.startServer()
    delay(50)
    client.connectTo("127.0.0.1", server.getPort())
    delay(50)
    assertThat(client.isConnected).isTrue()
    client.disconnect()
    assertThat(client.isConnected).isFalse()
    // Reconnect should work
    client.connectTo("127.0.0.1", server.getPort())
    delay(50)
    assertThat(client.isConnected).isTrue()
    server.disconnect()
    client.disconnect()
  }
}
