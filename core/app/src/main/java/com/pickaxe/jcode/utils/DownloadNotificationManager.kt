/*
 *  This file is part of AndroidCodeStudio.
 *
 *  AndroidCodeStudio is free software: you can redistribute it and/or modify
 *  it under the terms of the GNU General Public License as published by
 *  the Free Software Foundation, either version 3 of the License, or
 *  (at your option) any later version.
 *
 *  AndroidCodeStudio is distributed in the hope that it will be useful,
 *  but WITHOUT ANY WARRANTY; without even the implied warranty of
 *  MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *  GNU General Public License for more details.
 *
 *  You should have received a copy of the GNU General Public License
 *   along with AndroidCodeStudio.  If not, see <https://www.gnu.org/licenses/>.
 */
package com.pickaxe.jcode.utils

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.pickaxe.jcode.BuildConfig
import com.pickaxe.jcode.R
import com.pickaxe.jcode.app.BaseApplication

/**
 * Manages persistent notifications for dependency downloads.
 *
 * Kotlin and JDK artifacts are large (~100MB+), so downloads can take a while.
 * We keep a progress notification visible to the user while downloading and
 * dismiss it on completion or failure.
 */
object DownloadNotificationManager {

  private const val NOTIFICATION_ID: Int = 17572

  fun ensureNotificationChannel(context: Context) {
    val channel =
        NotificationChannel(
            BaseApplication.NOTIFICATION_DEPENDENCY_DOWNLOAD,
            context.getString(R.string.title_dependency_download_notification_channel),
            NotificationManager.IMPORTANCE_LOW,
        )
    NotificationManagerCompat.from(context).createNotificationChannel(channel)
  }

  private fun buildNotification(
      context: Context,
      message: String,
      isProgress: Boolean,
  ): Notification {
    val intent =
        context.packageManager.getLaunchIntentForPackage(BuildConfig.APPLICATION_ID)
            ?.let {
              PendingIntent.getActivity(
                  context,
                  0,
                  it,
                  PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
              )
            }

    return NotificationCompat.Builder(context, BaseApplication.NOTIFICATION_DEPENDENCY_DOWNLOAD)
            .setSmallIcon(R.drawable.ic_launcher_notification)
            .setContentTitle(context.getString(R.string.title_dependency_download_notification))
            .setContentText(message)
            .setContentIntent(intent)
            .setAutoCancel(true)
            .setOnlyAlertOnce(true)
            .setProgress(0, 0, isProgress)
            .setOngoing(isProgress)
            .build()
  }

  fun startDownload(context: Context, message: String) {
    ensureNotificationChannel(context)
    NotificationManagerCompat.from(context).notify(
        NOTIFICATION_ID,
        buildNotification(context, message, isProgress = true),
    )
  }

  fun updateDownload(context: Context, message: String) {
    ensureNotificationChannel(context)
    NotificationManagerCompat.from(context).notify(
        NOTIFICATION_ID,
        buildNotification(context, message, isProgress = true),
    )
  }

  fun completeDownload(context: Context, message: String) {
    ensureNotificationChannel(context)
    NotificationManagerCompat.from(context).notify(
        NOTIFICATION_ID,
        buildNotification(
            context,
            message,
            isProgress = false,
        ),
    )
  }

  fun failDownload(context: Context, errorMessage: String) {
    ensureNotificationChannel(context)
    NotificationManagerCompat.from(context).notify(
        NOTIFICATION_ID,
        buildNotification(
            context,
            errorMessage,
            isProgress = false,
        ),
    )
  }

  fun cancelDownload(context: Context) {
    NotificationManagerCompat.from(context).cancel(NOTIFICATION_ID)
  }
}
