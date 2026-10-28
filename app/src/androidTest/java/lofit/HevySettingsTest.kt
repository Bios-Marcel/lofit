package lofit

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.util.UUID
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class HevySettingsTest {
    @Test
    fun rotatedTokenPairIsSavedTogether() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val name = "hevy-test-${UUID.randomUUID()}"
        try {
            val settings = HevySettings(context, name)
            settings.saveTokenPair("Bearer first-access", "first-refresh")
            assertEquals("first-access", settings.accessToken)
            assertEquals("first-refresh", settings.refreshToken)

            settings.saveTokenPair("second-access", "second-refresh")
            val reloaded = HevySettings(context, name)
            assertEquals("second-access", reloaded.accessToken)
            assertEquals("second-refresh", reloaded.refreshToken)
        } finally {
            context.getSharedPreferences(name, 0).edit().clear().commit()
        }
    }

    @Test
    fun unauthorizedRequestRefreshesAndRetriesWithNewPair() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val name = "hevy-test-${UUID.randomUUID()}"
        try {
            val settings = HevySettings(context, name)
            settings.saveTokenPair("old-access", "old-refresh")
            val usedTokens = mutableListOf<String>()
            val session =
                HevySession(settings) { method, path, body, key, bearer, refresh ->
                    assertEquals(null, key)
                    if (refresh) {
                        assertEquals("POST", method)
                        assertEquals("/auth/refresh_token", path)
                        assertEquals("old-access", bearer)
                        assertEquals("old-refresh", body!!.getString("refresh_token"))
                        HevyResponse(
                            200,
                            """{"access_token":"new-access","refresh_token":"new-refresh"}""",
                        )
                    } else {
                        assertEquals("GET", method)
                        assertEquals("/custom_exercise_templates", path)
                        usedTokens.add(bearer.orEmpty())
                        HevyResponse(if (bearer == "old-access") 401 else 200, "{}")
                    }
                }

            assertEquals(
                200,
                session.request("GET", "/custom_exercise_templates", null, null, true).status,
            )
            assertEquals(listOf("old-access", "new-access"), usedTokens)
            assertEquals("new-access", HevySettings(context, name).accessToken)
            assertEquals("new-refresh", HevySettings(context, name).refreshToken)
            assertFalse(settings.refreshToken == "old-refresh")
        } finally {
            context.getSharedPreferences(name, 0).edit().clear().commit()
        }
    }
}
