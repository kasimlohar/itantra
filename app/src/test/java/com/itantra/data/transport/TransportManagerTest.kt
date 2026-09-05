package com.itantra.data.transport
import com.itantra.domain.model.Language
import com.itantra.domain.model.Frame
import com.itantra.domain.model.TransmitMode
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import kotlinx.coroutines.runBlocking
class TransportManagerTest {
  class FailingWifi: TransportConnection {
    override suspend fun send(frame: Frame) = Result.failure<Unit>(Exception("wifi fail"))
    override val incomingFrames = kotlinx.coroutines.flow.emptyFlow<Frame>()
    override fun disconnect() {}
    override val isConnected = false
  }
  class GoodBt: TransportConnection {
    var sent=false
    override suspend fun send(frame: Frame): Result<Unit> { sent=true; return Result.success(Unit) }
    override val incomingFrames = kotlinx.coroutines.flow.emptyFlow<Frame>()
    override fun disconnect() {}
    override val isConnected = true
  }
  @Test fun automaticFallbackToBluetoothWhenWifiFails() = runBlocking {
    val wifi=FailingWifi()
    val bt=GoodBt()
    val mgr=TransportManager(wifi, bt)
    // This should attempt wifi, fail, then fallback to bt and become CONNECTED
    val res = mgr.connect()
    assertThat(res.isSuccess).isTrue()
    assertThat(mgr.state.value).isEqualTo(TransportState.CONNECTED)
    assertThat(mgr.activeConnection).isEqualTo(bt as TransportConnection)
    val f=Frame(mode=TransmitMode.HALF_DUPLEX, isAlert=false, isStream=false, pttPressed=false, srcLang=Language.HINDI, dstLang=Language.HINDI, seqId=1, payloadText="fallback")
    val sendRes = mgr.send(f)
    assertThat(sendRes.isSuccess).isTrue()
    assertThat(bt.sent).isTrue()
  }
  @Test fun disconnectResetsState() = runBlocking {
    val wifi=FailingWifi()
    val bt=GoodBt()
    val mgr=TransportManager(wifi, bt)
    mgr.connect()
    assertThat(mgr.state.value).isEqualTo(TransportState.CONNECTED)
    mgr.disconnect()
    assertThat(mgr.state.value).isEqualTo(TransportState.DISCONNECTED)
  }
}
