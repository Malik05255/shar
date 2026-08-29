package com.almi.ai.ui.settings

import androidx.activity.compose.BackHandler
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.ArrowForward
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.Cloud
import androidx.compose.material.icons.outlined.ExpandLess
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material.icons.outlined.Key
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Route
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.SmartDisplay
import androidx.compose.material.icons.outlined.TextFields
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.almi.ai.data.preferences.AiMode
import com.almi.ai.data.preferences.CustomAiConfig
import com.almi.ai.data.preferences.OpenRouterConfig
import com.almi.ai.data.repository.DiscoveredProvider
import com.almi.ai.data.repository.GoogleAiStudioModelInfo
import com.almi.ai.data.repository.GoogleModelSpeed
import com.almi.ai.data.repository.GoogleOutputKind
import com.almi.ai.data.repository.ModelCapability
import com.almi.ai.data.repository.OpenRouterCatalog
import com.almi.ai.data.repository.OpenRouterModelInfo
import com.almi.ai.ui.components.AiOrb3D
import com.almi.ai.ui.components.ConnectionPill
import com.almi.ai.ui.components.DimensionCard
import com.almi.ai.ui.components.Glossy3DIcon

private enum class AiPage { HOME, OPENROUTER, GOOGLE, CUSTOM, FREE }
private enum class OpenRouterConnectMode { AUTO, MANUAL }
private enum class CustomMediaType { IMAGE, VIDEO }

@Composable
fun AiCenterScreen(
    viewModel: SettingsViewModel,
    language: String,
) {
    val aiMode by viewModel.aiMode.collectAsState()
    val googleSettings by viewModel.googleAiStudioSettings.collectAsState()
    var page by rememberSaveable { mutableStateOf(AiPage.HOME) }

    BackHandler(enabled = page != AiPage.HOME) { page = AiPage.HOME }

    when (page) {
        AiPage.HOME -> AiHome(
            mode = aiMode,
            googleConnected = googleSettings.connected,
            language = language,
            onOpen = { page = it },
        )
        AiPage.OPENROUTER -> OpenRouterPane(viewModel, language) { page = AiPage.HOME }
        AiPage.GOOGLE -> GoogleAiStudioPane(viewModel, language) { page = AiPage.HOME }
        AiPage.CUSTOM -> CustomPane(viewModel, language) { page = AiPage.HOME }
        AiPage.FREE -> FreePane(viewModel, language) { page = AiPage.HOME }
    }
}

@Composable
private fun AiHome(
    mode: AiMode,
    googleConnected: Boolean,
    language: String,
    onOpen: (AiPage) -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 18.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Row(
            Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Column {
                Text("ALMI", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Black)
                Text(
                    if (language == "ar") "إعدادات الذكاء الاصطناعي" else "AI settings",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            ConnectionPill(currentEngineName(mode, language))
        }

        DimensionCard(emphasized = true) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(18.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                AiOrb3D(label = "AI")
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                    Text(
                        if (language == "ar") "موديل الذكاء الاصطناعي الحالي" else "Current AI model",
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.Black,
                    )
                    Text(
                        currentEngineName(mode, language),
                        color = MaterialTheme.colorScheme.primary,
                        fontWeight = FontWeight.Bold,
                    )
                }
            }
        }

        GatewayRow(
            title = "OpenRouter",
            subtitle = if (language == "ar") "ربط مباشر أو API يدوي" else "Direct connect or manual API",
            active = mode == AiMode.OPENROUTER,
            icon = Icons.Outlined.Route,
            onClick = { onOpen(AiPage.OPENROUTER) },
        )
        GatewayRow(
            title = "Google AI Studio API",
            subtitle = if (language == "ar") "مفتاح واحد • موديلات مجانية ومدفوعة" else "One key • free and paid model catalogs",
            active = false,
            connected = googleConnected,
            icon = Icons.Outlined.Cloud,
            onClick = { onOpen(AiPage.GOOGLE) },
        )
        GatewayRow(
            title = if (language == "ar") "API مخصص" else "Custom API",
            subtitle = if (language == "ar") "إنشاء الصور والفيديو" else "Image and video generation",
            active = mode == AiMode.CUSTOM,
            icon = Icons.Outlined.Key,
            onClick = { onOpen(AiPage.CUSTOM) },
        )
        GatewayRow(
            title = if (language == "ar") "ذكاء اصطناعي مجاني" else "Free AI",
            subtitle = if (language == "ar") "بدون مفتاح شخصي" else "No personal API key",
            active = mode == AiMode.FREE_AUTO,
            icon = Icons.Outlined.AutoAwesome,
            onClick = { onOpen(AiPage.FREE) },
        )
        Spacer(Modifier.height(8.dp))
    }
}

@Composable
private fun GatewayRow(
    title: String,
    subtitle: String,
    active: Boolean,
    connected: Boolean = active,
    icon: ImageVector,
    onClick: () -> Unit,
) {
    DimensionCard(onClick = onClick, emphasized = active) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(15.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(13.dp),
        ) {
            Glossy3DIcon(icon, active = active || connected)
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            when {
                active -> ConnectionPill("ON")
                connected -> ConnectionPill("Connected")
                else -> Icon(Icons.AutoMirrored.Outlined.ArrowForward, contentDescription = null)
            }
        }
    }
}

@Composable
private fun GoogleAiStudioPane(
    viewModel: SettingsViewModel,
    language: String,
    onBack: () -> Unit,
) {
    val state by viewModel.googleAiStudioState.collectAsState()
    val settings by viewModel.googleAiStudioSettings.collectAsState()
    var apiKey by remember { mutableStateOf("") }
    var freeExpanded by rememberSaveable { mutableStateOf(true) }
    var paidExpanded by rememberSaveable { mutableStateOf(false) }
    var freeQuery by rememberSaveable { mutableStateOf("") }
    var paidQuery by rememberSaveable { mutableStateOf("") }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 18.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        SubHeader(
            onBack = onBack,
            title = "Google AI Studio API",
            subtitle = if (settings.connected || state.connected) {
                if (language == "ar") "متصل • النماذج جاهزة" else "Connected • models ready"
            } else {
                if (language == "ar") "أدخل API فقط ثم اضغط ربط" else "Enter your API key, then connect"
            },
        )

        DimensionCard(emphasized = settings.connected || state.connected) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Row(
                    Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Glossy3DIcon(Icons.Outlined.Cloud, active = settings.connected || state.connected)
                    Column(Modifier.weight(1f)) {
                        Text(
                            if (language == "ar") "اتصال Google" else "Google connection",
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Black,
                        )
                        Text(
                            if (settings.connected || state.connected) {
                                if (language == "ar") "تم التحقق من المفتاح وحفظه بأمان على الجهاز" else "Key verified and stored securely on-device"
                            } else {
                                if (language == "ar") "يتم التحقق مباشرة من Gemini Models API" else "Validated directly against the Gemini Models API"
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    ConnectionPill(
                        text = if (settings.connected || state.connected) {
                            if (language == "ar") "متصل" else "Connected"
                        } else {
                            if (language == "ar") "غير متصل" else "Offline"
                        },
                        connected = settings.connected || state.connected,
                    )
                }

                if (!settings.connected && !state.connected) {
                    OutlinedTextField(
                        value = apiKey,
                        onValueChange = { apiKey = it },
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text("Google AI Studio API key") },
                        singleLine = true,
                        visualTransformation = PasswordVisualTransformation(),
                        shape = RoundedCornerShape(18.dp),
                    )
                    Button(
                        onClick = { viewModel.connectGoogleAiStudio(apiKey) },
                        enabled = apiKey.isNotBlank() && !state.isConnecting,
                        modifier = Modifier.fillMaxWidth().height(52.dp),
                        shape = RoundedCornerShape(18.dp),
                    ) {
                        if (state.isConnecting) {
                            CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                            Spacer(Modifier.size(8.dp))
                        } else {
                            Icon(Icons.Outlined.Cloud, contentDescription = null)
                            Spacer(Modifier.size(8.dp))
                        }
                        Text(if (language == "ar") "ربط Google AI Studio" else "Connect Google AI Studio")
                    }
                } else {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(
                            onClick = viewModel::refreshGoogleAiStudio,
                            modifier = Modifier.weight(1f),
                        ) {
                            Icon(Icons.Outlined.Refresh, contentDescription = null)
                            Spacer(Modifier.size(6.dp))
                            Text(if (language == "ar") "تحديث النماذج" else "Refresh models")
                        }
                        OutlinedButton(
                            onClick = viewModel::disconnectGoogleAiStudio,
                            modifier = Modifier.weight(1f),
                        ) {
                            Text(if (language == "ar") "فصل" else "Disconnect")
                        }
                    }
                }

                if (!state.error.isNullOrBlank()) {
                    Text(
                        if (language == "ar") "تعذر الربط. تأكد من المفتاح وحاول مرة أخرى." else "Connection failed. Check the key and try again.",
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        }

        if (state.isConnecting && (settings.connected || state.connected)) {
            Box(Modifier.fillMaxWidth().height(70.dp), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
        }

        if (settings.connected || state.connected) {
            GoogleModelDropdown(
                title = if (language == "ar") "الموديلات المجانية" else "Free models",
                subtitle = if (language == "ar") "النماذج التي لديها Free Tier رسمي" else "Models with an official Free Tier",
                models = state.catalog.freeModels,
                expanded = freeExpanded,
                onExpandedChange = { freeExpanded = !freeExpanded },
                query = freeQuery,
                onQueryChange = { freeQuery = it },
                selectedId = settings.freeModelId,
                paid = false,
                language = language,
                onSelect = viewModel::selectGoogleFreeModel,
            )

            GoogleModelDropdown(
                title = if (language == "ar") "الموديلات المدفوعة" else "Paid models",
                subtitle = if (language == "ar") "السعر والسرعة والوسائط في بطاقة واحدة" else "Price, speed and media support in one card",
                models = state.catalog.paidModels,
                expanded = paidExpanded,
                onExpandedChange = { paidExpanded = !paidExpanded },
                query = paidQuery,
                onQueryChange = { paidQuery = it },
                selectedId = settings.paidModelId,
                paid = true,
                language = language,
                onSelect = viewModel::selectGooglePaidModel,
            )

            DimensionCard {
                Text(
                    if (language == "ar") {
                        "ملاحظة: بعض موديلات Google تظهر في المجاني والمدفوع معًا لأن نفس الموديل قد يملك Free Tier وتسعير Paid Tier. ALMI يعرض الخطة، وليس اسمًا وهميًا مختلفًا للموديل."
                    } else {
                        "Note: a Google model can appear in both lists because the same model can have both Free and Paid tiers. ALMI shows the tier, not a fake duplicate model."
                    },
                    modifier = Modifier.padding(15.dp),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Spacer(Modifier.height(18.dp))
    }
}

@Composable
private fun GoogleModelDropdown(
    title: String,
    subtitle: String,
    models: List<GoogleAiStudioModelInfo>,
    expanded: Boolean,
    onExpandedChange: () -> Unit,
    query: String,
    onQueryChange: (String) -> Unit,
    selectedId: String,
    paid: Boolean,
    language: String,
    onSelect: (GoogleAiStudioModelInfo) -> Unit,
) {
    val filtered = models.filter {
        query.isBlank() || it.name.contains(query, true) || it.id.contains(query, true)
    }
    DimensionCard(emphasized = expanded) {
        Column(Modifier.padding(15.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(
                Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Glossy3DIcon(
                    if (paid) Icons.Outlined.Key else Icons.Outlined.AutoAwesome,
                    active = expanded,
                )
                Column(Modifier.weight(1f)) {
                    Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Black)
                    Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                ConnectionPill(models.size.toString())
                IconButton(onClick = onExpandedChange) {
                    Icon(if (expanded) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore, contentDescription = null)
                }
            }

            if (expanded) {
                OutlinedTextField(
                    value = query,
                    onValueChange = onQueryChange,
                    modifier = Modifier.fillMaxWidth(),
                    placeholder = { Text(if (language == "ar") "ابحث داخل النماذج" else "Search models") },
                    leadingIcon = { Icon(Icons.Outlined.Search, contentDescription = null) },
                    singleLine = true,
                    shape = RoundedCornerShape(16.dp),
                )

                filtered.take(40).forEach { model ->
                    GoogleModelCard(
                        model = model,
                        selected = model.id == selectedId,
                        paid = paid,
                        language = language,
                        onClick = { onSelect(model) },
                    )
                }

                if (filtered.isEmpty()) {
                    Text(
                        if (language == "ar") "لا توجد موديلات مطابقة." else "No matching models.",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        }
    }
}

@Composable
private fun GoogleModelCard(
    model: GoogleAiStudioModelInfo,
    selected: Boolean,
    paid: Boolean,
    language: String,
    onClick: () -> Unit,
) {
    DimensionCard(onClick = onClick, emphasized = selected) {
        Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(
                Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Glossy3DIcon(googleModelIcon(model.outputKind), active = selected)
                Column(Modifier.weight(1f)) {
                    Text(model.name, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(model.id, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                if (selected) Icon(Icons.Outlined.Check, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
            }

            Row(horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                ConnectionPill(googleSpeedLabel(model.speed, language))
                ConnectionPill(googleCapabilityLabel(model, language))
            }

            if (paid) {
                Text(
                    model.paidPriceLabel,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.SemiBold,
                )
            } else {
                Text(
                    if (language == "ar") "Free Tier • يخضع لحدود Google للحساب والموديل" else "Free Tier • subject to Google account/model limits",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.tertiary,
                )
            }
        }
    }
}

private fun googleModelIcon(kind: GoogleOutputKind): ImageVector = when (kind) {
    GoogleOutputKind.TEXT -> Icons.Outlined.TextFields
    GoogleOutputKind.IMAGE -> Icons.Outlined.Image
    GoogleOutputKind.VIDEO -> Icons.Outlined.SmartDisplay
}

private fun googleSpeedLabel(speed: GoogleModelSpeed, language: String): String = when (speed) {
    GoogleModelSpeed.VERY_FAST -> if (language == "ar") "سريع جدًا" else "Very fast"
    GoogleModelSpeed.FAST -> if (language == "ar") "سريع" else "Fast"
    GoogleModelSpeed.BALANCED -> if (language == "ar") "متوازن" else "Balanced"
    GoogleModelSpeed.QUALITY -> if (language == "ar") "جودة أعلى" else "Quality"
}

private fun googleCapabilityLabel(model: GoogleAiStudioModelInfo, language: String): String = when (model.outputKind) {
    GoogleOutputKind.IMAGE -> if (language == "ar") "إنشاء صور • وسائط" else "Image generation • media"
    GoogleOutputKind.VIDEO -> if (language == "ar") "إنشاء فيديو • وسائط" else "Video generation • media"
    GoogleOutputKind.TEXT -> if (model.acceptsMedia) {
        if (language == "ar") "نص + فهم وسائط" else "Text + media understanding"
    } else {
        if (language == "ar") "نص" else "Text"
    }
}

@Composable
private fun OpenRouterPane(
    viewModel: SettingsViewModel,
    language: String,
    onBack: () -> Unit,
) {
    val state by viewModel.openRouterState.collectAsState()
    val config by viewModel.openRouterConfig.collectAsState()
    val keys by viewModel.apiKeys.collectAsState()
    val oauth by viewModel.oauthState.collectAsState()
    var connectMode by rememberSaveable { mutableStateOf(OpenRouterConnectMode.AUTO) }
    var manualKey by remember { mutableStateOf("") }
    var query by rememberSaveable { mutableStateOf("") }
    var capability by rememberSaveable { mutableStateOf(ModelCapability.IMAGE) }

    val directCatalog = state.catalog.filtered(true)
    val catalog = if (connectMode == OpenRouterConnectMode.AUTO) directCatalog else state.catalog.filtered(config.freeOnly)
    val models = modelsFor(catalog, capability).filter {
        query.isBlank() || it.name.contains(query, true) || it.id.contains(query, true)
    }
    val connected = state.keyStatus?.connected == true || oauth.connected

    Column(
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(18.dp),
        verticalArrangement = Arrangement.spacedBy(13.dp),
    ) {
        SubHeader(onBack, "OpenRouter", if (connected) {
            if (language == "ar") "متصل وجاهز" else "Connected and ready"
        } else {
            if (language == "ar") "اختر طريقة الاتصال" else "Choose a connection method"
        })

        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            ModeButton(
                selected = connectMode == OpenRouterConnectMode.AUTO,
                label = if (language == "ar") "ربط مباشر" else "Direct connect",
                onClick = {
                    connectMode = OpenRouterConnectMode.AUTO
                    viewModel.setOpenRouterFreeOnly(true)
                },
                modifier = Modifier.weight(1f),
            )
            ModeButton(
                selected = connectMode == OpenRouterConnectMode.MANUAL,
                label = if (language == "ar") "API يدوي" else "Manual API",
                onClick = { connectMode = OpenRouterConnectMode.MANUAL },
                modifier = Modifier.weight(1f),
            )
        }

        DimensionCard(emphasized = connected) {
            Column(Modifier.padding(15.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Glossy3DIcon(if (connectMode == OpenRouterConnectMode.AUTO) Icons.Outlined.Route else Icons.Outlined.Key, active = connected)
                    Spacer(Modifier.size(10.dp))
                    Column(Modifier.weight(1f)) {
                        Text(
                            if (connectMode == OpenRouterConnectMode.AUTO) {
                                if (language == "ar") "الربط المباشر" else "Direct connection"
                            } else {
                                if (language == "ar") "مفتاح OpenRouter" else "OpenRouter key"
                            },
                            fontWeight = FontWeight.Black,
                        )
                        Text(
                            if (language == "ar") "Fallback تلقائي عند فشل الموديل" else "Automatic model fallback",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    ConnectionPill(if (connected) (if (language == "ar") "متصل" else "Connected") else (if (language == "ar") "غير متصل" else "Offline"), connected)
                }

                if (connectMode == OpenRouterConnectMode.AUTO) {
                    Button(
                        onClick = if (connected) viewModel::refreshOpenRouter else viewModel::connectOpenRouterAutomatically,
                        enabled = !oauth.isConnecting,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(if (connected) {
                            if (language == "ar") "تحديث النماذج" else "Refresh models"
                        } else {
                            if (language == "ar") "اتصال مباشر بـ OpenRouter" else "Connect to OpenRouter"
                        })
                    }
                } else {
                    OutlinedTextField(
                        value = manualKey,
                        onValueChange = { manualKey = it },
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text("API key") },
                        visualTransformation = PasswordVisualTransformation(),
                        singleLine = true,
                    )
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(if (language == "ar") "مجاني فقط" else "Free only", modifier = Modifier.weight(1f))
                        Switch(checked = config.freeOnly, onCheckedChange = viewModel::setOpenRouterFreeOnly)
                    }
                    Button(
                        onClick = {
                            viewModel.addManualOpenRouterKey(manualKey, config.freeOnly)
                            manualKey = ""
                        },
                        enabled = manualKey.isNotBlank(),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(if (language == "ar") "حفظ وربط" else "Save and connect")
                    }
                    if (keys.isNotEmpty()) Text(
                        "${if (language == "ar") "المفاتيح المفعلة" else "Enabled keys"}: ${keys.count { it.enabled }}",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        }

        Row(horizontalArrangement = Arrangement.spacedBy(7.dp)) {
            FilterChip(capability == ModelCapability.IMAGE, { capability = ModelCapability.IMAGE }, { Text(if (language == "ar") "صور" else "Images") })
            FilterChip(capability == ModelCapability.VIDEO, { capability = ModelCapability.VIDEO }, { Text(if (language == "ar") "فيديو" else "Video") })
            FilterChip(capability == ModelCapability.TEXT, { capability = ModelCapability.TEXT }, { Text(if (language == "ar") "نص" else "Text") })
        }
        OutlinedTextField(
            value = query,
            onValueChange = { query = it },
            modifier = Modifier.fillMaxWidth(),
            placeholder = { Text(if (language == "ar") "بحث عن موديل" else "Search models") },
            leadingIcon = { Icon(Icons.Outlined.Search, contentDescription = null) },
            singleLine = true,
        )
        if (state.isLoading) {
            Box(Modifier.fillMaxWidth().height(80.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
        } else {
            models.take(30).forEach { model ->
                OpenRouterModelCard(model, selectedModel(config, capability) == model.id, language) {
                    viewModel.selectOpenRouterModel(capability, model.id)
                    viewModel.activateOpenRouter()
                }
            }
        }
    }
}

@Composable
private fun OpenRouterModelCard(
    model: OpenRouterModelInfo,
    selected: Boolean,
    language: String,
    onClick: () -> Unit,
) {
    DimensionCard(onClick = onClick, emphasized = selected) {
        Column(Modifier.padding(13.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(model.name, modifier = Modifier.weight(1f), fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (selected) Icon(Icons.Outlined.Check, contentDescription = null)
            }
            Text(model.id, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(
                if (model.isFree) {
                    if (language == "ar") "مجاني" else "Free"
                } else {
                    model.imageUsdPerUnit?.let { "$${"%.4f".format(it)}/image" }
                        ?: model.videoUsdPerSecond?.let { "$${"%.4f".format(it)}/sec" }
                        ?: model.inputUsdPerMillion?.let { "$${"%.2f".format(it)}/1M input" }
                        ?: if (language == "ar") "مدفوع" else "Paid"
                },
                color = if (model.isFree) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.primary,
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}

@Composable
private fun CustomPane(viewModel: SettingsViewModel, language: String, onBack: () -> Unit) {
    val saved by viewModel.customAiConfig.collectAsState()
    var provider by remember(saved) { mutableStateOf(saved.providerName) }
    var baseUrl by remember(saved) { mutableStateOf(saved.baseUrl) }
    var apiKey by remember(saved) { mutableStateOf(saved.apiKey) }
    var imageEndpoint by remember(saved) { mutableStateOf(saved.imageEndpoint) }
    var imageModel by remember(saved) { mutableStateOf(saved.imageModel) }
    var videoEndpoint by remember(saved) { mutableStateOf(saved.videoEndpoint) }
    var videoModel by remember(saved) { mutableStateOf(saved.videoModel) }
    var mediaType by rememberSaveable { mutableStateOf(CustomMediaType.IMAGE) }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(18.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        SubHeader(onBack, if (language == "ar") "API مخصص" else "Custom API", if (language == "ar") "صور وفيديو فقط" else "Image and video only")
        DimensionCard {
            Column(Modifier.padding(15.dp), verticalArrangement = Arrangement.spacedBy(9.dp)) {
                OutlinedTextField(provider, { provider = it }, Modifier.fillMaxWidth(), label = { Text(if (language == "ar") "اسم المزود" else "Provider") }, singleLine = true)
                OutlinedTextField(baseUrl, { baseUrl = it }, Modifier.fillMaxWidth(), label = { Text("Base URL") }, singleLine = true)
                OutlinedTextField(apiKey, { apiKey = it }, Modifier.fillMaxWidth(), label = { Text("API key") }, visualTransformation = PasswordVisualTransformation(), singleLine = true)
            }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            ModeButton(mediaType == CustomMediaType.IMAGE, if (language == "ar") "إنشاء الصور" else "Images", { mediaType = CustomMediaType.IMAGE }, Modifier.weight(1f))
            ModeButton(mediaType == CustomMediaType.VIDEO, if (language == "ar") "إنشاء الفيديو" else "Video", { mediaType = CustomMediaType.VIDEO }, Modifier.weight(1f))
        }
        DimensionCard {
            Column(Modifier.padding(15.dp), verticalArrangement = Arrangement.spacedBy(9.dp)) {
                if (mediaType == CustomMediaType.IMAGE) {
                    OutlinedTextField(imageEndpoint, { imageEndpoint = it }, Modifier.fillMaxWidth(), label = { Text("Image endpoint") }, singleLine = true)
                    OutlinedTextField(imageModel, { imageModel = it }, Modifier.fillMaxWidth(), label = { Text("Image model") }, singleLine = true)
                } else {
                    OutlinedTextField(videoEndpoint, { videoEndpoint = it }, Modifier.fillMaxWidth(), label = { Text("Video endpoint") }, singleLine = true)
                    OutlinedTextField(videoModel, { videoModel = it }, Modifier.fillMaxWidth(), label = { Text("Video model") }, singleLine = true)
                }
            }
        }
        Button(
            onClick = {
                viewModel.saveAndActivateCustom(
                    CustomAiConfig(
                        providerName = provider,
                        baseUrl = baseUrl,
                        apiKey = apiKey,
                        analysisEndpoint = "",
                        analysisModel = "",
                        imageEndpoint = imageEndpoint,
                        imageModel = imageModel,
                        videoEndpoint = videoEndpoint,
                        videoModel = videoModel,
                    )
                )
            },
            modifier = Modifier.fillMaxWidth().height(52.dp),
        ) {
            Text(if (language == "ar") "حفظ وتفعيل" else "Save and activate")
        }
    }
}

@Composable
private fun FreePane(viewModel: SettingsViewModel, language: String, onBack: () -> Unit) {
    val mode by viewModel.aiMode.collectAsState()
    val state by viewModel.providerDiscoveryState.collectAsState()
    val enabled = mode == AiMode.FREE_AUTO
    val connected = state.result.connectedProvider

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(18.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        SubHeader(onBack, if (language == "ar") "ذكاء اصطناعي مجاني" else "Free AI", if (language == "ar") "بدون مفتاح API شخصي" else "No personal API key")
        DimensionCard(emphasized = enabled) {
            Column(Modifier.padding(15.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(if (language == "ar") "التشغيل التلقائي" else "Automatic mode", fontWeight = FontWeight.Black)
                        Text(
                            if (connected != null) "${if (language == "ar") "متصل الآن" else "Connected now"}: ${connected.name}" else if (language == "ar") "لا يوجد مزود متصل حاليًا" else "No connected provider",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Switch(checked = enabled, onCheckedChange = viewModel::setFreeMode)
                }
                Button(onClick = viewModel::discoverFreeProviders, modifier = Modifier.fillMaxWidth(), enabled = !state.isChecking) {
                    if (state.isChecking) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp) else Icon(Icons.Outlined.Refresh, contentDescription = null)
                    Spacer(Modifier.size(7.dp))
                    Text(if (language == "ar") "بحث موسع عن المزودات" else "Scan providers")
                }
                Text(
                    "${if (language == "ar") "تم فحص" else "Scanned"}: ${state.result.scannedCount} • ${if (language == "ar") "مستبعد" else "Excluded"}: ${state.result.excludedCount}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        state.result.providers.forEach { provider -> FreeProviderCard(provider, state.activeProviderId == provider.id, language) {
            viewModel.activateDiscoveredProvider(provider.id)
        } }
    }
}

@Composable
private fun FreeProviderCard(provider: DiscoveredProvider, active: Boolean, language: String, onClick: () -> Unit) {
    DimensionCard(onClick = onClick, emphasized = active) {
        Column(Modifier.padding(13.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(provider.name, modifier = Modifier.weight(1f), fontWeight = FontWeight.Bold)
                ConnectionPill(
                    if (provider.connected) (if (language == "ar") "متصل" else "Connected") else (if (language == "ar") "غير متاح" else "Unavailable"),
                    provider.connected,
                )
            }
            val abilities = buildList {
                if (provider.supportsText) add(if (language == "ar") "نص" else "Text")
                if (provider.supportsImage) add(if (language == "ar") "صور" else "Images")
                if (provider.supportsVideo) add(if (language == "ar") "فيديو" else "Video")
            }
            Text(abilities.joinToString(" • "), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun SubHeader(onBack: () -> Unit, title: String, subtitle: String) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = null) }
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Black)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun ModeButton(
    selected: Boolean,
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    if (selected) {
        Button(onClick = onClick, modifier = modifier.height(46.dp), shape = RoundedCornerShape(16.dp)) { Text(label) }
    } else {
        OutlinedButton(onClick = onClick, modifier = modifier.height(46.dp), shape = RoundedCornerShape(16.dp)) { Text(label) }
    }
}

private fun modelsFor(catalog: OpenRouterCatalog, capability: ModelCapability): List<OpenRouterModelInfo> = when (capability) {
    ModelCapability.TEXT -> catalog.textModels
    ModelCapability.IMAGE -> catalog.imageModels
    ModelCapability.VIDEO -> catalog.videoModels
}

private fun selectedModel(config: OpenRouterConfig, capability: ModelCapability): String = when (capability) {
    ModelCapability.TEXT -> config.analysisModel
    ModelCapability.IMAGE -> config.imageModel
    ModelCapability.VIDEO -> config.videoModel
}

private fun currentEngineName(mode: AiMode, language: String): String = when (mode) {
    AiMode.OPENROUTER -> "OpenRouter"
    AiMode.CUSTOM -> if (language == "ar") "API مخصص" else "Custom API"
    AiMode.FREE_AUTO -> if (language == "ar") "مجاني تلقائي" else "Automatic free"
}
