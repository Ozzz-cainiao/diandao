package io.github.ozzz.personalagent

import org.junit.Assert.*
import org.junit.Test

class TaobaoFarmTaskTest {
    private fun page(vararg labels: String) = UiSnapshot(labels.map {
        UiNode(it, "", "", 0, 0, 100, 100, false, true, true)
    })
    private class Runtime(var label: String = "蚂蚁庄园") : AutomationRuntime {
        var reads = 0
        var launches = 0
        var cancelled = false
        override fun launch(packageName: String) { assertEquals("com.taobao.taobao", packageName); launches++ }
        override fun readUi(packageName: String): String = error("跨App访问由远端完成，不在App中轮询")
        override fun visitAndReturn(packageName: String, x: Int, y: Int, visitPackage: String, title: String) {
            reads++
            if (cancelled) error("任务已取消")
            check(label == title) { "访问标题不符" }
            launches++
        }
        override fun tap(packageName: String, x: Int, y: Int) = error("no taps in Alipay")
        override fun back(expectedPackage: String) = error("no back in Alipay")
        override fun returnHome(expectedPackage: String) {}
        override fun swipe(packageName: String, startX: Int, startY: Int, endX: Int, endY: Int, durationMs: Int) = error("no swipe")
        override fun pause(milliseconds: Long) {}
        override fun log(message: String) {}
    }
    @Test fun visitThenConfirmAllDoneWithoutAlipayActions() {
        val runtime = Runtime()
        var reads = 0
        val done = page("今日速赚", "今日快速赚奖励已拿完")
        assertEquals(done, TaobaoFarmTask.run(runtime, page("入口").nodes[0]) { if (reads++ == 0) page("今日速赚") else done })
        assertEquals(1, runtime.launches)
    }
    @Test fun unknownAlipayPageStops() {
        val runtime = Runtime("支付宝")
        assertTrue(runCatching { TaobaoFarmTask.run(runtime, page("入口").nodes[0]) { page() } }.isFailure)
        assertEquals(0, runtime.launches)
    }
    @Test fun cancellationIsNotRetried() {
        val runtime = Runtime().apply { cancelled = true }
        assertTrue(runCatching { TaobaoFarmTask.run(runtime, page("入口").nodes[0]) { page() } }.isFailure)
        assertEquals(1, runtime.reads)
    }
    @Test fun visitAloneDoesNotClaimRewardSuccess() {
        assertTrue(runCatching { TaobaoFarmTask.run(Runtime(), page("入口").nodes[0]) { page("今日速赚") } }.isFailure)
        assertFalse(TaobaoQuickTask.allDone(page("今日快速赚奖励已拿完")))
    }
}
