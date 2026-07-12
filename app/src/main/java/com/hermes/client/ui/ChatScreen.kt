package com.hermes.client.ui

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageFormat
import android.graphics.Matrix
import android.graphics.Rect
import android.graphics.YuvImage
import android.net.Uri
import android.util.Base64
import android.view.ViewGroup
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.AspectRatio
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.viewmodel.compose.viewModel
import android.widget.Toast
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import coil.compose.AsyncImage
import com.hermes.client.data.MessageEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

@Composable
fun ChatScreen(vm: ChatViewModel = viewModel()) {
    val context = LocalContext.current
    val messages by vm.messages.collectAsState()
    val streamingContent by vm.streamingContent.collectAsState()
    val isStreaming by vm.isStreaming.collectAsState()
    val isSending by vm.isSending.collectAsState()
    val toolStatus by vm.toolStatus.collectAsState()
    val toolRunning by vm.toolRunning.collectAsState()

    var input by rememberSaveable { mutableStateOf("") }
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    // Track initial load so we jump to bottom instead of animate-scrolling from top
    var initialLoadDone by rememberSaveable { mutableStateOf(false) }

    var selectedImageUri by rememberSaveable { mutableStateOf<Uri?>(null) }
    var selectedImageBase64 by remember { mutableStateOf<String?>(null) }
    var selectedImageMime by remember { mutableStateOf("image/jpeg") }

    // In-app camera overlay
    var showCamera by remember { mutableStateOf(false) }

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
    LaunchedEffect(messages.size, streamingContent) {
        if (messages.isNotEmpty()) {
            if (!initialLoadDone) {
                listState.scrollToItem(messages.size - 1)
                initialLoadDone = true
            } else {
                listState.animateScrollToItem(messages.size - 1)
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
                        val clean = keyInput.replace(Regex("[^a-zA-Z0-9_\\-]"), "")
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

    // CameraX overlay
    if (showCamera) {
        CameraCaptureOverlay(
            onImageCaptured = { bytes ->
                scope.launch {
                    selectedImageMime = "image/jpeg"
                    selectedImageBase64 = Base64.encodeToString(bytes, Base64.NO_WRAP)
                    selectedImageUri = null // CameraX doesn't produce a URI
                }
                showCamera = false
            },
            onClose = { showCamera = false }
        )
    }

    Column(modifier = Modifier.fillMaxSize().statusBarsPadding()) {
        // Top bar with clear button and settings
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            IconButton(onClick = { showKeyDialog = true; keyInput = vm.apiKey }) {
                Icon(Icons.Default.Settings, contentDescription = "设置", modifier = Modifier.size(20.dp))
            }
            if (messages.isNotEmpty()) {
                TextButton(
                    onClick = { vm.clearMessages() },
                    colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)
                ) {
                    Icon(Icons.Default.Delete, contentDescription = "清空", modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("清空记录", style = MaterialTheme.typography.bodySmall)
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
                        Text(
                            text = streamingContent,
                            modifier = Modifier.padding(10.dp),
                            style = MaterialTheme.typography.bodyMedium
                        )
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
                            showCamera = true
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
}

@Composable
fun CameraCaptureOverlay(
    onImageCaptured: (ByteArray) -> Unit,
    onClose: () -> Unit
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val cameraProviderFuture = remember { ProcessCameraProvider.getInstance(context) }
    val imageCapture = remember { ImageCapture.Builder()
        .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
        .setTargetAspectRatio(AspectRatio.RATIO_4_3)
        .build()
    }
    val cameraExecutor = remember { Executors.newSingleThreadExecutor() }
    var cameraReady by remember { mutableStateOf(false) }
    var flashOn by remember { mutableStateOf(false) }

    DisposableEffect(Unit) {
        onDispose {
            cameraExecutor.shutdown()
        }
    }

    Box(modifier = Modifier.fillMaxSize().background(Color.Black)) {
        // Camera preview
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { ctx ->
                val previewView = PreviewView(ctx).apply {
                    layoutParams = ViewGroup.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT
                    )
                    scaleType = PreviewView.ScaleType.FILL_CENTER
                }
                cameraProviderFuture.addListener({
                    val cameraProvider = cameraProviderFuture.get()
                    val preview = Preview.Builder()
                        .setTargetAspectRatio(AspectRatio.RATIO_4_3)
                        .build()
                    preview.setSurfaceProvider(previewView.surfaceProvider)

                    val cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA

                    try {
                        cameraProvider.unbindAll()
                        cameraProvider.bindToLifecycle(
                            lifecycleOwner, cameraSelector, preview, imageCapture
                        )
                        cameraReady = true
                    } catch (e: Exception) {
                        e.printStackTrace()
                    }
                }, ContextCompat.getMainExecutor(ctx))
                previewView
            }
        )

        // Top close button
        IconButton(
            onClick = onClose,
            modifier = Modifier
                .align(Alignment.TopStart)
                .padding(16.dp)
                .background(Color.Black.copy(alpha = 0.5f), CircleShape)
        ) {
            Icon(Icons.Default.Close, "关闭", tint = Color.White)
        }

        // Bottom capture button
        Box(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 48.dp)
        ) {
            IconButton(
                onClick = {
                    if (!cameraReady) return@IconButton
                    imageCapture.takePicture(
                        ContextCompat.getMainExecutor(context),
                        object : ImageCapture.OnImageCapturedCallback() {
                            override fun onCaptureSuccess(image: ImageProxy) {
                                // Convert ImageProxy to JPEG bytes
                                val buffer = image.planes[0].buffer
                                val bytes = ByteArray(buffer.remaining())
                                buffer.get(bytes)

                                // For JPEG format, the bytes are ready for NV21/YUV_420_888 we compress
                                var jpegBytes = bytes
                                if (image.format == ImageFormat.JPEG) {
                                    jpegBytes = bytes
                                } else {
                                    // Convert YUV_420_888 or NV21 to JPEG
                                    val yuvImage = YuvImage(bytes, ImageFormat.NV21,
                                        image.width, image.height, null)
                                    val out = ByteArrayOutputStream()
                                    yuvImage.compressToJpeg(Rect(0, 0, image.width, image.height), 90, out)
                                    jpegBytes = out.toByteArray()
                                }
                                image.close()
                                onImageCaptured(jpegBytes)
                            }

                            override fun onError(exception: ImageCaptureException) {
                                Toast.makeText(context, "拍照失败: ${exception.message}", Toast.LENGTH_SHORT).show()
                            }
                        }
                    )
                },
                modifier = Modifier
                    .size(72.dp)
                    .background(Color.White, CircleShape)
            ) {
                Box(
                    modifier = Modifier
                        .size(62.dp)
                        .background(Color.Transparent, CircleShape)
                        .border(3.dp, Color.Black, CircleShape)
                )
            }
        }
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
                    if (hasText) Text(msg.content, style = MaterialTheme.typography.bodyMedium)
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