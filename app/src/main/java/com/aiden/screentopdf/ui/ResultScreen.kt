package com.aiden.screentopdf.ui

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.ParcelFileDescriptor
import android.provider.MediaStore
import android.util.Log
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.PictureAsPdf
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import com.aiden.screentopdf.R
import com.aiden.screentopdf.pdf.PdfMetadata
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException

private const val TAG = "ResultScreen"
private const val PDF_MIME = "application/pdf"

/** How many pages the in-app preview shows, and how wide each page image is. */
private const val PREVIEW_MAX_PAGES = 10
private const val PREVIEW_WIDTH_PX = 1080

/** Pages rendered for the preview, plus how many pages the file has in total. */
private class PdfPreview(val pages: List<Bitmap>, val pageCount: Int)

@Composable
fun ResultScreen(
    pdfPath: String,
    onStartAgain: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val file = remember(pdfPath) { File(pdfPath) }
    val notes = remember(pdfPath) { ResultNotes.lines }

    val readingText = stringResource(R.string.result_reading)
    val details by produceState(initialValue = readingText, file) {
        value = withContext(Dispatchers.IO) { PdfMetadata.describe(file) }
    }

    // Render the first pages off the main thread. Null means still loading.
    var previewFailed by remember(file) { mutableStateOf(false) }
    val preview by produceState<PdfPreview?>(initialValue = null, file) {
        value = withContext(Dispatchers.IO) {
            try {
                renderPreview(file)
            } catch (e: Exception) {
                Log.e(TAG, "Could not render the preview", e)
                previewFailed = true
                null
            }
        }
    }
    // Free the page images when the screen goes away.
    DisposableEffect(preview) {
        val current = preview
        onDispose {
            current?.pages?.forEach { if (!it.isRecycled) it.recycle() }
        }
    }

    var saving by remember { mutableStateOf(false) }

    fun doSave() {
        if (saving) return
        saving = true
        scope.launch {
            val ok = withContext(Dispatchers.IO) {
                try {
                    saveToDownloads(context, file)
                    true
                } catch (e: Exception) {
                    Log.e(TAG, "Save to Downloads failed", e)
                    false
                }
            }
            saving = false
            val text = if (ok) R.string.result_saved else R.string.result_save_failed
            Toast.makeText(context, text, Toast.LENGTH_SHORT).show()
        }
    }

    // Android 8 and 9 need the storage permission to write to Downloads.
    val storagePermission = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        if (granted) {
            doSave()
        } else {
            Toast.makeText(context, R.string.result_need_storage, Toast.LENGTH_LONG).show()
        }
    }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .safeDrawingPadding(),
        contentPadding = PaddingValues(horizontal = 24.dp, vertical = 32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        item(key = "header") {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Icon(
                    imageVector = Icons.Default.PictureAsPdf,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(72.dp),
                )
                Spacer(Modifier.height(16.dp))
                Text(
                    text = stringResource(R.string.result_title),
                    style = MaterialTheme.typography.headlineMedium,
                    textAlign = TextAlign.Center,
                )
                Spacer(Modifier.height(12.dp))
                Text(
                    text = file.name,
                    style = MaterialTheme.typography.titleMedium,
                    textAlign = TextAlign.Center,
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    text = details,
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                )
                Spacer(Modifier.height(24.dp))
            }
        }

        if (notes.isNotEmpty()) {
            item(key = "notes") {
                NotesCard(notes)
                Spacer(Modifier.height(24.dp))
            }
        }

        item(key = "actions") {
            Column {
                ActionButton(
                    label = stringResource(R.string.result_share),
                    icon = Icons.Default.Share,
                    primary = true,
                    onClick = { sharePdf(context, file) },
                )
                Spacer(Modifier.height(12.dp))
                ActionButton(
                    label = stringResource(R.string.result_open),
                    icon = Icons.AutoMirrored.Filled.OpenInNew,
                    onClick = { openPdf(context, file) },
                )
                Spacer(Modifier.height(12.dp))
                ActionButton(
                    label = stringResource(R.string.result_save),
                    icon = Icons.Default.Download,
                    enabled = !saving,
                    onClick = {
                        val needsPermission = Build.VERSION.SDK_INT < Build.VERSION_CODES.Q &&
                            ContextCompat.checkSelfPermission(
                                context,
                                Manifest.permission.WRITE_EXTERNAL_STORAGE,
                            ) != PackageManager.PERMISSION_GRANTED
                        if (needsPermission) {
                            storagePermission.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
                        } else {
                            doSave()
                        }
                    },
                )
                Spacer(Modifier.height(12.dp))
                TextButton(
                    onClick = onStartAgain,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Icon(Icons.Default.Refresh, contentDescription = null)
                    Spacer(Modifier.size(8.dp))
                    Text(stringResource(R.string.result_again), style = MaterialTheme.typography.titleMedium)
                }
                Spacer(Modifier.height(24.dp))
            }
        }

        item(key = "preview-title") {
            Column(
                modifier = Modifier.fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(
                    text = stringResource(R.string.result_preview),
                    style = MaterialTheme.typography.titleLarge,
                )
                Spacer(Modifier.height(12.dp))
                val current = preview
                when {
                    previewFailed -> Text(
                        text = stringResource(R.string.result_preview_failed),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                    )
                    current == null -> {
                        CircularProgressIndicator()
                        Spacer(Modifier.height(8.dp))
                        Text(
                            text = stringResource(R.string.result_preview_loading),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    current.pageCount > current.pages.size -> Text(
                        text = stringResource(R.string.result_preview_more, current.pages.size),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                    )
                }
                Spacer(Modifier.height(12.dp))
            }
        }

        val pages = preview?.pages.orEmpty()
        itemsIndexed(pages, key = { index, _ -> "page-$index" }) { index, bitmap ->
            Column(modifier = Modifier.fillMaxWidth()) {
                Text(
                    text = stringResource(R.string.result_preview_page, index + 1),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(6.dp))
                if (!bitmap.isRecycled) {
                    val image = remember(bitmap) { bitmap.asImageBitmap() }
                    Image(
                        bitmap = image,
                        contentDescription = stringResource(R.string.result_preview_page, index + 1),
                        contentScale = ContentScale.FillWidth,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(6.dp))
                            .border(
                                width = 1.dp,
                                color = MaterialTheme.colorScheme.outlineVariant,
                                shape = RoundedCornerShape(6.dp),
                            ),
                    )
                }
                Spacer(Modifier.height(16.dp))
            }
        }
    }
}

@Composable
private fun NotesCard(notes: List<String>) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.secondaryContainer,
            contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
        ),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = Icons.Default.Info,
                    contentDescription = null,
                    modifier = Modifier.size(20.dp),
                )
                Spacer(Modifier.size(8.dp))
                Text(
                    text = stringResource(R.string.result_notes),
                    style = MaterialTheme.typography.titleMedium,
                )
            }
            Spacer(Modifier.height(8.dp))
            notes.forEach { line ->
                Text(
                    text = line,
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(vertical = 2.dp),
                )
            }
        }
    }
}

@Composable
private fun ActionButton(
    label: String,
    icon: ImageVector,
    onClick: () -> Unit,
    primary: Boolean = false,
    enabled: Boolean = true,
) {
    val modifier = Modifier
        .fillMaxWidth()
        .height(56.dp)
    val content: @Composable () -> Unit = {
        Icon(icon, contentDescription = null)
        Spacer(Modifier.size(12.dp))
        Text(label, style = MaterialTheme.typography.titleMedium)
    }
    if (primary) {
        Button(onClick = onClick, enabled = enabled, modifier = modifier) { content() }
    } else {
        OutlinedButton(onClick = onClick, enabled = enabled, modifier = modifier) { content() }
    }
}

/**
 * Draws the first pages of the PDF as images on a white background.
 * Call off the main thread. Throws if the file cannot be opened.
 */
private fun renderPreview(file: File): PdfPreview {
    val descriptor = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
    descriptor.use { fd ->
        val renderer = PdfRenderer(fd)
        renderer.use { pdf ->
            val count = pdf.pageCount
            val shown = minOf(count, PREVIEW_MAX_PAGES)
            val pages = ArrayList<Bitmap>(shown)
            for (index in 0 until shown) {
                pdf.openPage(index).use { page ->
                    val width = PREVIEW_WIDTH_PX
                    val height = (page.height.toFloat() / page.width * width).toInt().coerceAtLeast(1)
                    val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
                    Canvas(bitmap).drawColor(Color.WHITE)
                    page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                    pages += bitmap
                }
            }
            return PdfPreview(pages, count)
        }
    }
}

private fun pdfUri(context: Context, file: File): Uri =
    FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)

private fun sharePdf(context: Context, file: File) {
    try {
        val uri = pdfUri(context, file)
        val send = Intent(Intent.ACTION_SEND).apply {
            type = PDF_MIME
            putExtra(Intent.EXTRA_STREAM, uri)
            // ClipData makes sure the read grant reaches the app chosen in the chooser.
            clipData = ClipData.newRawUri(file.name, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(
            Intent.createChooser(send, context.getString(R.string.result_share_title)),
        )
    } catch (e: ActivityNotFoundException) {
        Toast.makeText(context, R.string.result_no_share, Toast.LENGTH_SHORT).show()
    } catch (e: IllegalArgumentException) {
        Log.e(TAG, "File is outside the FileProvider paths", e)
        Toast.makeText(context, R.string.result_no_share, Toast.LENGTH_SHORT).show()
    }
}

private fun openPdf(context: Context, file: File) {
    try {
        val uri = pdfUri(context, file)
        val view = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, PDF_MIME)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(view)
    } catch (e: ActivityNotFoundException) {
        Toast.makeText(context, R.string.result_no_viewer, Toast.LENGTH_SHORT).show()
    } catch (e: IllegalArgumentException) {
        Log.e(TAG, "File is outside the FileProvider paths", e)
        Toast.makeText(context, R.string.result_no_viewer, Toast.LENGTH_SHORT).show()
    }
}

/** Copies the PDF into the public Downloads folder. Throws on failure. Call off the main thread. */
private fun saveToDownloads(context: Context, file: File) {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
        val resolver = context.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, file.name)
            put(MediaStore.MediaColumns.MIME_TYPE, PDF_MIME)
            put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        val target = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
            ?: throw IOException("Could not create the file in Downloads")
        try {
            val output = resolver.openOutputStream(target)
                ?: throw IOException("Could not open the file in Downloads")
            output.use { out ->
                file.inputStream().use { input -> input.copyTo(out) }
            }
            val done = ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }
            resolver.update(target, done, null, null)
        } catch (e: Exception) {
            resolver.delete(target, null, null)
            throw e
        }
    } else {
        @Suppress("DEPRECATION")
        val downloads = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
        if (!downloads.exists() && !downloads.mkdirs()) {
            throw IOException("Could not create the Downloads folder")
        }
        val target = File(downloads, file.name)
        file.copyTo(target, overwrite = true)
        // Let the phone's file apps see the new file straight away.
        MediaScannerConnection.scanFile(context, arrayOf(target.absolutePath), arrayOf(PDF_MIME), null)
    }
}
