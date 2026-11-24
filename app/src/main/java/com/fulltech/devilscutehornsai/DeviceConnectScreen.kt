package com.fulltech.devilscutehornsai

import android.content.Context
import android.os.Build
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.util.UUID
import android.os.Handler
import android.os.Looper
import java.util.concurrent.Executors

data class LocalDeviceIdentity(
    val id: String,
    val name: String
)

class DeviceConnectIdentity(context: Context) {

    private val preferences = context.getSharedPreferences(
        "dch_device_connect",
        Context.MODE_PRIVATE
    )

    @Synchronized
    fun getOrCreate(): LocalDeviceIdentity {
        var id = preferences.getString("device_id", null)
        var name = preferences.getString("device_name", null)

        if (id.isNullOrBlank()) {
            id = UUID.randomUUID().toString()
        }

        if (name.isNullOrBlank()) {
            val manufacturer = Build.MANUFACTURER
                .replaceFirstChar { it.uppercase() }
            name = "$manufacturer ${Build.MODEL}".trim()
        }

        preferences.edit()
            .putString("device_id", id)
            .putString("device_name", name)
            .apply()

        return LocalDeviceIdentity(id, name)
    }
}

@Composable
fun DeviceConnectScreen(
    onBack: () -> Unit
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val identity = remember {
        DeviceConnectIdentity(context.applicationContext).getOrCreate()
    }

    val credentialStore = remember {
        DeviceConnectCredentialStore(context.applicationContext)
    }

    var credentials by remember {
        mutableStateOf(
            runCatching { credentialStore.get() }.getOrNull()
        )
    }
    var registering by remember { mutableStateOf(false) }
    var registrationMessage by remember { mutableStateOf<String?>(null) }

    val serviceConfigured = DeviceConnectServiceConfig.BASE_URL
        .startsWith("https://")

    val registrationExecutor = remember {
        Executors.newSingleThreadExecutor()
    }
    val mainHandler = remember { Handler(Looper.getMainLooper()) }

    DisposableEffect(registrationExecutor) {
        onDispose {
            registrationExecutor.shutdown()
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF090909))
            .padding(20.dp)
    ) {
        TextButton(onClick = onBack) {
            Text("BACK", color = Color(0xFFDC143C))
        }

        Text(
            "Device Connect",
            color = Color.White,
            fontSize = 28.sp,
            fontWeight = FontWeight.Bold
        )

        Text(
            "Connect your devices to receive completed answers securely.",
            color = Color(0xFF999999),
            fontSize = 14.sp,
            lineHeight = 21.sp,
            modifier = Modifier.padding(top = 8.dp, bottom = 24.dp)
        )

        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(
                containerColor = Color(0xFF171717)
            )
        ) {
            Column(modifier = Modifier.padding(20.dp)) {
                Text(
                    "THIS DEVICE",
                    color = Color(0xFFDC143C),
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 1.5.sp
                )

                Spacer(modifier = Modifier.height(12.dp))

                Text(
                    identity.name,
                    color = Color.White,
                    fontSize = 18.sp,
                    fontWeight = FontWeight.SemiBold
                )

                Text(
                    "Device ID: ${credentials?.deviceId ?: identity.id}",
                    color = Color(0xFF999999),
                    fontSize = 12.sp,
                    modifier = Modifier.padding(top = 8.dp)
                )

                Text(
                    if (credentials != null) "REGISTERED • NOT PAIRED"
                    else "NOT REGISTERED",
                    color = Color(0xFFDC143C),
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(top = 16.dp)
                )
            }
        }

        Spacer(modifier = Modifier.height(20.dp))

        Text(
            "SECURE DEVICE SYNCHRONIZATION",
            color = Color.White,
            fontWeight = FontWeight.SemiBold,
            fontSize = 15.sp
        )

        Text(
            "• Share completed answers, not AI provider keys.\n" +
                "• Each device keeps its own local copy.\n" +
                "• Receive answers even when reconnecting later.\n" +
                "• Devices remain independent after delivery.",
            color = Color(0xFF999999),
            fontSize = 13.sp,
            lineHeight = 23.sp,
            modifier = Modifier.padding(top = 10.dp)
        )

        Spacer(modifier = Modifier.height(20.dp))

        Button(
            onClick = {
                registering = true
                registrationMessage = "Registering this device securely..."

                registrationExecutor.execute {
                    val result = runCatching {
                        DeviceConnectRegistrationClient(
                            context.applicationContext,
                            DeviceConnectServiceConfig.BASE_URL
                        ).register(identity.name)
                    }

                    mainHandler.post {
                        registering = false
                        result.onSuccess {
                            credentials = it
                            registrationMessage = "Device registered successfully. Pairing is not yet active."
                        }.onFailure {
                            registrationMessage = it.message
                                ?: "Device registration failed."
                        }
                    }
                }
            },
            enabled = serviceConfigured && credentials == null && !registering,
            modifier = Modifier
                .fillMaxWidth()
                .height(52.dp),
            colors = ButtonDefaults.buttonColors(
                disabledContainerColor = Color(0xFF383838),
                disabledContentColor = Color(0xFF999999)
            ),
            shape = RoundedCornerShape(12.dp)
        ) {
            Text(
                when {
                    registering -> "REGISTERING..."
                    credentials != null -> "DEVICE REGISTERED"
                    else -> "REGISTER THIS DEVICE"
                }
            )
        }

        registrationMessage?.let {
            Text(
                it,
                color = if (credentials != null) Color(0xFF75D69C)
                else Color(0xFFFF8A80),
                fontSize = 12.sp,
                lineHeight = 18.sp,
                modifier = Modifier.padding(top = 10.dp)
            )
        }

        if (!serviceConfigured && credentials == null) {
            Text(
                "Registration will be enabled when the secure synchronization service is configured.",
                color = Color(0xFF777777),
                fontSize = 12.sp,
                lineHeight = 18.sp,
                modifier = Modifier.padding(top = 10.dp)
            )
        }

        Spacer(modifier = Modifier.height(12.dp))

        Button(
            onClick = {},
            enabled = false,
            modifier = Modifier
                .fillMaxWidth()
                .height(52.dp),
            colors = ButtonDefaults.buttonColors(
                disabledContainerColor = Color(0xFF383838),
                disabledContentColor = Color(0xFF999999)
            ),
            shape = RoundedCornerShape(12.dp)
        ) {
            Text("PAIR A DEVICE")
        }

        Text(
            "Pairing will be enabled after secure device registration and the pairing protocol are validated.",
            color = Color(0xFF777777),
            fontSize = 12.sp,
            lineHeight = 18.sp,
            modifier = Modifier.padding(top = 10.dp)
        )
    }
}
