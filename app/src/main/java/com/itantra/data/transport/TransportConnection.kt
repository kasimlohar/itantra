package com.itantra.data.transport
import com.itantra.domain.model.Frame
import kotlinx.coroutines.flow.Flow
interface TransportConnection {
  suspend fun send(frame: Frame): Result<Unit>
  val incomingFrames: Flow<Frame>
  fun disconnect()
  val isConnected: Boolean
}
