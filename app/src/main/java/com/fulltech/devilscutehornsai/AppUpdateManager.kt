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

object AppUpdateManager {

    private const val PACKAGE_NAME =
        "com.fulltech.devilscutehornsai"

    private const val RELEASES_API =
        "https://api.github.com/repos/Fulltech-Inc-Networks/DevilsCuteHornsAI/releases/latest"

    private const val RELEASE_DOWNLOAD_PREFIX =
        "https://github.com/Fulltech-Inc-Networks/DevilsCuteHornsAI/releases/download/"

    suspend fun checkForUpdate(context: Context): AppUpdate? =
        withContext(Dispatchers.IO) {

            val release = JSONObject(
                requestText(RELEASES_API)
            )

            val assets = release.getJSONArray("assets")

            var manifestUrl: String? = null

            for (i in 0 until assets.length()) {
                val asset = assets.getJSONObject(i)

                if (asset.optString("name") == "latest.json") {
                    manifestUrl = asset.optString("browser_download_url")
                    break
                }
            }

            val url = manifestUrl
                ?: throw IllegalStateException(
                    "The latest release has no update manifest."
                )

            if (!url.startsWith(RELEASE_DOWNLOAD_PREFIX)) {
                throw SecurityException("Untrusted update manifest URL.")
            }

            val manifest = JSONObject(requestText(url))

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

            val apkUrl = RELEASE_DOWNLOAD_PREFIX +
                release.getString("tag_name") + "/" + apkAsset

            val installedVersion = installedVersionCode(context)

            if (versionCode <= installedVersion) {
                return@withContext null
            }

            val releaseAssetExists = (0 until assets.length()).any { i ->
                assets.getJSONObject(i).optString("name") == apkAsset
            }

            if (!releaseAssetExists) {
                throw IllegalStateException(
                    "The APK listed in the update manifest is missing."
                )
            }

            AppUpdate(
                versionCode = versionCode,
                versionName = versionName,
                apkAsset = apkAsset,
                sha256 = sha256,
                downloadUrl = apkUrl
            )
        }

    suspend fun downloadAndInstall(
        context: Context,
        update: AppUpdate
    ) = withContext(Dispatchers.IO) {

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

        if (
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.O &&
            !context.packageManager.canRequestPackageInstalls()
        ) {
            val settingsIntent = Intent(
                android.provider.Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                Uri.parse("package:${context.packageName}")
            ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

            context.startActivity(settingsIntent)

            throw IllegalStateException(
                "Allow DCH AI to install updates, then check for updates again."
            )
        }

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

                session.commit(callback.intentSender)
            }
        } catch (error: Exception) {
            runCatching { installer.abandonSession(sessionId) }
            throw error
        }
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
        val connection = URL(address).openConnection()
            as HttpURLConnection

        try {
            connection.connectTimeout = 15000
            connection.readTimeout = 60000
            connection.setRequestProperty(
                "User-Agent",
                "DCH-AI-Android-Updater"
            )

            if (connection.responseCode !in 200..299) {
                throw IllegalStateException(
                    "APK download failed: HTTP ${connection.responseCode}"
                )
            }

            connection.inputStream.use { input ->
                destination.outputStream().use { output ->
                    input.copyTo(output)
                }
            }
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
