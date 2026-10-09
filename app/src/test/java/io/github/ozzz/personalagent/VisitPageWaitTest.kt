package io.github.ozzz.personalagent

import org.junit.Assert.*
import org.junit.Test

class VisitPageWaitTest {
    private fun page(title: String = "") = UiSnapshot(listOf(
        UiNode(title, "", "", 0, 0, 100, 100, false, true, true)))
    @Test fun loadingTreeIsRetriedUntilTitleAppears() {
        var reads = 0
        var pauses = 0
        VisitPageWait.awaitTitle("蚂蚁庄园", { if (reads++ < 2) page() else page("蚂蚁庄园") },
            { assertEquals(750L, it); pauses++ }, {}, {})
        assertEquals(3, reads)
        assertEquals(2, pauses)
    }
    @Test fun unknownTitleHasBoundedReads() {
        var reads = 0
        var pauses = 0
        assertTrue(runCatching { VisitPageWait.awaitTitle("蚂蚁庄园", { reads++; page("未知页面") },
            { pauses++ }, {}, {}) }.isFailure)
        assertEquals(8, reads)
        assertEquals(7, pauses)
    }
    @Test fun foregroundChangeDoesNotRetryOrReturn() {
        var reads = 0
        assertTrue(runCatching { VisitPageWait.awaitTitle("蚂蚁庄园", { reads++; page() }, {},
            { error("用户切到其他App") }, {}) }.isFailure)
        assertEquals(0, reads)
    }
    @Test fun blockedReadCannotBeFollowedBySuccessAfterDeadline() {
        var now = 1000L
        val deadline = RemoteDeadline { now }
        deadline.arm(61_000)
        deadline.beginOperation(20_000)
        assertTrue(runCatching { VisitPageWait.awaitTitle("蚂蚁庄园", {
            now = 21_000
            page("蚂蚁庄园")
        }, {}, deadline::check, {}) }.isFailure)
    }
}
