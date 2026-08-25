package com.noluryard.autoclicker.ui

import android.Manifest
import android.content.Context
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.PriorityHigh
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.noluryard.autoclicker.ui.theme.Danger
import com.noluryard.autoclicker.ui.theme.Ok
import com.noluryard.autoclicker.ui.theme.Warn
import com.noluryard.autoclicker.util.PermissionId
import com.noluryard.autoclicker.util.PermissionStatus
import com.noluryard.autoclicker.util.Permissions

/**
 * Izinlerin canli durumu.
 *
 * Ayarlar ekranindan donuldugunde durum degisir ama sistem bize bunu bildirmez;
 * bu yuzden ON_RESUME'da yeniden okuyoruz.
 */
@Composable
fun rememberPermissionStates(): List<PermissionStatus> {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    var states by remember { mutableStateOf(Permissions.snapshot(context)) }

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                states = Permissions.snapshot(context)
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    return states
}

@Composable
fun PermissionSection(states: List<PermissionStatus>) {
    val context = LocalContext.current

    val notificationLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { /* Sonuc ON_RESUME'da zaten yeniden okunuyor. */ }

    SectionCard(
        title = "1 · İzinler",
        subtitle = "Sırayla ver. Kırmızı olanlar olmadan tıklayıcı çalışmaz.",
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            states.forEachIndexed { index, status ->
                PermissionRow(
                    step = index + 1,
                    status = status,
                    onGrant = {
                        when (status.id) {
                            PermissionId.OVERLAY ->
                                context.startActivity(Permissions.overlaySettingsIntent(context))

                            PermissionId.ACCESSIBILITY ->
                                context.startActivity(Permissions.accessibilitySettingsIntent())

                            PermissionId.NOTIFICATIONS -> requestNotifications(
                                context = context,
                                launch = { notificationLauncher.launch(it) },
                            )
                        }
                    },
                )
            }
        }
    }
}

private fun requestNotifications(context: Context, launch: (String) -> Unit) {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        launch(Manifest.permission.POST_NOTIFICATIONS)
    } else {
        context.startActivity(Permissions.appNotificationSettingsIntent(context))
    }
}

@Composable
private fun PermissionRow(step: Int, status: PermissionStatus, onGrant: () -> Unit) {
    val granted = status.granted
    // Verilmeyen zorunlu izin kirmizi; zorunlu olmayan (bildirim) turuncu.
    val accent = when {
        granted -> Ok
        status.required -> Danger
        else -> Warn
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .border(1.dp, accent.copy(alpha = 0.55f), RoundedCornerShape(14.dp))
            .background(accent.copy(alpha = 0.08f), RoundedCornerShape(14.dp))
            .padding(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(28.dp)
                .background(accent, CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            if (granted) {
                Icon(Icons.Filled.Check, contentDescription = null, tint = Color.Black, modifier = Modifier.size(18.dp))
            } else {
                Icon(Icons.Filled.PriorityHigh, contentDescription = null, tint = Color.Black, modifier = Modifier.size(18.dp))
            }
        }

        Spacer(Modifier.width(12.dp))

        Column(Modifier.weight(1f)) {
            Text(
                "$step. ${titleFor(status.id)}",
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold,
            )
            Spacer(Modifier.height(2.dp))
            Text(
                if (granted) "Verildi" else descriptionFor(status.id),
                style = MaterialTheme.typography.bodySmall,
                color = if (granted) accent else MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        if (!granted) {
            TextButton(onClick = onGrant) { Text("Ver") }
        }
    }
}

private fun titleFor(id: PermissionId): String = when (id) {
    PermissionId.OVERLAY -> "Diğer uygulamaların üzerinde göster"
    PermissionId.ACCESSIBILITY -> "Erişilebilirlik servisi"
    PermissionId.NOTIFICATIONS -> "Bildirim izni (önerilir)"
}

private fun descriptionFor(id: PermissionId): String = when (id) {
    PermissionId.OVERLAY -> "Yüzen balonun oyunun üzerinde durabilmesi için gerekli."
    PermissionId.ACCESSIBILITY -> "Ayarlar → Erişilebilirlik → Auto Clicker → Aç. Tıklamaların gönderilmesi için zorunlu."
    PermissionId.NOTIFICATIONS -> "Kalıcı bildirim ve 'Durdur' kısayolu için. Olmadan da çalışır."
}
