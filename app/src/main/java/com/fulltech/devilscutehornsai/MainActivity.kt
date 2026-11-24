package com.fulltech.devilscutehornsai

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.platform.LocalContext
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat

private val Background = Color(0xFF090909)
private val SurfaceDark = Color(0xFF171717)
private val Crimson = Color(0xFFDC143C)
private val Muted = Color(0xFF999999)

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setContent { DevilTheme { DchApp() } }
    }

    override fun onResume() {
        super.onResume()
        UpdateInstallState.recover(applicationContext)
    }

    override fun onKeyDown(
        keyCode: Int,
        event: android.view.KeyEvent
    ): Boolean {
        if (DchHardwareKeyRouter.onKeyDown(keyCode, event)) {
            return true
        }
        return super.onKeyDown(keyCode, event)
    }

    override fun onKeyUp(
        keyCode: Int,
        event: android.view.KeyEvent
    ): Boolean {
        if (DchHardwareKeyRouter.onKeyUp(keyCode, event)) {
            return true
        }
        return super.onKeyUp(keyCode, event)
    }
}

@Composable
fun DevilTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = Crimson,
            background = Background,
            surface = SurfaceDark,
            onPrimary = Color.White,
            onBackground = Color.White,
            onSurface = Color.White
        ),
        content = content
    )
}

@Composable
fun HomeScreen(
    photoReady: Boolean,
    onCapture: () -> Unit
) {
    val context = LocalContext.current
    val appVersion = remember(context) {
        runCatching {
            context.packageManager
                .getPackageInfo(context.packageName, 0)
                .versionName
        }.getOrNull() ?: "Unknown"
    }

    Surface(
        modifier = Modifier.fillMaxSize(),
        color = Background
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Spacer(modifier = Modifier.height(65.dp))

            Image(
                painter = painterResource(R.drawable.dch_logo),
                contentDescription = "Devil's Cute Horns AI logo",
                modifier = Modifier.size(76.dp),
                contentScale = ContentScale.Fit
            )

            Spacer(modifier = Modifier.height(16.dp))

            Text(
                text = "DEVIL'S",
                color = Color.White,
                fontSize = 17.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = 6.sp
            )

            Text(
                text = "CUTE HORNS AI",
                color = Crimson,
                fontSize = 27.sp,
                fontWeight = FontWeight.Black,
                textAlign = TextAlign.Center
            )

            Spacer(modifier = Modifier.height(10.dp))

            Text(
                text = "Cute Interface. Unholy Intelligence.",
                color = Muted,
                fontSize = 12.sp
            )

            Spacer(modifier = Modifier.height(65.dp))

            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = SurfaceDark
                ),
                shape = RoundedCornerShape(20.dp)
            ) {
                Column(modifier = Modifier.padding(22.dp)) {
                    Text(
                        text = if (photoReady) "PHOTO READY" else "READY WHEN YOU ARE",
                        color = Crimson,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 2.sp
                    )

                    Spacer(modifier = Modifier.height(12.dp))

                    Text(
                        text = "Your personal AI assistant.",
                        color = Color.White,
                        fontSize = 19.sp,
                        fontWeight = FontWeight.Bold
                    )

                    Spacer(modifier = Modifier.height(8.dp))

                    Text(
                        text = if (photoReady) {
                            "Your question sheet has been captured. Text recognition is the next step."
                        } else {
                            "Capture a question sheet, understand its contents, and listen to carefully generated explanations."
                        },
                        color = Muted,
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
                            containerColor = Crimson
                        ),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Text(
                            text = "CAPTURE QUESTION SHEET",
                            fontWeight = FontWeight.Bold,
                            letterSpacing = 1.sp
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.weight(1f))

            Text(
                text = "DCH AI  •  VERSION $appVersion",
                color = Color.DarkGray,
                fontSize = 10.sp,
                letterSpacing = 2.sp
            )
        }
    }
}
