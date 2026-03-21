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
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView

// Helper Data Classes
data class SmsItem(val address: String, val body: String, val direction: String, val dateLong: Long)
data class Quadruple<A, B, C, D>(val first: A, val second: B, val third: C, val fourth: D)

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
            Toast.makeText(this, "권한 허가되었습니다.", Toast.LENGTH_SHORT).show()
            pendingSyncAction?.invoke()
            pendingSyncAction = null
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
        var selectedDateFilter by remember { mutableStateOf<String?>(null) }
        var isLoadingInitialData by remember { mutableStateOf(true) }
        
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
                                scope.launch {
                                    val resTimeline = RetrofitClient.apiService.getGlobalTimeline()
                                    if (resTimeline.isSuccessful) globalTimeline = resTimeline.body() ?: emptyList()
                                }
                            }
                        }
                        else -> { isSyncing = false }
                    }
                }
            }
            liveData.observeForever(observer)
            onDispose { liveData.removeObserver(observer) }
        }

        LaunchedEffect(Unit) {
            try {
                isLoadingInitialData = true
                // 1. Fetch existing data always on start
                val t1 = launch {
                    val resTimeline = RetrofitClient.apiService.getGlobalTimeline()
                    if (resTimeline.isSuccessful) globalTimeline = resTimeline.body() ?: emptyList()
                }
                val t2 = launch {
                    val resStats = RetrofitClient.apiService.getContactsStats()
                    if (resStats.isSuccessful) contactStats = resStats.body() ?: emptyList()
                }
                t1.join()
                t2.join()
                isLoadingInitialData = false

                // 2. Automatically trigger sync for new data
                attemptSyncAll { b, s -> 
                    isSyncing = b
                    if (s != null) syncProgress = s
                }
            } catch (e: Exception) {
                Log.e("LoadError", "Initial fetch failed: ${e.message}")
                isLoadingInitialData = false
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
                            Icon(Icons.Default.Close, contentDescription = null)
                        }
                    }
                    
                    OutlinedTextField(
                        value = searchQuery,
                        onValueChange = { searchQuery = it },
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                        placeholder = { Text("이름 또는 번호 검색") },
                        leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) }
                    )
                    
                    LazyColumn(modifier = Modifier.fillMaxSize().padding(top = 16.dp)) {
                        val filtered = if (searchQuery.isEmpty()) contactStats else contactStats.filter {
                             (it["name"]?.toString()?.contains(searchQuery, true) ?: false) || 
                             (it["phone_number"]?.toString()?.contains(searchQuery) ?: false)
                        }
                        items(filtered) { item ->
                            ListItem(
                                headlineContent = { Text(item["name"]?.toString() ?: "?") },
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
            Scaffold(
                topBar = {
                    if (screenState == "Dashboard") {
                        TopAppBar(
                            title = { 
                                Column {
                                    Text("SalesMind AI CRM")
                                    if (isSyncing) Text(syncProgress, style = MaterialTheme.typography.bodySmall)
                                }
                            },
                            navigationIcon = {
                                IconButton(onClick = { scope.launch { drawerState.open() } }) {
                                    Icon(Icons.Default.Menu, contentDescription = null)
                                }
                            },
                            actions = {
                                IconButton(onClick = { showDatePicker = true }) {
                                    Icon(Icons.Default.DateRange, contentDescription = null, 
                                         tint = if (selectedDateFilter != null) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface)
                                }
                                IconButton(onClick = { attemptSyncAll { b, s -> 
                                    isSyncing = b
                                    if (s != null) syncProgress = s
                                } }, enabled = !isSyncing) {
                                    if (isSyncing) CircularProgressIndicator(modifier = Modifier.size(24.dp))
                                    else Icon(Icons.Default.Refresh, contentDescription = null)
                                }
                            }
                        )
                    }
                }
            ) { padding ->
                Box(modifier = Modifier.padding(padding).fillMaxSize()) {
                    when (screenState) {
                        "Dashboard" -> {
                            val filteredTimeline = if (selectedDateFilter == null) globalTimeline 
                                else globalTimeline.filter { it["timestamp"]?.toString()?.startsWith(selectedDateFilter!!) ?: false }
                            
                            Column {
                                Row(modifier = Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                                    Text(text = if (selectedDateFilter == null) "최근 영업 활동" else "$selectedDateFilter 활동", 
                                         style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
                                    if (selectedDateFilter != null) {
                                        Spacer(Modifier.weight(1f))
                                        TextButton(onClick = { selectedDateFilter = null }) { Text("전체보기") }
                                    }
                                }
                                
                                if (isLoadingInitialData) {
                                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                            CircularProgressIndicator()
                                            Spacer(Modifier.height(16.dp))
                                            Text("클라우드 데이터를 불러오는 중...", style = MaterialTheme.typography.bodyLarge)
                                        }
                                    }
                                } else if (globalTimeline.isEmpty() && isSyncing) {
                                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                         Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                             CircularProgressIndicator()
                                             Spacer(Modifier.height(16.dp))
                                             Text(syncProgress, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Bold)
                                             Text("핸드폰의 신규 활동(통화/문자/녹음)을 클라우드로 전송 중입니다.", 
                                                  style = MaterialTheme.typography.bodySmall, color = Color.Gray)
                                         }
                                    }
                                } else if (filteredTimeline.isEmpty()) {
                                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                            Icon(Icons.AutoMirrored.Filled.List, contentDescription = null, modifier = Modifier.size(64.dp), tint = Color.Gray)
                                            Spacer(Modifier.height(8.dp))
                                            Text(if (selectedDateFilter == null) "활동 기록이 없습니다." else "$selectedDateFilter 일의 기록이 없습니다.", color = Color.Gray)
                                        }
                                    }
                                } else {
                                    LazyColumn(modifier = Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 80.dp)) {
                                        items(filteredTimeline) { item ->
                                            TimelineItemCard(item) {
                                                selectedContactId = item["contact_id"]?.toString() ?: ""
                                                screenState = "Detail"
                                            }
                                        }
                                    }
                                }
                            }
                        }
                        "Detail" -> {
                            AnalysisWebView(selectedContactId) { screenState = "Dashboard" }
                        }
                    }
                }
            }
        }
    }

    @Composable
    fun AnalysisWebView(contactId: String, onBack: () -> Unit) {
        Column {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(8.dp)) {
                IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = null) }
                Text("AI 분석 리포트", style = MaterialTheme.typography.titleLarge)
            }
            AndroidView(factory = { ctx ->
                WebView(ctx).apply {
                    webViewClient = WebViewClient()
                    settings.javaScriptEnabled = true
                    loadUrl("https://crm-f2v6.onrender.com/?id=$contactId")
                }
            }, modifier = Modifier.fillMaxSize())
        }
    }

    @Suppress("UNCHECKED_CAST")
    @Composable
    fun TimelineItemCard(item: Map<String, Any>, onClick: () -> Unit) {
        val type = item["type"]?.toString() ?: "call"
        val data = (item["data"] as? Map<String, Any>) ?: emptyMap()
        Card(modifier = Modifier.fillMaxWidth().padding(8.dp).clickable { onClick() }) {
            Row(modifier = Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = when(type) {
                        "call" -> Icons.Default.Call
                        "message" -> Icons.Default.Email
                        else -> Icons.Default.Notifications
                    },
                    contentDescription = null,
                    modifier = Modifier.size(32.dp),
                    tint = MaterialTheme.colorScheme.primary
                )
                Spacer(Modifier.width(16.dp))
                Column(Modifier.weight(1f)) {
                    Text(item["name"]?.toString() ?: "Unknown", fontWeight = FontWeight.Bold)
                    val dir = data["direction"]?.toString() ?: ""
                    val content = when(type) {
                        "call" -> "${if(dir=="IN") "↙" else "↗"} ${(data["duration"] as? Number)?.toInt() ?: 0}초 통화"
                        "message" -> "${if(dir=="INBOX") "↙" else "↗"} ${data["content"]?.toString()?.take(20)}"
                        else -> "🎙 AI 분석 리포트"
                    }
                    Text(content, style = MaterialTheme.typography.bodySmall, color = Color.Gray)
                }
                Text(item["timestamp"]?.toString()?.substringAfter(" ")?.take(5) ?: "", style = MaterialTheme.typography.labelSmall)
            }
        }
    }

    private fun getRequiredPermissions(): Array<String> {
        val list = mutableListOf(Manifest.permission.READ_CONTACTS, Manifest.permission.READ_CALL_LOG, Manifest.permission.READ_SMS)
        if (android.os.Build.VERSION.SDK_INT >= 33) list.add(Manifest.permission.READ_MEDIA_AUDIO)
        else list.add(Manifest.permission.READ_EXTERNAL_STORAGE)
        return list.toTypedArray()
    }

    private fun attemptSyncAll(onSync: (Boolean, String?) -> Unit) {
        val permissions = getRequiredPermissions()
        val missing = permissions.filter { ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED }
        if (missing.isNotEmpty()) {
            pendingSyncAction = { enqueueSyncWorker(onSync) }
            requestPermissionLauncher.launch(missing.toTypedArray())
        } else {
            enqueueSyncWorker(onSync)
        }
    }

    private fun enqueueSyncWorker(onSync: (Boolean, String?) -> Unit) {
        val req = OneTimeWorkRequestBuilder<SyncWorker>().addTag("sync").build()
        WorkManager.getInstance(this).enqueueUniqueWork("sync", androidx.work.ExistingWorkPolicy.REPLACE, req)
        Toast.makeText(this, "백그라운드 동기화 시작!", Toast.LENGTH_SHORT).show()
    }
}
