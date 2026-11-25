package lofit

import android.app.DatePickerDialog
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.graphics.Rect
import android.graphics.RectF
import android.net.Uri
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.ArrayAdapter
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.text.buildSpannedString
import androidx.core.text.color
import androidx.core.text.scale
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.widget.doAfterTextChanged
import com.google.android.material.button.MaterialButton
import java.io.File
import java.time.LocalDate
import java.util.UUID
import java.util.concurrent.Executors
import kotlin.math.abs
import kotlin.math.roundToInt

internal class FoodEditorActivity : AppCompatActivity() {
    private val section by lazy { FoodSection.valueOf(intent.getStringExtra("section")!!) }
    private val mealItemId by lazy { intent.getStringExtra("mealItemId") }
    private val loggedMealId by lazy { intent.getStringExtra("loggedMealId") }
    private val store by lazy { FoodStore(this) }
    private val ingredients by lazy { store.ingredients().associateBy { it.id } }
    private val pictures by lazy { ingredientPictures(this) }
    private val ui by lazy { LofitViews(this, pictures) { BiologicalGender.MALE } }
    private var draft = Bundle()
    private lateinit var root: FrameLayout
    private val pictureWorker = Executors.newSingleThreadExecutor()
    private val nutritionFields =
        listOf(
            FoodMetric.FAT,
            FoodMetric.CARBS,
            FoodMetric.SUGAR,
            FoodMetric.FIBER,
            FoodMetric.PROTEIN,
            FoodMetric.SALT,
        )
    private val cancelCrop =
        object : OnBackPressedCallback(false) {
            override fun handleOnBackPressed() = closeCrop()
        }

    private val chooseImage =
        registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
            uri?.let { importImage(it) }
        }
    private val takePhoto =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            val file = captureFile ?: return@registerForActivityResult
            when {
                result.resultCode != RESULT_OK -> discardCapture()
                draft.getBoolean("captureLabel") ->
                    openCrop(
                        scanLabel = true,
                        labelFrame =
                            result.data?.getBundleExtra(IngredientCameraActivity.LABEL_FRAME),
                    )
                else -> importImage(Uri.fromFile(file))
            }
        }

    private val captureDirectory
        get() = File(cacheDir, "ingredient-captures")

    private val captureFile
        get() = draft.getString("captureName")?.let { File(captureDirectory, it) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        draft =
            savedInstanceState?.getBundle("draft")
                ?: initialDraft()
                ?: run {
                    toast("Meal no longer exists")
                    saved()
                    return
                }
        root =
            FrameLayout(this).apply {
                setBackgroundColor(ui.color(R.color.charcoal))
                isFocusableInTouchMode = true
            }
        ViewCompat.setOnApplyWindowInsetsListener(root) { view, insets ->
            val bars =
                insets.getInsets(
                    WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.ime()
                )
            view.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            insets
        }
        onBackPressedDispatcher.addCallback(this, cancelCrop)
        setContentView(root)
        render()
        draft.getString("pendingImage")?.let { importImage(Uri.parse(it)) }
    }

    private fun initialDraft(): Bundle? {
        loggedMealId?.let { id ->
            return store.meals(id).singleOrNull()?.let(::mealDraft)
        }
        return Bundle().apply {
            val sourceId = intent.getStringExtra("id")
            putString(
                "id",
                if (section == FoodSection.MEALS) UUID.randomUUID().toString()
                else sourceId ?: UUID.randomUUID().toString(),
            )
            if (section == FoodSection.INGREDIENTS) {
                ingredients[sourceId]?.let { item ->
                    putString("name", item.name)
                    putString("image", item.image)
                    putString("originalImage", item.image)
                    putParcelable("crop", item.crop)
                    putString("defaultPortion", item.defaultPortionGrams?.let { formatWeight(it) })
                    item.product.forEach { (metric, value) ->
                        putString(metric.name, formatWeight(value))
                    }
                }
            } else {
                putString("day", LocalDate.now().toString())
                val item = mealItemId?.let { ingredients.getValue(it) }
                val recipe = sourceId?.let { id -> store.recipes().first { it.id == id } }
                putString("name", item?.name ?: recipe?.name.orEmpty())
                val items =
                    if (item != null) {
                        val grams =
                            item.defaultPortionGrams ?: item.product.getValue(FoodMetric.WEIGHT)
                        listOf(RecipeItem(item.id, grams))
                    } else recipe?.items.orEmpty()
                putParcelableArrayList(
                    "rows",
                    ArrayList(items.map { row(it.ingredientId, formatWeight(it.grams)) }),
                )
            }
        }
    }

    private fun row(id: String, grams: String) =
        Bundle().apply {
            putString("ingredient", id)
            putString("grams", grams)
        }

    private fun mealDraft(meal: CookedMeal) =
        Bundle().apply {
            putString("id", meal.id)
            putString("name", meal.name)
            putString("day", meal.day.toString())
            putBoolean("singleItem", meal.itemId != null)
            putParcelableArrayList("rows", ArrayList(meal.items.map(::loggedRow)))
        }

    private fun loggedRow(item: CookedItem) =
        Bundle().apply {
            putString("grams", formatWeight(item.amounts.getValue(FoodMetric.WEIGHT)))
            putString("loggedName", item.name)
            item.amounts.forEach { (metric, value) -> putDouble(metric.name, value) }
        }

    private fun Bundle.itemName() =
        getString("loggedName") ?: ingredients.getValue(getString("ingredient")!!).name

    private fun Bundle.portion(grams: Double): FoodAmounts {
        getString("ingredient")?.let {
            return ingredients.getValue(it).portion(grams)
        }
        val scale = grams / getDouble(FoodMetric.WEIGHT.name)
        return FoodMetric.inputs.associateWith { metric ->
            if (metric == FoodMetric.WEIGHT) grams else getDouble(metric.name) * scale
        }
    }

    private val rows
        get() = draft.getParcelableArrayList("rows", Bundle::class.java)!!

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putBundle("draft", draft)
        super.onSaveInstanceState(outState)
    }

    private fun render() {
        val y = (root.getChildAt(0) as? ScrollView)?.scrollY ?: 0
        root.removeAllViews()
        val cropDraft = draft.getBundle("cropDraft")
        cancelCrop.isEnabled = cropDraft != null
        val view =
            when {
                cropDraft != null -> cropForm(cropDraft)
                section == FoodSection.INGREDIENTS -> ingredientForm()
                else -> compositionForm()
            }
        root.addView(view)
        if (y > 0 && view is ScrollView) view.post { view.scrollTo(0, y) }
    }

    private fun field(
        parent: LinearLayout,
        label: String,
        key: String,
        decimal: Boolean = false,
    ): EditText {
        parent.addView(ui.fieldLabel(label))
        return (if (decimal) ui.decimalField(label) else ui.textField(label)).apply {
            contentDescription = label
            setText(draft.getString(key).orEmpty())
            doAfterTextChanged { draft.putString(key, it.toString()) }
            parent.addView(this)
        }
    }

    private fun ingredientForm() =
        ui.page(if (intent.hasExtra("id")) "Edit item" else "Add item") { body ->
            val image = draft.getString("image")
            val pending = draft.containsKey("pendingImage")
            if (image == null) {
                body.addView(
                    ui.smallButton(if (pending) "Loading image…" else "Add image") {
                            showImageOptions(false)
                        }
                        .apply { isEnabled = !pending },
                    ui.foodSpacing(),
                )
            } else {
                body.addView(
                    FrameLayout(this).apply {
                        addView(
                            ui.foodImage(
                                this@FoodEditorActivity,
                                pictures,
                                draft.getString("name").orEmpty(),
                                image,
                                180,
                                draft.getParcelable("crop", Rect::class.java),
                            ),
                            FrameLayout.LayoutParams(-1, -1),
                        )
                        addView(
                            ui.pictureActions(!pending, { showImageOptions(true) }) {
                                discardStagedImage()
                                discardCapture()
                                draft.remove("image")
                                draft.remove("crop")
                                render()
                            }
                        )
                    },
                    LinearLayout.LayoutParams(ui.dp(180), ui.dp(180)).apply {
                        bottomMargin = ui.dp(16)
                    },
                )
            }
            val name = field(body, "Item name", "name")
            body.addView(
                ui.smallButton("Scan nutrition label") { openCamera(scanLabel = true) }
                    .apply { isEnabled = !pending },
                ui.foodSpacing(),
            )
            val labelCheck = ui.metaText(draft.getString("labelCheck").orEmpty())
            if (labelCheck.text.isEmpty()) labelCheck.visibility = View.GONE
            body.addView(labelCheck)
            val weight =
                field(
                    body,
                    FoodMetric.WEIGHT.ingredientLabel,
                    FoodMetric.WEIGHT.name,
                    decimal = true,
                )
            val price =
                field(body, FoodMetric.PRICE.ingredientLabel, FoodMetric.PRICE.name, decimal = true)
            val defaultPortion =
                field(body, "Default portion size (g, optional)", "defaultPortion", decimal = true)
            val fields =
                mapOf(FoodMetric.WEIGHT to weight, FoodMetric.PRICE to price) +
                    nutritionFields.associateWith {
                        field(body, it.ingredientLabel, it.name, decimal = true)
                    }
            val energy = FoodMetric.CALORIES
            body.addView(ui.fieldLabel(energy.ingredientLabel))
            val calories = ui.titleText("")
            body.addView(calories)
            fun updateCalories() {
                val macros =
                    FoodMetric.energySources.associateWith {
                        fields.getValue(it).text.toString().foodNumber() ?: 0.0
                    }
                val estimate = energy.format(energy.valueIn(macros))
                val scanned = draft.getString("scannedCalories")?.let { "$it kcal" }
                val confirmed = draft.getBoolean("caloriesConfirmed")
                calories.text = buildSpannedString {
                    append(estimate)
                    if (scanned != null) {
                        append(' ')
                        color(ui.color(android.R.color.darker_gray)) {
                            scale(0.78f) { append("($scanned)") }
                        }
                    }
                    if (confirmed) {
                        append('\u00A0')
                        color(ui.color(R.color.target_green)) { append('✓') }
                    }
                }
                calories.contentDescription =
                    if (scanned != null || confirmed) {
                        buildString {
                            append(estimate)
                            if (scanned != null) append(". Scanned $scanned")
                            if (confirmed) append(". Matches scanned calories within 1 kcal.")
                        }
                    } else null
            }
            val warnings = draft.getBundle("nutritionWarnings")
            nutritionFields.forEach { metric ->
                val input = fields.getValue(metric)
                warnings?.getString(metric.name)?.let { input.error = it }
                input.doAfterTextChanged {
                    if (warnings?.containsKey(metric.name) == true) {
                        warnings.remove(metric.name)
                        input.error = null
                    }
                    draft.remove("labelCheck")
                    draft.remove("caloriesConfirmed")
                    labelCheck.visibility = View.GONE
                    updateCalories()
                }
            }
            updateCalories()
            body.addView(
                ui.actionButton("Save item") {
                        val title = name.text.toString().trim()
                        if (title.isEmpty()) {
                            fail(name, "Enter an item name")
                            return@actionButton
                        }
                        val portionText = defaultPortion.text.toString()
                        val portionGrams = portionText.foodNumber()?.takeIf { it > 0 }
                        if (portionText.isNotBlank() && portionGrams == null) {
                            fail(defaultPortion, "Enter a weight above zero")
                            return@actionButton
                        }
                        val amounts = mutableMapOf<FoodMetric, Double>()
                        for ((metric, input) in fields) {
                            val raw = input.text.toString()
                            val value =
                                if (raw.isBlank() && metric != FoodMetric.WEIGHT) 0.0
                                else raw.foodNumber()
                            if (value == null || !metric.validInput(value)) {
                                fail(
                                    input,
                                    when (metric) {
                                        FoodMetric.WEIGHT -> "Enter a weight above zero"
                                        FoodMetric.PRICE -> "Enter a nonnegative price"
                                        else -> "Enter 0 to 100 g"
                                    },
                                )
                                return@actionButton
                            }
                            amounts[metric] = value
                        }
                        if (
                            amounts.getValue(FoodMetric.SUGAR) > amounts.getValue(FoodMetric.CARBS)
                        ) {
                            fail(
                                fields.getValue(FoodMetric.SUGAR),
                                "Sugars cannot exceed total carbs",
                            )
                            return@actionButton
                        }
                        store.save(
                            Ingredient(
                                draft.getString("id")!!,
                                title,
                                image,
                                amounts,
                                draft.getParcelable("crop", Rect::class.java),
                                defaultPortionGrams = portionGrams,
                            )
                        )
                        val original = draft.getString("originalImage")
                        if (original != null && original != image) pictures.delete(original)
                        draft.putString("originalImage", image)
                        saved()
                    }
                    .apply { isEnabled = !pending },
                ui.foodSpacing(),
            )
        }

    private fun showImageOptions(hasImage: Boolean) {
        val actions =
            buildList<Pair<String, () -> Unit>> {
                if (hasImage) add("Crop image" to { openCrop() })
                add("Take photo" to { openCamera() })
                add("Choose from gallery" to { chooseImage.launch("image/*") })
            }
        AlertDialog.Builder(this)
            .setItems(actions.map { it.first }.toTypedArray()) { _, index ->
                actions[index].second()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun openCrop(
        candidate: String? = null,
        scanLabel: Boolean = false,
        labelFrame: Bundle? = null,
    ) {
        draft.putBundle(
            "cropDraft",
            Bundle().apply {
                putString("candidate", candidate)
                putBoolean("scanLabel", scanLabel)
                if (labelFrame != null) {
                    putBundle("labelFrame", labelFrame)
                    putBoolean("scanning", true)
                }
                if (candidate == null && !scanLabel)
                    putParcelable("region", draft.getParcelable("crop", Rect::class.java))
            },
        )
        WindowCompat.getInsetsController(window, root).hide(WindowInsetsCompat.Type.ime())
        render()
    }

    private fun closeCrop(apply: Boolean = false) {
        val state = draft.getBundle("cropDraft") ?: return
        val candidate = state.getString("candidate")
        if (state.getBoolean("scanLabel")) {
            discardCapture()
        } else if (apply) {
            val region = state.getParcelable("region", Rect::class.java) ?: return
            if (candidate != null) {
                discardStagedImage()
                draft.putString("image", candidate)
            }
            draft.putParcelable("crop", region)
        } else {
            candidate?.let(pictures::delete)
        }
        draft.remove("cropDraft")
        render()
    }

    private fun cropForm(state: Bundle): View {
        val scanLabel = state.getBoolean("scanLabel")
        val photo = captureFile
        val image = state.getString("candidate") ?: draft.getString("image")
        val sizeLabel = ui.fieldLabel("Crop size")
        val size =
            SeekBar(this).apply {
                contentDescription = "Crop size"
                isEnabled = false
            }
        val preview =
            IngredientCropView(this, square = !scanLabel) { region, maxSide ->
                state.putParcelable("region", region)
                size.max = maxSide - 1
                size.progress = region.width() - 1
                size.isEnabled = maxSide > 1
                sizeLabel.text = "Crop size · ${region.width()} × ${region.height()} px"
            }
        val applyCrop =
            ui.actionButton(if (scanLabel) "Scan label" else "Apply crop") {
                    closeCrop(apply = true)
                }
                .apply { isEnabled = false }
        if (scanLabel)
            applyCrop.setOnClickListener { scanNutritionLabel(state, preview, applyCrop) }
        size.setOnSeekBarChangeListener(
            object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(bar: SeekBar, value: Int, fromUser: Boolean) {
                    if (fromUser) preview.setSize(value + 1)
                }

                override fun onStartTrackingTouch(bar: SeekBar) = Unit

                override fun onStopTrackingTouch(bar: SeekBar) = Unit
            }
        )
        val form =
            LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(ui.dp(14), ui.dp(12), ui.dp(14), ui.dp(12))
                addView(ui.titleText(if (scanLabel) "Crop nutrition label" else "Crop image"))
                addView(
                    ui.metaText(
                        if (scanLabel) "Include the column headings. Drag a corner to resize."
                        else "Drag the frame to move it. Drag a corner to resize it."
                    )
                )
                addView(preview, LinearLayout.LayoutParams(-1, 0, 1f))
                if (!scanLabel) {
                    addView(sizeLabel)
                    addView(size, LinearLayout.LayoutParams(-1, ui.dp(48)))
                }
                addView(ui.horizontalActions(ui.smallButton("Cancel") { closeCrop() }, applyCrop))
            }
        pictureWorker.execute {
            val bitmap =
                if (scanLabel) photo?.let(::labelPhoto) else image?.let(pictures::fullPicture)
            runOnUiThread {
                if (isDestroyed || isFinishing || form.parent !== root) {
                    if (scanLabel) bitmap?.recycle()
                    return@runOnUiThread
                }
                if (bitmap == null) {
                    toast("Could not open image")
                    closeCrop()
                } else {
                    val saved = state.getParcelable("region", Rect::class.java)
                    preview.setImage(bitmap, saved ?: labelFrameRegion(state, bitmap))
                    applyCrop.isEnabled = true
                    if (state.getBoolean("scanning")) applyCrop.performClick()
                }
            }
        }
        return form
    }

    private fun labelFrameRegion(state: Bundle, bitmap: Bitmap): Rect? {
        val framing = state.getBundle("labelFrame") ?: return null
        val frame = framing.getParcelable("fraction", RectF::class.java) ?: return null
        val previewAspect = framing.getFloat("previewAspect")
        val photoAspect = bitmap.width.toFloat() / bitmap.height
        // A quarter turn in the JPEG metadata swaps the axes of the centered guide.
        val crop =
            if (abs(1f / photoAspect - previewAspect) < abs(photoAspect - previewAspect))
                RectF(frame.top, frame.left, frame.bottom, frame.right)
            else frame
        return Rect(
            (crop.left * bitmap.width).roundToInt(),
            (crop.top * bitmap.height).roundToInt(),
            (crop.right * bitmap.width).roundToInt(),
            (crop.bottom * bitmap.height).roundToInt(),
        )
    }

    private fun scanNutritionLabel(
        state: Bundle,
        preview: IngredientCropView,
        button: MaterialButton,
    ) {
        val photo = captureFile ?: return
        val crop = preview.cropFraction()
        state.putBoolean("scanning", true)
        preview.isEnabled = false
        button.isEnabled = false
        button.text = "Scanning…"

        fun showResult(result: Result<NutritionLabel>) {
            if (isDestroyed || isFinishing || draft.getBundle("cropDraft") !== state) return
            state.remove("scanning")
            preview.isEnabled = true
            button.isEnabled = true
            button.text = "Scan label"
            val label = result.getOrNull()
            when {
                label == null -> toast("Could not scan label")
                label.amounts.keys.none { it != FoodMetric.CALORIES } ->
                    toast("No nutrition values found. Crop to the table and try again.")
                else -> {
                    label.amounts.forEach { (metric, value) ->
                        if (metric != FoodMetric.CALORIES)
                            draft.putString(metric.name, formatWeight(value))
                    }
                    draft.putBundle(
                        "nutritionWarnings",
                        Bundle().apply {
                            for (metric in nutritionFields) {
                                val message =
                                    when {
                                        metric !in label.amounts -> "Value not found on label"
                                        metric in label.lowConfidenceReadings ->
                                            "Check scanned value"
                                        else -> continue
                                    }
                                putString(metric.name, message)
                            }
                        },
                    )
                    val energy = FoodMetric.CALORIES
                    draft.putString(
                        "scannedCalories",
                        label.amounts[energy]?.let { formatWeight(it) },
                    )
                    val macros =
                        FoodMetric.energySources
                            .mapNotNull { metric ->
                                val raw = draft.getString(metric.name).orEmpty()
                                val value = if (raw.isBlank()) 0.0 else raw.foodNumber()
                                value?.takeIf(metric::validInput)?.let { metric to it }
                            }
                            .toMap()
                    val estimated =
                        if (macros.size == FoodMetric.energySources.size) {
                            energy.valueIn(macros)
                        } else {
                            null
                        }
                    val matchesCalories = estimated?.let(label::caloriesMatch)
                    draft.putBoolean("caloriesConfirmed", matchesCalories == true)
                    val message =
                        when (matchesCalories) {
                            true -> ""
                            false ->
                                "Scanned calories differ by more than 1 kcal. Check the values."
                            null -> "Check the scanned values. Calorie check incomplete."
                        }
                    draft.putString(
                        "labelCheck",
                        if (label.basisUnconfirmed)
                            "Column heading unreadable. Verify values are per 100 g or 100 ml. $message"
                                .trimEnd()
                        else message,
                    )
                    closeCrop()
                }
            }
        }

        pictureWorker.execute {
            val result = runCatching {
                val cropped = requireNotNull(labelPhoto(photo, crop))
                try {
                    NutritionLabelScanner.scan(cropped)
                } finally {
                    cropped.recycle()
                }
            }
            runOnUiThread { showResult(result) }
        }
    }

    private fun labelPhoto(file: File, crop: RectF? = null): Bitmap? = runCatching {
        ImageDecoder.decodeBitmap(ImageDecoder.createSource(file)) { decoder, info, _ ->
            decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            val sourceWidth = info.size.width
            val sourceHeight = info.size.height
            val longest =
                maxOf(
                    sourceWidth * (crop?.width() ?: 1f),
                    sourceHeight * (crop?.height() ?: 1f),
                )
            val scale = if (longest > 3072) 3072 / longest else 1f
            val width = (sourceWidth * scale).roundToInt().coerceAtLeast(1)
            val height = (sourceHeight * scale).roundToInt().coerceAtLeast(1)
            if (scale < 1) decoder.setTargetSize(width, height)
            if (crop != null) {
                val left = (width * crop.left).roundToInt().coerceIn(0, width - 1)
                val top = (height * crop.top).roundToInt().coerceIn(0, height - 1)
                val right = (width * crop.right).roundToInt().coerceIn(left + 1, width)
                val bottom = (height * crop.bottom).roundToInt().coerceIn(top + 1, height)
                decoder.setCrop(Rect(left, top, right, bottom))
            }
        }
    }
        .getOrNull()

    private fun openCamera(scanLabel: Boolean = false) {
        discardCapture()
        WindowCompat.getInsetsController(window, root).hide(WindowInsetsCompat.Type.ime())
        try {
            val file =
                File.createTempFile("ingredient-", ".jpg", captureDirectory.apply { mkdirs() })
            draft.putString("captureName", file.name)
            draft.putBoolean("captureLabel", scanLabel)
            takePhoto.launch(
                Intent(this, IngredientCameraActivity::class.java)
                    .putExtra(IngredientCameraActivity.OUTPUT_PATH, file.path)
                    .putExtra(IngredientCameraActivity.SCAN_LABEL, scanLabel)
            )
        } catch (error: Exception) {
            discardCapture()
            toast("Could not open camera")
        }
    }

    private fun discardCapture() {
        captureFile?.delete()
        draft.remove("captureName")
        draft.remove("captureLabel")
    }

    private fun importImage(uri: Uri) {
        draft.putString("pendingImage", uri.toString())
        render()
        pictureWorker.execute {
            val result = runCatching { pictures.importPicture(uri) }
            runOnUiThread {
                if (isFinishing || isDestroyed) {
                    result.getOrNull()?.let(pictures::delete)
                    return@runOnUiThread
                }
                draft.remove("pendingImage")
                result
                    .onSuccess { candidate -> openCrop(candidate) }
                    .onFailure {
                        toast("Could not open image")
                        render()
                    }
            }
        }
    }

    private fun discardStagedImage() {
        draft
            .getString("image")
            ?.takeIf { it != draft.getString("originalImage") }
            ?.let(pictures::delete)
    }

    private fun compositionForm(): View {
        val cooking = section == FoodSection.MEALS
        val singleItem = mealItemId != null || draft.getBoolean("singleItem")
        val title =
            if (loggedMealId != null) "Edit meal"
            else if (cooking) draft.getString("name")!!
            else if (intent.hasExtra("id")) "Edit recipe" else "Add recipe"
        return ui.page(title) { body ->
            val name =
                when {
                    loggedMealId != null -> field(body, "Meal name", "name")
                    cooking -> null
                    else -> field(body, "Recipe name", "name")
                }
            if (cooking) {
                val date = LocalDate.parse(draft.getString("day"))
                val dateLabel = if (date == LocalDate.now()) "Today" else date.toString()
                body.addView(
                    ui.smallButton("Meal date · $dateLabel") {
                        DatePickerDialog(
                                this,
                                { _, year, month, day ->
                                    draft.putString(
                                        "day",
                                        LocalDate.of(year, month + 1, day).toString(),
                                    )
                                    render()
                                },
                                date.year,
                                date.monthValue - 1,
                                date.dayOfMonth,
                            )
                            .apply {
                                datePicker.maxDate = System.currentTimeMillis()
                            }
                            .show()
                    },
                    ui.foodSpacing(),
                )
            }
            if (!singleItem) body.addView(ui.sectionTitle("Items"))
            val summary = FoodSummary(this, ui)
            fun updateTotals() {
                val amounts =
                    rows
                        .map { item ->
                            item.portion(
                                item.getString("grams")?.foodNumber()?.takeIf { it > 0 } ?: 0.0
                            )
                        }
                        .foodTotals()
                summary.update(amounts)
            }
            val weights = rows.mapIndexed { index, item ->
                val itemName = item.itemName()
                val input =
                    ui.decimalField("Weight (g)").apply {
                        contentDescription = "$itemName weight (g)"
                        setText(item.getString("grams"))
                        doAfterTextChanged {
                            item.putString("grams", it.toString())
                            updateTotals()
                        }
                    }
                if (singleItem) {
                    body.addView(
                        ui.fieldLabel(
                            if (loggedMealId != null) "$itemName weight (g)" else "Weight (g)"
                        )
                    )
                    body.addView(input, ui.foodSpacing())
                } else
                    body.addView(
                        ui.card {
                            addView(
                                ui.smallButton(itemName) {
                                        pickIngredient {
                                            rows[index] =
                                                row(it.id, item.getString("grams").orEmpty())
                                            render()
                                        }
                                    }
                                    .apply { contentDescription = "Replace $itemName" }
                            )
                            addView(ui.fieldLabel("Weight (g)"))
                            addView(
                                LinearLayout(this@FoodEditorActivity).apply {
                                    orientation = LinearLayout.HORIZONTAL
                                    gravity = Gravity.CENTER_VERTICAL
                                    addView(input, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
                                    addView(
                                        ui.smallButton("") {
                                                rows.remove(item)
                                                render()
                                            }
                                            .apply {
                                                contentDescription = "Remove $itemName"
                                                setIconResource(R.drawable.ic_close)
                                                iconGravity = MaterialButton.ICON_GRAVITY_TEXT_START
                                                iconPadding = 0
                                                setPadding(0, 0, 0, 0)
                                            },
                                        LinearLayout.LayoutParams(ui.dp(48), ui.dp(48)).apply {
                                            marginStart = ui.dp(8)
                                        },
                                    )
                                }
                            )
                        }
                    )
                input
            }
            if (!singleItem)
                body.addView(
                    ui.smallButton("Add item") {
                        pickIngredient { ingredient ->
                            // LEGACY: Ingredients without a default portion start with a blank
                            // weight.
                            val grams = ingredient.defaultPortionGrams?.let { formatWeight(it) }
                            rows.add(row(ingredient.id, grams.orEmpty()))
                            render()
                        }
                    },
                    ui.foodSpacing(),
                )
            updateTotals()
            body.addView(summary.card(), ui.foodSpacing())
            body.addView(
                ui.actionButton(if (cooking) "Save meal" else "Save recipe") {
                    val foodName = draft.getString("name").orEmpty().trim()
                    if (name != null && foodName.isEmpty()) {
                        fail(name, if (cooking) "Enter a meal name" else "Enter a recipe name")
                        return@actionButton
                    }
                    if (rows.isEmpty()) {
                        toast("Add an item first")
                        return@actionButton
                    }
                    val weightsInGrams = rows.mapIndexed { index, item ->
                        val grams = item.getString("grams").orEmpty().foodNumber()
                        if (grams == null || grams <= 0) {
                            fail(weights[index], "Enter a weight above zero")
                            return@actionButton
                        }
                        grams
                    }
                    val id = draft.getString("id")!!
                    if (cooking) {
                        val day = LocalDate.parse(draft.getString("day"))
                        val items = rows.mapIndexed { index, item ->
                            CookedItem(item.itemName(), item.portion(weightsInGrams[index]))
                        }
                        if (loggedMealId != null) {
                            if (!store.updateMeal(id, foodName, day, items))
                                toast("Meal no longer exists")
                        } else {
                            store.log(
                                CookedMeal(
                                    id,
                                    foodName,
                                    day,
                                    items,
                                    recipeId = intent.getStringExtra("id"),
                                    itemId = mealItemId,
                                )
                            )
                        }
                    } else {
                        val items = rows.mapIndexed { index, item ->
                            RecipeItem(item.getString("ingredient")!!, weightsInGrams[index])
                        }
                        store.save(FoodRecipe(id, foodName, items))
                    }
                    saved()
                },
                ui.foodSpacing(),
            )
        }
    }

    private fun pickIngredient(select: (Ingredient) -> Unit) {
        val choices = ingredients.values.toList()
        if (choices.isEmpty()) {
            toast("Add an item in Items first")
            return
        }
        val adapter =
            object : ArrayAdapter<Ingredient>(this, android.R.layout.simple_list_item_1, choices) {
                override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
                    val ingredient = choices[position]
                    return LinearLayout(this@FoodEditorActivity).apply {
                        gravity = Gravity.CENTER_VERTICAL
                        setPadding(ui.dp(20), ui.dp(6), ui.dp(20), ui.dp(6))
                        addView(
                            ui.foodImage(
                                context,
                                pictures,
                                ingredient.name,
                                ingredient.image,
                                54,
                                ingredient.crop,
                            )
                        )
                        addView(ui.titleText(ingredient.name))
                    }
                }
            }
        AlertDialog.Builder(this)
            .setTitle("Choose item")
            .setAdapter(adapter) { _, index -> select(choices[index]) }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun fail(input: EditText, message: String) {
        input.requestFocus()
        input.error = message
    }

    private fun toast(message: String) = Toast.makeText(this, message, Toast.LENGTH_SHORT).show()

    private fun saved() {
        setResult(RESULT_OK)
        finish()
    }

    override fun onDestroy() {
        if (isFinishing) {
            draft.getBundle("cropDraft")?.getString("candidate")?.let(pictures::delete)
            discardStagedImage()
            discardCapture()
        }
        pictureWorker.shutdown()
        store.close()
        super.onDestroy()
    }
}
