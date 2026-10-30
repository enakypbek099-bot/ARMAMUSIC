package kz.armamus.app.ui

/*
 * Порт полного экрана плеера (#player) из armamus.html.
 * Источник состояния воспроизведения — PlayerUiState; список Track для cover-flow
 * приходит отдельным параметром `queue`. Все команды идут в существующий PlaybackController.
 */

import android.graphics.BlurMaskFilter
import android.graphics.Paint as NPaint
import androidx.activity.compose.BackHandler
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.Easing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.CanvasDrawScope
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.hsl
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import androidx.media3.common.Player
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kz.armamus.app.library.ArtworkStore
import kz.armamus.app.library.Track
import kz.armamus.app.playback.PlaybackController
import kz.armamus.app.playback.PlayerUiState
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sign
import kotlin.math.sin
import kotlin.math.sqrt

/** Значок формата (fmtBadge из HTML): Hi-RES + разрядность либо FLAC/MP3/... */
data class FpBadge(val label: String, val hiRes: Boolean = false, val bits: Int? = null)

/* ───────────────────────── адаптеры к проекту ───────────────────────── */

private class Snap(
    val index: Int,
    val isPlaying: Boolean,
    val positionMs: Long,
    val durationMs: Long,
    val shuffle: Boolean,
    /** 0 = выкл, 1 = очередь по кругу, 2 = один трек (как S.repeat в HTML) */
    val repeat: Int,
)

private fun PlayerUiState.snap(): Snap = Snap(
    index = currentIndex,
    isPlaying = isPlaying,
    positionMs = positionMs,
    durationMs = durationMs,
    shuffle = shuffleEnabled,
    repeat = when (repeatMode) {
        Player.REPEAT_MODE_ONE -> 2
        Player.REPEAT_MODE_ALL -> 1
        else -> 0
    },
)

private const val UNKNOWN_ARTIST = "Неизвестный исполнитель"

private fun Track.artistText(): String = artist?.takeIf { it.isNotBlank() } ?: UNKNOWN_ARTIST

private fun ArtworkStore.largeFor(t: Track, maxSidePx: Int): ImageBitmap? =
    loadLarge(t.id, maxSidePx)?.asImageBitmap()

/** Значок формата только из реальных полей Track (sampleRateHz/bitsPerSample/fileName/mimeType). */
private fun defaultBadge(t: Track): FpBadge? {
    val ext = t.fileName.substringAfterLast('.', "").lowercase()
    val mime = (t.mimeType ?: "").lowercase()
    val lossless = ext == "flac" || ext == "wav" ||
        mime.contains("flac") || mime.contains("wav") || mime.contains("alac")
    val bits = t.bitsPerSample
    if (lossless && bits != null && bits >= 24) return FpBadge("Hi-RES", hiRes = true, bits = bits)
    val label = when {
        ext == "flac" || mime.contains("flac") -> "FLAC"
        ext == "wav" || mime.contains("wav") -> "WAV"
        mime.contains("alac") -> "ALAC"
        ext == "mp3" || mime.contains("mpeg") -> "MP3"
        ext == "m4a" || ext == "aac" || ext == "mp4" -> "AAC"
        ext == "ogg" -> "OGG"
        ext == "opus" -> "OPUS"
        ext == "wma" -> "WMA"
        else -> return null
    }
    return FpBadge(label)
}

/* ───────────────────────── палитра (финальные :root из HTML) ───────────────────────── */

private class Pal(val dark: Boolean) {
    val bg0 = if (dark) Color(0xFF0A0B0E) else Color(0xFFE6E2DA)
    val tx = if (dark) Color(0xFFF2F3F7) else Color(0xFF1B1A22)
    val t2 = if (dark) Color(0x99E2E5F0) else Color(0x9E282632)
    val t3 = if (dark) Color(0x4DE2E5F0) else Color(0x52282632)
    val wc = if (dark) Color.White else Color.Black
    val ac = if (dark) Color(0xFF8A7BFF) else Color(0xFF6B4CF2)
    val ac2 = Color(0xFF41D6FF)
    val slabA = if (dark) Color(0xFF1B1E25) else Color(0xFFF3F0EA)
    val slabB = if (dark) Color(0xFF12141A) else Color(0xFFE2DED5)
    val pl = if (dark) 0.20f else 0.84f

    fun slab(size: Size): Brush = diag(size, slabA, slabB)
}

/** linear-gradient(145deg, a, b) */
private fun diag(size: Size, a: Color, b: Color): Brush {
    val rad = Math.toRadians(145.0)
    val dx = sin(rad).toFloat()
    val dy = (-cos(rad)).toFloat()
    val len = abs(size.width * dx) + abs(size.height * dy)
    val c = Offset(size.width / 2f, size.height / 2f)
    val v = Offset(dx * len / 2f, dy * len / 2f)
    return Brush.linearGradient(listOf(a, b), start = c - v, end = c + v)
}

/* ───────────────────────── тени (box-shadow) ───────────────────────── */

private fun blurR(b: Float): Float = if (b <= 0f) 0f else max(0.5f, 0.866f * b - 0.87f)

private fun shadowPaint(color: Color, blur: Float): NPaint =
    NPaint(NPaint.ANTI_ALIAS_FLAG).apply {
        this.color = color.toArgb()
        val br = blurR(blur)
        if (br > 0f) maskFilter = BlurMaskFilter(br, BlurMaskFilter.Blur.NORMAL)
    }

private fun DrawScope.outerShadow(
    color: Color, dx: Float, dy: Float, blur: Float, spread: Float, radius: Float,
    w: Float = size.width, h: Float = size.height,
) {
    val l = -spread + dx
    val t = -spread + dy
    val r = w + spread + dx
    val b = h + spread + dy
    if (r <= l || b <= t) return
    val rad = max(0f, radius + spread)
    drawContext.canvas.nativeCanvas.drawRoundRect(l, t, r, b, rad, rad, shadowPaint(color, blur))
}

private fun DrawScope.insetShadow(
    color: Color, dx: Float, dy: Float, blur: Float, radius: Float,
    w: Float = size.width, h: Float = size.height,
) {
    val shape = android.graphics.Path().apply {
        addRoundRect(0f, 0f, w, h, radius, radius, android.graphics.Path.Direction.CW)
    }
    val pad = blur * 3f + max(abs(dx), abs(dy)) + 16f
    val ring = android.graphics.Path().apply {
        fillType = android.graphics.Path.FillType.EVEN_ODD
        addRect(-pad, -pad, w + pad, h + pad, android.graphics.Path.Direction.CW)
        addRoundRect(0f, 0f, w, h, radius, radius, android.graphics.Path.Direction.CW)
    }
    val c = drawContext.canvas.nativeCanvas
    c.save()
    c.clipPath(shape)
    c.translate(dx, dy)
    c.drawPath(ring, shadowPaint(color, blur))
    c.restore()
}

/** --raise-s + slab-фон */
private fun DrawScope.drawRaisedS(p: Pal, r: Float) {
    val d = density
    if (p.dark) {
        outerShadow(Color(0x8C000000), 3 * d, 5 * d, 10 * d, 0f, r)
        outerShadow(Color(0x08FFFFFF), -2 * d, -2 * d, 7 * d, 0f, r)
    } else {
        outerShadow(Color(0x6B968A76), 3 * d, 5 * d, 10 * d, 0f, r)
        outerShadow(Color(0xD9FFFFFF), -3 * d, -3 * d, 8 * d, 0f, r)
    }
    drawRoundRect(p.slab(size), cornerRadius = CornerRadius(r))
    insetShadow(if (p.dark) Color(0x14FFFFFF) else Color(0xB3FFFFFF), 0f, d, 0f, r)
}

/** внутренние тени --well без заливки */
private fun DrawScope.drawWellInsets(p: Pal, r: Float) {
    val d = density
    if (p.dark) {
        insetShadow(Color(0xA6000000), 3 * d, 4 * d, 9 * d, r)
        insetShadow(Color(0x09FFFFFF), -2 * d, -2 * d, 6 * d, r)
    } else {
        insetShadow(Color(0x80968A76), 3 * d, 4 * d, 9 * d, r)
        insetShadow(Color(0xCCFFFFFF), -3 * d, -3 * d, 8 * d, r)
    }
}

/** --well + фон bg0 */
private fun DrawScope.drawWell(p: Pal, r: Float) {
    if (p.dark) outerShadow(Color(0x0DFFFFFF), 0f, 0.5f * density, 0f, 0f, r)
    drawRoundRect(p.bg0, cornerRadius = CornerRadius(r))
    drawWellInsets(p, r)
}

/* ───────────────────────── иконки (пути из объекта I в HTML) ───────────────────────── */

private enum class Ic { Down, More, Heart, Shuffle, Prev, Next, Play, Pause, Repeat, Repeat1 }

private fun pp(d: String): Path = PathParser().parsePathString(d).toPath()

private val P_DOWN = pp("M5 9l7 7 7-7")
private val P_HEART = pp("M12 21s-8-5.2-9.6-10.1C1.3 7.5 3.4 4.5 6.8 4.5c2 0 3.7 1.1 5.2 3 1.5-1.9 3.2-3 5.2-3 3.4 0 5.5 3 4.4 6.4C20 15.8 12 21 12 21z")
private val P_SHUFFLE = pp("M3 7h3.5c4 0 5 10 9.5 10H21M3 17h3.5c1.6 0 2.7-1.2 3.6-2.6M13.9 9.5C14.8 8.1 15.2 7 16.5 7H21M18.5 4.5L21 7l-2.5 2.5M18.5 14.5L21 17l-2.5 2.5")
private val P_PREV = pp("M18 5.8v12.4a.8.8 0 0 1-1.2.7l-8.6-6.2a.8.8 0 0 1 0-1.3l8.6-5.3A.8.8 0 0 1 18 5.8z")
private val P_NEXT = pp("M6 5.8v12.4a.8.8 0 0 0 1.2.7l8.6-6.2a.8.8 0 0 0 0-1.3L7.2 5.1A.8.8 0 0 0 6 5.8z")
private val P_PLAY = pp("M8 5.2v13.6c0 .8.9 1.3 1.6.9l10.6-6.8c.6-.4.6-1.3 0-1.7L9.6 4.3C8.9 3.9 8 4.4 8 5.2z")
private val P_REPEAT = pp("M17 2l3 3-3 3M4 11V9a4 4 0 0 1 4-4h12M7 22l-3-3 3-3M20 13v2a4 4 0 0 1-4 4H4")
private val P_REPEAT1 = pp("M17 2l3 3-3 3M4 11V9a4 4 0 0 1 4-4h12M7 22l-3-3 3-3M20 13v2a4 4 0 0 1-4 4H4M11 10l1.4-1v6")

@Composable
private fun Icon24(ic: Ic, color: Color, size: Dp, filled: Boolean = false) {
    Canvas(Modifier.size(size)) {
        val s = this.size.width / 24f
        val stroke = Stroke(width = 1.8f, cap = StrokeCap.Round, join = StrokeJoin.Round)
        scale(s, s, pivot = Offset.Zero) {
            when (ic) {
                Ic.Down -> drawPath(P_DOWN, color, style = stroke)
                Ic.More -> {
                    drawCircle(color, 1.6f, Offset(5f, 12f))
                    drawCircle(color, 1.6f, Offset(12f, 12f))
                    drawCircle(color, 1.6f, Offset(19f, 12f))
                }
                Ic.Heart -> {
                    if (filled) drawPath(P_HEART, color)
                    drawPath(P_HEART, color, style = stroke)
                }
                Ic.Shuffle -> drawPath(P_SHUFFLE, color, style = stroke)
                Ic.Prev -> {
                    drawPath(P_PREV, color)
                    drawRoundRect(color, Offset(4.8f, 5f), Size(2.4f, 14f), CornerRadius(1.1f))
                }
                Ic.Next -> {
                    drawPath(P_NEXT, color)
                    drawRoundRect(color, Offset(16.8f, 5f), Size(2.4f, 14f), CornerRadius(1.1f))
                }
                Ic.Play -> drawPath(P_PLAY, color)
                Ic.Pause -> {
                    drawRoundRect(color, Offset(6.5f, 5f), Size(4f, 14f), CornerRadius(1.3f))
                    drawRoundRect(color, Offset(13.5f, 5f), Size(4f, 14f), CornerRadius(1.3f))
                }
                Ic.Repeat -> drawPath(P_REPEAT, color, style = stroke)
                Ic.Repeat1 -> drawPath(P_REPEAT1, color, style = stroke)
            }
        }
    }
}

/* ───────────────────────── обложка-заглушка: cover() / rng() / hashS() из HTML ───────────────────────── */

private fun hashS(s: String): Int {
    var h = 0
    for (ch in s) h = h * 31 + ch.code
    return h
}

private class Rng(seed: String) {
    private var h: Int = hashS(seed).let { if (it == 0) 1 else it }
    fun next(): Double {
        h += 0x6D2B79F5
        var t = (h xor (h ushr 15)) * (1 or h)
        t = (t + ((t xor (t ushr 7)) * (61 or t))) xor t
        return ((t xor (t ushr 14)).toLong() and 0xFFFFFFFFL).toDouble() / 4294967296.0
    }
}

private fun hueOf(seed: String): Int = floor(Rng(seed).next() * 360.0).toInt()
private fun tseed(t: Track): String = t.artistText() + "|" + t.title
private fun tkind(t: Track): Int =
    ((hashS(t.title + t.artistText()).toLong() and 0xFFFFFFFFL) % 3L).toInt()

private fun hslc(h: Int, s: Int, l: Float): Color =
    Color.hsl((((h % 360) + 360) % 360).toFloat(), s / 100f, (l / 100f).coerceIn(0f, 1f))

private fun renderFallback(seed: String, kind: Int, px: Int): ImageBitmap {
    val bmp = ImageBitmap(px, px)
    val canvas = androidx.compose.ui.graphics.Canvas(bmp)
    CanvasDrawScope().draw(Density(1f), LayoutDirection.Ltr, canvas, Size(px.toFloat(), px.toFloat())) {
        val k = px / 100f
        val r = Rng(seed)
        val h1 = floor(r.next() * 360.0).toInt()
        val h2 = (h1 + 40 + floor(r.next() * 80.0).toInt()) % 360
        val h3 = (h2 + 40 + floor(r.next() * 80.0).toInt()) % 360
        when (kind) {
            0 -> {
                drawRect(hslc(h1, 85, 13f))
                val n = 4
                val c = 100f / n
                for (y in 0 until n) for (x in 0 until n) {
                    val o = floor(r.next() * 4.0).toInt()
                    val l = (32.0 + r.next() * 38.0).toFloat()
                    val xx = x * c
                    val yy = y * c
                    val pts = mutableListOf(
                        xx to yy, (xx + c) to yy, (xx + c) to (yy + c), xx to (yy + c),
                    )
                    pts.removeAt(o)
                    val hue = h1 + (if (r.next() < 0.3) 22 else 0)
                    val path = Path().apply {
                        moveTo(pts[0].first * k, pts[0].second * k)
                        for (i in 1 until pts.size) lineTo(pts[i].first * k, pts[i].second * k)
                        close()
                    }
                    drawPath(path, hslc(hue, 92, l))
                }
            }
            1 -> {
                drawRect(
                    Brush.verticalGradient(
                        0f to hslc(h1, 85, 52f),
                        0.5f to hslc(h2, 85, 50f),
                        1f to hslc(h3, 90, 55f),
                    )
                )
                var x = 0f
                while (x < 100f) {
                    val a = (r.next() * 0.3).toFloat()
                    drawRect(Color.Black.copy(alpha = a), Offset(x * k, 0f), Size(0.7f * k, px.toFloat()))
                    x += 1.6f
                }
            }
            else -> {
                drawRect(hslc(h1, 60, 17f))
                val n = 7
                val c = 100f / n
                for (y in 0 until n) for (x in 0 until n) {
                    val s = floor(50.0 + r.next() * 40.0 + 0.5).toInt()
                    val l = floor(25.0 + r.next() * 50.0 + 0.5).toFloat()
                    val op = (0.35 + r.next() * 0.65).toFloat()
                    drawRect(
                        hslc(h1, s, l).copy(alpha = op),
                        Offset(x * c * k, y * c * k),
                        Size(c * k + 0.5f, c * k + 0.5f),
                    )
                }
            }
        }
    }
    return bmp
}

@Composable
private fun TrackArt(track: Track, store: ArtworkStore, maxSidePx: Int, modifier: Modifier = Modifier) {
    val art by produceState<ImageBitmap?>(null, track.id, maxSidePx) {
        value = withContext(Dispatchers.IO) {
            try {
                store.largeFor(track, maxSidePx)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                null
            }
        }
    }
    val fallback = remember(track.id, track.title, track.artist) {
        renderFallback(tseed(track), tkind(track), 360)
    }
    Image(
        bitmap = art ?: fallback,
        contentDescription = null,
        contentScale = ContentScale.Crop,
        modifier = modifier.fillMaxSize(),
    )
}

/* ───────────────────────── мелочи ───────────────────────── */

private fun fmt(totalSec: Long): String {
    val s = max(0L, totalSec)
    return "${s / 60}:${(s % 60).toString().padStart(2, '0')}"
}

private val EaseOut3 = Easing { f -> 1f - (1f - f) * (1f - f) * (1f - f) }
private val EaseInOut3 = Easing { k -> if (k < 0.5f) 4f * k * k * k else 1f - (-2f * k + 2f).pow(3) / 2f }

private fun Modifier.expandH(m: Dp): Modifier = layout { measurable, c ->
    val e = m.roundToPx() * 2
    val w = c.maxWidth + e
    val p = measurable.measure(c.copy(minWidth = w, maxWidth = w))
    layout(c.maxWidth, p.height) { p.place(-e / 2, 0) }
}

/* ───────────────────────── cover flow ───────────────────────── */

private class CfGeo(val x: Float, val z: Float, val sc: Float, val ab: Float, val a: Float)

private fun cfGeo(i: Int, p: Float, bk: Float, cs: Float, big: Float): CfGeo {
    val o = i - p
    val a = o.coerceIn(-1f, 1f)
    val ab = abs(o)
    val sc = 1f + (big - 1f) * bk * max(0f, 1f - ab)
    val x = a * 0.62f * cs + (o - a) * 0.2f * cs + a * (big - 1f) * cs * 0.5f * bk
    val z = -abs(a) * cs * 0.45f
    return CfGeo(x, z, sc, ab, a)
}

private suspend fun cfGo(pos: Animatable<Float, androidx.compose.animation.core.AnimationVector1D>, target: Float) {
    var from = pos.value
    if (abs(target - from) > 1.5f) {
        from = target - sign(target - from) * 1.5f
        pos.snapTo(from)
    }
    pos.animateTo(target, tween(380, easing = EaseOut3))
}

@Composable
private fun CoverFlow(
    pal: Pal,
    queue: List<Track>,
    index: Int,
    store: ArtworkStore,
    controller: PlaybackController,
    modifier: Modifier,
) {
    val density = LocalDensity.current
    val dens = density.density
    val cfg = LocalConfiguration.current
    val csPx = with(density) { min(min(cfg.screenWidthDp * 0.52f, cfg.screenHeightDp * 0.41f), 300f).dp.toPx() }
    val csDp = with(density) { csPx.toDp() }
    var sz by remember { mutableStateOf(IntSize.Zero) }
    val n = queue.size
    val sig = "$n:${queue.firstOrNull()?.id}:${queue.lastOrNull()?.id}"
    val pos = remember { Animatable(max(index, 0).toFloat()) }
    val bk = remember { Animatable(0f) }
    var dragging by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val idxNow by rememberUpdatedState(index)
    val nNow by rememberUpdatedState(n)
    val szNow by rememberUpdatedState(sz)

    LaunchedEffect(sig) { pos.snapTo(max(index, 0).toFloat()) }
    LaunchedEffect(index) {
        if (index >= 0 && !dragging && pos.targetValue != index.toFloat()) cfGo(pos, index.toFloat())
    }

    fun bigScale(): Float {
        val s = szNow
        if (s.width == 0 || s.height == 0) return 1f
        return max(1f, min(s.width * 0.92f / csPx, s.height * 0.97f / csPx))
    }

    fun pick(target: Int) {
        val nn = nNow
        if (nn == 0) return
        val t = target.coerceIn(0, nn - 1)
        if (t != idxNow) {
            controller.playQueueIndex(t)
            scope.launch { cfGo(pos, t.toFloat()) }
        } else {
            scope.launch { cfGo(pos, idxNow.toFloat()) }
        }
    }

    fun hit(pt: Offset): Int? {
        val nn = nNow
        val p = pos.value
        val k = bk.value
        val big = bigScale()
        var best: Int? = null
        var bestAb = Float.MAX_VALUE
        val lo = max(0, floor(p).toInt() - 4)
        val hi = min(nn - 1, ceil(p).toInt() + 4)
        for (i in lo..hi) {
            val g = cfGeo(i, p, k, csPx, big)
            if (g.ab >= 4f) continue
            val f = 1100f * dens / (1100f * dens - g.z)
            val cx = szNow.width / 2f + g.x * f
            val cy = szNow.height / 2f
            val half = csPx * g.sc * f / 2f
            if (abs(pt.x - cx) <= half && abs(pt.y - cy) <= half && g.ab < bestAb) {
                best = i
                bestAb = g.ab
            }
        }
        return best
    }

    Box(
        modifier
            .onSizeChanged { sz = it }
            .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
            .drawWithContent {
                drawContent()
                drawRect(
                    brush = Brush.horizontalGradient(
                        0f to Color.Transparent,
                        0.03f to Color.Black,
                        0.97f to Color.Black,
                        1f to Color.Transparent,
                    ),
                    blendMode = BlendMode.DstIn,
                )
            }
            .clipToBounds()
            .pointerInput(Unit) {
                awaitEachGesture {
                    val down = awaitFirstDown()
                    if (nNow == 0) return@awaitEachGesture
                    scope.launch { pos.stop() }
                    dragging = true
                    val startX = down.position.x
                    val startP = pos.value
                    var moved = false
                    var lx = startX
                    var lt = down.uptimeMillis
                    var v = 0f
                    while (true) {
                        val ev = awaitPointerEvent()
                        val ch = ev.changes.firstOrNull { it.id == down.id } ?: break
                        if (!ch.pressed) break
                        val dx = ch.position.x - startX
                        if (!moved && abs(dx) > 6f * dens) moved = true
                        if (moved) {
                            ch.consume()
                            val now = ch.uptimeMillis
                            val dt = max(1L, now - lt).toFloat()
                            v = (ch.position.x - lx) / dt
                            lx = ch.position.x
                            lt = now
                            val np = (startP - dx / (csPx * 0.55f)).coerceIn(-0.4f, nNow - 0.6f)
                            scope.launch(start = CoroutineStart.UNDISPATCHED) { pos.snapTo(np) }
                        }
                    }
                    dragging = false
                    if (!moved) {
                        val i = hit(down.position) ?: idxNow
                        if (i == idxNow) {
                            val goBig = bk.targetValue < 0.5f
                            scope.launch { bk.animateTo(if (goBig) 1f else 0f, tween(420, easing = EaseInOut3)) }
                        } else {
                            pick(i)
                        }
                    } else {
                        val extra = (-v * 120f / (csPx * 0.55f)).coerceIn(-0.5f, 0.5f)
                        val b = startP.roundToInt()
                        pick((pos.value + extra).roundToInt().coerceIn(b - 2, b + 2))
                    }
                }
            }
    ) {
        if (n > 0 && sz.width > 0 && sz.height > 0) {
            val big = max(1f, min(sz.width * 0.92f / csPx, sz.height * 0.97f / csPx))
            val artPx = (csPx * big).roundToInt().coerceIn(512, 2048)
            val lo by remember(n) { derivedStateOf { max(0, floor(pos.value).toInt() - 4) } }
            val hi by remember(n) { derivedStateOf { min(n - 1, ceil(pos.value).toInt() + 4) } }
            val center by remember { derivedStateOf { pos.value.roundToInt() } }
            val order = (lo..hi).sortedByDescending { abs(it - center) }
            for (i in order) {
                if (i < 0 || i >= queue.size) continue
                key(i) {
                    CfItem(
                        i = i,
                        track = queue[i],
                        pos = { pos.value },
                        bk = { bk.value },
                        cs = csPx,
                        csDp = csDp,
                        big = big,
                        artPx = artPx,
                        pal = pal,
                        store = store,
                    )
                }
            }
        }
    }
}

@Composable
private fun BoxScope.CfItem(
    i: Int,
    track: Track,
    pos: () -> Float,
    bk: () -> Float,
    cs: Float,
    csDp: Dp,
    big: Float,
    artPx: Int,
    pal: Pal,
    store: ArtworkStore,
) {
    val shape = RoundedCornerShape(26.dp)
    Box(
        Modifier
            .align(Alignment.Center)
            .size(csDp)
            .graphicsLayer {
                val g = cfGeo(i, pos(), bk(), cs, big)
                val persp = 1100f * density
                val f = persp / (persp - g.z)
                translationX = g.x * f
                scaleX = g.sc * f
                scaleY = g.sc * f
                rotationY = g.a * 58f
                cameraDistance = persp
                alpha = (4f - g.ab).coerceIn(0f, 1f) * (if (g.ab < 0.5f) 1f else 1f - 0.85f * bk())
            }
            .drawBehind {
                outerShadow(
                    Color(0xBF000000), 0f, 34.dp.toPx(), 52.dp.toPx(), -22.dp.toPx(), 26.dp.toPx(),
                )
            }
    ) {
        Box(
            Modifier
                .fillMaxSize()
                .clip(shape)
                .drawWithContent {
                    drawContent()
                    val w = 1.5.dp.toPx()
                    drawRoundRect(
                        Color(0x1FFFFFFF),
                        topLeft = Offset(w / 2f, w / 2f),
                        size = Size(size.width - w, size.height - w),
                        cornerRadius = CornerRadius(26.dp.toPx() - w / 2f),
                        style = Stroke(w),
                    )
                    val g = cfGeo(i, pos(), bk(), cs, big)
                    val dim = min(0.72f, abs(g.a) * 0.42f + (g.ab - abs(g.a)) * 0.12f)
                    drawRect(pal.bg0.copy(alpha = dim.coerceIn(0f, 1f)))
                }
        ) {
            TrackArt(track, store, artPx)
        }
    }
}

/* ───────────────────────── seek (input[type=range]) ───────────────────────── */

@Composable
private fun SeekBar(
    pal: Pal,
    positionMs: Long,
    durationMs: Long,
    dragP: Float?,
    onDragP: (Float?) -> Unit,
    onSeek: (Long) -> Unit,
) {
    val real = if (durationMs > 0) (positionMs.toFloat() / durationMs).coerceIn(0f, 1f) else 0f
    val p = dragP ?: real
    val durNow by rememberUpdatedState(durationMs)
    val seekNow by rememberUpdatedState(onSeek)
    val dragNow by rememberUpdatedState(onDragP)

    Canvas(
        Modifier
            .fillMaxWidth()
            .height(24.dp)
            .pointerInput(Unit) {
                awaitEachGesture {
                    val down = awaitFirstDown()
                    val th = 12.dp.toPx()
                    fun toP(x: Float): Float = ((x - th) / (size.width - 2f * th)).coerceIn(0f, 1f)
                    var cur = toP(down.position.x)
                    dragNow(cur)
                    down.consume()
                    while (true) {
                        val ev = awaitPointerEvent()
                        val ch = ev.changes.firstOrNull { it.id == down.id } ?: break
                        if (!ch.pressed) break
                        cur = toP(ch.position.x)
                        dragNow(cur)
                        ch.consume()
                    }
                    if (durNow > 0) seekNow((cur * durNow).toLong())
                    dragNow(null)
                }
            }
    ) {
        val w = size.width
        val d = density

        // .pseek::before — риски
        var tx = 8f * d
        while (tx < w - 8f * d) {
            drawRect(pal.wc.copy(alpha = 0.24f), Offset(tx, -5f * d), Size(1f * d, 5f * d))
            tx += 9f * d
        }

        // трек
        val th8 = 8f * d
        val rr = 4f * d
        val top = size.height / 2f - th8 / 2f
        withTransform({ translate(0f, top) }) {
            drawRoundRect(pal.wc.copy(alpha = 0.08f), size = Size(w, th8), cornerRadius = CornerRadius(rr))
            val fw = w * p
            if (fw > 0f) {
                clipPath(Path().apply { addRoundRect(RoundRect(0f, 0f, w, th8, rr, rr)) }) {
                    drawRect(
                        Brush.horizontalGradient(listOf(pal.ac, pal.ac2), startX = 0f, endX = max(1f, fw)),
                        size = Size(fw, th8),
                    )
                }
            }
            insetShadow(Color(0x73000000), 0f, 2f * d, 4f * d, rr, w, th8)
        }

        // бегунок 24px
        val td = 24f * d
        val cx = 12f * d + p * (w - 24f * d)
        val left = cx - td / 2f
        val tp = size.height / 2f - td / 2f
        withTransform({ translate(left, tp) }) {
            outerShadow(Color(0x80000000), 0f, 3f * d, 8f * d, 0f, td / 2f, td, td)
            val c = Offset(0.35f * td, 0.30f * td)
            val rad = sqrt((td - c.x) * (td - c.x) + (td - c.y) * (td - c.y))
            drawCircle(
                Brush.radialGradient(
                    0f to Color(0xFFFAFBFF),
                    0.7f to Color(0xFFC5C8D8),
                    1f to Color(0xFFC5C8D8),
                    center = c,
                    radius = rad,
                ),
                radius = td / 2f,
                center = Offset(td / 2f, td / 2f),
            )
            insetShadow(Color(0x24000000), 0f, -2f * d, 3f * d, td / 2f, td, td)
        }
    }
}

/* ───────────────────────── кнопки ───────────────────────── */

@Composable
private fun HbButton(pal: Pal, onClick: () -> Unit, content: @Composable () -> Unit) {
    Box(
        Modifier
            .size(44.dp)
            .drawBehind { drawRaisedS(pal, 16.dp.toPx()) }
            .clip(RoundedCornerShape(16.dp))
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) { content() }
}

/** style: 0 = .c, 1 = .c.w, 2 = .c.big */
@Composable
private fun CtlButton(
    pal: Pal,
    dim: Dp,
    style: Int,
    on: Boolean,
    onClick: () -> Unit,
    content: @Composable () -> Unit,
) {
    val src = remember { MutableInteractionSource() }
    val pressed by src.collectIsPressedAsState()
    Box(
        Modifier
            .size(dim)
            .drawBehind {
                val r = size.minDimension / 2f
                val d = density
                when (style) {
                    1 -> if (pressed) {
                        drawRoundRect(pal.slab(size), cornerRadius = CornerRadius(r))
                        drawWellInsets(pal, r)
                    } else drawRaisedS(pal, r)
                    2 -> {
                        outerShadow(Color(0xB36A4DF0), 0f, 14f * d, 24f * d, -8f * d, r)
                        drawRoundRect(
                            diag(size, pal.ac, Color(0xFF5A45E0)),
                            cornerRadius = CornerRadius(r),
                        )
                        insetShadow(Color(0x33000000), 0f, -5f * d, 9f * d, r)
                        insetShadow(Color(0x59FFFFFF), 0f, 1.5f * d, 0f, r)
                    }
                    else -> if (pressed) drawWellInsets(pal, r)
                }
                if (on && style == 0) {
                    drawCircle(
                        pal.ac,
                        radius = 2f * d,
                        center = Offset(size.width / 2f, size.height - 4f * d),
                    )
                }
            }
            .clip(CircleShape)
            .clickable(interactionSource = src, indication = null, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) { content() }
}

@Composable
private fun FormatBadge(pal: Pal, b: FpBadge) {
    val shape = RoundedCornerShape(5.dp)
    val base = Modifier
        .padding(end = 6.dp)
        .height(17.dp)
        .then(
            if (b.hiRes) Modifier.background(pal.tx, shape)
            else Modifier.border(1.dp, pal.wc.copy(alpha = 0.5f), shape)
        )
        .padding(horizontal = 6.dp)
    val c = if (b.hiRes) pal.bg0 else pal.tx
    Row(base, verticalAlignment = Alignment.CenterVertically) {
        BasicText(
            b.label,
            style = TextStyle(color = c, fontSize = 10.5.sp, fontWeight = FontWeight.ExtraBold, letterSpacing = 0.05.em),
        )
        if (b.hiRes && b.bits != null) {
            Spacer(Modifier.width(4.dp))
            Box(Modifier.width(1.dp).height(11.dp).background(Color(0x59000000)))
            Spacer(Modifier.width(4.dp))
            BasicText(
                b.bits.toString(),
                style = TextStyle(color = c, fontSize = 10.5.sp, fontWeight = FontWeight.ExtraBold, letterSpacing = 0.05.em),
            )
        }
    }
}

/* ───────────────────────── FullPlayer ───────────────────────── */

@Composable
fun FullPlayer(
    visible: Boolean,
    state: PlayerUiState,
    queue: List<Track>,
    controller: PlaybackController,
    artworkStore: ArtworkStore,
    isLiked: (Track) -> Boolean,
    onClose: () -> Unit,
    onToggleLike: (Track) -> Unit,
    onMore: (Track) -> Unit,
    modifier: Modifier = Modifier,
    darkTheme: Boolean = isSystemInDarkTheme(),
    badgeFor: (Track) -> FpBadge? = { defaultBadge(it) },
) {
    val progress by animateFloatAsState(
        targetValue = if (visible) 1f else 0f,
        animationSpec = tween(450, easing = CubicBezierEasing(0.2f, 0.85f, 0.2f, 1f)),
        label = "fullPlayerSlide",
    )
    if (!visible && progress <= 0.001f) return

    BackHandler(enabled = visible, onBack = onClose)

    val pal = remember(darkTheme) { Pal(darkTheme) }
    val snap = state.snap()
    val mediaId = state.mediaId
    val index = when {
        queue.isEmpty() -> -1
        mediaId == null -> snap.index.coerceIn(0, queue.size - 1)
        queue.getOrNull(snap.index)?.id == mediaId -> snap.index
        else -> queue.indexOfFirst { it.id == mediaId }.let { if (it >= 0) it else snap.index.coerceIn(0, queue.size - 1) }
    }
    val track = queue.getOrNull(index)
    val liked = track?.let(isLiked) ?: false
    var dragP by remember { mutableStateOf<Float?>(null) }

    val titleText = track?.title ?: state.title ?: ""
    val artistText = track?.artistText() ?: state.artist?.takeIf { it.isNotBlank() } ?: UNKNOWN_ARTIST
    val seed = if (track != null) tseed(track) else "$artistText|$titleText"
    val hue = hueOf(seed)
    val glow by animateColorAsState(
        Color.hsl(hue.toFloat(), 0.62f, pal.pl),
        tween(600),
        label = "fullPlayerGlow",
    )

    val topInset = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
    val botInset = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()

    Box(
        modifier
            .fillMaxSize()
            .graphicsLayer { translationY = size.height * 1.02f * (1f - progress) }
            .background(pal.bg0)
            .pointerInput(Unit) {
                awaitPointerEventScope {
                    while (true) awaitPointerEvent().changes.forEach { it.consume() }
                }
            }
    ) {
        // .pbg
        Box(
            Modifier
                .fillMaxSize()
                .drawBehind {
                    drawRect(pal.bg0)
                    withTransform({
                        translate(size.width / 2f, 0f)
                        scale(size.width * 1.2f, size.height * 0.58f, Offset.Zero)
                    }) {
                        drawRect(
                            Brush.radialGradient(
                                0f to glow,
                                0.74f to glow.copy(alpha = 0f),
                                center = Offset.Zero,
                                radius = 1f,
                            ),
                            topLeft = Offset(-1f, -1f),
                            size = Size(2f, 2f),
                        )
                    }
                }
        )

        Column(
            Modifier
                .fillMaxSize()
                .padding(
                    start = 26.dp,
                    end = 26.dp,
                    top = topInset + 10.dp,
                    bottom = maxOf(botInset, 24.dp),
                )
        ) {
            // .ptop
            Row(
                Modifier.fillMaxWidth().expandH(10.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                HbButton(pal, onClick = onClose) { Icon24(Ic.Down, pal.tx, 22.dp) }
                Box(
                    Modifier
                        .drawBehind { drawWell(pal, 14.dp.toPx()) }
                        .padding(horizontal = 15.dp, vertical = 8.dp)
                ) {
                    BasicText(
                        "СЕЙЧАС ИГРАЕТ",
                        style = TextStyle(
                            color = pal.t2,
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Bold,
                            letterSpacing = 0.26.em,
                            fontFamily = FontFamily.Monospace,
                        ),
                    )
                }
                HbButton(pal, onClick = { track?.let(onMore) }) { Icon24(Ic.More, pal.tx, 22.dp) }
            }

            // .pcf
            CoverFlow(
                pal = pal,
                queue = queue,
                index = index,
                store = artworkStore,
                controller = controller,
                modifier = Modifier.weight(1f).fillMaxWidth().expandH(26.dp),
            )

            // .pmeta
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(14.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    BasicText(
                        titleText,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        style = TextStyle(
                            color = pal.tx,
                            fontSize = 25.sp,
                            fontWeight = FontWeight.ExtraBold,
                            letterSpacing = (-0.04).em,
                        ),
                    )
                    Row(Modifier.padding(top = 5.dp), verticalAlignment = Alignment.CenterVertically) {
                        val badge = track?.let(badgeFor)
                        if (badge != null) FormatBadge(pal, badge)
                        BasicText(
                            artistText,
                            modifier = Modifier.weight(1f, fill = false),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            style = TextStyle(color = pal.t2, fontSize = 16.5.sp, fontWeight = FontWeight.Medium),
                        )
                    }
                }
                Box(
                    Modifier
                        .size(44.dp)
                        .clip(CircleShape)
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null,
                        ) { track?.let(onToggleLike) },
                    contentAlignment = Alignment.Center,
                ) {
                    Icon24(Ic.Heart, if (liked) pal.ac else pal.t3, 26.dp, filled = liked)
                }
            }

            // .pseek
            Column(Modifier.padding(top = 26.dp)) {
                SeekBar(
                    pal = pal,
                    positionMs = snap.positionMs,
                    durationMs = snap.durationMs,
                    dragP = dragP,
                    onDragP = { dragP = it },
                    onSeek = { controller.seekTo(it) },
                )
                val tcSec = ((dragP?.times(snap.durationMs) ?: snap.positionMs.toFloat()) / 1000f).toLong()
                val timeStyle = TextStyle(
                    color = pal.t2,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.SemiBold,
                    fontFeatureSettings = "tnum",
                )
                Row(Modifier.fillMaxWidth().padding(top = 2.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                    BasicText(fmt(tcSec), style = timeStyle)
                    BasicText(fmt(snap.durationMs / 1000L), style = timeStyle)
                }
            }

            // .pctl
            Row(
                Modifier
                    .padding(top = 20.dp)
                    .fillMaxWidth()
                    .drawBehind { drawWell(pal, 38.dp.toPx()) }
                    .padding(horizontal = 10.dp, vertical = 9.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                CtlButton(pal, 46.dp, 0, snap.shuffle, onClick = { controller.toggleShuffle() }) {
                    Icon24(Ic.Shuffle, if (snap.shuffle) pal.ac else pal.t3, 26.dp)
                }
                CtlButton(pal, 54.dp, 1, false, onClick = { controller.previous() }) {
                    Icon24(Ic.Prev, pal.tx, 30.dp)
                }
                CtlButton(pal, 72.dp, 2, false, onClick = { controller.togglePlayPause() }) {
                    Icon24(if (snap.isPlaying) Ic.Pause else Ic.Play, Color.White, 34.dp)
                }
                CtlButton(pal, 54.dp, 1, false, onClick = { controller.next() }) {
                    Icon24(Ic.Next, pal.tx, 30.dp)
                }
                val repOn = snap.repeat > 0
                CtlButton(pal, 46.dp, 0, repOn, onClick = { controller.cycleRepeatMode() }) {
                    Icon24(if (snap.repeat == 2) Ic.Repeat1 else Ic.Repeat, if (repOn) pal.ac else pal.t3, 26.dp)
                }
            }
        }
    }
}
