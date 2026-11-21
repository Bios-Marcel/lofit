package lofit

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.view.CameraController
import androidx.camera.view.LifecycleCameraController
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.doOnLayout
import com.google.android.material.button.MaterialButton
import java.io.File

internal class IngredientCameraActivity : AppCompatActivity() {
    companion object {
        const val OUTPUT_PATH = "outputPath"
        const val SCAN_LABEL = "scanLabel"
        const val LABEL_FRAME = "labelFrame"
    }

    private val ui by lazy { LofitViews(this, ingredientPictures(this)) { BiologicalGender.MALE } }
    private lateinit var output: File
    private lateinit var preview: PreviewView
    private lateinit var shutter: MaterialButton
    private lateinit var switchCamera: MaterialButton
    private var guide: NutritionLabelGuide? = null
    private var controller: LifecycleCameraController? = null
    private var useFront = false
    private var takingPhoto = false

    private val requestCamera =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) startCamera() else fail("Allow camera access to take a photo")
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val path = intent.getStringExtra(OUTPUT_PATH)
        if (path == null) {
            finish()
            return
        }
        output = File(path)
        useFront = savedInstanceState?.getBoolean("useFront") == true
        val scanLabel = intent.getBooleanExtra(SCAN_LABEL, false)
        if (scanLabel && requestedOrientation != ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE) {
            requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        }
        val wideScan =
            scanLabel && resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE

        preview =
            PreviewView(this).apply {
                contentDescription = "Camera preview"
                scaleType = PreviewView.ScaleType.FILL_CENTER
                implementationMode = PreviewView.ImplementationMode.COMPATIBLE
            }
        shutter = ui.actionButton("Take photo", action = ::takePhoto).apply { isEnabled = false }
        switchCamera =
            ui.smallButton("Switch camera", ::switchLens).apply { visibility = View.GONE }
        val frame = FrameLayout(this).apply { addView(preview, FrameLayout.LayoutParams(-1, -1)) }
        if (scanLabel) {
            guide =
                NutritionLabelGuide(this, wideScan).also {
                    frame.addView(it, FrameLayout.LayoutParams(-1, -1))
                }
        }
        val instruction =
            if (scanLabel) {
                ui.metaText("Fit the nutrition label and column headings in the box.")
            } else null
        val layout =
            LinearLayout(this).apply {
                setBackgroundColor(ui.color(R.color.charcoal))
                if (wideScan) {
                    orientation = LinearLayout.HORIZONTAL
                    addView(frame, LinearLayout.LayoutParams(0, -1, 1f))
                    val controls =
                        LinearLayout(this@IngredientCameraActivity).apply {
                            orientation = LinearLayout.VERTICAL
                            gravity = Gravity.CENTER_VERTICAL
                            setPadding(ui.dp(12), 0, ui.dp(12), 0)
                            instruction?.let { addView(it, ui.foodSpacing()) }
                            addView(shutter, ui.foodSpacing())
                            addView(switchCamera, ui.foodSpacing())
                        }
                    addView(controls, LinearLayout.LayoutParams(ui.dp(144), -1))
                } else {
                    orientation = LinearLayout.VERTICAL
                    addView(frame, LinearLayout.LayoutParams(-1, 0, 1f))
                    instruction?.let {
                        it.setPadding(ui.dp(12), ui.dp(8), ui.dp(12), 0)
                        addView(it)
                    }
                    addView(ui.horizontalActions(switchCamera, shutter))
                }
            }
        ViewCompat.setOnApplyWindowInsetsListener(layout) { view, insets ->
            val bars =
                insets.getInsets(
                    WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout()
                )
            view.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            insets
        }
        setContentView(layout)
        preview.previewStreamState.observe(this) { updateShutter() }

        if (
            ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) ==
                PackageManager.PERMISSION_GRANTED
        ) {
            startCamera()
        } else if (savedInstanceState == null) {
            requestCamera.launch(Manifest.permission.CAMERA)
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putBoolean("useFront", useFront)
        super.onSaveInstanceState(outState)
    }

    private fun startCamera() {
        if (isFinishing || controller != null) return
        val camera =
            LifecycleCameraController(this).apply {
                setEnabledUseCases(CameraController.IMAGE_CAPTURE)
                setImageCaptureMode(ImageCapture.CAPTURE_MODE_MAXIMIZE_QUALITY)
            }
        controller = camera
        val initialization = camera.initializationFuture
        initialization.addListener(
            {
                if (isFinishing || isDestroyed) return@addListener
                preview.doOnLayout {
                    runCatching {
                        initialization.get()
                        val rear = camera.hasCamera(CameraSelector.DEFAULT_BACK_CAMERA)
                        val front = camera.hasCamera(CameraSelector.DEFAULT_FRONT_CAMERA)
                        check(rear || front)
                        useFront = front && (useFront || !rear)
                        camera.cameraSelector = lens(useFront)
                        preview.controller = camera
                        camera.bindToLifecycle(this)
                        switchCamera.visibility = if (front && rear) View.VISIBLE else View.GONE
                    }
                        .onFailure { fail("Could not open camera") }
                }
            },
            mainExecutor,
        )
    }

    private fun lens(front: Boolean) =
        if (front) CameraSelector.DEFAULT_FRONT_CAMERA else CameraSelector.DEFAULT_BACK_CAMERA

    private fun switchLens() {
        val camera = controller ?: return
        shutter.isEnabled = false
        runCatching { camera.cameraSelector = lens(!useFront) }
            .onSuccess { useFront = !useFront }
            .onFailure {
                Toast.makeText(this, "Could not switch camera", Toast.LENGTH_SHORT).show()
                updateShutter()
            }
    }

    private fun updateShutter() {
        shutter.isEnabled =
            !takingPhoto && preview.previewStreamState.value == PreviewView.StreamState.STREAMING
    }

    private fun takePhoto() {
        val camera = controller ?: return
        val framing = guide?.framing()
        val temporary = runCatching {
            File.createTempFile("food-camera-", ".jpg", output.parentFile)
        }
            .getOrElse {
                Toast.makeText(this, "Could not take photo", Toast.LENGTH_SHORT).show()
                return
            }
        takingPhoto = true
        shutter.text = "Taking photo…"
        updateShutter()
        switchCamera.isEnabled = false

        fun captureFailed() {
            temporary.delete()
            if (isFinishing || isDestroyed) return
            takingPhoto = false
            shutter.text = "Take photo"
            updateShutter()
            switchCamera.isEnabled = true
            Toast.makeText(this, "Could not take photo", Toast.LENGTH_SHORT).show()
        }

        runCatching {
            camera.takePicture(
                ImageCapture.OutputFileOptions.Builder(temporary).build(),
                mainExecutor,
                object : ImageCapture.OnImageSavedCallback {
                    override fun onImageSaved(result: ImageCapture.OutputFileResults) {
                        if (isFinishing || isDestroyed || !temporary.renameTo(output)) {
                            captureFailed()
                            return
                        }
                        setResult(RESULT_OK, Intent().putExtra(LABEL_FRAME, framing))
                        finish()
                    }

                    override fun onError(error: ImageCaptureException) = captureFailed()
                },
            )
        }
            .onFailure { captureFailed() }
    }

    private fun fail(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
        finish()
    }
}

private class NutritionLabelGuide(context: Context, private val landscape: Boolean) :
    View(context) {
    private val outline =
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = 2 * resources.displayMetrics.density
            color = Color.WHITE
        }
    private val frame = RectF()

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        val aspect = if (landscape) 3f / 2 else 4f / 3
        val heightLimit = if (landscape) 0.88f else 0.80f
        val width = minOf(w * 0.90f, h * heightLimit * aspect)
        val height = width / aspect
        frame.set((w - width) / 2, (h - height) / 2, (w + width) / 2, (h + height) / 2)
    }

    override fun onDraw(canvas: Canvas) {
        canvas.save()
        canvas.clipOutRect(frame)
        canvas.drawColor(0x80000000.toInt())
        canvas.restore()
        canvas.drawRect(frame, outline)
    }

    fun framing() =
        Bundle().apply {
            putParcelable(
                "fraction",
                RectF(
                    frame.left / width,
                    frame.top / height,
                    frame.right / width,
                    frame.bottom / height,
                ),
            )
            putFloat("previewAspect", width.toFloat() / height)
        }
}
