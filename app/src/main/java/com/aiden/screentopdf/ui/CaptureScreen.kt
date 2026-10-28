package com.aiden.screentopdf.ui

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.res.Configuration
import android.net.Uri
import android.provider.Settings
import android.util.Log
import android.view.ViewGroup
import androidx.camera.core.AspectRatio
import androidx.camera.core.CameraSelector
import androidx.camera.core.Preview
import androidx.camera.core.resolutionselector.AspectRatioStrategy
import androidx.camera.core.resolutionselector.ResolutionSelector
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
import androidx.compose.foundation.gestures.detectDragGestures
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
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalConfiguration
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
import com.aiden.screentopdf.core.CaptureRegion
import com.aiden.screentopdf.core.Corners
import com.google.accompanist.permissions.ExperimentalPermissionsApi
import com.google.accompanist.permissions.isGranted
import com.google.accompanist.permissions.rememberPermissionState
import com.google.accompanist.permissions.shouldShowRationale
import java.io.File
import java.util.Locale
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.hypot
import kotlin.math.min

private const val TAG = "CaptureScreen"
private val RecordRed = Color(0xFFE53935)

/** Where the last document frame is remembered between uses. */
private const val CAPTURE_PREFS = "capture_prefs"
private const val CORNER_KEY_PREFIX = "corner_"

/** The frame starts 8% in from each edge of the camera image. */
private const val DEFAULT_INSET = 0.08f

/** The camera records at 16:9, so the preview is shown at 16:9 (or 9:16 when the phone is upright). */
private const val IMAGE_ASPECT = 16f / 9f

/** Corner handles: 28 dp visible, 48 dp touch target. */
private val HANDLE_RADIUS = 14.dp
private val HANDLE_TOUCH_RADIUS = 24.dp

/**
 * The 4 corners of the document frame, in order top-left, top-right, bottom-right, bottom-left.
 * Each value is a fraction (0.0 to 1.0) of the displayed camera image, not of the whole view.
 */
private typealias CornerFractions = List<Offset>

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
            // FIT_CENTER shows the whole camera image (with black bars if needed), so the
            // frame the user draws maps exactly onto the recorded video.
            scaleType = PreviewView.ScaleType.FIT_CENTER
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
            .setAspectRatio(AspectRatio.RATIO_16_9)
            .build()
    }
    val videoCapture = remember { VideoCapture.withOutput(recorder) }

    var recording by remember { mutableStateOf<Recording?>(null) }
    var isFinishing by remember { mutableStateOf(false) }
    var elapsedNanos by remember { mutableLongStateOf(0L) }
    var message by remember { mutableStateOf<String?>(null) }
    val disposed = remember { AtomicBoolean(false) }

    val prefs = remember { context.getSharedPreferences(CAPTURE_PREFS, Context.MODE_PRIVATE) }
    var corners by remember { mutableStateOf(loadCorners(prefs)) }

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
                // Same 16:9 shape as the recording, so what the user frames is what is saved.
                val preview = Preview.Builder()
                    .setResolutionSelector(
                        ResolutionSelector.Builder()
                            .setAspectRatioStrategy(AspectRatioStrategy.RATIO_16_9_FALLBACK_AUTO_STRATEGY)
                            .build(),
                    )
                    .build()
                    .also { it.setSurfaceProvider(previewView.surfaceProvider) }
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
        // Hand the frame to the cleaner. Order: top-left, top-right, bottom-right, bottom-left.
        val c = corners
        CaptureRegion.corners = Corners(
            topLeftX = c[0].x, topLeftY = c[0].y,
            topRightX = c[1].x, topRightY = c[1].y,
            bottomRightX = c[2].x, bottomRightY = c[2].y,
            bottomLeftX = c[3].x, bottomLeftY = c[3].y,
        )
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

        DocumentFrameOverlay(
            corners = corners,
            locked = isRecording,
            color = if (isRecording) RecordRed else Color.White,
            onCornersChanged = { corners = it },
            onEditFinished = { saveCorners(prefs, corners) },
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
                TextButton(
                    onClick = {
                        corners = defaultCorners()
                        saveCorners(prefs, corners)
                    },
                    colors = ButtonDefaults.textButtonColors(contentColor = Color.White),
                ) {
                    Text(stringResource(R.string.capture_reset))
                }
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

/**
 * The 4-corner document frame drawn over the camera preview.
 *
 * Before recording, the user drags a corner handle to move that corner, or drags inside the
 * frame to move the whole frame. While recording ([locked]), the outline stays but the handles
 * go and touch does nothing. The area outside the frame is dimmed.
 */
@Composable
private fun DocumentFrameOverlay(
    corners: CornerFractions,
    locked: Boolean,
    color: Color,
    onCornersChanged: (CornerFractions) -> Unit,
    onEditFinished: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val isPortrait = LocalConfiguration.current.orientation == Configuration.ORIENTATION_PORTRAIT
    val currentCorners by rememberUpdatedState(corners)
    val currentOnChanged by rememberUpdatedState(onCornersChanged)
    val currentOnFinished by rememberUpdatedState(onEditFinished)

    Canvas(
        modifier = modifier.pointerInput(locked, isPortrait) {
            if (locked) return@pointerInput
            val touchRadius = HANDLE_TOUCH_RADIUS.toPx()
            // -1 means no drag, 0 to 3 means one corner, 4 means the whole frame.
            var dragTarget = -1
            detectDragGestures(
                onDragStart = { position ->
                    val rect = imageRect(Size(size.width.toFloat(), size.height.toFloat()), isPortrait)
                    val points = toPixels(currentCorners, rect)
                    val nearest = points.indices.minByOrNull { distance(points[it], position) } ?: -1
                    dragTarget = when {
                        nearest >= 0 && distance(points[nearest], position) <= touchRadius -> nearest
                        isInsideQuad(points, position) -> 4
                        else -> -1
                    }
                },
                onDragEnd = {
                    if (dragTarget >= 0) currentOnFinished()
                    dragTarget = -1
                },
                onDragCancel = {
                    if (dragTarget >= 0) currentOnFinished()
                    dragTarget = -1
                },
                onDrag = { change, dragAmount ->
                    if (dragTarget < 0) return@detectDragGestures
                    change.consume()
                    val rect = imageRect(Size(size.width.toFloat(), size.height.toFloat()), isPortrait)
                    val points = toPixels(currentCorners, rect)
                    val moved = if (dragTarget == 4) {
                        moveAll(points, dragAmount, rect)
                    } else {
                        points.mapIndexed { index, point ->
                            if (index == dragTarget) clampToRect(point + dragAmount, rect) else point
                        }
                    }
                    currentOnChanged(toFractions(moved, rect))
                },
            )
        },
    ) {
        val rect = imageRect(size, isPortrait)
        val points = toPixels(corners, rect)

        val quad = Path().apply {
            moveTo(points[0].x, points[0].y)
            for (i in 1 until points.size) lineTo(points[i].x, points[i].y)
            close()
        }
        // Even-odd fill: the full view minus the frame, so only the outside is dimmed.
        val outside = Path().apply {
            fillType = PathFillType.EvenOdd
            addRect(Rect(Offset.Zero, size))
            addPath(quad)
        }
        drawPath(path = outside, color = Color.Black.copy(alpha = 0.45f))
        drawPath(path = quad, color = color, style = Stroke(width = 3.dp.toPx()))

        if (!locked) {
            val radius = HANDLE_RADIUS.toPx()
            for (point in points) {
                drawCircle(color = Color.White, radius = radius, center = point)
                drawCircle(color = color, radius = radius, center = point, style = Stroke(width = 2.dp.toPx()))
                drawCircle(color = Color.Black.copy(alpha = 0.6f), radius = radius * 0.3f, center = point)
            }
        }
    }
}

/**
 * The rectangle the camera image fills inside the view, with FIT_CENTER.
 * The image is 16:9, which is 9:16 when the phone is held upright. It is scaled to fit the
 * view and centred, so there may be black bars above and below or left and right.
 */
private fun imageRect(viewSize: Size, isPortrait: Boolean): Rect {
    if (viewSize.width <= 0f || viewSize.height <= 0f) return Rect(Offset.Zero, viewSize)
    val aspect = if (isPortrait) 1f / IMAGE_ASPECT else IMAGE_ASPECT // width divided by height
    var width = viewSize.width
    var height = width / aspect
    if (height > viewSize.height) {
        height = viewSize.height
        width = height * aspect
    }
    val left = (viewSize.width - width) / 2f
    val top = (viewSize.height - height) / 2f
    return Rect(left, top, left + width, top + height)
}

private fun toPixels(corners: CornerFractions, rect: Rect): List<Offset> =
    corners.map { Offset(rect.left + it.x * rect.width, rect.top + it.y * rect.height) }

private fun toFractions(points: List<Offset>, rect: Rect): CornerFractions {
    val w = if (rect.width > 0f) rect.width else 1f
    val h = if (rect.height > 0f) rect.height else 1f
    return points.map {
        Offset(
            ((it.x - rect.left) / w).coerceIn(0f, 1f),
            ((it.y - rect.top) / h).coerceIn(0f, 1f),
        )
    }
}

private fun clampToRect(point: Offset, rect: Rect): Offset =
    Offset(point.x.coerceIn(rect.left, rect.right), point.y.coerceIn(rect.top, rect.bottom))

/** Moves every corner by the same amount, shortened so no corner leaves the image. */
private fun moveAll(points: List<Offset>, delta: Offset, rect: Rect): List<Offset> {
    val minX = points.minOf { it.x }
    val maxX = points.maxOf { it.x }
    val minY = points.minOf { it.y }
    val maxY = points.maxOf { it.y }
    val dx = delta.x.coerceIn(rect.left - minX, rect.right - maxX)
    val dy = delta.y.coerceIn(rect.top - minY, rect.bottom - maxY)
    return points.map { Offset(it.x + dx, it.y + dy) }
}

private fun distance(a: Offset, b: Offset): Float = hypot(a.x - b.x, a.y - b.y)

/** Ray casting: count how many edges a line to the right of the point crosses. */
private fun isInsideQuad(points: List<Offset>, p: Offset): Boolean {
    var inside = false
    var j = points.size - 1
    for (i in points.indices) {
        val a = points[i]
        val b = points[j]
        val crosses = (a.y > p.y) != (b.y > p.y)
        if (crosses) {
            val xAtY = (b.x - a.x) * (p.y - a.y) / (b.y - a.y) + a.x
            if (p.x < xAtY) inside = !inside
        }
        j = i
    }
    return inside
}

private fun defaultCorners(): CornerFractions {
    val lo = DEFAULT_INSET
    val hi = 1f - DEFAULT_INSET
    return listOf(Offset(lo, lo), Offset(hi, lo), Offset(hi, hi), Offset(lo, hi))
}

private fun loadCorners(prefs: SharedPreferences): CornerFractions {
    val fallback = defaultCorners()
    val loaded = ArrayList<Offset>(4)
    for (i in 0 until 4) {
        val xKey = "${CORNER_KEY_PREFIX}${i}_x"
        val yKey = "${CORNER_KEY_PREFIX}${i}_y"
        if (!prefs.contains(xKey) || !prefs.contains(yKey)) return fallback
        val x = prefs.getFloat(xKey, fallback[i].x)
        val y = prefs.getFloat(yKey, fallback[i].y)
        if (x.isNaN() || y.isNaN()) return fallback
        loaded.add(Offset(x.coerceIn(0f, 1f), y.coerceIn(0f, 1f)))
    }
    return loaded
}

private fun saveCorners(prefs: SharedPreferences, corners: CornerFractions) {
    val editor = prefs.edit()
    for (i in 0 until min(corners.size, 4)) {
        editor.putFloat("${CORNER_KEY_PREFIX}${i}_x", corners[i].x)
        editor.putFloat("${CORNER_KEY_PREFIX}${i}_y", corners[i].y)
    }
    editor.apply()
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
