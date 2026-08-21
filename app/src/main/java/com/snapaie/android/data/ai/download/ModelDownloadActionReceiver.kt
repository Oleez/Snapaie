package com.snapaie.android.data.ai.download

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.snapaie.android.SnapAieApplication

/** Handles the Pause / Cancel actions on the download notification. */
class ModelDownloadActionReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val controller = (context.applicationContext as? SnapAieApplication)
            ?.container
            ?.modelDownloadController
            ?: return
        when (intent.action) {
            ACTION_PAUSE -> controller.pause()
            ACTION_CANCEL -> controller.cancel()
        }
    }

    companion object {
        const val ACTION_PAUSE = "com.snapaie.android.action.MODEL_DOWNLOAD_PAUSE"
        const val ACTION_CANCEL = "com.snapaie.android.action.MODEL_DOWNLOAD_CANCEL"

        fun pendingIntent(context: Context, action: String, requestCode: Int): PendingIntent =
            PendingIntent.getBroadcast(
                context,
                requestCode,
                Intent(context, ModelDownloadActionReceiver::class.java)
                    .setAction(action)
                    .setPackage(context.packageName),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
    }
}
