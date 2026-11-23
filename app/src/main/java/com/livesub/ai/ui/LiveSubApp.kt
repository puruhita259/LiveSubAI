package com.livesub.ai.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.BlurOn
import androidx.compose.material.icons.rounded.Bolt
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.CloudDone
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.DownloadForOffline
import androidx.compose.material.icons.rounded.FormatSize
import androidx.compose.material.icons.rounded.Key
import androidx.compose.material.icons.rounded.Layers
import androidx.compose.material.icons.rounded.Mic
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Stop
import androidx.compose.material.icons.rounded.Translate
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material.icons.rounded.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.livesub.ai.ai.OfflineSttManager
import com.livesub.ai.ai.OfflineTranslatorManager
import com.livesub.ai.data.LiveSubStateHolder
import com.livesub.ai.model.EngineMode
import com.livesub.ai.model.HybridSttProvider
import com.livesub.ai.model.LanguageCatalog
import com.livesub.ai.model.LanguageOption
import com.livesub.ai.model.LiveSubRuntimeState
import com.livesub.ai.model.OverlayConfig
import com.livesub.ai.model.SurfaceStyle
import com.livesub.ai.model.TextProtection
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

private enum class AppTab(val label: String, val icon: ImageVector) {
    HOME("Beranda", Icons.Rounded.Translate),
    OFFLINE_PACKS("Paket Offline", Icons.Rounded.DownloadForOffline),
    APPEARANCE("Tampilan & API", Icons.Rounded.Tune)
}

private val FONT_PRESETS = listOf(
    "Kecil" to 12f,
    "Sedang" to 15f,
    "Besar" to 19f,
    "Jumbo" to 24f
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LiveSubApp(
    hasOverlayPermission: Boolean,
    hasAudioPermission: Boolean,
    onRequestOverlayPermission: () -> Unit,
    onRequestAudioPermission: () -> Unit,
    onStartLiveCapture: () -> Unit,
    onStartMicMode: () -> Unit,
    onStartDemoOverlay: () -> Unit,
    onStopOverlay: () -> Unit,
    onSaveConfig: (OverlayConfig) -> Unit
) {
    val config by LiveSubStateHolder.config.collectAsStateWithLifecycle()
    val runtime by LiveSubStateHolder.runtime.collectAsStateWithLifecycle()

    var selectedTabIndex by rememberSaveable { mutableIntStateOf(0) }
    val selectedTab = AppTab.entries[selectedTabIndex]

    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        val isLargeScreen = maxWidth >= 600.dp

        Scaffold(
            topBar = {
                TopAppBar(
                    title = {
                        Column {
                            Text(
                                text = "LiveSub AI",
                                style = MaterialTheme.typography.titleLarge,
                                fontWeight = FontWeight.Bold
                            )
                            Text(
                                text = if (runtime.isOverlayActive) {
                                    "Overlay aktif · ${runtime.activeProviderTag}"
                                } else {
                                    config.engineMode.label
                                },
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                )
            },
            bottomBar = {
                if (!isLargeScreen) {
                    NavigationBar {
                        AppTab.entries.forEachIndexed { index, tab ->
                            NavigationBarItem(
                                selected = selectedTabIndex == index,
                                onClick = { selectedTabIndex = index },
                                icon = { Icon(tab.icon, contentDescription = tab.label) },
                                label = { Text(tab.label) }
                            )
                        }
                    }
                }
            }
        ) { innerPadding ->
            Row(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
            ) {
                if (isLargeScreen) {
                    NavigationRail(modifier = Modifier.fillMaxHeight()) {
                        Spacer(Modifier.height(12.dp))
                        AppTab.entries.forEachIndexed { index, tab ->
                            NavigationRailItem(
                                selected = selectedTabIndex == index,
                                onClick = { selectedTabIndex = index },
                                icon = { Icon(tab.icon, contentDescription = tab.label) },
                                label = { Text(tab.label) }
                            )
                        }
                    }
                }

                Box(modifier = Modifier.weight(1f).fillMaxHeight()) {
                    when (selectedTab) {
                        AppTab.HOME -> HomeScreen(
                            config = config,
                            runtime = runtime,
                            hasOverlayPermission = hasOverlayPermission,
                            hasAudioPermission = hasAudioPermission,
                            onRequestOverlayPermission = onRequestOverlayPermission,
                            onRequestAudioPermission = onRequestAudioPermission,
                            onStartLiveCapture = onStartLiveCapture,
                            onStartMicMode = onStartMicMode,
                            onStartDemoOverlay = onStartDemoOverlay,
                            onStopOverlay = onStopOverlay,
                            onGoToOfflinePacks = { selectedTabIndex = 1 },
                            onGoToApiSetup = { selectedTabIndex = 2 },
                            onConfigChange = onSaveConfig
                        )
                        AppTab.OFFLINE_PACKS -> OfflinePacksScreen(
                            config = config,
                            onSelectOfflineSttModel = { modelId ->
                                onSaveConfig(config.copy(offlineSttModelId = modelId))
                            }
                        )
                        AppTab.APPEARANCE -> AppearanceAndApiScreen(
                            config = config,
                            onSaveConfig = onSaveConfig
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun HomeScreen(
    config: OverlayConfig,
    runtime: LiveSubRuntimeState,
    hasOverlayPermission: Boolean,
    hasAudioPermission: Boolean,
    onRequestOverlayPermission: () -> Unit,
    onRequestAudioPermission: () -> Unit,
    onStartLiveCapture: () -> Unit,
    onStartMicMode: () -> Unit,
    onStartDemoOverlay: () -> Unit,
    onStopOverlay: () -> Unit,
    onGoToOfflinePacks: () -> Unit,
    onGoToApiSetup: () -> Unit,
    onConfigChange: (OverlayConfig) -> Unit
) {
    val context = LocalContext.current
    val sttManager = remember { OfflineSttManager(context) }
    val downloadedSttIds = remember(config.offlineSttModelId) { sttManager.getDownloadedModelIds() }
    val hasHybridKey = config.groqApiKey.isNotBlank() ||
        (config.cloudflareAccountId.isNotBlank() && config.cloudflareApiToken.isNotBlank())

    LazyColumn(
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
        modifier = Modifier.fillMaxSize()
    ) {
        item {
            Card(
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.55f)
                ),
                shape = RoundedCornerShape(20.dp)
            ) {
                Column(modifier = Modifier.padding(18.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = if (runtime.isOverlayActive) Icons.Rounded.CheckCircle else Icons.Rounded.Layers,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary
                        )
                        Spacer(Modifier.width(10.dp))
                        Text(
                            text = if (runtime.isOverlayActive) "Overlay sedang berjalan" else "Siap menerjemahkan video",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                    Spacer(Modifier.height(6.dp))
                    Text(
                        text = if (runtime.isOverlayActive) {
                            "Mesin aktif: ${runtime.activeProviderTag}. Translate lokal ML Kit ~20–50 ms."
                        } else {
                            "Sistem rekam audio, transkripsi (STT), dan terjemahan offline (< 50 ms) berjalan terpisah agar real-time."
                        },
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.height(14.dp))

                    if (runtime.isOverlayActive) {
                        Button(
                            onClick = onStopOverlay,
                            colors = ButtonDefaults.buttonColors(
                                containerColor = MaterialTheme.colorScheme.error
                            ),
                            modifier = Modifier.fillMaxWidth().height(48.dp)
                        ) {
                            Icon(Icons.Rounded.Stop, contentDescription = null)
                            Spacer(Modifier.width(8.dp))
                            Text("Hentikan overlay")
                        }
                    } else {
                        Button(
                            onClick = onStartLiveCapture,
                            modifier = Modifier.fillMaxWidth().height(50.dp)
                        ) {
                            Icon(Icons.Rounded.PlayArrow, contentDescription = null)
                            Spacer(Modifier.width(8.dp))
                            Text("Aktifkan overlay live")
                        }
                        Spacer(Modifier.height(8.dp))
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            FilledTonalButton(
                                onClick = onStartDemoOverlay,
                                modifier = Modifier.weight(1f).height(44.dp)
                            ) {
                                Icon(Icons.Rounded.AutoAwesome, contentDescription = null, modifier = Modifier.size(18.dp))
                                Spacer(Modifier.width(6.dp))
                                Text("Uji overlay")
                            }
                            OutlinedButton(
                                onClick = onStartMicMode,
                                modifier = Modifier.weight(1f).height(44.dp)
                            ) {
                                Icon(Icons.Rounded.Mic, contentDescription = null, modifier = Modifier.size(18.dp))
                                Spacer(Modifier.width(6.dp))
                                Text("Mode mikrofon")
                            }
                        }
                    }
                }
            }
        }

        // Pilih 2 Mesin Utama: Hybrid vs Full Offline
        item {
            OutlinedCard(shape = RoundedCornerShape(16.dp)) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Rounded.Bolt, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                        Spacer(Modifier.width(8.dp))
                        Text(
                            text = "Pilih model penerjemah (2 mode)",
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.SemiBold
                        )
                    }

                    EngineMode.entries.forEach { mode ->
                        SelectableOptionRow(
                            title = mode.label,
                            subtitle = mode.subtitle,
                            selected = config.engineMode == mode,
                            onClick = { onConfigChange(config.copy(engineMode = mode)) }
                        )
                    }

                    if (config.engineMode == EngineMode.HYBRID_CLOUD_STT) {
                        HorizontalDivider()
                        Text(
                            text = "Provider STT Hybrid",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            HybridSttProvider.entries.forEach { provider ->
                                FilterChip(
                                    selected = config.hybridProvider == provider,
                                    onClick = { onConfigChange(config.copy(hybridProvider = provider)) },
                                    label = { Text(provider.label, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                                    modifier = Modifier.weight(1f)
                                )
                            }
                        }
                        if (!hasHybridKey) {
                            FilledTonalButton(
                                onClick = onGoToApiSetup,
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Icon(Icons.Rounded.Key, contentDescription = null, modifier = Modifier.size(18.dp))
                                Spacer(Modifier.width(8.dp))
                                Text("Isi API Key Groq / Cloudflare")
                            }
                        }
                    } else {
                        HorizontalDivider()
                        val selectedStt = LanguageCatalog.findOfflineSttById(config.offlineSttModelId)
                        val isReady = downloadedSttIds.contains(selectedStt.id)
                        Text(
                            text = if (isReady) {
                                "Model suara offline siap: ${selectedStt.displayName} (${selectedStt.sizeLabel})"
                            } else {
                                "Model suara '${selectedStt.displayName}' belum diunduh."
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = if (isReady) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error
                        )
                        FilledTonalButton(
                            onClick = onGoToOfflinePacks,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Icon(Icons.Rounded.DownloadForOffline, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(8.dp))
                            Text("Kelola & unduh bahasa offline")
                        }
                    }
                }
            }
        }

        if (!hasOverlayPermission || !hasAudioPermission) {
            item {
                OutlinedCard(shape = RoundedCornerShape(16.dp)) {
                    Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Rounded.Warning, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                            Spacer(Modifier.width(8.dp))
                            Text(
                                text = "Izin diperlukan sebelum mulai",
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.SemiBold
                            )
                        }

                        if (!hasOverlayPermission) {
                            PermissionRow(
                                title = "Tampil di atas aplikasi lain",
                                description = "Menampilkan kotak subtitle di atas YouTube, TikTok, dan Reels.",
                                buttonLabel = "Izinkan tampilan",
                                onClick = onRequestOverlayPermission
                            )
                        }

                        if (!hasAudioPermission) {
                            PermissionRow(
                                title = "Tangkap audio & mikrofon",
                                description = "Dibutuhkan Android untuk menangkap audio video dan fallback DRM.",
                                buttonLabel = "Izinkan audio",
                                onClick = onRequestAudioPermission
                            )
                        }
                    }
                }
            }
        }

        // Pengaturan Bahasa
        item {
            OutlinedCard(shape = RoundedCornerShape(16.dp)) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(
                        text = "Bahasa terjemahan",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold
                    )

                    if (config.engineMode == EngineMode.HYBRID_CLOUD_STT) {
                        LanguageCodeDropdown(
                            label = "Bahasa suara video (Hybrid STT)",
                            selected = LanguageCatalog.findLanguageByCode(config.sourceLangCode),
                            options = listOf(LanguageCatalog.AUTO_DETECT) + LanguageCatalog.TRANSLATE_LANGUAGES,
                            onSelected = { onConfigChange(config.copy(sourceLangCode = it.code)) }
                        )
                    } else {
                        OfflineSttDropdown(
                            label = "Model suara video (Full Offline)",
                            selectedId = config.offlineSttModelId,
                            downloadedIds = downloadedSttIds,
                            onSelected = { onConfigChange(config.copy(offlineSttModelId = it)) }
                        )
                    }

                    LanguageCodeDropdown(
                        label = "Terjemahkan ke (ML Kit Offline < 50 ms)",
                        selected = LanguageCatalog.findLanguageByCode(config.targetLangCode),
                        options = LanguageCatalog.TRANSLATE_LANGUAGES,
                        onSelected = { onConfigChange(config.copy(targetLangCode = it.code)) }
                    )
                }
            }
        }

        item {
            OutlinedCard(shape = RoundedCornerShape(16.dp)) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    FontSizeControlSection(
                        fontSizeSp = config.fontSizeSp,
                        onFontSizeChange = { onConfigChange(config.copy(fontSizeSp = it)) }
                    )
                }
            }
        }

        item {
            OutlinedCard(shape = RoundedCornerShape(16.dp)) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Rounded.BlurOn, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                        Spacer(Modifier.width(8.dp))
                        Text(
                            text = "Gaya kaca kotak subtitle",
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        SurfaceStyle.entries.forEach { style ->
                            FilterChip(
                                selected = config.surfaceStyle == style,
                                onClick = { onConfigChange(config.copy(surfaceStyle = style)) },
                                label = { Text(style.label, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                                modifier = Modifier.weight(1f)
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun OfflinePacksScreen(
    config: OverlayConfig,
    onSelectOfflineSttModel: (String) -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val sttManager = remember { OfflineSttManager(context) }
    val translatorManager = remember { OfflineTranslatorManager() }

    var activeSection by rememberSaveable { mutableIntStateOf(0) } // 0 = STT Suara (16), 1 = Kamus Translate (20)
    var downloadedStt by remember { mutableStateOf(sttManager.getDownloadedModelIds()) }
    var downloadedTranslate by remember { mutableStateOf<Set<String>>(emptySet()) }
    var statusBanner by remember { mutableStateOf<String?>(null) }

    val sttProgress = remember { mutableStateMapOf<String, Float>() }
    val translateDownloading = remember { mutableStateMapOf<String, Boolean>() }

    LaunchedEffect(Unit) {
        try {
            downloadedTranslate = translatorManager.getDownloadedLanguageCodes()
        } catch (_: Exception) {
        }
    }

    LazyColumn(
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        modifier = Modifier.fillMaxSize()
    ) {
        item {
            Card(
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f)),
                shape = RoundedCornerShape(16.dp)
            ) {
                Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        text = "Unduh paket bahasa untuk kerja offline & cepat",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold
                    )
                    Text(
                        text = "• Model Suara (STT): dipakai Mode Full Offline (streaming kata per kata di HP).\n• Kamus Terjemahan (ML Kit ~30 MB): dipakai Mode Hybrid & Full Offline untuk terjemahan < 50 ms.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        FilterChip(
                            selected = activeSection == 0,
                            onClick = { activeSection = 0 },
                            label = { Text("Suara STT (${downloadedStt.size}/16)") },
                            modifier = Modifier.weight(1f)
                        )
                        FilterChip(
                            selected = activeSection == 1,
                            onClick = { activeSection = 1 },
                            label = { Text("Kamus (${downloadedTranslate.size}/20)") },
                            modifier = Modifier.weight(1f)
                        )
                    }
                    if (!statusBanner.isNullOrBlank()) {
                        Text(
                            text = statusBanner!!,
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                }
            }
        }

        if (activeSection == 0) {
            items(LanguageCatalog.OFFLINE_STT_MODELS, key = { it.id }) { spec ->
                val isDownloaded = downloadedStt.contains(spec.id)
                val isSelected = config.offlineSttModelId == spec.id
                val progress = sttProgress[spec.id]

                OutlinedCard(
                    shape = RoundedCornerShape(14.dp),
                    border = if (isSelected) BorderStroke(1.5.dp, MaterialTheme.colorScheme.primary) else CardDefaults.outlinedCardBorder()
                ) {
                    Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = spec.displayName,
                                    style = MaterialTheme.typography.bodyLarge,
                                    fontWeight = FontWeight.SemiBold
                                )
                                Text(
                                    text = if (isDownloaded) "Terunduh (${spec.sizeLabel}) · Siap Offline" else "Ukuran unduhan: ${spec.sizeLabel}",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = if (isDownloaded) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }

                            if (progress != null) {
                                Text(
                                    text = "${(progress * 100).roundToInt()}%",
                                    style = MaterialTheme.typography.labelLarge,
                                    color = MaterialTheme.colorScheme.primary
                                )
                            } else if (isDownloaded) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    if (!isSelected) {
                                        FilledTonalButton(
                                            onClick = { onSelectOfflineSttModel(spec.id) },
                                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp)
                                        ) {
                                            Text("Pakai")
                                        }
                                    } else {
                                        Icon(
                                            imageVector = Icons.Rounded.CheckCircle,
                                            contentDescription = "Aktif",
                                            tint = MaterialTheme.colorScheme.primary
                                        )
                                    }
                                    IconButton(
                                        onClick = {
                                            sttManager.deleteModel(spec.id)
                                            downloadedStt = sttManager.getDownloadedModelIds()
                                            statusBanner = "Model suara ${spec.displayName} dihapus."
                                        }
                                    ) {
                                        Icon(Icons.Rounded.DeleteOutline, contentDescription = "Hapus")
                                    }
                                }
                            } else {
                                FilledTonalButton(
                                    onClick = {
                                        sttProgress[spec.id] = 0.02f
                                        statusBanner = "Mengunduh model suara ${spec.displayName}..."
                                        scope.launch {
                                            val res = sttManager.downloadModel(spec) { p ->
                                                sttProgress[spec.id] = p
                                            }
                                            sttProgress.remove(spec.id)
                                            downloadedStt = sttManager.getDownloadedModelIds()
                                            if (res.isSuccess) {
                                                onSelectOfflineSttModel(spec.id)
                                                statusBanner = "Model ${spec.displayName} siap dipakai & dipilih otomatis."
                                            } else {
                                                statusBanner = "Gagal unduh ${spec.displayName}: ${res.exceptionOrNull()?.localizedMessage}"
                                            }
                                        }
                                    }
                                ) {
                                    Icon(Icons.Rounded.Download, contentDescription = null, modifier = Modifier.size(18.dp))
                                    Spacer(Modifier.width(6.dp))
                                    Text("Unduh")
                                }
                            }
                        }

                        if (progress != null) {
                            LinearProgressIndicator(
                                progress = { progress.coerceIn(0f, 1f) },
                                modifier = Modifier.fillMaxWidth()
                            )
                        }
                    }
                }
            }
        } else {
            items(LanguageCatalog.TRANSLATE_LANGUAGES, key = { it.code }) { lang ->
                val isDownloaded = downloadedTranslate.contains(lang.code)
                val isLoading = translateDownloading[lang.code] == true

                OutlinedCard(shape = RoundedCornerShape(14.dp)) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.fillMaxWidth().padding(14.dp)
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "${lang.displayName} (${lang.shortCode})",
                                style = MaterialTheme.typography.bodyLarge,
                                fontWeight = FontWeight.SemiBold
                            )
                            Text(
                                text = if (isDownloaded) "Kamus offline siap (< 50 ms)" else "Belum diunduh (~30 MB)",
                                style = MaterialTheme.typography.bodySmall,
                                color = if (isDownloaded) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }

                        if (isLoading) {
                            CircularProgressIndicator(modifier = Modifier.size(24.dp), strokeWidth = 2.5.dp)
                        } else if (isDownloaded) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(
                                    imageVector = Icons.Rounded.CloudDone,
                                    contentDescription = "Terunduh",
                                    tint = MaterialTheme.colorScheme.primary
                                )
                                IconButton(
                                    onClick = {
                                        translateDownloading[lang.code] = true
                                        scope.launch {
                                            translatorManager.deleteLanguagePack(lang.code)
                                            downloadedTranslate = translatorManager.getDownloadedLanguageCodes()
                                            translateDownloading.remove(lang.code)
                                            statusBanner = "Kamus ${lang.displayName} dihapus."
                                        }
                                    }
                                ) {
                                    Icon(Icons.Rounded.DeleteOutline, contentDescription = "Hapus")
                                }
                            }
                        } else {
                            FilledTonalButton(
                                onClick = {
                                    translateDownloading[lang.code] = true
                                    statusBanner = "Mengunduh kamus ${lang.displayName}..."
                                    scope.launch {
                                        val res = translatorManager.downloadLanguagePack(lang.code)
                                        downloadedTranslate = translatorManager.getDownloadedLanguageCodes()
                                        translateDownloading.remove(lang.code)
                                        statusBanner = if (res.isSuccess) {
                                            "Kamus ${lang.displayName} siap offline."
                                        } else {
                                            "Gagal unduh kamus ${lang.displayName}: ${res.exceptionOrNull()?.localizedMessage}"
                                        }
                                    }
                                }
                            ) {
                                Icon(Icons.Rounded.Download, contentDescription = null, modifier = Modifier.size(18.dp))
                                Spacer(Modifier.width(6.dp))
                                Text("Unduh")
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun AppearanceAndApiScreen(
    config: OverlayConfig,
    onSaveConfig: (OverlayConfig) -> Unit
) {
    var groqKeyInput by remember(config.groqApiKey) { mutableStateOf(config.groqApiKey) }
    var groqModelInput by remember(config.groqModel) { mutableStateOf(config.groqModel) }
    var cfAccountInput by remember(config.cloudflareAccountId) { mutableStateOf(config.cloudflareAccountId) }
    var cfTokenInput by remember(config.cloudflareApiToken) { mutableStateOf(config.cloudflareApiToken) }
    var cfModelInput by remember(config.cloudflareModel) { mutableStateOf(config.cloudflareModel) }
    var previewLightBg by remember { mutableStateOf(false) }

    LazyColumn(
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
        modifier = Modifier.fillMaxSize()
    ) {
        // Konfigurasi API Key Hybrid (Groq Console & Cloudflare Workers AI)
        item {
            OutlinedCard(shape = RoundedCornerShape(18.dp)) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Rounded.Key, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                        Spacer(Modifier.width(8.dp))
                        Text(
                            text = "API Key STT Hybrid (Groq & Cloudflare)",
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                    Text(
                        text = "Isi salah satu atau keduanya (otomatis saling backup saat limit). Semua kunci disimpan lokal di HP saja.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )

                    Text("1. Groq Console (console.groq.com)", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold)
                    OutlinedTextField(
                        value = groqKeyInput,
                        onValueChange = { groqKeyInput = it },
                        label = { Text("Groq API Key (gsk_...)") },
                        visualTransformation = PasswordVisualTransformation(),
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    OutlinedTextField(
                        value = groqModelInput,
                        onValueChange = { groqModelInput = it },
                        label = { Text("Model Whisper Groq") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )

                    HorizontalDivider()

                    Text("2. Cloudflare Workers AI", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold)
                    OutlinedTextField(
                        value = cfAccountInput,
                        onValueChange = { cfAccountInput = it },
                        label = { Text("Cloudflare Account ID") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    OutlinedTextField(
                        value = cfTokenInput,
                        onValueChange = { cfTokenInput = it },
                        label = { Text("Cloudflare API Token (Workers AI)") },
                        visualTransformation = PasswordVisualTransformation(),
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    OutlinedTextField(
                        value = cfModelInput,
                        onValueChange = { cfModelInput = it },
                        label = { Text("Model Whisper Cloudflare") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )

                    Button(
                        onClick = {
                            onSaveConfig(
                                config.copy(
                                    groqApiKey = groqKeyInput.trim(),
                                    groqModel = groqModelInput.trim().ifEmpty { "whisper-large-v3-turbo" },
                                    cloudflareAccountId = cfAccountInput.trim(),
                                    cloudflareApiToken = cfTokenInput.trim(),
                                    cloudflareModel = cfModelInput.trim().ifEmpty { "@cf/openai/whisper-large-v3-turbo" }
                                )
                            )
                        },
                        modifier = Modifier.fillMaxWidth().height(46.dp)
                    ) {
                        Text("Simpan kredensial STT")
                    }
                }
            }
        }

        // Pratinjau keterbacaan overlay
        item {
            OutlinedCard(shape = RoundedCornerShape(18.dp)) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(
                            text = "Pratinjau keterbacaan",
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.SemiBold
                        )
                        FilterChip(
                            selected = previewLightBg,
                            onClick = { previewLightBg = !previewLightBg },
                            label = { Text(if (previewLightBg) "Latar putih" else "Latar split") }
                        )
                    }

                    val backdropBrush = if (previewLightBg) {
                        Brush.horizontalGradient(listOf(Color(0xFFF8FAFC), Color(0xFFE2E8F0)))
                    } else {
                        Brush.horizontalGradient(listOf(Color(0xFF1E293B), Color(0xFFF1F5F9)))
                    }

                    val boxBgColor = when (config.surfaceStyle) {
                        SurfaceStyle.SOLID_DARK -> Color(0xFF0C0E14).copy(alpha = config.tintOpacity.coerceIn(0.78f, 0.96f))
                        SurfaceStyle.GLASS_DARK -> Color(0xFF0A0E18).copy(alpha = config.tintOpacity.coerceIn(0.28f, 0.85f))
                        SurfaceStyle.GLASS_CLEAR -> Color.White.copy(alpha = (config.tintOpacity * 0.35f).coerceIn(0.10f, 0.38f))
                    }

                    val borderColor = when (config.surfaceStyle) {
                        SurfaceStyle.SOLID_DARK -> Color.White.copy(alpha = 0.16f)
                        SurfaceStyle.GLASS_DARK -> Color.White.copy(alpha = 0.35f)
                        SurfaceStyle.GLASS_CLEAR -> Color.White.copy(alpha = 0.65f)
                    }

                    Box(
                        contentAlignment = Alignment.Center,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(155.dp)
                            .clip(RoundedCornerShape(14.dp))
                            .background(backdropBrush)
                            .padding(14.dp)
                    ) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(16.dp))
                                .background(boxBgColor)
                                .border(BorderStroke(1.dp, borderColor), RoundedCornerShape(16.dp))
                                .padding(horizontal = 14.dp, vertical = 12.dp)
                        ) {
                            Text(
                                text = "Mendengarkan... [${config.hybridProvider.shortTag} → ID · 24ms]",
                                fontSize = 11.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = Color(0xFF93C5FD),
                                style = TextStyle(shadow = Shadow(Color.Black, Offset(0f, 2f), 6f))
                            )
                            if (config.showOriginalText) {
                                Text(
                                    text = "First, simmer the broth gently to bring out the aroma.",
                                    fontSize = 11.sp,
                                    color = Color(0xFFE2E8F0),
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                            Spacer(Modifier.height(4.dp))
                            val textModifier = if (config.textProtection == TextProtection.MICRO_SCRIM) {
                                Modifier
                                    .clip(RoundedCornerShape(6.dp))
                                    .background(Color.Black.copy(alpha = 0.56f))
                                    .padding(horizontal = 6.dp, vertical = 2.dp)
                            } else {
                                Modifier
                            }
                            Text(
                                text = "Pertama, panaskan kaldu perlahan agar aromanya keluar.",
                                fontSize = config.fontSizeSp.sp,
                                fontWeight = FontWeight.Bold,
                                color = Color.White,
                                lineHeight = (config.fontSizeSp + 5f).sp,
                                style = TextStyle(
                                    shadow = Shadow(
                                        color = Color.Black,
                                        offset = Offset(0f, 2f),
                                        blurRadius = if (config.textProtection == TextProtection.HALO_SHADOW) 12f else 5f
                                    )
                                ),
                                modifier = textModifier
                            )
                        }
                    }
                }
            }
        }

        // Pengaturan Tampilan Kotak Subtitle
        item {
            OutlinedCard(shape = RoundedCornerShape(18.dp)) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    FontSizeControlSection(
                        fontSizeSp = config.fontSizeSp,
                        onFontSizeChange = { onSaveConfig(config.copy(fontSizeSp = it)) }
                    )

                    HorizontalDivider()

                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("Tampilkan teks asli (2 bahasa)", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
                            Text(
                                text = "Tampilkan transkrip bahasa asli tipis di atas hasil terjemahan.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Switch(
                            checked = config.showOriginalText,
                            onCheckedChange = { onSaveConfig(config.copy(showOriginalText = it)) }
                        )
                    }

                    HorizontalDivider()

                    Text("Permukaan kotak subtitle", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                    SurfaceStyle.entries.forEach { style ->
                        SelectableOptionRow(
                            title = style.label,
                            subtitle = style.subtitle,
                            selected = config.surfaceStyle == style,
                            onClick = { onSaveConfig(config.copy(surfaceStyle = style)) }
                        )
                    }

                    HorizontalDivider()

                    Text("Pelindung keterbacaan huruf", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold)
                    TextProtection.entries.forEach { protection ->
                        SelectableOptionRow(
                            title = protection.label,
                            subtitle = protection.subtitle,
                            selected = config.textProtection == protection,
                            onClick = { onSaveConfig(config.copy(textProtection = protection)) }
                        )
                    }

                    HorizontalDivider()

                    Text("Intensitas blur kaca (Android 12+): ${config.blurRadiusPx} px", style = MaterialTheme.typography.labelLarge)
                    Slider(
                        value = config.blurRadiusPx.toFloat(),
                        onValueChange = { onSaveConfig(config.copy(blurRadiusPx = it.roundToInt())) },
                        valueRange = 0f..60f
                    )

                    Text("Opasitas tint: ${(config.tintOpacity * 100).roundToInt()}%", style = MaterialTheme.typography.labelLarge)
                    Slider(
                        value = config.tintOpacity,
                        onValueChange = { onSaveConfig(config.copy(tintOpacity = it)) },
                        valueRange = 0.15f..0.95f
                    )
                }
            }
        }
    }
}

@Composable
private fun FontSizeControlSection(
    fontSizeSp: Float,
    onFontSizeChange: (Float) -> Unit
) {
    val currentInt = fontSizeSp.roundToInt()

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
            modifier = Modifier.fillMaxWidth()
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Rounded.FormatSize, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.width(8.dp))
                Text(
                    text = "Ukuran font subtitle: $currentInt sp",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                OutlinedButton(
                    onClick = { onFontSizeChange((fontSizeSp - 1f).coerceIn(10f, 28f)) },
                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                    modifier = Modifier.height(34.dp)
                ) {
                    Text("A-")
                }
                OutlinedButton(
                    onClick = { onFontSizeChange((fontSizeSp + 1f).coerceIn(10f, 28f)) },
                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                    modifier = Modifier.height(34.dp)
                ) {
                    Text("A+")
                }
            }
        }

        Row(
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            FONT_PRESETS.forEach { (label, presetSp) ->
                FilterChip(
                    selected = currentInt == presetSp.roundToInt(),
                    onClick = { onFontSizeChange(presetSp) },
                    label = { Text(label, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                    modifier = Modifier.weight(1f)
                )
            }
        }

        Slider(
            value = fontSizeSp.coerceIn(10f, 28f),
            onValueChange = { onFontSizeChange(it.roundToInt().toFloat()) },
            valueRange = 10f..28f
        )
    }
}

@Composable
private fun PermissionRow(
    title: String,
    description: String,
    buttonLabel: String,
    onClick: () -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(text = title, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
        Text(
            text = description,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        FilledTonalButton(onClick = onClick) {
            Text(buttonLabel)
        }
    }
}

@Composable
private fun LanguageCodeDropdown(
    label: String,
    selected: LanguageOption,
    options: List<LanguageOption>,
    onSelected: (LanguageOption) -> Unit
) {
    var expanded by remember { mutableStateOf(false) }

    Box(modifier = Modifier.fillMaxWidth()) {
        OutlinedCard(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { expanded = true },
            shape = RoundedCornerShape(12.dp)
        ) {
            Column(modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp)) {
                Text(
                    text = label,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    text = "${selected.displayName} (${selected.shortCode})",
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.Medium
                )
            }
        }

        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false }
        ) {
            options.forEach { option ->
                DropdownMenuItem(
                    text = { Text("${option.displayName} (${option.shortCode})") },
                    onClick = {
                        onSelected(option)
                        expanded = false
                    }
                )
            }
        }
    }
}

@Composable
private fun OfflineSttDropdown(
    label: String,
    selectedId: String,
    downloadedIds: Set<String>,
    onSelected: (String) -> Unit
) {
    var expanded by remember { mutableStateOf(false) }
    val selected = LanguageCatalog.findOfflineSttById(selectedId)

    Box(modifier = Modifier.fillMaxWidth()) {
        OutlinedCard(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { expanded = true },
            shape = RoundedCornerShape(12.dp)
        ) {
            Column(modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp)) {
                Text(
                    text = label,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(2.dp))
                val badge = if (downloadedIds.contains(selected.id)) "Siap Offline" else "Belum Diunduh"
                Text(
                    text = "${selected.displayName} ($badge)",
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.Medium
                )
            }
        }

        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false }
        ) {
            LanguageCatalog.OFFLINE_STT_MODELS.forEach { spec ->
                val status = if (downloadedIds.contains(spec.id)) "✓" else spec.sizeLabel
                DropdownMenuItem(
                    text = { Text("${spec.displayName} ($status)") },
                    onClick = {
                        onSelected(spec.id)
                        expanded = false
                    }
                )
            }
        }
    }
}

@Composable
private fun SelectableOptionRow(
    title: String,
    subtitle: String,
    selected: Boolean,
    onClick: () -> Unit
) {
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = if (selected) {
            MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.55f)
        } else {
            MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.30f)
        },
        border = if (selected) BorderStroke(1.dp, MaterialTheme.colorScheme.primary) else null,
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp)
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(text = title, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            if (selected) {
                Icon(
                    imageVector = Icons.Rounded.CheckCircle,
                    contentDescription = "Dipilih",
                    tint = MaterialTheme.colorScheme.primary
                )
            }
        }
    }
}
