package com.itantra.di

import android.content.Context
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import com.itantra.data.asr.SherpaAsrEngine
import com.itantra.data.audio.AlertAudioManager
import com.itantra.data.audio.AndroidAlertAudioManager
import com.itantra.data.audio.AndroidVoiceTransceiver
import com.itantra.data.audio.FakeVoiceTransceiver
import com.itantra.data.audio.VoiceTransceiver
import com.itantra.data.transport.TransportManager
import com.itantra.data.transport.WifiDirectTransport
import com.itantra.data.transport.BluetoothTransport
import com.itantra.data.ptt.PttStateMachine
import com.itantra.data.router.PriorityRouter
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object AppModule {
  @Provides @Singleton
  fun provideAlertAudioManager(@ApplicationContext ctx: Context): AlertAudioManager {
    return if (isHost()) FakeAlertAudioManager() else AndroidAlertAudioManager(ctx)
  }

  @Provides @Singleton
  fun provideSherpaAsrEngine(@ApplicationContext ctx: Context): SherpaAsrEngine {
    return SherpaAsrEngine(context = ctx)
  }

  @Provides @Singleton
  fun provideVoiceTransceiver(
    @ApplicationContext ctx: Context,
    sherpaAsr: SherpaAsrEngine
  ): VoiceTransceiver {
    return if (isHost()) FakeVoiceTransceiver() else AndroidVoiceTransceiver(ctx, sherpaAsr)
  }

  @Provides @Singleton
  fun provideWifiDirectTransport(): WifiDirectTransport = WifiDirectTransport()
  @Provides @Singleton
  fun provideBluetoothTransport(): BluetoothTransport = BluetoothTransport()
  @Provides @Singleton
  fun providePttStateMachine(): PttStateMachine = PttStateMachine()
  @Provides @Singleton
  fun providePriorityRouter(): PriorityRouter = PriorityRouter()
  @Provides @Singleton
  fun provideTransportManager(
    wifi: WifiDirectTransport,
    bt: BluetoothTransport,
    router: PriorityRouter,
    ptt: PttStateMachine
  ): TransportManager = TransportManager(wifi, bt, router, ptt)

  private fun isHost(): Boolean = try { System.getProperty("java.vm.name") != "Dalvik" } catch (_: Exception) { true }
}

// Host fallback for AlertAudioManager when not on Dalvik
class FakeAlertAudioManager : AlertAudioManager {
  override fun acquireAlarmFocus(): Boolean = true
  override fun vibrate(pattern: LongArray) {}
  override fun release() {}
}
