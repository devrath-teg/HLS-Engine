package com.istudio.hls_engine.ui

import com.istudio.hls_engine.download.api.HlsDownloadState

/**
 * Immutable UI model for the HLS demo screen.
 * Built by [HlsDemoViewModel]; Compose only renders this + invokes intents.
 */
data class HlsDemoUiState(
    val urlText: String,
    val contentId: String,
    val status: String = "Idle",
    val playbackSource: String = "—",
    val downloadState: HlsDownloadState,
    val cachePath: String,
) {
    val isDownloaded: Boolean get() = downloadState is HlsDownloadState.Downloaded
    val isDownloading: Boolean
        get() = downloadState is HlsDownloadState.Downloading ||
            downloadState is HlsDownloadState.Queued
    val downloadProgress: Float get() = downloadState.percent
    val bytesDownloaded: Long
        get() = (downloadState as? HlsDownloadState.Downloading)?.bytesDownloaded ?: 0L
}
