package com.istudio.hls_engine.download.api

/**
 * App-facing download states — map these onto Liskov `QUEUED` / `DOWNLOADING` /
 * `DOWNLOADED` / `FAILED` in the orchestrator.
 */
sealed class HlsDownloadState {
    abstract val contentId: String
    abstract val percent: Float

    data class Idle(override val contentId: String) : HlsDownloadState() {
        override val percent: Float = 0f
    }

    data class Queued(override val contentId: String) : HlsDownloadState() {
        override val percent: Float = 0f
    }

    data class Downloading(
        override val contentId: String,
        override val percent: Float,
        val bytesDownloaded: Long = 0L,
        val contentLength: Long = -1L,
    ) : HlsDownloadState()

    data class Downloaded(
        override val contentId: String,
        val bytesDownloaded: Long = 0L,
    ) : HlsDownloadState() {
        override val percent: Float = 100f
    }

    data class Failed(
        override val contentId: String,
        val message: String?,
        override val percent: Float = 0f,
    ) : HlsDownloadState()

    data class Stopped(
        override val contentId: String,
        override val percent: Float = 0f,
    ) : HlsDownloadState()

    data class Removing(override val contentId: String) : HlsDownloadState() {
        override val percent: Float = 0f
    }

    val isTerminalSuccess: Boolean get() = this is Downloaded
    val isActive: Boolean
        get() = this is Queued || this is Downloading || this is Removing
}
