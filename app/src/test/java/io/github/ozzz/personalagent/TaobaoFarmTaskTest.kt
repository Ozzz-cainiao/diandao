package io.github.ozzz.personalagent

import org.junit.Assert.*
import org.junit.Test

class TaobaoFarmTaskTest {
    private fun page(vararg labels: String) = UiSnapshot(labels.map {
        UiNode(it, "", "", 0, 0, 100, 100, false, true, true)
    })
    private class Runtime(val result: UiSnapshot, var label: String = "蚂蚁庄园") : AutomationRuntime {
        var visits = 0
        var cancelled = false
        override fun visitAndFinish(packageName: String, x: Int, y: Int, visitPackage: String,
                                    title: String, markers: List<String>): UiSnapshot {
            visits++
            check(!cancelled) { "任务已取消" }
            check(label == title) { "访问标题不符" }
            assertEquals(listOf("今日速赚", "今日快速赚奖励已拿完"), markers)
            return result
        }
        override fun launch(packageName: String) = error("return performed remotely")
        override fun readUi(packageName: String): String = error("no client polling after visit")
        override fun tap(packageName: String, x: Int, y: Int) = error("no taps in Alipay")
        override fun back(expectedPackage: String) = error("no back in Alipay")
        override fun returnHome(expectedPackage: String) {}
        override fun swipe(packageName: String, startX: Int, startY: Int, endX: Int, endY: Int, durationMs: Int) = error("no swipe")
        override fun pause(milliseconds: Long) = error("no client waits after visit")
        override fun log(message: String) {}
    }
    @Test fun remoteCompletionNeedsNoClientPolling() {
        val done = page("今日速赚", "今日快速赚奖励已拿完")
        val runtime = Runtime(done)
        assertEquals(done, TaobaoFarmTask.run(runtime, page("入口").nodes[0]))
        assertEquals(1, runtime.visits)
    }
    @Test fun unknownAlipayPageStops() {
        val runtime = Runtime(page(), "支付宝")
        assertTrue(runCatching { TaobaoFarmTask.run(runtime, page("入口").nodes[0]) }.isFailure)
    }
    @Test fun cancellationIsNotRetried() {
        val runtime = Runtime(page()).apply { cancelled = true }
        assertTrue(runCatching { TaobaoFarmTask.run(runtime, page("入口").nodes[0]) }.isFailure)
        assertEquals(1, runtime.visits)
    }
    @Test fun visitAloneDoesNotClaimRewardSuccess() {
        assertTrue(runCatching { TaobaoFarmTask.run(Runtime(page("今日速赚")), page("入口").nodes[0]) }.isFailure)
    }
}
