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
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

private const val DRESS_CM_PER_INCH = 2.54f
private const val DRESS_KG_PER_POUND = 0.45359237f
private const val DRESS_HEADER = 0xFF12345E.toInt()
private const val DRESS_SURFACE = 0xFF1A4777.toInt()
private const val DRESS_PANEL = 0xF21B456F.toInt()
private const val DRESS_PANEL_SOFT = 0xF01A416C.toInt()
private const val DRESS_BLUE = 0xFF72B9FF.toInt()
private const val DRESS_RED = 0xFFFF3D4B.toInt()
private const val DRESS_TEXT_SOFT = 0xFFD4E7FA.toInt()

/**
 * Tailoring-first measurement surface backed by the live Filament rig.
 *
 * Hotspots are not fixed screen coordinates anymore. Every frame, Filament projects the actual
 * skeleton into screen space and this Activity derives tailoring landmarks from those projected
 * joints. The result is stable through orbit, pinch zoom, focus zoom, weight morphs and asymmetric
 * arm edits.
 */
@AndroidEntryPoint
class DressMeasurementActivity : ComponentActivity() {
    @Inject lateinit var bodyProfileStore: BodyProfileStore

    private lateinit var runtime: PersistentFilamentRuntime
    private lateinit var surfaceView: SurfaceView
    private lateinit var countView: TextView
    private lateinit var progressView: DressProgressView
    private lateinit var editor: LinearLayout
    private lateinit var editorTitle: TextView
    private lateinit var editorInput: EditText
    private lateinit var editorSecondaryInput: EditText
    private lateinit var editorPrimaryLabel: TextView
    private lateinit var editorSecondaryLabel: TextView
    private lateinit var editorSecondaryRow: LinearLayout
    private lateinit var guideView: DressMeasurementGuideView
    private lateinit var annotationsView: DressAnnotationsView
    private lateinit var hotspotLayer: FrameLayout
    private lateinit var weightInput: EditText
    private lateinit var topBar: View
    private lateinit var weightDock: View

    private var selectedTarget: TailorTarget? = null
    private var profile = BodyProfile()
    private var language = "ar"
    private var introCompleted = false
    private var projection: BodyScreenProjection? = null
    private val hotspotViews = linkedMapOf<TailorTarget, View>()

    private val layoutScale: Float by lazy {
        val width = resources.configuration.screenWidthDp
        val height = resources.configuration.screenHeightDp
        when {
            width < 350 || height < 650 -> 0.84f
            width < 380 || height < 720 -> 0.89f
            width < 420 || height < 800 -> 0.94f
            else -> 1f
        }
    }

    private val typeScale: Float by lazy {
        val width = resources.configuration.screenWidthDp
        when {
            width < 350 -> 0.88f
            width < 390 -> 0.93f
            width < 430 -> 0.97f
            else -> 1f
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setResult(Activity.RESULT_CANCELED)
        window.statusBarColor = DRESS_HEADER
        window.navigationBarColor = DRESS_HEADER
        window.isNavigationBarContrastEnforced = false
        window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_NOTHING)
        @Suppress("DEPRECATION")
        window.decorView.systemUiVisibility = 0

        language = BodyMeasurementContract.language(intent)
        profile = BodyMeasurementContract.readProfile(intent)

        val root = FrameLayout(this).apply { setBackgroundColor(DRESS_SURFACE) }
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
        root.addView(
            surfaceView,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
            ),
        )

        val topHeight = dp(118)
        val dockHeight = dp(76)

        // Full-screen overlay uses the same coordinate system as the Filament SurfaceView.
        hotspotLayer = FrameLayout(this).apply {
            clipChildren = false
            clipToPadding = false
            isClickable = true
        }
        root.addView(
            hotspotLayer,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
            ),
        )

        annotationsView = DressAnnotationsView()
        hotspotLayer.addView(
            annotationsView,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
            ),
        )

        guideView = DressMeasurementGuideView().apply { visibility = View.GONE }
        hotspotLayer.addView(
            guideView,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
            ),
        )

        TailorTarget.entries.forEach(::addHotspot)

        editor = buildMeasurementEditor().apply { visibility = View.GONE }
        hotspotLayer.addView(
            editor,
            FrameLayout.LayoutParams(dp(138), ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                gravity = Gravity.TOP or Gravity.END
                rightMargin = dp(8)
                topMargin = dp(170)
            },
        )

        // Header and weight dock stay above the gesture/measurement layer.
        topBar = buildTopBar().apply {
            visibility = View.INVISIBLE
            alpha = 0f
        }
        root.addView(
            topBar,
            FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, topHeight).apply {
                gravity = Gravity.TOP
            },
        )

        weightDock = buildWeightDock().apply {
            visibility = View.INVISIBLE
            alpha = 0f
        }
        root.addView(
            weightDock,
            FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dockHeight).apply {
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

    private fun buildTopBar(): View {
        val bar = FrameLayout(this).apply {
            setPadding(dp(12), dp(6), dp(12), dp(5))
            background = solidBg(DRESS_HEADER, 0f)
        }

        val titleBlock = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
        }
        titleBlock.addView(
            text("ALMI / FILAMENT", 12f, 0xFFA3D0FF.toInt(), true).apply { gravity = Gravity.CENTER },
        )
        titleBlock.addView(
            text(if (language == "ar") "قياسات جسمك" else "Your measurements", 24f, Color.WHITE, true).apply {
                gravity = Gravity.CENTER
                setPadding(0, dp(1), 0, 0)
            },
        )
        bar.addView(
            titleBlock,
            FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
                topMargin = dp(5)
            },
        )

        val done = text(if (language == "ar") "✓  تم" else "✓  Done", 14f, Color.WHITE, true).apply {
            gravity = Gravity.CENTER
            background = roundedBg(0xD8244E7C.toInt(), 99f, 0x6695BCE2)
            setOnClickListener {
                persistSideMeasurements()
                setResult(Activity.RESULT_OK, BodyMeasurementContract.resultIntent(profile))
                finish()
            }
        }
        bar.addView(
            done,
            FrameLayout.LayoutParams(dp(69), dp(37)).apply {
                gravity = Gravity.TOP or Gravity.END
                topMargin = dp(4)
            },
        )

        val progressRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        countView = text("0/${TailorTarget.entries.size}", 12f, Color.WHITE, true).apply {
            gravity = Gravity.CENTER
            background = roundedBg(0xE82A557F.toInt(), 12f)
        }
        progressRow.addView(countView, LinearLayout.LayoutParams(dp(52), dp(29)))

        progressView = DressProgressView()
        progressRow.addView(
            progressView,
            LinearLayout.LayoutParams(dp(116), dp(13)).apply { marginStart = dp(9) },
        )

        bar.addView(
            progressRow,
            FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(31)).apply {
                gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
                bottomMargin = dp(1)
            },
        )
        return bar
    }

    private fun buildMeasurementEditor(): LinearLayout {
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(8), dp(7), dp(8), dp(7))
            background = roundedBg(DRESS_PANEL, 14f, 0x728FB5D8)
            elevation = dp(8).toFloat()

            editorTitle = text("", 11.5f, Color.WHITE, true).apply { gravity = Gravity.CENTER }
            addView(editorTitle, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(19)))

            val primaryRow = measurementRow().also { row ->
                editorPrimaryLabel = text("", 8f, DRESS_TEXT_SOFT, true).apply { gravity = Gravity.CENTER }
                row.addView(editorPrimaryLabel, LinearLayout.LayoutParams(dp(29), dp(36)))
                editorInput = measurementEditText()
                row.addView(editorInput, LinearLayout.LayoutParams(0, dp(36), 1f))
                row.addView(
                    text("cm", 8.5f, 0xFFE9F4FF.toInt(), false).apply { gravity = Gravity.CENTER },
                    LinearLayout.LayoutParams(dp(25), dp(36)),
                )
            }
            addView(
                primaryRow,
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(36)).apply { topMargin = dp(5) },
            )

            editorSecondaryRow = measurementRow().apply { visibility = View.GONE }
            editorSecondaryLabel = text("", 8f, DRESS_TEXT_SOFT, true).apply { gravity = Gravity.CENTER }
            editorSecondaryRow.addView(editorSecondaryLabel, LinearLayout.LayoutParams(dp(29), dp(36)))
            editorSecondaryInput = measurementEditText()
            editorSecondaryRow.addView(editorSecondaryInput, LinearLayout.LayoutParams(0, dp(36), 1f))
            editorSecondaryRow.addView(
                text("cm", 8.5f, 0xFFE9F4FF.toInt(), false).apply { gravity = Gravity.CENTER },
                LinearLayout.LayoutParams(dp(25), dp(36)),
            )
            addView(
                editorSecondaryRow,
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(36)).apply { topMargin = dp(4) },
            )

            val actions = LinearLayout(this@DressMeasurementActivity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER
            }
            val cancel = text("×", 18f, 0xFFEAF4FF.toInt(), false).apply {
                gravity = Gravity.CENTER
                background = roundedBg(0xCC1B416B.toInt(), 10f, 0x5F84A9CE)
                setOnClickListener { closeEditor() }
            }
            actions.addView(cancel, LinearLayout.LayoutParams(0, dp(32), 1f))
            val confirm = text("✓", 18f, Color.WHITE, true).apply {
                gravity = Gravity.CENTER
                background = roundedBg(DRESS_BLUE, 10f)
                setOnClickListener { saveSelectedMeasurement() }
            }
            actions.addView(
                confirm,
                LinearLayout.LayoutParams(0, dp(32), 1f).apply { marginStart = dp(5) },
            )
            addView(
                actions,
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(32)).apply { topMargin = dp(5) },
            )
        }
    }

    private fun measurementRow(): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        background = roundedBg(0xE01A416B.toInt(), 10f, 0x5F84A9CE)
    }

    private fun measurementEditText(): EditText = EditText(this).apply {
        textSize = scaledText(15.5f)
        setTextColor(Color.WHITE)
        setHintTextColor(0xFF9BB5D1.toInt())
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

    private fun buildWeightDock(): View {
        val dock = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(10), dp(6), dp(10), dp(6))
            background = roundedBg(DRESS_PANEL_SOFT, 18f, 0x6A8AAED1)
            elevation = dp(6).toFloat()
        }

        val label = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_VERTICAL
        }
        label.addView(text(if (language == "ar") "الوزن" else "Weight", 15f, Color.WHITE, true))
        label.addView(
            text(
                if (language == "ar") "يتفاعل المجسم مباشرة" else "Twin reacts immediately",
                8.3f,
                DRESS_TEXT_SOFT,
                false,
            ),
        )
        dock.addView(label, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))

        val inputShell = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = roundedBg(0xE0183B64.toInt(), 11f, 0x7089AFD2)
        }
        weightInput = EditText(this).apply {
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
        inputShell.addView(weightInput, LinearLayout.LayoutParams(dp(51), dp(40)))
        inputShell.addView(
            text("kg", 9f, 0xFFE8F3FF.toInt(), true).apply { gravity = Gravity.CENTER },
            LinearLayout.LayoutParams(dp(27), dp(40)),
        )
        dock.addView(inputShell, LinearLayout.LayoutParams(dp(78), dp(40)).apply { marginStart = dp(6) })

        val cancel = text("×", 17f, 0xFFEDF6FF.toInt(), false).apply {
            gravity = Gravity.CENTER
            background = roundedBg(0xCC1B416B.toInt(), 11f, 0x5F84A9CE)
            setOnClickListener { cancelWeightEdit() }
        }
        dock.addView(cancel, LinearLayout.LayoutParams(dp(35), dp(40)).apply { marginStart = dp(5) })

        val confirm = text("✓", 17f, Color.WHITE, true).apply {
            gravity = Gravity.CENTER
            background = roundedBg(DRESS_BLUE, 11f)
            setOnClickListener { commitWeight() }
        }
        dock.addView(confirm, LinearLayout.LayoutParams(dp(38), dp(40)).apply { marginStart = dp(5) })
        return dock
    }

    private fun addHotspot(target: TailorTarget) {
        val hit = View(this).apply {
            visibility = View.INVISIBLE
            background = null
            isClickable = true
            setOnClickListener { openEditor(target) }
        }
        hotspotLayer.addView(hit, FrameLayout.LayoutParams(dp(28), dp(28)))
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
        hotspotViews.forEach { (target, holder) -> positionHotspot(target, holder) }
    }

    private fun positionHotspot(target: TailorTarget, holder: View) {
        val anchor = anchorFor(target)
        val size = dp(28)
        holder.x = hotspotLayer.width * anchor.x - size / 2f
        holder.y = hotspotLayer.height * anchor.y - size / 2f
        holder.alpha = if (anchor.visible) 1f else 0.18f
    }

    private fun openEditor(target: TailorTarget) {
        if (!introCompleted) return
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
            editorInput.setText(
                profile.sideMeasurementsInches[rightKey]?.times(DRESS_CM_PER_INCH)?.let(::formatNumber)
                    ?: fallback?.let(::formatNumber).orEmpty(),
            )
            editorSecondaryInput.setText(
                profile.sideMeasurementsInches[leftKey]?.times(DRESS_CM_PER_INCH)?.let(::formatNumber)
                    ?: fallback?.let(::formatNumber).orEmpty(),
            )
        } else {
            editorPrimaryLabel.visibility = View.GONE
            editorSecondaryRow.visibility = View.GONE
            editorInput.setText(target.valueCm(profile)?.let(::formatNumber).orEmpty())
        }

        editor.visibility = View.VISIBLE
        guideView.setTarget(target)
        guideView.visibility = View.VISIBLE
        annotationsView.selectedTarget = target
        runtime.focusOn(target.focusY, target.focusDistance)
        positionEditor(target)
    }

    private fun positionEditor(target: TailorTarget) {
        hotspotLayer.post {
            val anchor = anchorFor(target)
            val estimatedHeight = if (target.sideKeys() != null) dp(146) else dp(104)
            val minTop = dp(124)
            val maxTop = (hotspotLayer.height - dp(86) - estimatedHeight).coerceAtLeast(minTop)
            val desired = (hotspotLayer.height * anchor.y - estimatedHeight * .38f).toInt()
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
        val sideKeys = target.sideKeys()
        if (sideKeys != null) {
            val rightCm = editorInput.text.toString().toFloatOrNull()?.takeIf { it in 1f..300f } ?: return
            val leftCm = editorSecondaryInput.text.toString().toFloatOrNull()?.takeIf { it in 1f..300f } ?: return
            val (rightKey, leftKey) = sideKeys
            val point = target.point ?: return
            profile = profile.copy(
                measurementsInches = profile.measurementsInches + (point to ((rightCm + leftCm) / 2f / DRESS_CM_PER_INCH)),
                sideMeasurementsInches = profile.sideMeasurementsInches + mapOf(
                    rightKey to rightCm / DRESS_CM_PER_INCH,
                    leftKey to leftCm / DRESS_CM_PER_INCH,
                ),
            )
        } else {
            val centimeters = editorInput.text.toString().toFloatOrNull()?.takeIf { it in 1f..300f } ?: return
            profile = if (target == TailorTarget.HEIGHT) {
                profile.copy(heightInches = centimeters / DRESS_CM_PER_INCH, hasExplicitHeight = true)
            } else {
                val point = target.point ?: return
                profile.copy(measurementsInches = profile.measurementsInches + (point to centimeters / DRESS_CM_PER_INCH))
            }
        }
        applyShape()
        refreshUi()
        closeEditor()
    }

    private fun cancelWeightEdit() {
        if (profile.hasExplicitWeight) {
            weightInput.setText(formatNumber(profile.weightPounds * DRESS_KG_PER_POUND))
        } else {
            weightInput.text?.clear()
        }
        weightInput.clearFocus()
        hideKeyboard()
    }

    private fun commitWeight() {
        val kg = weightInput.text.toString().toFloatOrNull()?.takeIf { it in 20f..320f } ?: return
        profile = profile.copy(weightPounds = kg / DRESS_KG_PER_POUND, hasExplicitWeight = true)
        weightInput.clearFocus()
        hideKeyboard()
        applyShape()
        refreshUi()
        runtime.resetFocus()
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

    private fun refreshUi() {
        val total = TailorTarget.entries.size
        val completed = TailorTarget.entries.count { it.valueCm(profile) != null }
        countView.text = "$completed/$total"
        progressView.progress = completed.toFloat() / total.toFloat()
        if (profile.hasExplicitWeight && !weightInput.hasFocus()) {
            weightInput.setText(formatNumber(profile.weightPounds * DRESS_KG_PER_POUND))
        }
        annotationsView.profile = profile
        annotationsView.invalidate()
    }

    private fun renderState(state: BodyRendererState) {
        when (state) {
            BodyRendererState.LOADING -> Unit
            BodyRendererState.READY -> if (!introCompleted) {
                runtime.playIntroSpin(2_150L) { revealInteractiveUi() }
            }
            BodyRendererState.ERROR -> Toast.makeText(
                this,
                if (language == "ar") "تعذر عرض المجسم ثلاثي الأبعاد" else "Unable to render the 3D body",
                Toast.LENGTH_LONG,
            ).show()
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

        TailorTarget.entries.forEachIndexed { index, target ->
            hotspotLayer.postDelayed({
                if (!isFinishing) {
                    annotationsView.revealedCount = index + 1
                    hotspotViews[target]?.visibility = View.VISIBLE
                    positionAllHotspots()
                }
            }, 160L + index * 78L)
        }
    }

    private fun applyShape() {
        if (!::runtime.isInitialized) return
        val shape = BodyShapeSolver.solve(profile)
        runtime.updateBodyShape(shape.widthScale, shape.heightScale, shape.depthScale)
        runtime.updateProfile(profile)
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

    private fun solidBg(color: Int, radius: Float): GradientDrawable = GradientDrawable().apply {
        setColor(color)
        cornerRadius = dp(radius.toInt()).toFloat()
    }

    private fun roundedBg(
        color: Int,
        radius: Float,
        stroke: Int? = null,
        strokeDp: Int = 1,
    ): GradientDrawable = GradientDrawable().apply {
        setColor(color)
        cornerRadius = dp(radius.toInt()).toFloat()
        if (stroke != null) setStroke(dp(strokeDp), stroke)
    }

    private fun dp(value: Int): Int =
        (value * resources.displayMetrics.density * layoutScale).roundToInt().coerceAtLeast(1)

    private fun scaledText(value: Float): Float = value * typeScale

    private fun formatNumber(value: Float): String =
        if (value % 1f == 0f) value.toInt().toString()
        else String.format(Locale.US, "%.1f", value)

    // --- Live anatomical projection -----------------------------------------------------------

    private fun p(name: String): BodyScreenPoint? = projection?.get(name)?.takeIf { it.visible }

    private fun blend(a: BodyScreenPoint?, b: BodyScreenPoint?, t: Float): BodyScreenPoint? {
        if (a == null) return b
        if (b == null) return a
        return BodyScreenPoint(
            x = a.x + (b.x - a.x) * t,
            y = a.y + (b.y - a.y) * t,
            visible = a.visible || b.visible,
        )
    }

    private fun extrapolate(a: BodyScreenPoint?, b: BodyScreenPoint?, t: Float): BodyScreenPoint? {
        if (a == null || b == null) return b ?: a
        return BodyScreenPoint(
            x = a.x + (b.x - a.x) * t,
            y = a.y + (b.y - a.y) * t,
            visible = a.visible || b.visible,
        )
    }

    private fun levelTowardSide(center: BodyScreenPoint?, side: BodyScreenPoint?, factor: Float): BodyScreenPoint? {
        if (center == null) return side
        if (side == null) return center
        return BodyScreenPoint(
            x = center.x + (side.x - center.x) * factor,
            y = center.y,
            visible = center.visible || side.visible,
        )
    }

    private fun anchorFor(target: TailorTarget): BodyScreenPoint {
        val hips = p("Hips")
        val spine = p("Spine")
        val spine1 = p("Spine1")
        val spine2 = p("Spine2")
        val neck = p("Neck")
        val head = p("Head")
        val leftShoulder = blend(p("LeftShoulder"), p("LeftUpperArm"), .52f)
        val rightShoulder = blend(p("RightShoulder"), p("RightUpperArm"), .52f)
        val leftUpperArm = p("LeftUpperArm")
        val rightUpperArm = p("RightUpperArm")
        val rightForeArm = p("RightForeArm")
        val rightHand = p("RightHand")

        val crown = extrapolate(neck, head, 1.32f)
        val chestCenter = blend(spine2, spine1, .28f)
        val underBustCenter = blend(spine2, spine1, .53f)
        val waistCenter = blend(spine1, spine, .39f)
        val abdomenCenter = blend(spine1, spine, .70f)
        val bustRight = levelTowardSide(chestCenter, rightShoulder, .56f)
        val bustLeft = levelTowardSide(chestCenter, leftShoulder, .56f)
        val wrist = blend(rightForeArm, rightHand, .78f)
        val upperArm = blend(rightUpperArm, rightForeArm, .43f)
        val highLeftShoulder = blend(neck, leftShoulder, .58f)

        return when (target) {
            TailorTarget.HEIGHT -> crown
            TailorTarget.NECK -> neck
            TailorTarget.SHOULDERS -> rightShoulder
            TailorTarget.SHOULDER_LENGTH -> leftShoulder
            TailorTarget.CHEST -> levelTowardSide(chestCenter, rightShoulder, .42f)
            TailorTarget.UNDERBUST -> levelTowardSide(underBustCenter, rightShoulder, .38f)
            TailorTarget.BUST_HEIGHT -> bustRight
            TailorTarget.BUST_POINT_DISTANCE -> bustLeft
            TailorTarget.WAIST -> levelTowardSide(waistCenter, rightShoulder, .32f)
            TailorTarget.ABDOMEN -> levelTowardSide(abdomenCenter, rightShoulder, .36f)
            TailorTarget.HIPS -> levelTowardSide(hips, rightShoulder, .45f)
            TailorTarget.DRESS_LENGTH -> highLeftShoulder
            TailorTarget.ARM_LENGTH -> wrist
            TailorTarget.UPPER_ARM -> upperArm
            TailorTarget.WRIST -> wrist
        } ?: BodyScreenPoint(target.fallbackX, target.fallbackY)
    }

    private fun guideFor(target: TailorTarget): DressGuideGeometry {
        val hips = p("Hips") ?: BodyScreenPoint(.50f, .53f)
        val spine = p("Spine") ?: BodyScreenPoint(.50f, .47f)
        val spine1 = p("Spine1") ?: BodyScreenPoint(.50f, .39f)
        val spine2 = p("Spine2") ?: BodyScreenPoint(.50f, .30f)
        val neck = p("Neck") ?: BodyScreenPoint(.50f, .20f)
        val head = p("Head") ?: BodyScreenPoint(.50f, .13f)
        val leftShoulder = blend(p("LeftShoulder"), p("LeftUpperArm"), .52f) ?: BodyScreenPoint(.36f, .25f)
        val rightShoulder = blend(p("RightShoulder"), p("RightUpperArm"), .52f) ?: BodyScreenPoint(.64f, .25f)
        val rightUpperArm = p("RightUpperArm") ?: BodyScreenPoint(.69f, .29f)
        val rightForeArm = p("RightForeArm") ?: BodyScreenPoint(.75f, .39f)
        val rightHand = p("RightHand") ?: BodyScreenPoint(.79f, .48f)
        val leftFoot = p("LeftFoot") ?: BodyScreenPoint(.44f, .90f)
        val rightFoot = p("RightFoot") ?: BodyScreenPoint(.56f, .90f)

        val crown = extrapolate(neck, head, 1.32f) ?: BodyScreenPoint(.50f, .07f)
        val footMid = blend(leftFoot, rightFoot, .5f) ?: BodyScreenPoint(.50f, .90f)
        val chest = blend(spine2, spine1, .28f) ?: BodyScreenPoint(.50f, .32f)
        val underBust = blend(spine2, spine1, .53f) ?: BodyScreenPoint(.50f, .35f)
        val waist = blend(spine1, spine, .39f) ?: BodyScreenPoint(.50f, .43f)
        val abdomen = blend(spine1, spine, .70f) ?: BodyScreenPoint(.50f, .47f)
        val shoulderSpan = abs(rightShoulder.x - leftShoulder.x).coerceAtLeast(.10f)
        val torsoHeight = abs(hips.y - neck.y).coerceAtLeast(.30f)
        val bustRight = levelTowardSide(chest, rightShoulder, .56f) ?: chest
        val bustLeft = levelTowardSide(chest, leftShoulder, .56f) ?: chest
        val wrist = blend(rightForeArm, rightHand, .78f) ?: rightHand
        val upperArm = blend(rightUpperArm, rightForeArm, .43f) ?: rightUpperArm
        val highLeftShoulder = blend(neck, leftShoulder, .58f) ?: leftShoulder

        fun oval(center: BodyScreenPoint, width: Float, height: Float): DressGuideGeometry =
            DressGuideGeometry(
                DressGuideShape.OVAL,
                BodyScreenPoint(center.x - width / 2f, center.y - height / 2f),
                BodyScreenPoint(center.x + width / 2f, center.y + height / 2f),
            )

        return when (target) {
            TailorTarget.HEIGHT -> DressGuideGeometry(
                DressGuideShape.LINE,
                BodyScreenPoint((crown.x - shoulderSpan * .62f).coerceIn(.08f, .92f), crown.y),
                BodyScreenPoint((crown.x - shoulderSpan * .62f).coerceIn(.08f, .92f), footMid.y + .025f),
            )
            TailorTarget.NECK -> oval(neck, shoulderSpan * .22f, torsoHeight * .040f)
            TailorTarget.SHOULDERS -> DressGuideGeometry(DressGuideShape.LINE, leftShoulder, rightShoulder)
            TailorTarget.SHOULDER_LENGTH -> DressGuideGeometry(DressGuideShape.LINE, neck, leftShoulder)
            TailorTarget.CHEST -> oval(chest, shoulderSpan * .78f, torsoHeight * .055f)
            TailorTarget.UNDERBUST -> oval(underBust, shoulderSpan * .70f, torsoHeight * .047f)
            TailorTarget.BUST_HEIGHT -> DressGuideGeometry(
                DressGuideShape.LINE,
                blend(neck, rightShoulder, .28f) ?: neck,
                bustRight,
            )
            TailorTarget.BUST_POINT_DISTANCE -> DressGuideGeometry(DressGuideShape.LINE, bustLeft, bustRight)
            TailorTarget.WAIST -> oval(waist, shoulderSpan * .58f, torsoHeight * .042f)
            TailorTarget.ABDOMEN -> oval(abdomen, shoulderSpan * .64f, torsoHeight * .045f)
            TailorTarget.HIPS -> oval(hips, shoulderSpan * .76f, torsoHeight * .055f)
            TailorTarget.DRESS_LENGTH -> DressGuideGeometry(
                DressGuideShape.LINE,
                highLeftShoulder,
                BodyScreenPoint(highLeftShoulder.x, footMid.y + .010f),
            )
            TailorTarget.ARM_LENGTH -> DressGuideGeometry(DressGuideShape.LINE, rightShoulder, wrist)
            TailorTarget.UPPER_ARM -> oval(upperArm, shoulderSpan * .14f, torsoHeight * .055f)
            TailorTarget.WRIST -> oval(wrist, shoulderSpan * .085f, torsoHeight * .035f)
        }
    }

    private inner class DressProgressView : View(this@DressMeasurementActivity) {
        var progress: Float = 0f
            set(value) {
                field = value.coerceIn(0f, 1f)
                invalidate()
            }
        private val track = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF2B5680.toInt() }
        private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = DRESS_BLUE }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            val radius = height / 2f
            canvas.drawRoundRect(0f, height * .29f, width.toFloat(), height * .71f, radius, radius, track)
            val end = width * progress
            if (end > 0f) canvas.drawRoundRect(0f, height * .29f, end, height * .71f, radius, radius, fill)
        }
    }

    private inner class DressAnnotationsView : View(this@DressMeasurementActivity) {
        var profile: BodyProfile = this@DressMeasurementActivity.profile
        var bodyReady: Boolean = false
        var revealedCount: Int = 0
            set(value) {
                field = value.coerceIn(0, TailorTarget.entries.size)
                invalidate()
            }
        var selectedTarget: TailorTarget? = null
            set(value) {
                field = value
                invalidate()
            }

        private var pulse = 0f
        private val glow = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
        private val dot = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
        private val dotStroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0xFFFFD5D9.toInt()
            strokeWidth = dp(1).toFloat()
            style = Paint.Style.STROKE
        }
        private val value = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0xFFF4F9FF.toInt()
            textSize = dp(8).toFloat()
            typeface = android.graphics.Typeface.DEFAULT_BOLD
        }

        private val animator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 1_050L
            repeatCount = ValueAnimator.INFINITE
            repeatMode = ValueAnimator.REVERSE
            addUpdateListener {
                pulse = it.animatedValue as Float
                invalidate()
            }
            start()
        }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            if (!bodyReady) return

            TailorTarget.entries.take(revealedCount).forEach { target ->
                val anchor = anchorFor(target)
                if (!anchor.visible) return@forEach
                val cx = width * anchor.x
                val cy = height * anchor.y
                val selected = selectedTarget == target
                glow.color = if (selected) 0x82FF3D4B.toInt() else 0x34FF3D4B
                val glowRadius = dp(if (selected) 8 else 4).toFloat() + pulse * dp(if (selected) 2 else 1)
                canvas.drawCircle(cx, cy, glowRadius, glow)
                dot.color = DRESS_RED
                canvas.drawCircle(cx, cy, dp(if (selected) 4 else 3).toFloat(), dot)
                canvas.drawCircle(cx, cy, dp(if (selected) 5 else 4).toFloat(), dotStroke)

                val measured = target.valueCm(profile)
                if (measured != null) {
                    val number = formatNumber(measured)
                    val drawRight = anchor.x <= .53f
                    value.textAlign = if (drawRight) Paint.Align.LEFT else Paint.Align.RIGHT
                    val tx = cx + if (drawRight) dp(8) else -dp(8)
                    canvas.drawText(number, tx.toFloat(), cy + dp(3), value)
                }
            }
        }

        override fun onDetachedFromWindow() {
            animator.cancel()
            super.onDetachedFromWindow()
        }
    }

    private inner class DressMeasurementGuideView : View(this@DressMeasurementActivity) {
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = DRESS_BLUE
            strokeWidth = dp(1.7f).toFloat()
            style = Paint.Style.STROKE
            strokeCap = Paint.Cap.ROUND
        }
        private val travelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0xFFF5FAFF.toInt()
            style = Paint.Style.FILL
        }
        private var target: TailorTarget? = null
        private var phase = 0f
        private val animator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 900L
            repeatCount = ValueAnimator.INFINITE
            repeatMode = ValueAnimator.REVERSE
            addUpdateListener {
                phase = it.animatedValue as Float
                invalidate()
            }
            start()
        }

        fun setTarget(value: TailorTarget) {
            target = value
            invalidate()
        }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            val item = target ?: return
            val geometry = guideFor(item)
            val sx = width * geometry.start.x
            val sy = height * geometry.start.y
            val ex = width * geometry.end.x
            val ey = height * geometry.end.y

            if (geometry.shape == DressGuideShape.OVAL) {
                val rect = RectF(minOf(sx, ex), minOf(sy, ey), maxOf(sx, ex), maxOf(sy, ey))
                canvas.drawOval(rect, paint)
                val angle = phase * Math.PI * 2.0
                val px = rect.centerX() + cos(angle).toFloat() * rect.width() / 2f
                val py = rect.centerY() + sin(angle).toFloat() * rect.height() / 2f
                canvas.drawCircle(px, py, dp(2.5f).toFloat(), travelPaint)
            } else {
                val px = sx + (ex - sx) * phase
                val py = sy + (ey - sy) * phase
                canvas.drawLine(sx, sy, ex, ey, paint)
                canvas.drawCircle(px, py, dp(2.5f).toFloat(), travelPaint)
                drawArrowHead(canvas, sx, sy, ex, ey)
                drawArrowHead(canvas, ex, ey, sx, sy)
            }
        }

        private fun drawArrowHead(canvas: Canvas, tipX: Float, tipY: Float, fromX: Float, fromY: Float) {
            val angle = atan2((tipY - fromY).toDouble(), (tipX - fromX).toDouble())
            val len = dp(7).toFloat()
            val path = Path().apply {
                moveTo(tipX, tipY)
                lineTo(
                    (tipX - len * cos(angle - 0.55)).toFloat(),
                    (tipY - len * sin(angle - 0.55)).toFloat(),
                )
                moveTo(tipX, tipY)
                lineTo(
                    (tipX - len * cos(angle + 0.55)).toFloat(),
                    (tipY - len * sin(angle + 0.55)).toFloat(),
                )
            }
            canvas.drawPath(path, paint)
        }

        override fun onDetachedFromWindow() {
            animator.cancel()
            super.onDetachedFromWindow()
        }
    }

    private fun dp(value: Float): Int =
        (value * resources.displayMetrics.density * layoutScale).roundToInt().coerceAtLeast(1)
}

private enum class DressGuideShape { LINE, OVAL }

private data class DressGuideGeometry(
    val shape: DressGuideShape,
    val start: BodyScreenPoint,
    val end: BodyScreenPoint,
)

/**
 * Professional core set for a fitted women's dress. These are the visible primary measurements;
 * legacy body channels stay in BodyProfile for compatibility but do not clutter this screen.
 */
private enum class TailorTarget(
    val point: BodyMeasurePoint?,
    val fallbackX: Float,
    val fallbackY: Float,
    val focusY: Float,
    val focusDistance: Float,
) {
    HEIGHT(null, .50f, .075f, .00f, 2.86f),
    NECK(BodyMeasurePoint.NECK, .50f, .205f, .60f, 2.18f),
    SHOULDERS(BodyMeasurePoint.SHOULDERS, .66f, .245f, .49f, 2.28f),
    SHOULDER_LENGTH(BodyMeasurePoint.SHOULDER_LENGTH, .36f, .245f, .50f, 2.18f),
    CHEST(BodyMeasurePoint.CHEST, .61f, .320f, .33f, 2.12f),
    UNDERBUST(BodyMeasurePoint.UNDERBUST, .59f, .355f, .25f, 2.10f),
    BUST_HEIGHT(BodyMeasurePoint.BUST_HEIGHT, .61f, .320f, .34f, 2.05f),
    BUST_POINT_DISTANCE(BodyMeasurePoint.BUST_POINT_DISTANCE, .39f, .320f, .34f, 2.05f),
    WAIST(BodyMeasurePoint.WAIST, .58f, .425f, .10f, 2.10f),
    ABDOMEN(BodyMeasurePoint.ABDOMEN, .59f, .465f, .00f, 2.10f),
    HIPS(BodyMeasurePoint.HIPS, .61f, .520f, -.12f, 2.16f),
    DRESS_LENGTH(BodyMeasurePoint.DRESS_LENGTH, .40f, .245f, .00f, 2.70f),
    ARM_LENGTH(BodyMeasurePoint.ARM_LENGTH, .78f, .475f, .22f, 2.08f),
    UPPER_ARM(BodyMeasurePoint.UPPER_ARM, .70f, .305f, .34f, 1.98f),
    WRIST(BodyMeasurePoint.WRIST, .79f, .468f, .04f, 1.92f),
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
        HEIGHT -> profile.heightInches.takeIf { profile.hasExplicitHeight }?.times(DRESS_CM_PER_INCH)
        else -> point?.let { profile.measurementsInches[it]?.times(DRESS_CM_PER_INCH) }
    }
}
