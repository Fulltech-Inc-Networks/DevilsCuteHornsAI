package com.fulltech.devilscutehornsai

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.os.Build
import android.widget.Toast

object UpdateInstallState {
    private const val PREFS = "dch_update_install"
    private const val KEY_MESSAGE = "message"
    private const val KEY_SUCCESS = "success"
    private const val KEY_PENDING = "pending"
    private const val KEY_SESSION_ID = "session_id"
    private const val KEY_EXPECTED_VERSION = "expected_version_code"

    fun save(
        context: Context,
        message: String,
        success: Boolean,
        pending: Boolean = false,
        sessionId: Int? = null,
        expectedVersionCode: Long? = null
    ): Boolean {
        val editor = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_MESSAGE, message)
            .putBoolean(KEY_SUCCESS, success)
            .putBoolean(KEY_PENDING, pending)

        if (!pending) {
            editor.putInt(KEY_SESSION_ID, -1)
            editor.putLong(KEY_EXPECTED_VERSION, -1L)
        } else {
            if (sessionId != null) editor.putInt(KEY_SESSION_ID, sessionId)
            if (expectedVersionCode != null) {
                editor.putLong(KEY_EXPECTED_VERSION, expectedVersionCode)
            }
        }

        // Persist before Android receives the installation commit.
        return editor.commit()
    }

    fun isPending(context: Context): Boolean = recover(context)

    fun recover(context: Context): Boolean {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (!prefs.getBoolean(KEY_PENDING, false)) return false

        val expectedVersion = prefs.getLong(KEY_EXPECTED_VERSION, -1L)
        val sessionId = prefs.getInt(KEY_SESSION_ID, -1)

        val installedVersion = runCatching {
            val info = context.packageManager.getPackageInfo(
                context.packageName,
                0
            )
            if (Build.VERSION.SDK_INT >= 28) {
                info.longVersionCode
            } else {
                @Suppress("DEPRECATION")
                info.versionCode.toLong()
            }
        }.getOrElse {
            // Never unlock while Android's installation outcome is uncertain.
            return true
        }

        if (expectedVersion > 0 && installedVersion >= expectedVersion) {
            save(
                context,
                "DCH AI update installed successfully.",
                true
            )
            return false
        }

        if (sessionId < 0) {
            save(
                context,
                "The previous update session could not be recovered. You can check for updates again.",
                false
            )
            return false
        }

        val sessionExists = runCatching {
            context.packageManager.packageInstaller
                .getSessionInfo(sessionId) != null
        }.getOrElse {
            return true
        }

        if (!sessionExists) {
            save(
                context,
                "The previous update session ended without completing the expected installation. You can check for updates again.",
                false
            )
            return false
        }

        return true
    }

    fun abandonPendingSession(context: Context) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val sessionId = prefs.getInt(KEY_SESSION_ID, -1)
        if (sessionId >= 0) {
            runCatching {
                context.packageManager.packageInstaller.abandonSession(sessionId)
            }
        }
    }

    fun message(context: Context): String? =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_MESSAGE, null)

    fun isSuccess(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getBoolean(KEY_SUCCESS, false)

    fun clear(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .clear()
            .commit()
    }
}

class UpdateInstallReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (
            intent.getIntExtra(
                PackageInstaller.EXTRA_STATUS,
                PackageInstaller.STATUS_FAILURE
            )
        ) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                UpdateInstallState.save(
                    context,
                    "Waiting for Android installation confirmation.",
                    false,
                    true
                )

                val confirmation = if (Build.VERSION.SDK_INT >= 33) {
                    intent.getParcelableExtra(
                        Intent.EXTRA_INTENT,
                        Intent::class.java
                    )
                } else {
                    @Suppress("DEPRECATION")
                    intent.getParcelableExtra(Intent.EXTRA_INTENT)
                }

                if (confirmation != null) {
                    try {
                        confirmation.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        context.startActivity(confirmation)
                    } catch (error: Exception) {
                        UpdateInstallState.abandonPendingSession(context)
                        UpdateInstallState.save(
                            context,
                            "Unable to open Android's installation confirmation. The installation session was cancelled. Please try again.",
                            false
                        )
                    }
                } else {
                    UpdateInstallState.abandonPendingSession(context)
                    UpdateInstallState.save(
                        context,
                        "Android did not provide an installation confirmation screen. The installation session was cancelled.",
                        false
                    )
                }
            }

            PackageInstaller.STATUS_SUCCESS -> {
                UpdateInstallState.save(
                    context,
                    "DCH AI update installed successfully.",
                    true
                )
                Toast.makeText(
                    context,
                    "DCH AI update installed successfully.",
                    Toast.LENGTH_LONG
                ).show()
            }

            PackageInstaller.STATUS_FAILURE_ABORTED -> {
                UpdateInstallState.save(
                    context,
                    "Installation was cancelled.",
                    false
                )
            }

            else -> {
                val message = intent.getStringExtra(
                    PackageInstaller.EXTRA_STATUS_MESSAGE
                ) ?: "Installation was not completed."

                UpdateInstallState.save(
                    context,
                    "Update installation failed: $message",
                    false
                )
                Toast.makeText(
                    context,
                    message,
                    Toast.LENGTH_LONG
                ).show()
            }
        }
    }
}
