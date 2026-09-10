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
import com.istudio.hls_engine.download.api.HlsDownloadEngine
import com.istudio.hls_engine.download.api.HlsDownloadState
import com.istudio.hls_engine.download.api.HlsEnqueueRequest
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flow
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.coroutines.suspendCoroutine
import kotlin.time.Duration.Companion.milliseconds

/**
 * Media3-backed [HlsDownloadEngine] using in-process [DownloadManager] only
 * (no [androidx.media3.exoplayer.offline.DownloadService] / no notification).
 *
 * Downloads continue while the app process is alive (including backgrounded UI).
 * If the process is killed, active downloads stop.
 */
@OptIn(UnstableApi::class)
@Singleton
class Media3HlsDownloadEngine @Inject constructor(
    @ApplicationContext context: Context,
) : HlsDownloadEngine {

    private val appContext = context.applicationContext
    private val mainHandler = Handler(Looper.getMainLooper())
    private val downloadManager = DownloadComponents.getDownloadManager(appContext)

    init {
        // Process death leaves rows as DOWNLOADING/QUEUED in DownloadIndex. Without a
        // DownloadService those jobs are dead — clear them so UI doesn't stick on
        // indeterminate "Downloading…".
        downloadManager.pauseDownloads()
        if (downloadManager.isInitialized) {
            removeInterruptedDownloads()
        } else {
            downloadManager.addListener(
                object : DownloadManager.Listener {
                    override fun onInitialized(downloadManager: DownloadManager) {
                        removeInterruptedDownloads()
                        downloadManager.removeListener(this)
                    }
                },
            )
        }
    }

    override suspend fun enqueue(request: HlsEnqueueRequest): Result<Unit> = runCatching {
        val existing = downloadManager.downloadIndex.getDownload(request.contentId)
        if (existing != null && existing.state == Download.STATE_COMPLETED) {
            return@runCatching
        }
        // Replace any leftover incomplete entry for this id.
        if (existing != null) {
            downloadManager.removeDownload(request.contentId)
        }

        val media3Request = prepareDownloadRequest(request)
        downloadManager.addDownload(media3Request)
        downloadManager.resumeDownloads()
    }

    override fun pause(contentId: String) {
        downloadManager.setStopReason(contentId, STOP_REASON_USER)
    }

    override fun resume(contentId: String) {
        downloadManager.setStopReason(contentId, Download.STOP_REASON_NONE)
        downloadManager.resumeDownloads()
    }

    override fun cancel(contentId: String) {
        // AT-331 v1: cancel deletes partial data.
        remove(contentId)
    }

    override fun remove(contentId: String) {
        downloadManager.removeDownload(contentId)
    }

    /**
     * Treats non-completed downloads as cancelled after process death.
     * Completed offline assets are kept.
     */
    private fun removeInterruptedDownloads() {
        val idsToRemove = mutableListOf<String>()
        downloadManager.downloadIndex.getDownloads().use { cursor ->
            while (cursor.moveToNext()) {
                val download = cursor.download
                if (download.state != Download.STATE_COMPLETED) {
                    idsToRemove += download.request.id
                }
            }
        }
        idsToRemove.forEach { id ->
            downloadManager.removeDownload(id)
        }
    }

    override fun getState(contentId: String): HlsDownloadState {
        // Prefer in-memory currentDownloads for freshest percentDownloaded.
        downloadManager.currentDownloads
            .firstOrNull { it.request.id == contentId }
            ?.let { return it.toHlsState() }
        val download = downloadManager.downloadIndex.getDownload(contentId)
            ?: return HlsDownloadState.Idle(contentId)
        return download.toHlsState()
    }

    /**
     * Media3's [DownloadManager.Listener] only fires on state changes, not percent updates.
     * Polling is the supported way to surface progress (same approach as DownloadService).
     */
    override fun observeStates(): Flow<Map<String, HlsDownloadState>> = flow {
        while (true) {
            emit(snapshotStates())
            delay(PROGRESS_POLL_MS.milliseconds)
        }
    }.distinctUntilChanged()

    override fun observeState(contentId: String): Flow<HlsDownloadState> = flow {
        while (true) {
            emit(getState(contentId))
            delay(PROGRESS_POLL_MS.milliseconds)
        }
    }.distinctUntilChanged()

    private fun snapshotStates(): Map<String, HlsDownloadState> {
        val result = linkedMapOf<String, HlsDownloadState>()
        // Live downloads first — freshest percentDownloaded.
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

                // Prefer the highest available video bitrate for offline download.
                val params = TrackSelectionParameters.Builder(appContext)
                    .setForceHighestSupportedBitrate(true)
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

    private fun Download.normalizedPercent(): Float {
        val raw = percentDownloaded
        return if (raw < 0f) 0f else raw.coerceIn(0f, 100f)
    }

    companion object {
        const val STOP_REASON_USER = 1
        private const val PROGRESS_POLL_MS = 500L
    }
}
