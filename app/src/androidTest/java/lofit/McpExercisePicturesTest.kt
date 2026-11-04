package lofit

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.Base64
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class McpExercisePicturesTest {
    private val context = RepositoryTestContext("mcp-pictures")
    private val directory
        get() = File(context.filesDir, "exercise-pictures")

    private lateinit var repository: LofitRepository
    private lateinit var protocol: McpProtocol
    private var changes = 0
    private val exerciseId = "custom-picture"

    @Before
    fun setup() {
        context.clearRepositoryTestData()
        directory.deleteRecursively()
        repository = LofitRepository(context)
        repository.data.exercises.add(
            Exercise(
                exerciseId,
                "Custom hold",
                ExerciseType.TIMED,
                true,
                "Grip",
                "Forearms",
                custom = true,
                weighted = true,
                liveMeasurement = true,
            )
        )
        repository.save()
        changes = 0
        protocol = McpProtocol(repository, ExercisePictures(context)) { changes++ }
    }

    @After
    fun cleanup() {
        directory.deleteRecursively()
        context.clearRepositoryTestData()
    }

    private fun image(
        color: Int = Color.RED,
        format: Bitmap.CompressFormat = Bitmap.CompressFormat.PNG,
    ): String {
        val bitmap = Bitmap.createBitmap(2048, 1024, Bitmap.Config.ARGB_8888)
        return try {
            bitmap.eraseColor(color)
            val output = ByteArrayOutputStream()
            assertTrue(bitmap.compress(format, 100, output))
            Base64.getEncoder().encodeToString(output.toByteArray())
        } finally {
            bitmap.recycle()
        }
    }

    private fun request(args: JSONObject): JSONObject =
        protocol.handle(
            JSONObject()
                .put("jsonrpc", "2.0")
                .put("id", 1)
                .put("method", "tools/call")
                .put("params", JSONObject().put("name", "edit_exercise").put("arguments", args))
                .toString()
        )!!

    private fun args() = JSONObject().put("exerciseId", exerciseId)

    private fun attach(encoded: String = image()): String {
        val result = request(args().put("imageBase64", encoded)).getJSONObject("result")
        assertFalse(result.toString(), result.getBoolean("isError"))
        return result
            .getJSONObject("structuredContent")
            .getJSONObject("exercise")
            .getString("imageFile")
    }

    @Test
    fun attachesSupportedImagesScalesAndPersistsWithoutChangingExerciseFields() {
        val original = repository.data.exercises.single { it.id == exerciseId }.copy()
        listOf(
                Bitmap.CompressFormat.PNG,
                Bitmap.CompressFormat.JPEG,
                Bitmap.CompressFormat.WEBP_LOSSY,
            )
            .forEach { format ->
                val name = attach(image(format = format))
                assertTrue(name.endsWith(".webp"))
                val saved = LofitRepository(context).data.exercises.single { it.id == exerciseId }
                assertEquals(original.copy(imageFile = name), saved)
                val bitmap = BitmapFactory.decodeFile(File(directory, name).path)!!
                try {
                    assertEquals(1024, bitmap.width)
                    assertEquals(512, bitmap.height)
                    assertTrue(Color.red(bitmap.getPixel(512, 256)) > 240)
                } finally {
                    bitmap.recycle()
                }
                assertEquals(listOf(name), directory.list()!!.toList())
            }
        assertEquals(3, changes)
    }

    @Test
    fun omittedPictureIsPreservedAndReplacementAndRemovalCleanOldFiles() {
        val first = attach()
        assertFalse(
            request(args().put("name", "Renamed")).getJSONObject("result").getBoolean("isError")
        )
        assertEquals(
            first,
            LofitRepository(context).data.exercises.single { it.id == exerciseId }.imageFile,
        )
        assertTrue(File(directory, first).exists())
        val second = attach(image(Color.BLUE))
        assertNotEquals(first, second)
        assertFalse(File(directory, first).exists())
        assertTrue(File(directory, second).exists())
        assertFalse(
            request(args().put("imageBase64", JSONObject.NULL))
                .getJSONObject("result")
                .getBoolean("isError")
        )
        assertNull(LofitRepository(context).data.exercises.single { it.id == exerciseId }.imageFile)
        assertTrue(directory.list()!!.isEmpty())
    }

    @Test
    fun replacingSharedPictureKeepsOtherExercisePicture() {
        val first = attach()
        val other = repository.data.exercises.single { it.id == exerciseId }.copy(id = "other")
        repository.data.exercises.add(other)
        repository.save()
        attach(image(Color.BLUE))
        assertTrue(File(directory, first).exists())
        assertEquals(
            first,
            LofitRepository(context).data.exercises.single { it.id == other.id }.imageFile,
        )
    }

    @Test
    fun invalidPicturesAndFieldsDoNotSaveOrLeakFiles() {
        val first = attach()
        val before = context.repositoryStateFile.readText()
        val invalid =
            listOf(
                args().put("imageBase64", ""),
                args().put("imageBase64", "not base64!"),
                args()
                    .put(
                        "imageBase64",
                        Base64.getEncoder().encodeToString("not an image".toByteArray()),
                    ),
                args().put("imageBase64", 123),
                args().put("imageBase64", "A".repeat(4 * ((5 * 1024 * 1024 + 2) / 3) + 4)),
                args()
                    .put(
                        "imageBase64",
                        Base64.getEncoder().encodeToString(ByteArray(5 * 1024 * 1024 + 1)),
                    ),
                args().put("imageBase64", "data:image/png;base64," + image()),
                args().put("imageBase64", image()).put("name", " "),
                args().put("imageBase64", image()).put("exerciseId", "missing"),
                args().put("imageBase64", image()).put("exerciseId", "bench-press"),
            )
        invalid.forEach { input ->
            val result = request(input).getJSONObject("result")
            assertTrue(result.toString(), result.getBoolean("isError"))
            assertEquals(before, context.repositoryStateFile.readText())
            assertEquals(first, repository.data.exercises.single { it.id == exerciseId }.imageFile)
            assertEquals(listOf(first), directory.list()!!.toList())
        }
        assertEquals(1, changes)
    }

    @Test
    fun failedSaveKeepsOldPictureAndRemovesNewUpload() {
        val first = attach()
        val before = context.repositoryStateFile.readText()
        val blockedWrite = File(context.repositoryStateFile.path + ".new")
        assertTrue(blockedWrite.mkdir())
        try {
            listOf<Any>(image(Color.BLUE), JSONObject.NULL).forEach { picture ->
                val result = request(args().put("imageBase64", picture).put("name", "Changed"))
                assertEquals(-32603, result.getJSONObject("error").getInt("code"))
                assertEquals(before, context.repositoryStateFile.readText())
                assertEquals(
                    first,
                    repository.data.exercises.single { it.id == exerciseId }.imageFile,
                )
                assertEquals(listOf(first), directory.list()!!.toList())
                assertEquals(1, changes)
            }
        } finally {
            assertTrue(blockedWrite.delete())
        }
    }
}
