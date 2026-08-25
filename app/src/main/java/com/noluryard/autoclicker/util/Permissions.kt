package com.noluryard.autoclicker.util

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.ContextCompat
import com.noluryard.autoclicker.engine.ClickerAccessibilityService

enum class PermissionId { OVERLAY, ACCESSIBILITY, NOTIFICATIONS }

data class PermissionStatus(
    val id: PermissionId,
    val granted: Boolean,
    val required: Boolean,
)

object Permissions {

    fun canDrawOverlays(context: Context): Boolean = Settings.canDrawOverlays(context)

    fun accessibilityEnabled(context: Context): Boolean =
        ClickerAccessibilityService.isEnabledInSettings(context)

    /** Android 13 oncesinde bildirim izni yok; her zaman verilmis say. */
    fun notificationsGranted(context: Context): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.POST_NOTIFICATIONS,
            ) == PackageManager.PERMISSION_GRANTED
        } else {
            true
        }

    fun snapshot(context: Context): List<PermissionStatus> = listOf(
        PermissionStatus(PermissionId.OVERLAY, canDrawOverlays(context), required = true),
        PermissionStatus(PermissionId.ACCESSIBILITY, accessibilityEnabled(context), required = true),
        // Bildirim izni olmasa da tiklama calisir; sadece kalici bildirim gorunmez.
        PermissionStatus(PermissionId.NOTIFICATIONS, notificationsGranted(context), required = false),
    )

    fun overlaySettingsIntent(context: Context): Intent =
        Intent(
            Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
            Uri.parse("package:${context.packageName}"),
        )

    fun accessibilitySettingsIntent(): Intent =
        Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }

    fun appNotificationSettingsIntent(context: Context): Intent =
        Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).apply {
            putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
}
