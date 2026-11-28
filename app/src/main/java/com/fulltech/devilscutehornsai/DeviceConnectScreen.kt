package com.fulltech.devilscutehornsai

import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.Looper
import androidx.compose.foundation.background
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.util.UUID
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

        if (id.isNullOrBlank()) id = UUID.randomUUID().toString()

        if (name.isNullOrBlank()) {
            val manufacturer = Build.MANUFACTURER.replaceFirstChar {
                it.uppercase()
            }
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
fun DeviceConnectScreen(onBack: () -> Unit) {
    val context = androidx.compose.ui.platform.LocalContext.current

    val identity = remember {
        DeviceConnectIdentity(context.applicationContext).getOrCreate()
    }

    val credentialStore = remember {
        DeviceConnectCredentialStore(context.applicationContext)
    }

    var credentials by remember {
        mutableStateOf(runCatching { credentialStore.get() }.getOrNull())
    }

    var registering by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    var invitationCode by remember { mutableStateOf("") }
    var claimCode by remember { mutableStateOf("") }
    var pairedDevices by remember { mutableStateOf<List<String>>(emptyList()) }

    val serviceConfigured = DeviceConnectServiceConfig.BASE_URL
        .startsWith("https://")

    val executor = remember { Executors.newSingleThreadExecutor() }
    val mainHandler = remember { Handler(Looper.getMainLooper()) }

    DisposableEffect(executor) {
        onDispose { executor.shutdownNow() }
    }

    fun runPairing(task: () -> Unit) {
        busy = true
        message = "Working..."
        executor.execute {
            val result = runCatching(task)
            mainHandler.post {
                busy = false
                message = result.fold(
                    onSuccess = { "Pairing operation completed." },
                    onFailure = {
                        it.message ?: "Pairing operation failed."
                    }
                )
            }
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF090909))
            .verticalScroll(rememberScrollState())
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
            "Connect devices to receive completed answers securely.",
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
            Column(Modifier.padding(20.dp)) {
                Text(
                    "THIS DEVICE",
                    color = Color(0xFFDC143C),
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 1.5.sp
                )

                Spacer(Modifier.height(12.dp))

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
                    if (credentials != null) "REGISTERED"
                    else "NOT REGISTERED",
                    color = Color(0xFFDC143C),
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(top = 16.dp)
                )
            }
        }

        Spacer(Modifier.height(20.dp))

        Text(
            "SECURE DEVICE SYNCHRONIZATION",
            color = Color.White,
            fontWeight = FontWeight.SemiBold,
            fontSize = 15.sp
        )

        Text(
            "• Share completed answers, not provider keys.\n" +
                "• Each device keeps its own local copy.\n" +
                "• Offline devices can receive answers later.\n" +
                "• Pairing is authorized by both devices.",
            color = Color(0xFF999999),
            fontSize = 13.sp,
            lineHeight = 23.sp,
            modifier = Modifier.padding(top = 10.dp)
        )

        Spacer(Modifier.height(20.dp))

        Button(
            onClick = {
                registering = true
                message = "Registering this device securely..."

                executor.execute {
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
                            message = "Device registered successfully."
                        }.onFailure {
                            message = it.message ?: "Registration failed."
                        }
                    }
                }
            },
            enabled = serviceConfigured && credentials == null &&
                !registering && !busy,
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

        Spacer(Modifier.height(12.dp))

        Text(
            "PAIR A DEVICE",
            color = Color.White,
            fontSize = 15.sp,
            fontWeight = FontWeight.SemiBold
        )

        Button(
            onClick = {
                busy = true
                message = "Creating pairing invitation..."

                executor.execute {
                    val result = runCatching {
                        val client = DeviceConnectPairingClient(
                            context.applicationContext,
                            DeviceConnectServiceConfig.BASE_URL
                        )

                        val invitation = client.createInvitation()

                        mainHandler.post {
                            invitationCode = invitation.code
                            message = "Invitation ready. Share it with the other device. Waiting for its claim..."
                        }

                        client.waitForClaimAndConfirm(invitation.sessionId) { status ->
                            mainHandler.post {
                                message = status
                            }
                        }

                        val pairingResult = client.listActivePairings()
                        val array = pairingResult.getJSONArray("pairings")
                        val devices = (0 until array.length()).map { index ->
                            val item = array.getJSONObject(index)
                            "${item.optString("deviceName")} (${item.optString("deviceId")})"
                        }

                        mainHandler.post {
                            pairedDevices = devices
                            DeviceConnectSyncScheduler.enqueueReceiverNow(
                                context.applicationContext
                            )
                        }
                    }

                    mainHandler.post {
                        busy = false
                        result.onFailure {
                            message = it.message ?: "Pairing failed."
                        }
                    }
                }
            },
            enabled = serviceConfigured && credentials != null && !busy,
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 10.dp)
                .height(50.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = Color(0xFFDC143C)
            ),
            shape = RoundedCornerShape(12.dp)
        ) {
            Text("CREATE PAIRING INVITATION")
        }

        if (invitationCode.isNotBlank()) {
            Text(
                "SHARE THIS INVITATION CODE",
                color = Color(0xFFDC143C),
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(top = 14.dp)
            )

            SelectionContainer {
                Text(
                    invitationCode,
                    color = Color.White,
                    fontSize = 12.sp,
                    lineHeight = 18.sp,
                    modifier = Modifier.padding(top = 6.dp)
                )
            }

            Text(
                "The invitation expires after five minutes.",
                color = Color(0xFF999999),
                fontSize = 11.sp,
                modifier = Modifier.padding(top = 6.dp)
            )
        }

        Button(
            onClick = {
                runPairing {
                    val result = DeviceConnectPairingClient(
                        context.applicationContext,
                        DeviceConnectServiceConfig.BASE_URL
                    ).listActivePairings()

                    val names = result.getJSONArray("pairings")
                    val devices = (0 until names.length()).map { index ->
                        val item = names.getJSONObject(index)
                        "${item.optString("deviceName")} (${item.optString("deviceId")})"
                    }

                    mainHandler.post {
                        pairedDevices = devices
                        message = if (devices.isEmpty()) {
                            "No active paired devices yet."
                        } else {
                            "Active paired devices refreshed."
                        }
                    }
                }
            },
            enabled = serviceConfigured && credentials != null && !busy,
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 10.dp)
                .height(48.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = Color(0xFF292929)
            ),
            shape = RoundedCornerShape(12.dp)
        ) {
            Text("REFRESH PAIRED DEVICES")
        }

        if (pairedDevices.isNotEmpty()) {
            Text(
                "ACTIVE PAIRED DEVICES",
                color = Color(0xFFDC143C),
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(top = 14.dp)
            )

            pairedDevices.forEach { device ->
                SelectionContainer {
                    Text(
                        device,
                        color = Color.White,
                        fontSize = 12.sp,
                        modifier = Modifier.padding(top = 7.dp)
                    )
                }
            }
        }

        OutlinedTextField(
            value = claimCode,
            onValueChange = { claimCode = it },
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 16.dp),
            enabled = credentials != null && !busy,
            label = { Text("Enter invitation code") },
            placeholder = { Text("session.secret.publicKey.expiry") },
            singleLine = false,
            minLines = 2,
            colors = OutlinedTextFieldDefaults.colors(
                focusedTextColor = Color.White,
                unfocusedTextColor = Color.White,
                focusedBorderColor = Color(0xFFDC143C),
                unfocusedBorderColor = Color(0xFF555555),
                focusedLabelColor = Color(0xFFDC143C),
                unfocusedLabelColor = Color(0xFF999999)
            )
        )

        Button(
            onClick = {
                busy = true
                message = "Claiming pairing invitation..."

                executor.execute {
                    val result = runCatching {
                        val client = DeviceConnectPairingClient(
                            context.applicationContext,
                            DeviceConnectServiceConfig.BASE_URL
                        )

                        val claim = client.claimInvitation(claimCode)

                        mainHandler.post {
                            message = "Invitation claimed from ${claim.initiatorDeviceName}. Waiting for confirmation..."
                        }

                        client.waitForPairing(claim.initiatorDeviceId) { status ->
                            mainHandler.post {
                                message = status
                            }
                        }

                        val pairingResult = client.listActivePairings()
                        val array = pairingResult.getJSONArray("pairings")
                        val devices = (0 until array.length()).map { index ->
                            val item = array.getJSONObject(index)
                            "${item.optString("deviceName")} (${item.optString("deviceId")})"
                        }

                        mainHandler.post {
                            pairedDevices = devices
                            DeviceConnectSyncScheduler.enqueueReceiverNow(
                                context.applicationContext
                            )
                        }
                    }

                    mainHandler.post {
                        busy = false
                        result.onFailure {
                            message = it.message ?: "Pairing failed."
                        }
                    }
                }
            },
            enabled = serviceConfigured && credentials != null &&
                claimCode.isNotBlank() && !busy,
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 10.dp)
                .height(50.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = Color(0xFF292929)
            ),
            shape = RoundedCornerShape(12.dp)
        ) {
            Text("CLAIM INVITATION")
        }

        message?.let {
            Text(
                it,
                color = if (it.contains("failed", true) ||
                    it.contains("expired", true)) {
                    Color(0xFFFF8A80)
                } else {
                    Color(0xFF75D69C)
                },
                fontSize = 12.sp,
                lineHeight = 18.sp,
                modifier = Modifier.padding(top = 12.dp)
            )
        }

        if (!serviceConfigured) {
            Text(
                "The synchronization service URL has not been configured yet.",
                color = Color(0xFF777777),
                fontSize = 12.sp,
                modifier = Modifier.padding(top = 12.dp)
            )
        }
    }
}
