package com.example.crm
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.telephony.TelephonyManager
import android.util.Log
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.ExistingWorkPolicy

class CallReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == TelephonyManager.ACTION_PHONE_STATE_CHANGED) {
            val state = intent.getStringExtra(TelephonyManager.EXTRA_STATE)
            if (state == TelephonyManager.EXTRA_STATE_IDLE) {
                Log.i("CallReceiver", "Call ended. Triggering sync...")
                val req = OneTimeWorkRequestBuilder<SyncWorker>().addTag("sync").build()
                WorkManager.getInstance(context).enqueueUniqueWork(
                    "sync",
                    ExistingWorkPolicy.REPLACE,
                    req
                )
            }
        }
    }
}
