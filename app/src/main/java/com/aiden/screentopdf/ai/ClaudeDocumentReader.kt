package com.aiden.screentopdf.ai

import android.graphics.Bitmap
import android.util.Base64
import android.util.Log
import com.aiden.screentopdf.core.Align
import com.aiden.screentopdf.core.CapturedPage
import com.aiden.screentopdf.core.DocBlock
import com.aiden.screentopdf.core.DocumentReader
import com.aiden.screentopdf.core.PageSetup
import com.aiden.screentopdf.core.PaperSize
import com.aiden.screentopdf.core.ProgressListener
import com.aiden.screentopdf.core.RebuiltDocument
import com.aiden.screentopdf.core.TextRun
import com.anthropic.client.AnthropicClient
import com.anthropic.client.okhttp.AnthropicOkHttpClient
import com.anthropic.core.JsonValue
import com.anthropic.errors.AnthropicIoException
import com.anthropic.errors.AnthropicServiceException
import com.anthropic.errors.BadRequestException
import com.anthropic.errors.NotFoundException
import com.anthropic.errors.PermissionDeniedException
import com.anthropic.errors.RateLimitException
import com.anthropic.errors.UnauthorizedException
import com.anthropic.models.messages.Base64ImageSource
import com.anthropic.models.messages.CacheControlEphemeral
import com.anthropic.models.messages.ContentBlockParam
import com.anthropic.models.messages.ImageBlockParam
import com.anthropic.models.messages.Message
import com.anthropic.models.messages.MessageCreateParams
import com.anthropic.models.messages.OutputConfig
import com.anthropic.models.messages.StopReason
import com.anthropic.models.messages.TextBlockParam
import java.io.ByteArrayOutputStream
import java.time.Duration
import java.util.ArrayDeque
import java.util.Locale
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

/** Thrown when Claude cannot read the document. The message is written for the user. */
class DocumentReadException(message: String, cause: Throwable? = null) : Exception(message, cause)

/**
 * Sends the cleaned frames to Claude and turns the reply into a [RebuiltDocument].
 * Frames go in order, in batches of up to [BATCH_SIZE] images per request.
 */
class ClaudeDocumentReader : DocumentReader {

    override fun isReady(): Boolean = !runCatching { ApiKeyStore.getKey() }.getOrNull().isNullOrBlank()

    override suspend fun read(frames: List<CapturedPage>, onProgress: ProgressListener): RebuiltDocument =
        withContext(Dispatchers.IO) {
            val apiKey = ApiKeyStore.getKey()
            if (apiKey.isNullOrBlank()) {
                throw DocumentReadException("No API key is saved. Add your Anthropic API key in Settings.")
            }
            if (frames.isEmpty()) {
                throw DocumentReadException("There are no frames to read.")
            }
            val model = ApiKeyStore.getModel()

            val client: AnthropicClient = AnthropicOkHttpClient.builder()
                .apiKey(apiKey)
                .timeout(Duration.ofMinutes(15))
                .maxRetries(0)
                .build()
            try {
                readWithClient(client, model, frames, onProgress)
            } finally {
                try {
                    (client as? AutoCloseable)?.close()
                } catch (e: Exception) {
                    Log.w(TAG, "Could not close the Anthropic client", e)
                }
            }
        }

    // ---------------------------------------------------------------------
    // Batching
    // ---------------------------------------------------------------------

    private suspend fun readWithClient(
        client: AnthropicClient,
        model: String,
        frames: List<CapturedPage>,
        onProgress: ProgressListener,
    ): RebuiltDocument {
        val merged = ArrayList<DocBlock>()
        val warnings = ArrayList<String>()
        var title: String? = null
        var setup: PageSetup? = null
        var inputTokens = 0L
        var outputTokens = 0L

        // Work list of frame index ranges. A batch that hits max_tokens is split in two.
        val work = ArrayDeque<List<Int>>()
        frames.indices.chunked(BATCH_SIZE).forEach { work.addLast(it) }
        val plannedBatches = work.size
        var batchNumber = 0
        val retriedBatches = HashSet<List<Int>>()

        while (work.isNotEmpty()) {
            // Stop before the next paid request if the user has cancelled.
            currentCoroutineContext().ensureActive()
            val indices = work.removeFirst()
            batchNumber++
            val total = maxOf(plannedBatches, batchNumber + work.size)
            val startFraction = (batchNumber - 1).toFloat() / total
            onProgress(startFraction * PROGRESS_READ_SHARE, "Sending frames to Claude (batch $batchNumber of $total)")

            val contextText = if (merged.isEmpty()) null else plainText(merged).takeLast(CONTEXT_CHARS)
            val params = buildParams(model, frames, indices, contextText)
            val response = callWithRetries(client, model, params)

            inputTokens += response.usage().inputTokens()
            outputTokens += response.usage().outputTokens()

            val stop = response.stopReason().orElse(null)
            if (stop == StopReason.REFUSAL) {
                throw DocumentReadException("Claude declined to read this document.")
            }
            if (stop == StopReason.MAX_TOKENS && indices.size > 1) {
                // The reply was cut short. Try again with 2 smaller batches.
                val half = indices.size / 2
                work.addFirst(indices.drop(half))
                work.addFirst(indices.take(half))
                continue
            }

            onProgress((startFraction + 0.7f / total) * PROGRESS_READ_SHARE, "Reading the reply")
            val text = responseText(response)
            val json = try {
                JSONObject(extractJson(text))
            } catch (e: JSONException) {
                if (stop == StopReason.MAX_TOKENS) {
                    warnings.add("Frames ${indices.first()} to ${indices.last()} could not be read in full. Some content may be missing.")
                    continue
                }
                if (retriedBatches.add(indices)) {
                    // The reply was not valid JSON. Ask once more for the same frames.
                    Log.w(TAG, "Reply was not valid JSON, retrying batch", e)
                    work.addFirst(indices)
                    continue
                }
                throw DocumentReadException("Claude's reply could not be understood. Please try again.", e)
            }

            val part = parseDocument(json, frames.size)
            if (title == null) title = part.title
            if (setup == null) setup = part.setup
            warnings.addAll(part.warnings)
            appendWithoutDuplicates(merged, part.blocks)
            if (stop == StopReason.MAX_TOKENS) {
                warnings.add("Frame ${indices.first()} was long and the reply was cut short. Some content may be missing.")
            }
        }

        onProgress(PROGRESS_READ_SHARE, "Copying pictures from the frames")
        val withImages = cropImages(merged, frames, warnings)

        warnings.add(costLine(model, inputTokens, outputTokens))
        onProgress(1f, "Document read")

        return RebuiltDocument(
            title = title?.takeIf { it.isNotBlank() } ?: "Document",
            setup = setup ?: PageSetup(),
            blocks = withImages,
            warnings = warnings,
        )
    }

    private fun buildParams(
        model: String,
        frames: List<CapturedPage>,
        indices: List<Int>,
        contextText: String?,
    ): MessageCreateParams {
        val blocks = ArrayList<ContentBlockParam>()

        if (contextText != null) {
            blocks.add(
                textBlock(
                    "Here is the end of the text already transcribed from earlier frames of the same document:\n\n" +
                        "<already_transcribed>\n$contextText\n</already_transcribed>\n\n" +
                        "Continue the document from where this ends. Do not repeat any content that is already transcribed. " +
                        "The new frames overlap with the earlier ones, so skip the part you have already seen. " +
                        "If the text so far ends inside a table, continue it as a table block that holds only the rows " +
                        "not yet listed, with no header row. " +
                        "Give the same title and page setup as before.",
                ),
            )
        } else {
            blocks.add(textBlock("These are the first frames of the document. Start from the beginning."))
        }

        for (index in indices) {
            blocks.add(textBlock("Frame $index"))
            blocks.add(imageBlock(frames[index].bitmap))
        }

        blocks.add(
            textBlock(
                "Transcribe the document content shown in these ${indices.size} frames into the JSON structure. " +
                    "Use the frame numbers above for any image blocks.",
            ),
        )

        val builder = MessageCreateParams.builder()
            .model(model)
            .maxTokens(MAX_TOKENS)
            .systemOfTextBlockParams(
                listOf(
                    TextBlockParam.builder()
                        .text(SYSTEM_PROMPT)
                        .cacheControl(CacheControlEphemeral.builder().build())
                        .build(),
                ),
            )
            .addUserMessageOfBlockParams(blocks)
            .outputConfig(
                OutputConfig.builder()
                    .effort(OutputConfig.Effort.MEDIUM)
                    .build(),
            )

        if (model == ApiKeyStore.MODEL_SONNET) {
            // Server-side fallback when the safety classifier declines (Sonnet 5.5 on the Claude API only).
            builder
                .putAdditionalHeader("anthropic-beta", "server-side-fallback-2026-07-01")
                .putAdditionalBodyProperty("fallbacks", JsonValue.from("default"))
        }
        return builder.build()
    }

    private fun textBlock(text: String): ContentBlockParam =
        ContentBlockParam.ofText(TextBlockParam.builder().text(text).build())

    private fun imageBlock(bitmap: Bitmap): ContentBlockParam =
        ContentBlockParam.ofImage(
            ImageBlockParam.builder()
                .source(
                    Base64ImageSource.builder()
                        .data(encodeJpegBase64(bitmap))
                        .mediaType(Base64ImageSource.MediaType.IMAGE_JPEG)
                        .build(),
                )
                .build(),
        )

    /** Downscales so the long side is at most [MAX_IMAGE_SIDE] px, then JPEG at [JPEG_QUALITY], Base64 with no line breaks. */
    private fun encodeJpegBase64(source: Bitmap): String {
        val longSide = maxOf(source.width, source.height)
        val scaled = if (longSide > MAX_IMAGE_SIDE) {
            val scale = MAX_IMAGE_SIDE.toFloat() / longSide
            val w = maxOf(1, Math.round(source.width * scale))
            val h = maxOf(1, Math.round(source.height * scale))
            Bitmap.createScaledBitmap(source, w, h, true)
        } else {
            source
        }
        val out = ByteArrayOutputStream()
        scaled.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, out)
        if (scaled !== source) scaled.recycle()
        return Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP)
    }

    // ---------------------------------------------------------------------
    // Calling the API with retries
    // ---------------------------------------------------------------------

    /**
     * Calls the API with a short retry policy. Cancelling the coroutine interrupts the
     * blocking HTTP call and stops any retry, so no further paid requests are sent.
     */
    private suspend fun callWithRetries(client: AnthropicClient, model: String, params: MessageCreateParams): Message {
        var rateLimitRetried = false
        var serviceAttempts = 0
        while (true) {
            currentCoroutineContext().ensureActive()
            try {
                return runInterruptible(Dispatchers.IO) { client.messages().create(params) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: UnauthorizedException) {
                throw DocumentReadException("The API key is not valid. Check it in Settings.", e)
            } catch (e: BadRequestException) {
                throw DocumentReadException("Claude rejected the request: ${e.message ?: "bad request"}", e)
            } catch (e: NotFoundException) {
                throw DocumentReadException("The model $model is not available with this key. Pick another model in Settings.", e)
            } catch (e: PermissionDeniedException) {
                throw DocumentReadException(
                    "This API key is not allowed to use $model. Check your Anthropic account or pick another model in Settings.",
                    e,
                )
            } catch (e: RateLimitException) {
                if (rateLimitRetried) {
                    throw DocumentReadException("Claude is busy (rate limit). Wait a minute and try again.", e)
                }
                rateLimitRetried = true
                Log.w(TAG, "Rate limited, waiting before one retry", e)
                delay(RATE_LIMIT_WAIT_MS)
            } catch (e: AnthropicServiceException) {
                serviceAttempts++
                if (serviceAttempts > SERVICE_RETRIES) {
                    throw DocumentReadException("Claude's service had a problem. Please try again later.", e)
                }
                Log.w(TAG, "Service error, retry $serviceAttempts", e)
                delay(BACKOFF_MS * serviceAttempts)
            } catch (e: AnthropicIoException) {
                serviceAttempts++
                if (serviceAttempts > SERVICE_RETRIES) {
                    throw DocumentReadException("Could not reach Claude. Check your internet connection and try again.", e)
                }
                Log.w(TAG, "Network error, retry $serviceAttempts", e)
                delay(BACKOFF_MS * serviceAttempts)
            }
        }
    }

    private fun responseText(response: Message): String =
        response.content()
            .mapNotNull { block -> block.text().orElse(null) }
            .joinToString("") { it.text() }

    private fun costLine(model: String, inputTokens: Long, outputTokens: Long): String {
        val (inRate, outRate) = if (model == ApiKeyStore.MODEL_HAIKU) 0.10 to 0.50 else 2.0 to 10.0
        val cost = inputTokens / 1_000_000.0 * inRate + outputTokens / 1_000_000.0 * outRate
        return String.format(
            Locale.UK,
            "Estimated cost: ${'$'}%.3f (%,d input tokens, %,d output tokens, model %s).",
            cost, inputTokens, outputTokens, model,
        )
    }

    // ---------------------------------------------------------------------
    // Merging batches
    // ---------------------------------------------------------------------

    private fun plainText(blocks: List<DocBlock>): String {
        val sb = StringBuilder()
        for (block in blocks) {
            val line = blockText(block) ?: continue
            if (line.isNotBlank()) sb.append(line).append('\n')
        }
        return sb.toString()
    }

    /** Plain text of a block for comparing and for the continuation context. Null when the block has no text. */
    private fun blockText(block: DocBlock): String? = when (block) {
        is DocBlock.Heading -> runsText(block.runs)
        is DocBlock.Paragraph -> runsText(block.runs)
        is DocBlock.ListItem -> block.marker + " " + runsText(block.runs)
        is DocBlock.Table -> block.rows.joinToString("\n") { row -> row.cells.joinToString(" | ") { runsText(it.runs) } }
        is DocBlock.Image -> if (block.caption.isEmpty()) null else runsText(block.caption)
        DocBlock.Rule -> null
        DocBlock.PageBreak -> null
    }

    private fun runsText(runs: List<TextRun>): String = runs.joinToString("") { it.text }

    /** Adds the new blocks, dropping leading blocks that repeat the end of what is already merged. */
    private fun appendWithoutDuplicates(merged: MutableList<DocBlock>, incoming: List<DocBlock>) {
        if (merged.isEmpty()) {
            merged.addAll(incoming)
            return
        }
        val tail = merged.takeLast(DUPLICATE_WINDOW).mapNotNull { blockText(it)?.let(::normalise) }.filter { it.isNotEmpty() }
        var start = 0
        while (start < incoming.size) {
            val block = incoming[start]
            val text = blockText(block)?.let(::normalise)
            val isDuplicate = when {
                text == null -> block is DocBlock.PageBreak || block is DocBlock.Rule // a stray break at the join
                text.isEmpty() -> true
                else -> tail.any { similar(it, text) }
            }
            if (!isDuplicate) break
            start++
        }
        // Skip only leading text duplicates; a break or rule right at the join is dropped as well.
        merged.addAll(incoming.drop(start))
    }

    private fun normalise(s: String): String = s.lowercase(Locale.ROOT).replace(Regex("\\s+"), " ").trim()

    private fun similar(a: String, b: String): Boolean {
        if (a == b) return true
        // Short strings such as "Total" or "Yes" must match exactly, or real rows get dropped.
        if (a.length < 20 || b.length < 20) return false
        val shorter = if (a.length <= b.length) a else b
        val longer = if (a.length <= b.length) b else a
        if (shorter.length >= 20 && longer.contains(shorter)) return true
        if (a.length > 400 || b.length > 400) return false
        val allowed = maxOf(2, maxOf(a.length, b.length) / 10)
        return levenshtein(a, b) <= allowed
    }

    private fun levenshtein(a: String, b: String): Int {
        var prev = IntArray(b.length + 1) { it }
        var cur = IntArray(b.length + 1)
        for (i in 1..a.length) {
            cur[0] = i
            for (j in 1..b.length) {
                val cost = if (a[i - 1] == b[j - 1]) 0 else 1
                cur[j] = minOf(prev[j] + 1, cur[j - 1] + 1, prev[j - 1] + cost)
            }
            val t = prev; prev = cur; cur = t
        }
        return prev[b.length]
    }

    // ---------------------------------------------------------------------
    // JSON to DocModel
    // ---------------------------------------------------------------------

    private class ParsedPart(
        val title: String,
        val setup: PageSetup,
        val blocks: List<DocBlock>,
        val warnings: List<String>,
    )

    /**
     * Returns the JSON object in Claude's reply. It removes a Markdown code fence or a
     * sentence before or after the object, if there is one.
     */
    private fun extractJson(text: String): String {
        val start = text.indexOf('{')
        val end = text.lastIndexOf('}')
        if (start < 0 || end <= start) return text
        return text.substring(start, end + 1)
    }

    private fun parseDocument(json: JSONObject, frameCount: Int): ParsedPart {
        val title = json.optString("title", "").trim()
        val setup = parseSetup(json.optJSONObject("setup"))
        val blocks = ArrayList<DocBlock>()
        val blocksJson = json.optJSONArray("blocks") ?: JSONArray()
        for (i in 0 until blocksJson.length()) {
            val obj = blocksJson.optJSONObject(i) ?: continue
            parseBlock(obj, frameCount)?.let { blocks.add(it) }
        }
        val warnings = ArrayList<String>()
        val warnJson = json.optJSONArray("warnings") ?: JSONArray()
        for (i in 0 until warnJson.length()) {
            val w = warnJson.optString(i, "").trim()
            if (w.isNotEmpty()) warnings.add(w)
        }
        return ParsedPart(title, setup, blocks, warnings)
    }

    private fun parseSetup(obj: JSONObject?): PageSetup {
        if (obj == null) return PageSetup()
        val paper = when (obj.optString("paper", "A4").uppercase(Locale.ROOT)) {
            "LETTER" -> PaperSize.LETTER
            "SLIDE_16_9" -> PaperSize.SLIDE_16_9
            else -> PaperSize.A4
        }
        val family = when (obj.optString("fontFamily", "sans").lowercase(Locale.ROOT)) {
            "serif" -> "serif"
            "mono" -> "mono"
            else -> "sans"
        }
        return PageSetup(
            paper = paper,
            landscape = obj.optBoolean("landscape", paper == PaperSize.SLIDE_16_9),
            fontFamily = family,
            baseFontSizePt = obj.optDouble("baseFontSizePt", 11.0).toFloat().coerceIn(6f, 36f),
            marginPt = obj.optDouble("marginPt", 60.0).toFloat().coerceIn(18f, 144f),
            lineSpacing = obj.optDouble("lineSpacing", 1.15).toFloat().coerceIn(0.8f, 2.5f),
        )
    }

    private fun parseBlock(obj: JSONObject, frameCount: Int): DocBlock? {
        val runs = parseRuns(obj.optJSONArray("runs"))
        val align = parseAlign(obj.optString("align", "left"), Align.LEFT)
        return when (obj.optString("type", "")) {
            "heading" -> {
                if (runs.isEmpty()) return null
                DocBlock.Heading(level = obj.optInt("level", 1).coerceIn(1, 4), runs = runs, align = align)
            }
            "paragraph" -> {
                if (runs.isEmpty()) return null
                DocBlock.Paragraph(
                    runs = runs,
                    align = align,
                    sizeScale = obj.optDouble("sizeScale", 1.0).toFloat().coerceIn(0.5f, 3f),
                    indentLevel = obj.optInt("indentLevel", 0).coerceIn(0, 6),
                )
            }
            "list_item" -> {
                if (runs.isEmpty()) return null
                val ordered = obj.optBoolean("ordered", false)
                val marker = obj.optString("marker", "").trim().ifEmpty { if (ordered) "1." else "•" }
                DocBlock.ListItem(
                    runs = runs,
                    ordered = ordered,
                    level = obj.optInt("indentLevel", 0).coerceIn(0, 6),
                    marker = marker,
                )
            }
            "table" -> parseTable(obj)
            "image" -> parseImage(obj, frameCount, parseAlign(obj.optString("align", "centre"), Align.CENTRE))
            "rule" -> DocBlock.Rule
            "page_break" -> DocBlock.PageBreak
            else -> null
        }
    }

    private fun parseTable(obj: JSONObject): DocBlock? {
        val rowsJson = obj.optJSONArray("rows") ?: return null
        val rows = ArrayList<DocBlock.TableRow>()
        var maxCells = 0
        for (r in 0 until rowsJson.length()) {
            val rowObj = rowsJson.optJSONObject(r) ?: continue
            val cellsJson = rowObj.optJSONArray("cells") ?: continue
            val cells = ArrayList<DocBlock.TableCell>()
            for (c in 0 until cellsJson.length()) {
                val cellObj = cellsJson.optJSONObject(c) ?: continue
                cells.add(
                    DocBlock.TableCell(
                        runs = parseRuns(cellObj.optJSONArray("runs")),
                        align = parseAlign(cellObj.optString("align", "left"), Align.LEFT),
                        colSpan = cellObj.optInt("colSpan", 1).coerceIn(1, 20),
                        fill = parseColour(cellObj.optString("fill", "")),
                    ),
                )
            }
            if (cells.isEmpty()) continue
            maxCells = maxOf(maxCells, cells.sumOf { it.colSpan })
            rows.add(DocBlock.TableRow(cells = cells, header = rowObj.optBoolean("header", false)))
        }
        if (rows.isEmpty()) return null

        val widths = ArrayList<Float>()
        val widthsJson = obj.optJSONArray("columnWidths")
        if (widthsJson != null) {
            for (i in 0 until widthsJson.length()) {
                val w = widthsJson.optDouble(i, 0.0).toFloat()
                if (w > 0f) widths.add(w)
            }
        }
        val columnWidths = if (widths.size == maxCells) {
            val sum = widths.sum()
            widths.map { it / sum }
        } else {
            emptyList()
        }
        return DocBlock.Table(rows = rows, columnWidths = columnWidths, gridLines = obj.optBoolean("gridLines", true))
    }

    private fun parseImage(obj: JSONObject, frameCount: Int, align: Align): DocBlock? {
        val box = obj.optJSONObject("box") ?: return null
        val left = box.optDouble("left", 0.0).toFloat().coerceIn(0f, 1f)
        val top = box.optDouble("top", 0.0).toFloat().coerceIn(0f, 1f)
        val right = box.optDouble("right", 0.0).toFloat().coerceIn(0f, 1f)
        val bottom = box.optDouble("bottom", 0.0).toFloat().coerceIn(0f, 1f)
        if (right - left < 0.01f || bottom - top < 0.01f) return null
        val frame = obj.optInt("frame", -1)
        if (frame < 0 || frame >= frameCount) return null
        return DocBlock.Image(
            frameIndex = frame,
            left = left,
            top = top,
            right = right,
            bottom = bottom,
            widthFraction = obj.optDouble("widthFraction", 1.0).toFloat().coerceIn(0.1f, 1f),
            align = align,
            caption = parseRuns(obj.optJSONArray("caption")),
        )
    }

    private fun parseRuns(arr: JSONArray?): List<TextRun> {
        if (arr == null) return emptyList()
        val runs = ArrayList<TextRun>()
        for (i in 0 until arr.length()) {
            val r = arr.optJSONObject(i)
            if (r == null) {
                // Accept a plain string as an unstyled run.
                val plain = (arr.opt(i) as? String).orEmpty()
                if (plain.isNotEmpty()) runs.add(TextRun(text = plain))
                continue
            }
            val text = r.optString("text", "")
            if (text.isEmpty()) continue
            runs.add(
                TextRun(
                    text = text,
                    bold = r.optBoolean("bold", false),
                    italic = r.optBoolean("italic", false),
                    underline = r.optBoolean("underline", false),
                    strike = r.optBoolean("strike", false),
                    superscript = r.optBoolean("superscript", false),
                    subscript = r.optBoolean("subscript", false),
                    colour = parseColour(r.optString("colour", "")),
                    link = r.optString("link", "").trim().takeIf { it.isNotEmpty() && it != "null" },
                ),
            )
        }
        return runs
    }

    private fun parseAlign(value: String, default: Align): Align = when (value.lowercase(Locale.ROOT)) {
        "left" -> Align.LEFT
        "centre", "center" -> Align.CENTRE
        "right" -> Align.RIGHT
        "justify" -> Align.JUSTIFY
        else -> default
    }

    /** Accepts "#RRGGBB" only. Anything else (including black) becomes null, the normal colour. */
    private fun parseColour(value: String?): String? {
        if (value.isNullOrBlank() || value == "null") return null
        val v = value.trim().uppercase(Locale.ROOT)
        if (!COLOUR_REGEX.matches(v)) return null
        if (v == "#000000") return null
        return v
    }

    // ---------------------------------------------------------------------
    // Cropping pictures out of the frames
    // ---------------------------------------------------------------------

    private fun cropImages(blocks: List<DocBlock>, frames: List<CapturedPage>, warnings: MutableList<String>): List<DocBlock> {
        val out = ArrayList<DocBlock>(blocks.size)
        for (block in blocks) {
            if (block !is DocBlock.Image) {
                out.add(block)
                continue
            }
            val bitmap = try {
                cropFromFrame(frames[block.frameIndex].bitmap, block)
            } catch (e: Exception) {
                Log.w(TAG, "Could not crop a picture from frame ${block.frameIndex}", e)
                null
            }
            if (bitmap == null) {
                warnings.add("A picture on frame ${block.frameIndex} could not be copied and was left out.")
                continue
            }
            out.add(block.copy(bitmap = bitmap))
        }
        return out
    }

    private fun cropFromFrame(frame: Bitmap, block: DocBlock.Image): Bitmap? {
        val w = frame.width
        val h = frame.height
        var x0 = Math.round(block.left * w).coerceIn(0, w - 1)
        var y0 = Math.round(block.top * h).coerceIn(0, h - 1)
        var x1 = Math.round(block.right * w).coerceIn(0, w)
        var y1 = Math.round(block.bottom * h).coerceIn(0, h)
        if (x1 - x0 < MIN_CROP_PX) {
            x1 = minOf(w, x0 + MIN_CROP_PX)
            x0 = maxOf(0, x1 - MIN_CROP_PX)
        }
        if (y1 - y0 < MIN_CROP_PX) {
            y1 = minOf(h, y0 + MIN_CROP_PX)
            y0 = maxOf(0, y1 - MIN_CROP_PX)
        }
        val cw = x1 - x0
        val ch = y1 - y0
        if (cw <= 0 || ch <= 0) return null
        return Bitmap.createBitmap(frame, x0, y0, cw, ch)
    }

    companion object {
        private const val TAG = "ClaudeDocumentReader"
        private const val BATCH_SIZE = 6
        private const val MAX_TOKENS = 32000L
        private const val MAX_IMAGE_SIDE = 2000
        private const val JPEG_QUALITY = 80
        private const val CONTEXT_CHARS = 1500
        private const val DUPLICATE_WINDOW = 3
        private const val MIN_CROP_PX = 8
        private const val RATE_LIMIT_WAIT_MS = 20_000L
        private const val BACKOFF_MS = 3_000L
        private const val SERVICE_RETRIES = 2
        private const val PROGRESS_READ_SHARE = 0.95f
        private val COLOUR_REGEX = Regex("^#[0-9A-F]{6}$")

        private val SYSTEM_PROMPT = """
You rebuild documents from photos of a computer screen.

The images are frames from a phone video. The phone was pointed at a computer screen that was showing one document, and the document was scrolled or paged through while filming. Your task is to transcribe the document itself so that it can be typeset again, like for like.

What to transcribe
- Transcribe only the document's own content: its text, headings, lists, tables, pictures and page breaks.
- Ignore everything that belongs to the viewer app, the browser or the computer: window title bars, menus, toolbars, ribbons, tabs, sidebars, thumbnail panels, page-number boxes, zoom controls, scroll bars, the mouse cursor, the taskbar, the dock, notifications, browser chrome, address bars, adverts, cookie banners, comment panels and the desktop.
- Leave out running headers, footers and page numbers that repeat on every page of the document itself. Keep a header or footer that appears only once.
- Copy the text exactly, word for word. Keep the original spelling, numbers, punctuation and capitalisation. Do not summarise, paraphrase, correct, expand, shorten or translate anything.
- If a word cannot be read, give your best reading and add a short warning that says which word was unclear.

Structure
- Keep the structure of the original. Choose heading levels by visual size: level 1 for the title or the largest heading, down to level 4 for the smallest.
- For lists, copy the marker exactly as shown ("•", "–", "1.", "a)", "iv." and so on) and set indentLevel from the indent (0 for the outer level). Set ordered to true for numbered or lettered lists.
- For tables, give every row and every cell, in order, and mark header rows. Use colSpan when a cell spans columns. Give columnWidths as relative numbers when the columns are clearly unequal, otherwise give an empty list. Set gridLines to false when the table has no lines between cells.
- Mark bold, italic, underline, strikethrough, superscript and subscript on each run of text. Split a paragraph into runs only where the styling changes.
- Give a colour as "#RRGGBB" only when the text is clearly not black or dark grey. Otherwise set colour to null.
- Set align to "centre", "right" or "justify" only when it is clearly so. Otherwise use "left".
- Use a rule block for a horizontal line that is part of the document.
- Use a page_break block where the original document's page ends. In a PDF or word processor view this is the gap or shadow between two pages. Do not add a page break where the screen was just scrolled.
- For slides, set paper to "SLIDE_16_9" and landscape to true, and put a page_break after each slide.
- For web pages, set paper to "A4", landscape to false, and do not add page breaks.
- Estimate fontFamily ("serif", "sans" or "mono"), baseFontSizePt, marginPt and lineSpacing from the look of the original. Typical body text is 10 to 12 pt with margins of 50 to 72 pt.

Pictures
- Output an image block for real pictures, charts, logos, signatures and diagrams. Never output an image block for plain text, headings or tables.
- Choose the frame where the picture is most fully and sharply visible, give that frame number, and give a tight box around the picture as fractions of that frame's width and height (0.0 at the left or top edge, 1.0 at the right or bottom edge).
- Set widthFraction to the picture's width as a fraction of the text column width. Set align to "centre", "left" or "right" as in the original. Add a caption only if the original has one.

Overlap between frames
- The frames overlap heavily because the document was scrolled slowly. Each piece of content must appear once in the output. Take each piece from the frame where it is sharpest.
- If you are given text that was already transcribed from earlier frames, continue from where it ends and do not repeat it.

Reply format
- Reply with one JSON object and nothing else: no Markdown code fence and no text before or after it.
- The object has this shape:
  {"title": "...", "setup": {"paper": "A4" | "LETTER" | "SLIDE_16_9", "landscape": false, "fontFamily": "serif" | "sans" | "mono", "baseFontSizePt": 11, "marginPt": 60, "lineSpacing": 1.15}, "blocks": [ ... ], "warnings": ["..."]}
- A run of text is {"text": "...", "bold": false, "italic": false, "underline": false, "strike": false, "superscript": false, "subscript": false, "colour": null, "link": null}. You may leave out any field that is false or null.
- A table row is {"header": false, "cells": [ {"runs": [ ... ], "align": "left", "colSpan": 1, "fill": null} ]}.
- An image box is {"left": 0.1, "top": 0.2, "right": 0.9, "bottom": 0.6}.
- align is one of "left", "centre", "right", "justify".
- Each block has a "type" field and the fields of its kind:
  heading: type, level, align, runs.
  paragraph: type, align, sizeScale, indentLevel, runs.
  list_item: type, ordered, marker, indentLevel, runs.
  table: type, rows, columnWidths, gridLines.
  image: type, frame, box, widthFraction, align, caption.
  rule and page_break: type only.
- Use sizeScale 1 for normal body text. Use a larger or smaller number only when a paragraph is clearly a different size.
- Set title to the document's own title if it has one, or a short name taken from its first heading.
- Put short notes for the reader in warnings, such as unclear words or a page that was too blurred to read. Leave warnings empty when there is nothing to say.
        """.trimIndent()
    }
}
