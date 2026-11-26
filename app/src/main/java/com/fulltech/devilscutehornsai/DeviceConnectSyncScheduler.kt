package com.fulltech.devilscutehornsai

import android.content.Context
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.workDataOf
import java.util.concurrent.TimeUnit

internal object DeviceConnectSyncScheduler {

    private const val RECEIVER_WORK = "dch-device-connect-receiver"
    private const val DELIVERY_MONITOR_WORK = "dch-device-connect-delivery-monitor"
    private const val SENDER_PREFIX = "dch-device-connect-sender-"

    private fun networkConstraints() = Constraints.Builder()
        .setRequiredNetworkType(NetworkType.CONNECTED)
        .build()

    fun enqueueSender(context: Context, sessionId: String) {
        if (sessionId.isBlank()) return

        val request = OneTimeWorkRequestBuilder<DeviceConnectSendWorker>()
            .setInputData(workDataOf(DeviceConnectSendWorker.KEY_SESSION_ID to sessionId))
            .setConstraints(networkConstraints())
            .setBackoffCriteria(
                androidx.work.BackoffPolicy.EXPONENTIAL,
                30,
                TimeUnit.SECONDS
            )
            .build()

        WorkManager.getInstance(context.applicationContext).enqueueUniqueWork(
            SENDER_PREFIX + sessionId,
            ExistingWorkPolicy.KEEP,
            request
        )

        ensureReceiverScheduled(context)
    }

    fun enqueueReceiverNow(context: Context) {
        val request = OneTimeWorkRequestBuilder<DeviceConnectReceiveWorker>()
            .setConstraints(networkConstraints())
            .setBackoffCriteria(
                androidx.work.BackoffPolicy.EXPONENTIAL,
                30,
                TimeUnit.SECONDS
            )
            .build()

        WorkManager.getInstance(context.applicationContext).enqueueUniqueWork(
            "$RECEIVER_WORK-immediate",
            ExistingWorkPolicy.KEEP,
            request
        )

        ensureReceiverScheduled(context)
    }

    fun ensureReceiverScheduled(context: Context) {
        val monitorRequest =
            PeriodicWorkRequestBuilder<DeviceConnectDeliveryMonitorWorker>(
                15,
                TimeUnit.MINUTES
            )
                .setConstraints(networkConstraints())
                .build()

        WorkManager.getInstance(context.applicationContext)
            .enqueueUniquePeriodicWork(
                DELIVERY_MONITOR_WORK,
                ExistingPeriodicWorkPolicy.KEEP,
                monitorRequest
            )

        val request =
            PeriodicWorkRequestBuilder<DeviceConnectReceiveWorker>(
                15,
                TimeUnit.MINUTES
            )
                .setConstraints(networkConstraints())
                .setBackoffCriteria(
                    androidx.work.BackoffPolicy.EXPONENTIAL,
                    30,
                    TimeUnit.SECONDS
                )
                .build()

        WorkManager.getInstance(context.applicationContext)
            .enqueueUniquePeriodicWork(
                RECEIVER_WORK,
                ExistingPeriodicWorkPolicy.KEEP,
                request
            )
    }
}
