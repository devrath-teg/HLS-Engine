package com.istudio.hls_engine.download.api

import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.offline.DownloadRequest
import kotlinx.coroutines.flow.Flow

/**
 * HLS engine contract from AT-331.
 *
 * Lives in `core:download` and is called by `LiskovDownloadManager` for
 * `INSIDER_HLS` (or `VIDEO_HLS`) requests. Progressive ZIP/PDF/podcast keep
 * using `FileDownloaderRepository`.
 *
 * All lookups use a stable [contentId] (production: Insider event / TEG id —
 * never a signed CDN URL).
 */
interface HlsDownloadEngine {
    /** Start (or replace incomplete) download for [HlsEnqueueRequest.contentId]. */
    suspend fun enqueue(request: HlsEnqueueRequest): Result<Unit>

    fun pause(contentId: String)
    fun resume(contentId: String)

    /** Stop an in-progress download (v1 deletes partial data — same as [remove]). */
    fun cancel(contentId: String)

    /** Delete download + cache for [contentId] (finished or in progress). */
    fun remove(contentId: String)

    /** One-shot snapshot of status/progress for [contentId]. */
    fun getState(contentId: String): HlsDownloadState

    /**
     * Observe status + progress for one [contentId].
     *
     * - Polls percent **only** while [HlsDownloadState.isActive].
     * - Stops the 500ms loop when completed / failed / idle / stopped.
     * - Media3 listener stays until the collector cancels (leave UI,
     *   `WhileSubscribed`, or contentId change via `flatMapLatest`).
     *
     * See [com.istudio.hls_engine.download.media3.Media3HlsDownloadEngine.observeState]
     * for the full lifecycle notes.
     */
    fun observeState(contentId: String): Flow<HlsDownloadState>

    fun isDownloaded(contentId: String): Boolean

    /**
     * Media3 request used to build an offline [androidx.media3.common.MediaItem]
     * (`DownloadRequest.toMediaItem()`). Null if not downloaded / not completed.
     */
    @OptIn(UnstableApi::class)
    fun getDownloadRequest(contentId: String): DownloadRequest?
}
