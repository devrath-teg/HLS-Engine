package com.istudio.hls_engine.download.media3

import android.content.Context
import android.os.Handler
import android.os.Looper
import androidx.annotation.OptIn
import androidx.media3.common.MediaItem
import androidx.media3.common.TrackSelectionParameters
import androidx.media3.common.util.UnstableApi
import androidx.media3.common.util.Util
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.offline.Download
import androidx.media3.exoplayer.offline.DownloadHelper
import androidx.media3.exoplayer.offline.DownloadManager
import androidx.media3.exoplayer.offline.DownloadRequest
import androidx.media3.exoplayer.offline.DownloadService
import com.istudio.hls_engine.download.api.HlsDownloadEngine
import com.istudio.hls_engine.download.api.HlsDownloadState
import com.istudio.hls_engine.download.api.HlsEnqueueRequest
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import java.io.IOException
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.coroutines.suspendCoroutine

/**
 * Media3-backed [HlsDownloadEngine] — drop into `core:download` and wire from
 * `LiskovDownloadManager` for `INSIDER_HLS`.
 */
@OptIn(UnstableApi::class)
class Media3HlsDownloadEngine(
    context: Context,
    private val downloadServiceClass: Class<out DownloadService> = HlsDownloadService::class.java,
) : HlsDownloadEngine {

    private val appContext = context.applicationContext
    private val mainHandler = Handler(Looper.getMainLooper())
    private val downloadManager = DownloadComponents.getDownloadManager(appContext)

    override suspend fun enqueue(request: HlsEnqueueRequest): Result<Unit> = runCatching {
        val existing = downloadManager.downloadIndex.getDownload(request.contentId)
        if (existing != null &&
            (existing.state == Download.STATE_DOWNLOADING ||
                existing.state == Download.STATE_QUEUED ||
                existing.state == Download.STATE_COMPLETED)
        ) {
            return@runCatching
        }

        val media3Request = prepareDownloadRequest(request)
        DownloadService.sendAddDownload(
            appContext,
            downloadServiceClass,
            media3Request,
            /* foreground= */ true,
        )
    }

    override fun pause(contentId: String) {
        DownloadService.sendSetStopReason(
            appContext,
            downloadServiceClass,
            contentId,
            STOP_REASON_USER,
            /* foreground= */ false,
        )
    }

    override fun resume(contentId: String) {
        DownloadService.sendSetStopReason(
            appContext,
            downloadServiceClass,
            contentId,
            Download.STOP_REASON_NONE,
            /* foreground= */ true,
        )
    }

    override fun cancel(contentId: String) {
        // AT-331 v1: cancel deletes partial data.
        remove(contentId)
    }

    override fun remove(contentId: String) {
        DownloadService.sendRemoveDownload(
            appContext,
            downloadServiceClass,
            contentId,
            /* foreground= */ false,
        )
    }

    override fun getState(contentId: String): HlsDownloadState {
        val download = downloadManager.downloadIndex.getDownload(contentId)
            ?: return HlsDownloadState.Idle(contentId)
        return download.toHlsState()
    }

    override fun observeStates(): Flow<Map<String, HlsDownloadState>> = callbackFlow {
        fun snapshot(): Map<String, HlsDownloadState> {
            val result = linkedMapOf<String, HlsDownloadState>()
            // Live in-memory downloads have the freshest percentDownloaded.
            downloadManager.currentDownloads.forEach { download ->
                result[download.request.id] = download.toHlsState()
            }
            downloadManager.downloadIndex.getDownloads().use { cursor ->
                while (cursor.moveToNext()) {
                    val download = cursor.download
                    result.putIfAbsent(download.request.id, download.toHlsState())
                }
            }
            return result
        }

        fun hasActiveWork(states: Map<String, HlsDownloadState>): Boolean =
            states.values.any {
                it is HlsDownloadState.Downloading ||
                    it is HlsDownloadState.Queued ||
                    it is HlsDownloadState.Removing
            }

        // Media3 does not notify on every progress tick — poll while work is active.
        val progressTicker = object : Runnable {
            override fun run() {
                val states = snapshot()
                trySend(states)
                if (hasActiveWork(states)) {
                    mainHandler.postDelayed(this, PROGRESS_POLL_MS)
                }
            }
        }

        fun scheduleProgressPolling() {
            mainHandler.removeCallbacks(progressTicker)
            mainHandler.post(progressTicker)
        }

        trySend(snapshot())
        val listener = object : DownloadManager.Listener {
            override fun onInitialized(downloadManager: DownloadManager) {
                scheduleProgressPolling()
            }

            override fun onDownloadChanged(
                downloadManager: DownloadManager,
                download: Download,
                finalException: Exception?,
            ) {
                scheduleProgressPolling()
            }

            override fun onDownloadRemoved(downloadManager: DownloadManager, download: Download) {
                scheduleProgressPolling()
            }

            override fun onIdle(downloadManager: DownloadManager) {
                trySend(snapshot())
                mainHandler.removeCallbacks(progressTicker)
            }
        }
        downloadManager.addListener(listener)
        scheduleProgressPolling()

        awaitClose {
            downloadManager.removeListener(listener)
            mainHandler.removeCallbacks(progressTicker)
        }
    }.distinctUntilChanged()

    override fun isDownloaded(contentId: String): Boolean =
        getState(contentId) is HlsDownloadState.Downloaded

    override fun getDownloadRequest(contentId: String): DownloadRequest? =
        downloadManager.downloadIndex.getDownload(contentId)?.request

    private suspend fun prepareDownloadRequest(request: HlsEnqueueRequest): DownloadRequest =
        suspendCoroutine { cont ->
            mainHandler.post {
                val helper = DownloadHelper.Factory()
                    .setRenderersFactory(DefaultRenderersFactory(appContext))
                    .setDataSourceFactory(DownloadComponents.httpDataSourceFactory(appContext))
                    .create(MediaItem.fromUri(request.masterPlaylistUri))

                val params = TrackSelectionParameters.Builder(appContext)
                    .setMinVideoBitrate(request.minVideoBitrateBps)
                    .build()

                helper.prepare(
                    object : DownloadHelper.Callback {
                        override fun onPrepared(
                            helper: DownloadHelper,
                            tracksInfoAvailable: Boolean,
                        ) {
                            try {
                                if (tracksInfoAvailable) {
                                    for (periodIndex in 0 until helper.periodCount) {
                                        helper.clearTrackSelections(periodIndex)
                                        helper.addAudioLanguagesToSelection()
                                        helper.addTextLanguagesToSelection(false)
                                        helper.addTrackSelection(periodIndex, params)
                                    }
                                }
                                val data = request.customData
                                    ?: Util.getUtf8Bytes(request.masterPlaylistUri.toString())
                                cont.resume(
                                    helper.getDownloadRequest(request.contentId, data),
                                )
                            } catch (e: Exception) {
                                cont.resumeWithException(e)
                            } finally {
                                helper.release()
                            }
                        }

                        override fun onPrepareError(helper: DownloadHelper, e: IOException) {
                            helper.release()
                            cont.resumeWithException(e)
                        }
                    },
                )
            }
        }

    private fun Download.toHlsState(): HlsDownloadState {
        val percent = normalizedPercent()
        return when (state) {
            Download.STATE_QUEUED -> HlsDownloadState.Queued(request.id)
            Download.STATE_STOPPED -> HlsDownloadState.Stopped(request.id, percent)
            Download.STATE_DOWNLOADING, Download.STATE_RESTARTING -> HlsDownloadState.Downloading(
                contentId = request.id,
                percent = percent,
                bytesDownloaded = bytesDownloaded,
                contentLength = contentLength,
            )
            Download.STATE_COMPLETED -> HlsDownloadState.Downloaded(
                request.id,
                bytesDownloaded,
            )
            Download.STATE_FAILED -> HlsDownloadState.Failed(
                request.id,
                failureReason.toString(),
                percent,
            )
            Download.STATE_REMOVING -> HlsDownloadState.Removing(request.id)
            else -> HlsDownloadState.Idle(request.id)
        }
    }

    /** Media3 uses -1 when percent is unknown; never surface that in UI. */
    private fun Download.normalizedPercent(): Float {
        val raw = percentDownloaded
        return if (raw < 0f) 0f else raw.coerceIn(0f, 100f)
    }

    companion object {
        const val STOP_REASON_USER = 1
        private const val PROGRESS_POLL_MS = 500L

        @Volatile
        private var instance: Media3HlsDownloadEngine? = null

        fun get(context: Context): Media3HlsDownloadEngine {
            return instance ?: synchronized(this) {
                instance ?: Media3HlsDownloadEngine(context).also { instance = it }
            }
        }
    }
}
