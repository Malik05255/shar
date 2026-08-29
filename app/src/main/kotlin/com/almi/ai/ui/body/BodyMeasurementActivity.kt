package com.almi.ai.ui.body

import android.animation.ValueAnimator
import android.app.Activity
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
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
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

private const val CM_PER_INCH = 2.54f
private const val KG_PER_POUND = 0.45359237f
private const val NAVY = 0xFF04101E.toInt()
private const val PANEL = 0xF20B1A2C.toInt()
private const val PANEL_SOFT = 0xED10243B.toInt()
private const val BLUE = 0xFF62A9FF.toInt()
private const val RED = 0xFFFF3A3A.toInt()
private const val TEXT_SOFT = 0xFF9CB4D0.toInt()

/** Native, adaptive Filament body-measurement screen. */
@AndroidEntryPoint
class BodyMeasurementActivity : ComponentActivity() {
    @Inject lateinit var bodyProfileStore: BodyProfileStore

    private lateinit var runtime: PersistentFilamentRuntime
    private lateinit var countView: TextView
    private lateinit var progressView: BodyProgressView
    private lateinit var editor: LinearLayout
    private lateinit var editorTitle: TextView
    private lateinit var editorInput: EditText
    private lateinit var editorSecondaryInput: EditText
    private lateinit var editorPrimaryLabel: TextView
    private lateinit var editorSecondaryLabel: TextView
    private lateinit var editorSecondaryRow: LinearLayout
    private lateinit var guideView: MeasurementGuideView
    private lateinit var annotationsView: MeasurementAnnotationsView
    private lateinit var hotspotLayer: FrameLayout
    private lateinit var weightInput: EditText
    private lateinit var topBar: View
    private lateinit var weightDock: View

    private var selectedTarget: NativeBodyTarget? = null
    private var profile = BodyProfile()
    private var language = "ar"
    private var introCompleted = false
    private val hotspotViews = linkedMapOf<NativeBodyTarget, View>()

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
        window.statusBarColor = NAVY
        window.navigationBarColor = NAVY
        window.isNavigationBarContrastEnforced = false
        window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_NOTHING)
        @Suppress("DEPRECATION")
        window.decorView.systemUiVisibility = 0

        language = BodyMeasurementContract.language(intent)
        profile = BodyMeasurementContract.readProfile(intent)

        val root = FrameLayout(this).apply { setBackgroundColor(NAVY) }
        ViewCompat.setOnApplyWindowInsetsListener(root) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            view.setPadding(0, bars.top, 0, bars.bottom)
            insets
        }

        val surface = SurfaceView(this).apply {
            setZOrderOnTop(false)
            keepScreenOn = true
            background = null
        }
        root.addView(
            surface,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
            ),
        )

        val topHeight = dp(118)
        val dockHeight = dp(78)

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

        hotspotLayer = FrameLayout(this).apply {
            clipChildren = false
            clipToPadding = false
        }
        root.addView(
            hotspotLayer,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
            ).apply {
                topMargin = topHeight
                bottomMargin = dockHeight + dp(8)
            },
        )

        annotationsView = MeasurementAnnotationsView()
        hotspotLayer.addView(
            annotationsView,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
            ),
        )

        guideView = MeasurementGuideView().apply { visibility = View.GONE }
        hotspotLayer.addView(
            guideView,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
            ),
        )

        NativeBodyTarget.entries.forEach(::addHotspot)

        editor = buildMeasurementEditor().apply { visibility = View.GONE }
        hotspotLayer.addView(
            editor,
            FrameLayout.LayoutParams(dp(148), ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                gravity = Gravity.TOP or Gravity.END
                rightMargin = dp(9)
                topMargin = dp(180)
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
            surfaceView = surface,
            onStateChanged = { state -> runOnUiThread { renderState(state) } },
        )
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
            background = solidBg(NAVY, 0f)
        }

        val titleBlock = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
        }
        titleBlock.addView(
            text("ALMI / FILAMENT", 12f, 0xFF87BFFF.toInt(), true).apply { gravity = Gravity.CENTER },
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
            background = roundedBg(0xD8102237.toInt(), 99f, 0x5E6A86A6)
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
        countView = text("0/14", 12f, Color.WHITE, true).apply {
            gravity = Gravity.CENTER
            background = roundedBg(0xE8172940.toInt(), 12f)
        }
        progressRow.addView(countView, LinearLayout.LayoutParams(dp(49), dp(29)))

        progressView = BodyProgressView()
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
            setPadding(dp(9), dp(8), dp(9), dp(8))
            background = roundedBg(PANEL, 15f, 0x5C6C87A6)
            elevation = dp(10).toFloat()

            editorTitle = text("", 13f, Color.WHITE, true).apply { gravity = Gravity.CENTER }
            addView(editorTitle, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(22)))

            val primaryRow = LinearLayout(this@BodyMeasurementActivity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                background = roundedBg(0xFF081625.toInt(), 10f, 0x50627C99)
            }
            editorPrimaryLabel = text("", 9f, TEXT_SOFT, true).apply { gravity = Gravity.CENTER }
            primaryRow.addView(editorPrimaryLabel, LinearLayout.LayoutParams(dp(34), dp(41)))
            editorInput = measurementEditText()
            primaryRow.addView(editorInput, LinearLayout.LayoutParams(0, dp(41), 1f))
            primaryRow.addView(
                text("cm", 10f, 0xFFD4E4F8.toInt(), false).apply { gravity = Gravity.CENTER },
                LinearLayout.LayoutParams(dp(29), dp(41)),
            )
            addView(
                primaryRow,
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(41)).apply { topMargin = dp(6) },
            )

            editorSecondaryRow = LinearLayout(this@BodyMeasurementActivity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                background = roundedBg(0xFF081625.toInt(), 10f, 0x50627C99)
                visibility = View.GONE
            }
            editorSecondaryLabel = text("", 9f, TEXT_SOFT, true).apply { gravity = Gravity.CENTER }
            editorSecondaryRow.addView(editorSecondaryLabel, LinearLayout.LayoutParams(dp(34), dp(41)))
            editorSecondaryInput = measurementEditText()
            editorSecondaryRow.addView(editorSecondaryInput, LinearLayout.LayoutParams(0, dp(41), 1f))
            editorSecondaryRow.addView(
                text("cm", 10f, 0xFFD4E4F8.toInt(), false).apply { gravity = Gravity.CENTER },
                LinearLayout.LayoutParams(dp(29), dp(41)),
            )
            addView(
                editorSecondaryRow,
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(41)).apply { topMargin = dp(5) },
            )

            val actions = LinearLayout(this@BodyMeasurementActivity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER
            }
            val cancel = text("×", 20f, 0xFFD4E4F8.toInt(), false).apply {
                gravity = Gravity.CENTER
                background = roundedBg(0xFF101F31.toInt(), 10f, 0x405D7897)
                setOnClickListener { closeEditor() }
            }
            actions.addView(cancel, LinearLayout.LayoutParams(0, dp(36), 1f))
            val confirm = text("✓", 20f, Color.WHITE, true).apply {
                gravity = Gravity.CENTER
                background = roundedBg(BLUE, 10f)
                setOnClickListener { saveSelectedMeasurement() }
            }
            actions.addView(
                confirm,
                LinearLayout.LayoutParams(0, dp(36), 1f).apply { marginStart = dp(6) },
            )
            addView(
                actions,
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(36)).apply { topMargin = dp(6) },
            )
        }
    }

    private fun measurementEditText(): EditText = EditText(this).apply {
        textSize = scaledText(17f)
        setTextColor(Color.WHITE)
        setHintTextColor(0xFF69809A.toInt())
        hint = "0"
        inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
        imeOptions = EditorInfo.IME_ACTION_DONE
        gravity = Gravity.CENTER
        setSingleLine(true)
        setPadding(dp(3), 0, dp(3), 0)
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
            setPadding(dp(10), dp(7), dp(10), dp(7))
            background = roundedBg(PANEL_SOFT, 18f, 0x5A5F7A98)
            elevation = dp(7).toFloat()
        }

        val label = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_VERTICAL
        }
        label.addView(text(if (language == "ar") "الوزن" else "Weight", 15f, Color.WHITE, true))
        label.addView(
            text(
                if (language == "ar") "يتفاعل الجسم مباشرة" else "Body reacts immediately",
                8.5f,
                TEXT_SOFT,
                false,
            ),
        )
        dock.addView(label, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))

        val inputShell = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = roundedBg(0xFF071524.toInt(), 11f, 0x70667F9B)
        }
        weightInput = EditText(this).apply {
            textSize = scaledText(20f)
            setTextColor(Color.WHITE)
            hint = "80"
            setHintTextColor(0xFF6B829E.toInt())
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
            imeOptions = EditorInfo.IME_ACTION_DONE
            gravity = Gravity.CENTER
            setSingleLine(true)
            setPadding(dp(4), 0, dp(2), 0)
            background = null
            setOnEditorActionListener { _, actionId, _ ->
                if (actionId == EditorInfo.IME_ACTION_DONE) {
                    commitWeight()
                    true
                } else false
            }
        }
        inputShell.addView(weightInput, LinearLayout.LayoutParams(dp(58), dp(43)))
        inputShell.addView(
            text("kg", 10f, 0xFFD7E6F8.toInt(), true).apply { gravity = Gravity.CENTER },
            LinearLayout.LayoutParams(dp(30), dp(43)),
        )
        dock.addView(inputShell, LinearLayout.LayoutParams(dp(88), dp(43)).apply { marginStart = dp(7) })

        val cancel = text("×", 18f, 0xFFD4E4F8.toInt(), false).apply {
            gravity = Gravity.CENTER
            background = roundedBg(0xFF101F31.toInt(), 11f, 0x405D7897)
            setOnClickListener { cancelWeightEdit() }
        }
        dock.addView(cancel, LinearLayout.LayoutParams(dp(37), dp(43)).apply { marginStart = dp(5) })

        val confirm = text("✓", 18f, Color.WHITE, true).apply {
            gravity = Gravity.CENTER
            background = roundedBg(BLUE, 11f)
            setOnClickListener { commitWeight() }
        }
        dock.addView(confirm, LinearLayout.LayoutParams(dp(39), dp(43)).apply { marginStart = dp(5) })
        return dock
    }

    private fun addHotspot(target: NativeBodyTarget) {
        val hit = View(this).apply {
            visibility = View.INVISIBLE
            background = null
            setOnClickListener { openEditor(target) }
        }
        hotspotLayer.addView(hit, FrameLayout.LayoutParams(dp(36), dp(36)))
        hotspotViews[target] = hit
        hotspotLayer.post { positionHotspot(target, hit) }
    }

    private fun positionHotspot(target: NativeBodyTarget, holder: View) {
        val width = hotspotLayer.width
        val height = hotspotLayer.height
        if (width <= 0 || height <= 0) {
            hotspotLayer.post { positionHotspot(target, holder) }
            return
        }
        holder.x = width * target.x - dp(18).toFloat()
        holder.y = height * target.y - dp(18).toFloat()
    }

    private fun openEditor(target: NativeBodyTarget) {
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
            editorInput.setText(profile.sideMeasurementsInches[rightKey]?.times(CM_PER_INCH)?.let(::formatNumber) ?: fallback?.let(::formatNumber).orEmpty())
            editorSecondaryInput.setText(profile.sideMeasurementsInches[leftKey]?.times(CM_PER_INCH)?.let(::formatNumber) ?: fallback?.let(::formatNumber).orEmpty())
        } else {
            editorPrimaryLabel.visibility = View.GONE
            editorSecondaryRow.visibility = View.GONE
            editorInput.setText(target.valueCm(profile)?.let(::formatNumber).orEmpty())
        }

        editor.visibility = View.VISIBLE
        guideView.setTarget(target)
        guideView.visibility = View.VISIBLE
        annotationsView.selectedTarget = target
        positionEditor(target)
        runtime.focusOn(target.focusY, target.focusDistance)
    }

    private fun positionEditor(target: NativeBodyTarget) {
        hotspotLayer.post {
            val desired = (hotspotLayer.height * target.y - dp(55)).toInt()
            val extraHeight = if (target.sideKeys() != null) dp(48) else 0
            val maxTop = (hotspotLayer.height - dp(126) - extraHeight).coerceAtLeast(dp(20))
            val lp = editor.layoutParams as FrameLayout.LayoutParams
            lp.gravity = Gravity.TOP or when {
                target.x > .56f -> Gravity.START
                target.x < .44f -> Gravity.END
                target.y < .52f -> Gravity.END
                else -> Gravity.START
            }
            lp.leftMargin = dp(9)
            lp.rightMargin = dp(9)
            lp.topMargin = desired.coerceIn(dp(18), maxTop)
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
            val rightCm = editorInput.text.toString().toFloatOrNull()?.takeIf { it in 1f..280f } ?: return
            val leftCm = editorSecondaryInput.text.toString().toFloatOrNull()?.takeIf { it in 1f..280f } ?: return
            val (rightKey, leftKey) = sideKeys
            val point = target.point ?: return
            profile = profile.copy(
                measurementsInches = profile.measurementsInches + (point to ((rightCm + leftCm) / 2f / CM_PER_INCH)),
                sideMeasurementsInches = profile.sideMeasurementsInches + mapOf(
                    rightKey to rightCm / CM_PER_INCH,
                    leftKey to leftCm / CM_PER_INCH,
                ),
            )
        } else {
            val centimeters = editorInput.text.toString().toFloatOrNull()?.takeIf { it in 1f..280f } ?: return
            profile = if (target == NativeBodyTarget.HEIGHT) {
                profile.copy(heightInches = centimeters / CM_PER_INCH, hasExplicitHeight = true)
            } else {
                val point = target.point ?: return
                profile.copy(measurementsInches = profile.measurementsInches + (point to centimeters / CM_PER_INCH))
            }
        }
        applyShape()
        refreshUi()
        closeEditor()
    }

    private fun cancelWeightEdit() {
        if (profile.hasExplicitWeight) {
            weightInput.setText(formatNumber(profile.weightPounds * KG_PER_POUND))
        } else {
            weightInput.text?.clear()
        }
        weightInput.clearFocus()
        hideKeyboard()
    }

    private fun commitWeight() {
        val kg = weightInput.text.toString().toFloatOrNull()?.takeIf { it in 20f..320f } ?: return
        profile = profile.copy(weightPounds = kg / KG_PER_POUND, hasExplicitWeight = true)
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
        val completed = NativeBodyTarget.entries.count { it.valueCm(profile) != null } + if (profile.hasExplicitWeight) 1 else 0
        countView.text = "$completed/14"
        progressView.progress = completed / 14f
        if (profile.hasExplicitWeight && !weightInput.hasFocus()) {
            weightInput.setText(formatNumber(profile.weightPounds * KG_PER_POUND))
        }
        annotationsView.profile = profile
        annotationsView.invalidate()
    }

    private fun renderState(state: BodyRendererState) {
        when (state) {
            BodyRendererState.LOADING -> Unit
            BodyRendererState.READY -> if (!introCompleted) {
                runtime.playIntroSpin(2_200L) { revealInteractiveUi() }
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
        topBar.animate().alpha(1f).setDuration(240L).start()
        weightDock.animate().alpha(1f).setDuration(240L).start()
        annotationsView.bodyReady = true
        annotationsView.revealedCount = 0
        hotspotViews.values.forEach { it.visibility = View.INVISIBLE }

        NativeBodyTarget.entries.forEachIndexed { index, target ->
            hotspotLayer.postDelayed({
                if (!isFinishing) {
                    annotationsView.revealedCount = index + 1
                    hotspotViews[target]?.visibility = View.VISIBLE
                }
            }, 180L + index * 90L)
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

    private inner class BodyProgressView : View(this@BodyMeasurementActivity) {
        var progress: Float = 0f
            set(value) {
                field = value.coerceIn(0f, 1f)
                invalidate()
            }
        private val track = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF132238.toInt() }
        private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = BLUE }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            val radius = height / 2f
            canvas.drawRoundRect(0f, height * .29f, width.toFloat(), height * .71f, radius, radius, track)
            val end = width * progress
            if (end > 0f) canvas.drawRoundRect(0f, height * .29f, end, height * .71f, radius, radius, fill)
        }
    }

    private inner class MeasurementAnnotationsView : View(this@BodyMeasurementActivity) {
        var profile: BodyProfile = this@BodyMeasurementActivity.profile
        var bodyReady: Boolean = false
        var revealedCount: Int = 0
            set(value) {
                field = value.coerceIn(0, NativeBodyTarget.entries.size)
                invalidate()
            }
        var selectedTarget: NativeBodyTarget? = null
            set(value) {
                field = value
                invalidate()
            }

        private var pulse = 0f
        private val glow = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
        private val dot = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
        private val dotStroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0xFFFFC4C4.toInt()
            strokeWidth = dp(1).toFloat()
            style = Paint.Style.STROKE
        }
        private val value = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0xFFDCEAFF.toInt()
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

            NativeBodyTarget.entries.take(revealedCount).forEach { target ->
                val cx = width * target.x
                val cy = height * target.y
                val selected = selectedTarget == target
                glow.color = if (selected) 0x77FF3A3A else 0x38FF3434
                val glowRadius = dp(if (selected) 11 else 7).toFloat() + pulse * dp(if (selected) 2 else 1)
                canvas.drawCircle(cx, cy, glowRadius, glow)
                dot.color = RED
                canvas.drawCircle(cx, cy, dp(if (selected) 5 else 4).toFloat(), dot)
                canvas.drawCircle(cx, cy, dp(if (selected) 6 else 5).toFloat(), dotStroke)

                val measured = target.valueCm(profile)
                if (measured != null) {
                    val number = formatNumber(measured)
                    val drawRight = target.x <= .53f
                    value.textAlign = if (drawRight) Paint.Align.LEFT else Paint.Align.RIGHT
                    val tx = cx + if (drawRight) dp(9) else -dp(9)
                    canvas.drawText(number, tx.toFloat(), cy + dp(3), value)
                }
            }
        }

        override fun onDetachedFromWindow() {
            animator.cancel()
            super.onDetachedFromWindow()
        }
    }

    private inner class MeasurementGuideView : View(this@BodyMeasurementActivity) {
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = BLUE
            strokeWidth = dp(2).toFloat()
            style = Paint.Style.STROKE
            strokeCap = Paint.Cap.ROUND
        }
        private val travelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0xFFE6F2FF.toInt()
            style = Paint.Style.FILL
        }
        private var target: NativeBodyTarget? = null
        private var phase = 0f
        private val animator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 820L
            repeatCount = ValueAnimator.INFINITE
            repeatMode = ValueAnimator.REVERSE
            addUpdateListener {
                phase = it.animatedValue as Float
                invalidate()
            }
            start()
        }

        fun setTarget(value: NativeBodyTarget) {
            target = value
            invalidate()
        }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            val item = target ?: return
            val sx = width * item.guideStartX
            val sy = height * item.guideStartY
            val ex = width * item.guideEndX
            val ey = height * item.guideEndY
            val px = sx + (ex - sx) * phase
            val py = sy + (ey - sy) * phase

            canvas.drawLine(sx, sy, ex, ey, paint)
            canvas.drawCircle(px, py, dp(3).toFloat(), travelPaint)
            drawArrowHead(canvas, sx, sy, ex, ey)
            drawArrowHead(canvas, ex, ey, sx, sy)
        }

        private fun drawArrowHead(canvas: Canvas, tipX: Float, tipY: Float, fromX: Float, fromY: Float) {
            val angle = atan2((tipY - fromY).toDouble(), (tipX - fromX).toDouble())
            val len = dp(9).toFloat()
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
}

private enum class NativeBodyTarget(
    val point: BodyMeasurePoint?,
    val x: Float,
    val y: Float,
    val focusY: Float,
    val focusDistance: Float,
    val guideStartX: Float,
    val guideStartY: Float,
    val guideEndX: Float,
    val guideEndY: Float,
) {
    HEIGHT(null, .50f, .105f, .00f, 2.08f, .37f, .10f, .37f, .88f),
    NECK(BodyMeasurePoint.NECK, .50f, .205f, .63f, 1.38f, .445f, .205f, .555f, .205f),
    SHOULDERS(BodyMeasurePoint.SHOULDERS, .64f, .245f, .52f, 1.48f, .34f, .245f, .66f, .245f),
    CHEST(BodyMeasurePoint.CHEST, .50f, .325f, .34f, 1.42f, .36f, .325f, .64f, .325f),
    WAIST(BodyMeasurePoint.WAIST, .50f, .415f, .10f, 1.38f, .40f, .415f, .60f, .415f),
    HIPS(BodyMeasurePoint.HIPS, .40f, .495f, -.12f, 1.42f, .36f, .495f, .64f, .495f),
    ARM_LENGTH(BodyMeasurePoint.ARM_LENGTH, .72f, .405f, .28f, 1.34f, .64f, .245f, .75f, .495f),
    WRIST(BodyMeasurePoint.WRIST, .76f, .495f, .04f, 1.28f, .72f, .495f, .80f, .495f),
    HAND(BodyMeasurePoint.HAND, .73f, .555f, -.04f, 1.24f, .73f, .50f, .73f, .585f),
    THIGH(BodyMeasurePoint.THIGH, .40f, .605f, -.36f, 1.34f, .36f, .605f, .48f, .605f),
    INSEAM(BodyMeasurePoint.INSEAM, .52f, .645f, -.38f, 1.46f, .52f, .505f, .52f, .865f),
    CALF(BodyMeasurePoint.CALF, .40f, .765f, -.68f, 1.30f, .37f, .765f, .47f, .765f),
    FOOT(BodyMeasurePoint.FOOT, .42f, .875f, -.84f, 1.22f, .38f, .875f, .50f, .875f),
    ;

    fun title(language: String): String = if (language == "ar") when (this) {
        HEIGHT -> "الطول"
        NECK -> "محيط الرقبة"
        SHOULDERS -> "عرض الكتفين"
        CHEST -> "محيط الصدر"
        WAIST -> "محيط الخصر"
        HIPS -> "محيط الحوض"
        ARM_LENGTH -> "طول الذراع"
        WRIST -> "محيط المعصم"
        HAND -> "طول اليد"
        THIGH -> "محيط الفخذ"
        INSEAM -> "طول الساق الداخلي"
        CALF -> "محيط الساق"
        FOOT -> "طول القدم"
    } else when (this) {
        HEIGHT -> "Height"
        NECK -> "Neck circumference"
        SHOULDERS -> "Shoulder width"
        CHEST -> "Chest circumference"
        WAIST -> "Waist circumference"
        HIPS -> "Hip circumference"
        ARM_LENGTH -> "Arm length"
        WRIST -> "Wrist circumference"
        HAND -> "Hand length"
        THIGH -> "Thigh circumference"
        INSEAM -> "Inseam"
        CALF -> "Calf circumference"
        FOOT -> "Foot length"
    }

    fun sideKeys(): Pair<BodySideMeasurement, BodySideMeasurement>? = when (this) {
        ARM_LENGTH -> BodySideMeasurement.RIGHT_ARM_LENGTH to BodySideMeasurement.LEFT_ARM_LENGTH
        HAND -> BodySideMeasurement.RIGHT_HAND_LENGTH to BodySideMeasurement.LEFT_HAND_LENGTH
        INSEAM -> BodySideMeasurement.RIGHT_INSEAM to BodySideMeasurement.LEFT_INSEAM
        FOOT -> BodySideMeasurement.RIGHT_FOOT_LENGTH to BodySideMeasurement.LEFT_FOOT_LENGTH
        else -> null
    }

    fun valueCm(profile: BodyProfile): Float? = when (this) {
        HEIGHT -> profile.heightInches.takeIf { profile.hasExplicitHeight }?.times(CM_PER_INCH)
        else -> point?.let { profile.measurementsInches[it]?.times(CM_PER_INCH) }
    }
}
