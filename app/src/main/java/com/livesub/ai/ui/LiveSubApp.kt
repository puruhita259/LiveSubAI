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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.BlurOn
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Key
import androidx.compose.material.icons.rounded.Layers
import androidx.compose.material.icons.rounded.Link
import androidx.compose.material.icons.rounded.Mic
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Stop
import androidx.compose.material.icons.rounded.Subtitles
import androidx.compose.material.icons.rounded.Translate
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material.icons.rounded.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
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
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
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
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.livesub.ai.data.LiveSubStateHolder
import com.livesub.ai.model.LiveSubRuntimeState
import com.livesub.ai.model.OverlayConfig
import com.livesub.ai.model.SurfaceStyle
import com.livesub.ai.model.TextProtection
import kotlin.math.roundToInt

private enum class AppTab(val label: String, val icon: ImageVector) {
    HOME("Beranda", Icons.Rounded.Translate),
    SHARE_PLAYER("Pemutar Link", Icons.Rounded.Subtitles),
    APPEARANCE("Tampilan & AI", Icons.Rounded.Tune)
}

private val SUPPORTED_LANGUAGES = listOf(
    "Bahasa Indonesia",
    "Bahasa Inggris",
    "Bahasa Jepang",
    "Bahasa Korea",
    "Bahasa Mandarin",
    "Bahasa Arab",
    "Bahasa Spanyol",
    "Bahasa Prancis",
    "Bahasa Jerman"
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
    val sharedUrl by LiveSubStateHolder.sharedVideoUrl.collectAsStateWithLifecycle()

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
                                text = if (runtime.isOverlayActive) "Overlay aktif" else "Terjemahan video real-time",
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
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
                            onConfigChange = onSaveConfig
                        )
                        AppTab.SHARE_PLAYER -> SharePlayerScreen(
                            sharedUrl = sharedUrl,
                            targetLanguage = config.targetLanguage,
                            onLaunchOverlayForLink = onStartLiveCapture
                        )
                        AppTab.APPEARANCE -> AppearanceAndAiScreen(
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
    onConfigChange: (OverlayConfig) -> Unit
) {
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
                            "Status: ${runtime.status.label}. Buka YouTube, TikTok, Reels, atau browser untuk melihat subtitle."
                        } else {
                            "Tangkap suara video dari aplikasi lain secara langsung. Audio tidak disimpan di perangkat."
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

        item {
            OutlinedCard(shape = RoundedCornerShape(16.dp)) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(
                        text = "Bahasa terjemahan",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold
                    )
                    LanguageSelectorField(
                        label = "Bahasa sumber",
                        selected = config.sourceLanguage,
                        options = listOf("Deteksi otomatis") + SUPPORTED_LANGUAGES,
                        onSelected = { onConfigChange(config.copy(sourceLanguage = it)) }
                    )
                    LanguageSelectorField(
                        label = "Terjemahkan ke",
                        selected = config.targetLanguage,
                        options = SUPPORTED_LANGUAGES,
                        onSelected = { onConfigChange(config.copy(targetLanguage = it)) }
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
private fun SharePlayerScreen(
    sharedUrl: String?,
    targetLanguage: String,
    onLaunchOverlayForLink: () -> Unit
) {
    var manualUrl by remember(sharedUrl) { mutableStateOf(sharedUrl.orEmpty()) }

    LazyColumn(
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
        modifier = Modifier.fillMaxSize()
    ) {
        item {
            OutlinedCard(shape = RoundedCornerShape(18.dp)) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Rounded.Link, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                        Spacer(Modifier.width(8.dp))
                        Text(
                            text = "Terima tautan video (Share-to-LiveSub)",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                    Text(
                        text = "Bagikan tautan dari YouTube, TikTok, atau Reels ke LiveSub AI, atau tempel tautannya di bawah untuk menyiapkan terjemahan ke $targetLanguage.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    OutlinedTextField(
                        value = manualUrl,
                        onValueChange = { manualUrl = it },
                        label = { Text("Tautan video") },
                        placeholder = { Text("https://youtu.be/...") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    Button(
                        onClick = onLaunchOverlayForLink,
                        modifier = Modifier.fillMaxWidth().height(48.dp)
                    ) {
                        Icon(Icons.Rounded.PlayArrow, contentDescription = null)
                        Spacer(Modifier.width(8.dp))
                        Text("Siapkan overlay & putar video")
                    }
                }
            }
        }

        item {
            Card(
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)),
                shape = RoundedCornerShape(16.dp)
            ) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(
                        text = "Catatan kompatibilitas aplikasi video",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold
                    )
                    Text(
                        text = "• YouTube, TikTok, Reels, X, Telegram, dan browser mendukung tangkap audio internal secara langsung.\n• Aplikasi ber-DRM (seperti Netflix/Disney+) otomatis mendeteksi blokir dan menawarkan Mode Mikrofon.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}

@Composable
private fun AppearanceAndAiScreen(
    config: OverlayConfig,
    onSaveConfig: (OverlayConfig) -> Unit
) {
    var apiKeyInput by remember(config.geminiApiKey) { mutableStateOf(config.geminiApiKey) }
    var modelInput by remember(config.geminiModel) { mutableStateOf(config.geminiModel) }
    var previewLightBg by remember { mutableStateOf(false) }

    LazyColumn(
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
        modifier = Modifier.fillMaxSize()
    ) {
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
                            .height(140.dp)
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
                                text = "Mendengarkan suara video...",
                                fontSize = 11.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = Color(0xFF93C5FD),
                                style = TextStyle(
                                    shadow = Shadow(Color.Black, Offset(0f, 2f), 6f)
                                )
                            )
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
                                text = "Pertama, panaskan kaldu dengan api kecil agar aromanya keluar perlahan.",
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

        item {
            OutlinedCard(shape = RoundedCornerShape(18.dp)) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(
                        text = "Permukaan kotak & pelindung teks",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold
                    )

                    SurfaceStyle.entries.forEach { style ->
                        SelectableOptionRow(
                            title = style.label,
                            subtitle = style.subtitle,
                            selected = config.surfaceStyle == style,
                            onClick = { onSaveConfig(config.copy(surfaceStyle = style)) }
                        )
                    }

                    HorizontalDivider()

                    Text(
                        text = "Pelindung keterbacaan huruf",
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.SemiBold
                    )
                    TextProtection.entries.forEach { protection ->
                        SelectableOptionRow(
                            title = protection.label,
                            subtitle = protection.subtitle,
                            selected = config.textProtection == protection,
                            onClick = { onSaveConfig(config.copy(textProtection = protection)) }
                        )
                    }

                    HorizontalDivider()

                    Text("Ukuran teks: ${config.fontSizeSp.roundToInt()} sp", style = MaterialTheme.typography.labelLarge)
                    Slider(
                        value = config.fontSizeSp,
                        onValueChange = { onSaveConfig(config.copy(fontSizeSp = it)) },
                        valueRange = 14f..26f
                    )

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

        item {
            OutlinedCard(shape = RoundedCornerShape(18.dp)) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Rounded.Key, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                        Spacer(Modifier.width(8.dp))
                        Text(
                            text = "Koneksi Gemini AI",
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                    Text(
                        text = "API Key disimpan lokal di HP Anda saja (tidak pernah diunggah ke GitHub).",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    OutlinedTextField(
                        value = apiKeyInput,
                        onValueChange = { apiKeyInput = it },
                        label = { Text("Gemini API Key") },
                        visualTransformation = PasswordVisualTransformation(),
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    OutlinedTextField(
                        value = modelInput,
                        onValueChange = { modelInput = it },
                        label = { Text("Model Gemini") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    Button(
                        onClick = {
                            onSaveConfig(
                                config.copy(
                                    geminiApiKey = apiKeyInput.trim(),
                                    geminiModel = modelInput.trim().ifEmpty { "gemini-2.5-flash" }
                                )
                            )
                        },
                        modifier = Modifier.fillMaxWidth().height(46.dp)
                    ) {
                        Text("Simpan pengaturan AI")
                    }
                }
            }
        }
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
private fun LanguageSelectorField(
    label: String,
    selected: String,
    options: List<String>,
    onSelected: (String) -> Unit
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
                    text = selected,
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
                    text = { Text(option) },
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
