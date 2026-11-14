package com.lm.player.core.designsystem.glass

import android.content.Context
import android.graphics.Bitmap
import android.graphics.drawable.BitmapDrawable
import androidx.compose.foundation.layout.Spacer
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import coil.imageLoader
import coil.request.ImageRequest
import coil.request.SuccessResult
import com.lm.player.core.designsystem.component.getGradientForId
import com.lm.player.core.designsystem.component.normalizeMusicCoverUrl
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 播放页「封面模糊底图」：玻璃按钮折射的背景来源（外面套 `Modifier.layerBackdrop` 供玻璃采样）。
 *
 * 位图只有 72px 见方（预先盒式模糊），放大到全屏时由双线性过滤自然柔化，开销可以忽略。
 * 上面压一层遮罩（深色压暗 / 浅色提亮）保证文字可读，下半屏逐渐过渡回播放页原底色。
 */
@Composable
fun CoverBackdrop(
    coverUrl: String?,
    seedId: String,
    isDark: Boolean,
    baseBottomColor: Color,
    modifier: Modifier = Modifier,
    /** 底图顶部的实际颜色（已叠遮罩），用于把系统状态栏染成同色，看起来与封面底图连成一片 */
    onTopColor: (Color) -> Unit = {}
) {
    val context = LocalContext.current
    val cleanUrl = remember(coverUrl) { normalizeMusicCoverUrl(coverUrl) }

    val fallback = remember(seedId) {
        val g = getGradientForId(seedId.ifBlank { "seed" })
        g.first() to g.last()
    }
    val scrim = if (isDark) Color.Black.copy(alpha = 0.50f) else Color.White.copy(alpha = 0.55f)

    // 先用缓存（再次打开播放页 / 切回同一首时立刻就位，不闪默认色），再按需加载
    var image by remember(cleanUrl) {
        mutableStateOf(cleanUrl?.let { CoverBackdropCache.bitmap(it) }?.asImageBitmap())
    }
    val latestOnTopColor = rememberUpdatedState(onTopColor)
    LaunchedEffect(cleanUrl, isDark) {
        val bitmap = if (cleanUrl == null) null
        else CoverBackdropCache.bitmap(cleanUrl) ?: loadBlurredCover(context, cleanUrl)?.also {
            CoverBackdropCache.put(cleanUrl, it)
        }
        image = bitmap?.asImageBitmap()
        val top = bitmap?.let { averageTopColor(it) } ?: fallback.first
        val composed = compositeOver(top, scrim)
        CoverBackdropCache.putTopColor(coverUrl, isDark, composed)
        latestOnTopColor.value(composed)
    }

    Spacer(
        modifier = modifier.drawBehind {
            val w = size.width
            val h = size.height
            if (w <= 0f || h <= 0f) return@drawBehind
            val img = image
            if (img != null) {
                // centerCrop
                val scale = maxOf(w / img.width, h / img.height)
                val srcW = (w / scale).toInt().coerceIn(1, img.width)
                val srcH = (h / scale).toInt().coerceIn(1, img.height)
                drawImage(
                    image = img,
                    srcOffset = IntOffset((img.width - srcW) / 2, (img.height - srcH) / 2),
                    srcSize = IntSize(srcW, srcH),
                    dstSize = IntSize(w.toInt(), h.toInt()),
                    filterQuality = FilterQuality.Low
                )
            } else {
                drawRect(Brush.linearGradient(listOf(fallback.first, fallback.second), start = Offset.Zero, end = Offset(w, h)))
            }
            drawRect(scrim)
            // 下半屏逐渐过渡回播放页原底色：控件、进度条区域对比度稳定
            drawRect(
                brush = Brush.verticalGradient(
                    listOf(baseBottomColor.copy(alpha = 0f), baseBottomColor.copy(alpha = 0.92f)),
                    startY = h * 0.45f,
                    endY = h
                ),
                topLeft = Offset(0f, h * 0.45f),
                size = Size(w, h * 0.55f)
            )
        }
    )
}

/** 封面底图与其顶部颜色的小缓存（只存几张 72px 位图） */
object CoverBackdropCache {
    private val bitmaps = android.util.LruCache<String, Bitmap>(8)
    private val topColors = android.util.LruCache<String, Int>(32)

    internal fun bitmap(url: String): Bitmap? = bitmaps.get(url)
    internal fun put(url: String, bitmap: Bitmap) { bitmaps.put(url, bitmap) }

    internal fun putTopColor(coverUrl: String?, isDark: Boolean, color: Color) {
        topColors.put("${coverUrl.orEmpty()}|$isDark", color.toArgb())
    }

    /** 播放页一打开就能拿到上次算好的状态栏颜色（没有缓存时返回 null，用默认色） */
    fun peekTopColor(coverUrl: String?, isDark: Boolean): Color? =
        topColors.get("${coverUrl.orEmpty()}|$isDark")?.let { Color(it) }
}

private const val BACKDROP_SIZE = 72

private suspend fun loadBlurredCover(context: Context, url: String): Bitmap? {
    val result = runCatching {
        val request = ImageRequest.Builder(context)
            .data(url)
            .size(BACKDROP_SIZE * 2)
            .allowHardware(false)
            .build()
        context.imageLoader.execute(request)
    }.getOrNull() as? SuccessResult ?: return null
    val source = (result.drawable as? BitmapDrawable)?.bitmap ?: return null
    return withContext(Dispatchers.Default) {
        runCatching {
            val small = Bitmap.createScaledBitmap(source, BACKDROP_SIZE, BACKDROP_SIZE, true)
                .copy(Bitmap.Config.ARGB_8888, true)
            boxBlur(small, radius = 3, passes = 3)
            small
        }.getOrNull()
    }
}

/** 三遍盒式模糊 ≈ 高斯模糊，72px 位图上耗时不到 1ms */
private fun boxBlur(bitmap: Bitmap, radius: Int, passes: Int) {
    val w = bitmap.width
    val h = bitmap.height
    val pixels = IntArray(w * h)
    val buffer = IntArray(w * h)
    bitmap.getPixels(pixels, 0, w, 0, 0, w, h)
    repeat(passes) {
        blurLine(pixels, buffer, w, h, radius, horizontal = true)
        blurLine(buffer, pixels, w, h, radius, horizontal = false)
    }
    bitmap.setPixels(pixels, 0, w, 0, 0, w, h)
}

private fun blurLine(src: IntArray, dst: IntArray, w: Int, h: Int, r: Int, horizontal: Boolean) {
    val lines = if (horizontal) h else w
    val len = if (horizontal) w else h
    val window = r * 2 + 1
    for (line in 0 until lines) {
        var sa = 0; var sr = 0; var sg = 0; var sb = 0
        fun idx(i: Int): Int {
            val c = i.coerceIn(0, len - 1)
            return if (horizontal) line * w + c else c * w + line
        }
        for (i in -r..r) {
            val p = src[idx(i)]
            sa += p ushr 24; sr += (p shr 16) and 0xFF; sg += (p shr 8) and 0xFF; sb += p and 0xFF
        }
        for (i in 0 until len) {
            dst[idx(i)] = ((sa / window) shl 24) or ((sr / window) shl 16) or ((sg / window) shl 8) or (sb / window)
            val out = src[idx(i - r)]
            val inn = src[idx(i + r + 1)]
            sa += (inn ushr 24) - (out ushr 24)
            sr += ((inn shr 16) and 0xFF) - ((out shr 16) and 0xFF)
            sg += ((inn shr 8) and 0xFF) - ((out shr 8) and 0xFF)
            sb += (inn and 0xFF) - (out and 0xFF)
        }
    }
}

/** 位图顶部 15% 的平均色（底图位图很小，逐像素累加即可） */
private fun averageTopColor(bitmap: Bitmap): Color {
    val w = bitmap.width
    val rows = (bitmap.height * 0.15f).toInt().coerceAtLeast(1)
    var r = 0L; var g = 0L; var b = 0L; var n = 0L
    for (y in 0 until rows) {
        for (x in 0 until w) {
            val p = bitmap.getPixel(x, y)
            r += (p shr 16) and 0xFF; g += (p shr 8) and 0xFF; b += p and 0xFF; n++
        }
    }
    if (n == 0L) return Color.Black
    return Color((r / n).toInt(), (g / n).toInt(), (b / n).toInt())
}

/** 遮罩（带 alpha）叠在不透明底色上的结果 */
private fun compositeOver(base: Color, overlay: Color): Color {
    val a = overlay.alpha
    return Color(
        red = overlay.red * a + base.red * (1f - a),
        green = overlay.green * a + base.green * (1f - a),
        blue = overlay.blue * a + base.blue * (1f - a),
        alpha = 1f
    )
}
