package com.screentranslation.app.model.preparation

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.screentranslation.app.prefs.AppPreferences

class ModelPreparationActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val taskId = intent.getStringExtra(KEY_TASK_ID) ?: return
        val source = intent.getStringExtra(KEY_SOURCE_LANGUAGE) ?: return
        val target = intent.getStringExtra(KEY_TARGET_LANGUAGE) ?: return
        val coordinator = ModelPreparationCoordinator(context)
        when (intent.action) {
            ACTION_PAUSE -> coordinator.pause(taskId)
            ACTION_CANCEL -> coordinator.cancel(taskId)
            ACTION_RESUME -> coordinator.enqueue(
                sourceLanguage = source,
                targetLanguage = target,
                requireUnmeteredNetwork = AppPreferences(context).modelWifiOnly,
                replace = true,
            )
        }
    }

    companion object {
        const val ACTION_PAUSE = "com.screentranslation.app.action.PAUSE_MODEL_PREPARATION"
        const val ACTION_CANCEL = "com.screentranslation.app.action.CANCEL_MODEL_PREPARATION"
        const val ACTION_RESUME = "com.screentranslation.app.action.RESUME_MODEL_PREPARATION"

        fun pendingIntent(
            context: Context,
            action: String,
            taskId: String,
            sourceLanguage: String,
            targetLanguage: String,
        ): PendingIntent {
            val requestCode = (31 * action.hashCode() + taskId.hashCode()) and Int.MAX_VALUE
            return PendingIntent.getBroadcast(
                context,
                requestCode,
                Intent(context, ModelPreparationActionReceiver::class.java)
                    .setAction(action)
                    .putExtra(KEY_TASK_ID, taskId)
                    .putExtra(KEY_SOURCE_LANGUAGE, sourceLanguage)
                    .putExtra(KEY_TARGET_LANGUAGE, targetLanguage),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
        }
    }
}
