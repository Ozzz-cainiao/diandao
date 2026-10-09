package io.github.ozzz.personalagent

import org.junit.Assert.*
import org.junit.Test

class RemoteDeadlineTest {
    @Test fun expiresWithoutClientHeartbeat() {
        var now = 1000L
        val deadline = RemoteDeadline { now }
        deadline.arm(61_000)
        now = 60_999
        assertFalse(deadline.expired())
        now = 61_000
        assertTrue(deadline.expired())
        assertTrue(runCatching { deadline.check() }.isFailure)
        assertTrue(runCatching { deadline.arm(121_000) }.isFailure)
    }
    @Test fun pageRenewalCannotExtendSuppliedOverallDeadline() {
        var now = 1000L
        val deadline = RemoteDeadline { now }
        val overall = 121_000L
        deadline.arm(minOf(overall, now + 60_000))
        now = 50_000
        deadline.arm(minOf(overall, now + 60_000))
        now = 100_000
        deadline.arm(minOf(overall, now + 60_000))
        now = overall
        assertTrue(deadline.expired())
    }
    @Test fun blockingOperationHasShorterLimit() {
        var now = 1000L
        val deadline = RemoteDeadline { now }
        deadline.arm(61_000)
        deadline.beginOperation(20_000)
        now = 21_000
        assertTrue(deadline.expired())
        assertTrue(runCatching { deadline.check() }.isFailure)
    }
    @Test fun expiredOperationCannotBeRevivedByFinally() {
        var now = 1000L
        val deadline = RemoteDeadline { now }
        deadline.arm(61_000)
        deadline.beginOperation(20_000)
        now = 21_000
        deadline.endOperation()
        assertTrue(deadline.expired())
        assertTrue(runCatching { deadline.arm(61_000) }.isFailure)
    }
    @Test fun completedOperationKeepsTaskDeadline() {
        var now = 1000L
        val deadline = RemoteDeadline { now }
        deadline.arm(61_000)
        deadline.beginOperation(20_000)
        now = 5000
        deadline.endOperation()
        now = 21_000
        assertFalse(deadline.expired())
        now = 61_000
        assertTrue(deadline.expired())
    }
}
