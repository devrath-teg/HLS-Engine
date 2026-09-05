package com.istudio.hls_engine.download.media3

import android.content.Context
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.database.DatabaseProvider
import androidx.media3.database.StandaloneDatabaseProvider
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.datasource.cache.Cache
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.cache.NoOpCacheEvictor
import androidx.media3.datasource.cache.SimpleCache
import androidx.media3.exoplayer.offline.DownloadManager
import java.io.File
import java.util.concurrent.Executor

/**
 * Shared Media3 infrastructure for the HLS engine.
 *
 * Spec: dedicated `hls_download_cache` — never share PlayerKit streaming LRU cache.
 */
@OptIn(UnstableApi::class)
object DownloadComponents {

    const val HLS_CACHE_DIR_NAME = "hls_download_cache"

    @Volatile private var databaseProvider: DatabaseProvider? = null
    @Volatile private var downloadCache: Cache? = null
    @Volatile private var downloadManager: DownloadManager? = null
    @Volatile private var httpDataSourceFactory: DefaultHttpDataSource.Factory? = null

    fun httpDataSourceFactory(context: Context): DefaultHttpDataSource.Factory {
        return httpDataSourceFactory ?: synchronized(this) {
            httpDataSourceFactory ?: DefaultHttpDataSource.Factory()
                .setUserAgent("HLS-Engine")
                .setConnectTimeoutMs(60_000)
                .setReadTimeoutMs(60_000)
                .setAllowCrossProtocolRedirects(true)
                .also { httpDataSourceFactory = it }
        }
    }

    fun cacheDirectory(context: Context): File =
        File(context.applicationContext.filesDir, HLS_CACHE_DIR_NAME).also { it.mkdirs() }

    fun getDownloadCache(context: Context): Cache {
        return downloadCache ?: synchronized(this) {
            downloadCache ?: SimpleCache(
                cacheDirectory(context),
                NoOpCacheEvictor(),
                getDatabaseProvider(context),
            ).also { downloadCache = it }
        }
    }

    fun getDownloadManager(context: Context): DownloadManager {
        return downloadManager ?: synchronized(this) {
            downloadManager ?: createDownloadManager(context.applicationContext).also {
                downloadManager = it
            }
        }
    }

    fun readOnlyCacheDataSourceFactory(context: Context): CacheDataSource.Factory =
        CacheDataSource.Factory()
            .setCache(getDownloadCache(context))
            .setUpstreamDataSourceFactory(httpDataSourceFactory(context))
            .setCacheWriteDataSinkFactory(null)

    fun remoteDataSourceFactory(context: Context): DataSource.Factory =
        httpDataSourceFactory(context)

    private fun getDatabaseProvider(context: Context): DatabaseProvider {
        return databaseProvider ?: synchronized(this) {
            databaseProvider
                ?: StandaloneDatabaseProvider(context.applicationContext).also {
                    databaseProvider = it
                }
        }
    }

    private fun createDownloadManager(context: Context): DownloadManager {
        val executor = Executor { command -> command.run() }
        return DownloadManager(
            context,
            getDatabaseProvider(context),
            getDownloadCache(context),
            httpDataSourceFactory(context),
            executor,
        ).apply {
            maxParallelDownloads = 2
        }
    }
}
