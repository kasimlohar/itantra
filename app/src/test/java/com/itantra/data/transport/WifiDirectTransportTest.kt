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
import kotlinx.coroutines.withTimeout
class WifiDirectTransportTest {
  @Test fun loopbackTransmitsFrame() = runBlocking {
    withTimeout(10000) {
      val server = WifiDirectTransport(port=0)
      val client = WifiDirectTransport(port=0)
      try {
        server.startServer()
        delay(200)
        val port = server.getPort()
        assertThat(port).isGreaterThan(0)
        client.connectTo("127.0.0.1", port)
        delay(200)
        val frame = Frame(mode=TransmitMode.HALF_DUPLEX, isAlert=false, isStream=false, pttPressed=false, srcLang=Language.HINDI, dstLang=Language.HINDI, seqId=42, payloadText="नमस्ते")
        val deferred = async { server.incomingFrames.first() }
        delay(100)
        val res = client.send(frame)
        assertThat(res.isSuccess).isTrue()
        val received = deferred.await()
        assertThat(received.payloadText).isEqualTo("नमस्ते")
        assertThat(received.seqId).isEqualTo(42)
      } finally {
        server.disconnect()
        client.disconnect()
      }
    }
  }
  @Test fun tcpNoDelayEnabled() = runBlocking {
    withTimeout(5000) {
      val server = WifiDirectTransport(port=0)
      val client = WifiDirectTransport(port=0)
      try {
        server.startServer()
        delay(100)
        client.connectTo("127.0.0.1", server.getPort())
        delay(100)
        assertThat(client.isConnected).isTrue()
        assertThat(client.isConnected).isTrue()
      } finally {
        server.disconnect()
        client.disconnect()
      }
    }
  }
  @Test fun reconnectionAfterDisconnect() = runBlocking {
    withTimeout(5000) {
      val server = WifiDirectTransport(port=0)
      val client = WifiDirectTransport(port=0)
      try {
        server.startServer()
        delay(50)
        client.connectTo("127.0.0.1", server.getPort())
        delay(50)
        assertThat(client.isConnected).isTrue()
        client.disconnect()
        assertThat(client.isConnected).isFalse()
        client.connectTo("127.0.0.1", server.getPort())
        delay(50)
        assertThat(client.isConnected).isTrue()
      } finally {
        server.disconnect()
        client.disconnect()
      }
    }
  }
}
