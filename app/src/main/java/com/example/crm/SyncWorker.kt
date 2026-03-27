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
                setProgress(workDataOf("status" to "연락처 맵핑 데이터 로드 중..."))
                
                // 1. Fetch Lightweight mapping (Phone -> ID)
                val mapRes = RetrofitClient.apiService.getContactsMap()
                if (!mapRes.isSuccessful) return@withContext ListenableWorker.Result.retry()
                
                var phoneToId = mapRes.body()?.associate { 
                    normalizePhone(it["phone_number"] ?: "") to (it["id"] ?: "")
                }?.toMutableMap() ?: mutableMapOf()

                // 2. Sync Missing Contacts
                setProgress(workDataOf("status" to "신규 연락처 확인 중..."))
                val localContacts = getAllContacts()
                val missingContacts = localContacts.filter { 
                    phoneToId[normalizePhone(it.phone_number)] == null 
                }.map { it.copy(phone_number = normalizePhone(it.phone_number)) }
                
                if (missingContacts.isNotEmpty()) {
                    setProgress(workDataOf("status" to "신규 연락처 ${missingContacts.size}건 등록 중..."))
                    missingContacts.chunked(50).forEach { chunk ->
                        val res = RetrofitClient.apiService.createContactsBulk(chunk)
                        res.body()?.forEach { phoneToId[normalizePhone(it.phone_number)] = it.id ?: "" }
                    }
                }

                // 3. Get Global Cutoff Timestamps
                setProgress(workDataOf("status" to "동기화 지점 계산 중..."))
                val tsRes = RetrofitClient.apiService.getLastSyncTimestamps()
                val lastTimestamps = tsRes.body() ?: emptyMap()
                
                val lastGlobalCall = lastTimestamps["last_call"] 
                val lastGlobalMsg = lastTimestamps["last_message"]

                // 4. Sync Calls
                setProgress(workDataOf("status" to "통화 기록 대조 중..."))
                val allCalls = getAllCallLogs()
                val newCallRecords = allCalls.filter { call ->
                    val localAt = serverDateFormat.format(Date(call.third))
                    // Only sync if newer than global max OR if we haven't synced for a while (robustness fallback)
                    val id = phoneToId[normalizePhone(call.first)]
                    id != null && (lastGlobalCall == null || localAt > lastGlobalCall)
                }.map { call ->
                    CallRecord(
                        duration = call.second,
                        timestamp = serverDateFormat.format(Date(call.third)),
                        contact_id = phoneToId[normalizePhone(call.first)],
                        phone_number = call.first,
                        direction = call.fourth
                    )
                }
                
                if (newCallRecords.isNotEmpty()) {
                    setProgress(workDataOf("status" to "통화 기록 ${newCallRecords.size}건 동기화 중..."))
                    newCallRecords.chunked(100).forEach { RetrofitClient.apiService.logCallsBulk(it) }
                }

                // 5. Sync SMS
                setProgress(workDataOf("status" to "문자 메시지 대조 중..."))
                val allMsgs = getAllSMS()
                val newMsgRecords = allMsgs.filter { msg ->
                    val localAt = serverDateFormat.format(Date(msg.dateLong))
                    val id = phoneToId[normalizePhone(msg.address)]
                    id != null && (lastGlobalMsg == null || localAt > lastGlobalMsg)
                }.map { msg ->
                    MessageRecord(
                        content = msg.body,
                        timestamp = serverDateFormat.format(Date(msg.dateLong)),
                        contact_id = phoneToId[normalizePhone(msg.address)],
                        phone_number = msg.address,
                        direction = msg.direction
                    )
                }
                
                if (newMsgRecords.isNotEmpty()) {
                    setProgress(workDataOf("status" to "메시지 ${newMsgRecords.size}건 동기화 중..."))
                    newMsgRecords.chunked(100).forEach { RetrofitClient.apiService.logMessagesBulk(it) }
                }

                // 6. Sync recordings (Needs file-specific check, so fetch recent-only stats)
                setProgress(workDataOf("status" to "최근 녹음 파일 정보 로드..."))
                val recentStatsRes = RetrofitClient.apiService.getContactsStats(recentOnly = true)
                val recentStats = recentStatsRes.body() ?: emptyList()
                val phoneToRecentData = recentStats.associateBy { normalizePhone(it["phone_number"]?.toString() ?: "") }
                
                syncRecordings(phoneToRecentData)

                Log.i("SyncWorker", "Optimized sync completed successfully!")
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
        }.sortedByDescending { it.lastModified() }

        val totalToUpload = unsyncedFiles.size
        if (totalToUpload == 0) {
            setProgress(workDataOf("status" to "모든 녹음 파일이 이미 업로드되었습니다."))
            return
        }

        unsyncedFiles.forEachIndexed { index, file ->
            val progress = when {
                totalToUpload > 10 -> "최신 녹음 데이터 동기화 중... (${index + 1} / $totalToUpload)"
                else -> "${file.name.substringAfterLast("/").take(15)}... 업로드 중"
            }
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
        setProgress(workDataOf("status" to "동기화가 완료되었습니다!"))
    }

    private fun normalizePhone(number: String): String {
        val clean = number.replace(Regex("[^0-9]"), "")
        return when {
            clean.startsWith("8210") -> "010" + clean.substring(4)
            clean.startsWith("10") && clean.length >= 10 -> "0" + clean
            else -> clean
        }
    }

    private fun addAppAccountContact(name: String, phone: String) {
        try {
            val cleanPhone = normalizePhone(phone)
            
            // Delete any existing/orphaned raw contact for this account and phone to ensure a clean slate
            applicationContext.contentResolver.delete(
                android.provider.ContactsContract.RawContacts.CONTENT_URI,
                "${android.provider.ContactsContract.RawContacts.ACCOUNT_TYPE} = ? AND ${android.provider.ContactsContract.RawContacts.SYNC1} = ?",
                arrayOf("com.example.crm.account", cleanPhone)
            )

            val ops = java.util.ArrayList<android.content.ContentProviderOperation>()
            ops.add(android.content.ContentProviderOperation.newInsert(android.provider.ContactsContract.RawContacts.CONTENT_URI)
                .withValue(android.provider.ContactsContract.RawContacts.ACCOUNT_TYPE, "com.example.crm.account")
                .withValue(android.provider.ContactsContract.RawContacts.ACCOUNT_NAME, "SalesMind CRM 본계정")
                .withValue(android.provider.ContactsContract.RawContacts.SYNC1, cleanPhone) // unique identifier
                .build())

            ops.add(android.content.ContentProviderOperation.newInsert(android.provider.ContactsContract.Data.CONTENT_URI)
                .withValueBackReference(android.provider.ContactsContract.Data.RAW_CONTACT_ID, 0)
                .withValue(android.provider.ContactsContract.Data.MIMETYPE, android.provider.ContactsContract.CommonDataKinds.StructuredName.CONTENT_ITEM_TYPE)
                .withValue(android.provider.ContactsContract.CommonDataKinds.StructuredName.DISPLAY_NAME, name)
                .build())

            ops.add(android.content.ContentProviderOperation.newInsert(android.provider.ContactsContract.Data.CONTENT_URI)
                .withValueBackReference(android.provider.ContactsContract.Data.RAW_CONTACT_ID, 0)
                .withValue(android.provider.ContactsContract.Data.MIMETYPE, android.provider.ContactsContract.CommonDataKinds.Phone.CONTENT_ITEM_TYPE)
                .withValue(android.provider.ContactsContract.CommonDataKinds.Phone.NUMBER, cleanPhone)
                .build())

            ops.add(android.content.ContentProviderOperation.newInsert(android.provider.ContactsContract.Data.CONTENT_URI)
                .withValueBackReference(android.provider.ContactsContract.Data.RAW_CONTACT_ID, 0)
                .withValue(android.provider.ContactsContract.Data.MIMETYPE, "vnd.android.cursor.item/vnd.com.example.crm.profile")
                .withValue(android.provider.ContactsContract.Data.DATA1, cleanPhone)
                .withValue(android.provider.ContactsContract.Data.DATA2, "CRM")
                .withValue(android.provider.ContactsContract.Data.DATA3, "AI 분석")
                .build())

            applicationContext.contentResolver.applyBatch(android.provider.ContactsContract.AUTHORITY, ops)
        } catch (e: Exception) {
            android.util.Log.e("SyncWorker", "Failed to add crm contact: ${e.message}")
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
                val cleanNumber = normalizePhone(number)
                list.add(Contact(name = name, phone_number = cleanNumber))
                // Ensure our custom deep link exists in Native Contacts
                if (cleanNumber.isNotEmpty()) {
                    addAppAccountContact(name, cleanNumber)
                }
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
