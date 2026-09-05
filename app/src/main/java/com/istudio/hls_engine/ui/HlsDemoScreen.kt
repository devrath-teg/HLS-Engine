package com.istudio.hls_engine.ui

import android.net.Uri
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.annotation.OptIn
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.PlayerView
import com.istudio.hls_engine.Constants
import com.istudio.hls_engine.download.api.HlsDownloadState
import com.istudio.hls_engine.download.api.HlsEnqueueRequest
import com.istudio.hls_engine.download.media3.DownloadComponents
import com.istudio.hls_engine.download.media3.Media3HlsDownloadEngine
import com.istudio.hls_engine.download.media3.Media3HlsOfflineLocator
import com.istudio.hls_engine.player.HlsPlayerFactory
import kotlinx.coroutines.launch
import java.security.MessageDigest

@OptIn(UnstableApi::class)
@Composable
fun HlsDemoScreen(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val engine = remember { Media3HlsDownloadEngine.get(context) }
    val locator = remember { Media3HlsOfflineLocator.get(context, engine) }
    val scope = rememberCoroutineScope()

    var urlText by remember { mutableStateOf(Constants.HLS_REMOTE_URL) }
    var contentId by remember { mutableStateOf(contentIdFor(Constants.HLS_REMOTE_URL)) }
    var status by remember { mutableStateOf("Idle") }
    var playbackSource by remember { mutableStateOf("—") }

    val statesFlow = remember(engine) { engine.observeStates() }
    val states by statesFlow.collectAsState(initial = emptyMap())
    val state = states[contentId] ?: engine.getState(contentId)
    val isDownloaded = state is HlsDownloadState.Downloaded
    val isDownloading = state is HlsDownloadState.Downloading || state is HlsDownloadState.Queued
    val downloadProgress = state.percent
    val bytesDownloaded = (state as? HlsDownloadState.Downloading)?.bytesDownloaded ?: 0L

    val player = remember { HlsPlayerFactory.create(context, locator) }

    DisposableEffect(player) {
        val listener = object : Player.Listener {
            override fun onPlayerError(error: PlaybackException) {
                val cause = error.cause?.message?.let { " ($it)" }.orEmpty()
                status = "Playback error: ${error.errorCodeName}$cause"
            }
        }
        player.addListener(listener)
        onDispose {
            player.removeListener(listener)
            player.release()
        }
    }

    val cachePath = remember {
        DownloadComponents.cacheDirectory(context).absolutePath
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("HLS remote / offline demo", style = MaterialTheme.typography.titleLarge)

        OutlinedTextField(
            value = urlText,
            onValueChange = {
                urlText = it
                contentId = contentIdFor(it.trim())
            },
            label = { Text("Remote .m3u8 URL") },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
        )

        Text(
            text = "contentId: $contentId",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = "Cache (spec: hls_download_cache):\n$cachePath",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = "Engine state: ${state::class.simpleName} (${"%.1f".format(downloadProgress)}%)",
            style = MaterialTheme.typography.bodyMedium,
        )
        Text(
            text = "Source: $playbackSource",
            style = MaterialTheme.typography.bodyMedium,
        )
        Text(
            text = status,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.primary,
        )

        if (isDownloading) {
            if (downloadProgress > 0f) {
                LinearProgressIndicator(
                    progress = { downloadProgress / 100f },
                    modifier = Modifier.fillMaxWidth(),
                )
            } else {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            }
            Text(
                text = if (downloadProgress > 0f) {
                    "Downloading… ${"%.1f".format(downloadProgress)}%" +
                        if (bytesDownloaded > 0) " (${formatBytes(bytesDownloaded)})" else ""
                } else {
                    "Downloading…" +
                        if (bytesDownloaded > 0) " ${formatBytes(bytesDownloaded)}" else ""
                },
            )
        }

        AndroidView(
            factory = { ctx ->
                PlayerView(ctx).apply {
                    layoutParams = FrameLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT,
                    )
                    useController = true
                    this.player = player
                }
            },
            update = { it.player = player },
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(16f / 9f),
        )

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Button(
                onClick = {
                    val uri = Uri.parse(urlText.trim())
                    player.setMediaItem(HlsPlayerFactory.remoteMediaItem(uri))
                    player.prepare()
                    player.playWhenReady = true
                    playbackSource = "Remote"
                    status = "Playing from remote"
                },
                modifier = Modifier.weight(1f),
                enabled = !isDownloading && urlText.isNotBlank(),
            ) {
                Text("Play remote")
            }

            Button(
                onClick = {
                    val mediaItem = HlsPlayerFactory.offlineMediaItem(locator, contentId)
                    if (mediaItem == null) {
                        status = "Not downloaded yet"
                        return@Button
                    }
                    player.stop()
                    player.clearMediaItems()
                    player.setMediaItem(mediaItem)
                    player.prepare()
                    player.playWhenReady = true
                    playbackSource = "Local cache"
                    status = "Playing offline via HlsOfflineLocator"
                },
                modifier = Modifier.weight(1f),
                enabled = isDownloaded && !isDownloading,
            ) {
                Text("Play offline")
            }
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Button(
                onClick = {
                    val uri = Uri.parse(urlText.trim())
                    status = "Enqueueing via HlsDownloadEngine…"
                    scope.launch {
                        val result = engine.enqueue(
                            HlsEnqueueRequest(
                                contentId = contentId,
                                masterPlaylistUri = uri,
                            ),
                        )
                        status = result.fold(
                            onSuccess = {
                                "Download running in-process (stops if app is killed)"
                            },
                            onFailure = { "Enqueue failed: ${it.message}" },
                        )
                    }
                },
                modifier = Modifier.weight(1f),
                enabled = !isDownloading && urlText.isNotBlank() && !isDownloaded,
            ) {
                Text("Download")
            }

            OutlinedButton(
                onClick = {
                    player.stop()
                    engine.remove(contentId)
                    playbackSource = "—"
                    status = "Removed via HlsDownloadEngine"
                },
                modifier = Modifier.weight(1f),
                enabled = isDownloaded || isDownloading,
            ) {
                Text("Remove")
            }
        }

        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = "Downloads use in-process Media3 DownloadManager (no notification). " +
                "They continue while the process is alive; killing the app stops them. " +
                "UI talks to HlsDownloadEngine + HlsOfflineLocator (AT-331).",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

private fun contentIdFor(url: String): String {
    if (url.isBlank()) return "empty"
    val digest = MessageDigest.getInstance("SHA-256")
        .digest(url.toByteArray(Charsets.UTF_8))
    return digest.joinToString("") { "%02x".format(it) }.take(16)
}

private fun formatBytes(bytes: Long): String {
    if (bytes < 1024) return "$bytes B"
    val kb = bytes / 1024.0
    if (kb < 1024) return "%.1f KB".format(kb)
    val mb = kb / 1024.0
    return "%.1f MB".format(mb)
}
