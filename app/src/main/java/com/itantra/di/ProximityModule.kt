package com.itantra.di

import android.bluetooth.BluetoothAdapter
import android.content.Context
import com.itantra.data.audio.SirenPlayer
import com.itantra.data.perf.PerformanceMonitor
import com.itantra.data.proximity.BleRssiScanner
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * Hilt module for proximity, audio, and performance utilities added in the
 * Find My Phone / Siren Locate / Performance HUD feature set.
 *
 * BluetoothAdapter is nullable so the graph doesn't crash on devices that have
 * no Bluetooth hardware (emulators, some tablets). Downstream consumers must
 * null-check before use.
 */
@Module
@InstallIn(SingletonComponent::class)
object ProximityModule {

    @Provides
    @Singleton
    fun provideBluetoothAdapter(): BluetoothAdapter? =
        try {
            @Suppress("DEPRECATION")
            BluetoothAdapter.getDefaultAdapter()
        } catch (_: Throwable) {
            null
        }

    @Provides
    @Singleton
    fun provideBleRssiScanner(adapter: BluetoothAdapter?): BleRssiScanner? =
        adapter?.let { BleRssiScanner(it) }

    @Provides
    @Singleton
    fun provideSirenPlayer(): SirenPlayer = SirenPlayer()

    @Provides
    @Singleton
    fun providePerformanceMonitor(@ApplicationContext ctx: Context): PerformanceMonitor =
        PerformanceMonitor(ctx)
}
