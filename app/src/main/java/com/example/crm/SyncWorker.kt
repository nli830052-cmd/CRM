package com.example.crm

import android.content.Context
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.ListenableWorker
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import android.database.Cursor
import com.example.crm.network.RetrofitClient
import com.example.crm.models.*
import android.provider.CallLog
import android.provider.ContactsContract
import android.net.Uri
import java.text.SimpleDateFormat
import java.util.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import android.os.Environment
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.MultipartBody
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import java.security.MessageDigest

class SyncWorker(appContext: Context, workerParams: WorkerParameters)
    : CoroutineWorker(appContext, workerParams) {

    private val serverDateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())

    @Suppress("UNCHECKED_CAST")
    override suspend fun doWork(): ListenableWorker.Result {
        return withContext(Dispatchers.IO) {
            try {
                setProgress(workDataOf("status" to "서버 정보 대조 중..."))
                
                // 1. Fetch Server Status
                val statsResponse = RetrofitClient.apiService.getContactsStats()
                if (!statsResponse.isSuccessful) return@withContext ListenableWorker.Result.retry()
                
                val initialStats = statsResponse.body() ?: emptyList()
                var phoneToData = initialStats.associateBy(
                    { stat -> normalizePhone((stat as Map<String, Any>)["phone_number"]?.toString() ?: "") },
                    { stat -> stat as Map<String, Any> }
                ).toMutableMap()

                // 2. Sync Contacts
                setProgress(workDataOf("status" to "신규 연락처 동기화 중..."))
                val localContacts = getAllContacts()
                val newContacts = localContacts.filter { 
                    phoneToData[normalizePhone(it.phone_number)] == null 
                }.map { it.copy(phone_number = normalizePhone(it.phone_number)) }
                
                if (newContacts.isNotEmpty()) {
                    newContacts.chunked(50).forEach { chunk ->
                        RetrofitClient.apiService.createContactsBulk(chunk)
                    }
                    val refreshStats = RetrofitClient.apiService.getContactsStats()
                    if (refreshStats.isSuccessful) {
                        phoneToData = (refreshStats.body() ?: emptyList()).associateBy(
                            { it -> normalizePhone((it as Map<String, Any>)["phone_number"]?.toString() ?: "") }, 
                            { it -> it as Map<String, Any> }
                        ).toMutableMap()
                    }
                }

                // 3. Sync Calls
                setProgress(workDataOf("status" to "최신 통화 기록 분석 중..."))
                val allCalls = getAllCallLogs()
                val newCallRecords = mutableListOf<CallRecord>()
                allCalls.forEach { call ->
                    val contactData = phoneToData[normalizePhone(call.first)]
                    val lastCallOnServer = contactData?.get("last_call_at")?.toString()
                    val localCallAt = serverDateFormat.format(Date(call.third))
                    if (contactData != null && (lastCallOnServer == null || localCallAt > lastCallOnServer)) {
                        newCallRecords.add(CallRecord(
                            duration = call.second,
                            timestamp = localCallAt,
                            contact_id = contactData["id"]?.toString(),
                            phone_number = call.first,
                            direction = call.fourth
                        ))
                    }
                }
                if (newCallRecords.isNotEmpty()) {
                    newCallRecords.chunked(100).forEach { RetrofitClient.apiService.logCallsBulk(it) }
                }

                // 4. Sync SMS
                setProgress(workDataOf("status" to "새 문자 메시지 동기화 중..."))
                val allMsgs = getAllSMS()
                val newMsgRecords = mutableListOf<MessageRecord>()
                allMsgs.forEach { msg ->
                    val contactData = phoneToData[normalizePhone(msg.address)]
                    val lastMsgOnServer = contactData?.get("last_message_at")?.toString()
                    val localMsgAt = serverDateFormat.format(Date(msg.dateLong))
                    if (contactData != null && (lastMsgOnServer == null || localMsgAt > lastMsgOnServer)) {
                        newMsgRecords.add(MessageRecord(
                            content = msg.body,
                            timestamp = localMsgAt,
                            contact_id = contactData["id"]?.toString(),
                            phone_number = msg.address,
                            direction = msg.direction
                        ))
                    }
                }
                if (newMsgRecords.isNotEmpty()) {
                     newMsgRecords.chunked(100).forEach { RetrofitClient.apiService.logMessagesBulk(it) }
                }

                // 5. Sync recordings
                syncRecordings(phoneToData)

                Log.i("SyncWorker", "Background sync completed successfully!")
                ListenableWorker.Result.success()
            } catch (e: Exception) {
                Log.e("SyncWorker", "Sync failed: ${e.message}")
                ListenableWorker.Result.failure()
            }
        }
    }

    private suspend fun syncRecordings(phoneToData: Map<String, Map<String, Any>>) {
        val recordingsDir = File(Environment.getExternalStorageDirectory(), "Recordings/Call")
        if (!recordingsDir.exists()) return
        
        val files = recordingsDir.listFiles { f -> f.isFile && (f.extension == "m4a" || f.extension == "amr" || f.extension == "mp3") } ?: return
        
        setProgress(workDataOf("status" to "동기화된 파일 분류 중..."))
        
        val phoneToSyncedSet = phoneToData.mapValues { entry ->
            @Suppress("UNCHECKED_CAST")
            (entry.value["synced_recordings"] as? List<String>)?.toHashSet() ?: hashSetOf()
        }

        // Filter files that are NOT synced yet
        val unsyncedFiles = files.filter { file ->
            val phone = extractPhoneNumber(file.name) ?: return@filter false
            val cleanPhone = normalizePhone(phone)
            phoneToSyncedSet[cleanPhone]?.contains(file.name) != true
        }

        val totalToUpload = unsyncedFiles.size
        if (totalToUpload == 0) {
            setProgress(workDataOf("status" to "모든 녹음 파일이 이미 업로드되었습니다."))
            return
        }

        unsyncedFiles.forEachIndexed { index, file ->
            val progress = "신규 녹음 파일 업로드 중: ${index + 1} / ${totalToUpload}개"
            setProgress(workDataOf("status" to progress))
            
            val phone = extractPhoneNumber(file.name) ?: return@forEachIndexed
            val cleanPhone = normalizePhone(phone)
            
            try {
                val reqFile = file.asRequestBody("audio/*".toMediaTypeOrNull())
                val body = MultipartBody.Part.createFormData("file", file.name, reqFile)
                val phonePartReq = cleanPhone.toRequestBody("text/plain".toMediaTypeOrNull())
                val namePartReq = (extractContactName(file.name) ?: "Scanner").toRequestBody("text/plain".toMediaTypeOrNull())
                
                RetrofitClient.apiService.uploadRecording(body, phonePartReq, namePartReq)
            } catch (e: Exception) {
                Log.e("SyncWorker", "File upload failed: ${file.name}")
            }
        }
        setProgress(workDataOf("status" to "${totalToUpload}개의 최신 파일 동기화 완료!"))
    }

    private fun normalizePhone(number: String): String {
        val clean = number.replace(Regex("[^0-9]"), "")
        return when {
            clean.startsWith("8210") -> "010" + clean.substring(4)
            clean.startsWith("10") && clean.length >= 10 -> "0" + clean
            else -> clean
        }
    }

    private fun getAllContacts(): List<Contact> {
        val list = mutableListOf<Contact>()
        val it = applicationContext.contentResolver.query(ContactsContract.CommonDataKinds.Phone.CONTENT_URI, null, null, null, null)
        it?.use { cursor ->
            val nameIdx = cursor.getColumnIndex(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME)
            val numIdx = cursor.getColumnIndex(ContactsContract.CommonDataKinds.Phone.NUMBER)
            while (cursor.moveToNext()) {
                val name = cursor.getString(nameIdx) ?: "Unknown"
                val number = cursor.getString(numIdx) ?: ""
                list.add(Contact(name = name, phone_number = number.replace(Regex("[^0-9]"), "")))
            }
        }
        return list
    }

    private fun getAllCallLogs(): List<Quadruple<String, Int, Long, String>> {
        val list = mutableListOf<Quadruple<String, Int, Long, String>>()
        val it = applicationContext.contentResolver.query(CallLog.Calls.CONTENT_URI, null, null, null, "${CallLog.Calls.DATE} DESC")
        it?.use { cursor ->
            val numIdx = cursor.getColumnIndex(CallLog.Calls.NUMBER)
            val durIdx = cursor.getColumnIndex(CallLog.Calls.DURATION)
            val dateIdx = cursor.getColumnIndex(CallLog.Calls.DATE)
            val typeIdx = cursor.getColumnIndex(CallLog.Calls.TYPE)
            while (cursor.moveToNext()) {
                val num = cursor.getString(numIdx) ?: ""
                val dur = cursor.getInt(durIdx)
                val date = cursor.getLong(dateIdx)
                val type = cursor.getInt(typeIdx)
                val dir = if (type == CallLog.Calls.INCOMING_TYPE) "IN" else "OUT"
                list.add(Quadruple(num, dur, date, dir))
            }
        }
        return list
    }

    private fun getAllSMS(): List<SmsItem> {
        val list = mutableListOf<SmsItem>()
        val it = applicationContext.contentResolver.query(Uri.parse("content://sms"), null, null, null, "date DESC")
        it?.use { cursor ->
            val addrIdx = cursor.getColumnIndex("address")
            val bodyIdx = cursor.getColumnIndex("body")
            val typeIdx = cursor.getColumnIndex("type")
            val dateIdx = cursor.getColumnIndex("date")
            while (cursor.moveToNext()) {
                val dir = if (cursor.getInt(typeIdx) == 1) "INBOX" else "SENT"
                list.add(SmsItem(address = cursor.getString(addrIdx) ?: "", body = cursor.getString(bodyIdx) ?: "", direction = dir, dateLong = cursor.getLong(dateIdx)))
            }
        }
        return list
    }

    private fun extractPhoneNumber(filename: String): String? {
        val clean = filename.replace(Regex("[^0-9a-zA-Z]"), "")
        Regex("01[016789][0-9]{7,8}").find(clean)?.value?.let { return it }
        Regex("02[0-9]{7,8}").find(clean)?.value?.let { return it }
        Regex("0[3-9][0-9]{8,9}").find(clean)?.value?.let { return it }
        Regex("[0-9]{9,11}").find(clean)?.value?.let { return it }
        return null
    }

    private fun extractContactName(filename: String): String? {
        var name = filename.substringBeforeLast(".")
        name = name.removePrefix("통화 녹음 ").removePrefix("통화녹음 ")
        name = name.replace(Regex("_\\d{6,8}_\\d{4,6}$"), "").trim()
        val digitCount = name.count { it.isDigit() }
        if (digitCount > name.length / 2) return null
        return name.trim().ifEmpty { null }
    }
}
