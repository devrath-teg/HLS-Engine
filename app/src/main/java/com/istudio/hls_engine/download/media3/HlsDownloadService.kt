package com.istudio.hls_engine.download.media3

import android.app.Notification
import android.content.Context
import androidx.annotation.OptIn
import androidx.media3.common.util.NotificationUtil
import androidx.media3.common.util.UnstableApi
import androidx.media3.common.util.Util
import androidx.media3.exoplayer.offline.Download
import androidx.media3.exoplayer.offline.DownloadManager
import androidx.media3.exoplayer.offline.DownloadNotificationHelper
import androidx.media3.exoplayer.offline.DownloadService
import androidx.media3.exoplayer.scheduler.Requirements
import com.istudio.hls_engine.R

/** Foreground download service — survives UI teardown / process backgrounding. */
@OptIn(UnstableApi::class)
class HlsDownloadService : DownloadService(
    FOREGROUND_NOTIFICATION_ID,
    DEFAULT_FOREGROUND_NOTIFICATION_UPDATE_INTERVAL,
    DownloadComponents.DOWNLOAD_NOTIFICATION_CHANNEL_ID,
    R.string.download_notification_channel_name,
    /* channelDescriptionResourceId= */ 0,
) {

    override fun getDownloadManager(): DownloadManager {
        val manager = DownloadComponents.getDownloadManager(this)
        manager.addListener(
            TerminalStateNotificationHelper(
                this,
                DownloadComponents.getDownloadNotificationHelper(this),
                FOREGROUND_NOTIFICATION_ID + 1,
            ),
        )
        return manager
    }

    override fun getScheduler() = null

    override fun getForegroundNotification(
        downloads: MutableList<Download>,
        notMetRequirements: @Requirements.RequirementFlags Int,
    ): Notification {
        return DownloadComponents.getDownloadNotificationHelper(this)
            .buildProgressNotification(
                this,
                R.drawable.ic_notification_download,
                /* contentIntent= */ null,
                /* message= */ null,
                downloads,
                notMetRequirements,
            )
    }

    private class TerminalStateNotificationHelper(
        context: Context,
        private val notificationHelper: DownloadNotificationHelper,
        firstNotificationId: Int,
    ) : DownloadManager.Listener {
        private val appContext = context.applicationContext
        private var nextNotificationId = firstNotificationId

        override fun onDownloadChanged(
            downloadManager: DownloadManager,
            download: Download,
            finalException: Exception?,
        ) {
            val notification = when (download.state) {
                Download.STATE_COMPLETED -> notificationHelper.buildDownloadCompletedNotification(
                    appContext,
                    R.drawable.ic_notification_download,
                    null,
                    Util.fromUtf8Bytes(download.request.data),
                )
                Download.STATE_FAILED -> notificationHelper.buildDownloadFailedNotification(
                    appContext,
                    R.drawable.ic_notification_download,
                    null,
                    Util.fromUtf8Bytes(download.request.data),
                )
                else -> return
            }
            NotificationUtil.setNotification(appContext, nextNotificationId++, notification)
        }
    }

    companion object {
        private const val FOREGROUND_NOTIFICATION_ID = 1
    }
}
