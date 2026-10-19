package com.aiden.screentopdf.ui

import android.Manifest
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import android.util.Log
import android.view.ViewGroup
import androidx.camera.core.CameraSelector
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.video.FallbackStrategy
import androidx.camera.video.FileOutputOptions
import androidx.camera.video.Quality
import androidx.camera.video.QualitySelector
import androidx.camera.video.Recorder
import androidx.camera.video.Recording
import androidx.camera.video.VideoCapture
import androidx.camera.video.VideoRecordEvent
import androidx.camera.view.PreviewView
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.aiden.screentopdf.R
import com.google.accompanist.permissions.ExperimentalPermissionsApi
import com.google.accompanist.permissions.isGranted
import com.google.accompanist.permissions.rememberPermissionState
import com.google.accompanist.permissions.shouldShowRationale
import java.io.File
import java.util.Locale
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

private const val TAG = "CaptureScreen"
private val RecordRed = Color(0xFFE53935)

@OptIn(ExperimentalPermissionsApi::class)
@Composable
fun CaptureScreen(
    onRecorded: (Uri) -> Unit,
    onBack: () -> Unit,
) {
    // True once Android has answered at least one permission request.
    var askedOnce by remember { mutableStateOf(false) }
    val cameraPermission = rememberPermissionState(
        permission = Manifest.permission.CAMERA,
        onPermissionResult = { askedOnce = true },
    )

    LaunchedEffect(Unit) {
        if (!cameraPermission.status.isGranted) {
            cameraPermission.launchPermissionRequest()
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black),
    ) {
        if (cameraPermission.status.isGranted) {
            CameraRecorder(onRecorded = onRecorded)
        } else {
            PermissionRequest(
                // After a refusal with "don't ask again", Android shows no dialog,
                // so we point the user to the app settings instead.
                showSettings = askedOnce && !cameraPermission.status.shouldShowRationale,
                onGrant = { cameraPermission.launchPermissionRequest() },
            )
        }

        IconButton(
            onClick = onBack,
            modifier = Modifier
                .safeDrawingPadding()
                .padding(8.dp)
                .align(Alignment.TopStart),
            colors = IconButtonDefaults.iconButtonColors(
                containerColor = Color.Black.copy(alpha = 0.4f),
                contentColor = Color.White,
            ),
        ) {
            Icon(
                imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                contentDescription = stringResource(R.string.capture_back),
            )
        }
    }
}

@Composable
private fun PermissionRequest(
    showSettings: Boolean,
    onGrant: () -> Unit,
) {
    val context = LocalContext.current
    Column(
        modifier = Modifier
            .fillMaxSize()
            .safeDrawingPadding()
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(
            text = stringResource(
                if (showSettings) R.string.capture_permission_denied else R.string.capture_permission_needed,
            ),
            style = MaterialTheme.typography.titleMedium,
            color = Color.White,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(24.dp))
        if (showSettings) {
            Button(onClick = { openAppSettings(context) }) {
                Text(stringResource(R.string.capture_open_settings))
            }
        } else {
            Button(onClick = onGrant) {
                Text(stringResource(R.string.capture_grant))
            }
        }
    }
}

@Composable
private fun CameraRecorder(onRecorded: (Uri) -> Unit) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val currentOnRecorded by rememberUpdatedState(onRecorded)

    val previewView = remember {
        PreviewView(context).apply {
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
            )
            scaleType = PreviewView.ScaleType.FILL_CENTER
            implementationMode = PreviewView.ImplementationMode.COMPATIBLE
        }
    }
    val recorder = remember {
        Recorder.Builder()
            .setQualitySelector(
                QualitySelector.from(
                    Quality.FHD,
                    FallbackStrategy.lowerQualityOrHigherThan(Quality.HD),
                ),
            )
            .build()
    }
    val videoCapture = remember { VideoCapture.withOutput(recorder) }

    var recording by remember { mutableStateOf<Recording?>(null) }
    var isFinishing by remember { mutableStateOf(false) }
    var elapsedNanos by remember { mutableLongStateOf(0L) }
    var message by remember { mutableStateOf<String?>(null) }
    val disposed = remember { AtomicBoolean(false) }

    val cameraFailedText = stringResource(R.string.capture_camera_failed)
    val recordFailedText = stringResource(R.string.capture_record_failed)

    DisposableEffect(lifecycleOwner) {
        disposed.set(false)
        val mainExecutor = ContextCompat.getMainExecutor(context)
        val providerFuture = ProcessCameraProvider.getInstance(context)
        var provider: ProcessCameraProvider? = null

        providerFuture.addListener({
            if (disposed.get()) return@addListener
            try {
                val cameraProvider = providerFuture.get()
                provider = cameraProvider
                val preview = Preview.Builder().build().also {
                    it.setSurfaceProvider(previewView.surfaceProvider)
                }
                cameraProvider.unbindAll()
                cameraProvider.bindToLifecycle(
                    lifecycleOwner,
                    CameraSelector.DEFAULT_BACK_CAMERA,
                    preview,
                    videoCapture,
                )
            } catch (e: Exception) {
                Log.e(TAG, "Camera bind failed", e)
                message = cameraFailedText
            }
        }, mainExecutor)

        onDispose {
            disposed.set(true)
            recording?.stop()
            recording = null
            provider?.unbindAll()
        }
    }

    fun startRecording() {
        message = null
        elapsedNanos = 0L
        val file = File(context.cacheDir, "capture-${System.currentTimeMillis()}.mp4")
        val options = FileOutputOptions.Builder(file).build()
        // No withAudioEnabled(): the video has no sound, so no microphone permission is needed.
        recording = recorder
            .prepareRecording(context, options)
            .start(ContextCompat.getMainExecutor(context)) { event ->
                when (event) {
                    is VideoRecordEvent.Status -> {
                        elapsedNanos = event.recordingStats.recordedDurationNanos
                    }
                    is VideoRecordEvent.Finalize -> {
                        recording = null
                        isFinishing = false
                        if (disposed.get()) {
                            // The user left the screen while recording. Throw the clip away.
                            file.delete()
                        } else if (!event.hasError()) {
                            currentOnRecorded(Uri.fromFile(file))
                        } else {
                            Log.e(TAG, "Recording failed: ${event.error}", event.cause)
                            file.delete()
                            message = recordFailedText
                        }
                    }
                    else -> Unit
                }
            }
    }

    fun stopRecording() {
        val active = recording ?: return
        isFinishing = true
        active.stop()
    }

    val isRecording = recording != null

    Box(modifier = Modifier.fillMaxSize()) {
        AndroidView(
            factory = { previewView },
            modifier = Modifier.fillMaxSize(),
        )

        GuideOverlay(
            color = if (isRecording) RecordRed else Color.White,
            modifier = Modifier.fillMaxSize(),
        )

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .safeDrawingPadding()
                .padding(top = 16.dp, start = 64.dp, end = 64.dp)
                .align(Alignment.TopCenter),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            if (isRecording) {
                Text(
                    text = formatElapsed(elapsedNanos),
                    style = MaterialTheme.typography.titleLarge,
                    color = Color.White,
                    modifier = Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .background(RecordRed)
                        .padding(horizontal = 12.dp, vertical = 4.dp),
                )
            } else {
                Text(
                    text = message ?: stringResource(R.string.capture_hint),
                    style = MaterialTheme.typography.bodyLarge,
                    color = Color.White,
                    textAlign = TextAlign.Center,
                    modifier = Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .background(Color.Black.copy(alpha = 0.5f))
                        .padding(horizontal = 12.dp, vertical = 6.dp),
                )
            }
        }

        RecordButton(
            isRecording = isRecording,
            enabled = !isFinishing,
            onClick = { if (isRecording) stopRecording() else startRecording() },
            modifier = Modifier
                .safeDrawingPadding()
                .padding(bottom = 32.dp)
                .align(Alignment.BottomCenter),
        )
    }
}

@Composable
private fun GuideOverlay(color: Color, modifier: Modifier = Modifier) {
    Canvas(modifier = modifier) {
        val insetX = size.width * 0.08f
        val insetY = size.height * 0.16f
        val strokeWidth = 3.dp.toPx()
        drawRoundRect(
            color = color,
            topLeft = Offset(insetX, insetY),
            size = Size(size.width - insetX * 2, size.height - insetY * 2),
            cornerRadius = CornerRadius(12.dp.toPx()),
            style = Stroke(width = strokeWidth),
        )
    }
}

@Composable
private fun RecordButton(
    isRecording: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val label = stringResource(if (isRecording) R.string.capture_stop else R.string.capture_record)
    Box(
        modifier = modifier
            .size(84.dp)
            .clip(CircleShape)
            .border(width = 4.dp, color = Color.White, shape = CircleShape)
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .semantics { contentDescription = label },
        contentAlignment = Alignment.Center,
    ) {
        val innerColor = if (enabled) RecordRed else RecordRed.copy(alpha = 0.5f)
        if (isRecording) {
            Box(
                Modifier
                    .size(32.dp)
                    .clip(RoundedCornerShape(6.dp))
                    .background(innerColor),
            )
        } else {
            Box(
                Modifier
                    .size(64.dp)
                    .clip(CircleShape)
                    .background(innerColor),
            )
        }
    }
}

private fun formatElapsed(nanos: Long): String {
    val totalSeconds = TimeUnit.NANOSECONDS.toSeconds(nanos)
    return String.format(Locale.UK, "%02d:%02d", totalSeconds / 60, totalSeconds % 60)
}

private fun openAppSettings(context: Context) {
    val intent = Intent(
        Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
        Uri.fromParts("package", context.packageName, null),
    ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    context.startActivity(intent)
}
