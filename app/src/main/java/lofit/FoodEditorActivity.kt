package lofit

import android.app.DatePickerDialog
import android.content.ClipData
import android.content.Intent
import android.graphics.Rect
import android.net.Uri
import android.os.Bundle
import android.provider.MediaStore
import android.view.View
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
import androidx.core.content.FileProvider
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.widget.doAfterTextChanged
import java.io.File
import java.time.LocalDate
import java.util.UUID
import java.util.concurrent.Executors

internal class FoodEditorActivity : AppCompatActivity() {
    private val section by lazy { FoodSection.valueOf(intent.getStringExtra("section")!!) }
    private val store by lazy { FoodStore(this) }
    private val ingredients by lazy { store.ingredients().associateBy { it.id } }
    private val pictures by lazy { ingredientPictures(this) }
    private val ui by lazy { LofitViews(this, pictures) { BiologicalGender.MALE } }
    private lateinit var draft: Bundle
    private lateinit var root: FrameLayout
    private val pictureWorker = Executors.newSingleThreadExecutor()
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
            if (result.resultCode == RESULT_OK) importImage(Uri.fromFile(file))
            else discardCapture()
        }

    private val captureDirectory
        get() = File(cacheDir, "ingredient-captures")

    private val captureFile
        get() = draft.getString("captureName")?.let { File(captureDirectory, it) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        draft = savedInstanceState?.getBundle("draft") ?: initialDraft()
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

    private fun initialDraft() =
        Bundle().apply {
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
                    item.product.forEach { (metric, value) ->
                        putString(metric.name, formatWeight(value))
                    }
                }
            } else {
                val recipe = sourceId?.let { id -> store.recipes().first { it.id == id } }
                putString("name", recipe?.name.orEmpty())
                putString("day", LocalDate.now().toString())
                putParcelableArrayList(
                    "rows",
                    ArrayList(
                        recipe?.items.orEmpty().map { row(it.ingredientId, formatWeight(it.grams)) }
                    ),
                )
            }
        }

    private fun row(id: String, grams: String) =
        Bundle().apply {
            putString("ingredient", id)
            putString("grams", grams)
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
        ui.page(if (intent.hasExtra("id")) "Edit ingredient" else "Add ingredient") { body ->
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
            val name = field(body, "Ingredient name", "name")
            val fields =
                FoodMetric.inputs.associateWith {
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
                calories.text = energy.format(energy.valueIn(macros))
            }
            FoodMetric.energySources.forEach {
                fields.getValue(it).doAfterTextChanged { updateCalories() }
            }
            updateCalories()
            body.addView(
                ui.actionButton("Save ingredient") {
                        val title = name.text.toString().trim()
                        if (title.isEmpty()) {
                            fail(name, "Enter an ingredient name")
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
                add("Take photo" to ::openCamera)
                add("Choose from gallery" to { chooseImage.launch("image/*") })
            }
        AlertDialog.Builder(this)
            .setItems(actions.map { it.first }.toTypedArray()) { _, index ->
                actions[index].second()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun openCrop(candidate: String? = null) {
        draft.putBundle(
            "cropDraft",
            Bundle().apply {
                putString("candidate", candidate)
                if (candidate == null)
                    putParcelable("region", draft.getParcelable("crop", Rect::class.java))
            },
        )
        WindowCompat.getInsetsController(window, root).hide(WindowInsetsCompat.Type.ime())
        render()
    }

    private fun closeCrop(apply: Boolean = false) {
        val state = draft.getBundle("cropDraft") ?: return
        val candidate = state.getString("candidate")
        if (apply) {
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
        val image = state.getString("candidate") ?: requireNotNull(draft.getString("image"))
        val sizeLabel = ui.fieldLabel("Crop size")
        val size =
            SeekBar(this).apply {
                contentDescription = "Crop size"
                isEnabled = false
            }
        val applyCrop =
            ui.actionButton("Apply crop") { closeCrop(apply = true) }.apply { isEnabled = false }
        val preview =
            IngredientCropView(this) { region, maxSide ->
                state.putParcelable("region", region)
                size.max = maxSide - 1
                size.progress = region.width() - 1
                size.isEnabled = maxSide > 1
                sizeLabel.text = "Crop size · ${region.width()} × ${region.height()} px"
            }
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
                addView(ui.titleText("Crop image"))
                addView(ui.metaText("Drag the frame to move it. Drag a corner to resize it."))
                addView(preview, LinearLayout.LayoutParams(-1, 0, 1f))
                addView(sizeLabel)
                addView(size, LinearLayout.LayoutParams(-1, ui.dp(48)))
                addView(ui.horizontalActions(ui.smallButton("Cancel") { closeCrop() }, applyCrop))
            }
        pictureWorker.execute {
            val bitmap = pictures.fullPicture(image)
            runOnUiThread {
                if (isDestroyed || isFinishing || form.parent !== root) return@runOnUiThread
                if (bitmap == null) {
                    toast("Could not open image")
                    closeCrop()
                } else {
                    preview.setImage(bitmap, state.getParcelable("region", Rect::class.java))
                    applyCrop.isEnabled = true
                }
            }
        }
        return form
    }

    private fun openCamera() {
        discardCapture()
        try {
            val file =
                File.createTempFile("ingredient-", ".jpg", captureDirectory.apply { mkdirs() })
            draft.putString("captureName", file.name)
            val uri = FileProvider.getUriForFile(this, "${packageName}.workout-files", file)
            takePhoto.launch(
                Intent(MediaStore.ACTION_IMAGE_CAPTURE).apply {
                    putExtra(MediaStore.EXTRA_OUTPUT, uri)
                    clipData = ClipData.newRawUri("Ingredient photo", uri)
                    addFlags(
                        Intent.FLAG_GRANT_READ_URI_PERMISSION or
                            Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                    )
                }
            )
        } catch (error: Exception) {
            discardCapture()
            toast("Could not open camera")
        }
    }

    private fun discardCapture() {
        captureFile?.delete()
        draft.remove("captureName")
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
        val title =
            if (cooking) draft.getString("name")!!
            else if (intent.hasExtra("id")) "Edit recipe" else "Add recipe"
        return ui.page(title) { body ->
            val name = if (cooking) null else field(body, "Recipe name", "name")
            if (cooking) {
                val date = LocalDate.parse(draft.getString("day"))
                body.addView(
                    ui.smallButton("Cooked on $date") {
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
            body.addView(ui.sectionTitle("Ingredients"))
            val summary = FoodSummary(this, ui)
            fun updateTotals() {
                val amounts =
                    rows
                        .map { item ->
                            ingredients
                                .getValue(item.getString("ingredient")!!)
                                .portion(
                                    item.getString("grams")?.foodNumber()?.takeIf { it > 0 } ?: 0.0
                                )
                        }
                        .foodTotals()
                summary.update(amounts)
            }
            val weights = rows.map { item ->
                val ingredient = ingredients.getValue(item.getString("ingredient")!!)
                val input =
                    ui.decimalField("Weight (g)").apply {
                        contentDescription = "${ingredient.name} weight (g)"
                        setText(item.getString("grams"))
                        doAfterTextChanged {
                            item.putString("grams", it.toString())
                            updateTotals()
                        }
                    }
                body.addView(
                    ui.card {
                        addView(
                            ui.smallButton(ingredient.name) {
                                    pickIngredient {
                                        item.putString("ingredient", it.id)
                                        render()
                                    }
                                }
                                .apply { contentDescription = "Replace ${ingredient.name}" }
                        )
                        addView(ui.fieldLabel("Weight (g)"))
                        addView(input)
                        addView(
                            ui.smallButton("Remove") {
                                    rows.remove(item)
                                    render()
                                }
                                .apply {
                                    contentDescription = "Remove ${ingredient.name}"
                                },
                            ui.foodSpacing(),
                        )
                    }
                )
                input
            }
            body.addView(
                ui.smallButton("Add ingredient") {
                    pickIngredient {
                        rows.add(row(it.id, ""))
                        render()
                    }
                },
                ui.foodSpacing(),
            )
            updateTotals()
            body.addView(summary.view, ui.foodSpacing())
            body.addView(
                ui.actionButton(if (cooking) "Save meal" else "Save recipe") {
                    val recipeName = draft.getString("name").orEmpty().trim()
                    if (name != null && recipeName.isEmpty()) {
                        fail(name, "Enter a recipe name")
                        return@actionButton
                    }
                    if (rows.isEmpty()) {
                        toast("Add an ingredient first")
                        return@actionButton
                    }
                    val items = rows.mapIndexed { index, item ->
                        val grams = item.getString("grams").orEmpty().foodNumber()
                        if (grams == null || grams <= 0) {
                            fail(weights[index], "Enter a weight above zero")
                            return@actionButton
                        }
                        RecipeItem(item.getString("ingredient")!!, grams)
                    }
                    val id = draft.getString("id")!!
                    if (cooking) {
                        store.log(
                            CookedMeal(
                                id,
                                recipeName,
                                LocalDate.parse(draft.getString("day")),
                                items.map {
                                    val ingredient = ingredients.getValue(it.ingredientId)
                                    CookedItem(ingredient.name, ingredient.portion(it.grams))
                                },
                            )
                        )
                    } else store.save(FoodRecipe(id, recipeName, items))
                    saved()
                },
                ui.foodSpacing(),
            )
        }
    }

    private fun pickIngredient(select: (Ingredient) -> Unit) {
        val choices = ingredients.values.toList()
        if (choices.isEmpty()) {
            toast("Add an ingredient in Ingredients first")
            return
        }
        AlertDialog.Builder(this)
            .setTitle("Choose ingredient")
            .setItems(choices.map { it.name }.toTypedArray()) { _, index -> select(choices[index]) }
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
