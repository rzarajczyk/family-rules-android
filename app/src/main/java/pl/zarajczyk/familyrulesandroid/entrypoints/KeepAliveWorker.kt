package pl.zarajczyk.familyrulesandroid.entrypoints

import android.content.Context
import android.os.Build
import android.util.Log
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequest
import androidx.work.PeriodicWorkRequest
import androidx.work.WorkManager
import androidx.work.Worker
import androidx.work.WorkerParameters
import pl.zarajczyk.familyrulesandroid.core.FamilyRulesCoreService
import java.util.concurrent.TimeUnit
import kotlin.time.Duration

class KeepAliveWorker(private val context: Context, params: WorkerParameters) :
    Worker(context, params) {
    
    override fun doWork(): Result {
        if (!FamilyRulesCoreService.isServiceRunning(context)) {
            FamilyRulesCoreService.install(context)
        }
        
        return Result.success()
    }

    companion object {
        private const val TAG = "KeepAliveWorker"
        private const val WORK_NAME = "family_rules_keep_alive"
        
        fun install(context: Context, delayDuration: Duration) {
            val workRequest: PeriodicWorkRequest =
                PeriodicWorkRequest.Builder(
                    KeepAliveWorker::class.java,
                    delayDuration.inWholeMinutes,
                    TimeUnit.MINUTES
                )
                    .build()

            // Use unique work to prevent multiple instances
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                WORK_NAME,
                ExistingPeriodicWorkPolicy.KEEP,
                workRequest
            )
            Log.d(TAG, "KeepAliveWorker scheduled with unique work policy")
        }
        
        fun scheduleImmediateWork(context: Context) {
            // Schedule one-time immediate work when task is removed
            val workRequest = OneTimeWorkRequest.Builder(KeepAliveWorker::class.java)
                .build()
                
            WorkManager.getInstance(context).enqueueUniqueWork(
                "${WORK_NAME}_immediate",
                ExistingWorkPolicy.REPLACE,
                workRequest
            )
            Log.d(TAG, "Immediate KeepAliveWorker scheduled")
        }
    }
}