package io.github.ozzz.personalagent

import org.junit.Assert.*
import org.junit.Test

class TaobaoQuickTaskTest {
    @Test fun overlappingRewardLayersStillConfirmSameThirty() {
        val page = UiSnapshot(listOf(
            UiNode("已得", "", "", 919, 1386, 981, 1428, false, true, true),
            UiNode("30", "", "", 975, 1386, 1015, 1423, false, true, true),
            UiNode("30", "", "", 981, 1414, 1023, 1451, false, true, true)))
        assertTrue(TaobaoQuickTask.browseRewardEarned(page))
    }
    @Test fun recognizesObservedVideoPageWithoutPurchaseButtons() {
        val root = UiNode("", "", "", 0, 0, 1080, 2400, false, true, true)
        val content = root.copy(description = "图片，按钮。双击可进入详情页。", bottom = 2191)
        val page = UiSnapshot(listOf(root, content,
            UiNode("浏览15秒", "", "", 871, 1380, 1063, 1420, false, true, true),
            UiNode("30", "", "", 967, 1414, 1009, 1451, false, true, true)))
        assertEquals(root, TaobaoQuickTask.browseViewport(page))
    }
    private fun pending(): UiSnapshot = UiSnapshot(listOf(
        UiNode("", "", "", 0, 0, 1080, 2400, false, true, true),
        node("加入购物车", 300, 2100), node("立即购买", 700, 2100),
        node("浏览15秒", 870, 1300), node("30", 930, 1360)))
    private fun done() = UiSnapshot(listOf(node("已得30", 870, 1300)))
    private class Runtime : AutomationRuntime {
        var swipes = 0
        var homes = 0
        var failSwipe = false
        var failHome = false
        val pages = java.util.ArrayDeque<String>()
        override fun launch(packageName: String) {}
        override fun tap(packageName: String, x: Int, y: Int) {}
        override fun readUi(packageName: String) = pages.removeFirst()
        override fun pause(milliseconds: Long) {}
        override fun log(message: String) {}
        override fun back(expectedPackage: String) {}
        override fun returnHome(expectedPackage: String) { check(!failHome) { "前台切换" }; homes++ }
        override fun swipe(packageName: String, startX: Int, startY: Int, endX: Int, endY: Int, durationMs: Int) {
            check(!failSwipe) { "任务取消或前台切换" }
            assertEquals("com.taobao.taobao", packageName)
            assertEquals(650, durationMs)
            assertTrue(startY > endY && startY < 1800 && endY > 300)
            swipes++
        }
    }
    @Test fun swipesUntilConfirmedThenImmediatelyStops() {
        val runtime = Runtime()
        val pages = java.util.ArrayDeque(listOf(pending(), pending(), done()))
        assertTrue(TaobaoQuickTask.browse(runtime) { pages.removeFirst() }.contains("已得30"))
        assertEquals(2, runtime.swipes)
    }
    @Test fun alreadyEarnedDoesNotSwipe() {
        val runtime = Runtime()
        TaobaoQuickTask.browse(runtime) { done() }
        assertEquals(0, runtime.swipes)
    }
    @Test fun stopsAtLimitWithoutInventingSuccess() {
        val runtime = Runtime()
        assertTrue(runCatching { TaobaoQuickTask.browse(runtime) { pending() } }.isFailure)
        assertEquals(10, runtime.swipes)
        assertEquals(0, runtime.homes)
    }
    @Test fun unknownPageAndFailedGestureStopImmediately() {
        val runtime = Runtime()
        assertTrue(runCatching { TaobaoQuickTask.browse(runtime) { UiSnapshot(emptyList()) } }.isFailure)
        assertEquals(0, runtime.swipes)
        runtime.failSwipe = true
        assertTrue(runCatching { TaobaoQuickTask.browse(runtime) { pending() } }.isFailure)
        assertEquals(0, runtime.swipes)
    }
    private fun xml(page: UiSnapshot) = "<hierarchy>" + page.nodes.joinToString("") {
        "<node text=\"${it.text}\" bounds=\"${it.left},${it.top},${it.right},${it.bottom}\" enabled=\"true\" visible=\"true\"/>"
    } + "</hierarchy>"
    private fun readyRuntime() = Runtime().apply {
        val signed = UiSnapshot(listOf(node("淘金币标题", 0, 0), node("今天", 100, 200),
            node("赚更多金币", 200, 300), node("40秒快速赚", 200, 200)))
        pages.add(xml(signed)); pages.add(xml(signed))
        pages.add(xml(UiSnapshot(listOf(node("今日速赚", 0, 0), node("好物沉浸看", 100, 300), node("+30", 500, 300)))))
        pages.add(xml(pending())); pages.add(xml(done()))
        pages.add(xml(UiSnapshot(listOf(node("今日速赚", 0, 0)))))
    }
    @Test fun completeTaskSwipesAndReturnsHome() {
        val runtime = readyRuntime()
        assertTrue(TaobaoQuickTask.runAndReturnHome(runtime).contains("返回桌面"))
        assertEquals(1, runtime.swipes)
        assertEquals(1, runtime.homes)
    }
    @Test fun waitsForEntryAfterSignInAnimation() {
        val runtime = readyRuntime()
        val first = runtime.pages.removeFirst()
        val entry = runtime.pages.removeFirst()
        runtime.pages.addFirst(entry)
        runtime.pages.addFirst(xml(UiSnapshot(listOf(node("淘金币标题", 0, 0), node("今天", 100, 200), node("赚更多金币", 200, 300)))))
        runtime.pages.addFirst(first)
        assertTrue(TaobaoQuickTask.runAndReturnHome(runtime).contains("已得30"))
        assertEquals(1, runtime.homes)
    }
    @Test fun recoversCompletedListChecksPanelThenReturnsHome() {
        val runtime = Runtime()
        runtime.pages.add(xml(UiSnapshot(listOf(
            UiNode("淘宝购物清单", "", "", 0, 0, 1080, 2358, false, true, true),
            node("已得", 112, 157), node("60", 258, 157), node("当前页下单另得500", 483, 160)))))
        runtime.pages.add(xml(UiSnapshot(listOf(node("今日速赚", 45, 286), node("逛清单，每15秒30金币(2/2)", 196, 666)))))
        assertTrue(TaobaoQuickTask.runAndReturnHome(runtime).contains("2/2确认"))
        assertEquals(0, runtime.swipes)
        assertEquals(1, runtime.homes)
    }
    @Test fun renamedCompletedEntryStillOpensPanelAndExits() {
        val runtime = readyRuntime()
        val first = runtime.pages.removeFirst()
        val second = runtime.pages.removeFirst().replace("40秒快速赚", "快速赚")
        runtime.pages.clear()
        runtime.pages.add(first)
        runtime.pages.add(second)
        runtime.pages.add(xml(UiSnapshot(listOf(node("今日速赚", 0, 0), node("今日快速赚奖励已拿完", 0, 100)))))
        assertTrue(TaobaoQuickTask.runAndReturnHome(runtime).contains("已拿完"))
        assertEquals(0, runtime.swipes)
        assertEquals(1, runtime.homes)
    }
    @Test fun homeFailurePreservesEarnedResult() {
        val runtime = readyRuntime().apply { failHome = true }
        val error = runCatching { TaobaoQuickTask.runAndReturnHome(runtime) }.exceptionOrNull()
        assertTrue(error?.message.orEmpty().contains("已得30"))
        assertTrue(error?.message.orEmpty().contains("返回桌面失败"))
    }

    @Test fun recognizesActualSplitEarnedBadgeWithoutExtraCompletionText() {
        val label = UiNode("已得", "", "", 919, 1386, 981, 1428, false, true, true)
        val amount = UiNode("30", "", "", 975, 1386, 1015, 1423, false, true, true)
        assertTrue(TaobaoQuickTask.browseRewardEarned(UiSnapshot(listOf(label, amount))))
        assertFalse(TaobaoQuickTask.browseRewardEarned(UiSnapshot(listOf(label, amount.copy(visible = false)))))
        assertFalse(TaobaoQuickTask.browseRewardEarned(UiSnapshot(listOf(label, amount.copy(top = 1500, bottom = 1540)))))
        assertFalse(TaobaoQuickTask.browseRewardEarned(UiSnapshot(listOf(label, amount.copy(left = 100, right = 140)))))
    }

    @Test fun purchaseOfferAndUnrelatedAmountsAreNotEarnedReward() {
        assertFalse(TaobaoQuickTask.browseRewardEarned(UiSnapshot(listOf(node("下单再得", 800, 1300), node("500", 920, 1300)))))
        assertFalse(TaobaoQuickTask.browseRewardEarned(UiSnapshot(listOf(node("未得30", 800, 1300)))))
        assertFalse(TaobaoQuickTask.browseRewardEarned(UiSnapshot(listOf(node("浏览15秒", 800, 1300), node("30", 920, 1300)))))
        assertTrue(TaobaoQuickTask.browseRewardEarned(UiSnapshot(listOf(node("已得30", 800, 1300)))))
    }

    private fun node(text: String, x: Int, y: Int) = UiNode(text, "", "", x, y, x + 100, y + 50, false, true, true)
    @Test fun rewardMustBelongToNamedRow() {
        val target = node("+30", 500, 100)
        val page = UiSnapshot(listOf(node("好物沉浸看", 100, 100), target, node("+30", 500, 300)))
        assertEquals(target, TaobaoQuickTask.reward(page, "好物沉浸看", "+30"))
        assertNull(TaobaoQuickTask.reward(page, "未知任务", "+30"))
    }
    @Test fun renamedVideoUsesBrowseSubtitleAndItsOwnRewardRow() {
        val title = node("看看#卫生抽纸", 196, 675)
        val amount = node("+30", 883, 669)
        val subtitle = node("浏览15秒", 196, 739)
        assertEquals(amount, TaobaoQuickTask.videoReward(UiSnapshot(listOf(title, amount, subtitle))))
        assertNull(TaobaoQuickTask.videoReward(UiSnapshot(listOf(title, amount))))
        assertNull(TaobaoQuickTask.videoReward(UiSnapshot(listOf(title, amount.copy(top = 880, bottom = 930), subtitle))))
    }
    @Test fun ambiguousRewardsAreRejected() {
        val page = UiSnapshot(listOf(node("好物沉浸看", 100, 100), node("+30", 500, 100), node("+30", 650, 100)))
        assertNull(TaobaoQuickTask.reward(page, "好物沉浸看", "+30"))
    }
}
