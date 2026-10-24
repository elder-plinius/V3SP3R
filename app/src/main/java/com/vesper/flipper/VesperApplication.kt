package com.vesper.flipper

import android.app.Application
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.os.Build
import dagger.hilt.android.HiltAndroidApp

@HiltAndroidApp
class VesperApplication : Application() {

    override fun onCreate() {
        super.onCreate()
        createNotificationChannels()
    }

    /**
     * Creates the single, low-importance channel used by the BLE foreground
     * service. The channel stays silent, has no badge and is private on the lock
     * screen so an active connection never becomes an intrusive notification.
     */
    private fun createNotificationChannels() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val bleChannel = NotificationChannel(
            CHANNEL_BLE,
            getString(R.string.notification_channel_ble),
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = getString(R.string.notification_channel_ble_desc)
            setShowBadge(false)
            setSound(null, null)
            enableVibration(false)
            enableLights(false)
            lockscreenVisibility = Notification.VISIBILITY_PRIVATE
        }

        val notificationManager = getSystemService(NotificationManager::class.java)
        notificationManager?.createNotificationChannel(bleChannel)
    }

    companion object {
        /** Low-importance channel for the Flipper BLE foreground session. */
        const val CHANNEL_BLE = "ble_connection"
    }
}
