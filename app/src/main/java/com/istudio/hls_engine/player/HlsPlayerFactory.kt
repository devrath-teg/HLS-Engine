package com.istudio.hls_engine.player

import android.content.Context
import android.net.Uri
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.hls.HlsMediaSource
import androidx.media3.exoplayer.source.MediaSource
import com.istudio.hls_engine.download.HlsDownloadStore

@OptIn(UnstableApi::class)
object HlsPlayerFactory {

    fun create(context: Context): ExoPlayer = ExoPlayer.Builder(context).build()

    fun remoteMediaSource(store: HlsDownloadStore, playlistUri: Uri): MediaSource {
        return HlsMediaSource.Factory(store.remoteDataSourceFactory())
            .createMediaSource(store.mediaItemForRemote(playlistUri))
    }

    /**
     * Plays from `{filesDir}/download/{contentId}/cache/` using the same stream keys
     * that were selected when [HlsDownloadStore.download] ran.
     */
    fun offlineMediaSource(
        store: HlsDownloadStore,
        contentId: String,
        playlistUri: Uri,
    ): MediaSource {
        return HlsMediaSource.Factory(store.cacheDataSourceFactory(contentId, writeToCache = false))
            .createMediaSource(store.mediaItemForOffline(contentId, playlistUri))
    }
}
