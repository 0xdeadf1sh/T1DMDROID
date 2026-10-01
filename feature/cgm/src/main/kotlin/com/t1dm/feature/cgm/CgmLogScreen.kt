package com.t1dm.feature.cgm

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.DisableSelection
import androidx.compose.foundation.text.selection.LocalTextSelectionColors
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.text.selection.TextSelectionColors
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.ClipboardManager
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.OffsetMapping
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.input.TransformedText
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.t1dm.core.design.HapticEvent
import com.t1dm.core.design.LocalAnimationsEnabled
import com.t1dm.core.design.T1dmFontId
import com.t1dm.core.design.fontFamilyFor
import com.t1dm.core.design.rememberT1dmHaptics
import com.t1dm.core.model.CgmLogEntry
import com.t1dm.core.model.CgmLogKind
import com.t1dm.core.model.CgmLogLevel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.ZoneId

/** Fixed, whatever the app theme. */
private object Term {
    val bg = Color(0xFF020602)
    val fg = Color(0xFF39FF14)
    val dim = Color(0xFF2F8F2F)
    val faint = Color(0xFF174417)
    val tx = Color(0xFF3FD0FF)
    val rx = Color(0xFF39FF14)
    val ble = Color(0xFFFF4FD8)
    val dec = Color(0xFFE6FFE6)
    val log = Color(0xFF8FBF8F)
    val warn = Color(0xFFFFB000)
    val err = Color(0xFFFF3B3B)
    val fold = Color(0xFFC8FF00)
    val hex = Color(0xFF5FAF5F)
    val path = Color(0xFF5FA8FF)

    /** Opaque: every colour above keeps at least 3.2:1 contrast over it. */
    val select = Color(0xFF2A2F45)
}

private val TermSelection = TextSelectionColors(handleColor = Term.fg, backgroundColor = Term.select)

/** One toggle per kind; drawn in the kind's colour, so the row is also the legend. */
private enum class Filter(val label: String, val kind: CgmLogKind, val color: Color) {
    TX("tx", CgmLogKind.TX, Term.tx),
    RX("rx", CgmLogKind.RX, Term.rx),
    BLE("ble", CgmLogKind.GATT, Term.ble),
    DEC("dec", CgmLogKind.DEC, Term.dec),
    LOG("log", CgmLogKind.LOG, Term.log),
}

private class Folded(val entries: List<CgmLogEntry>, val rows: List<CgmLogRow>)

private class LineClipboard(private val real: ClipboardManager) : ClipboardManager by real {
    override fun setText(annotatedString: AnnotatedString) =
        real.setText(AnnotatedString(joinMarkedLines(annotatedString)))
}

private sealed interface Item {
    val key: String

    class Entry(val index: Int, val depth: Int) : Item {
        override val key get() = "e$index"
    }

    class FoldHead(val fold: CgmLogRow.Fold, val open: Boolean) : Item {
        override val key get() = "f${fold.key}"
    }

    class UnitHead(val unit: CgmLogUnit, val open: Boolean) : Item {
        override val key get() = "u${unit.first}"
    }

    class FoldPager(val foldKey: Int, val page: Int, val pages: Int) : Item {
        override val key get() = "p$foldKey"
    }

    class Reply(val reply: ShownReply) : Item {
        override val key get() = "r${reply.id}"
    }

    /** Index 0 on the newest page: the view holds the bottom while lines arrive above it. */
    data object Tail : Item {
        override val key get() = "tail"
    }
}

/** Screen only; never written to the log. */
private class ShownReply(val id: Int, val atMs: Long, val lines: List<CgmReplyLine>)

/** One sensor's log as a terminal: folded, [CGM_LOG_PAGE] rows a page, newest page first. */
@Composable
fun CgmLogScreen(
    name: String,
    /** Null while the file loads. */
    entries: List<CgmLogEntry>?,
    onSave: () -> Unit = {},
    /** The last save's outcome for the status line; null before one. */
    saveNote: String? = null,
    /** Log text only; the toolbar, pager and status line keep their size. */
    fontSp: Int = CGM_LOG_FONT_SP_DEFAULT,
    onFontSp: (Int) -> Unit = {},
    /** This sensor's panel row; null once the panel no longer lists it. */
    sensor: CgmSensorRow? = null,
    panel: CgmPanelState = CgmPanelState(),
    /** Null: no prompt. */
    console: CgmConsoleHost? = null,
    onExit: () -> Unit = {},
) {
    val zone = remember { ZoneId.systemDefault() }
    val haptics = rememberT1dmHaptics()
    val clipboard = LocalClipboardManager.current
    val lineClipboard = remember(clipboard) { LineClipboard(clipboard) }
    val mono = remember { fontFamilyFor(T1dmFontId.IBM_PLEX_MONO) }
    val base = remember(mono) { TextStyle(fontFamily = mono, fontSize = 11.sp, lineHeight = 15.sp, color = Term.fg) }
    val sp = clampCgmLogFontSp(fontSp)
    val logStyle = remember(base, sp) { base.copy(fontSize = sp.sp, lineHeight = (sp * LOG_LINE_HEIGHT).sp) }

    var filters by remember { mutableStateOf(Filter.entries.toSet()) }
    var paused by remember { mutableStateOf(false) }
    var page by remember { mutableIntStateOf(0) }
    val following = !paused && page == 0
    var frozen by remember { mutableStateOf<List<CgmLogEntry>?>(null) }
    LaunchedEffect(following) { frozen = if (following) null else entries }
    val source = frozen ?: entries

    var replies by remember { mutableStateOf(emptyList<ShownReply>()) }
    var nextReplyId by remember { mutableIntStateOf(0) }
    // `clear`: lines up to this instant leave the screen; the file keeps them.
    var clearedAtMs by remember { mutableStateOf(Long.MIN_VALUE) }
    var grep by remember { mutableStateOf<String?>(null) }

    val folded by produceState<Folded?>(null, source, filters, clearedAtMs, grep) {
        val src = source ?: return@produceState
        value = withContext(Dispatchers.Default) {
            val kinds = filters.mapTo(HashSet()) { it.kind }
            val allKinds = kinds.size == CgmLogKind.entries.size
            val needle = grep
            val shown = if (allKinds && clearedAtMs == Long.MIN_VALUE && needle == null) {
                src
            } else {
                src.filter { e ->
                    (allKinds || e.kind in kinds) && e.wallMs > clearedAtMs &&
                        (needle == null || e.text.contains(needle, ignoreCase = true) ||
                            e.channel?.contains(needle, ignoreCase = true) == true)
                }
            }
            Folded(shown, foldCgmLog(shown))
        }
    }

    // Fold keys index the filtered list, so any change to the filter invalidates them.
    var openFolds by remember(filters, clearedAtMs, grep) { mutableStateOf(emptySet<Int>()) }
    var openUnits by remember(filters, clearedAtMs, grep) { mutableStateOf(emptySet<Int>()) }
    var foldPages by remember(filters, clearedAtMs, grep) { mutableStateOf(emptyMap<Int, Int>()) }

    val rows = folded?.rows.orEmpty()
    val pages = cgmLogPageCount(rows.size)
    LaunchedEffect(pages) { if (page >= pages) page = pages - 1 }
    val shownPage = minOf(page, pages - 1)

    val items = remember(folded, shownPage, openFolds, openUnits, foldPages, replies) {
        buildList {
            val list = folded?.entries.orEmpty()
            // Replies sit among the lines by time, on the newest page only.
            val pendingReplies = if (shownPage == 0) ArrayDeque(replies) else ArrayDeque()
            for (i in cgmLogPage(rows.size, shownPage)) {
                val row = rows[i]
                val rowMs = when (row) {
                    is CgmLogRow.Line -> list[row.index].wallMs
                    is CgmLogRow.Fold -> row.firstMs
                }
                while (pendingReplies.isNotEmpty() && pendingReplies.first().atMs < rowMs) {
                    add(Item.Reply(pendingReplies.removeFirst()))
                }
                when (row) {
                    is CgmLogRow.Line -> add(Item.Entry(row.index, 0))
                    is CgmLogRow.Fold -> {
                        val open = row.key in openFolds
                        add(Item.FoldHead(row, open))
                        if (!open) continue
                        val unitPages = cgmLogPageCount(row.units.size)
                        val unitPage = (foldPages[row.key] ?: 0).coerceIn(0, unitPages - 1)
                        if (unitPages > 1) add(Item.FoldPager(row.key, unitPage, unitPages))
                        for (k in cgmLogPage(row.units.size, unitPage)) {
                            val u = row.units[k]
                            val unitOpen = u.first in openUnits
                            add(Item.UnitHead(u, unitOpen))
                            if (unitOpen) for (j in 0 until u.size) add(Item.Entry(u[j], 2))
                        }
                    }
                }
            }
            pendingReplies.forEach { add(Item.Reply(it)) }
            if (shownPage == 0) add(Item.Tail)
        }
    }

    // Reversed layout: index 0 is the bottom, so a fresh list opens on the newest line.
    val listState = rememberLazyListState()
    LaunchedEffect(shownPage) { listState.scrollToItem(0) }

    val scope = rememberCoroutineScope()
    val cgmConsole = remember(console) { console?.let(::CgmConsole) }
    val submit: (String) -> Unit = { line ->
        cgmConsole?.let { c ->
            val view = CgmConsoleView(sensor, panel, entries.orEmpty(), zone, System.currentTimeMillis())
            scope.launch {
                val reply = c.run(line, view)
                when (val effect = reply.effect) {
                    CgmConsoleEffect.Clear -> {
                        clearedAtMs = System.currentTimeMillis()
                        replies = emptyList()
                    }
                    is CgmConsoleEffect.Grep -> grep = effect.text
                    CgmConsoleEffect.Exit -> {
                        onExit()
                        return@launch
                    }
                    null -> Unit
                }
                if (reply.lines.isNotEmpty()) {
                    replies = (replies + ShownReply(nextReplyId++, System.currentTimeMillis(), reply.lines))
                        .takeLast(MAX_REPLIES)
                }
                page = 0
                listState.scrollToItem(0)
            }
        }
    }

    Column(
        Modifier
            .fillMaxSize()
            .padding(horizontal = 10.dp, vertical = 6.dp),
    ) {
        ToolRow {
            val allOn = filters.size == Filter.entries.size
            val mark = when {
                allOn -> "[x]"
                filters.isEmpty() -> "[ ]"
                else -> "[-]"
            }
            TermButton("$mark all", base, if (filters.isEmpty()) Term.fg.copy(alpha = OFF_ALPHA) else Term.fg) {
                haptics.perform(HapticEvent.Tap)
                filters = if (allOn) emptySet() else Filter.entries.toSet()
            }
            Filter.entries.forEach { f ->
                val on = f in filters
                TermButton(
                    if (on) "[x] ${f.label}" else "[ ] ${f.label}",
                    base,
                    if (on) f.color else f.color.copy(alpha = OFF_ALPHA),
                ) {
                    haptics.perform(HapticEvent.Tap)
                    filters = if (on) filters - f else filters + f
                }
            }
            TermButton("[-]", base, Term.fg, enabled = sp > CGM_LOG_FONT_SP_MIN) {
                haptics.perform(HapticEvent.Tap)
                onFontSp(sp - 1)
            }
            TermButton("[+]", base, Term.fg, enabled = sp < CGM_LOG_FONT_SP_MAX) {
                haptics.perform(HapticEvent.Tap)
                onFontSp(sp + 1)
            }
        }
        ToolRow {
            Legend("warn", Term.warn, base)
            Legend("error", Term.err, base)
            Legend("fold", Term.fold, base)
            TermButton(if (paused) "[run]" else "[pause]", base, Term.warn) {
                haptics.perform(HapticEvent.Tap)
                paused = !paused
            }
            TermButton("[save]", base, Term.tx) {
                haptics.perform(HapticEvent.Tap)
                onSave()
            }
        }
        Rule()

        val list = folded?.entries
        BoxWithConstraints(Modifier.weight(1f).fillMaxWidth()) {
            val bytesPerRow = hexRowWidth(maxWidth.value * LocalDensity.current.density, logStyle)
            when {
                source == null || list == null -> Text("loading…", style = base.copy(color = Term.dim))
                rows.isEmpty() && replies.isEmpty() -> when {
                    grep != null -> Text("no match", style = base.copy(color = Term.dim))
                    clearedAtMs == Long.MIN_VALUE -> Text("no lines yet", style = base.copy(color = Term.dim))
                }
                else -> CompositionLocalProvider(
                    LocalClipboardManager provides lineClipboard,
                    LocalTextSelectionColors provides TermSelection,
                ) {
                    SelectionContainer(Modifier.fillMaxSize()) {
                        LazyColumn(state = listState, modifier = Modifier.fillMaxSize(), reverseLayout = true) {
                            items(items.asReversed(), key = { it.key }) { item ->
                                when (item) {
                                    is Item.Entry -> EntryLine(
                                        e = list[item.index],
                                        depth = item.depth,
                                        zone = zone,
                                        base = logStyle,
                                        bytesPerRow = bytesPerRow,
                                    )
                                    is Item.FoldHead -> DisableSelection {
                                        FoldLine(item.fold, item.open, zone, logStyle) {
                                            haptics.perform(HapticEvent.Tap)
                                            val k = item.fold.key
                                            openFolds = if (item.open) openFolds - k else openFolds + k
                                        }
                                    }
                                    is Item.UnitHead -> DisableSelection {
                                        UnitLine(item.unit, item.open, list, zone, logStyle) {
                                            haptics.perform(HapticEvent.Tap)
                                            val k = item.unit.first
                                            openUnits = if (item.open) openUnits - k else openUnits + k
                                        }
                                    }
                                    is Item.FoldPager -> DisableSelection {
                                        Pager(item.page, item.pages, base, indent = 2) { p ->
                                            haptics.perform(HapticEvent.Tap)
                                            foldPages = foldPages + (item.foldKey to p)
                                        }
                                    }
                                    is Item.Reply -> ReplyBlock(item.reply, logStyle)
                                    Item.Tail -> Spacer(Modifier.height(1.dp))
                                }
                            }
                        }
                    }
                }
            }
        }

        if (console != null) Prompt(logStyle, sensor, submit)
        Rule()
        StatusLine(
            name = name,
            lines = folded?.entries?.size ?: 0,
            rows = rows.size,
            following = following,
            newer = if (frozen != null) (entries?.size ?: 0) - (frozen?.size ?: 0) else 0,
            note = listOfNotNull(grep?.let { "grep $it" }, saveNote).joinToString("  ").ifEmpty { null },
            base = base,
        )
        Pager(shownPage, pages, base, indent = 0) { p ->
            haptics.perform(HapticEvent.Tap)
            page = p
        }
    }
}

@Composable
private fun ToolRow(content: @Composable RowScope.() -> Unit) {
    Row(
        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.CenterVertically,
        content = content,
    )
}

/** A colour key only: warnings, errors and folds span every kind, so they have no toggle. */
@Composable
private fun Legend(label: String, color: Color, base: TextStyle) {
    Text("■ $label", style = base.copy(color = color, fontSize = 13.sp), modifier = Modifier.padding(vertical = 11.dp))
}

@Composable
private fun Rule() {
    Spacer(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp)
            .height(1.dp)
            .background(Term.faint),
    )
}

private val RowRuleWidth = 1.dp

/** Under the row; drawn, so it stays out of copied text. */
private fun Modifier.rowRule(): Modifier = drawBehind {
    val h = RowRuleWidth.toPx()
    drawRect(Term.faint, topLeft = Offset(0f, size.height - h), size = Size(size.width, h))
}.padding(bottom = RowRuleWidth)

/** A bracketed command; 40 dp tall so it stays tappable at 11 sp. */
@Composable
private fun TermButton(label: String, base: TextStyle, color: Color, enabled: Boolean = true, onClick: () -> Unit) {
    Text(
        label,
        style = base.copy(color = if (enabled) color else Term.faint, fontSize = 13.sp),
        modifier = Modifier
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .padding(vertical = 11.dp, horizontal = 2.dp),
    )
}

/** Older is next, newer is prev; page 1 is the newest. */
@Composable
private fun Pager(page: Int, pages: Int, base: TextStyle, indent: Int, onPage: (Int) -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(start = (indent * 8).dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        TermButton("[‹ prev]", base, Term.fg, enabled = page > 0) { onPage(page - 1) }
        Text("${page + 1}/$pages", style = base.copy(color = Term.dim, fontSize = 13.sp))
        TermButton("[next ›]", base, Term.fg, enabled = page < pages - 1) { onPage(page + 1) }
    }
}

/** Reverse video, as vim draws its status line. */
@Composable
private fun StatusLine(
    name: String,
    lines: Int,
    rows: Int,
    following: Boolean,
    newer: Int,
    note: String?,
    base: TextStyle,
) {
    val mode = if (following) "-- FOLLOW --" else if (newer > 0) "-- HELD +$newer --" else "-- HELD --"
    Text(
        listOfNotNull(mode, name, "$lines lines · $rows rows", note).joinToString("  "),
        style = base.copy(color = Term.bg, fontWeight = FontWeight.Bold),
        maxLines = 1,
        modifier = Modifier
            .fillMaxWidth()
            .background(if (following) Term.fg else Term.warn)
            .padding(horizontal = 6.dp, vertical = 2.dp),
    )
}

/** Steady when animations are off. */
@Composable
private fun rememberBlink(): Boolean {
    val animate = LocalAnimationsEnabled.current
    val on by produceState(true, animate) {
        if (!animate) return@produceState
        while (true) {
            delay(CURSOR_BLINK_MS)
            value = !value
        }
    }
    return on
}

/** A block cursor drawn at the caret; the field's own caret is hidden. */
@Composable
private fun Prompt(style: TextStyle, row: CgmSensorRow?, onSubmit: (String) -> Unit) {
    var value by remember { mutableStateOf(TextFieldValue("")) }
    var layout by remember { mutableStateOf<TextLayoutResult?>(null) }
    val on = rememberBlink()
    val measurer = rememberTextMeasurer()
    val cellPx = remember(style) { measurer.measure("0", style).size.width.toFloat() }
    val ghost = remember(row) {
        VisualTransformation { text ->
            val rest = completionOf(text.text, row)
            if (rest.isEmpty()) return@VisualTransformation TransformedText(text, OffsetMapping.Identity)
            val n = text.length
            TransformedText(
                text + AnnotatedString(rest, SpanStyle(color = Term.dim)),
                object : OffsetMapping {
                    override fun originalToTransformed(offset: Int) = offset
                    override fun transformedToOriginal(offset: Int) = minOf(offset, n)
                },
            )
        }
    }
    Row(
        Modifier.fillMaxWidth().heightIn(min = 40.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text("> ", style = style.copy(color = Term.dim))
        BasicTextField(
            value = value,
            onValueChange = { value = it },
            modifier = Modifier.weight(1f),
            textStyle = style,
            singleLine = true,
            cursorBrush = SolidColor(Color.Transparent),
            visualTransformation = ghost,
            onTextLayout = { layout = it },
            keyboardOptions = KeyboardOptions(
                capitalization = KeyboardCapitalization.None,
                autoCorrectEnabled = false,
                keyboardType = KeyboardType.Ascii,
                imeAction = ImeAction.Send,
            ),
            keyboardActions = KeyboardActions(
                onSend = {
                    val line = value.text.trim()
                    value = TextFieldValue("")
                    if (line.isNotEmpty()) onSubmit(line)
                },
            ),
            decorationBox = { inner ->
                Box(
                    Modifier.drawWithContent {
                        drawContent()
                        val l = layout
                        if (!on || l == null) return@drawWithContent
                        val at = value.selection.end.coerceIn(0, value.text.length)
                        val r = l.getCursorRect(at)
                        // Half strength over a character, so the character still reads.
                        val alpha = if (at == l.layoutInput.text.length) 1f else 0.5f
                        drawRect(Term.fg.copy(alpha = alpha), Offset(r.left, r.top), Size(cellPx, r.height))
                    },
                ) { inner() }
            },
        )
    }
}

@Composable
private fun ReplyBlock(reply: ShownReply, style: TextStyle) {
    val text = remember(reply) {
        buildAnnotatedString {
            markLine {
                reply.lines.forEachIndexed { i, line ->
                    if (i > 0) append('\n')
                    withStyle(SpanStyle(color = toneColor(line.tone))) { append(line.text) }
                }
            }
        }
    }
    Text(text, style = style, modifier = Modifier.fillMaxWidth().rowRule().padding(vertical = 4.dp))
}

private fun toneColor(tone: CgmReplyTone): Color = when (tone) {
    CgmReplyTone.OUT -> Term.dec
    CgmReplyTone.DIM -> Term.dim
    CgmReplyTone.WARN -> Term.warn
    CgmReplyTone.ERR -> Term.err
}

private fun colorOf(e: CgmLogEntry): Color = when {
    e.level == CgmLogLevel.E -> Term.err
    e.level == CgmLogLevel.W -> Term.warn
    else -> when (e.kind) {
        CgmLogKind.TX -> Term.tx
        CgmLogKind.RX -> Term.fg
        CgmLogKind.GATT -> Term.ble
        CgmLogKind.DEC -> Term.dec
        CgmLogKind.LOG -> Term.log
    }
}

@Composable
private fun EntryLine(
    e: CgmLogEntry,
    depth: Int,
    zone: ZoneId,
    base: TextStyle,
    bytesPerRow: Int,
) {
    val head: AnnotatedString = remember(e) {
        val color = colorOf(e)
        buildAnnotatedString {
            markLine {
                withStyle(SpanStyle(color = Term.dim)) {
                    append(clockOf(e.wallMs, zone))
                    append(' ')
                }
                withStyle(SpanStyle(color = color, fontWeight = FontWeight.Bold)) {
                    append(e.level.name)
                    append(' ')
                    append(kindTag(e.kind))
                }
                append(' ')
                e.channel?.let { withStyle(SpanStyle(color = Term.path)) { append("[$it] ") } }
                withStyle(SpanStyle(color = color)) { append(e.text) }
            }
        }
    }
    val hex = remember(e, bytesPerRow) {
        e.bytes?.takeIf { it.isNotEmpty() }?.let { b ->
            val rows = (b.indices step bytesPerRow).joinToString("\n") { off ->
                off.toString(16).uppercase().padStart(4, '0') + "  " + hexOf(b, off, minOf(b.size, off + bytesPerRow))
            }
            buildAnnotatedString { markLine { append(rows) } }
        }
    }
    Column(Modifier.fillMaxWidth().rowRule().padding(start = (depth * 8).dp, top = 4.dp, bottom = 4.dp)) {
        Text(head, style = base)
        hex?.let { Text(it, style = base.copy(color = Term.hex), modifier = Modifier.padding(start = 12.dp)) }
    }
}

@Composable
private fun FoldLine(fold: CgmLogRow.Fold, open: Boolean, zone: ZoneId, base: TextStyle, onToggle: () -> Unit) {
    val text = remember(fold, open) {
        buildString {
            append(if (open) "--- " else "+-- ")
            append(clockOf(fold.firstMs, zone)).append('–').append(clockOf(fold.lastMs, zone))
            append(' ').append(topicName(fold.topic)).append(" ×").append(fold.units.size)
            append(" · ").append(fold.lines).append(" lines")
            valueRange(fold.topic, fold.valueMin, fold.valueMax).takeIf { it.isNotEmpty() }?.let { append(" · ").append(it) }
        }
    }
    Text(
        text,
        style = base.copy(color = Term.fold, fontWeight = FontWeight.Bold),
        modifier = Modifier
            .fillMaxWidth()
            .rowRule()
            .clickable(role = Role.Button, onClickLabel = if (open) "Fold" else "Unfold", onClick = onToggle)
            .padding(vertical = 6.dp),
    )
}

@Composable
private fun UnitLine(
    unit: CgmLogUnit,
    open: Boolean,
    entries: List<CgmLogEntry>,
    zone: ZoneId,
    base: TextStyle,
    onToggle: () -> Unit,
) {
    val text = remember(unit, open, unit.size) {
        var pick = entries[unit.first]
        for (k in 0 until unit.size) {
            val e = entries[unit[k]]
            if (e.kind == CgmLogKind.DEC && (pick.kind != CgmLogKind.DEC || e.value != null)) pick = e
        }
        (if (open) "- " else "+ ") + clockOf(entries[unit.first].wallMs, zone) + "  " + pick.text + "  (${unit.size})"
    }
    Text(
        text,
        style = base.copy(color = Term.fg),
        maxLines = if (open) Int.MAX_VALUE else 1,
        modifier = Modifier
            .fillMaxWidth()
            .rowRule()
            .clickable(role = Role.Button, onClickLabel = if (open) "Fold" else "Unfold", onClick = onToggle)
            .padding(start = 8.dp, top = 4.dp, bottom = 4.dp),
    )
}

/** 16 bytes a row when the line holds it, else 8, else 4. */
@Composable
private fun hexRowWidth(widthPx: Float, base: TextStyle): Int {
    val measurer = rememberTextMeasurer()
    val charPx = remember(base) { measurer.measure("0000000000", base).size.width / 10f }
    val density = LocalDensity.current.density
    return remember(widthPx, charPx) {
        val chars = ((widthPx - HEX_INDENT_DP * density) / charPx).toInt()
        listOf(16, 8, 4).firstOrNull { 6 + it * 3 - 1 <= chars } ?: 4
    }
}

const val CGM_LOG_FONT_SP_MIN = 8
const val CGM_LOG_FONT_SP_MAX = 20
const val CGM_LOG_FONT_SP_DEFAULT = 11

fun clampCgmLogFontSp(sp: Int): Int = sp.coerceIn(CGM_LOG_FONT_SP_MIN, CGM_LOG_FONT_SP_MAX)

/** 15 sp lines at the default 11 sp. */
private const val LOG_LINE_HEIGHT = 15f / 11f

private const val CURSOR_BLINK_MS = 530L

/** Oldest dropped first; `clear` drops them all. */
private const val MAX_REPLIES = 200

/** A toggle that is off keeps its colour, dimmed, so the legend still reads. */
private const val OFF_ALPHA = 0.45f

/** Two nesting levels at 8 dp and the dump's own 12 dp. */
private const val HEX_INDENT_DP = 28f
