package io.github.chuck852.linksonda.updater

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class InstallSessionTrackerTest {
    @Test
    fun onlyOneSessionCanBeActiveAndStaleFinishIsIgnored() {
        val tracker = InstallSessionTracker()

        assertTrue(tracker.begin(10))
        assertFalse(tracker.begin(11))
        assertTrue(tracker.isCurrent(10))
        assertEquals(10, tracker.current())

        tracker.finish(11)
        assertTrue(tracker.isCurrent(10))

        tracker.finish(10)
        assertEquals(null, tracker.current())
        assertTrue(tracker.begin(12))
    }
}
