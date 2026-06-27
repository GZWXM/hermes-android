package com.hermes.client.ui

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.util.Base64
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Send
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.*
import androidx.compose.foundation.Image
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import coil.compose.AsyncImage
import com.hermes.client.data.MessageEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun ChatScreen(vm: ChatViewModel = viewModel()) {
    val context = LocalContext.current
    val messages by vm.messages.collectAsState()
    val streamingContent by vm.streamingContent.collectAsState()
    val isStreaming by vm.isStreaming.collectAsState()

    var input by rememberSaveable { mutableStateOf("") }
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()

    var selectedImageUri by rememberSaveable { mutableStateOf<Uri?>(null) }
    var selectedImageBase64 by remember { mutableStateOf<String?>(null) }
    var selectedImageMime by remember { mutableStateOf("image/jpeg") }

    val imagePicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        uri?.let {
            selectedImageUri = it
            scope.launch(Dispatchers.IO) {
                try {
                    val contentResolver = context.contentResolver
                    val mime = contentResolver.getType(it) ?: "image/jpeg"
                    val bytes = contentResolver.openInputStream(it)?.readBytes()
                    withContext(Dispatchers.Main) {
                        selectedImageMime = mime
                        selectedImageBase64 = bytes?.let { b ->
                            Base64.encodeToString(b, Base64.NO_WRAP)
                        }
                    }
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }
        }
    }

    LaunchedEffect(messages.size, streamingContent) {
        if (messages.isNotEmpty()) {
            listState.animateScrollToItem(messages.size)
        }
    }

    // 首次启动：填 API Key
    var showKeyDialog by remember { mutableStateOf(!vm.hasApiKey()) }
    var keyInput by remember { mutableStateOf("") }
    if (showKeyDialog) {
        AlertDialog(
            onDismissRequest = {},
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
                        if (keyInput.isNotBlank()) {
                            vm.setApiKey(keyInput.trim())
                            showKeyDialog = false
                        }
                    },
                    enabled = keyInput.isNotBlank()
                ) { Text("确定") }
            }
        )
    }

    Column(modifier = Modifier.fillMaxSize()) {
        LazyColumn(
            modifier = Modifier.weight(1f).fillMaxWidth(),
            state = listState,
            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            items(messages, key = { it.id }) { msg ->
                MessageBubble(msg)
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

        // 图片预览
        selectedImageUri?.let { uri ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                AsyncImage(
                    model = uri,
                    contentDescription = null,
                    modifier = Modifier
                        .size(60.dp)
                        .clip(RoundedCornerShape(8.dp)),
                    contentScale = ContentScale.Crop
                )
                Spacer(Modifier.width(8.dp))
                Text("图片已附加", style = MaterialTheme.typography.bodySmall)
                Spacer(Modifier.weight(1f))
                IconButton(onClick = {
                    selectedImageUri = null
                    selectedImageBase64 = null
                }) {
                    Icon(Icons.Default.Close, contentDescription = "移除")
                }
            }
        }

        // 输入栏
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(
                onClick = { imagePicker.launch("image/*") },
                enabled = !isStreaming
            ) {
                Icon(Icons.Default.Add, contentDescription = "添加图片")
            }

            TextField(
                value = input,
                onValueChange = { input = it },
                modifier = Modifier.weight(1f),
                placeholder = { Text("输入消息...") },
                maxLines = 4,
                enabled = !isStreaming
            )

            Spacer(Modifier.width(4.dp))

            if (isStreaming) {
                IconButton(onClick = { vm.stopGeneration() }) {
                    Icon(Icons.Default.Stop, contentDescription = "停止")
                }
            } else {
                IconButton(
                    onClick = {
                        if (input.isNotBlank() || selectedImageBase64 != null) {
                            vm.sendMessage(input, selectedImageBase64, selectedImageMime)
                            input = ""
                            selectedImageUri = null
                            selectedImageBase64 = null
                        }
                    }
                ) {
                    Icon(Icons.Default.Send, contentDescription = "发送")
                }
            }
        }
    }
}

@Composable
fun MessageBubble(msg: MessageEntity) {
    val isUser = msg.role == "user"
    val hasImage = msg.imageBase64 != null
    val hasText = msg.content.isNotBlank() && msg.content != "[图片]"

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 4.dp),
        horizontalArrangement = if (isUser) Arrangement.End else Arrangement.Start
    ) {
        Surface(
            color = if (isUser) MaterialTheme.colorScheme.primaryContainer
                    else MaterialTheme.colorScheme.surfaceVariant,
            shape = RoundedCornerShape(12.dp)
        ) {
            Column(modifier = Modifier.padding(10.dp).widthIn(max = 280.dp)) {
                if (hasImage) {
                    val bitmap = rememberBase64Bitmap(msg.imageBase64!!)
                    bitmap?.let { bmp ->
                        Image(
                            bitmap = bmp.asImageBitmap(),
                            contentDescription = "图片",
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(8.dp)),
                            contentScale = ContentScale.FillWidth
                        )
                    }
                    if (hasText) Spacer(Modifier.height(6.dp))
                }
                if (hasText) {
                    Text(
                        text = msg.content,
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
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
