package lofit

import org.junit.Assert.*
import org.junit.Test

class SearchTest {
    @Test
    fun wordsMatchAcrossFieldsWithoutCaseOrWhitespaceSensitivity() {
        val query = SearchQuery("  BENCH\tchest  ")
        assertTrue(query.matches("Bench press", "Upper chest"))
        assertFalse(query.matches("Bench press", "Triceps"))
        assertTrue(SearchQuery(" \n ").matches("Anything"))
        assertTrue(SearchQuery("live weighted").matches("Timed", "Weighted Live measurement"))
    }
}
