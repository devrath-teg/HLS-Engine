package com.istudio.hls_engine.download.media3

import android.content.Context
import androidx.annotation.OptIn
import androidx.media3.common.MediaItem
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.exoplayer.offline.Download
import com.istudio.hls_engine.download.api.HlsDownloadEngine
import com.istudio.hls_engine.download.api.HlsOfflineLocator
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * PlayerKit-facing offline bridge. Depends on [HlsDownloadEngine] for identity /
 * completion checks and on [DownloadComponents] for the isolated download cache.
 */
@OptIn(UnstableApi::class)
@Singleton
class Media3HlsOfflineLocator @Inject constructor(
    @ApplicationContext context: Context,
    private val engine: HlsDownloadEngine,
) : HlsOfflineLocator {

    private val appContext = context.applicationContext

    override fun isDownloaded(contentId: String): Boolean = engine.isDownloaded(contentId)

    override fun mediaItemForOffline(contentId: String): MediaItem? {
        val request = engine.getDownloadRequest(contentId) ?: return null
        val download = DownloadComponents.getDownloadManager(appContext)
            .downloadIndex
            .getDownload(contentId)
        if (download?.state != Download.STATE_COMPLETED) return null
        return request.toMediaItem()
    }

    override fun readOnlyCacheDataSourceFactory(): CacheDataSource.Factory =
        DownloadComponents.readOnlyCacheDataSourceFactory(appContext)

    override fun remoteDataSourceFactory(): DataSource.Factory =
        DownloadComponents.remoteDataSourceFactory(appContext)
}
