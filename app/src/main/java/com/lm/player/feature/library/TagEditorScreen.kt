package com.lm.player.feature.library

import android.graphics.BitmapFactory
import android.widget.Toast
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import coil.compose.AsyncImage
import com.lm.player.core.designsystem.component.AlbumArtworkImage
import com.lm.player.core.designsystem.theme.AppleRed
import com.lm.player.core.media.LyricsManager
import com.lm.player.core.model.UnifiedSong
import com.lm.player.core.network.LemonMusicProtocol
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject

/**
 * 在线修标签写回成功后上报的已保存字段快照 (null = 本次未写该字段)。
 * 宿主据此把新元数据同步到播放队列 / 本地曲库缓存 / 封面缓存等展示层。
 */
data class TagSavedMetadata(
    val songId: String,
    val title: String? = null,
    val artist: String? = null,
    val album: String? = null,
    /** 本次写回了封面 (宿主应基于原 coverUrl 追加时间戳参数刷新图片缓存) */
    val coverSaved: Boolean = false
)

/**
 * 在线修标签弹窗 (仅服务器 lemon_music 曲库歌曲可用)
 *
 * 流程：读取当前标签 (/api/tag/read) → 选择音源在线匹配 (/api/tag/match, 5 源) →
 * 点选候选获取全量元数据与封面/歌词预览 (/api/tag/match-apply) →
 * 勾选字段写回音频文件 (/api/tag/write，服务端写入后自动刷新曲库缓存)。
 */
@Composable
fun TagEditorDialog(
    song: UnifiedSong,
    onDismiss: () -> Unit,
    /** 歌词写回成功后回调 (宿主清除歌词缓存并重新加载播放页歌词) */
    onLyricsUpdated: () -> Unit = {},
    /** 标签写回成功后回调，携带本次实际写入的字段 (宿主同步播放队列/本地库/封面展示) */
    onMetadataSaved: (TagSavedMetadata) -> Unit = {}
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val isDark = MaterialTheme.colorScheme.background.red < 0.5f
    val surfaceBg = if (isDark) Color(0xFF222228) else Color.White
    val borderColor = if (isDark) Color.White.copy(alpha = 0.12f) else Color.Black.copy(alpha = 0.08f)
    val subtleCardBg = if (isDark) Color.White.copy(alpha = 0.06f) else Color.Black.copy(alpha = 0.04f)

    // ---------- 文件路径与当前标签 ----------
    var filePath by remember { mutableStateOf<String?>(null) }
    var isReading by remember { mutableStateOf(true) }
    var readNotice by remember { mutableStateOf("") }
    // tagRead 读出的原始标签快照 (用于勾选行的新旧值对比文案)
    var curTitle by remember { mutableStateOf("") }
    var curArtist by remember { mutableStateOf("") }
    var curAlbumArtist by remember { mutableStateOf("") }
    var curAlbum by remember { mutableStateOf("") }
    var curYear by remember { mutableStateOf("") }
    var curGenre by remember { mutableStateOf("") }
    // 可编辑标签输入框 (预填 tagRead 结果；用户可手动修改，也可由在线匹配结果自动填入)
    var editTitle by remember { mutableStateOf("") }
    var editArtist by remember { mutableStateOf("") }
    var editAlbumArtist by remember { mutableStateOf("") }
    var editAlbum by remember { mutableStateOf("") }
    var editYear by remember { mutableStateOf("") }
    var editGenre by remember { mutableStateOf("") }

    // ---------- 在线匹配 ----------
    var selectedSource by remember { mutableStateOf("wy") }
    var isMatching by remember { mutableStateOf(false) }
    var matchError by remember { mutableStateOf("") }
    var candidates by remember { mutableStateOf<JSONArray?>(null) }
    var selectedCandidateIndex by remember { mutableStateOf(-1) }

    // ---------- 应用预览 ----------
    var isApplying by remember { mutableStateOf(false) }
    var applyError by remember { mutableStateOf("") }
    var applied by remember { mutableStateOf<JSONObject?>(null) }
    var appliedCoverBitmap by remember { mutableStateOf<android.graphics.Bitmap?>(null) }

    // ---------- 字段勾选 (默认全选，勾选=保存时写入该字段输入框当前值) ----------
    // 默认不勾选：只有与当前标签不同的字段才会被自动勾选，避免把没改动的值重复写回文件
    var selTitle by remember { mutableStateOf(false) }
    var selArtist by remember { mutableStateOf(false) }
    var selAlbumArtist by remember { mutableStateOf(false) }
    var selAlbum by remember { mutableStateOf(false) }
    var selYear by remember { mutableStateOf(false) }
    var selGenre by remember { mutableStateOf(false) }
    // 手动修改区默认收起（先在线匹配，匹配不到再展开）
    var manualMode by remember { mutableStateOf(false) }
    var selCover by remember { mutableStateOf(false) }
    var selLyric by remember { mutableStateOf(false) }

    // ---------- 保存 ----------
    var isSaving by remember { mutableStateOf(false) }
    var saveError by remember { mutableStateOf("") }
    // 写回前二次确认：会直接修改服务器上的音乐文件，且无法撤销
    var pendingWriteMeta by remember { mutableStateOf<JSONObject?>(null) }

    val appliedTitle = applied?.optText("title") ?: ""
    val appliedArtist = applied?.optText("artist", "singer") ?: ""
    val appliedAlbumArtist = applied?.optText("albumArtist", "album_artist", "albumartist") ?: ""
    val appliedAlbum = applied?.optText("album") ?: ""
    val appliedYear = applied?.optText("year", "yearStr") ?: ""
    val appliedGenre = applied?.optText("genre") ?: ""
    val appliedLyric = applied?.optText("lyric", "lrc") ?: ""
    val appliedPicUrl = applied?.optText("picUrl", "pic_url", "coverUrl") ?: ""
    val hasAppliedCover = appliedCoverBitmap != null || appliedPicUrl.isNotBlank()
    val hasAppliedLyric = appliedLyric.isNotBlank()

    val isBusy = isReading || isMatching || isApplying || isSaving

    // 打开弹窗：解析 NAS 真实文件路径并读取当前标签 (失败显示为空)
    LaunchedEffect(song.id) {
        val path = LemonMusicProtocol.getServerFilePath(song.id, song.streamUrl, song.coverUrl)
        if (path.isNullOrBlank()) {
            isReading = false
            readNotice = "未能定位该歌曲在 NAS 上的文件路径，无法读取与写回"
            return@LaunchedEffect
        }
        filePath = path
        LemonMusicProtocol.tagRead(path, context)
            .onSuccess { data ->
                curTitle = data.optText("title")
                curArtist = data.optText("artist", "singer")
                curAlbumArtist = data.optText("albumArtist", "album_artist", "albumartist")
                curAlbum = data.optText("album")
                curYear = data.optText("year", "yearStr")
                curGenre = data.optText("genre")
                // 预填可编辑输入框
                editTitle = curTitle
                editArtist = curArtist
                editAlbumArtist = curAlbumArtist
                editAlbum = curAlbum
                editYear = curYear
                editGenre = curGenre
            }
            .onFailure {
                // 规格约定：read 失败显示为空，不阻断后续匹配流程
                readNotice = "当前标签读取失败，下方显示为空"
            }
        isReading = false
    }

    fun startMatch() {
        scope.launch {
            isMatching = true
            matchError = ""
            candidates = null
            selectedCandidateIndex = -1
            applied = null
            appliedCoverBitmap = null
            applyError = ""
            val path = filePath
            val fileName = path?.replace('\\', '/')?.substringAfterLast('/').orEmpty()
            // 匹配关键词取输入框当前值：用户可先手动改成正确名字再匹配
            val artist = editArtist.trim().takeUnless { it.isBlank() || it == "未知歌手" }.orEmpty()
            val album = editAlbum.trim().takeUnless { it.isBlank() || it == "未知专辑" }.orEmpty()
            val titleParam = editTitle.trim()
            val keyword = listOf(titleParam, artist).filter { it.isNotBlank() }.joinToString(" ")
            LemonMusicProtocol.tagMatch(
                fileName = fileName,
                keyword = keyword,
                artist = artist,
                title = titleParam,
                album = album,
                source = selectedSource,
                context = context
            )
                .onSuccess { arr ->
                    candidates = arr
                    if (arr.length() == 0) matchError = "该音源未找到候选结果，请换一个音源试试"
                }
                .onFailure { matchError = it.message ?: "在线匹配失败" }
            isMatching = false
        }
    }

    fun applyCandidate(index: Int) {
        val cand = candidates?.optJSONObject(index) ?: return
        selectedCandidateIndex = index
        scope.launch {
            isApplying = true
            applyError = ""
            applied = null
            appliedCoverBitmap = null
            LemonMusicProtocol.tagMatchApply(cand, selectedSource, context)
                .onSuccess { data ->
                    applied = data
                    appliedCoverBitmap = decodeCoverBitmap(data.optText("pic", "cover", "coverBase64"))
                    selCover = appliedCoverBitmap != null || data.optText("picUrl", "pic_url", "coverUrl").isNotBlank()
                    selLyric = data.optText("lyric", "lrc").isNotBlank()
                    // 匹配值自动填入输入框 (覆盖当前值，用户仍可手改)，并自动勾选有值的字段
                    val mTitle = data.optText("title")
                    val mArtist = data.optText("artist", "singer")
                    val mAlbumArtist = data.optText("albumArtist", "album_artist", "albumartist")
                    val mAlbum = data.optText("album")
                    val mYear = data.optText("year", "yearStr")
                    val mGenre = data.optText("genre")
                    // 只勾选与当前标签不同的字段
                    if (mTitle.isNotBlank()) { editTitle = mTitle; selTitle = mTitle != curTitle }
                    if (mArtist.isNotBlank()) { editArtist = mArtist; selArtist = mArtist != curArtist }
                    if (mAlbumArtist.isNotBlank()) { editAlbumArtist = mAlbumArtist; selAlbumArtist = mAlbumArtist != curAlbumArtist }
                    if (mAlbum.isNotBlank()) { editAlbum = mAlbum; selAlbum = mAlbum != curAlbum }
                    if (mYear.isNotBlank()) { editYear = mYear; selYear = mYear != curYear }
                    if (mGenre.isNotBlank()) { editGenre = mGenre; selGenre = mGenre != curGenre }
                }
                .onFailure { applyError = it.message ?: "获取匹配详情失败" }
            isApplying = false
        }
    }

    /** 第一步：按勾选组装 meta，弹出确认框（真正写入在 [writeConfirmed]） */
    fun save() {
        if (filePath == null) { saveError = "未能定位服务器文件路径"; return }
        saveError = ""
        // meta 只带勾选字段；文本字段取输入框当前值 (可能是手动编辑过的)
        val meta = JSONObject()
        if (selTitle && editTitle.isNotBlank()) meta.put("title", editTitle.trim())
        if (selArtist && editArtist.isNotBlank()) meta.put("artist", editArtist.trim())
        if (selAlbumArtist && editAlbumArtist.isNotBlank()) meta.put("albumArtist", editAlbumArtist.trim())
        if (selAlbum && editAlbum.isNotBlank()) meta.put("album", editAlbum.trim())
        if (selYear && editYear.isNotBlank()) meta.put("year", editYear.trim())
        if (selGenre && editGenre.isNotBlank()) meta.put("genre", editGenre.trim())
        val app = applied
        if (selCover && hasAppliedCover && app != null) {
            val pic = app.optText("pic", "cover", "coverBase64")
            if (pic.isNotBlank()) meta.put("pic", pic)
            val picUrl = app.optText("picUrl", "pic_url", "coverUrl")
            if (picUrl.isNotBlank()) meta.put("picUrl", picUrl)
        }
        if (selLyric && hasAppliedLyric) meta.put("lyric", appliedLyric)
        if (meta.length() == 0) {
            saveError = if (applied == null && !manualMode) {
                "请先在线匹配并选择结果，或点「手动修改」修改后再保存"
            } else {
                "没有要写入的改动：请修改内容或勾选要写回的字段"
            }
            return
        }
        pendingWriteMeta = meta
    }

    /** 第二步：用户确认后才写回服务器文件 */
    fun writeConfirmed(meta: JSONObject) {
        val path = filePath ?: run { saveError = "未能定位服务器文件路径"; return }
        scope.launch {
            isSaving = true
            saveError = ""
            LemonMusicProtocol.tagWrite(path, meta, context)
                .onSuccess {
                    isSaving = false
                    Toast.makeText(context, "标签已写回音频文件", Toast.LENGTH_SHORT).show()
                    // 上报本次实际写入的字段：meta 只包含勾选且有值的键，缺省即未写
                    onMetadataSaved(
                        TagSavedMetadata(
                            songId = song.id,
                            title = meta.optString("title").ifBlank { null },
                            artist = meta.optString("artist").ifBlank { null },
                            album = meta.optString("album").ifBlank { null },
                            coverSaved = meta.has("pic") || meta.has("picUrl")
                        )
                    )
                    if (meta.has("lyric")) {
                        // 歌词已写回文件：失效歌词缓存并通知宿主重新加载播放页歌词 (歌曲 id 不变)
                        LyricsManager.invalidateLyrics(song.id)
                        onLyricsUpdated()
                    }
                    onDismiss()
                }
                .onFailure {
                    isSaving = false
                    saveError = it.message ?: "写回失败，请重试"
                }
        }
    }

    // 布局（按维护者要求调整）：先在线匹配，匹配不到再点底部「手动修改」展开输入框。
    // 适配：不用平台默认宽度、按钮与输入框只设最小高度，文字单行省略，避免小屏 / 大字号下被遮挡。
    Dialog(
        onDismissRequest = { if (!isSaving) onDismiss() },
        properties = androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Surface(
            shape = RoundedCornerShape(24.dp),
            color = surfaceBg,
            border = BorderStroke(1.dp, borderColor),
            shadowElevation = 24.dp,
            modifier = Modifier
                .fillMaxWidth(0.92f)
                .widthIn(max = 520.dp)
                .heightIn(max = 680.dp)
                .padding(vertical = 12.dp)
        ) {
            Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 16.dp)) {
                // 1. 顶部：标题 + 歌曲信息 + 关闭
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "在线修标签",
                            fontSize = 17.sp,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurface,
                            maxLines = 1
                        )
                        Text(
                            text = "${song.title} · ${song.artist}",
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                    IconButton(onClick = onDismiss, enabled = !isSaving) {
                        Icon(
                            imageVector = Icons.Default.Close,
                            contentDescription = "关闭",
                            modifier = Modifier.size(20.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                Spacer(modifier = Modifier.height(8.dp))

                Column(
                    modifier = Modifier
                        .weight(1f, fill = false)
                        .verticalScroll(rememberScrollState())
                ) {
                    // 2. 当前标签概览：封面 + 标题 / 歌手 / 专辑（只读）
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(modifier = Modifier.size(64.dp)) {
                            when {
                                applied != null && selCover && appliedCoverBitmap != null -> Image(
                                    bitmap = appliedCoverBitmap!!.asImageBitmap(),
                                    contentDescription = "新封面预览",
                                    contentScale = ContentScale.Crop,
                                    modifier = Modifier
                                        .fillMaxSize()
                                        .clip(RoundedCornerShape(12.dp))
                                )
                                applied != null && selCover && appliedPicUrl.isNotBlank() -> AsyncImage(
                                    model = appliedPicUrl,
                                    contentDescription = "新封面预览",
                                    contentScale = ContentScale.Crop,
                                    modifier = Modifier
                                        .fillMaxSize()
                                        .clip(RoundedCornerShape(12.dp))
                                )
                                else -> AlbumArtworkImage(
                                    model = song.coverUrl,
                                    seedId = song.id,
                                    modifier = Modifier.fillMaxSize(),
                                    cornerRadius = 12.dp
                                )
                            }
                            if (applied != null && selCover && hasAppliedCover) {
                                Surface(
                                    shape = RoundedCornerShape(5.dp),
                                    color = AppleRed,
                                    modifier = Modifier
                                        .align(Alignment.BottomStart)
                                        .padding(3.dp)
                                ) {
                                    Text(
                                        text = "新封面",
                                        color = Color.White,
                                        fontSize = 9.sp,
                                        fontWeight = FontWeight.Bold,
                                        maxLines = 1,
                                        modifier = Modifier.padding(horizontal = 4.dp, vertical = 1.dp)
                                    )
                                }
                            }
                        }
                        Spacer(modifier = Modifier.width(12.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            if (isReading) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    CircularProgressIndicator(
                                        modifier = Modifier.size(14.dp),
                                        strokeWidth = 1.6.dp,
                                        color = AppleRed
                                    )
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Text(
                                        text = "正在读取当前标签...",
                                        fontSize = 12.sp,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        maxLines = 1
                                    )
                                }
                            } else {
                                Text(
                                    text = "当前标签",
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1
                                )
                                TagSummaryLine("标题", curTitle)
                                TagSummaryLine("歌手", curArtist)
                                TagSummaryLine("专辑", curAlbum)
                            }
                        }
                    }
                    if (readNotice.isNotBlank()) {
                        Spacer(modifier = Modifier.height(6.dp))
                        Text(
                            text = readNotice,
                            fontSize = 11.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.8f)
                        )
                    }

                    Spacer(modifier = Modifier.height(12.dp))
                    HorizontalDivider(color = borderColor, thickness = 0.6.dp)
                    Spacer(modifier = Modifier.height(10.dp))

                    // 3. 在线匹配（放在最上方：先匹配，匹配不到再手动修改）
                    Text(
                        text = "在线匹配",
                        fontSize = 12.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        listOf(
                            "wy" to "网易云",
                            "kw" to "酷我",
                            "kg" to "酷狗",
                            "tx" to "QQ",
                            "mg" to "咪咕"
                        ).forEach { (key, label) ->
                            val isSelected = selectedSource == key
                            Surface(
                                shape = RoundedCornerShape(8.dp),
                                color = if (isSelected) AppleRed.copy(alpha = 0.12f) else subtleCardBg,
                                border = BorderStroke(1.dp, if (isSelected) AppleRed else borderColor.copy(alpha = 0.4f)),
                                modifier = Modifier
                                    .weight(1f)
                                    .clip(RoundedCornerShape(8.dp))
                                    .clickable(enabled = !isBusy) { selectedSource = key }
                            ) {
                                Box(
                                    modifier = Modifier.padding(vertical = 7.dp, horizontal = 2.dp),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Text(
                                        text = label,
                                        fontSize = 11.sp,
                                        fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                                        color = if (isSelected) AppleRed else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.8f),
                                        maxLines = 1,
                                        softWrap = false,
                                        overflow = TextOverflow.Clip
                                    )
                                }
                            }
                        }
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                    Button(
                        onClick = { startMatch() },
                        enabled = !isBusy,
                        colors = ButtonDefaults.buttonColors(containerColor = AppleRed),
                        shape = RoundedCornerShape(12.dp),
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 40.dp)
                    ) {
                        if (isMatching) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(16.dp),
                                strokeWidth = 2.dp,
                                color = Color.White
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("正在匹配（约 3-8 秒）...", fontSize = 13.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        } else {
                            Icon(Icons.Default.Search, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("开始匹配", fontSize = 13.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
                        }
                    }

                    if (matchError.isNotBlank()) {
                        Spacer(modifier = Modifier.height(6.dp))
                        Text(
                            text = "$matchError；也可以点下方「手动修改」自己填写",
                            fontSize = 11.sp,
                            color = MaterialTheme.colorScheme.error
                        )
                    }

                    // 候选列表（标题 / 歌手 / 专辑）
                    val candArr = candidates
                    if (candArr != null && candArr.length() > 0) {
                        Spacer(modifier = Modifier.height(8.dp))
                        Column(
                            modifier = Modifier.fillMaxWidth(),
                            verticalArrangement = Arrangement.spacedBy(5.dp)
                        ) {
                            for (i in 0 until candArr.length()) {
                                val item = candArr.optJSONObject(i) ?: continue
                                val cTitle = item.optText("title", "name", "song")
                                val cArtist = item.optText("artist", "singer")
                                val cAlbum = item.optText("album", "albumName")
                                val isSelected = selectedCandidateIndex == i
                                Surface(
                                    shape = RoundedCornerShape(10.dp),
                                    color = if (isSelected) AppleRed.copy(alpha = 0.10f) else subtleCardBg,
                                    border = BorderStroke(1.dp, if (isSelected) AppleRed else borderColor.copy(alpha = 0.35f)),
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clip(RoundedCornerShape(10.dp))
                                        .clickable(enabled = !isBusy) { applyCandidate(i) }
                                ) {
                                    Column(modifier = Modifier.padding(horizontal = 10.dp, vertical = 7.dp)) {
                                        Text(
                                            text = cTitle.ifBlank { "（未知标题）" },
                                            fontSize = 12.5.sp,
                                            fontWeight = FontWeight.SemiBold,
                                            color = if (isSelected) AppleRed else MaterialTheme.colorScheme.onSurface,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                        Text(
                                            text = listOf(cArtist, cAlbum).filter { it.isNotBlank() }
                                                .joinToString(" • ").ifBlank { " " },
                                            fontSize = 11.sp,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                    }
                                }
                            }
                        }
                    }

                    // 4. 匹配结果预览：标题 / 歌手 / 专辑 + 歌词前 3 行
                    if (isApplying) {
                        Spacer(modifier = Modifier.height(10.dp))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(14.dp),
                                strokeWidth = 1.6.dp,
                                color = AppleRed
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = "正在获取封面与歌词...",
                                fontSize = 12.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1
                            )
                        }
                    }
                    if (applyError.isNotBlank()) {
                        Spacer(modifier = Modifier.height(6.dp))
                        Text(text = applyError, fontSize = 11.sp, color = MaterialTheme.colorScheme.error)
                    }
                    if (applied != null && !isApplying) {
                        Spacer(modifier = Modifier.height(10.dp))
                        Surface(
                            shape = RoundedCornerShape(10.dp),
                            color = subtleCardBg,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column(modifier = Modifier.padding(10.dp)) {
                                Text(
                                    text = "匹配结果预览",
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = AppleRed
                                )
                                Spacer(modifier = Modifier.height(5.dp))
                                TagSummaryLine("标题", appliedTitle, fontSize = 11.5f)
                                TagSummaryLine("歌手", appliedArtist, fontSize = 11.5f)
                                TagSummaryLine("专辑", appliedAlbum, fontSize = 11.5f)
                                if (hasAppliedLyric) {
                                    Spacer(modifier = Modifier.height(5.dp))
                                    val preview = appliedLyric
                                        .lines()
                                        .filter { it.isNotBlank() && !it.trim().startsWith("[") }
                                        .ifEmpty { appliedLyric.lines().filter { it.isNotBlank() } }
                                        .take(3)
                                        .joinToString("\n")
                                    Text(
                                        text = "歌词预览：\n$preview",
                                        fontSize = 11.sp,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        maxLines = 4,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }
                            }
                        }
                    }

                    // 5. 手动修改（点底部「手动修改」展开）：输入框预填当前值或匹配值，改动后自动勾选该字段
                    if (manualMode && !isReading) {
                        Spacer(modifier = Modifier.height(12.dp))
                        HorizontalDivider(color = borderColor, thickness = 0.6.dp)
                        Spacer(modifier = Modifier.height(10.dp))
                        Text(
                            text = "手动修改",
                            fontSize = 12.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(modifier = Modifier.height(6.dp))
                        Column(
                            modifier = Modifier.fillMaxWidth(),
                            verticalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            TagEditField("标题", editTitle, !isBusy) { editTitle = it; selTitle = it.trim() != curTitle }
                            TagEditField("歌手", editArtist, !isBusy) { editArtist = it; selArtist = it.trim() != curArtist }
                            TagEditField("专辑歌手", editAlbumArtist, !isBusy) { editAlbumArtist = it; selAlbumArtist = it.trim() != curAlbumArtist }
                            TagEditField("专辑", editAlbum, !isBusy) { editAlbum = it; selAlbum = it.trim() != curAlbum }
                            TagEditField("年份", editYear, !isBusy) { editYear = it; selYear = it.trim() != curYear }
                            TagEditField("流派", editGenre, !isBusy) { editGenre = it; selGenre = it.trim() != curGenre }
                        }
                    }

                    // 6. 要写回的字段：只有匹配了结果或打开了手动修改时才显示；默认只勾选与当前不同的字段
                    if (!isReading && (applied != null || manualMode)) {
                        Spacer(modifier = Modifier.height(12.dp))
                        Text(
                            text = "选择要写回的字段",
                            fontSize = 12.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(modifier = Modifier.height(2.dp))
                        TagFieldCheckbox("标题", changeDetail(curTitle, editTitle), selTitle, editTitle.isNotBlank()) { selTitle = it }
                        TagFieldCheckbox("歌手", changeDetail(curArtist, editArtist), selArtist, editArtist.isNotBlank()) { selArtist = it }
                        TagFieldCheckbox("专辑歌手", changeDetail(curAlbumArtist, editAlbumArtist), selAlbumArtist, editAlbumArtist.isNotBlank()) { selAlbumArtist = it }
                        TagFieldCheckbox("专辑", changeDetail(curAlbum, editAlbum), selAlbum, editAlbum.isNotBlank()) { selAlbum = it }
                        TagFieldCheckbox("年份", changeDetail(curYear, editYear), selYear, editYear.isNotBlank()) { selYear = it }
                        TagFieldCheckbox("流派", changeDetail(curGenre, editGenre), selGenre, editGenre.isNotBlank()) { selGenre = it }
                        if (applied != null && !isApplying) {
                            TagFieldCheckbox(
                                "封面",
                                if (hasAppliedCover) "已获取新封面" else "该结果未提供封面",
                                selCover,
                                hasAppliedCover
                            ) { selCover = it }
                            TagFieldCheckbox(
                                "歌词",
                                if (hasAppliedLyric) "已获取新歌词" else "该结果未提供歌词",
                                selLyric,
                                hasAppliedLyric
                            ) { selLyric = it }
                        }
                    }

                    if (saveError.isNotBlank()) {
                        Spacer(modifier = Modifier.height(6.dp))
                        Text(text = saveError, fontSize = 11.sp, color = MaterialTheme.colorScheme.error)
                    }
                }

                Spacer(modifier = Modifier.height(12.dp))

                // 7. 底部按钮：[手动修改] [保存到文件]（关闭用右上角 ×）
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    OutlinedButton(
                        onClick = { manualMode = !manualMode },
                        enabled = !isBusy,
                        shape = RoundedCornerShape(14.dp),
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 10.dp),
                        modifier = Modifier
                            .weight(1f)
                            .heightIn(min = 44.dp),
                        border = BorderStroke(1.dp, if (manualMode) AppleRed else borderColor)
                    ) {
                        Text(
                            text = if (manualMode) "收起手动修改" else "手动修改",
                            color = if (manualMode) AppleRed else MaterialTheme.colorScheme.onSurface,
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Medium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                    Button(
                        onClick = { save() },
                        enabled = !isBusy && filePath != null,
                        colors = ButtonDefaults.buttonColors(containerColor = AppleRed),
                        shape = RoundedCornerShape(14.dp),
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 10.dp),
                        modifier = Modifier
                            .weight(1f)
                            .heightIn(min = 44.dp)
                    ) {
                        if (isSaving) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(16.dp),
                                strokeWidth = 2.dp,
                                color = Color.White
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("正在写回...", fontSize = 14.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
                        } else {
                            Icon(Icons.Default.Edit, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                "保存到文件",
                                fontSize = 14.sp,
                                fontWeight = FontWeight.SemiBold,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                }
            }
        }
    }

    // 写回前二次确认：列出将写入的字段，提醒会直接修改服务器文件且无法撤销
    val confirmMeta = pendingWriteMeta
    if (confirmMeta != null) {
        val fieldLabels = listOf(
            "title" to "标题", "artist" to "歌手", "albumArtist" to "专辑歌手", "album" to "专辑",
            "year" to "年份", "genre" to "流派", "lyric" to "歌词"
        ).filter { confirmMeta.has(it.first) }.map { it.second } +
            (if (confirmMeta.has("pic") || confirmMeta.has("picUrl")) listOf("封面") else emptyList())
        val fileName = filePath?.replace('\\', '/')?.substringAfterLast('/').orEmpty()
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { pendingWriteMeta = null },
            title = { Text("确认写回服务器文件？") },
            text = {
                Text(
                    "将直接修改服务器上的音乐文件，修改后无法在 App 内撤销。\n\n" +
                        "文件：$fileName\n写入字段：${fieldLabels.joinToString("、")}",
                    fontSize = 13.sp
                )
            },
            confirmButton = {
                androidx.compose.material3.TextButton(onClick = {
                    pendingWriteMeta = null
                    writeConfirmed(confirmMeta)
                }) { Text("确认写回", color = AppleRed, fontWeight = FontWeight.SemiBold) }
            },
            dismissButton = {
                androidx.compose.material3.TextButton(onClick = { pendingWriteMeta = null }) { Text("取消") }
            }
        )
    }
}

/** 只读标签行：「标题：xxx」，单行省略 */
@Composable
private fun TagSummaryLine(label: String, value: String, fontSize: Float = 12f) {
    Text(
        text = "$label：${value.ifBlank { "—" }}",
        fontSize = fontSize.sp,
        color = MaterialTheme.colorScheme.onSurface,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis
    )
}

/** 标签可编辑输入框 (跟随 App 现有 Material3 OutlinedTextField 风格) */
@Composable
private fun TagEditField(
    label: String,
    value: String,
    enabled: Boolean,
    onValueChange: (String) -> Unit
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(label) },
        singleLine = true,
        enabled = enabled,
        textStyle = TextStyle(fontSize = 13.sp),
        modifier = Modifier.fillMaxWidth()
    )
}

/** 字段勾选行 */
@Composable
private fun TagFieldCheckbox(
    label: String,
    detail: String,
    checked: Boolean,
    enabled: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .clickable(enabled = enabled) { onCheckedChange(!checked) }
            .padding(vertical = 1.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Checkbox(
            checked = checked,
            onCheckedChange = { if (enabled) onCheckedChange(it) },
            enabled = enabled,
            colors = CheckboxDefaults.colors(checkedColor = AppleRed),
            modifier = Modifier.size(20.dp)
        )
        Spacer(modifier = Modifier.width(6.dp))
        Text(
            text = label,
            fontSize = 12.sp,
            fontWeight = FontWeight.Medium,
            color = if (enabled) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.45f)
        )
        Spacer(modifier = Modifier.width(8.dp))
        Text(
            text = detail,
            fontSize = 10.5.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = if (enabled) 0.9f else 0.4f),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

/** JSONObject 安全取字符串（跳过 null/空/"null"） */
private fun JSONObject.optText(vararg keys: String): String {
    for (key in keys) {
        val v = opt(key) ?: continue
        if (v == JSONObject.NULL) continue
        val s = v.toString().trim()
        if (s.isNotBlank() && !s.equals("null", ignoreCase = true) && !s.equals("undefined", ignoreCase = true)) {
            return s
        }
    }
    return ""
}

/** 勾选行 detail 文案（当前 → 新值） */
private fun changeDetail(current: String, newValue: String): String = when {
    newValue.isBlank() -> "无新值"
    current.isBlank() -> "将写入：$newValue"
    newValue == current -> "与当前相同"
    else -> "${current.ifBlank { "—" }} → $newValue"
}

/** 解码 base64 / dataURI 封面为 Bitmap（失败返回 null） */
private fun decodeCoverBitmap(raw: String): android.graphics.Bitmap? {
    if (raw.isBlank()) return null
    val b64 = if (raw.startsWith("data:")) raw.substringAfter("base64,", "") else raw
    if (b64.isBlank()) return null
    return runCatching {
        val bytes = android.util.Base64.decode(b64, android.util.Base64.DEFAULT)
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
    }.getOrNull()
}
