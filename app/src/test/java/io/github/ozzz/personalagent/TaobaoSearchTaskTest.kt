package io.github.ozzz.personalagent

import org.junit.Assert.*
import org.junit.Test

class TaobaoSearchTaskTest {
    private fun node(text: String, top: Int = 0, desc: String = "", clickable: Boolean = false) =
        UiNode(text, desc, "", 0, top, 1080, top + 100, clickable, true, true)
    private fun page(vararg nodes: UiNode) = UiSnapshot(nodes.toList())
    private val entry = page(node("搜索有福利"), node("搜索发现", 700),
        node("", 800, "无线耳机 近一个月点击超1千", true))
    private val root = node("金币-固搜-interact").copy(top = 127, bottom = 2358)
    private val pending = page(root, node("浏览"), node("秒可领"))
    private val done = page(node("今日速赚"), node("今日快速赚奖励已拿完"))
    private class Runtime : AutomationRuntime {
        var swipes = 0
        var taps = 0
        var backs = 0
        override fun launch(packageName: String) {}
        override fun readUi(packageName: String) = error("unused")
        override fun tap(packageName: String, x: Int, y: Int) { taps++ }
        override fun back(expectedPackage: String) { backs++ }
        override fun returnHome(expectedPackage: String) {}
        override fun pause(milliseconds: Long) {}
        override fun log(message: String) {}
        override fun swipe(packageName: String, startX: Int, startY: Int, endX: Int, endY: Int, durationMs: Int) {
            assertTrue(startY > endY && endY > 700)
            swipes++
        }
    }
    @Test fun verifiesPanelAfterTimerDisappears() {
        val runtime = Runtime()
        val pages = java.util.ArrayDeque(listOf(entry, pending, page(root), done))
        assertEquals(done, TaobaoSearchTask.run(runtime) { pages.removeFirst() })
        assertEquals(1, runtime.swipes)
        assertEquals(1, runtime.taps)
    }
    @Test fun timerDisappearanceIsNotSuccessIfRewardStillPending() {
        val runtime = Runtime()
        val panel = page(node("今日速赚"), node("搜一搜你心仪的宝贝").copy(right = 600),
            node("+30").copy(left = 850))
        val pages = java.util.ArrayDeque(listOf(entry, pending, page(root), panel))
        assertTrue(runCatching { TaobaoSearchTask.run(runtime) { pages.removeFirst() } }.isFailure)
    }
    @Test fun unknownPageStopsBeforeSwipe() {
        val runtime = Runtime()
        val pages = java.util.ArrayDeque(listOf(entry, page(node("商品详情"))))
        assertTrue(runCatching { TaobaoSearchTask.run(runtime) { pages.removeFirst() } }.isFailure)
        assertEquals(0, runtime.swipes)
    }
    @Test fun permanentlyPendingTimerIsBounded() {
        val runtime = Runtime()
        var reads = 0
        assertTrue(runCatching { TaobaoSearchTask.run(runtime) { if (reads++ == 0) entry else pending } }.isFailure)
        assertEquals(10, runtime.swipes)
    }
}
