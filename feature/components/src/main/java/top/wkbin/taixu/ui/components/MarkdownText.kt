package top.wkbin.taixu.ui.components

import android.content.ClipData
import android.content.ClipboardManager
import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import android.util.Base64
import android.util.LruCache
import android.widget.Toast
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.HorizontalDivider
import top.wkbin.taixu.ui.components.RuntimeIconButton as IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.geometry.isUnspecified
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.res.stringResource
import top.wkbin.taixu.feature.components.R
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withLink
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.SubcomposeAsyncImage
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import coil3.request.crossfade
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayInputStream
import java.net.HttpURLConnection
import java.net.URL
import kotlin.math.abs
import top.wkbin.taixu.ui.components.RuntimeCircularProgressIndicator as CircularProgressIndicator
import top.wkbin.taixu.ui.components.RuntimeIcon
import top.wkbin.taixu.ui.components.RuntimeIconName
import top.wkbin.taixu.ui.components.SyntaxHighlighter
import android.util.Log
import java.io.File

/**
 * 沙箱绝对路径前缀（如 "workspace" 对应 /workspace）到宿主真实目录的映射。
 * 模型回复里的本地媒体路径（/workspace/xxx.jpg）是 PRoot 沙箱内语义，
 * Android 侧必须翻译成应用私有目录下的真实文件才能被 Coil 加载。
 */
val LocalSandboxHostRoots = staticCompositionLocalOf<Map<String, File>> { emptyMap() }

/** 把模型引用的本地路径解析为 Coil 可加载的 data model；无法映射时原样返回。 */
private fun resolveMediaSource(source: String, hostRoots: Map<String, File>): Any {
    // 模型可能输出 file:///workspace/...，统一剥掉 file:// 前缀再按沙箱绝对路径解析
    val path = if (source.startsWith("file://", ignoreCase = true)) source.substring("file://".length) else source
    if (path.startsWith("//")) return source
    if (!path.startsWith("/")) {
        // ./xxx.jpg 相对路径：尽力映射到工作区根（下载工具的 /workspace/ 前缀路径落盘于此）
        if (!path.startsWith("./")) return source
        val workspaceRoot = hostRoots["workspace"] ?: return source
        val relative = path.removePrefix("./").substringBefore('?').substringBefore('#')
        if (relative.isBlank() || relative.split('/').any { it.isEmpty() || it == "." || it == ".." }) return source
        return File(workspaceRoot, relative)
    }
    val clean = path.replace('\\', '/').substringBefore('?').substringBefore('#').trimEnd('/')
    val segments = clean.trimStart('/').split('/').filter { it.isNotEmpty() && it != "." }
    if (segments.isEmpty() || segments.any { it == ".." }) return source
    val root = hostRoots[segments.first()] ?: return source
    return if (segments.size == 1) root else File(root, segments.drop(1).joinToString("/"))
}

/**
 * 轻量 markdown 渲染组件：支持标题、段落、粗体/斜体、行内代码、
 * 代码块（带语言徽章、一键复制与语法高亮）、无序/有序列表、引用、链接、
 * 远程图片与表格。远程图片由 Coil 加载，网页链接由 Compose LinkAnnotation 打开。
 */
@Composable
fun MarkdownText(
    markdown: String,
    modifier: Modifier = Modifier,
    contentCacheKey: String? = null,
) {
    val blocks = remember(contentCacheKey, markdown) {
        resolveMarkdownBlocks(markdown, contentCacheKey)
    }
    SelectionContainer {
        Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
            blocks.forEachIndexed { index, block ->
                when (block) {
                    is MdParagraph -> InlineText(block.text, MaterialTheme.typography.bodyMedium)
                    is MdHeading -> InlineText(
                        block.text,
                        when (block.level) {
                            1 -> MaterialTheme.typography.headlineSmall
                            2 -> MaterialTheme.typography.titleLarge
                            3 -> MaterialTheme.typography.titleMedium
                            else -> MaterialTheme.typography.titleSmall
                        },
                        bold = true,
                    )
                    is MdCodeBlock -> CodeBlock(block)
                    is MdList -> ListBlock(block)
                    is MdQuote -> QuoteBlock(block)
                    is MdTable -> TableBlock(block)
                    is MdRemoteMedia -> RemoteMediaBlock(
                        block = block,
                        cacheKey = contentCacheKey?.let { "$it:media:$index" },
                    )
                    is MdHr -> HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                }
            }
        }
    }
}

// ---------- 解析 ----------

private sealed interface MdBlock

private data class MdParagraph(val text: String) : MdBlock
private data class MdHeading(val level: Int, val text: String) : MdBlock
private data class MdCodeBlock(val language: String, val code: String) : MdBlock
private data class MdList(val ordered: Boolean, val items: List<String>) : MdBlock
private data class MdQuote(val lines: List<String>) : MdBlock
private data class MdTable(val headers: List<String>, val rows: List<List<String>>) : MdBlock
private data class MdRemoteMedia(val url: String, val description: String) : MdBlock
private object MdHr : MdBlock

private val headingRegex = Regex("^\\s*(#{1,6})\\s+(.+?)\\s*#*\\s*$")
private val orderedListRegex = Regex("^\\s*\\d+\\.\\s+(.*)$")
private val unorderedListRegex = Regex("^\\s*[-*+]\\s+(.*)$")
private val hrRegex = Regex("^\\s*(?:-{3,}|\\*{3,}|_{3,})\\s*$")
private val markdownImageRegex = Regex(
    pattern = """^\s*!\[([^]\n]*)]\(((?:https?://|file://|data:image/|/|\./|\\|\.\\)[^)\s]+)\)\s*$""",
    option = RegexOption.IGNORE_CASE,
)
/** 行内图片（不要求独占一行），用于从段落文本中拆出图片块。 */
private val inlineImageRegex = Regex("""!\[([^]\n]*)]\(((?:https?://|file://|data:image/|/|\./|\\|\.\\)[^)\s]+)\)""", RegexOption.IGNORE_CASE)
/** HTML <img> 标签，兼容模型直接输出 img 标签的情况。 */
private val htmlImgRegex = Regex("""<img[^>]+src\s*=\s*["']((?:https?://|file://|data:image/|/|\./|\\|\.\\)[^"'\s]+)["'][^>]*>""", RegexOption.IGNORE_CASE)
private val standaloneWebUrlRegex = Regex("^https?://\\S+$", RegexOption.IGNORE_CASE)
private val inlineMarkdownLinkRegex = Regex("\\[([^]\\n]+)]\\((https?://[^)\\s]+)\\)", RegexOption.IGNORE_CASE)
private val inlineWebUrlRegex = Regex("https?://[^\\s<>\\[\\]{}\"']+", RegexOption.IGNORE_CASE)
/** 视为图片的 URL 后缀（原始 URL 嵌在段落中时，匹配这些后缀则渲染为图片）。 */
private val imageFileExtensions = setOf("jpg", "jpeg", "png", "gif", "webp", "svg", "bmp", "avif", "heic", "heif")

private val markdownBlockCache = LruCache<String, List<MdBlock>>(300)
/** Generated image / oversized markdown is expensive to parse; retain a few by message id + content fingerprint. */
private val largeMarkdownBlockCache = LruCache<String, CachedMarkdownBlocks>(4)
private val inlineSpanCache = LruCache<String, AnnotatedString>(500)

private data class CachedMarkdownBlocks(
    val fingerprint: Int,
    val blocks: List<MdBlock>,
)

private fun markdownContentFingerprint(markdown: String): Int = 31 * markdown.length + markdown.hashCode()

/**
 * 解析 markdown。大文本 LRU 以 [contentCacheKey]（通常是 message.id）为槽位，
 * 命中时必须核对内容指纹，避免同一 id 换了正文仍复用旧块。
 *
 * [largeCacheMinChars] 仅测试可覆盖；生产走 [MARKDOWN_CACHE_MAX_CHARS]。
 * 流式短消息不会写入大文本缓存，remember(key, markdown) 仍按正文更新。
 */
private fun resolveMarkdownBlocks(
    markdown: String,
    contentCacheKey: String?,
    largeCacheMinChars: Int = MARKDOWN_CACHE_MAX_CHARS,
): List<MdBlock> {
    if (contentCacheKey != null && markdown.length > largeCacheMinChars) {
        val cached = largeMarkdownBlockCache.get(contentCacheKey)
        if (cached != null && cached.fingerprint == markdownContentFingerprint(markdown)) {
            return cached.blocks
        }
    }
    val parsed = parseMarkdownBlocks(markdown)
    if (contentCacheKey != null && markdown.length > largeCacheMinChars) {
        largeMarkdownBlockCache.put(
            contentCacheKey,
            CachedMarkdownBlocks(markdownContentFingerprint(markdown), parsed),
        )
    }
    return parsed
}

private fun parseMarkdownBlocks(md: String): List<MdBlock> {
    val cacheable = md.length <= MARKDOWN_CACHE_MAX_CHARS
    if (cacheable) markdownBlockCache.get(md)?.let { return it }
    val raw = parseRawBlocks(md)
    // 段落后处理：把行内的 ![alt](url)、<img src="url">、原始图片 URL 拆成独立图片块，
    // 否则模型带前后文字输出图片时，解析器只当纯文本渲染，Coil 永远不会被调用。
    val parsed = raw.flatMap { block ->
        if (block is MdParagraph) extractInlineMedia(block.text)
        else listOf(block)
    }
    if (cacheable) markdownBlockCache.put(md, parsed)
    return parsed
}

private fun parseRawBlocks(md: String): List<MdBlock> {
    val lines = md.replace("\r\n", "\n").replace("\r", "\n").split("\n")
    val blocks = mutableListOf<MdBlock>()
    var i = 0
    while (i < lines.size) {
        val line = lines[i]
        val trimmed = line.trimStart()
        when {
            trimmed.startsWith("```") -> {
                val lang = trimmed.removePrefix("```").trim()
                val code = mutableListOf<String>()
                i++
                while (i < lines.size && !lines[i].trimStart().startsWith("```")) {
                    code.add(lines[i]); i++
                }
                i++ // skip closing fence
                blocks.add(MdCodeBlock(lang, code.joinToString("\n").trim('\n')))
            }
            markdownImageRegex.matches(line) -> {
                val match = markdownImageRegex.matchEntire(line)!!
                blocks.add(
                    MdRemoteMedia(
                        url = match.groupValues[2],
                        description = match.groupValues[1],
                    ),
                )
                i++
            }
            standaloneWebUrlRegex.matches(line.trim()) -> {
                val url = trimUrlPunctuation(line.trim())
                blocks.add(MdRemoteMedia(url = url, description = ""))
                i++
            }
            headingRegex.matches(line.trim()) -> {
                val m = headingRegex.find(line.trim())!!
                blocks.add(MdHeading(m.groupValues[1].length, m.groupValues[2]))
                i++
            }
            isTableStart(lines, i) -> {
                val headers = splitRow(lines[i])
                i += 2
                val rows = mutableListOf<List<String>>()
                while (i < lines.size) {
                    val t = lines[i].trimStart()
                    if (lines[i].isBlank() || !t.startsWith("|")) break
                    rows.add(splitRow(lines[i]))
                    i++
                }
                blocks.add(MdTable(headers, rows))
            }
            hrRegex.matches(line) -> { blocks.add(MdHr); i++ }
            trimmed.startsWith(">") -> {
                val quoted = mutableListOf<String>()
                while (i < lines.size && lines[i].trimStart().startsWith(">")) {
                    quoted.add(lines[i].trimStart().removePrefix(">").removePrefix(" "))
                    i++
                }
                blocks.add(MdQuote(quoted))
            }
            orderedListRegex.matches(line) || unorderedListRegex.matches(line) -> {
                val ordered = orderedListRegex.matches(lines[i])
                val items = mutableListOf<String>()
                while (i < lines.size && (orderedListRegex.matches(lines[i]) || unorderedListRegex.matches(lines[i]))) {
                    val l = lines[i]
                    val m = orderedListRegex.find(l) ?: unorderedListRegex.find(l)
                    items.add(m!!.groupValues[1])
                    i++
                }
                blocks.add(MdList(ordered, items))
            }
            line.isBlank() -> i++
            else -> {
                val para = mutableListOf<String>()
                while (i < lines.size) {
                    val l = lines[i]
                    val t = l.trimStart()
                    if (l.isBlank() ||
                        headingRegex.matches(l.trim()) ||
                        hrRegex.matches(l) ||
                        isTableStart(lines, i) ||
                        t.startsWith("```") ||
                        t.startsWith(">") ||
                        markdownImageRegex.matches(l) ||
                        standaloneWebUrlRegex.matches(l.trim()) ||
                        orderedListRegex.matches(l) ||
                        unorderedListRegex.matches(l)
                    ) break
                    para.add(l); i++
                }
                blocks.add(MdParagraph(para.joinToString(" ").trim()))
            }
        }
    }
    return blocks
}

/**
 * 从段落文本中拆出行内媒体（Markdown 图片、HTML img、原始图片 URL）。
 * 返回文本段落与图片块的有序列表；无媒体时返回单段落。
 */
private fun extractInlineMedia(text: String): List<MdBlock> {
    if (text.isBlank()) return listOf(MdParagraph(text))
    val result = mutableListOf<MdBlock>()
    val textBuf = StringBuilder()
    var remaining = text

    while (remaining.isNotEmpty()) {
        // 优先级：Markdown 图片 > HTML img > 原始图片 URL
        val mdImg = inlineImageRegex.find(remaining)
        val htmlImg = htmlImgRegex.find(remaining)
        val rawUrl = inlineWebUrlRegex.find(remaining)

        val earliest = listOfNotNull(mdImg, htmlImg, rawUrl)
            .minByOrNull { it.range.first }

        if (earliest == null) {
            textBuf.append(remaining)
            break
        }

        // 匹配到的媒体之前的文本
        if (earliest.range.first > 0) {
            textBuf.append(remaining, 0, earliest.range.first)
        }

        val isActualImage = when (earliest) {
            mdImg -> true
            htmlImg -> true
            rawUrl -> {
                val url = trimUrlPunctuation(earliest.value)
                imageFileExtensions.any { ext -> url.lowercase().endsWith(".$ext") }
            }
            else -> false
        }

        if (isActualImage) {
            // 先 flush 累积的文本
            if (textBuf.isNotBlank()) {
                result.add(MdParagraph(textBuf.toString().trim()))
                textBuf.clear()
            }
            val url = when (earliest) {
                mdImg -> earliest.groupValues[2]
                htmlImg -> earliest.groupValues[1]
                else -> trimUrlPunctuation(earliest.value)
            }
            val desc = if (earliest === mdImg) earliest.groupValues[1] else ""
            result.add(MdRemoteMedia(url = url, description = desc))
        } else {
            // 普通 URL，保留为文本（行内渲染会把它变成可点击链接）
            textBuf.append(remaining, earliest.range.first, earliest.range.last + 1)
        }

        remaining = remaining.substring(earliest.range.last + 1)
    }

    if (textBuf.isNotBlank()) {
        result.add(MdParagraph(textBuf.toString().trim()))
    }

    return if (result.isEmpty()) listOf(MdParagraph(text)) else result
}

// ---------- 行内渲染 ----------

@Composable
private fun InlineText(
    text: String,
    style: TextStyle,
    bold: Boolean = false,
    color: Color = Color.Unspecified,
    modifier: Modifier = Modifier,
) {
    val linkColor = MaterialTheme.colorScheme.primary
    val isDark = MaterialTheme.colorScheme.surface.luminance() < 0.5f
    val inlineCodeBg = if (isDark) Color(0xFF21262D) else Color(0xFFEFF1F4)
    val inlineCodeColor = if (isDark) Color(0xFF79C0FF) else Color(0xFF0969DA)
    val annotated = remember(text, bold, linkColor, isDark) {
        buildInline(text, bold, linkColor, inlineCodeBg, inlineCodeColor)
    }
    Text(annotated, style = style, color = color, modifier = modifier)
}

private fun buildInline(
    text: String,
    forceBold: Boolean,
    linkColor: Color,
    inlineCodeBg: Color,
    inlineCodeColor: Color,
): AnnotatedString {
    val cacheKey = "$forceBold|${linkColor.value}|${inlineCodeBg.value}|${inlineCodeColor.value}|$text"
    inlineSpanCache.get(cacheKey)?.let { return it }

    val built = buildAnnotatedString {
        if (forceBold) pushStyle(SpanStyle(fontWeight = FontWeight.Bold))
        val linkStyles = TextLinkStyles(
            style = SpanStyle(color = linkColor, textDecoration = TextDecoration.Underline),
            hoveredStyle = SpanStyle(color = linkColor.copy(alpha = 0.8f)),
            pressedStyle = SpanStyle(color = linkColor.copy(alpha = 0.65f)),
        )
        var i = 0
        while (i < text.length) {
            when {
                text.startsWith("~~", i) -> {
                    val end = text.indexOf("~~", i + 2)
                    if (end != -1) {
                        pushStyle(SpanStyle(textDecoration = TextDecoration.LineThrough))
                        append(text, i + 2, end)
                        pop()
                        i = end + 2
                    } else { append(text[i]); i++ }
                }
                text.startsWith("**", i) -> {
                    val end = text.indexOf("**", i + 2)
                    if (end != -1) {
                        pushStyle(SpanStyle(fontWeight = FontWeight.Bold))
                        append(text, i + 2, end)
                        pop()
                        i = end + 2
                    } else { append(text[i]); i++ }
                }
                text[i] == '`' -> {
                    val end = text.indexOf('`', i + 1)
                    if (end != -1) {
                        pushStyle(
                            SpanStyle(
                                fontFamily = FontFamily.Monospace,
                                fontWeight = FontWeight.SemiBold,
                                fontSize = 12.sp,
                                background = inlineCodeBg,
                                color = inlineCodeColor,
                            ),
                        )
                        append(text, i + 1, end)
                        pop()
                        i = end + 1
                    } else { append(text[i]); i++ }
                }
                text.startsWith("[", i) -> {
                    val link = inlineMarkdownLinkRegex.find(text, i)
                    if (link != null && link.range.first == i) {
                        withLink(LinkAnnotation.Url(link.groupValues[2], linkStyles)) {
                            append(link.groupValues[1])
                        }
                        i = link.range.last + 1
                    } else { append(text[i]); i++ }
                }
                text.regionMatches(i, "https://", 0, 8, ignoreCase = true) ||
                    text.regionMatches(i, "http://", 0, 7, ignoreCase = true) -> {
                    val match = inlineWebUrlRegex.find(text, i)
                    if (match != null && match.range.first == i) {
                        val url = trimUrlPunctuation(match.value)
                        withLink(LinkAnnotation.Url(url, linkStyles)) { append(url) }
                        i += url.length
                    } else { append(text[i]); i++ }
                }
                text[i] == '*' -> {
                    val end = text.indexOf('*', i + 1)
                    if (end != -1 && text.getOrNull(end + 1) != '*') {
                        pushStyle(SpanStyle(fontStyle = FontStyle.Italic))
                        append(text, i + 1, end)
                        pop()
                        i = end + 1
                    } else { append(text[i]); i++ }
                }
                else -> { append(text[i]); i++ }
            }
        }
        if (forceBold) pop()
    }
    inlineSpanCache.put(cacheKey, built)
    return built
}

private fun trimUrlPunctuation(value: String): String {
    var result = value.trimEnd('.', ',', ';', ':', '!', '?')
    while (result.endsWith(')') && result.count { it == ')' } > result.count { it == '(' }) {
        result = result.dropLast(1)
    }
    return result
}

@Composable
private fun RemoteMediaBlock(block: MdRemoteMedia, cacheKey: String? = null) {
    val context = LocalContext.current
    var previewing by remember(block.url) { mutableStateOf(false) }
    var loaded by remember(cacheKey, block.url) { mutableStateOf(false) }
    var failed by remember(cacheKey, block.url) { mutableStateOf(false) }
    var aspectRatio by remember(cacheKey, block.url) { mutableFloatStateOf(0f) }
    val shape = RoundedCornerShape(14.dp)
    val density = LocalDensity.current
    val decodeWidthPx = with(density) { MEDIA_MAX_WIDTH.roundToPx() }
    val decodeHeightPx = with(density) { MEDIA_MAX_HEIGHT.roundToPx() }

    val hostRoots = LocalSandboxHostRoots.current
    val dataModel: Any = remember(block.url, hostRoots) {
        when {
            block.url.startsWith("http://", ignoreCase = true) ||
                block.url.startsWith("https://", ignoreCase = true) ||
                block.url.startsWith("data:", ignoreCase = true) -> block.url
            else -> resolveMediaSource(block.url, hostRoots)
        }
    }
    val request = remember(dataModel, cacheKey, decodeWidthPx, decodeHeightPx) {
        ImageRequest.Builder(context)
            .data(dataModel)
            .size(decodeWidthPx, decodeHeightPx)
            .apply { if (cacheKey != null) memoryCacheKey(cacheKey) }
            // 列表内关闭 crossfade：淡入会给每张图片产生一个动画帧，
            // 快速滑动时多张图同时切 loaded 状态会叠加成可见掉帧。全屏预览仍保留淡入。
            .build()
    }
    val imageWidth: androidx.compose.ui.unit.Dp
    val imageHeight: androidx.compose.ui.unit.Dp
    if (aspectRatio > 0f) {
        imageWidth = minOf(MEDIA_MAX_WIDTH, MEDIA_MAX_HEIGHT * aspectRatio)
        imageHeight = imageWidth / aspectRatio
    } else {
        // 占位高度按「常见横图 4:3」估算，而非固定 180dp：
        // 旧写法给的是与真实比例无关的常数高度，图片加载完成回填 aspectRatio 后，
        // item 高度会从占位值突变到真实值 → LazyColumn 在滚动中反复重测量/位置修正。
        // 按比例占位把跳变幅度压到最小（多数图片接近该比例），显著减轻滚动抖动。
        imageWidth = MEDIA_MAX_WIDTH
        imageHeight = imageWidth / MEDIA_PLACEHOLDER_ASPECT
    }

    // AsyncImage avoids SubcomposeAsyncImage's per-item subcomposition overhead in the LazyColumn.
    Box(
        modifier = Modifier
            .size(imageWidth, imageHeight)
            .clip(shape)
            .background(MaterialTheme.colorScheme.surfaceContainerHigh)
            .then(if (loaded) Modifier.clickable { previewing = true } else Modifier),
        contentAlignment = Alignment.Center,
    ) {
        AsyncImage(
            model = request,
            contentDescription = block.description.ifBlank { stringResource(R.string.components_md_web_content) },
            contentScale = ContentScale.FillBounds,
            onLoading = {
                loaded = false
                failed = false
            },
            onSuccess = { state ->
                val src = state.painter.intrinsicSize
                if (!src.isUnspecified && src.width > 0f && src.height > 0f) {
                    aspectRatio = src.width / src.height
                }
                loaded = true
                failed = false
            },
            onError = { state ->
                loaded = false
                failed = true
                // 诊断日志：失败时输出原始 url、解析后的 data model 与异常，便于 logcat 定位
                Log.e(
                    "TaiXu",
                    "chat image load failed: url=${block.url}, data=$dataModel, error=${state.result.throwable}",
                )
            },
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer { alpha = if (loaded) 1f else 0f },
        )
        when {
            failed -> Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                RuntimeIcon(
                    RuntimeIconName.Image,
                    Modifier.size(32.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    stringResource(R.string.components_md_image_load_failed),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            !loaded -> CircularProgressIndicator(Modifier.size(28.dp), strokeWidth = 3.dp)
        }
    }

    if (previewing) {
        ImagePreviewDialog(
            block = block,
            onDismiss = { previewing = false },
        )
    }
}

/** Full-screen generated-image viewer with pinch zoom, pan, and Save As support. */
@Composable
private fun ImagePreviewDialog(block: MdRemoteMedia, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val hostRoots = LocalSandboxHostRoots.current
    val mediaModel = remember(block.url, hostRoots) { resolveMediaSource(block.url, hostRoots) }
    val mimeType = remember(block.url) { imageMimeType(block.url) }
    var saving by remember(block.url) { mutableStateOf(false) }
    var scale by remember(block.url) { mutableFloatStateOf(1f) }
    var offsetX by remember(block.url) { mutableFloatStateOf(0f) }
    var panOffsetY by remember(block.url) { mutableFloatStateOf(0f) }
    // This state is read only inside graphicsLayer lambdas below, so drag frames invalidate the
    // render layers rather than recomposing the Coil image and the entire full-screen dialog.
    var dismissOffsetY by remember(block.url) { mutableFloatStateOf(0f) }
    val density = LocalDensity.current
    val dismissThresholdPx = with(density) { 140.dp.toPx() }
    val fadeDistancePx = with(density) { 360.dp.toPx() }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false),
    ) {
        Surface(color = Color.Transparent, modifier = Modifier.fillMaxSize()) {
            Box(Modifier.fillMaxSize()) {
                Box(
                    Modifier
                        .fillMaxSize()
                        .graphicsLayer {
                            val progress = (abs(dismissOffsetY) / fadeDistancePx).coerceIn(0f, 1f)
                            alpha = 1f - progress * 0.82f
                        }
                        .background(Color.Black),
                )
                SubcomposeAsyncImage(
                    model = remember(mediaModel) { imageRequest(context, mediaModel) },
                    contentDescription = block.description.ifBlank { stringResource(R.string.components_md_image_preview) },
                    contentScale = ContentScale.Fit,
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(12.dp)
                        .graphicsLayer {
                            val progress = (abs(dismissOffsetY) / fadeDistancePx).coerceIn(0f, 1f)
                            val dismissScale = 1f - progress * 0.14f
                            scaleX = scale * dismissScale
                            scaleY = scale * dismissScale
                            translationX = offsetX
                            translationY = panOffsetY + dismissOffsetY
                            alpha = 1f - progress * 0.72f
                        }
                        .pointerInput(block.url) {
                            awaitEachGesture {
                                awaitFirstDown(requireUnconsumed = false)
                                var pointersStillPressed: Boolean
                                do {
                                    val event = awaitPointerEvent()
                                    val zoomChange = event.calculateZoom()
                                    val panChange = event.calculatePan()
                                    val pressedPointers = event.changes.count { it.pressed }
                                    val nextScale = (scale * zoomChange).coerceIn(1f, 6f)
                                    val isZooming = pressedPointers > 1 || abs(zoomChange - 1f) > 0.001f

                                    if (isZooming || scale > 1.01f || nextScale > 1.01f) {
                                        scale = nextScale
                                        dismissOffsetY = 0f
                                        if (scale <= 1.01f) {
                                            scale = 1f
                                            offsetX = 0f
                                            panOffsetY = 0f
                                        } else {
                                            offsetX += panChange.x
                                            panOffsetY += panChange.y
                                        }
                                    } else if (abs(panChange.y) >= abs(panChange.x)) {
                                        // At 1x, a one-finger vertical drag dismisses the viewer.
                                        dismissOffsetY += panChange.y
                                    }
                                    event.changes.forEach { change ->
                                        if (change.positionChanged()) change.consume()
                                    }
                                    pointersStillPressed = event.changes.any { it.pressed }
                                } while (pointersStillPressed)

                                if (scale <= 1.01f && abs(dismissOffsetY) >= dismissThresholdPx) {
                                    onDismiss()
                                } else if (dismissOffsetY != 0f) {
                                    val startOffset = dismissOffsetY
                                    scope.launch {
                                        Animatable(startOffset).animateTo(0f, spring()) {
                                            dismissOffsetY = value
                                        }
                                    }
                                }
                            }
                        },
                ) {
                    when (painter.state.collectAsState().value) {
                        is coil3.compose.AsyncImagePainter.State.Error -> Text(
                            text = stringResource(R.string.components_md_image_load_failed),
                            color = Color.White,
                            modifier = Modifier.align(Alignment.Center),
                        )
                        is coil3.compose.AsyncImagePainter.State.Success -> androidx.compose.foundation.Image(
                            painter = painter,
                            contentDescription = null,
                            contentScale = ContentScale.Fit,
                            modifier = Modifier.fillMaxSize(),
                        )
                        else -> CircularProgressIndicator(
                            modifier = Modifier.align(Alignment.Center).size(32.dp),
                            color = Color.White,
                        )
                    }
                }

                Row(
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(16.dp)
                        .graphicsLayer {
                            val progress = (abs(dismissOffsetY) / fadeDistancePx).coerceIn(0f, 1f)
                            alpha = 1f - progress
                        },
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    IconButton(
                        onClick = {
                            if (!saving) scope.launch {
                                saving = true
                                val saved = withContext(Dispatchers.IO) {
                                    saveImageToGallery(context, mediaModel, mimeType)
                                }
                                saving = false
                                Toast.makeText(
                                    context,
                                    context.getString(if (saved) R.string.components_md_image_saved else R.string.components_md_image_save_failed),
                                    Toast.LENGTH_SHORT,
                                ).show()
                            }
                        },
                        enabled = !saving,
                        contentDescription = stringResource(R.string.components_md_save_image),
                    ) {
                        if (saving) {
                            CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp, color = Color.White)
                        } else {
                            RuntimeIcon(RuntimeIconName.Download, Modifier.size(22.dp), Color.White)
                        }
                    }
                    IconButton(onClick = onDismiss, contentDescription = stringResource(R.string.components_close)) {
                        RuntimeIcon(RuntimeIconName.Close, Modifier.size(22.dp), Color.White)
                    }
                }
            }
        }
    }
}

private fun imageRequest(context: Context, dataModel: Any): ImageRequest =
    ImageRequest.Builder(context).data(dataModel).crossfade(true).build()

private fun imageMimeType(source: String): String {
    if (source.startsWith("data:", ignoreCase = true)) {
        return source.substringAfter("data:").substringBefore(';').takeIf { it.startsWith("image/") } ?: "image/png"
    }
    val clean = source.substringBefore('?').substringBefore('#').lowercase()
    return when {
        clean.endsWith(".jpg") || clean.endsWith(".jpeg") -> "image/jpeg"
        clean.endsWith(".webp") -> "image/webp"
        clean.endsWith(".gif") -> "image/gif"
        clean.endsWith(".avif") -> "image/avif"
        else -> "image/png"
    }
}

private fun imageExtension(mimeType: String): String = when (mimeType.lowercase()) {
    "image/jpeg" -> "jpg"
    "image/webp" -> "webp"
    "image/gif" -> "gif"
    "image/avif" -> "avif"
    else -> "png"
}

/** Saves into Pictures/TaiXu so the generated image appears in the system gallery immediately. */
private fun saveImageToGallery(context: Context, mediaModel: Any, mimeType: String): Boolean {
    val resolver = context.contentResolver
    val values = ContentValues().apply {
        put(MediaStore.Images.Media.DISPLAY_NAME, "taixu-${System.currentTimeMillis()}.${imageExtension(mimeType)}")
        put(MediaStore.Images.Media.MIME_TYPE, mimeType)
        put(MediaStore.Images.Media.RELATIVE_PATH, "${Environment.DIRECTORY_PICTURES}/TaiXu")
        put(MediaStore.Images.Media.IS_PENDING, 1)
    }
    val target = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values) ?: return false
    return runCatching {
        copyImageSource(context, mediaModel, target)
        resolver.update(
            target,
            ContentValues().apply { put(MediaStore.Images.Media.IS_PENDING, 0) },
            null,
            null,
        )
        true
    }.getOrElse {
        resolver.delete(target, null, null)
        false
    }
}

private fun copyImageSource(context: Context, source: Any, target: Uri) {
    context.contentResolver.openOutputStream(target)?.use { output ->
        when (source) {
            is File -> source.inputStream().use { it.copyTo(output) }
            is String -> when {
                source.startsWith("data:", ignoreCase = true) -> {
                    val payload = source.substringAfter(',', missingDelimiterValue = "")
                    require(payload.isNotEmpty()) { "Invalid image data URL" }
                    ByteArrayInputStream(Base64.decode(payload, Base64.DEFAULT)).use { it.copyTo(output) }
                }
                source.startsWith("file://", ignoreCase = true) ->
                    File(source.removePrefix("file://")).inputStream().use { it.copyTo(output) }
                source.startsWith("http://", true) || source.startsWith("https://", true) -> {
                    val connection = URL(source).openConnection() as HttpURLConnection
                    connection.connectTimeout = 15_000
                    connection.readTimeout = 30_000
                    connection.instanceFollowRedirects = true
                    try {
                        check(connection.responseCode in 200..299) { "HTTP ${connection.responseCode}" }
                        connection.inputStream.use { it.copyTo(output) }
                    } finally {
                        connection.disconnect()
                    }
                }
                else -> File(source).inputStream().use { it.copyTo(output) }
            }
        }
    } ?: error("Cannot open destination")
}

private val MEDIA_MAX_WIDTH = 260.dp
private val MEDIA_MAX_HEIGHT = 340.dp
/** 未加载完成时的占位宽高比（4:3 横图，接近常见截图/生成图比例，可把图片加载后的高度跳变压到最小）。 */
private const val MEDIA_PLACEHOLDER_ASPECT = 4f / 3f
private const val MARKDOWN_CACHE_MAX_CHARS = 128_000

@Composable
private fun CodeBlock(block: MdCodeBlock) {
    val context = LocalContext.current
    val isDark = MaterialTheme.colorScheme.surface.luminance() < 0.5f

    val copyCode = {
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText(context.getString(R.string.components_md_code_clipboard_label), block.code))
        Toast.makeText(context, context.getString(R.string.components_md_code_copied), Toast.LENGTH_SHORT).show()
    }

    val highlightedText = remember(block.code, block.language, isDark) {
        SyntaxHighlighter.highlight(block.code, block.language, isDark = isDark)
    }

    val containerBg = if (isDark) Color(0xFF0D1117) else Color(0xFFF6F8FA)
    val headerBg = if (isDark) Color(0xFF161B22) else Color(0xFFEAEEF2)
    val borderColor = if (isDark) Color(0xFF30363D) else Color(0xFFD0D7DE)
    val headerTextColor = if (isDark) Color(0xFF8B949E) else Color(0xFF57606A)

    Surface(
        color = containerBg,
        shape = RoundedCornerShape(8.dp),
        border = androidx.compose.foundation.BorderStroke(0.6.dp, borderColor),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column {
            // 代码块顶部信息栏：语言徽章 + 复制按钮 (紧凑设计)
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(headerBg)
                    .padding(horizontal = 10.dp, vertical = 3.5.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(
                    text = if (block.language.isNotBlank()) block.language.uppercase() else stringResource(R.string.components_md_code_badge),
                    style = MaterialTheme.typography.labelSmall.copy(
                        fontSize = 10.5.sp,
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.SemiBold,
                    ),
                    color = headerTextColor,
                )
                Surface(
                    color = Color.Transparent,
                    shape = RoundedCornerShape(4.dp),
                    modifier = Modifier.clickable(onClick = copyCode),
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                        modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp),
                    ) {
                        RuntimeIcon(
                            RuntimeIconName.Copy,
                            Modifier.size(11.dp),
                            tint = headerTextColor,
                        )
                        Text(
                            text = stringResource(R.string.components_md_copy),
                            style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.5.sp),
                            color = headerTextColor,
                        )
                    }
                }
            }

            // 代码高亮显示区 (紧凑字号与间距)
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState())
                    .padding(horizontal = 10.dp, vertical = 7.dp),
            ) {
                Text(
                    text = highlightedText,
                    style = MaterialTheme.typography.bodySmall.copy(
                        fontFamily = FontFamily.Monospace,
                        fontSize = 11.5.sp,
                        lineHeight = 16.5.sp,
                    ),
                )
            }
        }
    }
}

private fun splitRow(line: String): List<String> =
    line.trim().trim('|').split("|").map { it.trim() }

private fun isTableSeparator(line: String): Boolean {
    val t = line.trim()
    if (t.isEmpty()) return false
    val cells = t.trim('|').split("|")
    if (cells.size < 2) return false
    return cells.all { c ->
        val s = c.trim()
        s.isEmpty() || s.all { ch -> ch == '-' || ch == ':' || ch.isWhitespace() }
    }
}

private fun isTableStart(lines: List<String>, i: Int): Boolean =
    i + 1 < lines.size && lines[i].trimStart().startsWith("|") && isTableSeparator(lines[i + 1])

@Composable
private fun TableBlock(table: MdTable) {
    val colCount = (listOf(table.headers) + table.rows).maxOf { it.size }

    // 列宽按「该列所有单元格中最宽者」估算，用固定 dp 宽度绘制。
    //
    // 为什么不用 IntrinsicSize / weight：
    //   旧写法 Box{ Column(horizontalScroll + width(IntrinsicSize.Max)){ Row(fillMaxWidth){ 列 weight(1f) } } }
    //   三者语义冲突——horizontalScroll 下子项获得无限宽约束，IntrinsicSize 依赖固有测量，
    //   而 weight 需要确定的可分配宽度；结果每列被压成 0 宽，界面只剩最左一列
    //   （用户实际观测：表格内容丢失、只剩第一列）。
    //   改为按字符数估算固定宽度：纯算术、确定性 100%，不依赖 Compose 测量机制。
    val colWidths = (0 until colCount).map { c ->
        val widest = (listOf(table.headers) + table.rows)
            .mapNotNull { it.getOrNull(c) }
            .maxOfOrNull { displayWidthOf(it) } ?: 1
        // 每字符约 7.2dp（bodySmall ~12sp 的粗略折算），上下限夹逼避免极窄/极宽列。
        (widest * 7.2f).dp.coerceIn(48.dp, 280.dp)
    }

    Box(
        Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f), RoundedCornerShape(8.dp)),
    ) {
        Column(
            Modifier
                .horizontalScroll(rememberScrollState())
                .padding(10.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Row {
                (0 until colCount).forEach { c ->
                    InlineText(
                        table.headers.getOrNull(c) ?: "",
                        MaterialTheme.typography.bodySmall,
                        bold = true,
                        modifier = Modifier
                            .width(colWidths[c])
                            .padding(end = 12.dp),
                    )
                }
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            table.rows.forEach { row ->
                Row {
                    (0 until colCount).forEach { c ->
                        InlineText(
                            row.getOrNull(c) ?: "",
                            MaterialTheme.typography.bodySmall,
                            modifier = Modifier
                                .width(colWidths[c])
                                .padding(end = 12.dp),
                        )
                    }
                }
            }
        }
    }
}

/**
 * 估算字符串的显示宽度（以半角字符为单位）。
 *
 * Markdown 强调标记（`**`、`*`、`` ` ``）不占实际显示宽度，需先剥离；
 * CJK / 全角标点按 2 个单位计，其余按 1。用于表格列宽估算，无需精确字形度量。
 */
private fun displayWidthOf(raw: String): Int {
    val text = raw
        .replace("**", "")
        .replace("`", "")
        .replace("*", "")
    var width = 0
    text.forEach { ch ->
        val code = ch.code
        width += when {
            code in 0x1100..0x115F -> 2                 // 韩文字母
            code in 0x2E80..0xA4CF -> 2                 // CJK 部首/汉字/日文假名
            code in 0xAC00..0xD7A3 -> 2                 // 韩文音节
            code in 0xF900..0xFAFF -> 2                 // CJK 兼容表意
            code in 0xFE30..0xFE6F -> 2                 // CJK 兼容形式
            code in 0xFF00..0xFF60 -> 2                 // 全角 ASCII
            code in 0xFFE0..0xFFE6 -> 2                 // 全角符号
            else -> 1
        }
    }
    return width.coerceAtLeast(1)
}

@Composable
private fun ListBlock(block: MdList) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        block.items.forEachIndexed { index, item ->
            Row(Modifier.fillMaxWidth()) {
                Text(
                    if (block.ordered) "${index + 1}." else "\u2022",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(end = 8.dp),
                )
                InlineText(item, MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(end = 0.dp))
            }
        }
    }
}

@Composable
private fun QuoteBlock(block: MdQuote) {
    Column(
        verticalArrangement = Arrangement.spacedBy(2.dp),
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f), RoundedCornerShape(6.dp))
            .padding(horizontal = 10.dp, vertical = 6.dp),
    ) {
        block.lines.forEach { line ->
            InlineText(line, MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

fun markdownBlocksPreview(
    markdown: String,
    contentCacheKey: String? = null,
    largeCacheMinChars: Int = MARKDOWN_CACHE_MAX_CHARS,
): String {
    return resolveMarkdownBlocks(markdown, contentCacheKey, largeCacheMinChars).joinToString("\n") { block ->
        when (block) {
            is MdParagraph -> "P:${block.text}"
            is MdHeading -> "H${block.level}:${block.text}"
            is MdCodeBlock -> "C:${block.language}:${block.code}"
            is MdList -> "L:${block.items.joinToString("|")}"
            is MdQuote -> "Q:${block.lines.joinToString("|")}"
            is MdTable -> "T:${block.headers.joinToString("|")}"
            is MdRemoteMedia -> "M:${block.url}"
            is MdHr -> "HR"
        }
    }
}

fun clearMarkdownCachesForTest() {
    markdownBlockCache.evictAll()
    largeMarkdownBlockCache.evictAll()
    inlineSpanCache.evictAll()
}
