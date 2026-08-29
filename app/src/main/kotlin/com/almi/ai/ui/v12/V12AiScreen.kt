package com.almi.ai.ui.v12

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.almi.ai.data.preferences.AiMode
import com.almi.ai.data.preferences.CustomAiConfig
import com.almi.ai.data.repository.DiscoveredProvider
import com.almi.ai.data.repository.GoogleAiStudioModelInfo
import com.almi.ai.data.repository.GoogleOutputKind
import com.almi.ai.data.repository.ModelCapability
import com.almi.ai.data.repository.OpenRouterModelInfo
import com.almi.ai.ui.settings.SettingsViewModel

private enum class AiEngine { FREE, ROUTER, GOOGLE, CUSTOM }
private enum class GoogleTier { FREE, PAID }

@Composable
internal fun V12AiScreen(
    viewModel: SettingsViewModel,
    language: String,
    onBack: () -> Unit,
) {
    val p = V12Palettes.Ai
    val mode by viewModel.aiMode.collectAsState()
    val googleSettings by viewModel.googleAiStudioSettings.collectAsState()
    var engine by rememberSaveable {
        mutableStateOf(
            when {
                googleSettings.active -> AiEngine.GOOGLE
                mode == AiMode.FREE_AUTO -> AiEngine.FREE
                mode == AiMode.CUSTOM -> AiEngine.CUSTOM
                else -> AiEngine.ROUTER
            }
        )
    }

    Box(Modifier.fillMaxSize().background(worldBrush(p)).statusBarsPadding()) {
        Column(
            modifier = Modifier.fillMaxSize().padding(horizontal = 12.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Row(Modifier.fillMaxWidth(), Arrangement.SpaceBetween, Alignment.CenterVertically) {
                Column {
                    Text("ALMI / AI SPINE", color = p.signal, fontSize = 8.5.sp, fontWeight = FontWeight.Black, letterSpacing = 1.4.sp)
                    Text(if (language == "ar") "المحركات" else "ENGINES", color = p.ink, fontSize = 28.sp, fontWeight = FontWeight.Black, letterSpacing = (-.7).sp)
                }
                V12BackControl(p, if (language == "ar") "العوالم" else "WORLDS", onBack)
            }

            Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Column(
                    modifier = Modifier.width(72.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    EngineNode("FREE", engine == AiEngine.FREE, p) { engine = AiEngine.FREE }
                    EngineNode("ROUTER", engine == AiEngine.ROUTER, p) { engine = AiEngine.ROUTER }
                    EngineNode("GOOGLE", engine == AiEngine.GOOGLE, p) { engine = AiEngine.GOOGLE }
                    EngineNode("CUSTOM", engine == AiEngine.CUSTOM, p) { engine = AiEngine.CUSTOM }
                    Spacer(Modifier.weight(1f))
                    Surface(shape = CircleShape, color = p.signal.copy(alpha = .12f), border = BorderStroke(1.dp, p.edge)) {
                        V12Glyph(V12GlyphType.AI, p.signal, Modifier.padding(12.dp).size(28.dp))
                    }
                }

                Surface(
                    modifier = Modifier.weight(1f).fillMaxSize(),
                    shape = RoundedCornerShape(topStart = 42.dp, topEnd = 12.dp, bottomEnd = 42.dp, bottomStart = 12.dp),
                    color = p.panel,
                    border = BorderStroke(1.dp, p.edge),
                ) {
                    when (engine) {
                        AiEngine.FREE -> FreeEngine(viewModel, language, p)
                        AiEngine.ROUTER -> RouterEngine(viewModel, language, p)
                        AiEngine.GOOGLE -> GoogleEngine(viewModel, language, p)
                        AiEngine.CUSTOM -> CustomEngine(viewModel, language, p)
                    }
                }
            }
        }
    }
}

@Composable
private fun EngineNode(label: String, active: Boolean, p: V12Palette, onClick: () -> Unit) {
    Surface(
        modifier = Modifier.fillMaxWidth().height(if (active) 76.dp else 58.dp).clickable(onClick = onClick),
        shape = RoundedCornerShape(topStart = if (active) 30.dp else 16.dp, topEnd = 10.dp, bottomEnd = if (active) 30.dp else 16.dp, bottomStart = 10.dp),
        color = if (active) p.signal else p.panel,
        border = BorderStroke(1.dp, if (active) p.signal else p.edge),
    ) {
        Box(contentAlignment = Alignment.Center) {
            Text(label, color = if (active) p.signalInk else p.muted, fontSize = 8.sp, fontWeight = FontWeight.Black, letterSpacing = .7.sp)
        }
    }
}

@Composable
private fun EngineHeader(code: String, title: String, subtitle: String, p: V12Palette) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(code, color = p.signal, fontSize = 8.sp, fontWeight = FontWeight.Black, letterSpacing = 1.2.sp)
        Text(title, color = p.ink, fontSize = 22.sp, fontWeight = FontWeight.Black)
        Text(subtitle, color = p.muted, fontSize = 10.sp, lineHeight = 14.sp)
    }
}

@Composable
private fun FreeEngine(viewModel: SettingsViewModel, language: String, p: V12Palette) {
    val state by viewModel.providerDiscoveryState.collectAsState()
    val mode by viewModel.aiMode.collectAsState()
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(14.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        EngineHeader(
            "00 / FREE",
            if (language == "ar") "بدون مفتاح شخصي" else "NO PERSONAL KEY",
            if (language == "ar") "يفحص مزودين حقيقيين ويعرض فقط ما يمكن لـALMI تشغيله دون مفتاحك." else "Scans real providers and only exposes routes ALMI can operate without your personal key.",
            p,
        )
        V12SignalButton(
            text = if (state.isChecking) (if (language == "ar") "جاري الفحص…" else "SCANNING…") else (if (language == "ar") "افحص الآن" else "SCAN NOW"),
            palette = p,
            onClick = viewModel::discoverFreeProviders,
            modifier = Modifier.fillMaxWidth(),
            glyph = V12GlyphType.AI,
        )
        if (state.isChecking) CircularProgressIndicator(color = p.signal, strokeWidth = 2.dp, modifier = Modifier.size(28.dp))
        state.result.providers.forEach { provider ->
            ProviderRow(provider, active = mode == AiMode.FREE_AUTO && state.activeProviderId == provider.id, p = p) {
                viewModel.activateDiscoveredProvider(provider.id)
            }
        }
        if (state.result.providers.isNotEmpty()) {
            Text("SCANNED ${state.result.scannedCount} / EXCLUDED ${state.result.excludedCount}", color = p.muted, fontSize = 7.5.sp, fontWeight = FontWeight.Bold, letterSpacing = .8.sp)
        }
        state.error?.let { Text(if (language == "ar") "فشل فحص المزودين" else "PROVIDER SCAN FAILED", color = Color(0xFFFF8A8F), fontSize = 9.sp, fontWeight = FontWeight.Bold) }
    }
}

@Composable
private fun ProviderRow(provider: DiscoveredProvider, active: Boolean, p: V12Palette, onClick: () -> Unit) {
    val usable = provider.connected && provider.integrated && !provider.requiresPersonalApiKey
    Surface(
        modifier = Modifier.fillMaxWidth().clickable(enabled = usable, onClick = onClick),
        shape = RoundedCornerShape(topStart = 24.dp, topEnd = 10.dp, bottomEnd = 24.dp, bottomStart = 10.dp),
        color = if (active) p.signal.copy(alpha = .16f) else p.background,
        border = BorderStroke(1.dp, if (active) p.signal else p.edge),
    ) {
        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Surface(Modifier.size(34.dp), CircleShape, color = if (usable) p.signal.copy(alpha = .12f) else p.edge.copy(alpha = .28f)) {
                Box(contentAlignment = Alignment.Center) { V12Glyph(V12GlyphType.AI, if (usable) p.signal else p.muted, Modifier.size(18.dp)) }
            }
            Spacer(Modifier.width(9.dp))
            Column(Modifier.weight(1f)) {
                Text(provider.name, color = p.ink, fontSize = 11.sp, fontWeight = FontWeight.Black)
                Text(buildString {
                    if (provider.supportsText) append("TXT ")
                    if (provider.supportsImage) append("IMG ")
                    if (provider.supportsVideo) append("VID")
                }.trim(), color = p.muted, fontSize = 7.5.sp, fontWeight = FontWeight.Bold, letterSpacing = .7.sp)
            }
            Text(if (active) "ACTIVE" else if (usable) "READY" else "BLOCKED", color = if (active || usable) p.signal else p.muted, fontSize = 7.5.sp, fontWeight = FontWeight.Black)
        }
    }
}

@Composable
private fun RouterEngine(viewModel: SettingsViewModel, language: String, p: V12Palette) {
    val config by viewModel.openRouterConfig.collectAsState()
    val state by viewModel.openRouterState.collectAsState()
    val keys by viewModel.apiKeys.collectAsState()
    val oauth by viewModel.oauthState.collectAsState()
    var key by rememberSaveable { mutableStateOf("") }
    var query by rememberSaveable { mutableStateOf("") }
    var capability by rememberSaveable { mutableStateOf(ModelCapability.TEXT.name) }
    val cap = runCatching { ModelCapability.valueOf(capability) }.getOrDefault(ModelCapability.TEXT)
    val catalog = state.catalog.filtered(config.freeOnly)
    val models = when (cap) {
        ModelCapability.TEXT -> catalog.textModels
        ModelCapability.IMAGE -> catalog.imageModels
        ModelCapability.VIDEO -> catalog.videoModels
    }.filter { query.isBlank() || it.name.contains(query, true) || it.id.contains(query, true) }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(14.dp), verticalArrangement = Arrangement.spacedBy(11.dp)) {
        EngineHeader("01 / ROUTER", "OPENROUTER", if (language == "ar") "اتصال تلقائي أو مفتاح يدوي، مع كل كتالوج النماذج." else "OAuth or manual key, with the full live model catalog.", p)

        Row(horizontalArrangement = Arrangement.spacedBy(7.dp)) {
            MiniAction(if (oauth.isConnecting) "CONNECTING" else "AUTO CONNECT", active = false, p, Modifier.weight(1f)) { viewModel.connectOpenRouterAutomatically() }
            MiniAction(if (config.freeOnly) "FREE ONLY" else "ALL MODELS", active = config.freeOnly, p, Modifier.weight(1f)) { viewModel.setOpenRouterFreeOnly(!config.freeOnly) }
        }
        if (keys.isEmpty()) {
            OutlinedTextField(
                value = key,
                onValueChange = { key = it },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
                placeholder = { Text("OPENROUTER API KEY") },
                shape = RoundedCornerShape(14.dp),
            )
            MiniAction(if (language == "ar") "حفظ المفتاح" else "SAVE KEY", false, p, Modifier.fillMaxWidth()) {
                viewModel.addManualOpenRouterKey(key, config.freeOnly); key = ""
            }
        } else {
            keys.forEach { record ->
                Surface(Modifier.fillMaxWidth(), RoundedCornerShape(13.dp), color = p.background, border = BorderStroke(1.dp, p.edge)) {
                    Row(Modifier.padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(record.label, Modifier.weight(1f), color = p.ink, fontSize = 9.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(if (record.enabled) "ON" else "OFF", Modifier.clickable { viewModel.setApiKeyEnabled(record.id, !record.enabled) }.padding(6.dp), color = if (record.enabled) p.signal else p.muted, fontSize = 8.sp, fontWeight = FontWeight.Black)
                        Text("×", Modifier.clickable { viewModel.removeApiKey(record.id) }.padding(6.dp), color = Color(0xFFFF8A8F), fontSize = 15.sp)
                    }
                }
            }
        }

        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            ModelTab("TEXT", cap == ModelCapability.TEXT, p, Modifier.weight(1f)) { capability = ModelCapability.TEXT.name }
            ModelTab("IMAGE", cap == ModelCapability.IMAGE, p, Modifier.weight(1f)) { capability = ModelCapability.IMAGE.name }
            ModelTab("VIDEO", cap == ModelCapability.VIDEO, p, Modifier.weight(1f)) { capability = ModelCapability.VIDEO.name }
        }
        OutlinedTextField(value = query, onValueChange = { query = it }, modifier = Modifier.fillMaxWidth(), singleLine = true, placeholder = { Text(if (language == "ar") "ابحث عن موديل" else "SEARCH MODEL") }, shape = RoundedCornerShape(14.dp))
        if (state.isLoading) CircularProgressIndicator(color = p.signal, strokeWidth = 2.dp, modifier = Modifier.size(28.dp))
        models.forEach { model ->
            RouterModelRow(model, selected = selectedRouterId(config, cap) == model.id, p = p) { viewModel.selectOpenRouterModel(cap, model.id) }
        }
        if (models.isEmpty() && !state.isLoading) {
            MiniAction(if (language == "ar") "تحديث الكتالوج" else "REFRESH CATALOG", false, p, Modifier.fillMaxWidth(), viewModel::refreshOpenRouter)
        }
    }
}

@Composable
private fun RouterModelRow(model: OpenRouterModelInfo, selected: Boolean, p: V12Palette, onClick: () -> Unit) {
    Surface(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick),
        shape = RoundedCornerShape(topStart = 18.dp, topEnd = 9.dp, bottomEnd = 18.dp, bottomStart = 9.dp),
        color = if (selected) p.signal.copy(alpha = .14f) else p.background,
        border = BorderStroke(1.dp, if (selected) p.signal else p.edge),
    ) {
        Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Row(Modifier.fillMaxWidth(), Arrangement.SpaceBetween) {
                Text(model.name, Modifier.weight(1f), color = p.ink, fontSize = 10.sp, fontWeight = FontWeight.Black, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(if (model.isFree) "FREE" else "PAID", color = if (model.isFree) p.signal else p.muted, fontSize = 7.sp, fontWeight = FontWeight.Black)
            }
            Text(model.id, color = p.muted, fontSize = 7.5.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

@Composable
private fun GoogleEngine(viewModel: SettingsViewModel, language: String, p: V12Palette) {
    val state by viewModel.googleAiStudioState.collectAsState()
    val settings by viewModel.googleAiStudioSettings.collectAsState()
    var key by rememberSaveable { mutableStateOf("") }
    var query by rememberSaveable { mutableStateOf("") }
    var tierName by rememberSaveable { mutableStateOf(GoogleTier.FREE.name) }
    var kindName by rememberSaveable { mutableStateOf(GoogleOutputKind.TEXT.name) }
    val tier = runCatching { GoogleTier.valueOf(tierName) }.getOrDefault(GoogleTier.FREE)
    val kind = runCatching { GoogleOutputKind.valueOf(kindName) }.getOrDefault(GoogleOutputKind.TEXT)
    val source = if (tier == GoogleTier.FREE) state.catalog.freeModels else state.catalog.paidModels
    val models = source.filter { it.outputKind == kind && (query.isBlank() || it.name.contains(query, true) || it.id.contains(query, true)) }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(14.dp), verticalArrangement = Arrangement.spacedBy(11.dp)) {
        EngineHeader("02 / GOOGLE", "GOOGLE AI STUDIO", if (language == "ar") "مفتاح واحد، كتالوج مجاني ومدفوع، وتحديد نص/صورة/فيديو." else "One key, free + paid catalogs, explicit text/image/video routing.", p)
        if (!settings.connected && !state.connected) {
            OutlinedTextField(value = key, onValueChange = { key = it }, modifier = Modifier.fillMaxWidth(), singleLine = true, visualTransformation = PasswordVisualTransformation(), placeholder = { Text("GOOGLE AI STUDIO API KEY") }, shape = RoundedCornerShape(14.dp))
            MiniAction(if (state.isConnecting) "CONNECTING…" else "CONNECT", false, p, Modifier.fillMaxWidth()) { viewModel.connectGoogleAiStudio(key) }
        } else {
            Row(horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                MiniAction("REFRESH", false, p, Modifier.weight(1f), viewModel::refreshGoogleAiStudio)
                MiniAction("DISCONNECT", false, p, Modifier.weight(1f), viewModel::disconnectGoogleAiStudio)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                ModelTab("FREE", tier == GoogleTier.FREE, p, Modifier.weight(1f)) { tierName = GoogleTier.FREE.name }
                ModelTab("PAID", tier == GoogleTier.PAID, p, Modifier.weight(1f)) { tierName = GoogleTier.PAID.name }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                ModelTab("TEXT", kind == GoogleOutputKind.TEXT, p, Modifier.weight(1f)) { kindName = GoogleOutputKind.TEXT.name }
                ModelTab("IMAGE", kind == GoogleOutputKind.IMAGE, p, Modifier.weight(1f)) { kindName = GoogleOutputKind.IMAGE.name }
                ModelTab("VIDEO", kind == GoogleOutputKind.VIDEO, p, Modifier.weight(1f)) { kindName = GoogleOutputKind.VIDEO.name }
            }
            OutlinedTextField(value = query, onValueChange = { query = it }, modifier = Modifier.fillMaxWidth(), singleLine = true, placeholder = { Text(if (language == "ar") "ابحث" else "SEARCH") }, shape = RoundedCornerShape(14.dp))
            models.forEach { model -> GoogleModelRow(model, selectedGoogle(settings, model), tier, p) { if (tier == GoogleTier.FREE) viewModel.selectGoogleFreeModel(model) else viewModel.selectGooglePaidModel(model) } }
        }
        if (state.isConnecting) CircularProgressIndicator(color = p.signal, strokeWidth = 2.dp, modifier = Modifier.size(28.dp))
        state.error?.let { Text(if (language == "ar") "تعذر الاتصال بـGoogle" else "GOOGLE CONNECTION FAILED", color = Color(0xFFFF8A8F), fontSize = 9.sp, fontWeight = FontWeight.Bold) }
    }
}

@Composable
private fun GoogleModelRow(model: GoogleAiStudioModelInfo, selected: Boolean, tier: GoogleTier, p: V12Palette, onClick: () -> Unit) {
    Surface(Modifier.fillMaxWidth().clickable(onClick = onClick), RoundedCornerShape(topStart = 18.dp, topEnd = 9.dp, bottomEnd = 18.dp, bottomStart = 9.dp), color = if (selected) p.signal.copy(alpha = .14f) else p.background, border = BorderStroke(1.dp, if (selected) p.signal else p.edge)) {
        Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Row(Modifier.fillMaxWidth(), Arrangement.SpaceBetween) {
                Text(model.name, Modifier.weight(1f), color = p.ink, fontSize = 10.sp, fontWeight = FontWeight.Black, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(tier.name, color = if (tier == GoogleTier.FREE) p.signal else p.muted, fontSize = 7.sp, fontWeight = FontWeight.Black)
            }
            Text(if (tier == GoogleTier.PAID) model.paidPriceLabel else model.id, color = p.muted, fontSize = 7.5.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

@Composable
private fun CustomEngine(viewModel: SettingsViewModel, language: String, p: V12Palette) {
    val saved by viewModel.customAiConfig.collectAsState()
    var provider by remember(saved) { mutableStateOf(saved.providerName) }
    var base by remember(saved) { mutableStateOf(saved.baseUrl) }
    var key by remember { mutableStateOf(saved.apiKey) }
    var analysisEndpoint by remember(saved) { mutableStateOf(saved.analysisEndpoint) }
    var analysisModel by remember(saved) { mutableStateOf(saved.analysisModel) }
    var imageEndpoint by remember(saved) { mutableStateOf(saved.imageEndpoint) }
    var imageModel by remember(saved) { mutableStateOf(saved.imageModel) }
    var videoEndpoint by remember(saved) { mutableStateOf(saved.videoEndpoint) }
    var videoModel by remember(saved) { mutableStateOf(saved.videoModel) }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        EngineHeader("03 / CUSTOM", if (language == "ar") "محركك الخاص" else "YOUR OWN ENGINE", if (language == "ar") "لا نخفي أي endpoint أو model id." else "Every endpoint and model id stays explicit.", p)
        Field(provider, { provider = it }, "PROVIDER")
        Field(base, { base = it }, "BASE URL")
        OutlinedTextField(value = key, onValueChange = { key = it }, modifier = Modifier.fillMaxWidth(), singleLine = true, visualTransformation = PasswordVisualTransformation(), placeholder = { Text("API KEY") }, shape = RoundedCornerShape(14.dp))
        SectionLabel("ANALYSIS", p)
        Field(analysisEndpoint, { analysisEndpoint = it }, "ENDPOINT")
        Field(analysisModel, { analysisModel = it }, "MODEL")
        SectionLabel("IMAGE", p)
        Field(imageEndpoint, { imageEndpoint = it }, "ENDPOINT")
        Field(imageModel, { imageModel = it }, "MODEL")
        SectionLabel("VIDEO", p)
        Field(videoEndpoint, { videoEndpoint = it }, "ENDPOINT")
        Field(videoModel, { videoModel = it }, "MODEL")
        V12SignalButton(
            text = if (language == "ar") "حفظ وتشغيل" else "SAVE + ACTIVATE",
            palette = p,
            modifier = Modifier.fillMaxWidth(),
            glyph = V12GlyphType.AI,
            onClick = {
                viewModel.saveAndActivateCustom(
                    CustomAiConfig(provider, base, key, analysisEndpoint, analysisModel, imageEndpoint, imageModel, videoEndpoint, videoModel)
                )
            },
        )
    }
}

@Composable
private fun Field(value: String, onChange: (String) -> Unit, placeholder: String) {
    OutlinedTextField(value = value, onValueChange = onChange, modifier = Modifier.fillMaxWidth(), singleLine = true, placeholder = { Text(placeholder) }, shape = RoundedCornerShape(14.dp))
}

@Composable
private fun SectionLabel(text: String, p: V12Palette) { Text(text, color = p.signal, fontSize = 8.sp, fontWeight = FontWeight.Black, letterSpacing = 1.1.sp) }

@Composable
private fun MiniAction(text: String, active: Boolean, p: V12Palette, modifier: Modifier, onClick: () -> Unit) {
    Surface(modifier.height(42.dp).clickable(onClick = onClick), RoundedCornerShape(999.dp), color = if (active) p.signal else p.background, border = BorderStroke(1.dp, if (active) p.signal else p.edge)) {
        Box(contentAlignment = Alignment.Center) { Text(text, color = if (active) p.signalInk else p.ink, fontSize = 8.sp, fontWeight = FontWeight.Black, letterSpacing = .6.sp) }
    }
}

@Composable
private fun ModelTab(text: String, active: Boolean, p: V12Palette, modifier: Modifier, onClick: () -> Unit) = MiniAction(text, active, p, modifier, onClick)

private fun selectedRouterId(config: com.almi.ai.data.preferences.OpenRouterConfig, capability: ModelCapability): String = when (capability) {
    ModelCapability.TEXT -> config.analysisModel
    ModelCapability.IMAGE -> config.imageModel
    ModelCapability.VIDEO -> config.videoModel
}

private fun selectedGoogle(settings: com.almi.ai.data.preferences.GoogleAiStudioSettings, model: GoogleAiStudioModelInfo): Boolean = when (model.outputKind) {
    GoogleOutputKind.TEXT -> settings.textModelId == model.id
    GoogleOutputKind.IMAGE -> settings.imageModelId == model.id
    GoogleOutputKind.VIDEO -> settings.videoModelId == model.id
}
