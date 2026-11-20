package lofit

import android.Manifest
import android.app.Instrumentation

internal fun Instrumentation.grantWorkoutStartPermissions() {
    val app = targetContext.packageName
    uiAutomation.grantRuntimePermission(app, Manifest.permission.ACCESS_COARSE_LOCATION)
    uiAutomation.grantRuntimePermission(app, Manifest.permission.ACCESS_FINE_LOCATION)
    uiAutomation.grantRuntimePermission(app, Manifest.permission.POST_NOTIFICATIONS)
}
