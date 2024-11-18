package pl.zarajczyk.familyrulesandroid.scheduled

import android.content.Context
import android.util.Log
import androidx.work.PeriodicWorkRequest
import androidx.work.WorkManager
import androidx.work.Worker
import androidx.work.WorkerParameters
import java.util.concurrent.TimeUnit

class KeepAliveWorker(context: Context, params: WorkerParameters) : Worker(context, params) {
    override fun doWork(): Result {
        Log.i("KeepAliveWorker", "I'm alive!")
        return Result.success()
    }

    companion object {
        fun install(context: Context) {
            val workRequest: PeriodicWorkRequest =
                PeriodicWorkRequest.Builder(KeepAliveWorker::class.java, 15, TimeUnit.MINUTES)
                    .build()

            WorkManager.getInstance(context).enqueue(workRequest)
        }
    }
}