package lofit

import android.os.Bundle
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity

class HealthPrivacyActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        title = "Health data privacy"
        setContentView(ScrollView(this).apply {
            addView(TextView(this@HealthPrivacyActivity).apply {
                text = getString(R.string.health_privacy)
                textSize = 18f
                val padding = (24 * resources.displayMetrics.density).toInt()
                setPadding(padding, padding, padding, padding)
            })
        })
    }
}
