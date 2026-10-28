package com.aiden.screentopdf.render

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.pdf.PdfDocument
import android.os.Build
import android.text.Layout
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.StaticLayout
import android.text.TextPaint
import android.text.style.ForegroundColorSpan
import android.text.style.RelativeSizeSpan
import android.text.style.StrikethroughSpan
import android.text.style.StyleSpan
import android.text.style.SubscriptSpan
import android.text.style.SuperscriptSpan
import android.text.style.UnderlineSpan
import com.aiden.screentopdf.core.Align
import com.aiden.screentopdf.core.DocBlock
import com.aiden.screentopdf.core.DocumentRenderer
import com.aiden.screentopdf.core.PageSetup
import com.aiden.screentopdf.core.PaperSize
import com.aiden.screentopdf.core.ProgressListener
import com.aiden.screentopdf.core.RebuiltDocument
import com.aiden.screentopdf.core.TextRun
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import kotlin.math.max
import kotlin.math.min

/**
 * Typesets a [RebuiltDocument] into a PDF with real vector text.
 * Uses the platform PdfDocument and StaticLayout, so the text stays selectable.
 */
class PdfTypesetter : DocumentRenderer {

    override suspend fun render(doc: RebuiltDocument, outFile: File, onProgress: ProgressListener): File =
        withContext(Dispatchers.Default) {
            val pdf = PdfDocument()
            val job = TypesetJob(pdf, doc, onProgress)
            try {
                job.run()
                outFile.parentFile?.mkdirs()
                FileOutputStream(outFile).use { pdf.writeTo(it) }
            } finally {
                // PdfDocument refuses to close with a page still open.
                job.finish()
                pdf.close()
            }
            onProgress(1f, "PDF ready")
            outFile
        }
}

// Spacing constants, all in points.
private const val INDENT_STEP = 18f
private const val MARKER_GAP = 5f
/** Space added to the widest marker in a list to get the marker column width. */
private const val MARKER_COLUMN_EXTRA = 7f
private const val CELL_PAD = 4f
private const val RULE_GAP = 6f
private const val CAPTION_GAP = 4f
private const val LINK_COLOUR = 0xFF1155CC.toInt()
private const val GRID_COLOUR = 0xFF808080.toInt()
private const val RULE_COLOUR = 0xFF999999.toInt()

/** Page size, margins, fonts and line heights derived from the [PageSetup]. */
private class PageMetrics(setup: PageSetup) {
    val pageWidth: Float
    val pageHeight: Float

    init {
        val (w, h) = when (setup.paper) {
            PaperSize.A4 -> 595f to 842f
            PaperSize.LETTER -> 612f to 792f
            PaperSize.SLIDE_16_9 -> 960f to 540f
        }
        val landscape = setup.landscape || setup.paper == PaperSize.SLIDE_16_9
        pageWidth = if (landscape) max(w, h) else min(w, h)
        pageHeight = if (landscape) min(w, h) else max(w, h)
    }

    val margin: Float = setup.marginPt.coerceIn(18f, min(pageWidth, pageHeight) / 4f)
    val textWidth: Float = pageWidth - 2 * margin
    val textHeight: Float = pageHeight - 2 * margin

    val baseTypeface: Typeface = when (setup.fontFamily.trim().lowercase()) {
        "serif" -> Typeface.SERIF
        "mono", "monospace" -> Typeface.MONOSPACE
        else -> Typeface.SANS_SERIF
    }
    val bodySize: Float = setup.baseFontSizePt.coerceIn(6f, 36f)
    val lineSpacing: Float = setup.lineSpacing.coerceIn(0.9f, 2.5f)
    val bodyLine: Float = lineHeightFor(bodySize)

    /** Height of one line of text at [size], including the line spacing multiplier. */
    fun lineHeightFor(size: Float): Float {
        val p = Paint().apply { typeface = baseTypeface; textSize = size }
        val fm = p.fontMetrics
        return (fm.descent - fm.ascent) * lineSpacing
    }
}

/** Tracks the current page, canvas and vertical position, and starts new pages. */
private class LayoutCursor(private val pdf: PdfDocument, private val m: PageMetrics) {
    private var page: PdfDocument.Page? = null
    private var pendingGap = 0f

    var pageNumber = 0
        private set

    /** Top of the next thing to draw, in points from the top of the page. */
    var y = 0f
        private set

    val isPageEmpty: Boolean get() = page == null || y <= m.margin + 0.01f
    val remaining: Float get() = m.pageHeight - m.margin - y
    val canvas: Canvas get() = ensurePage().canvas

    fun ensurePage(): PdfDocument.Page {
        page?.let { return it }
        pageNumber++
        val info = PdfDocument.PageInfo.Builder(m.pageWidth.toInt(), m.pageHeight.toInt(), pageNumber).create()
        val p = pdf.startPage(info)
        page = p
        y = m.margin
        pendingGap = 0f
        return p
    }

    fun newPage() {
        finishPage()
        ensurePage()
    }

    /** Asks for vertical space before the next block. Adjacent gaps merge; the larger wins. */
    fun gap(points: Float) {
        pendingGap = max(pendingGap, points)
    }

    /** Applies the pending gap. Gaps are dropped at the top of a page. */
    fun flushGap() {
        ensurePage()
        if (!isPageEmpty) y += pendingGap
        pendingGap = 0f
        if (remaining <= 0f) newPage()
    }

    /** Moves to a new page when [height] will not fit and the page already has content. */
    fun ensureSpace(height: Float) {
        ensurePage()
        if (height > remaining && !isPageEmpty) newPage()
    }

    fun advance(height: Float) {
        y += height
    }

    fun finishPage() {
        page?.let { pdf.finishPage(it) }
        page = null
    }
}

/** One run of the typesetter over one document. */
private class TypesetJob(
    pdf: PdfDocument,
    private val doc: RebuiltDocument,
    private val onProgress: ProgressListener,
) {
    private val m = PageMetrics(doc.setup)
    private val cursor = LayoutCursor(pdf, m)

    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val gridPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 0.5f
        color = GRID_COLOUR
    }
    private val rulePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 0.75f
        color = RULE_COLOUR
    }
    /** Slightly heavier line under header rows when a table has no grid lines. */
    private val headerLinePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 0.75f
        color = GRID_COLOUR
    }
    private val bitmapPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG).apply {
        isFilterBitmap = true
    }

    fun run() {
        val blocks = doc.blocks
        val total = blocks.size.coerceAtLeast(1)
        val listColumns = listColumns(blocks)
        cursor.ensurePage()
        blocks.forEachIndexed { i, block ->
            val next = blocks.getOrNull(i + 1)
            when (block) {
                is DocBlock.Heading -> drawHeading(block)
                is DocBlock.Paragraph -> drawParagraph(block)
                is DocBlock.ListItem -> drawListItem(block, next, listColumns[i])
                is DocBlock.Table -> drawTable(block)
                is DocBlock.Image -> drawImage(block)
                is DocBlock.Rule -> drawRule()
                is DocBlock.PageBreak -> pageBreak()
            }
            onProgress((i + 1).toFloat() / total, "Laying out page ${cursor.pageNumber}")
        }
        cursor.finishPage()
    }

    /** Safe to call more than once. Closes any page still open. */
    fun finish() {
        cursor.finishPage()
    }

    // ---- Blocks -------------------------------------------------------------

    private fun drawHeading(block: DocBlock.Heading) {
        val scale = when (block.level) {
            1 -> 1.8f
            2 -> 1.45f
            3 -> 1.2f
            else -> 1.05f
        }
        val size = m.bodySize * scale
        val line = m.lineHeightFor(size)
        val paint = textPaint(size, bold = true)
        val layout = buildLayout(spannable(block.runs), paint, m.textWidth, block.align)

        cursor.gap(line)
        // Keep with next: the heading plus two body lines must fit, or it moves on.
        drawLayout(layout, m.margin, keepWithNext = 0.4f * line + 2 * m.bodyLine)
        cursor.gap(0.4f * line)
    }

    private fun drawParagraph(block: DocBlock.Paragraph) {
        val size = m.bodySize * block.sizeScale.coerceIn(0.5f, 3f)
        val indent = block.indentLevel.coerceIn(0, 8) * INDENT_STEP
        val paint = textPaint(size)
        val layout = buildLayout(spannable(block.runs), paint, m.textWidth - indent, block.align)
        drawLayout(layout, m.margin + indent)
        cursor.gap(0.6f * m.lineHeightFor(size))
    }

    /**
     * Marker column width for each block, by block index (0 for non-list blocks).
     * Items in one run of list items share a width per level, so "9." and "10." line up.
     */
    private fun listColumns(blocks: List<DocBlock>): FloatArray {
        val paint = textPaint(m.bodySize)
        val columns = FloatArray(blocks.size)
        var i = 0
        while (i < blocks.size) {
            if (blocks[i] !is DocBlock.ListItem) {
                i++
                continue
            }
            var end = i
            while (end < blocks.size && blocks[end] is DocBlock.ListItem) end++
            val widest = HashMap<Int, Float>()
            for (j in i until end) {
                val item = blocks[j] as DocBlock.ListItem
                val w = paint.measureText(item.marker)
                widest[item.level] = max(widest[item.level] ?: 0f, w)
            }
            for (j in i until end) {
                val item = blocks[j] as DocBlock.ListItem
                columns[j] = max(INDENT_STEP, (widest[item.level] ?: 0f) + MARKER_COLUMN_EXTRA)
            }
            i = end
        }
        return columns
    }

    private fun drawListItem(block: DocBlock.ListItem, next: DocBlock?, column: Float) {
        val paint = textPaint(m.bodySize)
        val marker = block.marker
        val markerWidth = paint.measureText(marker)
        val indent = m.margin + block.level.coerceIn(0, 8) * INDENT_STEP
        val textX = indent + column
        val width = m.textWidth - (textX - m.margin)
        val layout = buildLayout(spannable(block.runs), paint, width, Align.LEFT)

        drawLayout(layout, textX) { canvas, top ->
            // Right-align the marker in its column, on the first line's baseline.
            val baseline = top + layout.getLineBaseline(0)
            canvas.drawText(marker, textX - MARKER_GAP - markerWidth, baseline, paint)
        }
        val tight = next is DocBlock.ListItem
        cursor.gap(if (tight) 0.25f * m.bodyLine else 0.6f * m.bodyLine)
    }

    private fun drawRule() {
        cursor.gap(RULE_GAP)
        cursor.flushGap()
        cursor.ensureSpace(rulePaint.strokeWidth + RULE_GAP)
        val y = cursor.y + rulePaint.strokeWidth / 2f
        cursor.canvas.drawLine(m.margin, y, m.margin + m.textWidth, y, rulePaint)
        cursor.advance(rulePaint.strokeWidth)
        cursor.gap(RULE_GAP)
    }

    private fun pageBreak() {
        if (!cursor.isPageEmpty) cursor.newPage()
    }

    // ---- Images -------------------------------------------------------------

    private fun drawImage(block: DocBlock.Image) {
        val bitmap: Bitmap = block.bitmap ?: return
        if (bitmap.isRecycled || bitmap.width <= 0 || bitmap.height <= 0) return

        val captionPaint = textPaint(m.bodySize * 0.9f, italic = true)
        val caption = if (block.caption.isEmpty()) null
        else buildLayout(spannable(block.caption), captionPaint, m.textWidth, block.align)
        val captionHeight = caption?.let { it.height + CAPTION_GAP } ?: 0f

        var w = m.textWidth * block.widthFraction.coerceIn(0.1f, 1f)
        var h = w * bitmap.height / bitmap.width
        val maxH = m.textHeight - captionHeight
        if (h > maxH) {
            // Taller than a page: shrink to fit while keeping the aspect ratio.
            val s = maxH / h
            w *= s
            h *= s
        }

        cursor.flushGap()
        cursor.ensureSpace(h + captionHeight)
        val x = when (block.align) {
            Align.LEFT, Align.JUSTIFY -> m.margin
            Align.CENTRE -> m.margin + (m.textWidth - w) / 2f
            Align.RIGHT -> m.margin + m.textWidth - w
        }
        val top = cursor.y
        cursor.canvas.drawBitmap(bitmap, null, RectF(x, top, x + w, top + h), bitmapPaint)
        cursor.advance(h)

        if (caption != null) {
            cursor.advance(CAPTION_GAP)
            drawLayout(caption, m.margin)
        }
        cursor.gap(0.6f * m.bodyLine)
    }

    // ---- Tables -------------------------------------------------------------

    private class CellBox(
        val layout: StaticLayout,
        val left: Float,
        val width: Float,
        val fill: Int?,
    )

    private class RowBox(val cells: List<CellBox>, val height: Float, val header: Boolean)

    private fun drawTable(table: DocBlock.Table) {
        if (table.rows.isEmpty()) return
        val colCount = table.rows.maxOf { row -> row.cells.sumOf { it.colSpan.coerceAtLeast(1) } }.coerceAtLeast(1)
        val widths = columnWidths(table.columnWidths, colCount)
        val rows = table.rows.map { measureRow(it, widths, colCount) }
        val headerRows = rows.takeWhile { it.header }
        val headerHeight = headerRows.sumOf { it.height.toDouble() }.toFloat()
        // Header rows repeat on later pages only when they leave room for body rows.
        val repeatHeaders = headerRows.isNotEmpty() && headerHeight <= m.textHeight / 2f
        val minSlice = m.bodyLine + 2 * CELL_PAD

        cursor.flushGap()
        // Keep with previous: the header rows and first body row stay together, so a
        // heading or caption above the table is not left alone at the foot of a page.
        val firstBody = rows.getOrNull(headerRows.size)
        cursor.ensureSpace(min(headerHeight + (firstBody?.height ?: 0f), m.textHeight))

        rows.forEachIndexed { i, row ->
            val body = i >= headerRows.size
            val tall = row.height > m.textHeight
            // A tall row is sliced across pages, so it only needs room for one slice.
            val needed = if (tall) minSlice else row.height
            var fresh = cursor.isPageEmpty
            if (needed > cursor.remaining && !fresh) {
                continueOnNewPage(headerRows, body && repeatHeaders, table.gridLines)
                fresh = true
            }
            val next = IntArray(row.cells.size)
            // Each pass draws what fits. On a fresh page every unfinished cell draws at
            // least one line, so the loop always moves forward.
            while (!drawRowSlice(row, next, table.gridLines, force = fresh)) {
                continueOnNewPage(headerRows, body && repeatHeaders, table.gridLines)
                fresh = true
            }
        }
        cursor.gap(0.6f * m.bodyLine)
    }

    /** Starts a new page and repeats the header rows when [withHeaders] is true. */
    private fun continueOnNewPage(headerRows: List<RowBox>, withHeaders: Boolean, gridLines: Boolean) {
        cursor.newPage()
        if (!withHeaders) return
        for (header in headerRows) {
            drawRowSlice(header, IntArray(header.cells.size), gridLines, force = true)
        }
    }

    private fun columnWidths(given: List<Float>, colCount: Int): List<Float> {
        val usable = given.size == colCount && given.all { it > 0f && it.isFinite() }
        if (!usable) return List(colCount) { m.textWidth / colCount }
        val sum = given.sum()
        return given.map { it / sum * m.textWidth }
    }

    private fun measureRow(row: DocBlock.TableRow, widths: List<Float>, colCount: Int): RowBox {
        val cells = ArrayList<CellBox>(row.cells.size)
        var col = 0
        var left = m.margin
        for (cell in row.cells) {
            if (col >= colCount) break
            val span = cell.colSpan.coerceIn(1, colCount - col)
            val width = widths.subList(col, col + span).sum()
            val paint = textPaint(m.bodySize, bold = row.header)
            val layout = buildLayout(spannable(cell.runs), paint, width - 2 * CELL_PAD, cell.align)
            // Only the fill the model gives; header rows get no default grey.
            val fill = parseColour(cell.fill)
            cells += CellBox(layout, left, width, fill)
            left += width
            col += span
        }
        val height = (cells.maxOfOrNull { it.layout.height.toFloat() } ?: 0f) + 2 * CELL_PAD
        return RowBox(cells, height, row.header)
    }

    /**
     * Draws one slice of [row] on the current page, starting each cell at the line in [next].
     * Updates [next] to where each cell stopped. With [force], every unfinished cell draws at
     * least one line even if it does not fit. Returns true when every cell is finished.
     */
    private fun drawRowSlice(row: RowBox, next: IntArray, gridLines: Boolean, force: Boolean): Boolean {
        cursor.ensurePage()
        val textRoom = cursor.remaining - 2 * CELL_PAD
        val ends = IntArray(row.cells.size)
        var textHeight = 0f
        row.cells.forEachIndexed { c, cell ->
            val layout = cell.layout
            val start = next[c].coerceAtMost(layout.lineCount)
            var end = if (start < layout.lineCount) lastLineThatFits(layout, start, textRoom) else start
            if (end == start && start < layout.lineCount && force) end = start + 1
            ends[c] = end
            if (end > start) {
                val h = (layout.getLineBottom(end - 1) - layout.getLineTop(start)).toFloat()
                textHeight = max(textHeight, h)
            }
        }
        val finished = row.cells.indices.all { ends[it] >= row.cells[it].layout.lineCount }
        // Nothing fits here and nothing is forced: let the caller start a new page.
        if (!finished && textHeight <= 0f) return false

        val top = cursor.y
        val height = min(textHeight + 2 * CELL_PAD, cursor.remaining)
        val canvas = cursor.canvas
        row.cells.forEachIndexed { c, cell ->
            val right = cell.left + cell.width
            cell.fill?.let {
                fillPaint.color = it
                canvas.drawRect(cell.left, top, right, top + height, fillPaint)
            }
            val start = next[c].coerceAtMost(cell.layout.lineCount)
            val end = ends[c]
            if (end > start) {
                val lineTop = cell.layout.getLineTop(start).toFloat()
                val lineBottom = cell.layout.getLineBottom(end - 1).toFloat()
                val textTop = top + CELL_PAD
                val textBottom = min(textTop + (lineBottom - lineTop), top + height)
                canvas.save()
                // Clip to the lines of this slice so earlier and later lines stay hidden.
                canvas.clipRect(cell.left, textTop, right, textBottom)
                canvas.translate(cell.left + CELL_PAD, textTop - lineTop)
                cell.layout.draw(canvas)
                canvas.restore()
            }
            if (gridLines) canvas.drawRect(cell.left, top, right, top + height, gridPaint)
            next[c] = end
        }
        if (finished && !gridLines && row.header) {
            val y = top + height
            canvas.drawLine(m.margin, y, m.margin + m.textWidth, y, headerLinePaint)
        }
        cursor.advance(height)
        return finished
    }

    // ---- Text helpers -------------------------------------------------------

    /**
     * Draws a layout at [x], splitting it across pages by whole lines.
     * [keepWithNext] is extra height that must also fit for the block to stay on this page.
     * [onFirstLine] is called with the canvas and the top of the first line, for list markers.
     */
    private fun drawLayout(
        layout: StaticLayout,
        x: Float,
        keepWithNext: Float = 0f,
        onFirstLine: ((Canvas, Float) -> Unit)? = null,
    ) {
        cursor.flushGap()
        val lineCount = layout.lineCount
        if (lineCount == 0) return
        if (keepWithNext > 0f) cursor.ensureSpace(layout.height + keepWithNext)

        var start = 0
        while (start < lineCount) {
            var end = lastLineThatFits(layout, start, cursor.remaining)
            val leftOver = lineCount - end

            if (end == start) {
                // Nothing fits here. Try a fresh page; on an empty page force one line.
                if (!cursor.isPageEmpty) {
                    cursor.newPage()
                    continue
                }
                end = start + 1
            } else if (end < lineCount && !cursor.isPageEmpty) {
                if (end - start == 1 && start == 0) {
                    // Do not leave a single orphan line at the foot of the page.
                    cursor.newPage()
                    continue
                }
                if (leftOver == 1 && end - start >= 3) end-- // avoid a single widow line
            }

            val top = layout.getLineTop(start).toFloat()
            val bottom = layout.getLineBottom(end - 1).toFloat()
            val height = bottom - top
            val canvas = cursor.canvas
            canvas.save()
            canvas.clipRect(x - 2f, cursor.y, x + layout.width + 2f, cursor.y + height)
            canvas.translate(x, cursor.y - top)
            layout.draw(canvas)
            canvas.restore()
            if (start == 0) onFirstLine?.invoke(canvas, cursor.y)
            cursor.advance(height)

            start = end
            if (start < lineCount) cursor.newPage()
        }
    }

    /** Returns the exclusive end line so that lines [start, end) fit in [available] points. */
    private fun lastLineThatFits(layout: StaticLayout, start: Int, available: Float): Int {
        val top = layout.getLineTop(start)
        var end = start
        while (end < layout.lineCount && layout.getLineBottom(end) - top <= available + 0.01f) end++
        return end
    }

    private fun buildLayout(text: CharSequence, paint: TextPaint, width: Float, align: Align): StaticLayout {
        val w = width.toInt().coerceAtLeast(1)
        val builder = StaticLayout.Builder.obtain(text, 0, text.length, paint, w)
            .setAlignment(alignmentOf(align))
            .setLineSpacing(0f, m.lineSpacing)
            .setIncludePad(false)
            .setBreakStrategy(Layout.BREAK_STRATEGY_HIGH_QUALITY)
            .setHyphenationFrequency(Layout.HYPHENATION_FREQUENCY_NORMAL)
        if (align == Align.JUSTIFY && Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            builder.setJustificationMode(Layout.JUSTIFICATION_MODE_INTER_WORD)
        }
        return builder.build()
    }

    private fun alignmentOf(align: Align): Layout.Alignment = when (align) {
        Align.LEFT, Align.JUSTIFY -> Layout.Alignment.ALIGN_NORMAL
        Align.CENTRE -> Layout.Alignment.ALIGN_CENTER
        Align.RIGHT -> Layout.Alignment.ALIGN_OPPOSITE
    }

    private fun textPaint(size: Float, bold: Boolean = false, italic: Boolean = false): TextPaint {
        val style = when {
            bold && italic -> Typeface.BOLD_ITALIC
            bold -> Typeface.BOLD
            italic -> Typeface.ITALIC
            else -> Typeface.NORMAL
        }
        return TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            isAntiAlias = true
            typeface = Typeface.create(m.baseTypeface, style)
            textSize = size
            color = Color.BLACK
        }
    }

    /** Turns styled runs into a spannable string for StaticLayout. */
    private fun spannable(runs: List<TextRun>): SpannableStringBuilder {
        val sb = SpannableStringBuilder()
        for (run in runs) {
            if (run.text.isEmpty()) continue
            val start = sb.length
            sb.append(run.text)
            val end = sb.length
            fun span(what: Any) = sb.setSpan(what, start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)

            when {
                run.bold && run.italic -> span(StyleSpan(Typeface.BOLD_ITALIC))
                run.bold -> span(StyleSpan(Typeface.BOLD))
                run.italic -> span(StyleSpan(Typeface.ITALIC))
            }
            if (run.underline) span(UnderlineSpan())
            if (run.strike) span(StrikethroughSpan())
            if (run.superscript) {
                span(SuperscriptSpan())
                span(RelativeSizeSpan(0.7f))
            } else if (run.subscript) {
                span(SubscriptSpan())
                span(RelativeSizeSpan(0.7f))
            }
            val colour = parseColour(run.colour)
            if (colour != null) span(ForegroundColorSpan(colour))
            if (!run.link.isNullOrBlank()) {
                if (colour == null) span(ForegroundColorSpan(LINK_COLOUR))
                if (!run.underline) span(UnderlineSpan())
            }
        }
        return sb
    }

    /** Parses "#RRGGBB" (or "#AARRGGBB"). Returns null for anything it cannot read. */
    private fun parseColour(value: String?): Int? {
        val raw = value?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        val hex = if (raw.startsWith("#")) raw else "#$raw"
        return try {
            Color.parseColor(hex)
        } catch (e: IllegalArgumentException) {
            null
        }
    }
}
