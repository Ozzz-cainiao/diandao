package io.github.ozzz.personalagent

import org.junit.Assert.*
import org.junit.Test

class TaobaoCoinTaskTest {
    private fun page(vararg labels: String) = "<hierarchy>" + labels.joinToString("") {
        "<node text=\"$it\" bounds=\"0,0,100,100\" enabled=\"true\" visible=\"true\"/>"
    } + "</hierarchy>"

    private class Runtime(val pages: List<String>) : AutomationRuntime {
        var index = 0
        var taps = 0
        var homes = 0
        override fun back(expectedPackage: String) {}
        override fun returnHome(expectedPackage: String) { homes++ }
        override fun launch(packageName: String) {}
        override fun readUi(packageName: String) = pages[minOf(index++, pages.lastIndex)]
        override fun tap(packageName: String, x: Int, y: Int) { taps++ }
        override fun swipe(packageName: String, startX: Int, startY: Int, endX: Int, endY: Int, durationMs: Int) {
            error("每日签到不应调用滑动")
        }
        override fun pause(milliseconds: Long) {}
        override fun log(message: String) {}
    }

    @Test fun returnsHomeOnlyAfterConfirmedSuccess() {
        val good = Runtime(listOf(page("淘金币标题", "签到领金币"), page("淘金币标题", "赚更多金币", "今天")))
        TaobaoCoinTask.runAndReturnHome(good)
        assertEquals(1, good.homes)
        val already = Runtime(listOf(page("淘金币标题", "赚更多金币", "今天")))
        TaobaoCoinTask.runAndReturnHome(already)
        assertEquals(1, already.homes)
        val bad = Runtime(listOf(page("淘金币标题", "签到领金币")))
        assertTrue(runCatching { TaobaoCoinTask.runAndReturnHome(bad) }.isFailure)
        assertEquals(0, bad.homes)
    }

    @Test fun hiddenClaimedTextDoesNotSkipClaim() {
        val runtime = Runtime(listOf(page("淘金币标题", "签到领金币", "已领取"),
            page("淘金币标题", "赚更多金币", "今天")))
        assertTrue(TaobaoCoinTask.run(runtime).contains("签到按钮已消失"))
        assertEquals(1, runtime.taps)
    }

    @Test fun alreadyClaimedDoesNotTap() {
        val runtime = Runtime(listOf(page("淘金币标题", "赚更多金币", "今天")))
        assertTrue(TaobaoCoinTask.run(runtime).contains("无需重复"))
        assertEquals(0, runtime.taps)
    }

    @Test fun unchangedPageStopsAfterOneClaimAttempt() {
        val runtime = Runtime(listOf(page("淘金币标题", "签到领金币", "已领取")))
        assertTrue(runCatching { TaobaoCoinTask.run(runtime) }.isFailure)
        assertEquals(1, runtime.taps)
    }

    @Test fun homeEntryThenNewClaimReturnsHome() {
        val runtime = Runtime(listOf(page("领淘金币"), page("淘金币标题", "签到领金币"),
            page("淘金币标题", "赚更多金币", "今天")))
        TaobaoCoinTask.runAndReturnHome(runtime)
        assertEquals(2, runtime.taps)
        assertEquals(1, runtime.homes)
    }

    @Test fun obscuredResultDoesNotReturnHomeOrRetryClaim() {
        val runtime = Runtime(listOf(page("淘金币标题", "签到领金币"),
            page("淘金币标题", "今天", "活动推广")))
        assertTrue(runCatching { TaobaoCoinTask.runAndReturnHome(runtime) }.isFailure)
        assertEquals(1, runtime.taps)
        assertEquals(0, runtime.homes)
    }

    @Test fun unknownPageDoesNotClickOrReturnHome() {
        val runtime = Runtime(listOf(page("签到领金币", "今天", "赚更多金币")))
        assertTrue(runCatching { TaobaoCoinTask.runAndReturnHome(runtime) }.isFailure)
        assertEquals(0, runtime.taps)
        assertEquals(0, runtime.homes)
    }

    @Test fun slowCoinPageLoadsWithoutRepeatingEntryTap() {
        val runtime = Runtime(listOf(page("领淘金币")) + List(6) { page("加载中") } +
            listOf(page("淘金币标题", "签到领金币"), page("淘金币标题", "赚更多金币", "今天")))
        TaobaoCoinTask.runAndReturnHome(runtime)
        assertEquals(2, runtime.taps) // One entry tap and one sign-in tap.
        assertEquals(1, runtime.homes)
    }

    @Test fun stalledCoinPageStopsWithoutRepeatingEntryTap() {
        val runtime = Runtime(listOf(page("领淘金币"), page("加载中")))
        assertTrue(runCatching { TaobaoCoinTask.runAndReturnHome(runtime) }.isFailure)
        assertEquals(1, runtime.taps)
        assertEquals(0, runtime.homes)
        assertTrue(runtime.index <= 10)
    }
}
