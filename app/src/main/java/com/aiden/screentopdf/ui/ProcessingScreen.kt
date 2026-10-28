package com.aiden.screentopdf.ui

import android.graphics.Bitmap
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.aiden.screentopdf.R
import kotlin.math.roundToInt

@Composable
fun ProcessingScreen(
    videoUri: Uri,
    onDone: (String) -> Unit,
    onCancel: () -> Unit,
    viewModel: ProcessingViewModel = viewModel(),
) {
    val context = LocalContext.current
    val state by viewModel.state.collectAsState()

    LaunchedEffect(videoUri) {
        viewModel.start(context, videoUri)
    }

    LaunchedEffect(state.pdfPath) {
        val path = state.pdfPath
        if (path != null && viewModel.markDelivered()) {
            onDone(path)
        }
    }

    val cancelAndLeave = {
        viewModel.cancel()
        onCancel()
    }
    BackHandler(onBack = cancelAndLeave)

    Column(
        modifier = Modifier
            .fillMaxSize()
            .safeDrawingPadding()
            .padding(horizontal = 24.dp, vertical = 32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        val error = state.error
        if (error != null) {
            ErrorContent(
                message = error,
                onRetry = { viewModel.retry(context, videoUri) },
                onBack = cancelAndLeave,
            )
        } else {
            ProgressContent(
                progress = state.progress,
                stage = state.stage.label,
                status = state.status,
                thumbnails = state.thumbnails,
                onCancel = cancelAndLeave,
            )
        }
    }
}

@Composable
private fun ProgressContent(
    progress: Float,
    stage: String,
    status: String,
    thumbnails: List<Bitmap>,
    onCancel: () -> Unit,
) {
    Text(
        text = stringResource(R.string.processing_title),
        style = MaterialTheme.typography.headlineMedium,
        textAlign = TextAlign.Center,
    )
    Spacer(Modifier.height(12.dp))
    // The current big step, such as "Claude is reading the document".
    Text(
        text = stage,
        style = MaterialTheme.typography.titleLarge,
        color = MaterialTheme.colorScheme.primary,
        textAlign = TextAlign.Center,
    )
    Spacer(Modifier.height(24.dp))
    LinearProgressIndicator(
        progress = { progress.coerceIn(0f, 1f) },
        modifier = Modifier
            .fillMaxWidth()
            .height(8.dp)
            .clip(RoundedCornerShape(4.dp)),
    )
    Spacer(Modifier.height(12.dp))
    Text(
        text = "${(progress.coerceIn(0f, 1f) * 100).roundToInt()}%",
        style = MaterialTheme.typography.titleMedium,
    )
    Spacer(Modifier.height(8.dp))
    Text(
        text = status,
        style = MaterialTheme.typography.bodyLarge,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        textAlign = TextAlign.Center,
    )
    Spacer(Modifier.height(24.dp))

    if (thumbnails.isNotEmpty()) {
        val listState = rememberLazyListState()
        LaunchedEffect(thumbnails.size) {
            listState.animateScrollToItem(thumbnails.lastIndex)
        }
        LazyRow(
            state = listState,
            modifier = Modifier
                .fillMaxWidth()
                .height(160.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            contentPadding = PaddingValues(horizontal = 4.dp),
        ) {
            itemsIndexed(thumbnails) { _, bitmap ->
                val image = remember(bitmap) { bitmap.asImageBitmap() }
                Image(
                    bitmap = image,
                    contentDescription = stringResource(R.string.processing_page_preview),
                    contentScale = ContentScale.Fit,
                    modifier = Modifier
                        .height(160.dp)
                        .clip(RoundedCornerShape(6.dp))
                        .border(
                            width = 1.dp,
                            color = MaterialTheme.colorScheme.outlineVariant,
                            shape = RoundedCornerShape(6.dp),
                        ),
                )
            }
        }
        Spacer(Modifier.height(24.dp))
    }

    OutlinedButton(
        onClick = onCancel,
        modifier = Modifier
            .fillMaxWidth()
            .height(56.dp),
    ) {
        Text(stringResource(R.string.processing_cancel), style = MaterialTheme.typography.titleMedium)
    }
}

@Composable
private fun ErrorContent(
    message: String,
    onRetry: () -> Unit,
    onBack: () -> Unit,
) {
    Icon(
        imageVector = Icons.Default.ErrorOutline,
        contentDescription = null,
        tint = MaterialTheme.colorScheme.error,
        modifier = Modifier.size(56.dp),
    )
    Spacer(Modifier.height(16.dp))
    Text(
        text = stringResource(R.string.processing_error_title),
        style = MaterialTheme.typography.headlineSmall,
        textAlign = TextAlign.Center,
    )
    Spacer(Modifier.height(12.dp))
    Text(
        text = message,
        style = MaterialTheme.typography.bodyLarge,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        textAlign = TextAlign.Center,
    )
    Spacer(Modifier.height(32.dp))
    Button(
        onClick = onRetry,
        modifier = Modifier
            .fillMaxWidth()
            .height(56.dp),
    ) {
        Text(stringResource(R.string.processing_retry), style = MaterialTheme.typography.titleMedium)
    }
    Spacer(Modifier.height(12.dp))
    OutlinedButton(
        onClick = onBack,
        modifier = Modifier
            .fillMaxWidth()
            .height(56.dp),
    ) {
        Text(stringResource(R.string.processing_back), style = MaterialTheme.typography.titleMedium)
    }
}
