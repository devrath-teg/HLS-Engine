package com.istudio.hls_engine.player

import android.content.Context
import android.net.Uri
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.TrackSelectionParameters
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.hls.HlsMediaSource
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.source.MediaSource
import com.istudio.hls_engine.download.api.HlsOfflineLocator

/**
 * Demo player helpers. In production, PlayerKit's MediaSourceFactory should
 * inject [HlsOfflineLocator.readOnlyCacheDataSourceFactory] when offline.
 */
@OptIn(UnstableApi::class)
object HlsPlayerFactory {

    fun create(context: Context, locator: HlsOfflineLocator): ExoPlayer {
        return ExoPlayer.Builder(context)
            .setMediaSourceFactory(
                DefaultMediaSourceFactory(context)
                    .setDataSourceFactory(locator.readOnlyCacheDataSourceFactory()),
            )
            .build()
            .also { player ->
                // ExoPlayer does not auto-enable captions; prefer EN + undetermined
                // so PlayerView can render WebVTT/TTML when present in the HLS.
                player.trackSelectionParameters = TrackSelectionParameters.Builder(context)
                    .setPreferredTextLanguage("en")
                    .setSelectUndeterminedTextLanguage(true)
                    .setPreferredTextRoleFlags(C.ROLE_FLAG_CAPTION or C.ROLE_FLAG_SUBTITLE)
                    .build()
            }
    }

    fun offlineMediaItem(locator: HlsOfflineLocator, contentId: String): MediaItem? {
        val item = locator.mediaItemForOffline(contentId) ?: return null
        return item.buildUpon()
            .setMediaMetadata(
                MediaMetadata.Builder()
                    .setTitle("HLS offline")
                    .build(),
            )
            .build()
    }

    /** Streaming-only source (HTTP). Used by [com.istudio.hls_engine.ui.HlsDemoViewModel.playRemote]. */
    fun remoteMediaSource(locator: HlsOfflineLocator, playlistUri: Uri): MediaSource =
        HlsMediaSource.Factory(locator.remoteDataSourceFactory())
            .createMediaSource(remoteMediaItem(playlistUri))


    private fun remoteMediaItem(playlistUri: Uri): MediaItem =
        MediaItem.Builder()
            .setUri(playlistUri)
            .setMediaMetadata(
                MediaMetadata.Builder()
                    .setTitle("HLS remote")
                    .build(),
            )
            .build()
}
