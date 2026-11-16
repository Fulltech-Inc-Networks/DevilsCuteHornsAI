package com.fulltech.devilscutehornsai

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private val CameraBlack = Color(0xFF090909)
private val CameraRed = Color(0xFFDC143C)

@Composable
fun CameraScreen(
    sessionId: String,
    repository: SessionRepository,
    onBack: () -> Unit,
    onFinished: (String) -> Unit
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val lifecycleOwner = context as LifecycleOwner

    var imageCapture by remember { mutableStateOf<ImageCapture?>(null) }
    var capturedFile by remember { mutableStateOf<File?>(null) }
    var pageCount by remember { mutableIntStateOf(
        repository.getSession(sessionId)?.pageCount ?: 0
    ) }
    val scope = rememberCoroutineScope()
    var cameraError by remember { mutableStateOf<String?>(null) }
    var flashAvailable by remember { mutableStateOf(false) }
    var flashEnabled by remember { mutableStateOf(false) }
    var frontCameraAvailable by remember { mutableStateOf(false) }
    var useFrontCamera by remember { mutableStateOf(false) }
    var capturing by remember { mutableStateOf(false) }

    val previewView = remember {
        PreviewView(context).apply {
            scaleType = PreviewView.ScaleType.FILL_CENTER
            implementationMode = PreviewView.ImplementationMode.COMPATIBLE
        }
    }

    DisposableEffect(previewView, lifecycleOwner, useFrontCamera) {
        var disposed = false
        var provider: ProcessCameraProvider? = null

        val future = ProcessCameraProvider.getInstance(context)

        future.addListener({
            if (disposed) return@addListener

            try {
                val cameraProvider = future.get()
                provider = cameraProvider

                frontCameraAvailable = cameraProvider.hasCamera(
                    CameraSelector.DEFAULT_FRONT_CAMERA
                )

                val selector = if (useFrontCamera) {
                    CameraSelector.DEFAULT_FRONT_CAMERA
                } else {
                    CameraSelector.DEFAULT_BACK_CAMERA
                }

                if (!cameraProvider.hasCamera(selector)) {
                    throw IllegalStateException("The selected camera is unavailable.")
                }

                cameraProvider.unbindAll()

                val preview = Preview.Builder().build().also {
                    it.surfaceProvider = previewView.surfaceProvider
                }

                val capture = ImageCapture.Builder()
                    .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
                    .setJpegQuality(95)
                    .build()

                val camera = cameraProvider.bindToLifecycle(
                    lifecycleOwner,
                    selector,
                    preview,
                    capture
                )

                imageCapture = capture
                flashEnabled = false
                flashAvailable = camera.cameraInfo.hasFlashUnit()
            } catch (error: Exception) {
                imageCapture = null
                flashAvailable = false
                flashEnabled = false
                cameraError = error.localizedMessage
                    ?: "Unable to start the selected camera."
            }
        }, ContextCompat.getMainExecutor(context))

        onDispose {
            disposed = true
            provider?.unbindAll()
        }
    }

    val photo = capturedFile

    if (photo != null) {
        PhotoReviewScreen(
            file = photo,
            pageNumber = pageCount,
            onRetake = {
                scope.launch {
                    val removed = withContext(Dispatchers.IO) {
                        repository.removeLastPage(sessionId)
                    }

                    if (removed) {
                        pageCount = (pageCount - 1).coerceAtLeast(0)
                        capturedFile = null
                    } else {
                        cameraError = "Unable to remove the captured page."
                    }
                }
            },
            onAddPage = {
                capturedFile = null
            },
            onFinish = {
                onFinished(sessionId)
            }
        )
        return
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(CameraBlack)
    ) {
        AndroidView(
            factory = { previewView },
            modifier = Modifier.fillMaxSize()
        )

        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(20.dp),
            verticalArrangement = Arrangement.SpaceBetween
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                TextButton(
                    onClick = onBack
                ) {
                    Text("CLOSE", color = Color.White)
                }

                Text(
                    text = "DCH CAMERA",
                    color = Color.White,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 2.sp
                )

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    TextButton(
                        onClick = { useFrontCamera = !useFrontCamera },
                        enabled = frontCameraAvailable && !capturing
                    ) {
                        Text(
                            "FLIP",
                            color = if (frontCameraAvailable) Color.White else Color.Gray
                        )
                    }

                    TextButton(
                        onClick = {
                            if (flashAvailable) {
                                flashEnabled = !flashEnabled
                                imageCapture?.flashMode =
                                    if (flashEnabled) {
                                        ImageCapture.FLASH_MODE_ON
                                    } else {
                                        ImageCapture.FLASH_MODE_OFF
                                    }
                            }
                        },
                        enabled = flashAvailable && !capturing
                    ) {
                        Text(
                            if (flashEnabled) "FLASH ON" else "FLASH",
                            color = if (flashEnabled) CameraRed else Color.White
                        )
                    }
                }
            }

            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier.fillMaxWidth()
            ) {
                if (cameraError != null) {
                    Text(
                        text = cameraError!!,
                        color = Color.White,
                        modifier = Modifier
                            .background(CameraBlack, RoundedCornerShape(12.dp))
                            .padding(12.dp)
                    )
                }

                Text(
                    text = "Position the entire question sheet inside the frame.",
                    color = Color.White,
                    fontSize = 12.sp,
                    modifier = Modifier
                        .background(CameraBlack, RoundedCornerShape(12.dp))
                        .padding(12.dp)
                )

                Spacer(modifier = Modifier.height(24.dp))

                Button(
                    onClick = {
                        val capture = imageCapture ?: return@Button
                        if (capturing) return@Button

                        capturing = true

                        val file = File.createTempFile(
                            "dch_capture_",
                            ".jpg",
                            context.cacheDir
                        )

                        val options = ImageCapture.OutputFileOptions
                            .Builder(file)
                            .build()

                        capture.takePicture(
                            options,
                            ContextCompat.getMainExecutor(context),
                            object : ImageCapture.OnImageSavedCallback {
                                override fun onImageSaved(
                                    output: ImageCapture.OutputFileResults
                                ) {
                                    scope.launch {
                                        try {
                                            val saved = withContext(Dispatchers.IO) {
                                                repository.addPage(sessionId, file)
                                            }

                                            val durableFile = File(
                                                saved.pagePaths.last()
                                            )

                                            pageCount = saved.pageCount
                                            capturedFile = durableFile
                                        } catch (error: Exception) {
                                            cameraError = error.localizedMessage
                                                ?: "Unable to save the captured page."
                                        } finally {
                                            file.delete()
                                            capturing = false
                                        }
                                    }
                                }

                                override fun onError(
                                    exception: ImageCaptureException
                                ) {
                                    file.delete()
                                    capturing = false
                                    cameraError = exception.message
                                        ?: "Photo capture failed."
                                }
                            }
                        )
                    },
                    enabled = imageCapture != null && !capturing,
                    modifier = Modifier
                        .size(76.dp),
                    shape = CircleShape,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = CameraRed,
                        disabledContainerColor = Color.DarkGray
                    ),
                    contentPadding = PaddingValues(0.dp)
                ) {
                    Text(
                        if (capturing) "..." else "●",
                        fontSize = 30.sp,
                        color = Color.White
                    )
                }

                Spacer(modifier = Modifier.height(20.dp))
            }
        }
    }
}

@Composable
private fun PhotoReviewScreen(
    file: File,
    pageNumber: Int,
    onRetake: () -> Unit,
    onAddPage: () -> Unit,
    onFinish: () -> Unit
) {
    val bitmap = remember(file) {
        decodePreviewBitmap(file)
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(CameraBlack)
            .padding(20.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = "REVIEW PAGE $pageNumber",
            color = CameraRed,
            fontWeight = FontWeight.Bold,
            letterSpacing = 2.sp
        )

        Spacer(modifier = Modifier.height(20.dp))

        if (bitmap != null) {
            Image(
                bitmap = bitmap.asImageBitmap(),
                contentDescription = "Captured question sheet",
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
            )
        } else {
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
                contentAlignment = Alignment.Center
            ) {
                Text("Unable to display photo.", color = Color.White)
            }
        }

        Spacer(modifier = Modifier.height(20.dp))

        OutlinedButton(
            onClick = onRetake,
            modifier = Modifier
                .fillMaxWidth()
                .height(48.dp)
        ) {
            Text("RETAKE PHOTO", color = Color.White)
        }

        Spacer(modifier = Modifier.height(10.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            OutlinedButton(
                onClick = onAddPage,
                modifier = Modifier
                    .weight(1f)
                    .height(54.dp)
            ) {
                Text("ADD PAGE", color = Color.White)
            }

            Button(
                onClick = onFinish,
                modifier = Modifier
                    .weight(1f)
                    .height(54.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = CameraRed
                )
            ) {
                Text("FINISH SCAN", color = Color.White)
            }
        }
    }
}

private fun decodePreviewBitmap(file: File): Bitmap? {
    val bounds = BitmapFactory.Options().apply {
        inJustDecodeBounds = true
    }

    BitmapFactory.decodeFile(file.absolutePath, bounds)

    var sampleSize = 1
    val targetSize = 1400

    while (
        bounds.outWidth / sampleSize > targetSize ||
        bounds.outHeight / sampleSize > targetSize
    ) {
        sampleSize *= 2
    }

    return BitmapFactory.decodeFile(
        file.absolutePath,
        BitmapFactory.Options().apply {
            inSampleSize = sampleSize
            inPreferredConfig = Bitmap.Config.RGB_565
        }
    )
}
