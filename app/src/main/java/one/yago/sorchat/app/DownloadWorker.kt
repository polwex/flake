package one.yago.sorchat.app

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import java.util.concurrent.TimeUnit

/**
 * Downloads one received attachment in the background, retrying (with backoff) until it works
 * or the server says the file is gone. WorkManager keeps it going across app restarts.
 */
class DownloadWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val messageId = inputData.getString(KEY_MESSAGE) ?: return Result.failure()
        return try {
            ChatRepository.get(applicationContext).downloadAttachment(messageId)
            Result.success()
        } catch (e: Exception) {
            if (runAttemptCount < 8) Result.retry() else Result.failure()
        }
    }

    companion object {
        private const val KEY_MESSAGE = "message"

        fun enqueue(context: Context, messageId: String) {
            val request = OneTimeWorkRequestBuilder<DownloadWorker>()
                .setInputData(workDataOf(KEY_MESSAGE to messageId))
                .setConstraints(Constraints(requiredNetworkType = NetworkType.CONNECTED))
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 10, TimeUnit.SECONDS)
                .build()
            WorkManager.getInstance(context).enqueueUniqueWork("download-$messageId", ExistingWorkPolicy.KEEP, request)
        }
    }
}
