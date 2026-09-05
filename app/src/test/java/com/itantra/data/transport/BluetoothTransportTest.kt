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
class BluetoothTransportTest {
  @Test fun loopbackTransmitsFrame() = runBlocking {
    withTimeout(10000) {
      val server=BluetoothTransport(port=0)
      val client=BluetoothTransport(port=0)
      try {
        server.startServer()
        delay(200)
        val port = server.getPort()
        assertThat(port).isGreaterThan(0)
        client.connectTo("127.0.0.1", port)
        delay(200)
        val f=Frame(mode=TransmitMode.HALF_DUPLEX, isAlert=false, isStream=false, pttPressed=false, srcLang=Language.HINDI, dstLang=Language.HINDI, seqId=7, payloadText="bluetooth hi")
        val d=async { server.incomingFrames.first() }
        delay(100)
        val res = client.send(f)
        assertThat(res.isSuccess).isTrue()
        assertThat(d.await().payloadText).isEqualTo("bluetooth hi")
      } finally {
        server.disconnect()
        client.disconnect()
      }
    }
  }
  @Test fun sppUuidIsCorrect() {
    assertThat(BluetoothTransport.SPP_UUID.toString().uppercase()).isEqualTo("00001101-0000-1000-8000-00805F9B34FB")
  }
}
