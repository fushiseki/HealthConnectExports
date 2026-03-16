package xyz.angeloanan.healthconnectexports

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.getSystemService
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.permission.HealthPermission
import androidx.health.connect.client.records.ActiveCaloriesBurnedRecord
import androidx.health.connect.client.records.HeartRateRecord
import androidx.health.connect.client.records.SleepSessionRecord
import androidx.health.connect.client.records.StepsRecord
import androidx.health.connect.client.records.TotalCaloriesBurnedRecord
import androidx.health.connect.client.records.WeightRecord
import androidx.health.connect.client.request.AggregateRequest
import androidx.health.connect.client.time.TimeRangeFilter
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.google.gson.Gson
import java.time.Instant

val requiredHealthConnectPermissions = setOf(
    HealthPermission.getReadPermission(StepsRecord::class),
    HealthPermission.getReadPermission(SleepSessionRecord::class),
    HealthPermission.getReadPermission(ActiveCaloriesBurnedRecord::class),
    HealthPermission.getReadPermission(TotalCaloriesBurnedRecord::class),
    HealthPermission.getReadPermission(HeartRateRecord::class),
    HealthPermission.getReadPermission(WeightRecord::class),
)

class DataExporterScheduleWorker(
    appContext: Context,
    workerParams: WorkerParameters
) : CoroutineWorker(appContext, workerParams) {
    private val notificationManager = applicationContext.getSystemService<NotificationManager>()!!
    private val healthConnect = HealthConnectClient.getOrCreate(applicationContext)

    private fun createNotificationChannel(): NotificationChannel {
        val notificationChannel = NotificationChannel(
            "export",
            "Data export",
            NotificationManager.IMPORTANCE_LOW,
        )
        notificationChannel.description = "Shown when Health Connect data is being exported"
        notificationChannel.enableLights(false)
        notificationChannel.enableVibration(false)

        notificationManager.createNotificationChannel(notificationChannel)
        return notificationChannel
    }

    private fun createExceptionNotification(e: Exception): Notification {
        return NotificationCompat.Builder(applicationContext, "export")
            .setContentTitle("Export failed")
            .setContentText("Failed to export Health Connect data")
            .setStyle(NotificationCompat.BigTextStyle().bigText(e.message))
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .build()
    }

    private suspend fun isHealthConnectPermissionGranted(healthConnect: HealthConnectClient): Boolean {
        val grantedPermissions = healthConnect.permissionController.getGrantedPermissions()
        return requiredHealthConnectPermissions.all { it in grantedPermissions }
    }

    override suspend fun doWork(): Result {
        val notificationChannel = createNotificationChannel()

        val isGranted = isHealthConnectPermissionGranted(healthConnect)
        if (!isGranted) {
            Log.d("DataExporterWorker", "Health Connect permissions not granted")
            return Result.failure()
        }

        val prefs = applicationContext.exportPrefs()
        val exportFolderUri = prefs.getString(EXPORT_FOLDER_URI_KEY, null)
        if (exportFolderUri == null) {
            Log.d("DataExporterWorker", "Export folder not set")
            return Result.failure()
        }

        val foregroundNotification =
            NotificationCompat.Builder(applicationContext, notificationChannel.id)
                .setContentTitle("Exporting data")
                .setContentText("Exporting Health Connect data to local storage")
                .setSmallIcon(R.drawable.ic_launcher_foreground)
                .setOngoing(true)
                .build()

        notificationManager.notify(1, foregroundNotification)

        val exportTo = Instant.now()
        val lastExportTimestamp = prefs.getLong(LAST_EXPORT_TIMESTAMP_KEY, -1)
        val exportFrom = if (lastExportTimestamp > 0) {
            Instant.ofEpochMilli(lastExportTimestamp)
        } else {
            Instant.EPOCH
        }

        return try {
            val healthDataAggregate = healthConnect.aggregate(
                AggregateRequest(
                    metrics = setOf(
                        StepsRecord.COUNT_TOTAL,
                        ActiveCaloriesBurnedRecord.ACTIVE_CALORIES_TOTAL,
                        TotalCaloriesBurnedRecord.ENERGY_TOTAL,
                        SleepSessionRecord.SLEEP_DURATION_TOTAL,
                    ),
                    timeRangeFilter = TimeRangeFilter.between(exportFrom, exportTo),
                )
            )

            val jsonValues = HashMap<String, Number>()
            jsonValues["steps"] = healthDataAggregate[StepsRecord.COUNT_TOTAL] ?: 0
            jsonValues["active_calories"] =
                healthDataAggregate[ActiveCaloriesBurnedRecord.ACTIVE_CALORIES_TOTAL]?.inKilocalories
                    ?: 0
            jsonValues["total_calories"] =
                healthDataAggregate[TotalCaloriesBurnedRecord.ENERGY_TOTAL]?.inKilocalories ?: 0
            jsonValues["sleep_duration_seconds"] =
                healthDataAggregate[SleepSessionRecord.SLEEP_DURATION_TOTAL]?.seconds ?: 0

            val json = Gson().toJson(
                mapOf(
                    "from" to exportFrom.toEpochMilli(),
                    "to" to exportTo.toEpochMilli(),
                    "data" to jsonValues,
                )
            )
            val fileName = buildExportFilename(exportFrom, exportTo)
            val didWrite = writeExportJsonToFolder(applicationContext, exportFolderUri, fileName, json)

            notificationManager.cancel(1)
            if (!didWrite) {
                return Result.failure()
            }

            prefs.edit().putLong(LAST_EXPORT_TIMESTAMP_KEY, exportTo.toEpochMilli()).apply()
            Result.success()
        } catch (e: Exception) {
            Log.e("DataExporterWorker", "Failed to export data", e)
            notificationManager.cancel(1)
            notificationManager.notify(1, createExceptionNotification(e))
            Result.failure()
        }
    }
}
