package com.lm.player.feature.settings

import android.os.Environment
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.lm.player.core.designsystem.theme.AppleRed
import java.io.File
import java.util.Locale

/**
 * 仿资料库本地文件夹风格的离线存储目录选择器
 * - 支持全盘层级下钻与返回上级目录
 * - 支持创建新文件夹
 * - 支持一键选定当前目录或恢复默认目录
 */
@Composable
fun LocalFolderPickerDialog(
    initialPath: String = "",
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit
) {
    val context = LocalContext.current
    val defaultStorageDir = remember {
        val external = Environment.getExternalStorageDirectory()
        if (external != null && external.exists()) external else File("/storage/emulated/0")
    }

    var currentDir by remember {
        val initialFile = if (initialPath.isNotBlank()) File(initialPath) else null
        mutableStateOf(if (initialFile != null && initialFile.exists() && initialFile.isDirectory) initialFile else defaultStorageDir)
    }

    // 新建文件夹弹窗状态
    var showCreateDirDialog by remember { mutableStateOf(false) }
    var newDirName by remember { mutableStateOf("") }

    // 获取当前目录下的所有子文件夹与文件 (文件夹置顶)
    val currentItems = remember(currentDir) {
        try {
            currentDir.listFiles()?.filter { !it.name.startsWith(".") }
                ?.sortedWith(compareBy({ !it.isDirectory }, { it.name.lowercase() }))
                ?: emptyList()
        } catch (e: Exception) {
            emptyList()
        }
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Surface(
            shape = RoundedCornerShape(20.dp),
            color = MaterialTheme.colorScheme.surface,
            tonalElevation = 6.dp,
            modifier = Modifier
                .fillMaxWidth(0.92f)
                .fillMaxHeight(0.82f)
                .padding(vertical = 16.dp)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(20.dp)
            ) {
                // 1. 顶部 Header
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Surface(
                            shape = RoundedCornerShape(10.dp),
                            color = AppleRed.copy(alpha = 0.12f),
                            modifier = Modifier.size(36.dp)
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Icon(
                                    imageVector = Icons.Default.FolderOpen,
                                    contentDescription = null,
                                    tint = AppleRed,
                                    modifier = Modifier.size(20.dp)
                                )
                            }
                        }
                        Spacer(modifier = Modifier.width(10.dp))
                        Column {
                            Text(
                                text = "选择离线存储目录",
                                style = TextStyle(fontSize = 17.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurface)
                            )
                            Text(
                                text = "歌曲文件将保存至此文件夹",
                                style = TextStyle(fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            )
                        }
                    }

                    // 新建文件夹按钮
                    IconButton(
                        onClick = {
                            newDirName = ""
                            showCreateDirDialog = true
                        }
                    ) {
                        Icon(
                            imageVector = Icons.Default.CreateNewFolder,
                            contentDescription = "新建文件夹",
                            tint = AppleRed,
                            modifier = Modifier.size(24.dp)
                        )
                    }
                }

                Spacer(modifier = Modifier.height(14.dp))

                // 2. 当前路径指示条与上级目录按钮
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 10.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        val parent = currentDir.parentFile
                        val canGoUp = parent != null && parent.canRead() && currentDir.absolutePath != "/"
                        IconButton(
                            onClick = {
                                if (canGoUp && parent != null) {
                                    currentDir = parent
                                }
                            },
                            enabled = canGoUp,
                            modifier = Modifier.size(28.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.ArrowBackIosNew,
                                contentDescription = "上一级",
                                tint = if (canGoUp) AppleRed else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.3f),
                                modifier = Modifier.size(14.dp)
                            )
                        }

                        Spacer(modifier = Modifier.width(6.dp))

                        Text(
                            text = currentDir.absolutePath,
                            style = TextStyle(fontSize = 12.sp, fontWeight = FontWeight.Medium, color = AppleRed),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f)
                        )
                    }
                }

                Spacer(modifier = Modifier.height(12.dp))

                // 3. 当前目录下的子文件夹与文件列表
                Box(modifier = Modifier.weight(1f)) {
                    if (currentItems.isEmpty()) {
                        Box(
                            modifier = Modifier.fillMaxSize(),
                            contentAlignment = Alignment.Center
                        ) {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Icon(
                                    imageVector = Icons.Default.FolderOff,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f),
                                    modifier = Modifier.size(44.dp)
                                )
                                Spacer(modifier = Modifier.height(8.dp))
                                Text(
                                    text = "该文件夹为空",
                                    style = TextStyle(fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                )
                                Text(
                                    text = "可点击右上角新建文件夹，或直接选择当前目录",
                                    style = TextStyle(fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f))
                                )
                            }
                        }
                    } else {
                        LazyColumn(
                            modifier = Modifier.fillMaxSize(),
                            verticalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            items(currentItems, key = { it.absolutePath }) { item ->
                                if (item.isDirectory) {
                                    val childItems = try { item.listFiles()?.filter { !it.name.startsWith(".") } ?: emptyList() } catch (_: Exception) { emptyList() }
                                    val subDirs = childItems.count { it.isDirectory }
                                    val subFiles = childItems.count { !it.isDirectory }
                                    val subtitle = when {
                                        subDirs > 0 && subFiles > 0 -> "$subDirs 个子目录 • $subFiles 个文件"
                                        subDirs > 0 -> "$subDirs 个子目录"
                                        subFiles > 0 -> "$subFiles 个文件"
                                        else -> "空文件夹"
                                    }

                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .clip(RoundedCornerShape(12.dp))
                                            .clickable {
                                                currentDir = item
                                            }
                                            .padding(horizontal = 10.dp, vertical = 10.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Icon(
                                            imageVector = Icons.Default.Folder,
                                            contentDescription = null,
                                            tint = AppleRed,
                                            modifier = Modifier.size(28.dp)
                                        )
                                        Spacer(modifier = Modifier.width(12.dp))
                                        Column(modifier = Modifier.weight(1f)) {
                                            Text(
                                                text = item.name,
                                                style = TextStyle(fontSize = 14.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurface),
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis
                                            )
                                            Text(
                                                text = subtitle,
                                                style = TextStyle(fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                            )
                                        }
                                        Icon(
                                            imageVector = Icons.Default.ChevronRight,
                                            contentDescription = null,
                                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                            modifier = Modifier.size(18.dp)
                                        )
                                    }
                                } else {
                                    val ext = item.extension.lowercase()
                                    val isAudio = ext in listOf("flac", "mp3", "wav", "m4a", "aac", "ape", "dsf", "dff", "ogg")
                                    val sizeMb = item.length().toFloat() / (1024 * 1024)
                                    val sizeText = if (sizeMb >= 1f) String.format(Locale.getDefault(), "%.1f MB", sizeMb) else "${item.length() / 1024} KB"

                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .clip(RoundedCornerShape(12.dp))
                                            .padding(horizontal = 10.dp, vertical = 8.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Icon(
                                            imageVector = if (isAudio) Icons.Default.MusicNote else Icons.Default.InsertDriveFile,
                                            contentDescription = null,
                                            tint = if (isAudio) AppleRed.copy(alpha = 0.8f) else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                                            modifier = Modifier.size(24.dp)
                                        )
                                        Spacer(modifier = Modifier.width(12.dp))
                                        Column(modifier = Modifier.weight(1f)) {
                                            Text(
                                                text = item.name,
                                                style = TextStyle(fontSize = 13.sp, fontWeight = FontWeight.Medium, color = MaterialTheme.colorScheme.onSurface),
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis
                                            )
                                            Text(
                                                text = "${item.extension.uppercase().ifBlank { "FILE" }} • $sizeText",
                                                style = TextStyle(fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.height(14.dp))

                // 4. 底部操作栏
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        onClick = {
                            onConfirm(currentDir.absolutePath)
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = AppleRed),
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(44.dp)
                    ) {
                        Icon(Icons.Default.Check, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(text = "选择此文件夹作为存储目录", fontWeight = FontWeight.Bold, fontSize = 14.sp)
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        OutlinedButton(
                            onClick = {
                                onConfirm("") // 传空代表恢复默认目录
                            },
                            shape = RoundedCornerShape(12.dp),
                            modifier = Modifier.weight(1f)
                        ) {
                            Text("恢复默认目录", fontSize = 13.sp)
                        }

                        TextButton(
                            onClick = onDismiss,
                            modifier = Modifier.weight(1f)
                        ) {
                            Text("取消", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }
        }
    }

    // 新建文件夹弹窗
    if (showCreateDirDialog) {
        AlertDialog(
            onDismissRequest = { showCreateDirDialog = false },
            title = { Text(text = "新建文件夹", fontWeight = FontWeight.Bold) },
            text = {
                OutlinedTextField(
                    value = newDirName,
                    onValueChange = { newDirName = it },
                    label = { Text("文件夹名称") },
                    placeholder = { Text("如: ZDSMusic") },
                    singleLine = true,
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.fillMaxWidth()
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        val name = newDirName.trim()
                        if (name.isNotBlank()) {
                            val newFolder = File(currentDir, name)
                            if (!newFolder.exists()) {
                                if (newFolder.mkdirs()) {
                                    Toast.makeText(context, "文件夹「$name」创建成功", Toast.LENGTH_SHORT).show()
                                    currentDir = newFolder
                                } else {
                                    Toast.makeText(context, "创建失败，请检查存储权限", Toast.LENGTH_SHORT).show()
                                }
                            } else {
                                Toast.makeText(context, "同名文件夹已存在", Toast.LENGTH_SHORT).show()
                                currentDir = newFolder
                            }
                            showCreateDirDialog = false
                        }
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = AppleRed)
                ) {
                    Text("创建")
                }
            },
            dismissButton = {
                TextButton(onClick = { showCreateDirDialog = false }) {
                    Text("取消")
                }
            }
        )
    }
}
