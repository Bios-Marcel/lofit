package lofit

import android.app.Activity
import android.app.Instrumentation
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.net.Uri
import android.os.Bundle
import android.util.AtomicFile
import android.view.ViewGroup
import android.widget.Button
import androidx.core.view.GravityCompat
import androidx.drawerlayout.widget.DrawerLayout
import androidx.test.core.app.ActivityScenario
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.Espresso.pressBack
import androidx.test.espresso.action.ViewActions.*
import androidx.test.espresso.assertion.ViewAssertions.*
import androidx.test.espresso.matcher.ViewMatchers.*
import androidx.test.espresso.util.TreeIterables
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.time.LocalDate
import java.util.UUID
import org.hamcrest.Matchers.allOf
import org.hamcrest.Matchers.not
import org.hamcrest.Matchers.startsWith
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class FoodFlowTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val store = FoodStore(context)
    private val state = AtomicFile(context.repositoryStateFile)
    private var original: ByteArray? = null
    private val suffix = UUID.randomUUID().toString().take(8)
    private val porridge = "Porridge $suffix"
    private val yogurt = "Yogurt $suffix"
    private val oats =
        Ingredient(
            "oats-$suffix",
            "Oats $suffix",
            null,
            FoodMetric.inputs.associateWith {
                when (it) {
                    FoodMetric.WEIGHT -> 500.0
                    FoodMetric.PRICE -> 2.0
                    FoodMetric.CARBS -> 50.0
                    FoodMetric.SUGAR -> 10.0
                    else -> 0.0
                }
            },
        )
    private val rice = oats.copy(id = "rice-$suffix", name = "Rice $suffix")

    @Before
    fun setup() {
        original = runCatching { state.readFully() }.getOrNull()
        LofitRepository(context).apply {
            data.onboardingComplete = true
            data.activeWorkout = null
            data.bodyMetricsEnabled = false
            data.mcpEnabled = false
            save()
        }
        store.save(oats)
        store.save(rice)
    }

    @After
    fun restore() {
        original?.let { state.finishWrite(state.startWrite().apply { write(it) }) }
            ?: state.delete()
        store
            .ingredients()
            .filter { it.name == yogurt || it.id == oats.id }
            .mapNotNull { it.image }
            .forEach { ingredientPictures(context).delete(it) }
        val db = store.writableDatabase
        db.delete("meals", "name = ?", arrayOf(porridge))
        db.delete("recipes", "name = ?", arrayOf(porridge))
        db.delete("ingredients", "id IN (?, ?) OR name = ?", arrayOf(oats.id, rice.id, yogurt))
        store.close()
    }

    private fun open(
        scenario: ActivityScenario<MainActivity>,
        section: String,
        expand: Boolean = false,
    ) {
        scenario.onActivity {
            val root = it.findViewById<ViewGroup>(android.R.id.content)
            (root.getChildAt(0) as DrawerLayout).openDrawer(GravityCompat.START, false)
        }
        if (expand) onView(withText("Food")).perform(scrollTo(), click())
        onView(allOf(withText(section), isAssignableFrom(Button::class.java)))
            .perform(scrollTo(), click())
    }

    @Test
    fun ingredientFormSavesAndSearchFindsProduct() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            open(scenario, "Ingredients", expand = true)
            onView(withText("Add ingredient")).perform(click())
            onView(withHint("Ingredient name")).perform(replaceText(yogurt))
            chooseImageFromPicker()
            pressBack()
            onView(withText("Add image")).check(matches(isDisplayed()))
            chooseImageFromPicker()
            onView(withText("Apply crop")).perform(click())
            onView(withContentDescription("Change picture")).perform(scrollTo(), click())
            onView(withText("Crop image")).perform(click())
            onView(withText("Cancel")).perform(click())
            onView(withHint("Product weight (g)")).perform(scrollTo(), replaceText("250"))
            onView(withHint("Product price")).perform(scrollTo(), replaceText("1.5"))
            onView(withHint("Carbs per 100 g")).perform(scrollTo(), replaceText("50"))
            onView(withHint("Of which sugars per 100 g")).perform(scrollTo(), replaceText("10"))
            onView(withText("200 kcal")).perform(scrollTo()).check(matches(isDisplayed()))
            onView(withText("Save ingredient")).perform(scrollTo(), click())
            onView(withHint("Search ingredients")).perform(replaceText("YOG"))
            onView(withText(yogurt)).check(matches(isDisplayed()))
            onView(withSubstring("200 kcal / 100 g")).check(matches(isDisplayed()))
            onView(withText(oats.name)).check(doesNotExist())
            val product = store.ingredients().single { it.name == yogurt }
            assertNotNull(ingredientPictures(context).thumbnail(product.image!!, 32, 32))
            assertEquals(125.0, product.portion(250.0).getValue(FoodMetric.CARBS), 0.0)
            assertEquals(25.0, product.portion(250.0).getValue(FoodMetric.SUGAR), 0.0)
            onView(withText(yogurt)).perform(click())
            onView(withContentDescription("Remove picture")).perform(scrollTo(), click())
            onView(withText("Add image")).check(matches(isDisplayed()))
            pressBack()
            assertEquals(product, store.ingredients().single { it.id == product.id })
            onView(withText(yogurt)).perform(click())
            onView(withContentDescription("Remove picture")).perform(scrollTo(), click())
            onView(withText("Save ingredient")).perform(scrollTo(), click())
            assertNull(store.ingredients().single { it.id == product.id }.image)
            assertNull(ingredientPictures(context).fullPicture(requireNotNull(product.image)))
        }
    }

    private fun chooseImageFromPicker() {
        val file = File.createTempFile("ingredient", ".png", context.cacheDir)
        val bitmap = Bitmap.createBitmap(16, 16, Bitmap.Config.ARGB_8888)
        file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val monitor =
            instrumentation.addMonitor(
                IntentFilter(Intent.ACTION_GET_CONTENT).apply {
                    addDataType("image/*")
                    addCategory(Intent.CATEGORY_OPENABLE)
                },
                Instrumentation.ActivityResult(
                    Activity.RESULT_OK,
                    Intent().setData(Uri.fromFile(file)),
                ),
                true,
            )
        try {
            onView(withText("Add image")).perform(scrollTo(), click())
            onView(withText("Choose from gallery")).perform(click())
            awaitCropReady()
        } finally {
            instrumentation.removeMonitor(monitor)
            file.delete()
        }
    }

    private fun awaitCropReady() {
        onView(isRoot())
            .perform(
                waitUntil("crop editor ready") {
                    val matches = arrayListOf<android.view.View>()
                    it.findViewsWithText(
                        matches,
                        "Apply crop",
                        android.view.View.FIND_VIEWS_WITH_TEXT,
                    )
                    matches.any { it.isEnabled }
                }
            )
    }

    @Test
    fun cameraPhotoSurvivesRecreationAndCancelledRetake() {
        val outputs = mutableListOf<File>()
        val camera =
            object : Instrumentation.ActivityMonitor() {
                override fun onStartActivity(intent: Intent): Instrumentation.ActivityResult? {
                    if (intent.component?.className != IngredientCameraActivity::class.java.name)
                        return null
                    assertFalse(intent.getBooleanExtra(IngredientCameraActivity.SCAN_LABEL, true))
                    val output =
                        File(
                            requireNotNull(
                                intent.getStringExtra(IngredientCameraActivity.OUTPUT_PATH)
                            )
                        )
                    outputs.add(output)
                    if (outputs.size > 2)
                        return Instrumentation.ActivityResult(Activity.RESULT_CANCELED, null)
                    val photo = Bitmap.createBitmap(320, 240, Bitmap.Config.ARGB_8888)
                    photo.eraseColor(Color.RED)
                    Canvas(photo)
                        .drawRect(160f, 0f, 320f, 240f, Paint().apply { color = Color.BLUE })
                    output.outputStream().use {
                        assertTrue(photo.compress(Bitmap.CompressFormat.JPEG, 95, it))
                    }
                    photo.recycle()
                    return Instrumentation.ActivityResult(Activity.RESULT_OK, null)
                }
            }
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.addMonitor(camera)
        try {
            val intent =
                Intent(context, FoodEditorActivity::class.java).apply {
                    putExtra("section", FoodSection.INGREDIENTS.name)
                    putExtra("id", oats.id)
                }
            ActivityScenario.launch<FoodEditorActivity>(intent).use { scenario ->
                onView(withText("Add image")).perform(scrollTo(), click())
                onView(withText("Take photo")).perform(click())
                awaitCropReady()
                onView(withContentDescription("Crop size")).perform(click())
                onView(withContentDescription("Crop preview")).perform(swipeRight())
                scenario.recreate()
                onView(withText("Apply crop")).perform(waitUntilMatches(isEnabled()), click())
                assertNull(store.ingredients().single { it.id == oats.id }.crop)
                onView(withContentDescription("Change picture")).perform(scrollTo(), click())
                onView(withText("Take photo")).perform(click())
                awaitCropReady()
                onView(withText("Cancel")).perform(click())
                onView(withContentDescription("Change picture")).perform(scrollTo(), click())
                onView(withText("Take photo")).perform(click())
                onView(withText("Save ingredient")).perform(scrollTo(), click())
                val saved = store.ingredients().single { it.id == oats.id }
                val image =
                    requireNotNull(ingredientPictures(context).thumbnail(saved.image!!, 320, 240))
                assertEquals(320, image.width)
                assertEquals(240, image.height)
                val crop = requireNotNull(saved.crop)
                assertEquals(crop.width(), crop.height())
                assertTrue(crop.width() in 119..122)
                assertEquals(320, crop.right)
                val thumbnail =
                    requireNotNull(
                        ingredientPictures(context).thumbnail(saved.image, null, 32, 32, crop)
                    )
                assertEquals(32, thumbnail.width)
                assertEquals(32, thumbnail.height)
                assertTrue(Color.blue(thumbnail.getPixel(0, 0)) > 220)
                assertEquals(3, outputs.distinct().size)
                outputs.forEach { output -> assertFalse(output.exists()) }
            }
        } finally {
            instrumentation.removeMonitor(camera)
        }
    }

    @Test
    fun capturesAndScansOnlyTheLabelFrame() {
        lateinit var output: File
        val camera =
            object : Instrumentation.ActivityMonitor() {
                override fun onStartActivity(intent: Intent): Instrumentation.ActivityResult? {
                    if (intent.component?.className != IngredientCameraActivity::class.java.name)
                        return null
                    assertTrue(intent.getBooleanExtra(IngredientCameraActivity.SCAN_LABEL, false))
                    output =
                        File(
                            requireNotNull(
                                intent.getStringExtra(IngredientCameraActivity.OUTPUT_PATH)
                            )
                        )
                    val bitmap = Bitmap.createBitmap(1100, 850, Bitmap.Config.ARGB_8888)
                    bitmap.eraseColor(Color.WHITE)
                    val paint =
                        Paint(Paint.ANTI_ALIAS_FLAG).apply {
                            color = Color.BLACK
                            textSize = 42f
                            typeface = Typeface.MONOSPACE
                        }
                    Canvas(bitmap).apply {
                        drawText("Fett 99 g", 220f, 80f, paint)
                        drawText("Nährwerte pro 100 g", 220f, 195f, paint)
                        val rows =
                            listOf(
                                "Energie" to "178 kcal",
                                "Fett" to "6 g",
                                "Kohlenhydrate" to "21 g",
                                "davon Zucker" to "4 g",
                                "Ballaststoffe" to "4 g",
                                "Eiweiß" to "8 g",
                                "Salz" to "0,8 g",
                            )
                        rows.forEachIndexed { index, (label, amount) ->
                            val y = 255f + index * 62f
                            drawText(label, 220f, y, paint)
                            drawText(amount, 680f, y, paint)
                        }
                    }
                    output.outputStream().use {
                        assertTrue(bitmap.compress(Bitmap.CompressFormat.JPEG, 95, it))
                    }
                    bitmap.recycle()
                    val frame =
                        Bundle().apply {
                            putParcelable("fraction", RectF(0.15f, 0.15f, 0.85f, 0.85f))
                            putFloat("previewAspect", 1100f / 850)
                        }
                    val data = Intent().putExtra(IngredientCameraActivity.LABEL_FRAME, frame)
                    return Instrumentation.ActivityResult(Activity.RESULT_OK, data)
                }
            }
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.addMonitor(camera)
        try {
            val editor =
                Intent(context, FoodEditorActivity::class.java)
                    .putExtra("section", FoodSection.INGREDIENTS.name)
            ActivityScenario.launch<FoodEditorActivity>(editor).use {
                onView(withText("Scan nutrition label")).perform(scrollTo(), click())
                onView(isRoot())
                    .perform(
                        waitUntil("label scan finished", timeoutMillis = 15_000) { root ->
                            TreeIterables.breadthFirstViewTraversal(root).any {
                                withText("Scan nutrition label").matches(it)
                            }
                        }
                    )
                onView(withText(startsWith("178 kcal"))).check(matches(isEnabled()))
                onView(allOf(withContentDescription(FoodMetric.FAT.ingredientLabel), withText("6")))
                    .check(matches(isEnabled()))
                assertFalse(output.exists())
            }
        } finally {
            instrumentation.removeMonitor(camera)
        }
    }

    @Test
    fun recipeRowsUpdateTotalsAndSave() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            open(scenario, "Recipes", expand = true)
            onView(withText("Add recipe")).perform(click())
            onView(withHint("Recipe name")).perform(replaceText(porridge))
            onView(withText("Add ingredient")).perform(scrollTo(), click())
            onView(withText(oats.name)).perform(click())
            onView(withContentDescription("${oats.name} weight (g)")).perform(replaceText("120"))
            onView(withText("240 kcal")).perform(scrollTo()).check(matches(isDisplayed()))
            onView(withText("60 g")).perform(scrollTo()).check(matches(isDisplayed()))
            onView(withText("Save recipe")).perform(scrollTo(), click())
            onView(withText(porridge)).check(matches(isDisplayed()))
            assertEquals(
                listOf(RecipeItem(oats.id, 120.0)),
                store.recipes().single { it.name == porridge }.items,
            )
        }
    }

    @Test
    fun cookedSubstitutionChangesTodayOnlyAndStatisticsRetainFilters() {
        val template = FoodRecipe("porridge-$suffix", porridge, listOf(RecipeItem(oats.id, 200.0)))
        store.save(template)
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            open(scenario, "Meals", expand = true)
            onView(withText("Log meal")).perform(click())
            onView(withText(porridge)).perform(click())
            onView(withContentDescription("${oats.name} weight (g)"))
                .check(matches(withText("200")))
            onView(withContentDescription("Replace ${oats.name}")).perform(click())
            onView(withText(rice.name)).perform(click())
            onView(withContentDescription("${rice.name} weight (g)")).perform(replaceText("60"))
            onView(withText("Save meal")).perform(scrollTo(), click())
            onView(withText(porridge)).check(matches(isDisplayed()))
            val meal = store.meals().first { it.name == template.name }
            assertEquals(LocalDate.now(), meal.day)
            assertEquals(rice.name, meal.items.single().name)
            assertEquals(30.0, meal.items.single().amounts.getValue(FoodMetric.CARBS), 0.0)
            assertEquals(template, store.recipes().first { it.id == template.id })
            open(scenario, "Statistics")
            onView(withContentDescription("Daily food totals")).check(matches(isDisplayed()))
            onView(withText("Carbs")).check(matches(isChecked()))
            onView(withText("Price")).check(matches(not(isChecked()))).perform(click())
            onView(withText("Calories"))
                .check(matches(not(isChecked())))
                .perform(scrollTo(), click())
            listOf("7D", "2W", "1M", "3M", "All").forEach { label ->
                onView(withText(label)).perform(scrollTo()).check(matches(isDisplayed()))
            }
            onView(withContentDescription("2 weeks")).perform(click())
            scenario.recreate()
            onView(withContentDescription("2 weeks")).check(matches(isSelected()))
            onView(withText("Price")).check(matches(isChecked()))
            onView(withContentDescription("Daily food totals")).check { view, _ ->
                assertTrue("Carbs" in view.stateDescription.toString())
                assertTrue("Price" in view.stateDescription.toString())
                assertTrue(
                    "Calories" in view.stateDescription.toString() &&
                        "kcal" in view.stateDescription.toString()
                )
            }
        }
    }
}
