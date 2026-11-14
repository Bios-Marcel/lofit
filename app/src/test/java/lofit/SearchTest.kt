package lofit

import org.junit.Assert.*
import org.junit.Test

class SearchTest {
    @Test
    fun searchQueries() {
        val query = SearchQuery("  BENCH\tchest  ")
        assertTrue(query.matches("Bench press", "Upper chest"))
        assertTrue(SearchQuery(" \n ").matches("Anything"))
        assertTrue(SearchQuery("live weighted").matches("Timed", "Weighted Live measurement"))
        assertTrue(matchesSearchQuery("CURL dumb", "Dumbbell Bicep Curl", "Biceps"))
        assertTrue(matchesSearchQuery("dumb fore", "Dumbbell Bicep Curl", "Forearms"))
        assertTrue(matchesSearchQuery("  dumb   FORE  ", "Dumbbell Bicep Curl", "Forearms"))
        assertTrue(matchesSearchQuery("", "Dumbbell Bicep Curl", "Biceps"))

        assertFalse(query.matches("Bench press", "Triceps"))
        assertFalse(matchesSearchQuery("dumb tri", "Dumbbell Bicep Curl", "Biceps"))
    }
}
