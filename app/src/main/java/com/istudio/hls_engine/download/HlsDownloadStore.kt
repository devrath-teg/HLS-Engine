package com.istudio.hls_engine.download

import android.content.Context
import android.net.Uri
import android.os.Handler
import android.os.Looper
import androidx.annotation.OptIn
import androidx.media3.common.MediaItem
import androidx.media3.common.StreamKey
import androidx.media3.common.TrackSelectionParameters
import androidx.media3.common.util.UnstableApi
import androidx.media3.database.StandaloneDatabaseProvider
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.cache.NoOpCacheEvictor
import androidx.media3.datasource.cache.SimpleCache
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.hls.offline.HlsDownloader
import androidx.media3.exoplayer.offline.DownloadHelper
import androidx.media3.exoplayer.offline.DownloadRequest
import java.io.File
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/**
 * Stores HLS downloads under app-private internal storage:
 *
 * `{filesDir}/download/{contentId}/cache/`  — Media3 [SimpleCache]
 * `{filesDir}/download/{contentId}/meta.txt` — playlist URI
 * `{filesDir}/download/{contentId}/request.bin` — [DownloadRequest] (stream keys)
 */
@OptIn(UnstableApi::class)
class HlsDownloadStore(context: Context) {

    private val appContext = context.applicationContext
    private val downloadRoot = File(appContext.filesDir, DOWNLOAD_DIR_NAME)
    private val databaseProvider = StandaloneDatabaseProvider(appContext)
    private val mainHandler = Handler(Looper.getMainLooper())

    /** Longer timeouts + redirects — emulator networks are often slow/flaky. */
    private val httpDataSourceFactory = DefaultHttpDataSource.Factory()
        .setUserAgent("HLS-Engine-Demo")
        .setConnectTimeoutMs(60_000)
        .setReadTimeoutMs(60_000)
        .setAllowCrossProtocolRedirects(true)

    /** One request at a time — parallel segment fetches tend to trip "socket closed". */
    private val downloadExecutor = Executor { command -> command.run() }

    private val caches = ConcurrentHashMap<String, SimpleCache>()
    private val completedDownloads = ConcurrentHashMap.newKeySet<String>()

    init {
        downloadRoot.mkdirs()
        downloadRoot.listFiles()
            ?.filter {
                it.isDirectory &&
                    metaFile(it.name).exists() &&
                    cacheDirectory(it.name).exists() &&
                    requestFile(it.name).exists()
            }
            ?.forEach { completedDownloads.add(it.name) }
    }

    fun downloadDirectory(contentId: String): File =
        File(downloadRoot, contentId).also { it.mkdirs() }

    fun cacheDirectory(contentId: String): File =
        File(downloadDirectory(contentId), CACHE_DIR_NAME).also { it.mkdirs() }

    fun isDownloaded(contentId: String): Boolean = contentId in completedDownloads

    fun savedPlaylistUri(contentId: String): Uri? {
        val meta = metaFile(contentId)
        if (!meta.exists()) return null
        return Uri.parse(meta.readText().trim())
    }

    fun mediaItemForRemote(playlistUri: Uri): MediaItem = MediaItem.fromUri(playlistUri)

    /** Offline playback must reuse the same stream keys that were downloaded. */
    fun mediaItemForOffline(contentId: String, playlistUri: Uri): MediaItem {
        val request = loadDownloadRequest(contentId)
        return request?.toMediaItem() ?: MediaItem.fromUri(playlistUri)
    }

    fun remoteDataSourceFactory(): DataSource.Factory = httpDataSourceFactory

    fun cacheDataSourceFactory(contentId: String, writeToCache: Boolean): CacheDataSource.Factory {
        val factory = CacheDataSource.Factory()
            .setCache(cacheFor(contentId))
            .setUpstreamDataSourceFactory(httpDataSourceFactory)
        return if (writeToCache) {
            factory
        } else {
            factory.setCacheWriteDataSinkFactory(null)
        }
    }

    /**
     * Downloads a **single lowest-bitrate selection** via [DownloadHelper] + [HlsDownloader].
     * Retries on transient socket errors without wiping already-cached segments.
     */
    fun download(
        contentId: String,
        playlistUri: Uri,
        onProgress: (percent: Float) -> Unit = {},
    ) {
        caches.remove(contentId)?.release()
        completedDownloads.remove(contentId)
        downloadDirectory(contentId).deleteRecursively()
        cacheDirectory(contentId)

        val downloadRequest = prepareDownloadRequest(contentId, playlistUri)
        saveDownloadRequest(contentId, downloadRequest)
        metaFile(contentId).writeText(playlistUri.toString())

        val mediaItem = downloadRequest.toMediaItem()
        var lastError: Exception? = null

        repeat(MAX_ATTEMPTS) { attempt ->
            try {
                val downloader = HlsDownloader.Factory(
                    cacheDataSourceFactory(contentId, writeToCache = true),
                )
                    .setExecutor(downloadExecutor)
                    .create(mediaItem)

                downloader.download { _, _, percentDownloaded ->
                    onProgress(percentDownloaded.coerceIn(0f, 100f))
                }

                completedDownloads.add(contentId)
                return
            } catch (e: Exception) {
                lastError = e
                if (attempt == MAX_ATTEMPTS - 1 || !isTransientNetworkError(e)) {
                    throw e
                }
                // Brief backoff, then resume — SimpleCache keeps finished segments.
                Thread.sleep(1_500L * (attempt + 1))
            }
        }

        throw lastError ?: IOException("Download failed")
    }

    fun remove(contentId: String) {
        caches.remove(contentId)?.release()
        completedDownloads.remove(contentId)
        downloadDirectory(contentId).deleteRecursively()
    }

    fun release() {
        caches.values.forEach { it.release() }
        caches.clear()
    }

    private fun prepareDownloadRequest(contentId: String, playlistUri: Uri): DownloadRequest {
        val mediaItem = MediaItem.fromUri(playlistUri)
        val lowestBitrate = TrackSelectionParameters.Builder(appContext)
            .setForceLowestBitrate(true)
            .build()

        val requestRef = AtomicReference<DownloadRequest>()
        val errorRef = AtomicReference<Exception>()
        val done = CountDownLatch(1)

        // DownloadHelper / DefaultTrackSelector must be touched only on the main thread.
        mainHandler.post {
            val helper = DownloadHelper.Factory()
                .setRenderersFactory(DefaultRenderersFactory(appContext))
                .setDataSourceFactory(httpDataSourceFactory)
                .create(mediaItem)

            helper.prepare(
                object : DownloadHelper.Callback {
                    override fun onPrepared(helper: DownloadHelper, tracksInfoAvailable: Boolean) {
                        try {
                            if (tracksInfoAvailable) {
                                for (periodIndex in 0 until helper.periodCount) {
                                    helper.clearTrackSelections(periodIndex)
                                    helper.addAudioLanguagesToSelection()
                                    helper.addTextLanguagesToSelection(
                                        /* selectUndeterminedTextLanguage= */ false,
                                    )
                                    helper.addTrackSelection(periodIndex, lowestBitrate)
                                }
                            }
                            requestRef.set(helper.getDownloadRequest(contentId, /* data= */ null))
                        } catch (e: Exception) {
                            errorRef.set(e)
                        } finally {
                            helper.release()
                            done.countDown()
                        }
                    }

                    override fun onPrepareError(helper: DownloadHelper, e: IOException) {
                        errorRef.set(e)
                        helper.release()
                        done.countDown()
                    }
                },
            )
        }

        if (!done.await(120, TimeUnit.SECONDS)) {
            throw IOException("Timed out preparing HLS download")
        }
        errorRef.get()?.let { throw it }
        return requestRef.get()
            ?: throw IOException("DownloadHelper produced no DownloadRequest")
    }

    private fun saveDownloadRequest(contentId: String, request: DownloadRequest) {
        // Persist stream keys so offline MediaItem matches what was downloaded.
        val lines = buildList {
            add(request.uri.toString())
            request.streamKeys.forEach { key ->
                add("${key.periodIndex},${key.groupIndex},${key.streamIndex}")
            }
        }
        requestFile(contentId).writeText(lines.joinToString("\n"))
    }

    private fun loadDownloadRequest(contentId: String): DownloadRequest? {
        val file = requestFile(contentId)
        if (!file.exists()) return null
        val lines = file.readLines().filter { it.isNotBlank() }
        if (lines.isEmpty()) return null
        val uri = Uri.parse(lines.first())
        val streamKeys = lines.drop(1).map { line ->
            val parts = line.split(",")
            StreamKey(parts[0].toInt(), parts[1].toInt(), parts[2].toInt())
        }
        return DownloadRequest.Builder(contentId, uri)
            .setStreamKeys(streamKeys)
            .build()
    }

    private fun metaFile(contentId: String): File =
        File(downloadDirectory(contentId), META_FILE_NAME)

    private fun requestFile(contentId: String): File =
        File(downloadDirectory(contentId), REQUEST_FILE_NAME)

    private fun cacheFor(contentId: String): SimpleCache {
        return caches.getOrPut(contentId) {
            SimpleCache(
                cacheDirectory(contentId),
                NoOpCacheEvictor(),
                databaseProvider,
            )
        }
    }

    private fun isTransientNetworkError(e: Exception): Boolean {
        val messages = generateSequence(e as Throwable) { it.cause }
            .mapNotNull { it.message?.lowercase() }
            .joinToString(" ")
        return messages.contains("socket") ||
            messages.contains("connection") ||
            messages.contains("timeout") ||
            messages.contains("reset") ||
            messages.contains("unreachable") ||
            e is IOException
    }

    companion object {
        const val DOWNLOAD_DIR_NAME = "download"
        private const val CACHE_DIR_NAME = "cache"
        private const val META_FILE_NAME = "meta.txt"
        private const val REQUEST_FILE_NAME = "request.bin"
        private const val MAX_ATTEMPTS = 5

        @Volatile
        private var instance: HlsDownloadStore? = null

        fun get(context: Context): HlsDownloadStore {
            return instance ?: synchronized(this) {
                instance ?: HlsDownloadStore(context).also { instance = it }
            }
        }
    }
}
