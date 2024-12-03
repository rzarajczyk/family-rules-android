package pl.zarajczyk.familyrulesandroid.entrypoints

import android.content.Context
import android.util.Log
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
        Log.i("KeepAliveWorker", "I'm alive!")
        FamilyRulesCoreService.install(context)
        return Result.success()
    }

    companion object {
        fun install(context: Context, delayDuration: Duration) {
            val workRequest: PeriodicWorkRequest =
                PeriodicWorkRequest.Builder(
                    KeepAliveWorker::class.java,
                    delayDuration.inWholeMinutes,
                    TimeUnit.MINUTES
                )
                    .build()

            WorkManager.getInstance(context).enqueue(workRequest)
        }
    }
}