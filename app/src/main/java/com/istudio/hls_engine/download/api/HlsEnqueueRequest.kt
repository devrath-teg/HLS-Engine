package com.istudio.hls_engine.download.api

import android.net.Uri

/**
 * Input for enqueueing an HLS VOD download.
 *
 * In production, [contentId] is a stable app id (e.g. Insider event id) — never the
 * signed CDN URL, which expires and changes.
 *
 * Track selection picks the **maximum** supported video bitrate (plus audio).
 */
data class HlsEnqueueRequest(
    val contentId: String,
    val masterPlaylistUri: Uri,
    val customData: ByteArray? = null,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is HlsEnqueueRequest) return false
        return contentId == other.contentId &&
            masterPlaylistUri == other.masterPlaylistUri &&
            customData.contentEquals(other.customData)
    }

    override fun hashCode(): Int {
        var result = contentId.hashCode()
        result = 31 * result + masterPlaylistUri.hashCode()
        result = 31 * result + (customData?.contentHashCode() ?: 0)
        return result
    }
}
