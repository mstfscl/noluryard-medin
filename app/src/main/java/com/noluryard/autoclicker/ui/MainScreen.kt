package com.noluryard.autoclicker.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.text.KeyboardOptions
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.noluryard.autoclicker.data.ClickPattern
import com.noluryard.autoclicker.data.ClickerSettings
import com.noluryard.autoclicker.data.SettingsBounds
import com.noluryard.autoclicker.engine.ClickerController
import com.noluryard.autoclicker.engine.StopReason
import com.noluryard.autoclicker.overlay.OverlayService
import com.noluryard.autoclicker.util.Formatting
import com.noluryard.autoclicker.util.PermissionId

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(viewModel: MainViewModel) {
    val context = LocalContext.current
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val stats by viewModel.stats.collectAsStateWithLifecycle()
    val calibration by viewModel.calibration.collectAsStateWithLifecycle()
    val permissions = rememberPermissionStates()

    val snackbarHostState = remember { SnackbarHostState() }
    LaunchedEffect(Unit) {
        viewModel.messages.collect { snackbarHostState.showSnackbar(it) }
    }

    val overlayGranted = permissions.first { it.id == PermissionId.OVERLAY }.granted
    val accessibilityGranted = permissions.first { it.id == PermissionId.ACCESSIBILITY }.granted
    val ready = overlayGranted && accessibilityGranted
    val active = stats.running || stats.countingDown

    var clickLimitDialog by remember { mutableStateOf(false) }
    var timeLimitDialog by remember { mutableStateOf(false) }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = { TopAppBar(title = { Text("Auto Clicker") }) },
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            item { PermissionSection(permissions) }

            item {
                LiveStatusCard(
                    settings = settings,
                    statsLine = Formatting.counterLine(stats),
                    speedLine = Formatting.speedLine(stats),
                    clickProgress = stats.clickProgress,
                    timeProgress = stats.timeProgress,
                    countdownMs = if (stats.countingDown) stats.countdownMsLeft else null,
                    active = active,
                    ready = ready,
                    onToggle = {
                        if (active) ClickerController.stop(StopReason.MANUAL)
                        else ClickerController.start(context)
                    },
                    onShowBubble = { OverlayService.start(context) },
                    onPickTargets = { OverlayService.startPicker(context) },
                )
            }

            item {
                SpeedSection(
                    settings = settings,
                    calibrationRunning = calibration.running,
                    calibrationProgress = calibration.progress,
                    calibrationStep = calibration.stepLabel,
                    onUpdate = viewModel::update,
                    onCalibrate = { ClickerController.calibrate(context) },
                    canCalibrate = ready && !active,
                )
            }

            item {
                StopConditionSection(
                    settings = settings,
                    onUpdate = viewModel::update,
                    onCustomClicks = { clickLimitDialog = true },
                    onCustomTime = { timeLimitDialog = true },
                )
            }

            item { TouchSection(settings = settings, onUpdate = viewModel::update) }

            item {
                TargetSection(
                    settings = settings,
                    onUpdate = viewModel::update,
                    onPickTargets = { OverlayService.startPicker(context) },
                    canPick = overlayGranted,
                )
            }

            item { SafetySection(settings = settings, onUpdate = viewModel::update) }

            item { Spacer(Modifier.height(24.dp)) }
        }
    }

    if (clickLimitDialog) {
        NumberDialog(
            title = "Özel tıklama sayısı",
            hint = "1 - ${SettingsBounds.MAX_CLICK_LIMIT}",
            initial = settings.clickLimit.takeIf { it > 0 }?.toString() ?: "",
            range = 1..SettingsBounds.MAX_CLICK_LIMIT,
            onDismiss = { clickLimitDialog = false },
            onConfirm = { value ->
                viewModel.update { it.copy(clickLimit = value) }
                clickLimitDialog = false
            },
        )
    }

    if (timeLimitDialog) {
        NumberDialog(
            title = "Özel süre (saniye)",
            hint = "1 - ${SettingsBounds.MAX_TIME_LIMIT_SEC}",
            initial = settings.timeLimitSec.takeIf { it > 0 }?.toString() ?: "",
            range = 1..SettingsBounds.MAX_TIME_LIMIT_SEC,
            onDismiss = { timeLimitDialog = false },
            onConfirm = { value ->
                viewModel.update { it.copy(timeLimitSec = value) }
                timeLimitDialog = false
            },
        )
    }
}

// ----------------------------------------------------------------------
// Canli durum
// ----------------------------------------------------------------------

@Composable
private fun LiveStatusCard(
    settings: ClickerSettings,
    statsLine: String,
    speedLine: String,
    clickProgress: Float?,
    timeProgress: Float?,
    countdownMs: Long?,
    active: Boolean,
    ready: Boolean,
    onToggle: () -> Unit,
    onShowBubble: () -> Unit,
    onPickTargets: () -> Unit,
) {
    SectionCard(
        title = "2 · Durum",
        subtitle = if (ready) null else "Önce yukarıdaki zorunlu izinleri tamamla.",
    ) {
        Text(
            if (countdownMs != null) "Başlıyor: ${Formatting.seconds(countdownMs)} sn" else statsLine,
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold,
        )
        Spacer(Modifier.height(4.dp))
        Text(
            speedLine,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        if (clickProgress != null) {
            Spacer(Modifier.height(10.dp))
            LinearProgressIndicator(
                progress = { clickProgress },
                modifier = Modifier.fillMaxWidth(),
            )
        }
        if (timeProgress != null) {
            Spacer(Modifier.height(6.dp))
            LinearProgressIndicator(
                progress = { timeProgress },
                modifier = Modifier.fillMaxWidth(),
            )
        }

        Spacer(Modifier.height(14.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(
                onClick = onToggle,
                enabled = ready,
                modifier = Modifier.weight(1f),
                colors = if (active) {
                    ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                } else {
                    ButtonDefaults.buttonColors()
                },
            ) {
                Text(if (active) "Durdur" else "Başlat")
            }
            OutlinedButton(onClick = onShowBubble, enabled = ready) { Text("Balon") }
        }

        Spacer(Modifier.height(8.dp))
        Text(
            "Başlat dedikten sonra oyuna geç; tıklama arka planda devam eder. " +
                "Balondaki sayaç ve ilerleme çubuğu canlı güncellenir.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (settings.points.isEmpty()) {
            Spacer(Modifier.height(6.dp))
            TextButton(onClick = onPickTargets) { Text("Hedef seçilmedi → ekran merkezine tıklanır") }
        }
    }
}

// ----------------------------------------------------------------------
// Hiz
// ----------------------------------------------------------------------

@Composable
private fun SpeedSection(
    settings: ClickerSettings,
    calibrationRunning: Boolean,
    calibrationProgress: Float,
    calibrationStep: String,
    onUpdate: ((ClickerSettings) -> ClickerSettings) -> Unit,
    onCalibrate: () -> Unit,
    canCalibrate: Boolean,
) {
    SectionCard(
        title = "3 · Hız",
        subtitle = "Otomatik modda hedef CPS bir tavandır; motor cihazın gerçekten " +
            "yetiştiği hıza kendi kendine oturur.",
    ) {
        LabeledSlider(
            label = "Hedef CPS",
            value = settings.targetCps,
            range = SettingsBounds.MIN_CPS..SettingsBounds.MAX_CPS,
            valueLabel = "${settings.targetCps} /sn",
            onChange = { value -> onUpdate { it.copy(targetCps = value) } },
        )

        Spacer(Modifier.height(8.dp))
        SwitchRow(
            label = "Otomatik (CPU'ya göre)",
            description = "Gerçekleşen/hedef oranı %85'in altına düşerse yavaşlar, " +
                "%97'nin üstünde ve cihaz serinse hızlanır.",
            checked = settings.autoSpeed,
            onCheckedChange = { value -> onUpdate { it.copy(autoSpeed = value) } },
        )

        Spacer(Modifier.height(12.dp))
        if (calibrationRunning) {
            Text(
                "Kalibrasyon: $calibrationStep",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.primary,
            )
            Spacer(Modifier.height(6.dp))
            LinearProgressIndicator(
                progress = { calibrationProgress },
                modifier = Modifier.fillMaxWidth(),
            )
        } else {
            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedButton(onClick = onCalibrate, enabled = canCalibrate) {
                    Text("Kalibre et (~3 sn)")
                }
                Spacer(Modifier.width(12.dp))
                Text(
                    if (settings.maxSafeCps > 0) {
                        "Ölçülen tavan: ${settings.maxSafeCps} CPS"
                    } else {
                        "Henüz ölçülmedi"
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

// ----------------------------------------------------------------------
// Durdurma kosullari
// ----------------------------------------------------------------------

@Composable
private fun StopConditionSection(
    settings: ClickerSettings,
    onUpdate: ((ClickerSettings) -> ClickerSettings) -> Unit,
    onCustomClicks: () -> Unit,
    onCustomTime: () -> Unit,
) {
    SectionCard(
        title = "4 · Durdurma koşulları",
        subtitle = "İkisi birden seçilebilir; hangisi önce dolarsa tıklama orada durur.",
    ) {
        Text("Tıklama sayısı", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(6.dp))
        ChipRow(
            options = SettingsBounds.CLICK_LIMIT_PRESETS,
            selected = settings.clickLimit.takeIf { it in SettingsBounds.CLICK_LIMIT_PRESETS },
            labelOf = { if (it == 0) "Sınırsız" else it.toString() },
            onSelect = { value -> onUpdate { it.copy(clickLimit = value) } },
            trailing = {
                TextButton(onClick = onCustomClicks) {
                    Text(
                        if (settings.clickLimit !in SettingsBounds.CLICK_LIMIT_PRESETS) {
                            "Özel: ${settings.clickLimit}"
                        } else {
                            "Özel…"
                        }
                    )
                }
            },
        )

        Spacer(Modifier.height(14.dp))
        Text("Süre", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(6.dp))
        ChipRow(
            options = SettingsBounds.TIME_LIMIT_PRESETS,
            selected = settings.timeLimitSec.takeIf { it in SettingsBounds.TIME_LIMIT_PRESETS },
            labelOf = { if (it == 0) "Sınırsız" else formatSeconds(it) },
            onSelect = { value -> onUpdate { it.copy(timeLimitSec = value) } },
            trailing = {
                TextButton(onClick = onCustomTime) {
                    Text(
                        if (settings.timeLimitSec !in SettingsBounds.TIME_LIMIT_PRESETS) {
                            "Özel: ${settings.timeLimitSec} sn"
                        } else {
                            "Özel…"
                        }
                    )
                }
            },
        )
    }
}

private fun formatSeconds(seconds: Int): String = when {
    seconds % 60 == 0 && seconds >= 60 -> "${seconds / 60} dk"
    else -> "$seconds sn"
}

// ----------------------------------------------------------------------
// Dokunus ayarlari
// ----------------------------------------------------------------------

@Composable
private fun TouchSection(
    settings: ClickerSettings,
    onUpdate: ((ClickerSettings) -> ClickerSettings) -> Unit,
) {
    SectionCard(
        title = "5 · Dokunuş ayarları",
        subtitle = "Jitter değerleri tıklamayı daha insansı yapar; bazı oyunlar " +
            "milimetrik düzenli dokunuşu fark eder.",
    ) {
        LabeledSlider(
            label = "Jitter (zaman)",
            value = settings.jitterMs,
            range = 0..SettingsBounds.MAX_JITTER_MS,
            valueLabel = "±${settings.jitterMs} ms",
            onChange = { value -> onUpdate { it.copy(jitterMs = value) } },
        )
        Spacer(Modifier.height(6.dp))
        LabeledSlider(
            label = "Konum sapması",
            value = settings.positionJitterPx,
            range = 0..SettingsBounds.MAX_POSITION_JITTER_PX,
            valueLabel = "±${settings.positionJitterPx} px",
            onChange = { value -> onUpdate { it.copy(positionJitterPx = value) } },
        )
        Spacer(Modifier.height(6.dp))
        LabeledSlider(
            label = "Dokunuş süresi",
            value = settings.touchDurationMs,
            range = SettingsBounds.MIN_TOUCH_MS..SettingsBounds.MAX_TOUCH_MS,
            valueLabel = "${settings.touchDurationMs} ms",
            onChange = { value -> onUpdate { it.copy(touchDurationMs = value) } },
        )
        Spacer(Modifier.height(6.dp))
        LabeledSlider(
            label = "Başlangıç gecikmesi",
            value = settings.startDelaySec,
            range = 0..SettingsBounds.MAX_START_DELAY_SEC,
            valueLabel = "${settings.startDelaySec} sn",
            onChange = { value -> onUpdate { it.copy(startDelaySec = value) } },
        )
    }
}

// ----------------------------------------------------------------------
// Hedefler
// ----------------------------------------------------------------------

@Composable
private fun TargetSection(
    settings: ClickerSettings,
    onUpdate: ((ClickerSettings) -> ClickerSettings) -> Unit,
    onPickTargets: () -> Unit,
    canPick: Boolean,
) {
    SectionCard(
        title = "6 · Hedef noktalar",
        subtitle = "Seçilmezse ekran merkezine tıklanır.",
    ) {
        LabeledSlider(
            label = "Nokta sayısı",
            value = settings.pointCount,
            range = 1..SettingsBounds.MAX_POINTS,
            valueLabel = "${settings.pointCount} nokta",
            onChange = { value -> onUpdate { it.copy(pointCount = value) } },
        )

        Spacer(Modifier.height(8.dp))
        Text("Sıra", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(6.dp))
        ChipRow(
            options = ClickPattern.entries.toList(),
            selected = settings.pattern,
            labelOf = { if (it == ClickPattern.SEQUENTIAL) "Sırayla" else "Rastgele" },
            onSelect = { value -> onUpdate { it.copy(pattern = value) } },
        )

        Spacer(Modifier.height(12.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedButton(onClick = onPickTargets, enabled = canPick) { Text("Hedefleri seç") }
            Spacer(Modifier.width(12.dp))
            Text(
                if (settings.points.isEmpty()) {
                    "Seçili yok"
                } else {
                    settings.points.joinToString(" · ") { "${it.x.toInt()},${it.y.toInt()}" }
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

// ----------------------------------------------------------------------
// Guvenlik
// ----------------------------------------------------------------------

@Composable
private fun SafetySection(
    settings: ClickerSettings,
    onUpdate: ((ClickerSettings) -> ClickerSettings) -> Unit,
) {
    SectionCard(title = "7 · Güvenlik") {
        SwitchRow(
            label = "Ses kısma tuşu = acil durdurma",
            description = "Tıklama sürerken ses kısma tuşuna basmak anında durdurur.",
            checked = settings.volumeKeyStop,
            onCheckedChange = { value -> onUpdate { it.copy(volumeKeyStop = value) } },
        )
        Spacer(Modifier.height(10.dp))
        SwitchRow(
            label = "Ekran kapanınca dur",
            description = "Telefon cebe girdiğinde tıklamanın sürmesini engeller.",
            checked = settings.stopOnScreenOff,
            onCheckedChange = { value -> onUpdate { it.copy(stopOnScreenOff = value) } },
        )
        Spacer(Modifier.height(10.dp))
        Text(
            "Bildirimdeki “Durdur” düğmesi her zaman çalışır. Durdurma anında kısa bir titreşim verilir.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

// ----------------------------------------------------------------------
// Ozel deger diyalogu
// ----------------------------------------------------------------------

@Composable
private fun NumberDialog(
    title: String,
    hint: String,
    initial: String,
    range: IntRange,
    onDismiss: () -> Unit,
    onConfirm: (Int) -> Unit,
) {
    var text by remember { mutableStateOf(initial) }
    val parsed = text.toIntOrNull()
    val valid = parsed != null && parsed in range

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column {
                OutlinedTextField(
                    value = text,
                    onValueChange = { input -> text = input.filter { it.isDigit() }.take(6) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    supportingText = { Text(hint) },
                    isError = text.isNotEmpty() && !valid,
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { parsed?.let(onConfirm) }, enabled = valid) { Text("Tamam") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Vazgeç") } },
    )
}
