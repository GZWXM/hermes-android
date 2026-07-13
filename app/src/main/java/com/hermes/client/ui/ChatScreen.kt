package com.hermes.client.ui

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.util.Base64
import android.view.ViewGroup
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.border
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.foundation.Image
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.lifecycle.viewmodel.compose.viewModel
import android.widget.Toast
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import coil.compose.AsyncImage
import com.hermes.client.data.ChatError
import com.hermes.client.data.ConversationEntity
import com.hermes.client.data.MessageEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

@Composable
fun ChatScreen(vm: ChatViewModel = viewModel()) {
    val context = LocalContext.current
    val messages by vm.messages.collectAsState()
    val streamingContent by vm.streamingContent.collectAsState()
    val thinkingContent by vm.thinkingContent.collectAsState()
    val isStreaming by vm.isStreaming.collectAsState()
    val isSending by vm.isSending.collectAsState()
    val toolStatus by vm.toolStatus.collectAsState()
    val toolRunning by vm.toolRunning.collectAsState()

    var input by rememberSaveable { mutableStateOf("") }
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    // Track initial load so we jump to bottom instead of animate-scrolling from top
    var initialLoadDone by rememberSaveable { mutableStateOf(false) }
    // Track if user manually scrolled up — disable auto-scroll while they browse history
    var userScrolled by remember { mutableStateOf(false) }
    // Watch scroll position: if user is near bottom, re-enable auto-scroll
    val isNearBottom by remember {
        derivedStateOf {
            val info = listState.layoutInfo
            if (info.totalItemsCount == 0) true
            else {
                val lastVisible = info.visibleItemsInfo.lastOrNull()?.index ?: return@derivedStateOf false
                lastVisible >= info.totalItemsCount - 3
            }
        }
    }

    var selectedImageUri by rememberSaveable { mutableStateOf<Uri?>(null) }
    var selectedImageBase64 by remember { mutableStateOf<String?>(null) }
    var selectedImageMime by remember { mutableStateOf("image/jpeg") }

    // Camera — uses system camera via TakePicture contract
    var cameraPhotoUri by remember { mutableStateOf<Uri?>(null) }

    val cameraLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.TakePicture()
    ) { success: Boolean ->
        if (success) {
            cameraPhotoUri?.let { uri ->
                selectedImageUri = uri
                scope.launch(Dispatchers.IO) {
                    try {
                        val cr = context.contentResolver
                        val bytes = cr.openInputStream(uri)?.readBytes()
                        withContext(Dispatchers.Main) {
                            selectedImageMime = "image/jpeg"
                            selectedImageBase64 = bytes?.let { b -> Base64.encodeToString(b, Base64.NO_WRAP) }
                        }
                    } catch (e: Exception) { e.printStackTrace() }
                }
            }
        }
    }

    // Camera permission launcher
    val cameraPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            // Permission granted — create temp file URI and launch camera
            val photoFile = File(context.cacheDir, "cam_${System.currentTimeMillis()}.jpg")
            cameraPhotoUri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", photoFile)
            cameraLauncher.launch(cameraPhotoUri!!)
        } else {
            Toast.makeText(context, "需要相机权限才能拍照", Toast.LENGTH_SHORT).show()
        }
    }

    // Gallery
    val galleryLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        uri?.let {
            selectedImageUri = it
            scope.launch(Dispatchers.IO) {
                try {
                    val cr = context.contentResolver
                    val mime = cr.getType(it) ?: "image/jpeg"
                    val bytes = cr.openInputStream(it)?.readBytes()
                    withContext(Dispatchers.Main) {
                        selectedImageMime = mime
                        selectedImageBase64 = bytes?.let { b -> Base64.encodeToString(b, Base64.NO_WRAP) }
                    }
                } catch (e: Exception) { e.printStackTrace() }
            }
        }
    }

    // Media picker dropdown
    var showMediaMenu by remember { mutableStateOf(false) }

    // Scroll behavior: jump on first load, animate on new messages
    // Automatically re-enable scroll when user scrolls back near bottom
    LaunchedEffect(isNearBottom) {
        if (isNearBottom) userScrolled = false
    }
    // Detect manual scrolling via scroll events
    LaunchedEffect(listState) {
        snapshotFlow { listState.isScrollInProgress }
            .collect { scrolling ->
                if (scrolling) userScrolled = true
            }
    }
    LaunchedEffect(messages.size, streamingContent) {
        if (messages.isNotEmpty()) {
            if (!initialLoadDone) {
                listState.scrollToItem(messages.size - 1)
                initialLoadDone = true
            } else if (!userScrolled) {
                // Use scrollToItem (no animation) for streaming tokens
                // Use animateScrollToItem for completed new messages
                if (streamingContent.isNotEmpty()) {
                    listState.scrollToItem(messages.size - 1)
                } else {
                    listState.animateScrollToItem(messages.size - 1)
                }
            }
        }
    }

    // API Key dialog
    var showKeyDialog by remember { mutableStateOf(!vm.hasApiKey()) }
    var keyInput by remember { mutableStateOf(vm.apiKey) }
    if (showKeyDialog) {
        AlertDialog(
            onDismissRequest = { if (vm.hasApiKey()) showKeyDialog = false },
            title = { Text("配置 API Key") },
            text = {
                Column {
                    Text("请输入 Hermes API Server 的 API_SERVER_KEY")
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = keyInput,
                        onValueChange = { keyInput = it },
                        placeholder = { Text("sk-...") },
                        singleLine = true
                    )
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        val clean = keyInput.replace(Regex("[^a-zA-Z0-9_\\\\-]"), "")
                        if (clean.isNotBlank()) {
                            vm.apiKey = clean
                            showKeyDialog = false
                        }
                    },
                    enabled = keyInput.isNotBlank()
                ) { Text("确定") }
            }
        )
    }

    // ── Conversation list dialog ──
    if (showConversations) {
        AlertDialog(
            onDismissRequest = { showConversations = false },
            title = { Text("对话列表") },
            text = {
                LazyColumn(modifier = Modifier.heightIn(max = 400.dp)) {
                    item {
                        TextButton(
                            onClick = {
                                vm.newConversation()
                                showConversations = false
                            },
                            modifier = Modifier.fillMaxWidth().padding(4.dp)
                        ) {
                            Icon(Icons.Default.Add, contentDescription = null)
                            Spacer(Modifier.width(8.dp))
                            Text("新建对话")
                        }
                        HorizontalDivider()
                    }
                    items(conversations, key = { it.id }) { conv ->
                        Surface(
                            onClick = {
                                vm.switchConversation(conv.uuid)
                                showConversations = false
                            },
                            modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
                            shape = RoundedCornerShape(8.dp),
                            color = if (conv.uuid == currentConvId)
                                MaterialTheme.colorScheme.primaryContainer
                            else MaterialTheme.colorScheme.surface
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth().padding(12.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = conv.title,
                                        style = MaterialTheme.typography.bodyMedium,
                                        maxLines = 1
                                    )
                                    Text(
                                        text = formatTime(conv.updatedAt),
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                                if (conversations.size > 1) {
                                    IconButton(
                                        onClick = {
                                            vm.deleteConversation(conv.uuid)
                                        }
                                    ) {
                                        Icon(
                                            Icons.Default.Delete,
                                            contentDescription = "删除",
                                            modifier = Modifier.size(18.dp),
                                            tint = MaterialTheme.colorScheme.error
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showConversations = false }) {
                    Text("关闭")
                }
            }
        )
    }

    // ── Clear confirmation ──
    if (showClearConfirm) {
        AlertDialog(
            onDismissRequest = { showClearConfirm = false },
            title = { Text("清空当前对话") },
            text = { Text("确定要清空当前对话的所有消息吗？此操作不可撤销。") },
            confirmButton = {
                TextButton(
                    onClick = {
                        vm.clearMessages()
                        showClearConfirm = false
                    },
                    colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)
                ) { Text("清空") }
            },
            dismissButton = {
                TextButton(onClick = { showClearConfirm = false }) { Text("取消") }
            }
        )
    }

    val snackbarHostState = remember { SnackbarHostState() }

    LaunchedEffect(vm.error) {
        vm.error?.let { error ->
            val msg = when (error) {
                is ChatError.Network -> "网络错误: ${error.message}"
                is ChatError.Unauthorized -> "请先配置 API Key"
                is ChatError.Server -> "服务器错误 (${error.code}): ${error.message}"
                is ChatError.Cancelled -> null
                is ChatError.Unknown -> "未知错误: ${error.message}"
            }
            if (msg != null) {
                snackbarHostState.showSnackbar(msg, duration = SnackbarDuration.Short)
            }
            vm.clearError()
        }
    }

    var conversations by vm.conversations.collectAsState()
    var currentConvId by vm.currentConversationId.collectAsState()
    var showConversations by remember { mutableStateOf(false) }
    var showClearConfirm by remember { mutableStateOf(false) }

    Box(modifier = Modifier.fillMaxSize()) {
        Column(modifier = Modifier.fillMaxSize().systemBarsPadding().imePadding()) {
            // Top bar with conversation switcher and settings
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = { showConversations = true }) {
                    Icon(Icons.Default.Menu, contentDescription = "会话列表", modifier = Modifier.size(20.dp))
                }

                // Current conversation title
                val currentConv = conversations.find { it.uuid == currentConvId }
                TextButton(
                    onClick = { showConversations = true },
                    colors = ButtonDefaults.textButtonColors(
                        contentColor = MaterialTheme.colorScheme.onSurface
                    )
                ) {
                    Text(
                        text = currentConv?.title ?: "新对话",
                        style = MaterialTheme.typography.bodyMedium,
                        maxLines = 1
                    )
                    Icon(Icons.Default.UnfoldMore, contentDescription = null, modifier = Modifier.size(16.dp))
                }

                Row {
                    if (messages.isNotEmpty()) {
                        IconButton(
                            onClick = { showClearConfirm = true },
                            colors = IconButtonDefaults.iconButtonColors(
                                contentColor = MaterialTheme.colorScheme.error
                            )
                        ) {
                            Icon(Icons.Default.Delete, contentDescription = "清空", modifier = Modifier.size(18.dp))
                        }
                    }
                    IconButton(onClick = { showKeyDialog = true; keyInput = vm.apiKey }) {
                        Icon(Icons.Default.Settings, contentDescription = "设置", modifier = Modifier.size(20.dp))
                    }
                }
            }

            LazyColumn(
                modifier = Modifier.weight(1f).fillMaxWidth(),
                state = listState,
                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                items(messages, key = { it.id }) { msg ->
                    MessageBubble(msg, onDelete = { vm.deleteMessage(msg) })
                }
                if (streamingContent.isNotEmpty()) {
                    item(key = "streaming") {
                        Surface(
                            shape = RoundedCornerShape(12.dp),
                            color = MaterialTheme.colorScheme.surfaceVariant,
                            modifier = Modifier.padding(12.dp)
                        ) {
                            MarkdownContent(
                                text = streamingContent,
                                isUser = false,
                                modifier = Modifier.padding(10.dp)
                            )
                        }
                    }
                }
                if (thinkingContent.isNotEmpty()) {
                    item(key = "thinking") {
                        val expanded = remember { mutableStateOf(true) }
                        Surface(
                            shape = RoundedCornerShape(12.dp),
                            color = if (isSystemInDarkTheme()) Color(0xFF1A2744) else Color(0xFFF0F5FF),
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp)
                        ) {
                            Column(modifier = Modifier.padding(10.dp)) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(
                                        "🧠 思维链",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = if (isSystemInDarkTheme()) Color(0xFF8AB4F8) else Color(0xFF1A73E8)
                                    )
                                    TextButton(
                                        onClick = { expanded.value = !expanded.value },
                                        contentPadding = PaddingValues(horizontal = 4.dp)
                                    ) {
                                        Text(
                                            if (expanded.value) "收起 ▲" else "展开 ▼",
                                            style = MaterialTheme.typography.labelSmall,
                                            color = if (isSystemInDarkTheme()) Color(0xFF8AB4F8) else Color(0xFF1A73E8)
                                        )
                                    }
                                }
                                if (expanded.value) {
                                    Text(
                                        text = thinkingContent,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                        }
                    }
                }
            }

            // Image preview
            selectedImageUri?.let { uri ->
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    AsyncImage(
                        model = uri, contentDescription = null,
                        modifier = Modifier.size(60.dp).clip(RoundedCornerShape(8.dp)),
                        contentScale = ContentScale.Crop
                    )
                    Spacer(Modifier.width(8.dp))
                    Text("图片已附加", style = MaterialTheme.typography.bodySmall)
                    Spacer(Modifier.weight(1f))
                    IconButton(onClick = {
                        selectedImageUri = null; selectedImageBase64 = null
                    }) { Icon(Icons.Default.Close, contentDescription = "移除") }
                }
            }

            // Tool status
            toolStatus?.let { status -> ToolStatusBar(status, toolRunning) }

            // Input bar
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Media picker button
                Box {
                    IconButton(onClick = { showMediaMenu = true }) {
                        Icon(Icons.Default.Add, contentDescription = "添加")
                    }
                    DropdownMenu(
                        expanded = showMediaMenu,
                        onDismissRequest = { showMediaMenu = false }
                    ) {
                        DropdownMenuItem(
                            text = { Text("📷 拍照") },
                            onClick = {
                                showMediaMenu = false
                                if (ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA)
                                    == PackageManager.PERMISSION_GRANTED
                                ) {
                                    val photoFile = File(context.cacheDir, "cam_${System.currentTimeMillis()}.jpg")
                                    cameraPhotoUri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", photoFile)
                                    cameraLauncher.launch(cameraPhotoUri!!)
                                } else {
                                    cameraPermissionLauncher.launch(Manifest.permission.CAMERA)
                                }
                            }
                        )
                        DropdownMenuItem(
                            text = { Text("🖼️ 相册") },
                            onClick = {
                                showMediaMenu = false
                                galleryLauncher.launch("image/*")
                            }
                        )
                    }
                }

                TextField(
                    value = input,
                    onValueChange = { input = it },
                    modifier = Modifier.weight(1f),
                    placeholder = { Text("输入消息...") },
                    maxLines = 4
                )

                Spacer(Modifier.width(4.dp))

                if (isStreaming) {
                    IconButton(onClick = { vm.stopGeneration() }) {
                        Icon(Icons.Default.Stop, contentDescription = "停止")
                    }
                }
                IconButton(
                    onClick = {
                        if (input.isNotBlank() || selectedImageBase64 != null) {
                            vm.sendMessage(input, selectedImageBase64, selectedImageMime)
                            input = ""; selectedImageUri = null; selectedImageBase64 = null
                        }
                    },
                    enabled = !isSending
                ) { Icon(Icons.Default.Send, contentDescription = "发送") }
            }
        }

        // Snackbar overlay for errors
        SnackbarHost(
            hostState = snackbarHostState,
            modifier = Modifier.align(Alignment.BottomCenter)
        )
    }
}

@Composable
fun ToolStatusBar(status: String, isRunning: Boolean) {
    val pulseAlpha by if (isRunning) {
        val t = rememberInfiniteTransition(label = "pulse")
        t.animateFloat(0.6f, 1f, infiniteRepeatable(tween(600, easing = LinearEasing), RepeatMode.Reverse), label = "a")
    } else { remember { mutableFloatStateOf(1f) } }

    Surface(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 2.dp),
        shape = RoundedCornerShape(8.dp),
        color = MaterialTheme.colorScheme.secondaryContainer
    ) {
        Row(modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            if (isRunning) {
                Box(Modifier.size(8.dp).alpha(pulseAlpha).clip(RoundedCornerShape(4.dp)).background(Color(0xFF4CAF50)))
                Spacer(Modifier.width(8.dp))
            }
            Text(text = status, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSecondaryContainer)
        }
    }
}

@Composable
fun MarkdownContent(
    text: String,
    isUser: Boolean,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val isDark = isSystemInDarkTheme()

    AndroidView(
        modifier = modifier.fillMaxWidth(),
        factory = { ctx ->
            WebView(ctx).apply {
                layoutParams = ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                )
                isVerticalScrollBarEnabled = false
                isHorizontalScrollBarEnabled = false
                settings.apply {
                    javaScriptEnabled = true
                    textZoom = 85
                    loadWithOverviewMode = true
                    useWideViewPort = false
                }
                webViewClient = object : WebViewClient() {
                    override fun onPageFinished(view: WebView, url: String?) {
                        view.setBackgroundColor(0)
                        val escaped = text
                            .replace("\\", "\\\\")
                            .replace("'", "\\'")
                            .replace("\n", "\\n")
                            .replace("\r", "\\r")
                        view.evaluateJavascript("render('$escaped', $isDark);", null)
                    }
                }
                loadUrl("file:///android_asset/message.html")
            }
        },
        update = { view ->
            view.setBackgroundColor(0)
            val escaped = text
                .replace("\\", "\\\\")
                .replace("'", "\\'")
                .replace("\n", "\\n")
                .replace("\r", "\\r")
            view.evaluateJavascript("render('$escaped', $isDark);", null)
        }
    )
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun MessageBubble(msg: MessageEntity, onDelete: () -> Unit = {}) {
    val context = LocalContext.current
    val clipboardManager = LocalClipboardManager.current
    val isUser = msg.role == "user"
    val hasImage = msg.imageBase64 != null
    val hasText = msg.content.isNotBlank() && msg.content != "[图片]"
    var showMenu by remember { mutableStateOf(false) }

    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
        horizontalArrangement = if (isUser) Arrangement.End else Arrangement.Start
    ) {
        Box {
            Surface(
                color = if (isUser) MaterialTheme.colorScheme.primaryContainer
                        else MaterialTheme.colorScheme.surfaceVariant,
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier.combinedClickable(
                    onClick = {},
                    onLongClick = { showMenu = true }
                )
            ) {
                Column(modifier = Modifier.padding(10.dp).widthIn(max = 280.dp)) {
                    if (hasImage) {
                        val bmp = rememberBase64Bitmap(msg.imageBase64!!)
                        bmp?.let {
                            Image(it.asImageBitmap(), "图片", Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)), contentScale = ContentScale.FillWidth)
                        }
                        if (hasText) Spacer(Modifier.height(6.dp))
                    }
                    if (hasText) MarkdownContent(text = msg.content, isUser = isUser)
                }
            }
            DropdownMenu(expanded = showMenu, onDismissRequest = { showMenu = false }) {
                DropdownMenuItem(
                    text = { Text("📋 复制") },
                    onClick = {
                        showMenu = false
                        if (hasText) {
                            clipboardManager.setText(AnnotatedString(msg.content))
                            Toast.makeText(context, "已复制", Toast.LENGTH_SHORT).show()
                        }
                    }
                )
                DropdownMenuItem(
                    text = { Text("🗑️ 删除", color = MaterialTheme.colorScheme.error) },
                    onClick = {
                        showMenu = false
                        onDelete()
                    }
                )
            }
        }
    }
}

@Composable
fun rememberBase64Bitmap(base64: String): Bitmap? {
    return remember(base64) {
        try {
            val bytes = Base64.decode(base64, Base64.DEFAULT)
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
        } catch (e: Exception) { null }
    }
}

private fun formatTime(timestamp: Long): String {
    val sdf = java.text.SimpleDateFormat("MM/dd HH:mm", java.util.Locale.getDefault())
    return sdf.format(java.util.Date(timestamp))
}
