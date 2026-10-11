package io.github.ozzz.personalagent

import org.junit.Assert.*
import org.junit.Test

class CompletionWaitTest {
    private fun xml(vararg labels: String) = "<hierarchy>" + labels.joinToString("") {
        "<node text=\"$it\" bounds=\"0,0,100,100\" visible=\"true\" enabled=\"true\"/>"
    } + "</hierarchy>"
    @Test fun waitsForAllTaskSuppliedMarkers() {
        var reads = 0
        val done = xml("面板", "全部完成")
        assertEquals(done, CompletionWait.confirm(listOf("面板", "全部完成"),
            { if (reads++ == 0) xml("面板") else done }, {}, {}))
        assertEquals(2, reads)
    }
    @Test fun absentMarkerCannotReturnCompletionEvidence() {
        var reads = 0
        assertTrue(runCatching { CompletionWait.confirm(listOf("面板", "全部完成"),
            { reads++; xml("面板") }, {}, {}) }.isFailure)
        assertEquals(8, reads)
    }
    @Test fun cancellationAfterReadRejectsEvenCompletedPage() {
        var checks = 0
        assertTrue(runCatching { CompletionWait.confirm(listOf("完成"), { xml("完成") }, {},
            { if (checks++ > 0) error("取消") }) }.isFailure)
    }
}
