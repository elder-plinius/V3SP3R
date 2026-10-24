package com.vesper.flipper.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Base64
import android.widget.RemoteViews
import com.vesper.flipper.MainActivity
import com.vesper.flipper.R
import java.security.MessageDigest
import java.security.SecureRandom

/**
 * Signal quick-launch widget.
 *
 * A slot tap opens the Arsenal screen in the app. The BLE service has no
 * action that executes a stored signal, so the widget does not broadcast a
 * command it cannot fulfil; it opens the surface where the signal can be
 * triggered with the normal approval flow.
 *
 * Security: the receiver is exported by the widget framework, so every
 * non-system action must carry a per-install token that only this app can
 * read. Unknown actions and unauthenticated senders are ignored.
 */
class SignalWidget : AppWidgetProvider() {

    override fun onUpdate(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetIds: IntArray
    ) {
        for (appWidgetId in appWidgetIds) {
            updateAppWidget(context, appWidgetManager, appWidgetId)
        }
    }

    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            AppWidgetManager.ACTION_APPWIDGET_UPDATE,
            AppWidgetManager.ACTION_APPWIDGET_ENABLED,
            AppWidgetManager.ACTION_APPWIDGET_DISABLED,
            AppWidgetManager.ACTION_APPWIDGET_DELETED,
            AppWidgetManager.ACTION_APPWIDGET_OPTIONS_CHANGED,
            AppWidgetManager.ACTION_APPWIDGET_RESTORED -> super.onReceive(context, intent)

            ACTION_REFRESH -> {
                if (!isTrustedAction(context, intent)) return

                val appWidgetManager = AppWidgetManager.getInstance(context)
                val requestedId = intent.getIntExtra(
                    AppWidgetManager.EXTRA_APPWIDGET_ID,
                    AppWidgetManager.INVALID_APPWIDGET_ID
                )
                val appWidgetIds = if (requestedId != AppWidgetManager.INVALID_APPWIDGET_ID) {
                    intArrayOf(requestedId)
                } else {
                    appWidgetManager.getAppWidgetIds(ComponentName(context, SignalWidget::class.java))
                }
                onUpdate(context, appWidgetManager, appWidgetIds)
            }

            else -> Unit
        }
    }

    override fun onDeleted(context: Context, appWidgetIds: IntArray) {
        super.onDeleted(context, appWidgetIds)
        appWidgetIds.forEach { appWidgetId ->
            context.deleteSharedPreferences(widgetPreferencesName(appWidgetId))
        }
    }

    private fun isTrustedAction(context: Context, intent: Intent): Boolean {
        val supplied = intent.getStringExtra(EXTRA_ACTION_TOKEN) ?: return false
        val expected = actionToken(context)
        return MessageDigest.isEqual(
            supplied.toByteArray(Charsets.UTF_8),
            expected.toByteArray(Charsets.UTF_8)
        )
    }

    companion object {
        const val ACTION_REFRESH = "com.vesper.flipper.ACTION_REFRESH_WIDGET"
        const val EXTRA_ACTION_TOKEN = "widget_action_token"
        const val EXTRA_SIGNAL_ID = "signal_id"
        const val EXTRA_SIGNAL_TYPE = "signal_type"

        const val SLOT_COUNT = 4

        private const val ACTION_PREFS = "vesper_widget_actions"
        private const val ACTION_TOKEN_KEY = "action_token"
        private const val DEEP_LINK_ARSENAL = "vesper://open/arsenal"

        fun widgetPreferencesName(appWidgetId: Int): String = "widget_" + appWidgetId

        fun updateAppWidget(
            context: Context,
            appWidgetManager: AppWidgetManager,
            appWidgetId: Int
        ) {
            val views = RemoteViews(context.packageName, R.layout.widget_signal)
            val savedSignals = loadWidgetSignals(context, appWidgetId)

            savedSignals.forEachIndexed { index, signal ->
                val buttonId = buttonIdForSlot(index)
                if (signal != null) {
                    views.setTextViewText(buttonId, signal.name)
                    views.setOnClickPendingIntent(
                        buttonId,
                        slotPendingIntent(context, appWidgetId, index, signal)
                    )
                } else {
                    views.setTextViewText(buttonId, context.getString(slotLabelRes(index)))
                    views.setOnClickPendingIntent(
                        buttonId,
                        slotPendingIntent(context, appWidgetId, index, null)
                    )
                }
            }

            views.setOnClickPendingIntent(
                R.id.btn_refresh,
                refreshPendingIntent(context, appWidgetId)
            )

            appWidgetManager.updateAppWidget(appWidgetId, views)
        }

        fun loadWidgetSignals(context: Context, appWidgetId: Int): List<WidgetSignal?> {
            val prefs = context.getSharedPreferences(
                widgetPreferencesName(appWidgetId),
                Context.MODE_PRIVATE
            )
            return (0 until SLOT_COUNT).map { index ->
                val id = prefs.getString("signal_" + index + "_id", null)
                val name = prefs.getString("signal_" + index + "_name", null)
                val type = prefs.getString("signal_" + index + "_type", null)
                if (id != null && name != null && type != null) {
                    WidgetSignal(id, name, type)
                } else {
                    null
                }
            }
        }

        fun saveWidgetSignals(
            context: Context,
            appWidgetId: Int,
            signals: List<WidgetSignal?>
        ) {
            val prefs = context.getSharedPreferences(
                widgetPreferencesName(appWidgetId),
                Context.MODE_PRIVATE
            )
            val editor = prefs.edit()
            for (index in 0 until SLOT_COUNT) {
                val signal = signals.getOrNull(index)
                if (signal == null) {
                    editor.remove("signal_" + index + "_id")
                    editor.remove("signal_" + index + "_name")
                    editor.remove("signal_" + index + "_type")
                } else {
                    editor.putString("signal_" + index + "_id", signal.id)
                    editor.putString("signal_" + index + "_name", signal.name)
                    editor.putString("signal_" + index + "_type", signal.type)
                }
            }
            editor.apply()
        }

        private fun slotPendingIntent(
            context: Context,
            appWidgetId: Int,
            index: Int,
            signal: WidgetSignal?
        ): PendingIntent {
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(DEEP_LINK_ARSENAL), context, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            if (signal != null) {
                intent.putExtra(EXTRA_SIGNAL_ID, signal.id)
                intent.putExtra(EXTRA_SIGNAL_TYPE, signal.type)
            }
            return PendingIntent.getActivity(
                context,
                appWidgetId * 10 + index,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
        }

        private fun refreshPendingIntent(context: Context, appWidgetId: Int): PendingIntent {
            val intent = Intent(context, SignalWidget::class.java).apply {
                action = ACTION_REFRESH
                putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, appWidgetId)
                putExtra(EXTRA_ACTION_TOKEN, actionToken(context))
            }
            return PendingIntent.getBroadcast(
                context,
                appWidgetId * 10 + 9,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
        }

        private fun actionToken(context: Context): String {
            val prefs = context.getSharedPreferences(ACTION_PREFS, Context.MODE_PRIVATE)
            val existing = prefs.getString(ACTION_TOKEN_KEY, null)
            if (!existing.isNullOrBlank()) return existing

            val bytes = ByteArray(24)
            SecureRandom().nextBytes(bytes)
            val token = Base64.encodeToString(bytes, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)
            prefs.edit().putString(ACTION_TOKEN_KEY, token).apply()
            return token
        }

        private fun buttonIdForSlot(index: Int): Int {
            return when (index) {
                0 -> R.id.btn_signal_1
                1 -> R.id.btn_signal_2
                2 -> R.id.btn_signal_3
                else -> R.id.btn_signal_4
            }
        }

        private fun slotLabelRes(index: Int): Int {
            return when (index) {
                0 -> R.string.widget_signal_1
                1 -> R.string.widget_signal_2
                2 -> R.string.widget_signal_3
                else -> R.string.widget_signal_4
            }
        }
    }
}

data class WidgetSignal(
    val id: String,
    val name: String,
    val type: String
)
