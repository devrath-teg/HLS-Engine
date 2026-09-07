package com.istudio.hls_engine.di

import com.istudio.hls_engine.download.api.HlsDownloadEngine
import com.istudio.hls_engine.download.api.HlsOfflineLocator
import com.istudio.hls_engine.download.media3.Media3HlsDownloadEngine
import com.istudio.hls_engine.download.media3.Media3HlsOfflineLocator
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
abstract class DownloadModule {

    @Binds
    @Singleton
    abstract fun bindHlsDownloadEngine(
        impl: Media3HlsDownloadEngine,
    ): HlsDownloadEngine

    @Binds
    @Singleton
    abstract fun bindHlsOfflineLocator(
        impl: Media3HlsOfflineLocator,
    ): HlsOfflineLocator
}
