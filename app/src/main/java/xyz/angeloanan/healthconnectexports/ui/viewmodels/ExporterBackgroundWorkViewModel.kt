package xyz.angeloanan.healthconnectexports.ui.viewmodels

import android.app.Application
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequest
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import kotlinx.coroutines.guava.await
import xyz.angeloanan.healthconnectexports.DataExporterScheduleWorker
import java.time.Duration
import java.util.concurrent.TimeUnit

const val WORK_NAME = "HealthConnectWeeklyExporter"
const val WORK_NAME_ONCE = "HealthConnectExporter"

val dataExportRequest: PeriodicWorkRequest =
    PeriodicWorkRequestBuilder<DataExporterScheduleWorker>(
        repeatInterval = 7,
        repeatIntervalTimeUnit = TimeUnit.DAYS,
    )
        .setConstraints(
            Constraints.Builder()
                .setRequiresBatteryNotLow(true)
                .build()
        )
        .setBackoffCriteria(
            BackoffPolicy.LINEAR,
            Duration.ofMinutes(5),
        )
        .build()

class ExporterBackgroundWorkViewModel(application: Application) : AndroidViewModel(application) {
    private val workManager = WorkManager.getInstance(application)

    suspend fun isWorkScheduled(): Boolean {
        val workQuery = workManager.getWorkInfosForUniqueWork(WORK_NAME).await()
        Log.d(
            "ExporterBackgroundWorkViewModel",
            "Time until next work: ${workQuery.firstOrNull()?.nextScheduleTimeMillis}"
        )
        return workQuery.isNotEmpty()
    }

    fun scheduleWork() {
        workManager.enqueueUniquePeriodicWork(
            WORK_NAME,
            ExistingPeriodicWorkPolicy.KEEP,
            dataExportRequest
        )
        Log.d("ExporterBackgroundWorkViewModel", "Scheduled work")
    }

    fun cancelWork() {
        workManager.cancelUniqueWork(WORK_NAME)
    }
}
