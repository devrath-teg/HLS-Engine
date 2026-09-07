package com.istudio.hls_engine.ui

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
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.PlayerView

@OptIn(UnstableApi::class)
@Composable
fun HlsDemoScreen(
    modifier: Modifier = Modifier,
    viewModel: HlsDemoViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("HLS remote / offline demo", style = MaterialTheme.typography.titleLarge)

        OutlinedTextField(
            value = state.urlText,
            onValueChange = viewModel::onUrlChange,
            label = { Text("Remote .m3u8 URL") },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
        )

        Text(
            text = "contentId: ${state.contentId}",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = "Cache (spec: hls_download_cache):\n${state.cachePath}",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = "Engine state: ${state.downloadState::class.simpleName} " +
                "(${"%.1f".format(state.downloadProgress)}%)",
            style = MaterialTheme.typography.bodyMedium,
        )
        Text(
            text = "Source: ${state.playbackSource}",
            style = MaterialTheme.typography.bodyMedium,
        )
        Text(
            text = state.status,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.primary,
        )

        if (state.isDownloading) {
            if (state.downloadProgress > 0f) {
                LinearProgressIndicator(
                    progress = { state.downloadProgress / 100f },
                    modifier = Modifier.fillMaxWidth(),
                )
            } else {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            }
            Text(
                text = if (state.downloadProgress > 0f) {
                    "Downloading… ${"%.1f".format(state.downloadProgress)}%" +
                        if (state.bytesDownloaded > 0) {
                            " (${formatBytes(state.bytesDownloaded)})"
                        } else {
                            ""
                        }
                } else {
                    "Downloading…" +
                        if (state.bytesDownloaded > 0) {
                            " ${formatBytes(state.bytesDownloaded)}"
                        } else {
                            ""
                        }
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
                    player = viewModel.player
                }
            },
            update = { it.player = viewModel.player },
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(16f / 9f),
        )

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Button(
                onClick = viewModel::playRemote,
                modifier = Modifier.weight(1f),
                enabled = !state.isDownloading && state.urlText.isNotBlank(),
            ) {
                Text("Play remote")
            }

            Button(
                onClick = viewModel::playOffline,
                modifier = Modifier.weight(1f),
                enabled = state.isDownloaded && !state.isDownloading,
            ) {
                Text("Play offline")
            }
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Button(
                onClick = viewModel::download,
                modifier = Modifier.weight(1f),
                enabled = !state.isDownloading &&
                    state.urlText.isNotBlank() &&
                    !state.isDownloaded,
            ) {
                Text("Download")
            }

            OutlinedButton(
                onClick = viewModel::remove,
                modifier = Modifier.weight(1f),
                enabled = state.isDownloaded || state.isDownloading,
            ) {
                Text("Remove")
            }
        }

        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = "Downloads use in-process Media3 DownloadManager (no notification). " +
                "They continue while the process is alive; killing the app stops them. " +
                "UI → Hilt ViewModel → HlsDownloadEngine + HlsOfflineLocator (AT-331).",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

private fun formatBytes(bytes: Long): String {
    if (bytes < 1024) return "$bytes B"
    val kb = bytes / 1024.0
    if (kb < 1024) return "%.1f KB".format(kb)
    val mb = kb / 1024.0
    return "%.1f MB".format(mb)
}
