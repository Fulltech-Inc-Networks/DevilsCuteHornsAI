package com.fulltech.devilscutehornsai

import androidx.activity.compose.BackHandler

import android.Manifest
import android.content.pm.PackageManager
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.Canvas
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.foundation.clickable
import androidx.compose.foundation.background
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.work.WorkManager
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
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

    val backStack = remember { mutableStateListOf("home") }
    val route = backStack.last()
    var pages by remember { mutableStateOf<List<File>>(emptyList()) }
    var activeSessionId by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()

    fun navigate(destination: String) {
        if (backStack.lastOrNull() != destination) {
            backStack.add(destination)
        }
    }

    fun goBack() {
        if (backStack.size > 1) {
            backStack.removeAt(backStack.lastIndex)
        }
    }

    fun resetToHistoryDetail(id: String) {
        activeSessionId = id
        backStack.clear()
        backStack.addAll(listOf("home", "history", "detail"))
    }

    BackHandler(enabled = backStack.size > 1) {
        goBack()
    }

    LaunchedEffect(route, activeSessionId) {
        if (
            (route == "camera" || route == "review" || route == "detail") &&
            activeSessionId == null
        ) {
            goBack()
        }
    }

    fun openCameraWithDraft() {
        scope.launch {
            try {
                val draft = withContext(Dispatchers.IO) {
                    repository.createDraftSession()
                }
                activeSessionId = draft.id
                navigate("camera")
            } catch (error: Exception) {
                Toast.makeText(
                    context,
                    error.localizedMessage ?: "Unable to start a scan.",
                    Toast.LENGTH_LONG
                ).show()
            }
        }
    }

    val cameraPermissionLauncher =
        rememberLauncherForActivityResult(
            ActivityResultContracts.RequestPermission()
        ) { granted ->
            if (granted) {
                openCameraWithDraft()
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
            val draftId = activeSessionId
            if (draftId != null) {
                CameraScreen(
                    sessionId = draftId,
                    repository = repository,
                    onBack = { goBack() },
                    onFinished = { id ->
                        val session = repository.getSession(id)
                        if (session != null && session.pagePaths.isNotEmpty()) {
                            activeSessionId = id
                            pages = session.pagePaths.map(::File)
                            navigate("review")
                        } else {
                            Toast.makeText(
                                context,
                                "Capture at least one page first.",
                                Toast.LENGTH_SHORT
                            ).show()
                        }
                    }
                )
            }
        }

        "review" -> {
            val reviewId = activeSessionId
            if (reviewId != null) {
                OcrReviewScreen(
                    sessionId = reviewId,
                    repository = repository,
                    onSaved = {
                        pages = emptyList()
                        resetToHistoryDetail(reviewId)
                    }
                )
            }
        }

        "settings" -> {
            SettingsHubScreen(
                onBack = { goBack() },
                onReadingSettings = { navigate("reading_settings") },
                onGatewaySettings = { navigate("gateway_settings") },
                onDeviceConnect = { navigate("device_connect") }
            )
        }

        "reading_settings" -> {
            ReadingSettingsScreen(onBack = { goBack() })
        }

        "gateway_settings" -> {
            GatewaySettingsScreen(onBack = { goBack() })
        }

        "device_connect" -> {
            DeviceConnectScreen(onBack = { goBack() })
        }

        "history" -> {
            SessionHistoryScreen(
                repository = repository,
                onBack = { goBack() },
                onOpen = { session ->
                    activeSessionId = session.id
                    navigate("detail")
                }
            )
        }

        "detail" -> {
            val id = activeSessionId
            if (id != null) {
                SessionDetailScreen(
                    sessionId = id,
                    repository = repository,
                    onBack = { goBack() },
                    onDeleted = { goBack() }
                )
            }
        }

        else -> {
            DashboardScreen(
                repository = repository,
                onOpenSession = { session ->
                    activeSessionId = session.id
                    navigate("detail")
                },
                onCapture = {
                    val granted = ContextCompat.checkSelfPermission(
                        context,
                        Manifest.permission.CAMERA
                    ) == PackageManager.PERMISSION_GRANTED

                    if (granted) {
                        openCameraWithDraft()
                    } else {
                        cameraPermissionLauncher.launch(
                            Manifest.permission.CAMERA
                        )
                    }
                },
                onHistory = { navigate("history") },
                onSettings = { navigate("settings") }
            )
        }
    }
}

@Composable
private fun DashboardScreen(
    repository: SessionRepository,
    onOpenSession: (ScanSession) -> Unit,
    onCapture: () -> Unit,
    onHistory: () -> Unit,
    onSettings: () -> Unit
) {
    var recentSessions by remember {
        mutableStateOf<List<ScanSession>>(emptyList())
    }
    var recentSessionsLoading by remember { mutableStateOf(true) }

    LaunchedEffect(repository) {
        recentSessions = withContext(Dispatchers.IO) {
            repository.getSessions().take(3)
        }
        recentSessionsLoading = false
    }

    Surface(
        modifier = Modifier.fillMaxSize(),
        color = AppBlack
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        colors = listOf(
                            Color(0xFF200B12),
                            Color(0xFF100B0E),
                            AppBlack
                        )
                    )
                )
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 20.dp, vertical = 16.dp)
            ) {
                // APP HEADER
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Image(
                        painter = painterResource(R.drawable.dch_logo),
                        contentDescription = "DCH AI logo",
                        modifier = Modifier
                            .size(46.dp)
                            .clip(RoundedCornerShape(13.dp)),
                        contentScale = ContentScale.Fit
                    )

                    Spacer(modifier = Modifier.width(12.dp))

                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            "DEVIL'S CUTE HORNS AI",
                            color = Color.White,
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Black,
                            letterSpacing = 0.5.sp
                        )
                        Text(
                            "YOUR INTELLIGENT STUDY SPACE",
                            color = AppMuted,
                            fontSize = 8.sp,
                            letterSpacing = 1.sp
                        )
                    }


                }

                Spacer(modifier = Modifier.height(30.dp))

                // WELCOME
                Text(
                    "WELCOME BACK",
                    color = Color(0xFFFF647E),
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 2.sp
                )

                Spacer(modifier = Modifier.height(7.dp))

                Text(
                    "What are we\nlearning today?",
                    color = Color.White,
                    fontSize = 29.sp,
                    lineHeight = 35.sp,
                    fontWeight = FontWeight.ExtraBold
                )

                Spacer(modifier = Modifier.height(9.dp))

                Text(
                    "Turn your documents into knowledge with AI.",
                    color = Color(0xFFB7ADB1),
                    fontSize = 13.sp,
                    lineHeight = 20.sp
                )

                Spacer(modifier = Modifier.height(25.dp))

                // PRIMARY ACTION
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(23.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = Color(0xFF24151B)
                    )
                ) {
                    Column(
                        modifier = Modifier.padding(20.dp)
                    ) {
                        Text(
                            "YOUR AI STUDY ASSISTANT",
                            color = Color(0xFFFF7189),
                            fontSize = 9.sp,
                            fontWeight = FontWeight.Bold,
                            letterSpacing = 1.5.sp
                        )

                        Spacer(modifier = Modifier.height(12.dp))

                        Text(
                            "Start a new session",
                            color = Color.White,
                            fontSize = 22.sp,
                            fontWeight = FontWeight.ExtraBold
                        )

                        Spacer(modifier = Modifier.height(7.dp))

                        Text(
                            "Capture question papers, notes or study material. " +
                                "Let AI understand the content and prepare your answers.",
                            color = Color(0xFFC1B4B9),
                            fontSize = 12.sp,
                            lineHeight = 19.sp
                        )

                        Spacer(modifier = Modifier.height(20.dp))

                        Button(
                            onClick = onCapture,
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(55.dp),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = AppRed
                            ),
                            shape = RoundedCornerShape(15.dp)
                        ) {
                            Text(
                                "＋   START NEW SESSION",
                                color = Color.White,
                                fontSize = 13.sp,
                                fontWeight = FontWeight.ExtraBold,
                                letterSpacing = 0.6.sp
                            )
                        }

                        Spacer(modifier = Modifier.height(10.dp))

                        Text(
                            "MULTI-PAGE CAPTURE  •  AI ANALYSIS  •  AUDIO",
                            modifier = Modifier.fillMaxWidth(),
                            color = Color(0xFF9B858D),
                            fontSize = 8.sp,
                            textAlign = TextAlign.Center,
                            letterSpacing = 0.5.sp
                        )
                    }
                }

                Spacer(modifier = Modifier.height(27.dp))

                // QUICK ACCESS
                Text(
                    "QUICK ACCESS",
                    color = Color.White,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 1.2.sp
                )

                Spacer(modifier = Modifier.height(13.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Card(
                        modifier = Modifier
                            .weight(1f)
                            .height(112.dp)
                            .clickable(onClick = onHistory),
                        colors = CardDefaults.cardColors(
                            containerColor = Color(0xFF191518)
                        ),
                        shape = RoundedCornerShape(17.dp)
                    ) {
                        Column(
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(15.dp),
                            verticalArrangement = Arrangement.Center
                        ) {
                            Text(
                                "◷",
                                color = Color(0xFFFF647E),
                                fontSize = 25.sp
                            )
                            Spacer(modifier = Modifier.height(7.dp))
                            Text(
                                "History",
                                color = Color.White,
                                fontSize = 14.sp,
                                fontWeight = FontWeight.Bold
                            )
                            Text(
                                "Previous sessions",
                                color = AppMuted,
                                fontSize = 10.sp
                            )
                        }
                    }

                    Card(
                        modifier = Modifier
                            .weight(1f)
                            .height(112.dp)
                            .clickable(onClick = onSettings),
                        colors = CardDefaults.cardColors(
                            containerColor = Color(0xFF191518)
                        ),
                        shape = RoundedCornerShape(17.dp)
                    ) {
                        Column(
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(15.dp),
                            verticalArrangement = Arrangement.Center
                        ) {
                            Text(
                                "⚙",
                                color = Color(0xFFFF647E),
                                fontSize = 25.sp
                            )
                            Spacer(modifier = Modifier.height(7.dp))
                            Text(
                                "Settings",
                                color = Color.White,
                                fontSize = 14.sp,
                                fontWeight = FontWeight.Bold
                            )
                            Text(
                                "Personalize your app",
                                color = AppMuted,
                                fontSize = 10.sp
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(27.dp))

                // REAL RECENT SESSIONS
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        "RECENT SESSIONS",
                        color = Color.White,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 1.2.sp
                    )

                    if (recentSessions.isNotEmpty()) {
                        TextButton(
                            onClick = onHistory,
                            contentPadding = PaddingValues(0.dp)
                        ) {
                            Text(
                                "VIEW ALL",
                                color = Color(0xFFFF7189),
                                fontSize = 10.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(12.dp))

                when {
                    recentSessionsLoading -> {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 22.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            CircularProgressIndicator(
                                color = AppRed,
                                modifier = Modifier.size(25.dp),
                                strokeWidth = 2.dp
                            )
                        }
                    }

                    recentSessions.isEmpty() -> {
                        Card(
                            modifier = Modifier.fillMaxWidth(),
                            colors = CardDefaults.cardColors(
                                containerColor = Color(0xFF191518)
                            ),
                            shape = RoundedCornerShape(16.dp)
                        ) {
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(17.dp)
                            ) {
                                Text(
                                    "Your study space starts here",
                                    color = Color.White,
                                    fontSize = 14.sp,
                                    fontWeight = FontWeight.Bold
                                )
                                Spacer(modifier = Modifier.height(5.dp))
                                Text(
                                    "You haven't saved any sessions yet. " +
                                        "Start a new session to scan your first document.",
                                    color = AppMuted,
                                    fontSize = 11.sp,
                                    lineHeight = 17.sp
                                )
                                Spacer(modifier = Modifier.height(12.dp))
                                TextButton(
                                    onClick = onCapture,
                                    contentPadding = PaddingValues(0.dp)
                                ) {
                                    Text(
                                        "START YOUR FIRST SESSION  →",
                                        color = Color(0xFFFF7189),
                                        fontSize = 10.sp,
                                        fontWeight = FontWeight.Bold
                                    )
                                }
                            }
                        }
                    }

                    else -> {
                        Column(
                            verticalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            recentSessions.forEach { session ->
                                Card(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clickable {
                                            onOpenSession(session)
                                        },
                                    colors = CardDefaults.cardColors(
                                        containerColor = Color(0xFF191518)
                                    ),
                                    shape = RoundedCornerShape(15.dp)
                                ) {
                                    Column(
                                        modifier = Modifier.padding(15.dp)
                                    ) {
                                        Text(
                                            DateFormat.getDateTimeInstance().format(
                                                Date(session.createdAt)
                                            ),
                                            color = Color(0xFFFF7189),
                                            fontSize = 10.sp,
                                            fontWeight = FontWeight.Bold
                                        )

                                        Spacer(modifier = Modifier.height(6.dp))

                                        Text(
                                            "${session.pageCount} " +
                                                if (session.pageCount == 1) {
                                                    "page"
                                                } else {
                                                    "pages"
                                                },
                                            color = Color.White,
                                            fontSize = 14.sp,
                                            fontWeight = FontWeight.Bold
                                        )

                                        Spacer(modifier = Modifier.height(4.dp))

                                        Text(
                                            session.extractedText
                                                .take(110)
                                                .ifBlank {
                                                    "No text preview available."
                                                },
                                            color = AppMuted,
                                            fontSize = 11.sp,
                                            lineHeight = 16.sp
                                        )

                                        Spacer(modifier = Modifier.height(7.dp))

                                        Text(
                                            "OPEN SESSION  →",
                                            color = Color(0xFFFF7189),
                                            fontSize = 9.sp,
                                            fontWeight = FontWeight.Bold
                                        )
                                    }
                                }
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.height(25.dp))

                // HOW IT WORKS
                Text(
                    "HOW IT WORKS",
                    color = Color.White,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 1.2.sp
                )

                Spacer(modifier = Modifier.height(12.dp))

                listOf(
                    Triple("01", "Capture", "Scan your study material"),
                    Triple("02", "Understand", "AI processes the content"),
                    Triple("03", "Listen", "Listen to your answers")
                ).forEach { item ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Surface(
                            color = Color(0xFF35141F),
                            shape = RoundedCornerShape(11.dp)
                        ) {
                            Text(
                                item.first,
                                modifier = Modifier.padding(
                                    horizontal = 13.dp,
                                    vertical = 11.dp
                                ),
                                color = Color(0xFFFF7189),
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Black
                            )
                        }

                        Spacer(modifier = Modifier.width(13.dp))

                        Column {
                            Text(
                                item.second,
                                color = Color.White,
                                fontSize = 13.sp,
                                fontWeight = FontWeight.Bold
                            )
                            Text(
                                item.third,
                                color = AppMuted,
                                fontSize = 11.sp
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(25.dp))

                Spacer(modifier = Modifier.height(12.dp))

                // SIGNATURE FOOTER
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    color = Color.Black
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 22.dp, horizontal = 12.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        BloodDripText(
                            text = "The Future is Here",
                            fontSize = 26.sp,
                            modifier = Modifier.fillMaxWidth().height(58.dp),
                            dripPoints = listOf(
                                0.39f to 0.88f,
                                0.45f to 0.78f,
                                0.53f to 0.94f,
                                0.60f to 0.84f
                            )
                        )

                        Spacer(modifier = Modifier.height(5.dp))

                        BloodDripText(
                            text = "AI created by Someone who would like to stay Anonymous",
                            fontSize = 13.sp,
                            modifier = Modifier.fillMaxWidth().height(45.dp),
                            dripPoints = listOf(
                                0.20f to 0.84f,
                                0.39f to 0.76f,
                                0.63f to 0.92f,
                                0.80f to 0.82f
                            )
                        )
                    }
                }

                Spacer(modifier = Modifier.height(15.dp))
            }
        }
    }
}



@Composable
private fun BloodDripText(
    text: String,
    fontSize: androidx.compose.ui.unit.TextUnit,
    modifier: Modifier = Modifier,
    dripPoints: List<Pair<Float, Float>>
) {
    val blood = Color(0xFFB5122A)
    val highlight = Color(0xFFFF263F)

    Box(
        modifier = modifier,
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = text,
            color = blood,
            fontSize = fontSize,
            fontFamily = FontFamily.Serif,
            fontStyle = FontStyle.Italic,
            fontWeight = FontWeight.ExtraBold,
            textAlign = TextAlign.Center,
            lineHeight = fontSize * 1.25f,
            style = TextStyle(
                shadow = Shadow(
                    color = Color(0xFF9E1025),
                    offset = Offset(0f, 1.5f),
                    blurRadius = 7f
                )
            )
        )

        Canvas(modifier = Modifier.fillMaxSize()) {
            dripPoints.forEachIndexed { index, point ->
                val x = size.width * point.first
                val startY = size.height * 0.68f
                val endY = size.height * point.second

                drawLine(
                    color = blood,
                    start = Offset(x, startY),
                    end = Offset(x, endY),
                    strokeWidth = if (index % 2 == 0) 2.2f else 1.6f
                )

                drawCircle(
                    color = blood,
                    radius = if (index % 2 == 0) 2.2f else 1.6f,
                    center = Offset(x, endY)
                )

                drawCircle(
                    color = highlight.copy(alpha = 0.65f),
                    radius = 0.8f,
                    center = Offset(x - 0.7f, startY + 2f)
                )
            }
        }
    }
}

@Composable
private fun GatewaySettingsScreen(
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val tokenStore = remember {
        GatewayTokenStore(context.applicationContext)
    }

    var tokenInput by remember { mutableStateOf("") }
    var tokenSaved by remember {
        mutableStateOf(runCatching { tokenStore.read() != null }.getOrDefault(false))
    }
    var checking by remember { mutableStateOf(false) }
    var statusMessage by remember { mutableStateOf<String?>(null) }
    var statusSuccess by remember { mutableStateOf(false) }

    var updateChecking by remember { mutableStateOf(false) }
    var updateInstalling by remember { mutableStateOf(false) }
    var updatePending by remember {
        mutableStateOf(UpdateInstallState.isPending(context))
    }
    var availableUpdate by remember { mutableStateOf<AppUpdate?>(null) }
    var updateStatus by remember {
        mutableStateOf(UpdateInstallState.message(context))
    }
    var updateSuccess by remember {
        mutableStateOf(UpdateInstallState.isSuccess(context))
    }

    DisposableEffect(context) {
        val preferences = context.getSharedPreferences(
            "dch_update_install",
            android.content.Context.MODE_PRIVATE
        )

        val listener = android.content.SharedPreferences.OnSharedPreferenceChangeListener {
                prefs, _ ->
            updateStatus = prefs.getString("message", null)
            updateSuccess = prefs.getBoolean("success", false)
            updatePending = prefs.getBoolean("pending", false)
        }

        preferences.registerOnSharedPreferenceChangeListener(listener)

        onDispose {
            preferences.unregisterOnSharedPreferenceChangeListener(listener)
        }
    }

    Surface(
        modifier = Modifier.fillMaxSize(),
        color = AppBlack
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(24.dp)
        ) {
            TextButton(onClick = onBack) {
                Text("← BACK", color = AppRed)
            }

            Spacer(modifier = Modifier.height(20.dp))

            Text(
                "AI GATEWAY",
                color = AppRed,
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = 3.sp
            )

            Spacer(modifier = Modifier.height(8.dp))

            Text(
                "Connection Settings",
                color = Color.White,
                fontSize = 25.sp,
                fontWeight = FontWeight.Bold
            )

            Spacer(modifier = Modifier.height(12.dp))

            Text(
                "Connect DCH AI to its secure Cloudflare gateway. " +
                    "Your gateway token is encrypted on this device using Android Keystore.",
                color = AppMuted,
                fontSize = 14.sp,
                lineHeight = 22.sp
            )

            Spacer(modifier = Modifier.height(28.dp))

            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = AppSurface),
                shape = RoundedCornerShape(16.dp)
            ) {
                Column(modifier = Modifier.padding(18.dp)) {
                    Text(
                        "GATEWAY TOKEN",
                        color = AppRed,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 2.sp
                    )

                    Spacer(modifier = Modifier.height(12.dp))

                    OutlinedTextField(
                        value = tokenInput,
                        onValueChange = {
                            tokenInput = it
                            statusMessage = null
                        },
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text("Enter your gateway token") },
                        singleLine = true,
                        visualTransformation = PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(
                            keyboardType = KeyboardType.Password
                        )
                    )

                    Spacer(modifier = Modifier.height(8.dp))

                    Text(
                        if (tokenSaved) {
                            "A gateway token is saved on this device."
                        } else {
                            "No gateway token has been saved yet."
                        },
                        color = if (tokenSaved) Color(0xFF72D69A) else AppMuted,
                        fontSize = 12.sp
                    )

                    Spacer(modifier = Modifier.height(18.dp))

                    Button(
                        onClick = {
                            try {
                                tokenStore.save(tokenInput)
                                tokenInput = ""
                                tokenSaved = true
                                statusMessage = "Token saved securely on this device."
                                statusSuccess = true
                            } catch (error: Exception) {
                                statusMessage = error.localizedMessage
                                    ?: "Unable to save the token."
                                statusSuccess = false
                            }
                        },
                        enabled = tokenInput.isNotBlank(),
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(50.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = AppRed
                        ),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Text("SAVE TOKEN", fontWeight = FontWeight.Bold)
                    }

                    Spacer(modifier = Modifier.height(10.dp))

                    OutlinedButton(
                        onClick = {
                            checking = true
                            statusMessage = null

                            scope.launch {
                                try {
                                    val result = withContext(Dispatchers.IO) {
                                        GatewayClient.checkHealth()
                                    }

                                    statusMessage =
                                        "${result.text} Model: ${result.model}"
                                    statusSuccess = true
                                } catch (error: Exception) {
                                    statusMessage = error.localizedMessage
                                        ?: "Gateway connection failed."
                                    statusSuccess = false
                                } finally {
                                    checking = false
                                }
                            }
                        },
                        enabled = !checking,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(50.dp)
                    ) {
                        if (checking) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(20.dp),
                                color = AppRed,
                                strokeWidth = 2.dp
                            )
                            Spacer(modifier = Modifier.width(10.dp))
                        }
                        Text(
                            if (checking) "CHECKING CONNECTION..." else "TEST GATEWAY CONNECTION",
                            color = Color.White
                        )
                    }

                    if (statusMessage != null) {
                        Spacer(modifier = Modifier.height(16.dp))
                        Text(
                            statusMessage!!,
                            color = if (statusSuccess) {
                                Color(0xFF72D69A)
                            } else {
                                AppRed
                            },
                            fontSize = 13.sp,
                            lineHeight = 20.sp
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(20.dp))

            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = AppSurface
                ),
                shape = RoundedCornerShape(16.dp)
            ) {
                Column(modifier = Modifier.padding(18.dp)) {
                    Text(
                        "APP UPDATES",
                        color = AppRed,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 2.sp
                    )

                    Spacer(modifier = Modifier.height(10.dp))

                    Text(
                        "Check GitHub for the latest verified version of DCH AI.",
                        color = AppMuted,
                        fontSize = 13.sp,
                        lineHeight = 20.sp
                    )

                    if (availableUpdate != null) {
                        Spacer(modifier = Modifier.height(12.dp))

                        Text(
                            "Available version: ${availableUpdate!!.versionName}",
                            color = Color.White,
                            fontWeight = FontWeight.Bold,
                            fontSize = 14.sp
                        )
                    }

                    Spacer(modifier = Modifier.height(16.dp))

                    OutlinedButton(
                        onClick = {
                            scope.launch {
                                updateChecking = true
                                updateStatus = null
                                updateSuccess = false
                                availableUpdate = null

                                try {
                                    val result = AppUpdateManager.checkForUpdate(
                                        context.applicationContext
                                    )

                                    availableUpdate = result

                                    updateStatus = if (result == null) {
                                        "DCH AI is up to date."
                                    } else {
                                        "Version ${result.versionName} is available."
                                    }

                                    updateSuccess = true
                                } catch (error: Exception) {
                                    updateStatus = error.localizedMessage
                                        ?: "Unable to check for updates."
                                    updateSuccess = false
                                } finally {
                                    updateChecking = false
                                }
                            }
                        },
                        enabled = !updateChecking && !updateInstalling && !updatePending,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(50.dp)
                    ) {
                        if (updateChecking) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(19.dp),
                                color = AppRed,
                                strokeWidth = 2.dp
                            )
                            Spacer(modifier = Modifier.width(10.dp))
                        }

                        Text(
                            if (updateChecking) {
                                "CHECKING FOR UPDATES..."
                            } else {
                                "CHECK FOR UPDATES"
                            },
                            color = Color.White
                        )
                    }

                    if (availableUpdate != null) {
                        Spacer(modifier = Modifier.height(10.dp))

                        Button(
                            onClick = {
                                val selectedUpdate = availableUpdate
                                    ?: return@Button

                                scope.launch {
                                    updateInstalling = true
                                    updateStatus = "Downloading and verifying update..."
                                    updateSuccess = false

                                    try {
                                        val result = withContext(Dispatchers.IO) {
                                            AppUpdateManager.downloadAndInstall(
                                                context.applicationContext,
                                                selectedUpdate
                                            )
                                        }

                                        when (result) {
                                            UpdateInstallResult.PERMISSION_REQUIRED -> {
                                                updateStatus =
                                                    "Allow DCH AI to install unknown apps in Android settings. Return here and tap DOWNLOAD AND INSTALL again."
                                                updateSuccess = false
                                                UpdateInstallState.save(
                                                    context,
                                                    updateStatus!!,
                                                    false
                                                )
                                            }

                                            UpdateInstallResult.INSTALLATION_SUBMITTED -> {
                                                updateStatus =
                                                    UpdateInstallState.message(context)
                                                        ?: "Verified APK submitted. Waiting for Android installation confirmation."
                                                updateSuccess =
                                                    UpdateInstallState.isSuccess(context)

                                                if (UpdateInstallState.message(context) == null) {
                                                    UpdateInstallState.save(
                                                        context,
                                                        updateStatus!!,
                                                        false
                                                    )
                                                }
                                            }
                                        }
                                    } catch (error: Exception) {
                                        updateStatus = error.localizedMessage
                                            ?: "Unable to install the update."
                                        updateSuccess = false
                                        val installationStillPending =
                                            UpdateInstallState.isPending(context)

                                        UpdateInstallState.save(
                                            context,
                                            updateStatus!!,
                                            false,
                                            installationStillPending
                                        )
                                    } finally {
                                        updateInstalling = false
                                    }
                                }
                            },
                            enabled = !updateChecking && !updateInstalling && !updatePending,
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(50.dp),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = AppRed
                            ),
                            shape = RoundedCornerShape(12.dp)
                        ) {
                            if (updateInstalling) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(19.dp),
                                    color = Color.White,
                                    strokeWidth = 2.dp
                                )
                                Spacer(modifier = Modifier.width(10.dp))
                            }

                            Text(
                                if (updateInstalling) {
                                    "PREPARING UPDATE..."
                                } else {
                                    "DOWNLOAD AND INSTALL"
                                },
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }

                    if (updateStatus != null) {
                        Spacer(modifier = Modifier.height(12.dp))

                        Text(
                            updateStatus!!,
                            color = if (updateSuccess) {
                                Color(0xFF72D69A)
                            } else {
                                AppRed
                            },
                            fontSize = 12.sp,
                            lineHeight = 19.sp
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(20.dp))

            Text(
                "SECURITY NOTE",
                color = AppRed,
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = 2.sp
            )

            Spacer(modifier = Modifier.height(8.dp))

            Text(
                "The token is encrypted at rest, but a shared token embedded in an " +
                    "installed app is not completely protected against extraction. " +
                    "Keep it private and rotate it if it is exposed.",
                color = AppMuted,
                fontSize = 12.sp,
                lineHeight = 19.sp
            )
        }
    }
}

@Composable
private fun SettingsHubScreen(
    onBack: () -> Unit,
    onReadingSettings: () -> Unit,
    onGatewaySettings: () -> Unit,
    onDeviceConnect: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(AppBlack)
            .verticalScroll(rememberScrollState())
            .padding(20.dp)
    ) {
        TextButton(onClick = onBack) {
            Text("BACK", color = AppRed)
        }

        Text(
            text = "Settings",
            color = Color.White,
            fontSize = 28.sp,
            fontWeight = FontWeight.Bold
        )

        Text(
            text = "Personalise how Devil's Cute Horns AI works and reads your answers.",
            color = AppMuted,
            fontSize = 14.sp,
            lineHeight = 21.sp,
            modifier = Modifier.padding(top = 8.dp, bottom = 24.dp)
        )

        Card(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(onClick = onReadingSettings),
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(containerColor = AppSurface)
        ) {
            Column(modifier = Modifier.padding(20.dp)) {
                Text(
                    "Reading & Display",
                    color = Color.White,
                    fontSize = 18.sp,
                    fontWeight = FontWeight.SemiBold
                )
                Text(
                    "Voice, accent, speed, pitch, answer formatting, font size and spacing.",
                    color = AppMuted,
                    fontSize = 13.sp,
                    lineHeight = 20.sp,
                    modifier = Modifier.padding(top = 7.dp)
                )
                Text(
                    "CONFIGURE  ›",
                    color = AppRed,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(top = 16.dp)
                )
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        Card(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(onClick = onDeviceConnect),
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(containerColor = AppSurface)
        ) {
            Column(modifier = Modifier.padding(20.dp)) {
                Text(
                    "Device Connect",
                    color = Color.White,
                    fontSize = 18.sp,
                    fontWeight = FontWeight.SemiBold
                )
                Text(
                    "Pair devices and receive completed answers securely.",
                    color = AppMuted,
                    fontSize = 13.sp,
                    lineHeight = 20.sp,
                    modifier = Modifier.padding(top = 7.dp)
                )
                Text(
                    "OPEN  ›",
                    color = AppRed,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(top = 16.dp)
                )
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        Card(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(onClick = onGatewaySettings),
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(containerColor = AppSurface)
        ) {
            Column(modifier = Modifier.padding(20.dp)) {
                Text(
                    "AI Gateway",
                    color = Color.White,
                    fontSize = 18.sp,
                    fontWeight = FontWeight.SemiBold
                )
                Text(
                    "Manage your AI connection, gateway token and app updates.",
                    color = AppMuted,
                    fontSize = 13.sp,
                    lineHeight = 20.sp,
                    modifier = Modifier.padding(top = 7.dp)
                )
                Text(
                    "CONFIGURE  ›",
                    color = AppRed,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(top = 16.dp)
                )
            }
        }
    }
}

@Composable
private fun ReadingSettingsScreen(
    onBack: () -> Unit
) {
    val context = LocalContext.current

    val store = remember(context) {
        DchReadingSettingsStore(context.applicationContext)
    }

    var settings by remember {
        mutableStateOf(store.read())
    }

    val voiceEngine = remember(context) {
        DchVoiceEngine(context.applicationContext)
    }

    var voiceMenuExpanded by remember { mutableStateOf(false) }

    LaunchedEffect(settings) {
        store.save(settings)
        voiceEngine.applySettings(settings)
    }

    DisposableEffect(voiceEngine) {
        onDispose {
            voiceEngine.shutdown()
        }
    }

    val voices = voiceEngine.availableVoices
    val selectedVoice = voices.firstOrNull {
        it.name == settings.voiceName
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(AppBlack)
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 12.dp)
    ) {
        TextButton(onClick = onBack) {
            Text("BACK", color = AppRed)
        }

        Text(
            "Reading & Display",
            color = Color.White,
            fontSize = 25.sp,
            fontWeight = FontWeight.Bold
        )

        Text(
            "Adjust how answers look and sound when read aloud.",
            color = AppMuted,
            fontSize = 13.sp,
            modifier = Modifier.padding(top = 6.dp, bottom = 22.dp)
        )

        Text(
            "VOICE",
            color = AppRed,
            fontWeight = FontWeight.Bold,
            fontSize = 12.sp
        )

        Text(
            "English voice and accent",
            color = Color.White,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(top = 12.dp, bottom = 8.dp)
        )

        Box {
            OutlinedButton(
                onClick = { voiceMenuExpanded = true },
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    selectedVoice?.label ?: if (settings.voiceName.isBlank()) {
                        "System default English voice"
                    } else {
                        "Selected voice unavailable"
                    },
                    color = Color.White
                )
            }

            DropdownMenu(
                expanded = voiceMenuExpanded,
                onDismissRequest = { voiceMenuExpanded = false }
            ) {
                DropdownMenuItem(
                    text = { Text("System default English voice") },
                    onClick = {
                        settings = settings.copy(voiceName = "")
                        voiceMenuExpanded = false
                    }
                )

                voices.forEach { voice ->
                    DropdownMenuItem(
                        text = {
                            Text("${voice.label} (${voice.locale})")
                        },
                        onClick = {
                            settings = settings.copy(voiceName = voice.name)
                            voiceMenuExpanded = false
                        }
                    )
                }
            }
        }

        if (voices.isEmpty()) {
            Text(
                "Loading installed voices. Available voices depend on your Android Text-to-Speech engine.",
                color = AppMuted,
                fontSize = 12.sp,
                lineHeight = 18.sp,
                modifier = Modifier.padding(top = 8.dp)
            )
        }

        Spacer(modifier = Modifier.height(22.dp))

        Text(
            "Speech rate: ${"%.2f".format(settings.speechRate)}",
            color = Color.White,
            fontWeight = FontWeight.SemiBold
        )
        Slider(
            value = settings.speechRate,
            onValueChange = {
                settings = settings.copy(speechRate = it)
            },
            valueRange = 0.25f..1.2f
        )
        Text(
            "Slower speech is easier to follow. Default: 0.82.",
            color = AppMuted,
            fontSize = 12.sp
        )

        Spacer(modifier = Modifier.height(14.dp))

        Text(
            "Voice pitch: ${"%.2f".format(settings.speechPitch)}",
            color = Color.White,
            fontWeight = FontWeight.SemiBold
        )
        Slider(
            value = settings.speechPitch,
            onValueChange = {
                settings = settings.copy(speechPitch = it)
            },
            valueRange = 0.8f..1.2f
        )

        Button(
            onClick = {
                voiceEngine.setText(
                    "This is a voice preview. Your answers will be read at the speed and pitch selected here."
                )
                voiceEngine.play()
            },
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 10.dp),
            colors = ButtonDefaults.buttonColors(containerColor = AppRed)
        ) {
            Text("TEST VOICE", color = Color.White)
        }

        Spacer(modifier = Modifier.height(28.dp))

        Text(
            "ANSWER DISPLAY",
            color = AppRed,
            fontWeight = FontWeight.Bold,
            fontSize = 12.sp
        )

        Text(
            "Answer font size: ${settings.fontSize.toInt()} sp",
            color = Color.White,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(top = 12.dp)
        )
        Slider(
            value = settings.fontSize,
            onValueChange = {
                settings = settings.copy(fontSize = it)
            },
            valueRange = 13f..24f
        )

        Text(
            "Paragraph spacing: ${settings.paragraphSpacing.toInt()} dp",
            color = Color.White,
            fontWeight = FontWeight.SemiBold
        )
        Slider(
            value = settings.paragraphSpacing,
            onValueChange = {
                settings = settings.copy(paragraphSpacing = it)
            },
            valueRange = 8f..32f
        )

        Spacer(modifier = Modifier.height(12.dp))

        HorizontalDivider(color = AppMuted.copy(alpha = 0.25f))

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text("Clean answer formatting", color = Color.White)
                Text(
                    "Remove Markdown decoration while preserving useful content.",
                    color = AppMuted,
                    fontSize = 12.sp,
                    lineHeight = 17.sp
                )
            }
            Switch(
                checked = settings.cleanFormatting,
                onCheckedChange = {
                    settings = settings.copy(cleanFormatting = it)
                }
            )
        }

        HorizontalDivider(color = AppMuted.copy(alpha = 0.25f))

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text("Read question headings", color = Color.White)
                Text(
                    "Include question numbers and headings in spoken answers.",
                    color = AppMuted,
                    fontSize = 12.sp,
                    lineHeight = 17.sp
                )
            }
            Switch(
                checked = settings.includeQuestionHeadings,
                onCheckedChange = {
                    settings = settings.copy(includeQuestionHeadings = it)
                }
            )
        }

        HorizontalDivider(color = AppMuted.copy(alpha = 0.25f))

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text("Repeat each question three times", color = Color.White)
                Text(
                    "Repeat selected question statements during playback.",
                    color = AppMuted,
                    fontSize = 12.sp,
                    lineHeight = 17.sp
                )
            }
            Switch(
                checked = settings.repeatQuestionThreeTimes,
                onCheckedChange = {
                    settings = settings.copy(repeatQuestionThreeTimes = it)
                }
            )
        }

        HorizontalDivider(color = AppMuted.copy(alpha = 0.25f))

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text("Repeat each answer three times", color = Color.White)
                Text(
                    "Repeat every answer part three times for easier listening.",
                    color = AppMuted,
                    fontSize = 12.sp,
                    lineHeight = 17.sp
                )
            }
            Switch(
                checked = settings.repeatAnswerThreeTimes,
                onCheckedChange = {
                    settings = settings.copy(repeatAnswerThreeTimes = it)
                }
            )
        }

        HorizontalDivider(color = AppMuted.copy(alpha = 0.25f))

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text("Bluetooth audio only", color = Color.White)
                Text(
                    "Pause speech when Bluetooth audio disconnects. "
                        + "Requires a connected Bluetooth audio device.",
                    color = AppMuted,
                    fontSize = 12.sp,
                    lineHeight = 17.sp
                )
            }

            Switch(
                checked = settings.bluetoothEarbudsOnly,
                onCheckedChange = {
                    settings = settings.copy(bluetoothEarbudsOnly = it)
                }
            )
        }

        OutlinedButton(
            onClick = {
                try {
                    context.startActivity(
                        android.content.Intent(
                            android.provider.Settings.ACTION_BLUETOOTH_SETTINGS
                        )
                    )
                } catch (_: android.content.ActivityNotFoundException) {
                    android.widget.Toast.makeText(
                        context,
                        "Bluetooth settings are unavailable.",
                        android.widget.Toast.LENGTH_SHORT
                    ).show()
                }
            },
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("OPEN BLUETOOTH PAIRING", color = Color.White)
        }

        Spacer(modifier = Modifier.height(12.dp))

        OutlinedButton(
            onClick = {
                settings = DchReadingSettings()
            },
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 22.dp, bottom = 30.dp)
        ) {
            Text("RESTORE DEFAULTS", color = Color.White)
        }
    }
}

@Composable
private fun OcrReviewScreen(
    sessionId: String,
    repository: SessionRepository,
    onSaved: () -> Unit
) {
    val context = LocalContext.current.applicationContext

    var processing by remember(sessionId) { mutableStateOf(true) }
    var phase by remember(sessionId) {
        mutableStateOf("Preparing your document...")
    }
    var progress by remember(sessionId) { mutableIntStateOf(0) }
    var pageCount by remember(sessionId) { mutableIntStateOf(0) }
    var errorMessage by remember(sessionId) { mutableStateOf<String?>(null) }
    var answerUncertain by remember(sessionId) { mutableStateOf(false) }
    var retryKey by remember(sessionId) { mutableIntStateOf(0) }

    LaunchedEffect(sessionId, retryKey) {
        processing = true
        errorMessage = null
        answerUncertain = false
        phase = "Starting background AI processing..."

        try {
            val initial = withContext(Dispatchers.IO) {
                repository.getSession(sessionId)
            } ?: throw IllegalStateException("The scan session could not be found.")

            if (initial.pageCount == 0) {
                throw IllegalStateException("No captured pages were found.")
            }

            if (initial.answerText.isNotBlank()) {
                onSaved()
                return@LaunchedEffect
            }

            pageCount = initial.pageCount
            progress = initial.successfulPageCount

            DchWorkScheduler.enqueue(context, sessionId)

            val workManager = WorkManager.getInstance(context)
            val uniqueName = DchWorkScheduler.uniqueName(sessionId)

            while (true) {
                val workInfos = withContext(Dispatchers.IO) {
                    workManager.getWorkInfosForUniqueWork(uniqueName).get()
                }

                val info = workInfos.firstOrNull()

                if (info == null) {
                    phase = "Waiting for background processing..."
                    delay(500)
                    continue
                }

                progress = info.progress.getInt("progress", progress)
                pageCount = info.progress.getInt("page_count", pageCount)
                phase = when (info.state) {
                    androidx.work.WorkInfo.State.ENQUEUED ->
                        "Waiting for a network connection or retry delay..."
                    androidx.work.WorkInfo.State.BLOCKED ->
                        "Waiting for another background task..."
                    androidx.work.WorkInfo.State.RUNNING ->
                        info.progress.getString("phase")
                            ?: "Processing your document..."
                    else ->
                        info.progress.getString("phase") ?: phase
                }

                when (info.state) {
                    androidx.work.WorkInfo.State.SUCCEEDED -> {
                        val completed = withContext(Dispatchers.IO) {
                            repository.getSession(sessionId)
                        }

                        if (completed?.answerText.isNullOrBlank()) {
                            throw IllegalStateException(
                                "Processing finished, but no saved answer was found."
                            )
                        }

                        phase = "Answers saved. Opening your results..."
                        onSaved()
                        return@LaunchedEffect
                    }

                    androidx.work.WorkInfo.State.FAILED -> {
                        val failedSession = withContext(Dispatchers.IO) {
                            repository.getSession(sessionId)
                        }

                        answerUncertain =
                            failedSession?.answerStatus == "uncertain"

                        throw IllegalStateException(
                            info.outputData.getString("error")
                                ?: "Background processing failed. Your captured pages are preserved."
                        )
                    }

                    androidx.work.WorkInfo.State.CANCELLED -> {
                        throw IllegalStateException(
                            "Background processing was cancelled. Your saved pages are preserved."
                        )
                    }

                    else -> delay(600)
                }
            }
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            errorMessage = error.localizedMessage
                ?: "Processing failed. Your captured pages have been preserved."
        } finally {
            processing = false
        }
    }

    Surface(
        modifier = Modifier.fillMaxSize(),
        color = AppBlack
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            if (processing) {
                CircularProgressIndicator(
                    modifier = Modifier.size(58.dp),
                    color = AppRed,
                    strokeWidth = 4.dp
                )
            } else {
                Text(
                    if (answerUncertain) "CHECK YOUR SAVED RESULTS"
                    else "PROCESSING NEEDS ATTENTION",
                    color = AppRed,
                    fontWeight = FontWeight.Bold,
                    textAlign = TextAlign.Center
                )
            }

            Spacer(modifier = Modifier.height(28.dp))

            Text(
                "DEVIL'S CUTE HORNS AI",
                color = AppRed,
                fontWeight = FontWeight.Bold,
                letterSpacing = 2.sp
            )

            Spacer(modifier = Modifier.height(12.dp))

            Text(
                phase,
                color = Color.White,
                fontSize = 17.sp,
                fontWeight = FontWeight.SemiBold,
                textAlign = TextAlign.Center
            )

            Spacer(modifier = Modifier.height(12.dp))

            Text(
                if (pageCount > 0) {
                    "$progress of $pageCount pages reconstructed"
                } else {
                    "Your processing task is managed in the background."
                },
                color = AppMuted,
                fontSize = 13.sp,
                textAlign = TextAlign.Center
            )

            if (pageCount > 0) {
                Spacer(modifier = Modifier.height(20.dp))

                LinearProgressIndicator(
                    progress = {
                        (progress.toFloat() / pageCount.coerceAtLeast(1))
                            .coerceIn(0f, 1f)
                    },
                    modifier = Modifier.fillMaxWidth(),
                    color = AppRed
                )
            }

            if (errorMessage != null) {
                Spacer(modifier = Modifier.height(24.dp))

                Text(
                    errorMessage!!,
                    color = AppRed,
                    fontSize = 13.sp,
                    lineHeight = 19.sp,
                    textAlign = TextAlign.Center
                )

                if (answerUncertain) {
                    Spacer(modifier = Modifier.height(12.dp))
                    Text(
                        "The AI may already have processed the answer request. " +
                            "Do not submit it again until you have checked your saved session.",
                        color = AppMuted,
                        fontSize = 12.sp,
                        textAlign = TextAlign.Center
                    )
                } else {
                    Spacer(modifier = Modifier.height(16.dp))

                    Button(
                        onClick = { retryKey++ },
                        enabled = !processing,
                        colors = ButtonDefaults.buttonColors(
                            containerColor = AppRed
                        )
                    ) {
                        Text("RETRY PROCESSING")
                    }
                }
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
    val context = LocalContext.current

    val voiceEngine = remember(context) {
        DchVoiceEngine(context.applicationContext)
    }

    val readingSettingsStore = remember(context) {
        DchReadingSettingsStore(context.applicationContext)
    }

    var readingSettings by remember {
        mutableStateOf(readingSettingsStore.read())
    }

    DisposableEffect(voiceEngine) {
        onDispose {
            DchHardwareKeyRouter.clear()
            voiceEngine.shutdown()
        }
    }

    var session by remember {
        mutableStateOf<ScanSession?>(null)
    }
    var paperText by remember { mutableStateOf("") }
    var loading by remember { mutableStateOf(true) }
    var working by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(sessionId) {
        var initialLoad = true

        while (true) {
            val latest = withContext(Dispatchers.IO) {
                repository.getSession(sessionId)
            }

            session = latest

            if (initialLoad) {
                paperText = latest?.let {
                    if (it.reconstructedText.isNotBlank()) {
                        it.reconstructedText
                    } else {
                        it.extractedText
                    }
                }.orEmpty()

                loading = false
                initialLoad = false
            }

            delay(1500)
        }
    }

    LaunchedEffect(readingSettings) {
        readingSettingsStore.save(readingSettings)
        voiceEngine.applySettings(readingSettings)
    }

    LaunchedEffect(
        session?.questionTextForSpeech,
        session?.answerText,
        readingSettings
    ) {
        voiceEngine.setContent(
            questionText = DchAnswerFormatter.formatForSpeech(
                session?.questionTextForSpeech.orEmpty(),
                includeQuestionHeadings = true,
                cleanFormatting = readingSettings.cleanFormatting
            ),
            answerText = DchAnswerFormatter.formatForDisplay(
                session?.answerText.orEmpty(),
                readingSettings.cleanFormatting
            ),
            includeQuestions = readingSettings.includeQuestionHeadings,
            repeatQuestions = readingSettings.repeatQuestionThreeTimes,
            repeatAnswers = readingSettings.repeatAnswerThreeTimes
        )
    }

    DisposableEffect(voiceEngine, session?.answerText) {
        if (!session?.answerText.isNullOrBlank()) {
            DchHardwareKeyRouter.setPlaybackControls(
                onDownShort = { voiceEngine.next() },
                onDownLong = { voiceEngine.resume() },
                onUpShort = { voiceEngine.replay() },
                onUpLong = { voiceEngine.pause() }
            )
        } else {
            DchHardwareKeyRouter.clear()
        }

        onDispose {
            DchHardwareKeyRouter.clear()
        }
    }

    Surface(
        modifier = Modifier.fillMaxSize(),
        color = AppBlack
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
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
                    Text(
                        "This session could not be found.",
                        color = AppMuted
                    )
                }

                else -> {
                    val currentSession = session!!

                    Text(
                        "SESSION RESULTS",
                        color = AppRed,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 2.sp
                    )

                    Spacer(modifier = Modifier.height(8.dp))

                    Text(
                        "${currentSession.pageCount} scanned page(s)",
                        color = AppMuted
                    )

                    Spacer(modifier = Modifier.height(20.dp))

                    Text(
                        "AI-GENERATED ANSWERS",
                        color = Color.White,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 1.sp
                    )

                    Spacer(modifier = Modifier.height(10.dp))

                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(
                            containerColor = AppSurface
                        )
                    ) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(16.dp)
                        ) {
                            val formattedAnswer =
                                DchAnswerFormatter.formatForDisplay(
                                    currentSession.answerText,
                                    readingSettings.cleanFormatting
                                )

                            if (formattedAnswer.isBlank()) {
                                Text(
                                    "No AI-generated answers are saved for this session yet.",
                                    color = AppMuted,
                                    fontSize = readingSettings.fontSize.sp
                                )
                            } else {
                                formattedAnswer
                                    .split(Regex("\\n\\s*\\n"))
                                    .forEach { paragraph ->
                                        if (paragraph.isNotBlank()) {
                                            Text(
                                                text = paragraph.trim(),
                                                color = Color.White,
                                                fontSize = readingSettings.fontSize.sp,
                                                lineHeight = (readingSettings.fontSize * 1.5f).sp,
                                                modifier = Modifier.padding(
                                                    bottom = readingSettings.paragraphSpacing.dp
                                                )
                                            )
                                        }
                                    }
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(24.dp))

                    Spacer(modifier = Modifier.height(12.dp))

                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(
                            containerColor = AppSurface
                        )
                    ) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(16.dp)
                        ) {
                            Text(
                                "ANSWER VOICE PLAYER",
                                color = Color.White,
                                fontWeight = FontWeight.Bold,
                                letterSpacing = 1.sp
                            )

                            Spacer(modifier = Modifier.height(8.dp))

                            Text(
                                voiceEngine.status,
                                color = AppMuted,
                                fontSize = 13.sp
                            )

                            if (voiceEngine.totalParts > 0) {
                                Spacer(modifier = Modifier.height(4.dp))

                                val progressText = when {
                                    voiceEngine.currentPart >= voiceEngine.totalParts ->
                                        "All parts completed"
                                    voiceEngine.isPlaying ->
                                        "Part ${voiceEngine.currentPart + 1} of ${voiceEngine.totalParts}"
                                    voiceEngine.status == "Paused" ->
                                        "Paused at part ${voiceEngine.currentPart + 1} of ${voiceEngine.totalParts}"
                                    else ->
                                        "${voiceEngine.totalParts} speech part(s)"
                                }

                                Text(
                                    progressText,
                                    color = AppMuted,
                                    fontSize = 12.sp
                                )
                            }

                            Spacer(modifier = Modifier.height(14.dp))

                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Button(
                                    onClick = { voiceEngine.play() },
                                    enabled = currentSession.answerText.isNotBlank(),
                                    modifier = Modifier.weight(1f)
                                ) {
                                    Text("PLAY")
                                }

                                OutlinedButton(
                                    onClick = { voiceEngine.pause() },
                                    enabled = voiceEngine.isPlaying,
                                    modifier = Modifier.weight(1f)
                                ) {
                                    Text("PAUSE")
                                }
                            }

                            Spacer(modifier = Modifier.height(6.dp))

                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                OutlinedButton(
                                    onClick = { voiceEngine.resume() },
                                    enabled = currentSession.answerText.isNotBlank() &&
                                        !voiceEngine.isPlaying,
                                    modifier = Modifier.weight(1f)
                                ) {
                                    Text("RESUME")
                                }

                                OutlinedButton(
                                    onClick = { voiceEngine.replay() },
                                    enabled = currentSession.answerText.isNotBlank(),
                                    modifier = Modifier.weight(1f)
                                ) {
                                    Text("REPLAY")
                                }
                            }

                            Spacer(modifier = Modifier.height(6.dp))

                            OutlinedButton(
                                onClick = { voiceEngine.next() },
                                enabled = voiceEngine.totalParts > 0 &&
                                    voiceEngine.currentPart < voiceEngine.totalParts - 1,
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Text("NEXT PART")
                            }

                            Spacer(modifier = Modifier.height(8.dp))

                            Text(
                                "Volume Down: short press for next part, long press to resume. Volume Up: short press to replay, long press to pause.",
                                color = AppMuted,
                                fontSize = 11.sp,
                                lineHeight = 16.sp
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(24.dp))

                    Text(
                        "RECONSTRUCTED PAPER",
                        color = Color.White,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 1.sp
                    )

                    Spacer(modifier = Modifier.height(8.dp))

                    Text(
                        "You can edit the reconstructed document without changing your AI-generated answers.",
                        color = AppMuted,
                        fontSize = 12.sp
                    )

                    Spacer(modifier = Modifier.height(10.dp))

                    OutlinedTextField(
                        value = paperText,
                        onValueChange = { paperText = it },
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text("Paper text") },
                        minLines = 8
                    )

                    if (message != null) {
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            message!!,
                            color = AppRed,
                            fontSize = 12.sp
                        )
                    }

                    Spacer(modifier = Modifier.height(16.dp))

                    Button(
                        onClick = {
                            scope.launch {
                                working = true
                                message = null

                                val hasAiDocument =
                                    currentSession.reconstructedText.isNotBlank() ||
                                    currentSession.answerText.isNotBlank()

                                val success = withContext(Dispatchers.IO) {
                                    if (hasAiDocument) {
                                        repository.updateReconstructedText(
                                            sessionId,
                                            paperText
                                        )
                                    } else {
                                        repository.updateText(
                                            sessionId,
                                            paperText
                                        )
                                    }
                                }

                                if (success) {
                                    session = withContext(Dispatchers.IO) {
                                        repository.getSession(sessionId)
                                    }
                                    message = "Paper changes saved."
                                } else {
                                    message = "Unable to save paper changes."
                                }

                                working = false
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
                        Text(
                            if (working) "SAVING..." else "SAVE PAPER CHANGES"
                        )
                    }

                    Spacer(modifier = Modifier.height(10.dp))

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

                    Spacer(modifier = Modifier.height(20.dp))
                }
            }
        }
    }
}
