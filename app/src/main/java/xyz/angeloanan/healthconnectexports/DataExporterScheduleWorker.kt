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
import androidx.health.connect.client.records.ExerciseSessionRecord
import androidx.health.connect.client.records.HeartRateRecord
import androidx.health.connect.client.records.SleepSessionRecord
import androidx.health.connect.client.records.StepsRecord
import androidx.health.connect.client.records.TotalCaloriesBurnedRecord
import androidx.health.connect.client.records.WeightRecord
import androidx.health.connect.client.request.ReadRecordsRequest
import androidx.health.connect.client.time.TimeRangeFilter
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.google.gson.Gson
import java.time.Instant
import java.time.ZoneOffset

val requiredHealthConnectPermissions = setOf(
    HealthPermission.getReadPermission(StepsRecord::class),
    HealthPermission.getReadPermission(SleepSessionRecord::class),
    HealthPermission.getReadPermission(ActiveCaloriesBurnedRecord::class),
    HealthPermission.getReadPermission(TotalCaloriesBurnedRecord::class),
    HealthPermission.getReadPermission(HeartRateRecord::class),
    HealthPermission.getReadPermission(WeightRecord::class),
    HealthPermission.getReadPermission(ExerciseSessionRecord::class),
)

class DataExporterScheduleWorker(
    appContext: Context,
    workerParams: WorkerParameters
) : CoroutineWorker(appContext, workerParams) {
    private val notificationManager = applicationContext.getSystemService<NotificationManager>()!!
    private val healthConnect = HealthConnectClient.getOrCreate(applicationContext)

    private fun sleepStageName(stage: Int): String {
        return when (stage) {
            SleepSessionRecord.STAGE_TYPE_AWAKE -> "AWAKE"
            SleepSessionRecord.STAGE_TYPE_SLEEPING -> "SLEEPING"
            SleepSessionRecord.STAGE_TYPE_OUT_OF_BED -> "OUT_OF_BED"
            SleepSessionRecord.STAGE_TYPE_LIGHT -> "LIGHT"
            SleepSessionRecord.STAGE_TYPE_DEEP -> "DEEP"
            SleepSessionRecord.STAGE_TYPE_REM -> "REM"
            SleepSessionRecord.STAGE_TYPE_AWAKE_IN_BED -> "AWAKE_IN_BED"
            SleepSessionRecord.STAGE_TYPE_UNKNOWN -> "UNKNOWN"
            else -> "UNRECOGNIZED"
        }
    }

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
        Log.d(
            "DataExporterWorker",
            "Loaded last_export_timestamp=$lastExportTimestamp (raw=${prefs.all[LAST_EXPORT_TIMESTAMP_KEY]})"
        )
        val exportFrom = if (lastExportTimestamp > 0) {
            Instant.ofEpochMilli(lastExportTimestamp)
        } else {
            Instant.EPOCH
        }
        Log.d("DataExporterWorker", "Export range from=$exportFrom to=$exportTo")

        return try {
            val timeFilter = TimeRangeFilter.between(exportFrom, exportTo)

            val steps = healthConnect.readRecords(
                ReadRecordsRequest(
                    recordType = StepsRecord::class,
                    timeRangeFilter = timeFilter,
                )
            ).records.map {
                mapOf(
                    "start" to it.startTime.atOffset(ZoneOffset.UTC).toString(),
                    "end" to it.endTime.atOffset(ZoneOffset.UTC).toString(),
                    "count" to it.count,
                )
            }

            val activeCalories = healthConnect.readRecords(
                ReadRecordsRequest(
                    recordType = ActiveCaloriesBurnedRecord::class,
                    timeRangeFilter = timeFilter,
                )
            ).records.map {
                mapOf(
                    "start" to it.startTime.atOffset(ZoneOffset.UTC).toString(),
                    "end" to it.endTime.atOffset(ZoneOffset.UTC).toString(),
                    "kilocalories" to it.energy.inKilocalories,
                )
            }

            val totalCalories = healthConnect.readRecords(
                ReadRecordsRequest(
                    recordType = TotalCaloriesBurnedRecord::class,
                    timeRangeFilter = timeFilter,
                )
            ).records.map {
                mapOf(
                    "start" to it.startTime.atOffset(ZoneOffset.UTC).toString(),
                    "end" to it.endTime.atOffset(ZoneOffset.UTC).toString(),
                    "kilocalories" to it.energy.inKilocalories,
                )
            }

            val heartRate = healthConnect.readRecords(
                ReadRecordsRequest(
                    recordType = HeartRateRecord::class,
                    timeRangeFilter = timeFilter,
                )
            ).records.flatMap { record ->
                record.samples.map { sample ->
                    mapOf(
                        "time" to sample.time.atOffset(ZoneOffset.UTC).toString(),
                        "bpm" to sample.beatsPerMinute,
                    )
                }
            }

            val sleep = healthConnect.readRecords(
                ReadRecordsRequest(
                    recordType = SleepSessionRecord::class,
                    timeRangeFilter = timeFilter,
                )
            ).records.flatMap { record ->
                record.stages.map { stage ->
                    mapOf(
                        "start" to stage.startTime.atOffset(ZoneOffset.UTC).toString(),
                        "end" to stage.endTime.atOffset(ZoneOffset.UTC).toString(),
                        "stage" to sleepStageName(stage.stage),
                    )
                }
            }

            val weight = healthConnect.readRecords(
                ReadRecordsRequest(
                    recordType = WeightRecord::class,
                    timeRangeFilter = timeFilter,
                )
            ).records.map {
                mapOf(
                    "time" to it.time.atOffset(ZoneOffset.UTC).toString(),
                    "kilograms" to it.weight.inKilograms,
                )
            }

            val exercise = healthConnect.readRecords(
                ReadRecordsRequest(
                    recordType = ExerciseSessionRecord::class,
                    timeRangeFilter = timeFilter,
                )
            ).records.map {
                mapOf(
                    "start" to it.startTime.atOffset(ZoneOffset.UTC).toString(),
                    "end" to it.endTime.atOffset(ZoneOffset.UTC).toString(),
                    "exercise_type" to it.exerciseType,
                    "title" to it.title,
                    "notes" to it.notes,
                )
            }

            val json = Gson().toJson(
                mapOf(
                    "from" to exportFrom.toEpochMilli(),
                    "to" to exportTo.toEpochMilli(),
                    "steps" to steps,
                    "active_calories" to activeCalories,
                    "total_calories" to totalCalories,
                    "heart_rate" to heartRate,
                    "sleep" to sleep,
                    "weight" to weight,
                    "exercise" to exercise,
                )
            )
            val fileName = buildExportFilename(exportFrom, exportTo)
            val didWrite = writeExportJsonToFolder(applicationContext, exportFolderUri, fileName, json)

            notificationManager.cancel(1)
            if (!didWrite) {
                return Result.failure()
            }

            val saved = prefs.edit().putLong(LAST_EXPORT_TIMESTAMP_KEY, exportTo.toEpochMilli()).commit()
            val verifiedTimestamp = prefs.getLong(LAST_EXPORT_TIMESTAMP_KEY, -1)
            Log.d(
                "DataExporterWorker",
                "Saved last_export_timestamp=${exportTo.toEpochMilli()} success=$saved verified=$verifiedTimestamp"
            )
            Result.success()
        } catch (e: Exception) {
            Log.e("DataExporterWorker", "Failed to export data", e)
            notificationManager.cancel(1)
            notificationManager.notify(1, createExceptionNotification(e))
            Result.failure()
        }
    }
}
