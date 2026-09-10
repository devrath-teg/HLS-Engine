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
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
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

    /**
     * Current status for [contentId].
     *
     * Checks [DownloadManager.currentDownloads] first (in-memory, best
     * percentDownloaded while active), then falls back to [DownloadIndex]
     * (persisted completed / failed / queued rows).
     */
    override fun getState(contentId: String): HlsDownloadState {
        downloadManager.currentDownloads
            .firstOrNull { it.request.id == contentId }
            ?.let { return it.toHlsState() }
        val download = downloadManager.downloadIndex.getDownload(contentId)
            ?: return HlsDownloadState.Idle(contentId)
        return download.toHlsState()
    }

    /**
     * API: Observe the status of progress from the UI layer
     */
    override fun observeState(contentId: String): Flow<HlsDownloadState> =
        observeDownloadState(contentId)
            .distinctUntilChanged() // skip identical states so Compose does not recompose for free

    /**
     * Builds the cold Flow that listens + polls for [contentId].
     */
    private fun observeDownloadState(contentId: String): Flow<HlsDownloadState> = callbackFlow {

        // Job that polls percent; null / inactive when download is not in flight.
        var progressJob: Job? = null

        /**
         * Push latest state to collectors, then start or stop the percent poll.
         * Called on: ---> first subscribe, Media3 onDownloadChanged, onDownloadRemoved.
         */
        fun emitAndMaybePoll() {
            val state = getState(contentId)
            trySend(state)

            if (state.isActive) {
                // Already polling this id — avoid stacking duplicate jobs.
                if (progressJob?.isActive == true) return

                progressJob = launch {
                    // Percent poll loop — Media3 does not push % updates.
                    while (isActive) {
                        val current = getState(contentId)
                        trySend(current)
                        // Completed / failed / idle / stopped → stop waking every 500ms.
                        if (!current.isActive) break
                        delay(PROGRESS_POLL_MS.milliseconds)
                    }
                }
            } else {
                // Not transferring — ensure any leftover poll is torn down.
                progressJob?.cancel()
                progressJob = null
            }
        }

        // Wakes us on state transitions only (not on percent ticks).
        val listener = object : DownloadManager.Listener {
            override fun onDownloadChanged(
                downloadManager: DownloadManager,
                download: Download,
                finalException: Exception?,
            ) {
                // Ignore other contentIds sharing this DownloadManager.
                if (download.request.id == contentId) emitAndMaybePoll()
            }

            override fun onDownloadRemoved(
                downloadManager: DownloadManager,
                download: Download,
            ) {
                if (download.request.id == contentId) emitAndMaybePoll()
            }
        }

        downloadManager.addListener(listener)
        emitAndMaybePoll() // initial snapshot (+ start poll if already active)

        // Full unsubscribe when nobody is collecting this Flow anymore.
        awaitClose {
            progressJob?.cancel()
            downloadManager.removeListener(listener)
        }
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

    /** Maps Media3 [Download] → app [HlsDownloadState] (including percent 0–100). */
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

    /** Media3 may report [Download.PERCENTAGE_UNSET] (-1); treat that as 0. */
    private fun Download.normalizedPercent(): Float {
        val raw = percentDownloaded
        return if (raw < 0f) 0f else raw.coerceIn(0f, 100f)
    }

    companion object {
        const val STOP_REASON_USER = 1
        /**
         * How often we re-read [Download.percentDownloaded] while
         * [HlsDownloadState.isActive]. Not used once the download is idle —
         * see [observeState].
         */
        private const val PROGRESS_POLL_MS = 500L
    }
}
