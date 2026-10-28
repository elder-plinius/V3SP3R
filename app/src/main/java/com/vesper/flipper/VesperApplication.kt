package com.vesper.flipper

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import dagger.hilt.android.HiltAndroidApp

@HiltAndroidApp
class VesperApplication : Application() {

    override fun onCreate() {
        super.onCreate()
        createNotificationChannels()
    }

    private fun createNotificationChannels() {
        val bleChannel = NotificationChannel(
            CHANNEL_BLE,
            getString(R.string.notification_channel_ble),
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = getString(R.string.notification_channel_ble_desc)
        }

        val notificationManager = getSystemService(NotificationManager::class.java)
        notificationManager.createNotificationChannel(bleChannel)
    }

    companion object {
        const val CHANNEL_BLE = "ble_connection"
    }
}
