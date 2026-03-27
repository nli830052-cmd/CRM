package com.example.crm
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.ExistingWorkPolicy

class SmsReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == "android.provider.Telephony.SMS_RECEIVED") {
            Log.i("SmsReceiver", "SMS received. Triggering sync...")
            val req = OneTimeWorkRequestBuilder<SyncWorker>().addTag("sync").build()
            WorkManager.getInstance(context).enqueueUniqueWork(
                "sync",
                ExistingWorkPolicy.REPLACE,
                req
            )
        }
    }
}
