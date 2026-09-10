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
 */
interface HlsDownloadEngine {
    suspend fun enqueue(request: HlsEnqueueRequest): Result<Unit>
    fun pause(contentId: String)
    fun resume(contentId: String)
    fun cancel(contentId: String)
    fun remove(contentId: String)
    fun getState(contentId: String): HlsDownloadState
    fun observeStates(): Flow<Map<String, HlsDownloadState>>
    /** Live status + progress for a single [contentId]. */
    fun observeState(contentId: String): Flow<HlsDownloadState>
    fun isDownloaded(contentId: String): Boolean

    @OptIn(UnstableApi::class)
    fun getDownloadRequest(contentId: String): DownloadRequest?
}
