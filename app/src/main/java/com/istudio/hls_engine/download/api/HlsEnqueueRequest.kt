package com.istudio.hls_engine.download.api

import android.net.Uri

/**
 * Input for enqueueing an HLS VOD download.
 *
 * In production, [contentId] is a stable app id (e.g. Insider event id) — never the
 * signed CDN URL, which expires and changes.
 */
data class HlsEnqueueRequest(
    val contentId: String,
    val masterPlaylistUri: Uri,
    /** iOS parity default from AT-331; prefer the quality the user was watching. */
    val minVideoBitrateBps: Int = DEFAULT_MIN_VIDEO_BITRATE_BPS,
    val customData: ByteArray? = null,
) {
    companion object {
        const val DEFAULT_MIN_VIDEO_BITRATE_BPS = 2_000_000
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is HlsEnqueueRequest) return false
        return contentId == other.contentId &&
            masterPlaylistUri == other.masterPlaylistUri &&
            minVideoBitrateBps == other.minVideoBitrateBps &&
            customData.contentEquals(other.customData)
    }

    override fun hashCode(): Int {
        var result = contentId.hashCode()
        result = 31 * result + masterPlaylistUri.hashCode()
        result = 31 * result + minVideoBitrateBps
        result = 31 * result + (customData?.contentHashCode() ?: 0)
        return result
    }
}
