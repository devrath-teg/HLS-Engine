package com.istudio.hls_engine.download.api

import androidx.annotation.OptIn
import androidx.media3.common.MediaItem
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.cache.CacheDataSource

/**
 * PlayerKit-facing offline bridge (AT-331 §6.4).
 *
 * PlayerKit should depend on this locator only — not on [HlsDownloadEngine] —
 * so it can read the download cache without owning enqueue/cancel.
 */
interface HlsOfflineLocator {
    fun isDownloaded(contentId: String): Boolean
    fun mediaItemForOffline(contentId: String): MediaItem?

    @OptIn(UnstableApi::class)
    fun readOnlyCacheDataSourceFactory(): CacheDataSource.Factory
    fun remoteDataSourceFactory(): DataSource.Factory
}
