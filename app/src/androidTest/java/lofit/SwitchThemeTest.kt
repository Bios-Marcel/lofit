package lofit

import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.view.ContextThemeWrapper
import android.widget.Switch
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SwitchThemeTest {
    @Test
    fun plainSwitchInheritsOpaqueTrackAndCreamThumbInDayAndNight() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.runOnMainSync {
            val base = instrumentation.targetContext
            for (nightMode in
                listOf(Configuration.UI_MODE_NIGHT_NO, Configuration.UI_MODE_NIGHT_YES)) {
                val configuration = Configuration().apply { uiMode = nightMode }
                val context =
                    ContextThemeWrapper(
                        base.createConfigurationContext(configuration),
                        R.style.Theme_Lofit,
                    )
                assertEquals(
                    nightMode,
                    context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK,
                )
                val switch = Switch(context)
                assertEquals(context.getColor(R.color.cream), switch.thumbTintList?.defaultColor)
                val track = switch.trackDrawable
                val bitmap = Bitmap.createBitmap(96, track.intrinsicHeight, Bitmap.Config.ARGB_8888)
                track.setBounds(0, 0, bitmap.width, bitmap.height)
                val canvas = Canvas(bitmap)
                for ((checked, color) in listOf(false to R.color.sage, true to R.color.coral)) {
                    switch.isChecked = checked
                    bitmap.eraseColor(Color.TRANSPARENT)
                    track.draw(canvas)
                    assertEquals(
                        "Track color for night mode $nightMode and checked=$checked",
                        context.getColor(color),
                        bitmap.getPixel(bitmap.width / 2, bitmap.height / 2),
                    )
                }
                bitmap.recycle()
            }
        }
    }
}
