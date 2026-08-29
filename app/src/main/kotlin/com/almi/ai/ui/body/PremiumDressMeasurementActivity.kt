package com.almi.ai.ui.body

import android.animation.ValueAnimator
import android.app.Activity
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.view.SurfaceView
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.almi.ai.data.preferences.BodyMeasurePoint
import com.almi.ai.data.preferences.BodyProfile
import com.almi.ai.data.preferences.BodyProfileStore
import com.almi.ai.data.preferences.BodySideMeasurement
import dagger.hilt.android.AndroidEntryPoint
import java.util.Locale
import javax.inject.Inject
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

private const val PREMIUM_CM_PER_INCH = 2.54f
private const val PREMIUM_KG_PER_POUND = 0.45359237f
private const val PREMIUM_HEADER = 0xFF12345E.toInt()
private const val PREMIUM_PANEL = 0xF21A436F.toInt()
private const val PREMIUM_PANEL_SOFT = 0xF01A416C.toInt()
private const val PREMIUM_BLUE = 0xFF79BCFF.toInt()
private const val PREMIUM_RED = 0xFFFF3D4B.toInt()
private const val PREMIUM_TEXT_SOFT = 0xFFD7E9FA.toInt()

/**
 * Final tailoring surface for the ALMI Filament twin.
 *
 * Measurement landmarks are derived from the live projected rig, then corrected to dressmaking
 * surface locations (bust line, underbust, natural waist, abdomen and full hip) rather than simply
 * placing dots on bone origins. Front-only tailoring points fade away when the user rotates the
 * body toward the side/back, preventing a 2D hotspot from ever pretending to be on the wrong skin
 * surface. The user can still orbit 360°, pinch zoom, double-tap reset and focus a selected region.
 */
@AndroidEntryPoint
class PremiumDressMeasurementActivity : ComponentActivity() {
    @Inject lateinit var bodyProfileStore: BodyProfileStore

    private lateinit var runtime: PersistentFilamentRuntime
    private lateinit var surfaceView: SurfaceView
    private lateinit var hotspotLayer: FrameLayout
    private lateinit var annotationsView: PremiumAnnotationsView
    private lateinit var guideView: PremiumGuideView
    private lateinit var topBar: View
    private lateinit var weightDock: View
    private lateinit var countView: TextView
    private lateinit var progressView: PremiumProgressView
    private lateinit var weightInput: EditText
    private lateinit var editor: LinearLayout
    private lateinit var editorTitle: TextView
    private lateinit var editorInput: EditText
    private lateinit var editorSecondaryInput: EditText
    private lateinit var editorPrimaryLabel: TextView
    private lateinit var editorSecondaryLabel: TextView
    private lateinit var editorSecondaryRow: LinearLayout

    private var profile = BodyProfile()
    private var language = "ar"
    private var introCompleted = false
    private var projection: BodyScreenProjection? = null
    private var selectedTarget: PremiumTailorTarget? = null
    private val hotspotViews = linkedMapOf<PremiumTailorTarget, View>()

    private val layoutScale: Float by lazy {
        val width = resources.configuration.screenWidthDp
        val height = resources.configuration.screenHeightDp
        when {
            width < 350 || height < 650 -> .84f
            width < 380 || height < 720 -> .89f
            width < 420 || height < 800 -> .94f
            else -> 1f
        }
    }

    private val typeScale: Float by lazy {
        when {
            resources.configuration.screenWidthDp < 350 -> .90f
            resources.configuration.screenWidthDp < 390 -> .95f
            else -> 1f
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setResult(Activity.RESULT_CANCELED)
        window.statusBarColor = PREMIUM_HEADER
        window.navigationBarColor = PREMIUM_HEADER
        window.isNavigationBarContrastEnforced = false
        window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_NOTHING)
        @Suppress("DEPRECATION")
        window.decorView.systemUiVisibility = 0

        language = BodyMeasurementContract.language(intent)
        profile = BodyMeasurementContract.readProfile(intent)

        val root = FrameLayout(this).apply { setBackgroundColor(0xFF1B4B7D.toInt()) }
        ViewCompat.setOnApplyWindowInsetsListener(root) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            view.setPadding(0, bars.top, 0, bars.bottom)
            insets
        }

        surfaceView = SurfaceView(this).apply {
            setZOrderOnTop(false)
            keepScreenOn = true
            background = null
        }
        root.addView(surfaceView, FrameLayout.LayoutParams(-1, -1))

        hotspotLayer = FrameLayout(this).apply {
            clipChildren = false
            clipToPadding = false
            isClickable = true
        }
        root.addView(hotspotLayer, FrameLayout.LayoutParams(-1, -1))

        annotationsView = PremiumAnnotationsView()
        hotspotLayer.addView(annotationsView, FrameLayout.LayoutParams(-1, -1))

        guideView = PremiumGuideView().apply { visibility = View.GONE }
        hotspotLayer.addView(guideView, FrameLayout.LayoutParams(-1, -1))

        PremiumTailorTarget.entries.forEach(::addHotspot)

        editor = buildMeasurementEditor().apply { visibility = View.GONE }
        hotspotLayer.addView(
            editor,
            FrameLayout.LayoutParams(dp(142), ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                gravity = Gravity.TOP or Gravity.END
                rightMargin = dp(8)
                topMargin = dp(170)
            },
        )

        val topHeight = dp(118)
        topBar = buildTopBar().apply {
            visibility = View.INVISIBLE
            alpha = 0f
        }
        root.addView(
            topBar,
            FrameLayout.LayoutParams(-1, topHeight).apply { gravity = Gravity.TOP },
        )

        val dockHeight = dp(76)
        weightDock = buildWeightDock().apply {
            visibility = View.INVISIBLE
            alpha = 0f
        }
        root.addView(
            weightDock,
            FrameLayout.LayoutParams(-1, dockHeight).apply {
                gravity = Gravity.BOTTOM
                leftMargin = dp(10)
                rightMargin = dp(10)
                bottomMargin = dp(5)
            },
        )

        setContentView(root)
        WindowInsetsControllerCompat(window, root).apply {
            isAppearanceLightStatusBars = false
            isAppearanceLightNavigationBars = false
        }
        ViewCompat.requestApplyInsets(root)

        runtime = PersistentFilamentRuntime(
            context = this,
            surfaceView = surfaceView,
            onStateChanged = { state -> runOnUiThread { renderState(state) } },
            onProjectionChanged = { value -> updateProjection(value) },
        )
        hotspotLayer.setOnTouchListener { _, event -> runtime.onViewportTouch(event) }
        runtime.initialize()
        applyShape()
        refreshUi()
    }

    override fun onResume() {
        super.onResume()
        if (::runtime.isInitialized) runtime.start()
    }

    override fun onPause() {
        if (::runtime.isInitialized) runtime.stop()
        super.onPause()
    }

    private fun buildTopBar(): View = FrameLayout(this).apply {
        setPadding(dp(12), dp(6), dp(12), dp(5))
        background = solidBg(PREMIUM_HEADER)

        val titleBlock = LinearLayout(this@PremiumDressMeasurementActivity).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            addView(text("ALMI / FILAMENT", 12f, 0xFFA7D2FF.toInt(), true).apply { gravity = Gravity.CENTER })
            addView(
                text(if (language == "ar") "قياسات جسمك" else "Your measurements", 24f, Color.WHITE, true).apply {
                    gravity = Gravity.CENTER
                    setPadding(0, dp(1), 0, 0)
                },
            )
        }
        addView(
            titleBlock,
            FrameLayout.LayoutParams(-2, -2).apply {
                gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
                topMargin = dp(5)
            },
        )

        val done = text(if (language == "ar") "✓  تم" else "✓  Done", 14f, Color.WHITE, true).apply {
            gravity = Gravity.CENTER
            background = roundedBg(0xD8244E7C.toInt(), 99f, 0x6698C0E8)
            setOnClickListener {
                persistSideMeasurements()
                setResult(Activity.RESULT_OK, BodyMeasurementContract.resultIntent(profile))
                finish()
            }
        }
        addView(
            done,
            FrameLayout.LayoutParams(dp(69), dp(37)).apply {
                gravity = Gravity.TOP or Gravity.END
                topMargin = dp(4)
            },
        )

        val progressRow = LinearLayout(this@PremiumDressMeasurementActivity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        countView = text("0/${PremiumTailorTarget.entries.size}", 12f, Color.WHITE, true).apply {
            gravity = Gravity.CENTER
            background = roundedBg(0xE82A557F.toInt(), 12f)
        }
        progressRow.addView(countView, LinearLayout.LayoutParams(dp(54), dp(29)))
        progressView = PremiumProgressView()
        progressRow.addView(
            progressView,
            LinearLayout.LayoutParams(dp(116), dp(13)).apply { marginStart = dp(9) },
        )
        addView(
            progressRow,
            FrameLayout.LayoutParams(-2, dp(31)).apply {
                gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
                bottomMargin = dp(1)
            },
        )
    }

    private fun buildMeasurementEditor(): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(8), dp(7), dp(8), dp(7))
        background = roundedBg(PREMIUM_PANEL, 14f, 0x728FB7DB)
        elevation = dp(8).toFloat()

        editorTitle = text("", 11.5f, Color.WHITE, true).apply { gravity = Gravity.CENTER }
        addView(editorTitle, LinearLayout.LayoutParams(-1, dp(19)))

        val primaryRow = measurementRow().also { row ->
            editorPrimaryLabel = text("", 8f, PREMIUM_TEXT_SOFT, true).apply { gravity = Gravity.CENTER }
            row.addView(editorPrimaryLabel, LinearLayout.LayoutParams(dp(29), dp(36)))
            editorInput = measurementEditText()
            row.addView(editorInput, LinearLayout.LayoutParams(0, dp(36), 1f))
            row.addView(text("cm", 8.5f, 0xFFEAF5FF.toInt(), false).apply { gravity = Gravity.CENTER }, LinearLayout.LayoutParams(dp(25), dp(36)))
        }
        addView(primaryRow, LinearLayout.LayoutParams(-1, dp(36)).apply { topMargin = dp(5) })

        editorSecondaryRow = measurementRow().apply { visibility = View.GONE }
        editorSecondaryLabel = text("", 8f, PREMIUM_TEXT_SOFT, true).apply { gravity = Gravity.CENTER }
        editorSecondaryRow.addView(editorSecondaryLabel, LinearLayout.LayoutParams(dp(29), dp(36)))
        editorSecondaryInput = measurementEditText()
        editorSecondaryRow.addView(editorSecondaryInput, LinearLayout.LayoutParams(0, dp(36), 1f))
        editorSecondaryRow.addView(text("cm", 8.5f, 0xFFEAF5FF.toInt(), false).apply { gravity = Gravity.CENTER }, LinearLayout.LayoutParams(dp(25), dp(36)))
        addView(editorSecondaryRow, LinearLayout.LayoutParams(-1, dp(36)).apply { topMargin = dp(4) })

        val actions = LinearLayout(this@PremiumDressMeasurementActivity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
        }
        val cancel = text("×", 18f, 0xFFEDF6FF.toInt(), false).apply {
            gravity = Gravity.CENTER
            background = roundedBg(0xCC1B416B.toInt(), 10f, 0x5F84A9CE)
            setOnClickListener { closeEditor() }
        }
        actions.addView(cancel, LinearLayout.LayoutParams(0, dp(32), 1f))
        val confirm = text("✓", 18f, Color.WHITE, true).apply {
            gravity = Gravity.CENTER
            background = roundedBg(PREMIUM_BLUE, 10f)
            setOnClickListener { saveSelectedMeasurement() }
        }
        actions.addView(confirm, LinearLayout.LayoutParams(0, dp(32), 1f).apply { marginStart = dp(5) })
        addView(actions, LinearLayout.LayoutParams(-1, dp(32)).apply { topMargin = dp(5) })
    }

    private fun measurementRow(): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        background = roundedBg(0xE01A416B.toInt(), 10f, 0x5F84A9CE)
    }

    private fun measurementEditText(): EditText = EditText(this).apply {
        textSize = scaledText(15.5f)
        setTextColor(Color.WHITE)
        setHintTextColor(0xFFA5BCD5.toInt())
        hint = "0"
        inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
        imeOptions = EditorInfo.IME_ACTION_DONE
        gravity = Gravity.CENTER
        setSingleLine(true)
        setPadding(dp(2), 0, dp(2), 0)
        background = null
        setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_DONE) {
                saveSelectedMeasurement()
                true
            } else false
        }
    }

    private fun buildWeightDock(): View = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(dp(10), dp(6), dp(10), dp(6))
        background = roundedBg(PREMIUM_PANEL_SOFT, 18f, 0x6A8DB2D5)
        elevation = dp(6).toFloat()

        val label = LinearLayout(this@PremiumDressMeasurementActivity).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_VERTICAL
            addView(text(if (language == "ar") "الوزن" else "Weight", 15f, Color.WHITE, true))
            addView(text(if (language == "ar") "يتفاعل المجسم مباشرة" else "Twin reacts immediately", 8.3f, PREMIUM_TEXT_SOFT, false))
        }
        addView(label, LinearLayout.LayoutParams(0, -2, 1f))

        val shell = LinearLayout(this@PremiumDressMeasurementActivity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = roundedBg(0xE0183B64.toInt(), 11f, 0x7089AFD2)
        }
        weightInput = EditText(this@PremiumDressMeasurementActivity).apply {
            textSize = scaledText(18.5f)
            setTextColor(Color.WHITE)
            hint = "80"
            setHintTextColor(0xFFABC2DC.toInt())
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
            imeOptions = EditorInfo.IME_ACTION_DONE
            gravity = Gravity.CENTER
            setSingleLine(true)
            setPadding(dp(3), 0, dp(1), 0)
            background = null
            setOnEditorActionListener { _, actionId, _ ->
                if (actionId == EditorInfo.IME_ACTION_DONE) {
                    commitWeight()
                    true
                } else false
            }
        }
        shell.addView(weightInput, LinearLayout.LayoutParams(dp(51), dp(40)))
        shell.addView(text("kg", 9f, 0xFFE8F3FF.toInt(), true).apply { gravity = Gravity.CENTER }, LinearLayout.LayoutParams(dp(27), dp(40)))
        addView(shell, LinearLayout.LayoutParams(dp(78), dp(40)).apply { marginStart = dp(6) })

        val cancel = text("×", 17f, 0xFFEDF6FF.toInt(), false).apply {
            gravity = Gravity.CENTER
            background = roundedBg(0xCC1B416B.toInt(), 11f, 0x5F84A9CE)
            setOnClickListener { cancelWeightEdit() }
        }
        addView(cancel, LinearLayout.LayoutParams(dp(35), dp(40)).apply { marginStart = dp(5) })
        val confirm = text("✓", 17f, Color.WHITE, true).apply {
            gravity = Gravity.CENTER
            background = roundedBg(PREMIUM_BLUE, 11f)
            setOnClickListener { commitWeight() }
        }
        addView(confirm, LinearLayout.LayoutParams(dp(38), dp(40)).apply { marginStart = dp(5) })
    }

    private fun addHotspot(target: PremiumTailorTarget) {
        val hit = View(this).apply {
            visibility = View.INVISIBLE
            background = null
            isClickable = true
            setOnClickListener { openEditor(target) }
        }
        hotspotLayer.addView(hit, FrameLayout.LayoutParams(dp(34), dp(34)))
        hotspotViews[target] = hit
    }

    private fun updateProjection(value: BodyScreenProjection) {
        projection = value
        if (!::hotspotLayer.isInitialized) return
        positionAllHotspots()
        annotationsView.invalidate()
        guideView.invalidate()
        selectedTarget?.let(::positionEditor)
    }

    private fun positionAllHotspots() {
        if (hotspotLayer.width <= 0 || hotspotLayer.height <= 0) return
        hotspotViews.forEach { (target, view) -> positionHotspot(target, view) }
    }

    private fun positionHotspot(target: PremiumTailorTarget, holder: View) {
        val anchor = anchorFor(target)
        val size = dp(34)
        holder.x = hotspotLayer.width * anchor.x - size / 2f
        holder.y = hotspotLayer.height * anchor.y - size / 2f
        holder.alpha = if (anchor.visible) 1f else 0f
        holder.isEnabled = anchor.visible
    }

    private fun openEditor(target: PremiumTailorTarget) {
        if (!introCompleted || !anchorFor(target).visible) return
        selectedTarget = target
        editorTitle.text = target.title(language)
        val sideKeys = target.sideKeys()
        if (sideKeys != null) {
            val (rightKey, leftKey) = sideKeys
            editorPrimaryLabel.visibility = View.VISIBLE
            editorSecondaryRow.visibility = View.VISIBLE
            editorPrimaryLabel.text = if (language == "ar") "يمين" else "R"
            editorSecondaryLabel.text = if (language == "ar") "يسار" else "L"
            val fallback = target.valueCm(profile)
            editorInput.setText(profile.sideMeasurementsInches[rightKey]?.times(PREMIUM_CM_PER_INCH)?.let(::formatNumber) ?: fallback?.let(::formatNumber).orEmpty())
            editorSecondaryInput.setText(profile.sideMeasurementsInches[leftKey]?.times(PREMIUM_CM_PER_INCH)?.let(::formatNumber) ?: fallback?.let(::formatNumber).orEmpty())
        } else {
            editorPrimaryLabel.visibility = View.GONE
            editorSecondaryRow.visibility = View.GONE
            editorInput.setText(target.valueCm(profile)?.let(::formatNumber).orEmpty())
        }
        editor.visibility = View.VISIBLE
        guideView.target = target
        guideView.visibility = View.VISIBLE
        annotationsView.selectedTarget = target
        runtime.focusOn(target.focusY, target.focusDistance)
        positionEditor(target)
    }

    private fun positionEditor(target: PremiumTailorTarget) {
        hotspotLayer.post {
            val anchor = anchorFor(target)
            val estimatedHeight = if (target.sideKeys() != null) dp(146) else dp(104)
            val minTop = dp(124)
            val maxTop = (hotspotLayer.height - dp(86) - estimatedHeight).coerceAtLeast(minTop)
            val desired = (hotspotLayer.height * anchor.y - estimatedHeight * .40f).toInt()
            val lp = editor.layoutParams as FrameLayout.LayoutParams
            lp.gravity = Gravity.TOP or if (anchor.x >= .52f) Gravity.START else Gravity.END
            lp.leftMargin = dp(8)
            lp.rightMargin = dp(8)
            lp.topMargin = desired.coerceIn(minTop, maxTop)
            editor.layoutParams = lp
        }
    }

    private fun closeEditor() {
        selectedTarget = null
        editor.visibility = View.GONE
        guideView.visibility = View.GONE
        annotationsView.selectedTarget = null
        editorInput.clearFocus()
        editorSecondaryInput.clearFocus()
        hideKeyboard()
        runtime.resetFocus()
    }

    private fun saveSelectedMeasurement() {
        val target = selectedTarget ?: return
        val sides = target.sideKeys()
        if (sides != null) {
            val rightCm = editorInput.text.toString().toFloatOrNull()?.takeIf { it in 1f..300f } ?: return
            val leftCm = editorSecondaryInput.text.toString().toFloatOrNull()?.takeIf { it in 1f..300f } ?: return
            val point = target.point ?: return
            profile = profile.copy(
                measurementsInches = profile.measurementsInches + (point to ((rightCm + leftCm) / 2f / PREMIUM_CM_PER_INCH)),
                sideMeasurementsInches = profile.sideMeasurementsInches + mapOf(
                    sides.first to rightCm / PREMIUM_CM_PER_INCH,
                    sides.second to leftCm / PREMIUM_CM_PER_INCH,
                ),
            )
        } else {
            val cm = editorInput.text.toString().toFloatOrNull()?.takeIf { it in 1f..300f } ?: return
            profile = if (target == PremiumTailorTarget.HEIGHT) {
                profile.copy(heightInches = cm / PREMIUM_CM_PER_INCH, hasExplicitHeight = true)
            } else {
                val point = target.point ?: return
                profile.copy(measurementsInches = profile.measurementsInches + (point to cm / PREMIUM_CM_PER_INCH))
            }
        }
        applyShape()
        refreshUi()
        closeEditor()
    }

    private fun commitWeight() {
        val kg = weightInput.text.toString().toFloatOrNull()?.takeIf { it in 20f..320f } ?: return
        profile = profile.copy(weightPounds = kg / PREMIUM_KG_PER_POUND, hasExplicitWeight = true)
        weightInput.clearFocus()
        hideKeyboard()
        applyShape()
        refreshUi()
        runtime.resetFocus()
    }

    private fun cancelWeightEdit() {
        if (profile.hasExplicitWeight) weightInput.setText(formatNumber(profile.weightPounds * PREMIUM_KG_PER_POUND)) else weightInput.text?.clear()
        weightInput.clearFocus()
        hideKeyboard()
    }

    private fun persistSideMeasurements() {
        val before = bodyProfileStore.profile.value.sideMeasurementsInches
        BodySideMeasurement.entries.forEach { point ->
            val value = profile.sideMeasurementsInches[point]
            when {
                value != null && value != before[point] -> bodyProfileStore.setSideMeasurement(point, value)
                value == null && before[point] != null -> bodyProfileStore.clearSideMeasurement(point)
            }
        }
    }

    private fun applyShape() {
        if (!::runtime.isInitialized) return
        val shape = BodyShapeSolver.solve(profile)
        runtime.updateBodyShape(shape.widthScale, shape.heightScale, shape.depthScale)
        runtime.updateProfile(profile)
    }

    private fun refreshUi() {
        val total = PremiumTailorTarget.entries.size
        val completed = PremiumTailorTarget.entries.count { it.valueCm(profile) != null }
        countView.text = "$completed/$total"
        progressView.progress = completed.toFloat() / total.toFloat()
        if (profile.hasExplicitWeight && !weightInput.hasFocus()) weightInput.setText(formatNumber(profile.weightPounds * PREMIUM_KG_PER_POUND))
        annotationsView.profile = profile
        annotationsView.invalidate()
    }

    private fun renderState(state: BodyRendererState) {
        when (state) {
            BodyRendererState.LOADING -> Unit
            BodyRendererState.READY -> if (!introCompleted) runtime.playIntroSpin(2_150L) { revealInteractiveUi() }
            BodyRendererState.ERROR -> Toast.makeText(this, if (language == "ar") "تعذر عرض المجسم ثلاثي الأبعاد" else "Unable to render the 3D body", Toast.LENGTH_LONG).show()
        }
    }

    private fun revealInteractiveUi() {
        if (introCompleted || isFinishing) return
        introCompleted = true
        topBar.visibility = View.VISIBLE
        weightDock.visibility = View.VISIBLE
        topBar.animate().alpha(1f).setDuration(220L).start()
        weightDock.animate().alpha(1f).setDuration(220L).start()
        annotationsView.bodyReady = true
        annotationsView.revealedCount = 0
        hotspotViews.values.forEach { it.visibility = View.INVISIBLE }
        PremiumTailorTarget.entries.forEachIndexed { index, target ->
            hotspotLayer.postDelayed({
                if (!isFinishing) {
                    annotationsView.revealedCount = index + 1
                    hotspotViews[target]?.visibility = View.VISIBLE
                    positionAllHotspots()
                }
            }, 150L + index * 72L)
        }
    }

    private fun p(name: String): BodyScreenPoint? = projection?.get(name)?.takeIf { it.visible }

    private fun mix(a: BodyScreenPoint?, b: BodyScreenPoint?, t: Float): BodyScreenPoint? {
        if (a == null) return b
        if (b == null) return a
        return BodyScreenPoint(a.x + (b.x - a.x) * t, a.y + (b.y - a.y) * t, a.visible || b.visible)
    }

    private fun extrapolate(a: BodyScreenPoint?, b: BodyScreenPoint?, t: Float): BodyScreenPoint? {
        if (a == null || b == null) return b ?: a
        return BodyScreenPoint(a.x + (b.x - a.x) * t, a.y + (b.y - a.y) * t, a.visible || b.visible)
    }

    private fun frontFacing(): Boolean {
        val yaw = projection?.yawRadians ?: 0.0
        val normalized = ((yaw + PI) % (PI * 2.0)) - PI
        return cos(normalized) > 0.16
    }

    /**
     * Places each dot on the actual dressmaking line, not on the nearest bone origin.
     * Shoulder span provides a live body-width ruler so the locations follow zoom, orbit, height,
     * weight and circumference deformation without returning to hard-coded phone coordinates.
     */
    private fun anchorFor(target: PremiumTailorTarget): BodyScreenPoint {
        val leftShoulder = mix(p("LeftShoulder"), p("LeftUpperArm"), .28f) ?: BodyScreenPoint(.36f, .25f)
        val rightShoulder = mix(p("RightShoulder"), p("RightUpperArm"), .28f) ?: BodyScreenPoint(.64f, .25f)
        val neck = p("Neck") ?: BodyScreenPoint(.50f, .20f)
        val head = p("Head") ?: BodyScreenPoint(.50f, .13f)
        val spine = p("Spine") ?: BodyScreenPoint(.50f, .46f)
        val spine1 = p("Spine1") ?: BodyScreenPoint(.50f, .39f)
        val spine2 = p("Spine2") ?: BodyScreenPoint(.50f, .31f)
        val hips = p("Hips") ?: BodyScreenPoint(.50f, .53f)
        val rightUpperArm = p("RightUpperArm") ?: BodyScreenPoint(.69f, .29f)
        val rightForeArm = p("RightForeArm") ?: BodyScreenPoint(.75f, .40f)
        val rightHand = p("RightHand") ?: BodyScreenPoint(.79f, .48f)

        val centerX = (leftShoulder.x + rightShoulder.x) * .5f
        val halfShoulder = abs(rightShoulder.x - leftShoulder.x).coerceAtLeast(.10f) * .5f
        fun centered(level: BodyScreenPoint?) = BodyScreenPoint(centerX, level?.y ?: .50f)
        fun lateral(level: BodyScreenPoint?, factor: Float) = BodyScreenPoint(centerX + halfShoulder * factor, level?.y ?: .50f)
        fun lateralLeft(level: BodyScreenPoint?, factor: Float) = BodyScreenPoint(centerX - halfShoulder * factor, level?.y ?: .50f)

        val crown = extrapolate(neck, head, 1.47f) ?: BodyScreenPoint(centerX, .07f)
        val chest = centered(mix(spine2, spine1, .28f))
        val underBust = centered(mix(spine2, spine1, .54f))
        val waist = centered(mix(spine1, spine, .48f))
        val abdomen = centered(mix(spine, hips, .30f))
        val bustRight = BodyScreenPoint(centerX + halfShoulder * .34f, chest.y)
        val bustLeft = BodyScreenPoint(centerX - halfShoulder * .34f, chest.y)
        val highLeftShoulder = mix(neck, leftShoulder, .78f) ?: leftShoulder
        val upperArm = mix(rightUpperArm, rightForeArm, .46f) ?: rightUpperArm
        val wrist = mix(rightForeArm, rightHand, .80f) ?: rightHand

        val raw = when (target) {
            PremiumTailorTarget.HEIGHT -> crown
            PremiumTailorTarget.NECK -> BodyScreenPoint(centerX, neck.y)
            PremiumTailorTarget.SHOULDERS -> rightShoulder
            PremiumTailorTarget.SHOULDER_LENGTH -> leftShoulder
            PremiumTailorTarget.CHEST -> lateral(chest, .58f)
            PremiumTailorTarget.UNDERBUST -> lateralLeft(underBust, .50f)
            PremiumTailorTarget.BUST_HEIGHT -> bustRight
            PremiumTailorTarget.BUST_POINT_DISTANCE -> bustLeft
            PremiumTailorTarget.WAIST -> lateral(waist, .42f)
            PremiumTailorTarget.ABDOMEN -> lateralLeft(abdomen, .45f)
            PremiumTailorTarget.HIPS -> lateral(hips, .56f)
            PremiumTailorTarget.DRESS_LENGTH -> highLeftShoulder
            PremiumTailorTarget.ARM_LENGTH -> rightForeArm
            PremiumTailorTarget.UPPER_ARM -> upperArm
            PremiumTailorTarget.WRIST -> wrist
        }
        return raw.copy(
            x = raw.x.coerceIn(.045f, .955f),
            y = raw.y.coerceIn(.055f, .930f),
            visible = raw.visible && frontFacing(),
        )
    }

    private fun guideFor(target: PremiumTailorTarget): PremiumGuideGeometry {
        val leftShoulder = mix(p("LeftShoulder"), p("LeftUpperArm"), .28f) ?: BodyScreenPoint(.36f, .25f)
        val rightShoulder = mix(p("RightShoulder"), p("RightUpperArm"), .28f) ?: BodyScreenPoint(.64f, .25f)
        val neck = p("Neck") ?: BodyScreenPoint(.50f, .20f)
        val head = p("Head") ?: BodyScreenPoint(.50f, .13f)
        val spine = p("Spine") ?: BodyScreenPoint(.50f, .46f)
        val spine1 = p("Spine1") ?: BodyScreenPoint(.50f, .39f)
        val spine2 = p("Spine2") ?: BodyScreenPoint(.50f, .31f)
        val hips = p("Hips") ?: BodyScreenPoint(.50f, .53f)
        val rightUpperArm = p("RightUpperArm") ?: BodyScreenPoint(.69f, .29f)
        val rightForeArm = p("RightForeArm") ?: BodyScreenPoint(.75f, .40f)
        val rightHand = p("RightHand") ?: BodyScreenPoint(.79f, .48f)
        val leftFoot = p("LeftFoot") ?: BodyScreenPoint(.44f, .90f)
        val rightFoot = p("RightFoot") ?: BodyScreenPoint(.56f, .90f)

        val centerX = (leftShoulder.x + rightShoulder.x) * .5f
        val span = abs(rightShoulder.x - leftShoulder.x).coerceAtLeast(.12f)
        val torsoHeight = abs(hips.y - neck.y).coerceAtLeast(.28f)
        fun center(level: BodyScreenPoint?) = BodyScreenPoint(centerX, level?.y ?: .50f)
        fun oval(c: BodyScreenPoint, width: Float, height: Float) = PremiumGuideGeometry(
            PremiumGuideShape.OVAL,
            BodyScreenPoint(c.x - width / 2f, c.y - height / 2f),
            BodyScreenPoint(c.x + width / 2f, c.y + height / 2f),
        )

        val crown = extrapolate(neck, head, 1.47f) ?: BodyScreenPoint(centerX, .07f)
        val feet = mix(leftFoot, rightFoot, .5f) ?: BodyScreenPoint(centerX, .90f)
        val chest = center(mix(spine2, spine1, .28f))
        val underBust = center(mix(spine2, spine1, .54f))
        val waist = center(mix(spine1, spine, .48f))
        val abdomen = center(mix(spine, hips, .30f))
        val bustRight = BodyScreenPoint(centerX + span * .17f, chest.y)
        val bustLeft = BodyScreenPoint(centerX - span * .17f, chest.y)
        val highLeftShoulder = mix(neck, leftShoulder, .78f) ?: leftShoulder
        val upperArm = mix(rightUpperArm, rightForeArm, .46f) ?: rightUpperArm
        val wrist = mix(rightForeArm, rightHand, .80f) ?: rightHand

        return when (target) {
            PremiumTailorTarget.HEIGHT -> PremiumGuideGeometry(PremiumGuideShape.LINE, BodyScreenPoint(centerX - span * .72f, crown.y), BodyScreenPoint(centerX - span * .72f, feet.y + .018f))
            PremiumTailorTarget.NECK -> oval(BodyScreenPoint(centerX, neck.y), span * .24f, torsoHeight * .040f)
            PremiumTailorTarget.SHOULDERS -> PremiumGuideGeometry(PremiumGuideShape.LINE, leftShoulder, rightShoulder)
            PremiumTailorTarget.SHOULDER_LENGTH -> PremiumGuideGeometry(PremiumGuideShape.LINE, mix(neck, leftShoulder, .18f) ?: neck, leftShoulder)
            PremiumTailorTarget.CHEST -> oval(chest, span * .78f, torsoHeight * .058f)
            PremiumTailorTarget.UNDERBUST -> oval(underBust, span * .70f, torsoHeight * .050f)
            PremiumTailorTarget.BUST_HEIGHT -> PremiumGuideGeometry(PremiumGuideShape.LINE, mix(neck, rightShoulder, .42f) ?: neck, bustRight)
            PremiumTailorTarget.BUST_POINT_DISTANCE -> PremiumGuideGeometry(PremiumGuideShape.LINE, bustLeft, bustRight)
            PremiumTailorTarget.WAIST -> oval(waist, span * .58f, torsoHeight * .046f)
            PremiumTailorTarget.ABDOMEN -> oval(abdomen, span * .64f, torsoHeight * .048f)
            PremiumTailorTarget.HIPS -> oval(hips.copy(x = centerX), span * .78f, torsoHeight * .060f)
            PremiumTailorTarget.DRESS_LENGTH -> PremiumGuideGeometry(PremiumGuideShape.LINE, highLeftShoulder, BodyScreenPoint(highLeftShoulder.x, feet.y + .010f))
            PremiumTailorTarget.ARM_LENGTH -> PremiumGuideGeometry(PremiumGuideShape.LINE, rightShoulder, wrist)
            PremiumTailorTarget.UPPER_ARM -> oval(upperArm, span * .16f, torsoHeight * .057f)
            PremiumTailorTarget.WRIST -> oval(wrist, span * .09f, torsoHeight * .037f)
        }
    }

    private inner class PremiumProgressView : View(this@PremiumDressMeasurementActivity) {
        var progress = 0f
            set(value) { field = value.coerceIn(0f, 1f); invalidate() }
        private val track = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF2C5A86.toInt() }
        private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = PREMIUM_BLUE }
        override fun onDraw(canvas: Canvas) {
            val r = height / 2f
            canvas.drawRoundRect(0f, height * .29f, width.toFloat(), height * .71f, r, r, track)
            val end = width * progress
            if (end > 0f) canvas.drawRoundRect(0f, height * .29f, end, height * .71f, r, r, fill)
        }
    }

    private inner class PremiumAnnotationsView : View(this@PremiumDressMeasurementActivity) {
        var profile: BodyProfile = this@PremiumDressMeasurementActivity.profile
        var bodyReady = false
        var revealedCount = 0
            set(value) { field = value.coerceIn(0, PremiumTailorTarget.entries.size); invalidate() }
        var selectedTarget: PremiumTailorTarget? = null
            set(value) { field = value; invalidate() }

        private var pulse = 0f
        private val glow = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
        private val dot = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
        private val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0xFFFFD9DD.toInt()
            style = Paint.Style.STROKE
            strokeWidth = dp(1).toFloat()
        }
        private val valuePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            textSize = spPx(11.5f)
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            setShadowLayer(dp(2).toFloat(), 0f, dp(1).toFloat(), 0x99071224.toInt())
        }
        private val valueBg = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xB5123254.toInt() }
        private val animator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 1_050L
            repeatCount = ValueAnimator.INFINITE
            repeatMode = ValueAnimator.REVERSE
            addUpdateListener { pulse = it.animatedValue as Float; invalidate() }
            start()
        }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            if (!bodyReady) return
            PremiumTailorTarget.entries.take(revealedCount).forEach { target ->
                val a = anchorFor(target)
                if (!a.visible) return@forEach
                val cx = width * a.x
                val cy = height * a.y
                val selected = selectedTarget == target
                glow.color = if (selected) 0x88FF3D4B.toInt() else 0x36FF3D4B.toInt()
                canvas.drawCircle(cx, cy, dp(if (selected) 8.5f else 4.6f) + pulse * dp(if (selected) 2f else .8f), glow)
                dot.color = PREMIUM_RED
                canvas.drawCircle(cx, cy, dp(if (selected) 4.2f else 3.2f), dot)
                canvas.drawCircle(cx, cy, dp(if (selected) 5.3f else 4.2f), ring)

                val measured = target.valueCm(profile) ?: return@forEach
                drawValueChip(canvas, target, cx, cy, measured)
            }
        }

        private fun drawValueChip(canvas: Canvas, target: PremiumTailorTarget, cx: Float, cy: Float, measured: Float) {
            val label = "${formatNumber(measured)} cm"
            val textWidth = valuePaint.measureText(label)
            val h = dp(21).toFloat()
            val w = textWidth + dp(14)
            val toRight = target.labelSide == PremiumLabelSide.RIGHT
            val left = if (toRight) cx + dp(9) else cx - dp(9) - w
            val top = cy - h / 2f
            val rect = RectF(left, top, left + w, top + h)
            canvas.drawRoundRect(rect, dp(8).toFloat(), dp(8).toFloat(), valueBg)
            valuePaint.textAlign = Paint.Align.LEFT
            val baseline = rect.centerY() - (valuePaint.ascent() + valuePaint.descent()) / 2f
            canvas.drawText(label, rect.left + dp(7), baseline, valuePaint)
        }

        override fun onDetachedFromWindow() {
            animator.cancel()
            super.onDetachedFromWindow()
        }
    }

    private inner class PremiumGuideView : View(this@PremiumDressMeasurementActivity) {
        var target: PremiumTailorTarget? = null
            set(value) { field = value; invalidate() }
        private var phase = 0f
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = PREMIUM_BLUE
            strokeWidth = dp(1.8f).toFloat()
            style = Paint.Style.STROKE
            strokeCap = Paint.Cap.ROUND
        }
        private val traveller = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFF6FBFF.toInt(); style = Paint.Style.FILL }
        private val animator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 900L
            repeatCount = ValueAnimator.INFINITE
            repeatMode = ValueAnimator.REVERSE
            addUpdateListener { phase = it.animatedValue as Float; invalidate() }
            start()
        }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            val item = target ?: return
            if (!anchorFor(item).visible) return
            val geometry = guideFor(item)
            val sx = width * geometry.start.x
            val sy = height * geometry.start.y
            val ex = width * geometry.end.x
            val ey = height * geometry.end.y
            if (geometry.shape == PremiumGuideShape.OVAL) {
                val rect = RectF(minOf(sx, ex), minOf(sy, ey), maxOf(sx, ex), maxOf(sy, ey))
                canvas.drawOval(rect, paint)
                val angle = phase * PI * 2.0
                canvas.drawCircle(rect.centerX() + cos(angle).toFloat() * rect.width() / 2f, rect.centerY() + sin(angle).toFloat() * rect.height() / 2f, dp(2.5f).toFloat(), traveller)
            } else {
                canvas.drawLine(sx, sy, ex, ey, paint)
                canvas.drawCircle(sx + (ex - sx) * phase, sy + (ey - sy) * phase, dp(2.5f).toFloat(), traveller)
                drawArrow(canvas, sx, sy, ex, ey)
                drawArrow(canvas, ex, ey, sx, sy)
            }
        }

        private fun drawArrow(canvas: Canvas, tx: Float, ty: Float, fx: Float, fy: Float) {
            val angle = atan2((ty - fy).toDouble(), (tx - fx).toDouble())
            val len = dp(7).toFloat()
            val path = Path().apply {
                moveTo(tx, ty)
                lineTo((tx - len * cos(angle - .55)).toFloat(), (ty - len * sin(angle - .55)).toFloat())
                moveTo(tx, ty)
                lineTo((tx - len * cos(angle + .55)).toFloat(), (ty - len * sin(angle + .55)).toFloat())
            }
            canvas.drawPath(path, paint)
        }

        override fun onDetachedFromWindow() {
            animator.cancel()
            super.onDetachedFromWindow()
        }
    }

    private fun hideKeyboard() {
        val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager ?: return
        imm.hideSoftInputFromWindow(window.decorView.windowToken, 0)
    }

    private fun text(value: String, size: Float, color: Int, bold: Boolean): TextView = TextView(this).apply {
        text = value
        textSize = scaledText(size)
        setTextColor(color)
        includeFontPadding = false
        if (bold) setTypeface(typeface, android.graphics.Typeface.BOLD)
        if (language == "ar") textDirection = View.TEXT_DIRECTION_RTL
    }

    private fun solidBg(color: Int) = GradientDrawable().apply { setColor(color) }
    private fun roundedBg(color: Int, radius: Float, stroke: Int? = null) = GradientDrawable().apply {
        setColor(color)
        cornerRadius = dp(radius).toFloat()
        if (stroke != null) setStroke(dp(1), stroke)
    }
    private fun dp(value: Int): Int = (value * resources.displayMetrics.density * layoutScale).roundToInt().coerceAtLeast(1)
    private fun dp(value: Float): Float = value * resources.displayMetrics.density * layoutScale
    private fun spPx(value: Float): Float = value * resources.displayMetrics.scaledDensity * typeScale
    private fun scaledText(value: Float): Float = value * typeScale
    private fun formatNumber(value: Float): String = if (value % 1f == 0f) value.toInt().toString() else String.format(Locale.US, "%.1f", value)
}

private enum class PremiumGuideShape { LINE, OVAL }
private data class PremiumGuideGeometry(val shape: PremiumGuideShape, val start: BodyScreenPoint, val end: BodyScreenPoint)
private enum class PremiumLabelSide { LEFT, RIGHT }

private enum class PremiumTailorTarget(
    val point: BodyMeasurePoint?,
    val focusY: Float,
    val focusDistance: Float,
    val labelSide: PremiumLabelSide,
) {
    HEIGHT(null, .00f, 2.82f, PremiumLabelSide.RIGHT),
    NECK(BodyMeasurePoint.NECK, .60f, 2.16f, PremiumLabelSide.LEFT),
    SHOULDERS(BodyMeasurePoint.SHOULDERS, .49f, 2.24f, PremiumLabelSide.RIGHT),
    SHOULDER_LENGTH(BodyMeasurePoint.SHOULDER_LENGTH, .50f, 2.12f, PremiumLabelSide.LEFT),
    CHEST(BodyMeasurePoint.CHEST, .33f, 2.08f, PremiumLabelSide.RIGHT),
    UNDERBUST(BodyMeasurePoint.UNDERBUST, .25f, 2.06f, PremiumLabelSide.LEFT),
    BUST_HEIGHT(BodyMeasurePoint.BUST_HEIGHT, .34f, 2.02f, PremiumLabelSide.RIGHT),
    BUST_POINT_DISTANCE(BodyMeasurePoint.BUST_POINT_DISTANCE, .34f, 2.02f, PremiumLabelSide.LEFT),
    WAIST(BodyMeasurePoint.WAIST, .10f, 2.06f, PremiumLabelSide.RIGHT),
    ABDOMEN(BodyMeasurePoint.ABDOMEN, .00f, 2.06f, PremiumLabelSide.LEFT),
    HIPS(BodyMeasurePoint.HIPS, -.12f, 2.12f, PremiumLabelSide.RIGHT),
    DRESS_LENGTH(BodyMeasurePoint.DRESS_LENGTH, .00f, 2.68f, PremiumLabelSide.LEFT),
    ARM_LENGTH(BodyMeasurePoint.ARM_LENGTH, .22f, 2.04f, PremiumLabelSide.RIGHT),
    UPPER_ARM(BodyMeasurePoint.UPPER_ARM, .34f, 1.96f, PremiumLabelSide.RIGHT),
    WRIST(BodyMeasurePoint.WRIST, .04f, 1.90f, PremiumLabelSide.RIGHT),
    ;

    fun title(language: String): String = if (language == "ar") when (this) {
        HEIGHT -> "الطول الكامل"
        NECK -> "محيط الرقبة"
        SHOULDERS -> "عرض الكتفين"
        SHOULDER_LENGTH -> "طول الكتف"
        CHEST -> "محيط الصدر"
        UNDERBUST -> "محيط أسفل الصدر"
        BUST_HEIGHT -> "ارتفاع الصدر"
        BUST_POINT_DISTANCE -> "المسافة بين نقطتي الصدر"
        WAIST -> "محيط الخصر"
        ABDOMEN -> "محيط البطن"
        HIPS -> "محيط الأرداف"
        DRESS_LENGTH -> "طول الفستان"
        ARM_LENGTH -> "طول الذراع"
        UPPER_ARM -> "محيط العضد"
        WRIST -> "محيط المعصم"
    } else when (this) {
        HEIGHT -> "Full height"
        NECK -> "Neck circumference"
        SHOULDERS -> "Shoulder width"
        SHOULDER_LENGTH -> "Shoulder length"
        CHEST -> "Bust circumference"
        UNDERBUST -> "Underbust circumference"
        BUST_HEIGHT -> "Bust height"
        BUST_POINT_DISTANCE -> "Bust-point distance"
        WAIST -> "Waist circumference"
        ABDOMEN -> "Abdomen circumference"
        HIPS -> "Hip circumference"
        DRESS_LENGTH -> "Dress length"
        ARM_LENGTH -> "Arm length"
        UPPER_ARM -> "Upper-arm circumference"
        WRIST -> "Wrist circumference"
    }

    fun sideKeys(): Pair<BodySideMeasurement, BodySideMeasurement>? = when (this) {
        ARM_LENGTH -> BodySideMeasurement.RIGHT_ARM_LENGTH to BodySideMeasurement.LEFT_ARM_LENGTH
        UPPER_ARM -> BodySideMeasurement.RIGHT_UPPER_ARM to BodySideMeasurement.LEFT_UPPER_ARM
        WRIST -> BodySideMeasurement.RIGHT_WRIST to BodySideMeasurement.LEFT_WRIST
        else -> null
    }

    fun valueCm(profile: BodyProfile): Float? = when (this) {
        HEIGHT -> profile.heightInches.takeIf { profile.hasExplicitHeight }?.times(PREMIUM_CM_PER_INCH)
        else -> point?.let { profile.measurementsInches[it]?.times(PREMIUM_CM_PER_INCH) }
    }
}
