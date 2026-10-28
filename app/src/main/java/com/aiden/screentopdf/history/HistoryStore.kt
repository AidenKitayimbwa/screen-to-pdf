package com.aiden.screentopdf.history

import android.content.Context
import android.util.Log
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.io.File
import java.io.IOException

/** One PDF the app made, with what the Claude requests used and cost. */
data class HistoryEntry(
    val id: String,
    val createdAtMillis: Long,
    val title: String,
    val pdfPath: String,
    val pageCount: Int?,
    val fileSizeBytes: Long,
    /** "rebuilt" when Claude rebuilt the document, "photo" for a photo copy. */
    val mode: String,
    val model: String?,
    val inputTokens: Long,
    val outputTokens: Long,
    val cacheReadTokens: Long,
    val cacheWriteTokens: Long,
    val requests: Int,
    val costUsd: Double,
    val notes: List<String>,
) {
    val isRebuilt: Boolean get() = mode == MODE_REBUILT

    /** All tokens sent to Claude, including cache reads and writes. */
    val totalInputTokens: Long get() = inputTokens + cacheReadTokens + cacheWriteTokens

    companion object {
        const val MODE_REBUILT = "rebuilt"
        const val MODE_PHOTO = "photo"
    }
}

/**
 * Keeps the list of PDFs the app has made, in a JSON file under the app's private files.
 * The PDFs themselves live in [pdfDir]. Call [init] once from the Application class.
 * All methods are safe to call from any thread.
 */
object HistoryStore {

    private const val TAG = "HistoryStore"
    private const val DIR_NAME = "history"
    private const val PDF_DIR_NAME = "pdfs"
    private const val FILE_NAME = "history.json"

    private val lock = Any()
    private var historyDir: File? = null
    private var entries: MutableList<HistoryEntry> = ArrayList()

    fun init(context: Context) {
        synchronized(lock) {
            if (historyDir != null) return
            val dir = File(context.applicationContext.filesDir, DIR_NAME)
            if (!dir.exists() && !dir.mkdirs()) {
                Log.w(TAG, "Could not create ${dir.absolutePath}")
            }
            historyDir = dir
            entries = load(File(dir, FILE_NAME))
        }
    }

    /** The folder where kept PDFs go. Created if needed. */
    val pdfDir: File
        get() {
            val dir = File(requireDir(), PDF_DIR_NAME)
            if (!dir.exists()) dir.mkdirs()
            return dir
        }

    /** All entries, newest first. */
    fun all(): List<HistoryEntry> = synchronized(lock) {
        entries.sortedByDescending { it.createdAtMillis }
    }

    fun get(id: String): HistoryEntry? = synchronized(lock) {
        entries.firstOrNull { it.id == id }
    }

    fun add(entry: HistoryEntry) {
        synchronized(lock) {
            entries.removeAll { it.id == entry.id }
            entries.add(entry)
            save()
        }
    }

    /** Removes the entry and deletes its PDF file. */
    fun delete(id: String) {
        synchronized(lock) {
            val entry = entries.firstOrNull { it.id == id } ?: return
            entries.remove(entry)
            save()
            val file = File(entry.pdfPath)
            if (file.exists() && !file.delete()) {
                Log.w(TAG, "Could not delete ${file.absolutePath}")
            }
        }
    }

    fun totalCostUsd(): Double = synchronized(lock) {
        entries.sumOf { it.costUsd }
    }

    private fun requireDir(): File =
        historyDir ?: error("HistoryStore.init(context) has not been called")

    /** Writes the list to a temp file first, then renames it, so a crash never leaves a half-written file. */
    private fun save() {
        val dir = try {
            requireDir()
        } catch (e: IllegalStateException) {
            Log.e(TAG, "Cannot save history", e)
            return
        }
        val target = File(dir, FILE_NAME)
        val temp = File(dir, "$FILE_NAME.tmp")
        try {
            val array = JSONArray()
            entries.forEach { array.put(toJson(it)) }
            temp.writeText(array.toString())
            if (!temp.renameTo(target)) {
                // Some file systems refuse to rename over an existing file.
                if (target.exists()) target.delete()
                if (!temp.renameTo(target)) throw IOException("Could not rename ${temp.name}")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Could not save history", e)
            temp.delete()
        }
    }

    /** Reads the file. A corrupt file is kept as a .bak copy and the list starts empty. */
    private fun load(file: File): MutableList<HistoryEntry> {
        if (!file.exists()) return ArrayList()
        return try {
            val array = JSONArray(file.readText())
            val list = ArrayList<HistoryEntry>(array.length())
            for (i in 0 until array.length()) {
                list += fromJson(array.getJSONObject(i))
            }
            list
        } catch (e: Exception) {
            Log.w(TAG, "History file is corrupt, starting empty", e)
            try {
                file.copyTo(File(file.parentFile, "$FILE_NAME.bak"), overwrite = true)
            } catch (copyError: Exception) {
                Log.w(TAG, "Could not keep a backup of the corrupt file", copyError)
            }
            ArrayList()
        }
    }

    private fun toJson(entry: HistoryEntry): JSONObject = JSONObject().apply {
        put("id", entry.id)
        put("createdAtMillis", entry.createdAtMillis)
        put("title", entry.title)
        put("pdfPath", entry.pdfPath)
        if (entry.pageCount != null) put("pageCount", entry.pageCount) else put("pageCount", JSONObject.NULL)
        put("fileSizeBytes", entry.fileSizeBytes)
        put("mode", entry.mode)
        if (entry.model != null) put("model", entry.model) else put("model", JSONObject.NULL)
        put("inputTokens", entry.inputTokens)
        put("outputTokens", entry.outputTokens)
        put("cacheReadTokens", entry.cacheReadTokens)
        put("cacheWriteTokens", entry.cacheWriteTokens)
        put("requests", entry.requests)
        put("costUsd", entry.costUsd)
        put("notes", JSONArray().apply { entry.notes.forEach { put(it) } })
    }

    @Throws(JSONException::class)
    private fun fromJson(json: JSONObject): HistoryEntry {
        val notesArray = json.optJSONArray("notes")
        val notes = ArrayList<String>()
        if (notesArray != null) {
            for (i in 0 until notesArray.length()) notes += notesArray.getString(i)
        }
        return HistoryEntry(
            id = json.getString("id"),
            createdAtMillis = json.getLong("createdAtMillis"),
            title = json.optString("title", ""),
            pdfPath = json.getString("pdfPath"),
            pageCount = if (json.isNull("pageCount")) null else json.getInt("pageCount"),
            fileSizeBytes = json.optLong("fileSizeBytes", 0L),
            mode = json.optString("mode", HistoryEntry.MODE_PHOTO),
            model = if (json.isNull("model")) null else json.optString("model", null),
            inputTokens = json.optLong("inputTokens", 0L),
            outputTokens = json.optLong("outputTokens", 0L),
            cacheReadTokens = json.optLong("cacheReadTokens", 0L),
            cacheWriteTokens = json.optLong("cacheWriteTokens", 0L),
            requests = json.optInt("requests", 0),
            costUsd = json.optDouble("costUsd", 0.0),
            notes = notes,
        )
    }
}
