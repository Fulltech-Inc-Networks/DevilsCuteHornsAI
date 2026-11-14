package com.fulltech.devilscutehornsai

import android.Manifest
import android.content.pm.PackageManager
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.DateFormat
import java.util.Date

private val AppBlack = Color(0xFF090909)
private val AppSurface = Color(0xFF171717)
private val AppRed = Color(0xFFDC143C)
private val AppMuted = Color(0xFF999999)

@Composable
fun DchApp() {
    val context = LocalContext.current
    val repository = remember {
        SessionRepository(context.applicationContext)
    }

    var route by remember { mutableStateOf("home") }
    var pages by remember { mutableStateOf<List<File>>(emptyList()) }
    var activeSessionId by remember { mutableStateOf<String?>(null) }

    val cameraPermissionLauncher =
        rememberLauncherForActivityResult(
            ActivityResultContracts.RequestPermission()
        ) { granted ->
            if (granted) {
                route = "camera"
            } else {
                Toast.makeText(
                    context,
                    "Camera permission is required to scan documents.",
                    Toast.LENGTH_LONG
                ).show()
            }
        }

    when (route) {
        "camera" -> {
            CameraScreen(
                onBack = { route = "home" },
                onImageReady = { captured ->
                    pages = captured
                    route = "review"
                }
            )
        }

        "review" -> {
            OcrReviewScreen(
                pages = pages,
                repository = repository,
                onSaved = {
                    pages.forEach { it.delete() }
                    pages = emptyList()
                    route = "history"
                },
                onCancel = {
                    pages.forEach { it.delete() }
                    pages = emptyList()
                    route = "home"
                }
            )
        }

        "history" -> {
            SessionHistoryScreen(
                repository = repository,
                onBack = { route = "home" },
                onOpen = { session ->
                    activeSessionId = session.id
                    route = "detail"
                }
            )
        }

        "detail" -> {
            val id = activeSessionId
            if (id != null) {
                SessionDetailScreen(
                    sessionId = id,
                    repository = repository,
                    onBack = { route = "history" },
                    onDeleted = { route = "history" }
                )
            } else {
                route = "history"
            }
        }

        else -> {
            DashboardScreen(
                onCapture = {
                    val granted = ContextCompat.checkSelfPermission(
                        context,
                        Manifest.permission.CAMERA
                    ) == PackageManager.PERMISSION_GRANTED

                    if (granted) {
                        route = "camera"
                    } else {
                        cameraPermissionLauncher.launch(
                            Manifest.permission.CAMERA
                        )
                    }
                },
                onHistory = { route = "history" }
            )
        }
    }
}

@Composable
private fun DashboardScreen(
    onCapture: () -> Unit,
    onHistory: () -> Unit
) {
    Surface(
        modifier = Modifier.fillMaxSize(),
        color = AppBlack
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Spacer(modifier = Modifier.height(65.dp))

            Text("😈", fontSize = 64.sp)

            Spacer(modifier = Modifier.height(16.dp))

            Text(
                "DEVIL'S",
                color = Color.White,
                fontSize = 17.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = 6.sp
            )

            Text(
                "CUTE HORNS AI",
                color = AppRed,
                fontSize = 27.sp,
                fontWeight = FontWeight.Black,
                textAlign = TextAlign.Center
            )

            Spacer(modifier = Modifier.height(10.dp))

            Text(
                "Cute Interface. Unholy Intelligence.",
                color = AppMuted,
                fontSize = 12.sp
            )

            Spacer(modifier = Modifier.height(55.dp))

            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = AppSurface
                ),
                shape = RoundedCornerShape(20.dp)
            ) {
                Column(modifier = Modifier.padding(22.dp)) {
                    Text(
                        "DOCUMENT INTELLIGENCE",
                        color = AppRed,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 2.sp
                    )

                    Spacer(modifier = Modifier.height(12.dp))

                    Text(
                        "Your personal AI assistant.",
                        color = Color.White,
                        fontSize = 19.sp,
                        fontWeight = FontWeight.Bold
                    )

                    Spacer(modifier = Modifier.height(8.dp))

                    Text(
                        "Scan multiple pages, extract text offline, " +
                            "correct recognition mistakes and save your sessions.",
                        color = AppMuted,
                        fontSize = 13.sp,
                        lineHeight = 21.sp
                    )

                    Spacer(modifier = Modifier.height(24.dp))

                    Button(
                        onClick = onCapture,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(54.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = AppRed
                        ),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Text(
                            "START SCANNING",
                            fontWeight = FontWeight.Bold,
                            letterSpacing = 1.sp
                        )
                    }

                    Spacer(modifier = Modifier.height(10.dp))

                    OutlinedButton(
                        onClick = onHistory,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(52.dp)
                    ) {
                        Text("SCAN HISTORY", color = Color.White)
                    }
                }
            }

            Spacer(modifier = Modifier.weight(1f))

            Text(
                "DCH AI  •  VERSION 1.0.0",
                color = Color.DarkGray,
                fontSize = 10.sp,
                letterSpacing = 2.sp
            )
        }
    }
}

@Composable
private fun OcrReviewScreen(
    pages: List<File>,
    repository: SessionRepository,
    onSaved: () -> Unit,
    onCancel: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val recognizer = remember { OcrEngine() }

    var extractedText by remember { mutableStateOf("") }
    var processing by remember { mutableStateOf(true) }
    var saving by remember { mutableStateOf(false) }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    var progress by remember { mutableStateOf(0) }

    DisposableEffect(recognizer) {
        onDispose { recognizer.close() }
    }

    LaunchedEffect(pages) {
        processing = true
        errorMessage = null
        progress = 0

        val output = StringBuilder()

        pages.forEachIndexed { index, file ->
            val completion = CompletableDeferred<Result<String>>()

            recognizer.recognize(context, file) { result ->
                completion.complete(result)
            }

            val result = completion.await()

            output.append("PAGE ${index + 1}\n\n")

            result.fold(
                onSuccess = { text ->
                    if (text.isBlank()) {
                        output.append(
                            "[No readable text detected on this page.]\n"
                        )
                    } else {
                        output.append(text.trim())
                        output.append('\n')
                    }
                },
                onFailure = { failure ->
                    output.append(
                        "[Recognition failed: " +
                            (failure.localizedMessage ?: "Unknown error") +
                            "]\n"
                    )
                    errorMessage = "Some pages could not be recognized."
                }
            )

            output.append("\n\n")
            progress = index + 1
        }

        extractedText = output.toString().trim()
        processing = false
    }

    Surface(
        modifier = Modifier.fillMaxSize(),
        color = AppBlack
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(20.dp)
        ) {
            Text(
                "TEXT RECOGNITION",
                color = AppRed,
                fontWeight = FontWeight.Bold,
                letterSpacing = 2.sp
            )

            Spacer(modifier = Modifier.height(8.dp))

            Text(
                if (processing) {
                    "Recognizing page $progress of ${pages.size}..."
                } else {
                    "${pages.size} page(s) processed. Review and correct the text."
                },
                color = AppMuted,
                fontSize = 13.sp
            )

            if (processing) {
                Spacer(modifier = Modifier.height(18.dp))
                LinearProgressIndicator(
                    progress = {
                        if (pages.isEmpty()) 0f
                        else progress.toFloat() / pages.size
                    },
                    modifier = Modifier.fillMaxWidth(),
                    color = AppRed
                )
            }

            if (errorMessage != null) {
                Spacer(modifier = Modifier.height(8.dp))
                Text(errorMessage!!, color = AppRed, fontSize = 12.sp)
            }

            Spacer(modifier = Modifier.height(16.dp))

            OutlinedTextField(
                value = extractedText,
                onValueChange = { extractedText = it },
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
                enabled = !processing,
                label = { Text("Extracted text") },
                placeholder = {
                    Text("Recognized text will appear here...")
                },
                minLines = 12
            )

            Spacer(modifier = Modifier.height(14.dp))

            Button(
                onClick = {
                    scope.launch {
                        saving = true
                        errorMessage = null

                        val result = runCatching {
                            withContext(Dispatchers.IO) {
                                repository.saveSession(
                                    pages,
                                    extractedText.trim()
                                )
                            }
                        }

                        saving = false

                        result.onSuccess {
                            onSaved()
                        }.onFailure {
                            errorMessage = it.localizedMessage
                                ?: "Unable to save this session."
                        }
                    }
                },
                enabled = !processing && !saving && pages.isNotEmpty(),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(54.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = AppRed
                )
            ) {
                Text(
                    if (saving) "SAVING..." else "SAVE SESSION",
                    fontWeight = FontWeight.Bold
                )
            }

            Spacer(modifier = Modifier.height(8.dp))

            OutlinedButton(
                onClick = onCancel,
                enabled = !saving,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(48.dp)
            ) {
                Text("DISCARD SCAN", color = Color.White)
            }
        }
    }
}

@Composable
private fun SessionHistoryScreen(
    repository: SessionRepository,
    onBack: () -> Unit,
    onOpen: (ScanSession) -> Unit
) {
    val scope = rememberCoroutineScope()
    var sessions by remember { mutableStateOf<List<ScanSession>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }

    LaunchedEffect(Unit) {
        sessions = withContext(Dispatchers.IO) {
            repository.getSessions()
        }
        loading = false
    }

    Surface(
        modifier = Modifier.fillMaxSize(),
        color = AppBlack
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(20.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                TextButton(onClick = onBack) {
                    Text("BACK", color = Color.White)
                }

                Text(
                    "SCAN HISTORY",
                    color = AppRed,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 2.sp
                )
            }

            Spacer(modifier = Modifier.height(12.dp))

            when {
                loading -> {
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center
                    ) {
                        CircularProgressIndicator(color = AppRed)
                    }
                }

                sessions.isEmpty() -> {
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            "No saved scans yet.",
                            color = AppMuted
                        )
                    }
                }

                else -> {
                    LazyColumn(
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        items(sessions, key = { it.id }) { session ->
                            Card(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable { onOpen(session) },
                                colors = CardDefaults.cardColors(
                                    containerColor = AppSurface
                                )
                            ) {
                                Column(
                                    modifier = Modifier.padding(18.dp)
                                ) {
                                    Text(
                                        DateFormat.getDateTimeInstance().format(
                                            Date(session.createdAt)
                                        ),
                                        color = AppRed,
                                        fontWeight = FontWeight.Bold
                                    )

                                    Spacer(modifier = Modifier.height(6.dp))

                                    Text(
                                        "${session.pageCount} page(s)",
                                        color = Color.White
                                    )

                                    Spacer(modifier = Modifier.height(4.dp))

                                    Text(
                                        session.extractedText
                                            .take(140)
                                            .ifBlank { "No text extracted." },
                                        color = AppMuted,
                                        fontSize = 12.sp
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SessionDetailScreen(
    sessionId: String,
    repository: SessionRepository,
    onBack: () -> Unit,
    onDeleted: () -> Unit
) {
    val scope = rememberCoroutineScope()

    var session by remember {
        mutableStateOf<ScanSession?>(null)
    }
    var text by remember { mutableStateOf("") }
    var loading by remember { mutableStateOf(true) }
    var working by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(sessionId) {
        session = withContext(Dispatchers.IO) {
            repository.getSession(sessionId)
        }
        text = session?.extractedText.orEmpty()
        loading = false
    }

    Surface(
        modifier = Modifier.fillMaxSize(),
        color = AppBlack
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(20.dp)
        ) {
            TextButton(onClick = onBack) {
                Text("BACK TO HISTORY", color = Color.White)
            }

            Spacer(modifier = Modifier.height(12.dp))

            when {
                loading -> {
                    CircularProgressIndicator(color = AppRed)
                }

                session == null -> {
                    Text("This session could not be found.", color = AppMuted)
                }

                else -> {
                    Text(
                        "SAVED SESSION",
                        color = AppRed,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 2.sp
                    )

                    Spacer(modifier = Modifier.height(8.dp))

                    Text(
                        "${session!!.pageCount} scanned page(s)",
                        color = AppMuted
                    )

                    Spacer(modifier = Modifier.height(16.dp))

                    OutlinedTextField(
                        value = text,
                        onValueChange = { text = it },
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxWidth(),
                        label = { Text("Session text") },
                        minLines = 12
                    )

                    if (message != null) {
                        Text(message!!, color = AppRed, fontSize = 12.sp)
                    }

                    Spacer(modifier = Modifier.height(12.dp))

                    Button(
                        onClick = {
                            scope.launch {
                                working = true
                                val success = withContext(Dispatchers.IO) {
                                    repository.updateText(sessionId, text)
                                }
                                working = false
                                message = if (success) {
                                    "Changes saved."
                                } else {
                                    "Unable to save changes."
                                }
                            }
                        },
                        enabled = !working,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(52.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = AppRed
                        )
                    ) {
                        Text(if (working) "SAVING..." else "SAVE CHANGES")
                    }

                    Spacer(modifier = Modifier.height(8.dp))

                    OutlinedButton(
                        onClick = {
                            scope.launch {
                                working = true
                                withContext(Dispatchers.IO) {
                                    repository.deleteSession(sessionId)
                                }
                                working = false
                                onDeleted()
                            }
                        },
                        enabled = !working,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(48.dp)
                    ) {
                        Text("DELETE SESSION", color = AppRed)
                    }
                }
            }
        }
    }
}
