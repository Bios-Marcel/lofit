package lofit

import android.graphics.BitmapFactory
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ExerciseImageAliasesTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val pictures = ExercisePictures(context)

    private fun assertImage(actual: Int?, expected: Int) {
        val actualBitmap = BitmapFactory.decodeResource(context.resources, requireNotNull(actual))
        val expectedBitmap = BitmapFactory.decodeResource(context.resources, expected)
        try {
            assertTrue(
                "Expected shared image for drawable $actual",
                actualBitmap.sameAs(expectedBitmap),
            )
        } finally {
            actualBitmap.recycle()
            expectedBitmap.recycle()
        }
    }

    @Test
    fun sharedPicturesKeepResourceNamesAndGenderForWeightedAndUnweightedExercises() {
        val catalog = ExerciseCatalog.exercises().associateBy { it.id }
        for (id in listOf("push-up", "weighted-push-up", "plank", "weighted-plank")) {
            val exercise = catalog.getValue(id)
            val name = if (id.endsWith("push-up")) "exercise_push_up" else "exercise_plank"
            val maleSource =
                if (id.endsWith("push-up")) R.drawable.exercise_db_005
                else R.drawable.exercise_db_334
            val femaleSource =
                if (id.endsWith("push-up")) R.drawable.exercise_db_005_female
                else R.drawable.exercise_db_334_female
            val male = requireNotNull(pictures.bundledResource(exercise, BiologicalGender.MALE))
            val female = requireNotNull(pictures.bundledResource(exercise, BiologicalGender.FEMALE))

            assertEquals(name, context.resources.getResourceEntryName(male))
            assertEquals("${name}_female", context.resources.getResourceEntryName(female))
            assertImage(male, maleSource)
            assertImage(female, femaleSource)
            assertImage(pictures.bundledResource(exercise, BiologicalGender.MALE), maleSource)
        }
    }

    @Test
    fun sharingOnlyFemalePicturePreservesMalePictureForNamedAndNumericReferences() {
        val exercise = ExerciseCatalog.exercises().single { it.id == "dumbbell-raise" }
        val numericReference =
            exercise.copy(imageName = null, imageResource = R.drawable.exercise_db_305)
        for (reference in listOf(exercise, numericReference)) {
            val male = pictures.bundledResource(reference, BiologicalGender.MALE)
            val female =
                requireNotNull(pictures.bundledResource(reference, BiologicalGender.FEMALE))

            assertEquals(R.drawable.exercise_db_305, male)
            assertEquals("exercise_db_305_female", context.resources.getResourceEntryName(female))
            assertImage(male, R.drawable.exercise_db_305)
            assertImage(female, R.drawable.exercise_db_012_female)
            assertEquals(male, pictures.bundledResource(reference, BiologicalGender.MALE))
        }

        val unchangedMale =
            BitmapFactory.decodeResource(context.resources, R.drawable.exercise_db_305)
        val otherMale = BitmapFactory.decodeResource(context.resources, R.drawable.exercise_db_012)
        try {
            assertFalse(
                "The unselected male images must stay distinct",
                unchangedMale.sameAs(otherMale),
            )
        } finally {
            unchangedMale.recycle()
            otherMale.recycle()
        }
    }
}
