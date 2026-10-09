package io.github.ozzz.personalagent

import org.junit.Assert.*
import org.junit.Test

class TaobaoQuizTaskTest {
    private fun node(text: String, x: Int = 0, y: Int = 0) =
        UiNode(text, "", "", x, y, x + 100, y + 50, false, true, true)
    private fun question(selected: Boolean = false) = UiSnapshot(listOf(
        node("淘金币趣味答题"), node("1. 猜一猜：成语“白云苍狗”的典故与哪位诗人有关单选题"),
        node("李白", 100, 800), node("杜甫", 100, 1000), node("我选好了", 100, 2100)) +
        if (selected) listOf(node("selected", 900, 1000)) else emptyList())
    private class Runtime : AutomationRuntime {
        var taps = 0
        var backs = 0
        override fun launch(packageName: String) {}
        override fun readUi(packageName: String): String = error("unused")
        override fun tap(packageName: String, x: Int, y: Int) { taps++ }
        override fun back(expectedPackage: String) { backs++ }
        override fun returnHome(expectedPackage: String) {}
        override fun swipe(packageName: String, startX: Int, startY: Int, endX: Int, endY: Int, durationMs: Int) = error("no swipe")
        override fun pause(milliseconds: Long) {}
        override fun log(message: String) {}
    }
    @Test fun selectsAnswerTextEvenWhenOrderChanges() {
        assertEquals(1025, TaobaoQuizTask.answer(question()).y)
    }
    @Test fun waterTouchscreenQuestionSelectsConductivityByText() {
        val question = UiSnapshot(listOf(node("淘金币趣味答题"),
            node("1. 为什么手机屏幕沾上水会影响触控效果单选题"),
            node("水能溶解屏幕", 100, 800), node("水能导电", 100, 1000)))
        assertEquals("水能导电", TaobaoQuizTask.answer(question).text)
    }
    @Test fun moneyNicknameSelectsKongfangByText() {
        val page = UiSnapshot(listOf(node("淘金币趣味答题"),
            node("1. 猜一猜：下列哪个词是古人对钱币的别称单选题"),
            node("不夜侯", 100, 800), node("孔方兄", 100, 1000)))
        assertEquals("孔方兄", TaobaoQuizTask.answer(page).text)
    }
    @Test fun unknownQuestionNeverClicks() {
        val runtime = Runtime()
        assertTrue(runCatching { TaobaoQuizTask.run(runtime) { UiSnapshot(listOf(node("淘金币趣味答题"), node("未知题目"))) } }.isFailure)
        assertEquals(0, runtime.taps)
    }
    @Test fun unselectedAnswerDoesNotSubmit() {
        val runtime = Runtime()
        assertTrue(runCatching { TaobaoQuizTask.run(runtime) { question() } }.isFailure)
        assertEquals(1, runtime.taps)
    }
    @Test fun submitsOnceAndVerifiesRewardOnPanel() {
        val runtime = Runtime()
        val panel = UiSnapshot(listOf(node("今日速赚"), node("淘金币趣味课堂参与答题")))
        val pages = java.util.ArrayDeque(listOf(UiSnapshot(emptyList()), question(), question(true),
            UiSnapshot(listOf(node("已成功领取奖励 回到主页"))), panel))
        assertEquals(panel, TaobaoQuizTask.run(runtime) { pages.removeFirst() })
        assertEquals(2, runtime.taps)
        assertEquals(1, runtime.backs)
    }
    @Test fun unchangedQuestionDoesNotSubmitTwice() {
        val runtime = Runtime()
        assertTrue(runCatching { TaobaoQuizTask.run(runtime) { question(true) } }.isFailure)
        assertEquals(2, runtime.taps)
        assertEquals(0, runtime.backs)
    }
}
