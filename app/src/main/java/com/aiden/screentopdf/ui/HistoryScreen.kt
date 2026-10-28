package com.aiden.screentopdf.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.aiden.screentopdf.R
import com.aiden.screentopdf.history.HistoryEntry
import com.aiden.screentopdf.history.HistoryStore
import com.aiden.screentopdf.pdf.PdfMetadata
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Shows every PDF the app has made, newest first, with what each one cost. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HistoryScreen(
    onOpen: (HistoryEntry) -> Unit,
    onBack: () -> Unit,
) {
    var entries by remember { mutableStateOf(HistoryStore.all()) }
    var pendingDelete by remember { mutableStateOf<HistoryEntry?>(null) }
    val scope = rememberCoroutineScope()

    val toDelete = pendingDelete
    if (toDelete != null) {
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text(stringResource(R.string.history_delete_title)) },
            text = { Text(stringResource(R.string.history_delete_body)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        pendingDelete = null
                        // Rewrite the history file and delete the PDF off the main thread.
                        scope.launch {
                            entries = withContext(Dispatchers.IO) {
                                HistoryStore.delete(toDelete.id)
                                HistoryStore.all()
                            }
                        }
                    },
                ) {
                    Text(stringResource(R.string.history_delete))
                }
            },
            dismissButton = {
                TextButton(onClick = { pendingDelete = null }) {
                    Text(stringResource(R.string.history_cancel))
                }
            },
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.history_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.history_back),
                        )
                    }
                },
            )
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 16.dp),
        ) {
            item(key = "summary") {
                SummaryCard(entries)
                Spacer(Modifier.height(16.dp))
            }

            if (entries.isEmpty()) {
                item(key = "empty") {
                    Text(
                        text = stringResource(R.string.history_empty),
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 32.dp),
                    )
                }
            } else {
                items(entries, key = { it.id }) { entry ->
                    HistoryRow(
                        entry = entry,
                        onOpen = { onOpen(entry) },
                        onDelete = { pendingDelete = entry },
                    )
                    HorizontalDivider()
                }
            }

            item(key = "note") {
                Spacer(Modifier.height(24.dp))
                Text(
                    text = stringResource(R.string.history_estimate_note),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
    }
}

@Composable
private fun SummaryCard(entries: List<HistoryEntry>) {
    val totalCost = entries.sumOf { it.costUsd }
    val totalTokens = entries.sumOf { it.totalInputTokens + it.outputTokens }
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.primaryContainer,
            contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
        ),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            SummaryItem(
                label = stringResource(R.string.history_summary_pdfs),
                value = entries.size.toString(),
                modifier = Modifier.weight(1f),
            )
            SummaryItem(
                label = stringResource(R.string.history_summary_spent),
                value = formatTotalCost(totalCost),
                modifier = Modifier.weight(1.4f),
            )
            SummaryItem(
                label = stringResource(R.string.history_summary_tokens),
                value = formatCount(totalTokens),
                modifier = Modifier.weight(1f),
            )
        }
    }
}

@Composable
private fun SummaryItem(label: String, value: String, modifier: Modifier = Modifier) {
    Column(modifier = modifier) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
        )
        Spacer(Modifier.height(4.dp))
        Text(
            text = value,
            style = MaterialTheme.typography.titleMedium,
        )
    }
}

@Composable
private fun HistoryRow(
    entry: HistoryEntry,
    onOpen: () -> Unit,
    onDelete: () -> Unit,
) {
    val missing = remember(entry.pdfPath) { !File(entry.pdfPath).exists() }
    val textColour = if (missing) {
        MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
    } else {
        MaterialTheme.colorScheme.onSurface
    }
    val subColour = if (missing) {
        MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.38f)
    } else {
        MaterialTheme.colorScheme.onSurfaceVariant
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .then(if (missing) Modifier else Modifier.clickable(onClick = onOpen))
            .padding(vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = entry.title,
                style = MaterialTheme.typography.titleMedium,
                color = textColour,
            )
            Spacer(Modifier.height(2.dp))
            Text(
                text = formatDateTime(entry.createdAtMillis),
                style = MaterialTheme.typography.bodyMedium,
                color = subColour,
            )
            Spacer(Modifier.height(2.dp))
            Text(
                text = describeEntry(entry, missing),
                style = MaterialTheme.typography.bodyMedium,
                color = subColour,
            )
            if (entry.isRebuilt || entry.requests > 0) {
                Spacer(Modifier.height(2.dp))
                Text(
                    text = tokenLine(entry),
                    style = MaterialTheme.typography.bodySmall,
                    color = subColour,
                )
            }
        }
        Spacer(Modifier.width(12.dp))
        Column(horizontalAlignment = Alignment.End) {
            Text(
                text = formatRowCost(entry.costUsd),
                style = MaterialTheme.typography.titleMedium,
                color = textColour,
            )
            IconButton(onClick = onDelete) {
                Icon(
                    imageVector = Icons.Default.Delete,
                    contentDescription = stringResource(R.string.history_delete),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/** For example "Rebuilt by Claude · 7 pages · 1.2 MB", or "File missing" first when the PDF is gone. */
@Composable
private fun describeEntry(entry: HistoryEntry, missing: Boolean): String {
    val parts = ArrayList<String>()
    if (missing) parts += stringResource(R.string.history_file_missing)
    parts += stringResource(if (entry.isRebuilt) R.string.history_rebuilt else R.string.history_photo)
    when (entry.pageCount) {
        null -> Unit
        1 -> parts += "1 page"
        else -> parts += "${entry.pageCount} pages"
    }
    if (entry.fileSizeBytes > 0) parts += PdfMetadata.sizeText(entry.fileSizeBytes)
    return parts.joinToString(" · ")
}

/** For example "41,230 in · 3,905 out · Sonnet 5.5". */
private fun tokenLine(entry: HistoryEntry): String {
    val parts = ArrayList<String>()
    parts += formatCount(entry.totalInputTokens) + " in"
    parts += formatCount(entry.outputTokens) + " out"
    friendlyModelName(entry.model)?.let { parts += it }
    return parts.joinToString(" · ")
}

/** Turns a model id such as "claude-sonnet-5-5" into "Sonnet 5.5". */
internal fun friendlyModelName(model: String?): String? {
    if (model.isNullOrBlank()) return null
    val match = Regex("^claude-([a-z]+)-(\\d+)-(\\d+)").find(model)
        ?: return model
    val family = match.groupValues[1].replaceFirstChar { it.uppercase() }
    return "$family ${match.groupValues[2]}.${match.groupValues[3]}"
}

/** Two decimals for the total, with a special case for tiny amounts. */
@Composable
private fun formatTotalCost(usd: Double): String =
    if (usd > 0.0 && usd < 0.01) {
        stringResource(R.string.history_less_than_cent)
    } else {
        String.format(Locale.UK, "US$%.2f", usd)
    }

/** Three decimals for one row, which matches the note on the result screen. */
private fun formatRowCost(usd: Double): String = String.format(Locale.UK, "US$%.3f", usd)

private fun formatCount(value: Long): String = String.format(Locale.UK, "%,d", value)

/** For example "28 Oct 2026, 3:45pm". */
private fun formatDateTime(millis: Long): String {
    val date = Date(millis)
    val day = SimpleDateFormat("d MMM yyyy", Locale.UK).format(date)
    val time = SimpleDateFormat("h:mma", Locale.UK).format(date).lowercase(Locale.UK)
    return "$day, $time"
}
