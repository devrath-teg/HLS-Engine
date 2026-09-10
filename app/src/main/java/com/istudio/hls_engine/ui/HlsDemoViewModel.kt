package com.istudio.hls_engine.ui

import android.content.Context
import android.net.Uri
import androidx.annotation.OptIn
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import com.istudio.hls_engine.Constants
import com.istudio.hls_engine.download.api.HlsDownloadEngine
import com.istudio.hls_engine.download.api.HlsDownloadState
import com.istudio.hls_engine.download.api.HlsEnqueueRequest
import com.istudio.hls_engine.download.api.HlsOfflineLocator
import com.istudio.hls_engine.download.media3.DownloadComponents
import com.istudio.hls_engine.player.HlsPlayerFactory
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.security.MessageDigest
import javax.inject.Inject

/**
 * Owns demo download + playback wiring so the Compose screen stays presentation-only.
 *
 * Talks to [HlsDownloadEngine] + [HlsOfflineLocator] (AT-331 shape); Media3 stays
 * behind those APIs.
 *
 * ## Download progress subscription
 *
 * UI state is driven by [HlsDownloadEngine.observeState] for the **current** URL's
 * contentId (not a map of all downloads).
 *
 * - [flatMapLatest]: when the URL changes, the previous contentId Flow is
 *   **cancelled** → engine [awaitClose] removes that Media3 listener / poll.
 * - [SharingStarted.WhileSubscribed]: when Compose leaves and nothing collects
 *   for 5s, upstream is cancelled the same way (full unsubscribe).
 * - While Downloaded/Idle, the engine itself already stops the 500ms poll; the
 *   ViewModel can stay subscribed without burning CPU on percent ticks.
 */
@OptIn(UnstableApi::class, ExperimentalCoroutinesApi::class)
@HiltViewModel
class HlsDemoViewModel @Inject constructor(
    @ApplicationContext appContext: Context,
    private val engine: HlsDownloadEngine,
    private val locator: HlsOfflineLocator,
) : ViewModel() {

    private val cachePath: String =
        DownloadComponents.cacheDirectory(appContext).absolutePath

    private val urlText = MutableStateFlow(Constants.HLS_REMOTE_URL)
    private val status = MutableStateFlow("Idle")
    private val playbackSource = MutableStateFlow("—")

    val player: ExoPlayer = HlsPlayerFactory.create(appContext, locator)

    private val playerListener = object : Player.Listener {
        override fun onPlayerError(error: PlaybackException) {
            val cause = error.cause?.message?.let { " ($it)" }.orEmpty()
            status.value = "Playback error: ${error.errorCodeName}$cause"
        }
    }

    /**
     * Re-subscribes [HlsDownloadEngine.observeState] whenever [urlText] changes.
     * See class KDoc for how that cancels the previous contentId listener.
     */
    val uiState: StateFlow<HlsDemoUiState> = urlText
        .flatMapLatest { url ->
            val contentId = contentIdFor(url.trim())
            combine(
                status,
                playbackSource,
                engine.observeState(contentId),
            ) { statusText, source, downloadState ->
                HlsDemoUiState(
                    urlText = url,
                    contentId = contentId,
                    status = statusText,
                    playbackSource = source,
                    downloadState = downloadState,
                    cachePath = cachePath,
                )
            }
        }
        .stateIn(
            scope = viewModelScope,
            // No UI collectors for 5s → cancel observeState (listener + poll).
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = HlsDemoUiState(
                urlText = Constants.HLS_REMOTE_URL,
                contentId = contentIdFor(Constants.HLS_REMOTE_URL),
                downloadState = HlsDownloadState.Idle(contentIdFor(Constants.HLS_REMOTE_URL)),
                cachePath = cachePath,
            ),
        )

    init {
        player.addListener(playerListener)
    }

    fun onUrlChange(value: String) {
        urlText.value = value
    }

    fun playRemote() {
        val uri = Uri.parse(urlText.value.trim())
        if (urlText.value.isBlank()) return
        player.stop()
        player.clearMediaItems()
        // Pure HTTP HlsMediaSource (not the download-cache factory used for offline).
        player.setMediaSource(HlsPlayerFactory.remoteMediaSource(locator, uri))
        player.prepare()
        player.playWhenReady = true
        playbackSource.value = "Remote"
        status.value = "Playing from remote"
    }

    fun playOffline() {
        val contentId = contentIdFor(urlText.value.trim())
        val mediaItem = HlsPlayerFactory.offlineMediaItem(locator, contentId)
        if (mediaItem == null) {
            status.value = "Not downloaded yet"
            return
        }
        player.stop()
        player.clearMediaItems()
        player.setMediaItem(mediaItem)
        player.prepare()
        player.playWhenReady = true
        playbackSource.value = "Local cache"
        status.value = "Playing offline via HlsOfflineLocator"
    }

    fun download() {
        val raw = urlText.value.trim()
        if (raw.isBlank()) return
        val contentId = contentIdFor(raw)
        val uri = Uri.parse(raw)
        status.value = "Enqueueing via HlsDownloadEngine…"
        viewModelScope.launch {
            val result = engine.enqueue(
                HlsEnqueueRequest(
                    contentId = contentId,
                    masterPlaylistUri = uri,
                ),
            )
            status.value = result.fold(
                onSuccess = {
                    "Download running in-process (stops if app is killed)"
                },
                onFailure = { "Enqueue failed: ${it.message}" },
            )
        }
    }

    fun remove() {
        val contentId = contentIdFor(urlText.value.trim())
        player.stop()
        engine.remove(contentId)
        playbackSource.value = "—"
        status.value = "Removed via HlsDownloadEngine"
    }

    override fun onCleared() {
        player.removeListener(playerListener)
        player.release()
        super.onCleared()
    }

    companion object {
        /**
         * POC-only: derive an id from the typed URL (SHA-256 prefix).
         * Production should pass a stable Insider event / TEG id instead.
         */
        fun contentIdFor(url: String): String {
            if (url.isBlank()) return "empty"
            val digest = MessageDigest.getInstance("SHA-256")
                .digest(url.toByteArray(Charsets.UTF_8))
            return digest.joinToString("") { "%02x".format(it) }.take(16)
        }
    }
}
