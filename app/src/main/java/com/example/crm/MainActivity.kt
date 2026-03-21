package com.example.crm

import android.Manifest
import android.content.pm.PackageManager
import android.database.Cursor
import android.net.Uri
import android.os.Bundle
import android.provider.CallLog
import android.provider.ContactsContract
import android.util.Log
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.example.crm.models.CallRecord
import com.example.crm.models.Contact
import com.example.crm.models.MessageRecord
import com.example.crm.network.RetrofitClient
import com.example.crm.ui.theme.CRMTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.*
import java.io.File
import android.os.Environment
import okhttp3.MediaType
import okhttp3.MultipartBody
import okhttp3.RequestBody
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.clickable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.Email
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DateRange
import androidx.work.ContentUriTrigger
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.WorkRequest
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.ui.viewinterop.AndroidView

// Helper Data Class for SMS
data class SmsItem(val address: String, val body: String, val direction: String, val dateLong: Long)

class MainActivity : ComponentActivity() {

    private fun normalizePhone(number: String): String {
        val clean = number.replace(Regex("[^0-9]"), "")
        return when {
            clean.startsWith("8210") -> "010" + clean.substring(4)
            clean.startsWith("10") && clean.length >= 10 -> "0" + clean
            else -> clean
        }
    }

    private var pendingSyncAction: (() -> Unit)? = null

    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        if (permissions.values.all { it }) {
            Toast.makeText(this, "권한 허용됨! 동기화를 시작합니다.", Toast.LENGTH_SHORT).show()
            pendingSyncAction?.invoke()
            pendingSyncAction = null
        } else {
            Toast.makeText(this, "필수 권한이 거부되었습니다.", Toast.LENGTH_LONG).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            CRMTheme {
                AppScreen()
            }
        }
    }

    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    fun AppScreen() {
        var screenState by remember { mutableStateOf("Dashboard") }
        var selectedContactId by remember { mutableStateOf("") }
        var contactStats by remember { mutableStateOf<List<Map<String, Any>>>(emptyList()) }
        var globalTimeline by remember { mutableStateOf<List<Map<String, Any>>>(emptyList()) }
        var isSyncing by remember { mutableStateOf(false) }
        var syncProgress by remember { mutableStateOf("준비") }
        var searchQuery by remember { mutableStateOf("") }
        var showDatePicker by remember { mutableStateOf(false) }
        var selectedDateFilter by remember { mutableStateOf<String?>(null) } // Format: YYYY-MM-DD
        
        val datePickerState = rememberDatePickerState()
        
        val drawerState = rememberDrawerState(initialValue = DrawerValue.Closed)
        val scope = rememberCoroutineScope()

        if (showDatePicker) {
            DatePickerDialog(
                onDismissRequest = { showDatePicker = false },
                confirmButton = {
                    TextButton(onClick = {
                        val sel = datePickerState.selectedDateMillis
                        if (sel != null) {
                            val sdf = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())
                            selectedDateFilter = sdf.format(Date(sel))
                        }
                        showDatePicker = false
                    }) { Text("확인") }
                },
                dismissButton = {
                    TextButton(onClick = { 
                        selectedDateFilter = null
                        showDatePicker = false 
                    }) { Text("필터 취소") }
                }
            ) {
                DatePicker(state = datePickerState)
            }
        }

        // Observe Background Sync Service via WorkManager
        val context = LocalContext.current
        DisposableEffect(Unit) {
            val liveData = WorkManager.getInstance(context).getWorkInfosByTagLiveData("sync")
            val observer = androidx.lifecycle.Observer<List<WorkInfo>> { workInfos ->
                val info = workInfos?.firstOrNull()
                if (info != null) {
                    when (info.state) {
                        WorkInfo.State.ENQUEUED, WorkInfo.State.RUNNING -> {
                            isSyncing = true
                            syncProgress = info.progress.getString("status") ?: "진행 중..."
                        }
                        WorkInfo.State.SUCCEEDED -> {
                            if (isSyncing) {
                                isSyncing = false
                                syncProgress = "완료"
                                // Final refresh when sync finishes
                                scope.launch {
                                    val resTimeline = RetrofitClient.apiService.getGlobalTimeline()
                                    if (resTimeline.isSuccessful) globalTimeline = resTimeline.body() ?: emptyList()
                                }
                            }
                        }
                        WorkInfo.State.FAILED, WorkInfo.State.CANCELLED -> {
                            isSyncing = false
                            syncProgress = "실패/취소됨"
                        }
                        else -> { isSyncing = false }
                    }
                }
            }
            liveData.observeForever(observer)
            onDispose { liveData.removeObserver(observer) }
        }

        // Fetch initial list when dashboard opens (unless already syncing)
        LaunchedEffect(Unit) {
            try {
                if (!isSyncing) {
                    val resTimeline = RetrofitClient.apiService.getGlobalTimeline()
                    if (resTimeline.isSuccessful) globalTimeline = resTimeline.body() ?: emptyList()
                }
            } catch (e: Exception) {
                Log.e("LoadError", "Initial load failed: ${e.message}")
            }
        }

        ModalNavigationDrawer(
            drawerState = drawerState,
            gesturesEnabled = false,
            drawerContent = {
                ModalDrawerSheet(modifier = Modifier.width(320.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text("연락처 찾기", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                        IconButton(onClick = { scope.launch { drawerState.close() } }) {
                            Icon(Icons.Default.Close, contentDescription = "Close Drawer")
                        }
                    }
                    
                    OutlinedTextField(
                        value = searchQuery,
                        onValueChange = { searchQuery = it },
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                        placeholder = { Text("이름 또는 번호 검색") },
                        leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                        shape = MaterialTheme.shapes.medium,
                        singleLine = true
                    )
                    
                    val filteredStats = if (searchQuery.isEmpty()) {
                        contactStats
                    } else {
                        contactStats.filter { 
                            (it["name"]?.toString()?.contains(searchQuery, true) ?: false) || 
                            (it["phone_number"]?.toString()?.contains(searchQuery) ?: false)
                        }
                    }

                    LazyColumn(modifier = Modifier.fillMaxSize().padding(top = 16.dp)) {
                        items(filteredStats) { item ->
                            ListItem(
                                headlineContent = { Text(item["name"]?.toString() ?: "?", fontWeight = FontWeight.Bold) },
                                supportingContent = { Text(item["organization"]?.toString() ?: "소속없음") },
                                leadingContent = { Icon(Icons.Default.Person, contentDescription = null) },
                                modifier = Modifier.clickable {
                                    selectedContactId = item["id"]?.toString() ?: ""
                                    screenState = "Detail"
                                    scope.launch { drawerState.close() }
                                }
                            )
                        }
                    }
                }
            }
        ) {
            Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                when (screenState) {
                    "Dashboard" -> {
                        Scaffold(
                            topBar = {
                                TopAppBar(
                                    title = { 
                                        Column {
                                            Text("SalesMind AI CRM", style = MaterialTheme.typography.titleLarge)
                                            if (isSyncing) Text(syncProgress, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
                                        }
                                    },
                                    navigationIcon = {
                                        IconButton(onClick = { scope.launch { drawerState.open() } }) {
                                            Icon(Icons.Default.Menu, contentDescription = "Menu")
                                        }
                                    },
                                    actions = {
                                        IconButton(onClick = { showDatePicker = true }) {
                                            Icon(
                                                imageVector = Icons.Default.DateRange, 
                                                contentDescription = "Filter by Date",
                                                tint = if (selectedDateFilter != null) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                                            )
                                        }
                                        IconButton(
                                            onClick = { 
                                                attemptSyncAll { isLoading, status ->
                                                    isSyncing = isLoading
                                                    if (status != null) syncProgress = status
                                                }
                                            },
                                            enabled = !isSyncing
                                        ) {
                                            if (isSyncing) CircularProgressIndicator(modifier = Modifier.size(24.dp))
                                            else Icon(Icons.Default.Refresh, contentDescription = "Sync All")
                                        }
                                    },
                                    colors = TopAppBarDefaults.topAppBarColors(
                                        containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
                                    )
                                )
                            }
                        ) { padding ->
                            Column(modifier = Modifier.padding(padding).fillMaxSize()) {
                                Row(
                                    modifier = Modifier.fillMaxWidth().padding(16.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Icon(Icons.Default.Notifications, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                                    Spacer(Modifier.width(8.dp))
                                    Text(
                                        text = if (selectedDateFilter == null) "최근 영업 활동 기록" else "$selectedDateFilter 기록", 
                                        style = MaterialTheme.typography.titleMedium,
                                        fontWeight = FontWeight.ExtraBold,
                                        color = MaterialTheme.colorScheme.primary
                                    )
                                    if (selectedDateFilter != null) {
                                        Spacer(Modifier.weight(1f))
                                        TextButton(onClick = { selectedDateFilter = null }) {
                                            Text("전체보기")
                                        }
                                    }
                                }

                                val filteredTimeline = if (selectedDateFilter == null) {
                                    globalTimeline
                                } else {
                                    globalTimeline.filter { it["timestamp"]?.toString()?.startsWith(selectedDateFilter!!) ?: false }
                                }

                                if (isSyncing && filteredTimeline.isEmpty()) {
                                    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                        CircularProgressIndicator()
                                    }
                                } else if (filteredTimeline.isEmpty()) {
                                    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                            Icon(Icons.AutoMirrored.Filled.List, contentDescription = null, modifier = Modifier.size(64.dp), 
                                                 tint = MaterialTheme.colorScheme.outline)
                                            Spacer(modifier = Modifier.height(8.dp))
                                            Text(
                                                if (selectedDateFilter == null) "기록이 없습니다. 동기화를 진행해 주세요."
                                                else "$selectedDateFilter 일의 기록이 없습니다.", 
                                                color = MaterialTheme.colorScheme.outline
                                            )
                                        }
                                    }
                                } else {
                                    LazyColumn(contentPadding = PaddingValues(bottom = 80.dp)) {
                                        items(filteredTimeline) { item ->e {
                                    LazyColumn(contentPadding = PaddingValues(bottom = 80.dp)) {
                                        items(globalTimeline) { item ->
                                            TimelineItemCard(
                                                item = item,
                                                onClick = { 
                                                    selectedContactId = item["contact_id"]?.toString() ?: ""
                                                    screenState = "Detail"
                                                }
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                    "Detail" -> {
                        AnalysisWebView(contactId = selectedContactId) { screenState = "Dashboard" }
                    }
                }
            }
        }
    }

    @Composable
    fun AnalysisWebView(contactId: String, onBack: () -> Unit) {
        val url = "https://crm-f2v6.onrender.com/?id=$contactId"
        
        Column(modifier = Modifier.fillMaxSize()) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(10.dp)) {
                IconButton(onClick = onBack) { 
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") 
                }
                Spacer(modifier = Modifier.width(15.dp))
                Text("AI 대화 이력 분석", style = MaterialTheme.typography.titleLarge)
            }
            
            AndroidView(factory = { context ->
                WebView(context).apply {
                    webViewClient = WebViewClient()
                    settings.javaScriptEnabled = true
                    settings.domStorageEnabled = true
                    loadUrl(url)
                }
            }, modifier = Modifier.fillMaxSize())
        }
    }

    @Suppress("UNCHECKED_CAST")
    @Composable
    fun TimelineItemCard(item: Map<String, Any>, onClick: () -> Unit) {
        val type = item["type"]?.toString() ?: "call"
        val data = item["data"] as? Map<String, Any> ?: emptyMap()
        val timestamp = item["timestamp"]?.toString() ?: ""
        
        Card(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp).clickable { onClick() },
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
            shape = MaterialTheme.shapes.medium
        ) {
            Row(modifier = Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                // 1. Icon (Call vs Message vs Recording)
                val icon = when(type) {
                    "call" -> Icons.Default.Call
                    "message" -> Icons.Default.Email
                    else -> Icons.Default.Notifications // Mic/Recording
                }
                val iconContainerColor = when(type) {
                    "call" -> MaterialTheme.colorScheme.primaryContainer
                    "message" -> MaterialTheme.colorScheme.secondaryContainer
                    else -> MaterialTheme.colorScheme.tertiaryContainer 
                }
                val iconColor = when(type) {
                    "call" -> MaterialTheme.colorScheme.onPrimaryContainer
                    "message" -> MaterialTheme.colorScheme.onSecondaryContainer
                    else -> MaterialTheme.colorScheme.onTertiaryContainer
                }

                Surface(
                    color = iconContainerColor,
                    shape = CircleShape,
                    modifier = Modifier.size(40.dp)
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            imageVector = icon,
                            contentDescription = null,
                            tint = iconColor,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                }
                
                Spacer(Modifier.width(12.dp))
                
                // 2. Main Content
                Column(modifier = Modifier.weight(1f)) {
                    Text(text = item["name"]?.toString() ?: "Unknown", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        val dir = data["direction"]?.toString() ?: ""
                        val detailText = when(type) {
                            "call" -> {
                                val dur = (data["duration"] as? Number)?.toInt() ?: 0
                                val arrow = if (dir == "IN") "↙ " else "↗ "
                                arrow + "${dur}초 통화"
                            }
                            "message" -> {
                                val arrow = if (dir == "INBOX") "↙ " else "↗ "
                                arrow + (data["content"]?.toString()?.take(20)?.plus("...") ?: "")
                            }
                            else -> {
                                "🎙 AI 분석 리포트 요약"
                            }
                        }
                        Text(text = detailText, style = MaterialTheme.typography.bodySmall, color = Color.Gray, maxLines = 1)
                    }
                }
                
                // 3. Timestamp
                Column(horizontalAlignment = Alignment.End) {
                    val timeOnly = if (timestamp.length > 11) timestamp.substring(11, 16) else ""
                    val dateOnly = if (timestamp.length > 10) timestamp.substring(5, 10) else ""
                    
                    Text(text = timeOnly, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                    Text(text = dateOnly, style = MaterialTheme.typography.labelSmall, color = Color.Gray)
                }
            }
        }
    }

    private fun getRequiredPermissions(): Array<String> {
        val permissions = mutableListOf(
            Manifest.permission.READ_CONTACTS,
            Manifest.permission.READ_CALL_LOG,
            Manifest.permission.READ_SMS
        )
        if (android.os.Build.VERSION.SDK_INT >= 33) {
            permissions.add(Manifest.permission.READ_MEDIA_AUDIO)
        } else {
            permissions.add(Manifest.permission.READ_EXTERNAL_STORAGE)
        }
        return permissions.toTypedArray()
    }

    private fun attemptSyncAll(setLoadingState: (Boolean, String?) -> Unit) {
        val permissions = getRequiredPermissions()
        val missing = permissions.filter { 
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED 
        }

        if (missing.isNotEmpty()) {
            pendingSyncAction = { enqueueSyncWorker(setLoadingState) }
            requestPermissionLauncher.launch(missing.toTypedArray())
        } else {
            enqueueSyncWorker(setLoadingState)
        }
    }

    private fun enqueueSyncWorker(setLoadingState: (Boolean, String?) -> Unit) {
        val workRequest = OneTimeWorkRequestBuilder<SyncWorker>()
            .addTag("sync")
            .build()
        WorkManager.getInstance(this).enqueueUniqueWork(
            "sync",
            androidx.work.ExistingWorkPolicy.REPLACE,
            workRequest
        )
        Toast.makeText(this, "백그라운드 동기화 시작!", Toast.LENGTH_SHORT).show()
    }



    private fun getAllContacts(): List<Contact> {
        val list = mutableListOf<Contact>()
        val cursor = contentResolver.query(ContactsContract.CommonDataKinds.Phone.CONTENT_URI, null, null, null, null)
        cursor?.use {
            val nameIdx = it.getColumnIndex(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME)
            val numIdx = it.getColumnIndex(ContactsContract.CommonDataKinds.Phone.NUMBER)
            while (it.moveToNext()) {
                val name = it.getString(nameIdx) ?: "Unknown"
                val number = it.getString(numIdx) ?: ""
                list.add(Contact(name = name, phone_number = number.replace(Regex("[^0-9]"), "")))
            }
        }
        return list
    }

    private fun getAllCallLogs(): List<Quadruple<String, Int, Long, String>> {
        val list = mutableListOf<Quadruple<String, Int, Long, String>>()
        val cursor = contentResolver.query(CallLog.Calls.CONTENT_URI, null, null, null, "${CallLog.Calls.DATE} DESC")
        cursor?.use {
            val numIdx = it.getColumnIndex(CallLog.Calls.NUMBER)
            val durIdx = it.getColumnIndex(CallLog.Calls.DURATION)
            val dateIdx = it.getColumnIndex(CallLog.Calls.DATE)
            val typeIdx = it.getColumnIndex(CallLog.Calls.TYPE)
            while (it.moveToNext()) {
                val num = it.getString(numIdx) ?: ""
                val dur = it.getInt(durIdx)
                val date = it.getLong(dateIdx)
                val type = it.getInt(typeIdx)
                val dir = if (type == CallLog.Calls.INCOMING_TYPE) "IN" else "OUT"
                list.add(Quadruple(num, dur, date, dir))
            }
        }
        return list
    }

    // Helper class for 4 values
    data class Quadruple<A, B, C, D>(val first: A, val second: B, val third: C, val fourth: D)

    private fun getAllSMS(): List<SmsItem> {
        val list = mutableListOf<SmsItem>()
        val cursor = contentResolver.query(Uri.parse("content://sms"), null, null, null, "date DESC")
        cursor?.use {
            val addrIdx = it.getColumnIndex("address")
            val bodyIdx = it.getColumnIndex("body")
            val typeIdx = it.getColumnIndex("type")
            val dateIdx = it.getColumnIndex("date")
            while (it.moveToNext()) {
                val dir = if (it.getInt(typeIdx) == 1) "INBOX" else "SENT"
                list.add(SmsItem(address = it.getString(addrIdx) ?: "", body = it.getString(bodyIdx) ?: "", direction = dir, dateLong = it.getLong(dateIdx)))
            }
        }
        return list
    }



    private fun extractPhoneNumber(filename: String): String? {
        // 특수문자 제거 (숫자와 알파벳만 남김)
        val clean = filename.replace(Regex("[^0-9a-zA-Z]"), "")

        // 1순위: 한국 휴대폰 번호 (010, 011, 016, 017, 018, 019)
        Regex("01[016789][0-9]{7,8}").find(clean)?.value?.let { return it }

        // 2순위: 한국 유선전화 - 서울(02) 8~9자리
        Regex("02[0-9]{7,8}").find(clean)?.value?.let { return it }

        // 3순위: 한국 유선전화 - 지역번호 3자리(031~099) + 7~8자리
        Regex("0[3-9][0-9]{8,9}").find(clean)?.value?.let { return it }

        // 4순위: 연속된 숫자 9~11자리 (위에서 못 잡은 경우)
        Regex("[0-9]{9,11}").find(clean)?.value?.let { return it }

        return null
    }

    /**
     * 파일명에서 연락처 이름을 추출합니다.
     * 예) "통화 녹음 충북타일 이창동차장_260320_174931.m4a" → "충북타일 이창동차장"
     */
    private fun extractContactName(filename: String): String? {
        var name = filename.substringBeforeLast(".")  // 확장자 제거

        // "통화 녹음 " 또는 "통화녹음 " 접두사 제거
        name = name.removePrefix("통화 녹음 ").removePrefix("통화녹음 ")

        // 끝에 붙은 날짜/시간 패턴 제거: _YYMMDD_HHMMSS 또는 _YYYYMMDD_HHMMSS
        name = name.replace(Regex("_\\d{6,8}_\\d{4,6}$"), "").trim()

        // 이름에 숫자가 많으면 (전화번호인 경우) null 반환
        val digitCount = name.count { it.isDigit() }
        if (digitCount > name.length / 2) return null

        return name.trim().ifEmpty { null }
    }
}