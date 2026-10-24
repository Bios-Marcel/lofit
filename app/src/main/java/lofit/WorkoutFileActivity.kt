package lofit

import android.app.Activity
import android.content.Intent
import android.os.Bundle

/** Route opened files into the existing workout screen without creating a second app session. */
class WorkoutFileActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        startActivity(Intent(this, MainActivity::class.java).apply {
            action = Intent.ACTION_VIEW
            setDataAndType(intent.data, intent.type)
            clipData = intent.clipData
            addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP or
                Intent.FLAG_GRANT_READ_URI_PERMISSION)
        })
        finish()
    }
}
