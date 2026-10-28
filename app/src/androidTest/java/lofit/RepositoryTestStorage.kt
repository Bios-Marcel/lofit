package lofit

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import android.util.AtomicFile
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import org.junit.Assert.assertTrue

internal val Context.repositoryStateFile: File
    get() = File(filesDir, "lofit-data.json")

internal val Context.repositoryHistoryFile: File
    get() = File(filesDir, "lofit-history.json")

internal fun Context.clearRepositoryTestData() {
    assertTrue(getSharedPreferences("lofit-data", 0).edit().clear().commit())
    AtomicFile(repositoryStateFile).delete()
    AtomicFile(repositoryHistoryFile).delete()
    AtomicFile(File(filesDir, "state-before-migration-v3.json")).delete()
}

// Storage tests must not clear the installed app's workouts or migration backups.
internal class RepositoryTestContext(private val suite: String) :
    ContextWrapper(InstrumentationRegistry.getInstrumentation().targetContext) {
    override fun getFilesDir(): File =
        File(baseContext.cacheDir, "repository-tests/$suite").apply { mkdirs() }

    override fun getSharedPreferences(name: String, mode: Int): SharedPreferences =
        super.getSharedPreferences("repository-test-$suite-$name", mode)

    val legacyXmlFile: File
        get() =
            File(
                baseContext.applicationInfo.dataDir,
                "shared_prefs/repository-test-$suite-lofit-data.xml",
            )
}
