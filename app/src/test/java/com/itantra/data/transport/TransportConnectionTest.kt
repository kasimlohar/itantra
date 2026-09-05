package com.itantra.data.transport
import com.itantra.domain.model.Frame
import com.itantra.domain.model.Language
import com.itantra.domain.model.TransmitMode
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import kotlinx.coroutines.test.runTest
class TransportConnectionTest {
  @Test fun interfaceExists() = runTest {
    val tc: TransportConnection = object: TransportConnection {
      override suspend fun send(frame: Frame): Result<Unit> = Result.success(Unit)
      override val incomingFrames = kotlinx.coroutines.flow.emptyFlow<Frame>()
      override fun disconnect() {}
      override val isConnected = false
    }
    val f = Frame(mode=TransmitMode.HALF_DUPLEX, isAlert=false, isStream=false, pttPressed=false, srcLang=Language.HINDI, dstLang=Language.HINDI, seqId=1, payloadText="hi")
    assertThat(tc.send(f).isSuccess).isTrue()
  }
  @Test fun transportManagerExists() {
    val m = TransportManager(FakeTransport(), FakeTransport())
    assertThat(m.state.value).isEqualTo(TransportState.DISCONNECTED)
  }
  class FakeTransport: TransportConnection {
    override suspend fun send(frame: Frame) = Result.success(Unit)
    override val incomingFrames = kotlinx.coroutines.flow.emptyFlow<Frame>()
    override fun disconnect() {}
    override val isConnected = false
  }
}
