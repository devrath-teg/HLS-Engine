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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
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
import com.istudio.hls_engine.download.HlsDownloadStore
import com.istudio.hls_engine.player.HlsPlayerFactory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.security.MessageDigest

@OptIn(UnstableApi::class)
@Composable
fun HlsDemoScreen(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val store = remember { HlsDownloadStore.get(context) }
    val scope = rememberCoroutineScope()

    var urlText by remember { mutableStateOf(Constants.HLS_REMOTE_URL) }
    var contentId by remember { mutableStateOf(contentIdFor(Constants.HLS_REMOTE_URL)) }
    var status by remember { mutableStateOf("Idle") }
    var playbackSource by remember { mutableStateOf("—") }
    var downloadProgress by remember { mutableFloatStateOf(0f) }
    var isDownloading by remember { mutableStateOf(false) }
    var isDownloaded by remember {
        mutableStateOf(store.isDownloaded(contentIdFor(Constants.HLS_REMOTE_URL)))
    }

    val player = remember { HlsPlayerFactory.create(context) }

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

    val downloadPath = remember(contentId) {
        File(context.filesDir, "${HlsDownloadStore.DOWNLOAD_DIR_NAME}/$contentId").absolutePath
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
                isDownloaded = store.isDownloaded(contentId)
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
            text = "Download folder:\n$downloadPath/cache",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
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
            LinearProgressIndicator(
                progress = { downloadProgress / 100f },
                modifier = Modifier.fillMaxWidth(),
            )
            Text("Downloading… ${"%.1f".format(downloadProgress)}%")
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
                    player.setMediaSource(HlsPlayerFactory.remoteMediaSource(store, uri))
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
                    val id = contentId
                    if (!store.isDownloaded(id)) {
                        status = "Not downloaded yet"
                        return@Button
                    }
                    val uri = store.savedPlaylistUri(id) ?: Uri.parse(urlText.trim())
                    player.stop()
                    player.clearMediaItems()
                    player.setMediaSource(
                        HlsPlayerFactory.offlineMediaSource(store, id, uri),
                    )
                    player.prepare()
                    player.playWhenReady = true
                    playbackSource = "Local cache"
                    status = "Playing from $downloadPath/cache"
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
                    val id = contentId
                    isDownloading = true
                    downloadProgress = 0f
                    status = "Starting HLS download…"
                    scope.launch {
                        try {
                            withContext(Dispatchers.IO) {
                                store.download(id, uri) { percent ->
                                    downloadProgress = percent.coerceIn(0f, 100f)
                                }
                            }
                            isDownloaded = true
                            status = "Download complete → $downloadPath/cache"
                        } catch (e: Exception) {
                            isDownloaded = false
                            status = friendlyDownloadError(e)
                        } finally {
                            isDownloading = false
                        }
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
                    store.remove(contentId)
                    isDownloaded = false
                    playbackSource = "—"
                    status = "Removed local download"
                },
                modifier = Modifier.weight(1f),
                enabled = isDownloaded && !isDownloading,
            ) {
                Text("Remove")
            }
        }

        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = "HLS uses Media3 HlsDownloader + SimpleCache under the download folder " +
                "(segments, not a single progressive file).",
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

private fun friendlyDownloadError(e: Exception): String {
    val root = generateSequence(e as Throwable) { it.cause }.last()
    val detail = root.message ?: e.message ?: e.javaClass.simpleName
    return when {
        detail.contains("socket", ignoreCase = true) ||
            detail.contains("Connection reset", ignoreCase = true) ->
            "Download failed: connection dropped (retry Download). $detail"
        root is java.net.UnknownHostException ->
            "Download failed: host unreachable / no network ($detail)"
        else ->
            "Download failed: $detail"
    }
}
