package com.almi.ai.ui

import android.os.Bundle
import android.os.SystemClock
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.LayoutDirection
import com.almi.ai.data.preferences.AvatarAppearanceStore
import com.almi.ai.data.preferences.BodyProfileStore
import com.almi.ai.data.preferences.JourneyMode
import com.almi.ai.ui.settings.SettingsViewModel
import com.almi.ai.ui.theme.AlmiTheme
import com.almi.ai.ui.tryon.TryOnViewModel
import com.almi.ai.ui.v12.V12AiScreen
import com.almi.ai.ui.v12.V12AvatarQualityScreen
import com.almi.ai.ui.v12.V12BodyMapScreen
import com.almi.ai.ui.v12.V12ControlScreen
import com.almi.ai.ui.v12.V12FitScreen
import com.almi.ai.ui.v12.V12IndexScreen
import com.almi.ai.ui.v12.V12OnboardingScreen
import com.almi.ai.ui.v12.V12World
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

@AndroidEntryPoint
class MainActivity : AppCompatActivity() {
    private val tryOnViewModel: TryOnViewModel by viewModels()
    private val settingsViewModel: SettingsViewModel by viewModels()

    @Inject lateinit var bodyProfileStore: BodyProfileStore
    @Inject lateinit var avatarAppearanceStore: AvatarAppearanceStore

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        setContent {
            val language by settingsViewModel.language.collectAsState()
            val themeMode by settingsViewModel.themeMode.collectAsState()
            val tryOnState by tryOnViewModel.uiState.collectAsState()
            val onboardingComplete by bodyProfileStore.onboardingComplete.collectAsState()
            val journeyMode by bodyProfileStore.journeyMode.collectAsState()
            val bodyProfile by bodyProfileStore.profile.collectAsState()
            val digitalTwinSnapshotUri by bodyProfileStore.digitalTwinSnapshotUri.collectAsState()
            val avatarAppearance by avatarAppearanceStore.appearance.collectAsState()
            var world by rememberSaveable { mutableStateOf(V12World.INDEX) }
            var lastRootBackAt by remember { mutableLongStateOf(0L) }
            val layoutDirection = if (language == "ar") LayoutDirection.Rtl else LayoutDirection.Ltr

            LaunchedEffect(onboardingComplete, journeyMode, digitalTwinSnapshotUri) {
                if (
                    onboardingComplete &&
                    journeyMode == JourneyMode.AVATAR &&
                    tryOnViewModel.uiState.value.personImage == null
                ) {
                    digitalTwinSnapshotUri?.let(tryOnViewModel::setPersonImage)
                }
                if (!onboardingComplete) world = V12World.INDEX
            }

            fun open(target: V12World) {
                if (target == V12World.FIT && world != V12World.FIT) {
                    tryOnViewModel.returnToStudio()
                }
                world = target
            }

            BackHandler(enabled = onboardingComplete && world != V12World.INDEX) {
                world = V12World.INDEX
            }

            BackHandler(
                enabled = onboardingComplete && world == V12World.FIT && tryOnState.generatedImage != null,
            ) {
                tryOnViewModel.returnToStudio()
            }

            BackHandler(enabled = onboardingComplete && world == V12World.INDEX) {
                val now = SystemClock.elapsedRealtime()
                if (now - lastRootBackAt <= EXIT_CONFIRM_WINDOW_MS) {
                    finish()
                } else {
                    lastRootBackAt = now
                    Toast.makeText(
                        this@MainActivity,
                        if (language == "ar") "اضغط رجوع مرة أخرى للخروج" else "Press back again to exit",
                        Toast.LENGTH_SHORT,
                    ).show()
                }
            }

            CompositionLocalProvider(LocalLayoutDirection provides layoutDirection) {
                AlmiTheme(themeMode = themeMode) {
                    if (!onboardingComplete) {
                        V12OnboardingScreen(
                            language = language,
                            appearance = avatarAppearance,
                            bodyProfile = bodyProfile,
                            digitalTwinSnapshotUri = digitalTwinSnapshotUri,
                            onLanguageChange = settingsViewModel::setLanguage,
                            onJourneyMode = bodyProfileStore::setJourneyMode,
                            onAvatarPresentation = avatarAppearanceStore::setPresentation,
                            onAvatarHair = avatarAppearanceStore::setHairVariant,
                            onAvatarHairColor = avatarAppearanceStore::setHairColor,
                            onAvatarSkinColor = avatarAppearanceStore::setSkinColor,
                            onAvatarEyes = avatarAppearanceStore::setEyesVariant,
                            onAvatarEyebrows = avatarAppearanceStore::setEyebrowsVariant,
                            onAvatarMouth = avatarAppearanceStore::setMouthVariant,
                            onComplete = bodyProfileStore::completeOnboarding,
                        )
                    } else {
                        Crossfade(
                            targetState = world,
                            animationSpec = tween(220),
                            label = "almi-v12-world",
                        ) { destination ->
                            when (destination) {
                                V12World.INDEX -> V12IndexScreen(
                                    language = language,
                                    personImage = tryOnState.personImage,
                                    bodyReady = bodyProfile.isFitReady,
                                    avatarReady = journeyMode == JourneyMode.AVATAR,
                                    aiReady = true,
                                    onFit = { open(V12World.FIT) },
                                    onAvatar = { open(V12World.AVATAR) },
                                    onBody = { open(V12World.BODY) },
                                    onAi = { open(V12World.AI) },
                                    onControl = { open(V12World.CONTROL) },
                                )

                                V12World.FIT -> V12FitScreen(
                                    viewModel = tryOnViewModel,
                                    language = language,
                                    onBack = { open(V12World.INDEX) },
                                    onAvatar = { open(V12World.AVATAR) },
                                    onAi = { open(V12World.AI) },
                                )

                                V12World.AVATAR -> V12AvatarQualityScreen(
                                    language = language,
                                    appearance = avatarAppearance,
                                    bodyProfile = bodyProfile,
                                    digitalTwinSnapshotUri = digitalTwinSnapshotUri,
                                    onPresentation = avatarAppearanceStore::setPresentation,
                                    onHair = avatarAppearanceStore::setHairVariant,
                                    onHairColor = avatarAppearanceStore::setHairColor,
                                    onSkinColor = avatarAppearanceStore::setSkinColor,
                                    onEyes = avatarAppearanceStore::setEyesVariant,
                                    onEyebrows = avatarAppearanceStore::setEyebrowsVariant,
                                    onMouth = avatarAppearanceStore::setMouthVariant,
                                    onBack = { open(V12World.INDEX) },
                                    onComplete = { open(V12World.INDEX) },
                                )

                                V12World.BODY -> V12BodyMapScreen(
                                    language = language,
                                    profile = bodyProfile,
                                    presentation = avatarAppearance.presentation,
                                    onPresentation = avatarAppearanceStore::setPresentation,
                                    onHeightChanged = bodyProfileStore::setHeightInches,
                                    onWeightChanged = bodyProfileStore::setWeightPounds,
                                    onMeasurementChanged = bodyProfileStore::setMeasurement,
                                    onMeasurementCleared = bodyProfileStore::clearMeasurement,
                                    onBack = { open(V12World.INDEX) },
                                )

                                V12World.AI -> V12AiScreen(
                                    viewModel = settingsViewModel,
                                    language = language,
                                    onBack = { open(V12World.INDEX) },
                                )

                                V12World.CONTROL -> V12ControlScreen(
                                    viewModel = settingsViewModel,
                                    language = language,
                                    bodyReady = bodyProfile.isFitReady,
                                    avatarReady = journeyMode == JourneyMode.AVATAR,
                                    onBack = { open(V12World.INDEX) },
                                    onBody = { open(V12World.BODY) },
                                    onAvatar = { open(V12World.AVATAR) },
                                    onAi = { open(V12World.AI) },
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    companion object {
        private const val EXIT_CONFIRM_WINDOW_MS = 2_000L
    }
}
