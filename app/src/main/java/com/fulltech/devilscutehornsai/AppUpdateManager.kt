package com.fulltech.devilscutehornsai

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.content.pm.PackageInstaller
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject

data class AppUpdate(
    val versionCode: Long,
    val versionName: String,
    val apkAsset: String,
    val sha256: String,
    val downloadUrl: String
)

enum class UpdateInstallResult {
    PERMISSION_REQUIRED,
    INSTALLATION_SUBMITTED
}

object AppUpdateManager {

    private const val PACKAGE_NAME =
        "com.fulltech.devilscutehornsai"

    private const val LATEST_MANIFEST_URL =
        "https://github.com/Fulltech-Inc-Networks/DevilsCuteHornsAI/releases/latest/download/latest.json"

    private const val LATEST_APK_DOWNLOAD_PREFIX =
        "https://github.com/Fulltech-Inc-Networks/DevilsCuteHornsAI/releases/latest/download/"

    suspend fun checkForUpdate(context: Context): AppUpdate? =
        withContext(Dispatchers.IO) {

            val manifest = JSONObject(
                requestText(LATEST_MANIFEST_URL)
            )

            val versionCode = manifest.getLong("versionCode")
            val versionName = manifest.getString("versionName")
            val apkAsset = manifest.getString("apkAsset")
            val sha256 = manifest.getString("sha256").lowercase()

            require(versionCode > 0) {
                "Invalid update version code."
            }

            require(versionName.isNotBlank()) {
                "Invalid update version name."
            }

            require(
                apkAsset.matches(
                    Regex("[A-Za-z0-9._-]+\\.apk")
                )
            ) {
                "Invalid APK asset name."
            }

            require(sha256.matches(Regex("[a-f0-9]{64}"))) {
                "Invalid APK checksum."
            }

            val installedVersion = installedVersionCode(context)

            if (versionCode <= installedVersion) {
                return@withContext null
            }

            AppUpdate(
                versionCode = versionCode,
                versionName = versionName,
                apkAsset = apkAsset,
                sha256 = sha256,
                downloadUrl = LATEST_APK_DOWNLOAD_PREFIX + apkAsset
            )
        }

    suspend fun downloadAndInstall(
        context: Context,
        update: AppUpdate
    ): UpdateInstallResult = withContext(Dispatchers.IO) {

        check(!UpdateInstallState.isPending(context)) {
            "An installation is already awaiting Android confirmation."
        }

        if (
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.O &&
            !context.packageManager.canRequestPackageInstalls()
        ) {
            val settingsIntent = Intent(
                android.provider.Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                Uri.parse("package:${context.packageName}")
            ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

            context.startActivity(settingsIntent)
            return@withContext UpdateInstallResult.PERMISSION_REQUIRED
        }

        val directory = File(context.cacheDir, "updates")

        if (!directory.exists() && !directory.mkdirs()) {
            throw IllegalStateException(
                "Unable to create the update directory."
            )
        }

        val apk = File(directory, update.apkAsset)

        downloadFile(update.downloadUrl, apk)

        val actualHash = sha256(apk)

        if (!actualHash.equals(update.sha256, ignoreCase = true)) {
            apk.delete()
            throw SecurityException(
                "APK checksum verification failed. Installation blocked."
            )
        }

        verifyApk(context, apk, update.versionCode)

        val installer = context.packageManager.packageInstaller

        val params = PackageInstaller.SessionParams(
            PackageInstaller.SessionParams.MODE_FULL_INSTALL
        )

        val sessionId = installer.createSession(params)

        try {
            installer.openSession(sessionId).use { session ->
                apk.inputStream().use { input ->
                    session.openWrite(
                        "dch-ai-update.apk",
                        0,
                        apk.length()
                    ).use { output ->
                        input.copyTo(output)
                        session.fsync(output)
                    }
                }

                val callbackIntent = Intent(
                    context,
                    UpdateInstallReceiver::class.java
                )

                val callback = PendingIntent.getBroadcast(
                    context,
                    sessionId,
                    callbackIntent,
                    PendingIntent.FLAG_UPDATE_CURRENT or
                        PendingIntent.FLAG_MUTABLE
                )

                check(
                    UpdateInstallState.save(
                        context,
                        "Waiting for Android installation confirmation.",
                        false,
                        true,
                        sessionId,
                        update.versionCode
                    )
                ) {
                    "Unable to persist the installation session."
                }

                session.commit(callback.intentSender)
            }
        } catch (error: Exception) {
            runCatching { installer.abandonSession(sessionId) }

            UpdateInstallState.save(
                context,
                "Unable to submit the update: ${
                    error.localizedMessage ?: "Unknown installation error."
                }",
                false,
                false
            )

            throw error
        }

        UpdateInstallResult.INSTALLATION_SUBMITTED
    }

    private fun verifyApk(
        context: Context,
        apk: File,
        expectedVersionCode: Long
    ) {
        val pm = context.packageManager

        val flags = if (Build.VERSION.SDK_INT >= 28) {
            PackageManager.GET_SIGNING_CERTIFICATES
        } else {
            @Suppress("DEPRECATION")
            PackageManager.GET_SIGNATURES
        }

        val archive = pm.getPackageArchiveInfo(apk.absolutePath, flags)
            ?: throw SecurityException("Downloaded APK is invalid.")

        if (archive.packageName != PACKAGE_NAME) {
            throw SecurityException("APK package name does not match DCH AI.")
        }

        val archiveVersion = if (Build.VERSION.SDK_INT >= 28) {
            archive.longVersionCode
        } else {
            @Suppress("DEPRECATION")
            archive.versionCode.toLong()
        }

        if (archiveVersion != expectedVersionCode) {
            throw SecurityException("APK version does not match its manifest.")
        }

        val installed = pm.getPackageInfo(PACKAGE_NAME, flags)

        if (signerDigests(archive) != signerDigests(installed)) {
            throw SecurityException(
                "APK signing certificate does not match the installed app."
            )
        }
    }

    private fun signerDigests(info: PackageInfo): Set<String> {
        val signatures = if (Build.VERSION.SDK_INT >= 28) {
            info.signingInfo?.apkContentsSigners
        } else {
            @Suppress("DEPRECATION")
            info.signatures
        } ?: emptyArray()

        if (signatures.isEmpty()) {
            throw SecurityException("APK signing information is unavailable.")
        }

        return signatures.map { signature ->
            MessageDigest.getInstance("SHA-256")
                .digest(signature.toByteArray())
                .joinToString("") { "%02x".format(it) }
        }.toSet()
    }

    private fun installedVersionCode(context: Context): Long {
        val info = context.packageManager.getPackageInfo(
            context.packageName,
            0
        )

        return if (Build.VERSION.SDK_INT >= 28) {
            info.longVersionCode
        } else {
            @Suppress("DEPRECATION")
            info.versionCode.toLong()
        }
    }

    private fun requestText(address: String): String {
        val connection = URL(address).openConnection()
            as HttpURLConnection

        try {
            connection.connectTimeout = 15000
            connection.readTimeout = 30000
            connection.setRequestProperty(
                "User-Agent",
                "DCH-AI-Android-Updater"
            )
            connection.setRequestProperty(
                "Accept",
                "application/vnd.github+json"
            )

            if (connection.responseCode !in 200..299) {
                throw IllegalStateException(
                    "GitHub update check failed: HTTP ${connection.responseCode}"
                )
            }

            return connection.inputStream.bufferedReader().use {
                it.readText()
            }
        } finally {
            connection.disconnect()
        }
    }

    private fun downloadFile(address: String, destination: File) {
        val temporary = File(destination.parentFile, destination.name + ".part")
        if (temporary.exists() && !temporary.delete()) {
            throw IllegalStateException("Unable to clear the previous partial download.")
        }

        val connection = URL(address).openConnection() as HttpURLConnection

        try {
            connection.connectTimeout = 15000
            connection.readTimeout = 60000
            connection.instanceFollowRedirects = true
            connection.useCaches = false
            connection.setRequestProperty(
                "User-Agent",
                "DCH-AI-Android-Updater"
            )

            val status = connection.responseCode
            if (status !in 200..299) {
                throw IllegalStateException("APK download failed: HTTP $status")
            }

            val expectedLength = connection.contentLengthLong
            var downloaded = 0L

            connection.inputStream.use { input ->
                java.io.FileOutputStream(temporary).use { output ->
                    val buffer = ByteArray(16 * 1024)
                    while (true) {
                        val count = input.read(buffer)
                        if (count < 0) break
                        output.write(buffer, 0, count)
                        downloaded += count
                    }
                    output.fd.sync()
                }
            }

            if (downloaded <= 0L ||
                (expectedLength > 0L && downloaded != expectedLength)
            ) {
                throw IllegalStateException(
                    "APK download was incomplete. Please try again."
                )
            }

            try {
                java.nio.file.Files.move(
                    temporary.toPath(),
                    destination.toPath(),
                    java.nio.file.StandardCopyOption.REPLACE_EXISTING,
                    java.nio.file.StandardCopyOption.ATOMIC_MOVE
                )
            } catch (_: java.nio.file.AtomicMoveNotSupportedException) {
                java.nio.file.Files.move(
                    temporary.toPath(),
                    destination.toPath(),
                    java.nio.file.StandardCopyOption.REPLACE_EXISTING
                )
            }
        } catch (error: Exception) {
            temporary.delete()
            throw error
        } finally {
            connection.disconnect()
        }
    }

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")

        file.inputStream().buffered().use { input ->
            val buffer = ByteArray(8192)

            while (true) {
                val count = input.read(buffer)
                if (count == -1) break
                digest.update(buffer, 0, count)
            }
        }

        return digest.digest().joinToString("") {
            "%02x".format(it)
        }
    }
}
