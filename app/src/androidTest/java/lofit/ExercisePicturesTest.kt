package lofit

import android.Manifest
import android.app.Activity
import android.app.Instrumentation.ActivityResult
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.drawable.BitmapDrawable
import android.os.SystemClock
import android.view.ViewGroup
import android.widget.ImageView
import androidx.core.content.FileProvider
import androidx.core.view.GravityCompat
import androidx.drawerlayout.widget.DrawerLayout
import androidx.test.core.app.ActivityScenario
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.action.ViewActions.*
import androidx.test.espresso.assertion.ViewAssertions.matches
import androidx.test.espresso.matcher.ViewMatchers.*
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import org.hamcrest.Matchers.allOf
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ExercisePicturesTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val pictures = ExercisePictures(context)

    @Before
    fun resetData() {
        instrumentation.uiAutomation.grantRuntimePermission(
            context.packageName,
            Manifest.permission.POST_NOTIFICATIONS,
        )
        context.clearRepositoryTestData()
        LofitRepository(context).apply {
            data.bodyMetricsEnabled = false
            data.onboardingComplete = true
            save()
        }
    }

    private fun launchEditor(): ActivityScenario<MainActivity> {
        val scenario =
            ActivityScenario.launch<MainActivity>(
                Intent(context, MainActivity::class.java)
                    .setAction(Intent.ACTION_MAIN)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        scenario.onActivity { activity ->
            val root = activity.findViewById<ViewGroup>(android.R.id.content)
            (root.getChildAt(0) as DrawerLayout).openDrawer(GravityCompat.START, false)
        }
        onView(allOf(withText(R.string.nav_exercises), isClickable())).perform(click())
        onView(withText("Create custom exercise")).perform(scrollTo(), click())
        onView(withHint("Exercise name")).perform(replaceText("My movement"), closeSoftKeyboard())
        onView(withHint("Search primary muscle")).perform(replaceText("Chest"), closeSoftKeyboard())
        return scenario
    }

    private fun source(color: Int): File {
        val file = File(context.cacheDir, "shared-workouts/picture-$color.png")
        file.parentFile!!.mkdirs()
        val bitmap =
            Bitmap.createBitmap(2000, 1000, Bitmap.Config.ARGB_8888).apply { eraseColor(color) }
        file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
        return file
    }

    private fun pick(file: File, label: String = "Add picture") {
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.workout-files", file)
        val filter =
            IntentFilter(Intent.ACTION_OPEN_DOCUMENT).apply {
                addCategory(Intent.CATEGORY_OPENABLE)
                addDataType("image/*")
            }
        val monitor =
            instrumentation.addMonitor(
                filter,
                ActivityResult(
                    Activity.RESULT_OK,
                    Intent().setData(uri).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION),
                ),
                true,
            )
        try {
            onView(withContentDescription(label)).perform(scrollTo(), click())
            assertEquals(1, monitor.hits)
        } finally {
            instrumentation.removeMonitor(monitor)
        }
    }

    private fun waitForPicture(
        scenario: ActivityScenario<MainActivity>,
        label: String = "Change picture",
    ) {
        val deadline = SystemClock.uptimeMillis() + 10_000
        while (SystemClock.uptimeMillis() < deadline) {
            var ready = false
            scenario.onActivity { activity ->
                fun find(view: android.view.View): Boolean =
                    (view.contentDescription?.toString() == label && view.isEnabled) ||
                        (view is ViewGroup &&
                            (0 until view.childCount).any { find(view.getChildAt(it)) })
                ready = find(activity.findViewById(android.R.id.content))
            }
            if (ready) return
            SystemClock.sleep(50)
        }
        fail("Picture did not load")
    }

    private fun searchCustomExercise() {
        onView(withHint("Search name or muscle"))
            .perform(scrollTo(), replaceText("My movement"), closeSoftKeyboard())
    }

    private fun assertColor(expected: Int, file: String) {
        val bitmap = pictures.thumbnail(file, 180, 128)!!.copy(Bitmap.Config.ARGB_8888, false)
        val actual = bitmap.getPixel(0, 0)
        bitmap.recycle()
        listOf(Color::red, Color::green, Color::blue).forEach { channel ->
            assertTrue(kotlin.math.abs(channel(expected) - channel(actual)) <= 5)
        }
    }

    @Test
    fun everyBundledPictureHasDistinctFemaleVariant() {
        val fields =
            R.drawable::class.java.fields.filter {
                it.name.startsWith("exercise_") && !it.name.endsWith("_female")
            }
        assertTrue(fields.isNotEmpty())
        fields.forEach { field ->
            val maleId = field.getInt(null)
            val femaleId =
                context.resources.getIdentifier(
                    "${field.name}_female",
                    "drawable",
                    context.packageName,
                )
            assertNotEquals("Missing female variant: ${field.name}", 0, femaleId)
            val male = BitmapFactory.decodeResource(context.resources, maleId)
            val female = BitmapFactory.decodeResource(context.resources, femaleId)
            assertNotNull(field.name, female)
            assertEquals(field.name, male.width, female.width)
            assertEquals(field.name, male.height, female.height)
            assertFalse("Variant must differ: ${field.name}", male.sameAs(female))
            male.recycle()
            female.recycle()
        }
        ExerciseCatalog.exercises().forEach { exercise ->
            val name =
                exercise.imageResource?.let(context.resources::getResourceEntryName)
                    ?: exercise.imageName
                    ?: return@forEach
            val male = context.resources.getIdentifier(name, "drawable", context.packageName)
            val female =
                context.resources.getIdentifier("${name}_female", "drawable", context.packageName)
            assertEquals(
                exercise.name,
                male,
                pictures.bundledResource(exercise, BiologicalGender.MALE),
            )
            assertEquals(
                exercise.name,
                female,
                pictures.bundledResource(exercise, BiologicalGender.FEMALE),
            )
            assertEquals(
                exercise.name,
                male,
                pictures.bundledResource(exercise, BiologicalGender.MALE),
            )
        }
    }

    @Test
    fun settingsSwitchPicturesAndKeepChoiceAfterRestart() {
        fun openScreen(scenario: ActivityScenario<MainActivity>, label: Int) {
            scenario.onActivity { activity ->
                val root = activity.findViewById<ViewGroup>(android.R.id.content)
                (root.getChildAt(0) as DrawerLayout).openDrawer(GravityCompat.START, false)
            }
            onView(allOf(withText(label), isClickable())).perform(click())
        }
        fun checkPicture(scenario: ActivityScenario<MainActivity>, gender: BiologicalGender) {
            openScreen(scenario, R.string.nav_exercises)
            val examples =
                ExerciseCatalog.exercises().filter {
                    it.id == "bench-press" || it.imageName == "exercise_db_001"
                }
            examples.forEach { exercise ->
                onView(withHint("Search name or muscle"))
                    .perform(scrollTo(), replaceText(exercise.name), closeSoftKeyboard())
                onView(withContentDescription("${exercise.name} illustration")).check { view, _ ->
                    val expectedId = pictures.bundledResource(exercise, gender)!!
                    val expected = BitmapFactory.decodeResource(context.resources, expectedId)
                    assertTrue(
                        expected.sameAs(((view as ImageView).drawable as BitmapDrawable).bitmap)
                    )
                    expected.recycle()
                }
            }
        }
        ActivityScenario.launch<MainActivity>(
                Intent(context, MainActivity::class.java)
                    .setAction(Intent.ACTION_MAIN)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
            .use { scenario ->
                checkPicture(scenario, BiologicalGender.MALE)
                openScreen(scenario, R.string.nav_settings)
                onView(withText("Female")).perform(click())
                checkPicture(scenario, BiologicalGender.FEMALE)
                scenario.recreate()
                checkPicture(scenario, BiologicalGender.FEMALE)
                openScreen(scenario, R.string.nav_settings)
                onView(withText("Male")).perform(click())
                checkPicture(scenario, BiologicalGender.MALE)
            }
    }

    @Test
    fun createReplaceCancelRemoveAndReloadPicture() {
        launchEditor().use { scenario ->
            val red = source(Color.RED)
            pick(red)
            waitForPicture(scenario)
            red.delete()
            scenario.recreate()
            onView(withHint("Exercise name")).check(matches(withText("My movement")))
            onView(withContentDescription("Change picture")).check(matches(isDisplayed()))
            onView(withText("Save exercise")).perform(scrollTo(), click())
            val saved = LofitRepository(context).data.exercises.single { it.custom }
            val original = saved.imageFile!!
            assertColor(Color.RED, original)
            searchCustomExercise()
            onView(withContentDescription("${saved.name} illustration"))
                .check(matches(isAssignableFrom(ImageView::class.java)))

            onView(withText("Edit")).perform(scrollTo(), click())
            pick(source(Color.BLUE), "Change picture")
            waitForPicture(scenario)
            onView(withText("Cancel")).perform(scrollTo(), click())
            searchCustomExercise()
            assertEquals(
                original,
                LofitRepository(context).data.exercises.single { it.custom }.imageFile,
            )
            assertColor(Color.RED, original)

            onView(withText("Edit")).perform(scrollTo(), click())
            pick(source(Color.GREEN), "Change picture")
            waitForPicture(scenario)
            onView(withText("Save exercise")).perform(scrollTo(), click())
            val replacement =
                LofitRepository(context).data.exercises.single { it.custom }.imageFile!!
            assertNotEquals(original, replacement)
            assertFalse(File(context.filesDir, "exercise-pictures/$original").exists())
            assertColor(Color.GREEN, replacement)
            searchCustomExercise()

            onView(withText("Edit")).perform(scrollTo(), click())
            onView(withContentDescription("Remove picture")).perform(scrollTo(), click())
            scenario.recreate()
            onView(withText("Add picture")).check(matches(isDisplayed()))
            onView(withText("Save exercise")).perform(scrollTo(), click())
            assertNull(LofitRepository(context).data.exercises.single { it.custom }.imageFile)
            assertFalse(File(context.filesDir, "exercise-pictures/$replacement").exists())
            searchCustomExercise()
            onView(withContentDescription("${saved.name} placeholder image"))
                .check(matches(isDisplayed()))
        }
    }

    @Test
    fun corruptAndMissingPicturesLeaveEditorUsable() {
        launchEditor().use { scenario ->
            val bad =
                File(context.cacheDir, "shared-workouts/bad.png").apply {
                    parentFile!!.mkdirs()
                    writeText("broken")
                }
            pick(bad)
            waitForPicture(scenario, "Add picture")
            onView(withText("Save exercise")).perform(scrollTo(), click())
            assertNull(LofitRepository(context).data.exercises.single { it.custom }.imageFile)
        }
        assertNull(pictures.thumbnail("missing.webp", 84, 60))
        assertNull(pictures.thumbnail("../missing.webp", 84, 60))
    }
}
