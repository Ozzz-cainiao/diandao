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
    @Test fun sikongQuestionSelectsOfficialTitleByText() {
        val page = UiSnapshot(listOf(node("淘金币趣味答题"),
            node("1. 成语“司空见惯”中的“司空”在古代指的是单选题"),
            node("地名", 100, 800), node("官职", 100, 1000)))
        assertEquals("官职", TaobaoQuizTask.answer(page).text)
    }
    @Test fun unknownQuestionNeverClicks() {
        val runtime = Runtime()
        assertTrue(runCatching { TaobaoQuizTask.run(runtime) { UiSnapshot(listOf(node("淘金币趣味答题"), node("未知题目"))) } }.isFailure)
        assertEquals(0, runtime.taps)
    }
    @Test fun gongcaiQuestionSelectsDriedLettuceByText() {
        val page = UiSnapshot(listOf(node("淘金币趣味答题"),
            node("1. 涮火锅时常见的“贡菜”究竟是什么菜单选题"),
            node("海带加工成的干菜", 100, 800), node("莴笋加工成的干菜", 100, 1000)))
        assertEquals("莴笋加工成的干菜", TaobaoQuizTask.answer(page).text)
    }
    @Test fun dedicationStorySelectsChessByText() {
        val page = UiSnapshot(listOf(node("淘金币趣味答题"),
            node("1. 成语“专心致志”最初说的是学习哪种技艺的故事单选题"),
            node("射箭", 100, 800), node("下棋", 100, 1000)))
        assertEquals("下棋", TaobaoQuizTask.answer(page).text)
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
        assertEquals(1, runtime.taps) // Already selected: only submits once, never toggles it off.
        assertEquals(0, runtime.backs)
    }
    @Test fun multiSelectQuestionIsSkippedWithoutAnyAnswerTap() {
        val runtime = Runtime()
        val question = UiSnapshot(listOf(node("淘金币趣味答题"),
            node("1. 新的多选问题多选题"), node("沈万三"), node("赵公明")))
        val panel = UiSnapshot(listOf(node("今日速赚")))
        val outcome = TaobaoQuizTask.runOrSkip(runtime, { panel }, question)
        assertEquals(panel, outcome.page)
        assertTrue(outcome.skippedReason.orEmpty().contains("多选题"))
        assertEquals(0, runtime.taps)
        assertEquals(1, runtime.backs)
    }
    @Test fun newSingleChoiceQuestionIsAlsoSkipped() {
        val runtime = Runtime()
        val question = UiSnapshot(listOf(node("淘金币趣味答题"), node("1. 新的问题单选题")))
        val outcome = TaobaoQuizTask.runOrSkip(runtime, { UiSnapshot(listOf(node("今日速赚"))) }, question)
        assertTrue(outcome.skippedReason.orEmpty().contains("新的问题"))
        assertEquals(0, runtime.taps)
    }
    @Test fun unidentifiedPageCannotBeDismissedAsUnknownQuiz() {
        val runtime = Runtime()
        assertTrue(runCatching { TaobaoQuizTask.runOrSkip(runtime, read = {
            UiSnapshot(listOf(node("1. 新的问题单选题")))
        }) }.isFailure)
        assertEquals(0, runtime.backs)
        assertEquals(0, runtime.taps)
    }
    @Test fun skipMustActuallyReturnToPanel() {
        val runtime = Runtime()
        val question = UiSnapshot(listOf(node("淘金币趣味答题"), node("1. 新的问题多选题")))
        assertTrue(runCatching { TaobaoQuizTask.runOrSkip(runtime, { question }, question) }.isFailure)
        assertEquals(1, runtime.backs)
        assertEquals(0, runtime.taps)
    }
    @Test fun selectionFailureIsNotSwallowedByFallback() {
        val runtime = Runtime()
        assertTrue(runCatching { TaobaoQuizTask.runOrSkip(runtime, { question() }, question()) }.isFailure)
        assertEquals(1, runtime.taps)
        assertEquals(0, runtime.backs)
    }
    private val people = listOf("沈万三", "赵公明", "鲁智深", "林黛玉")
    private fun multi(selected: Set<String> = emptySet(), order: List<String> = people) = UiSnapshot(
        listOf(node("淘金币趣味答题"), node("1. 以下哪位人物有可能成为淘金币的忠实用户？多选题")) +
            order.mapIndexed { i, label -> node(label, 100, 800 + i * 200) } +
            order.mapIndexedNotNull { i, label -> if (label in selected) node("selected", 900, 800 + i * 200) else null } +
            node("我选好了", 100, 2100))
    @Test fun knownMultiSelectsFourNamesAndSubmitsOnlyOnce() {
        val runtime = Runtime()
        val panel = UiSnapshot(listOf(node("今日速赚"), node("淘金币趣味课堂参与答题")))
        val pages = java.util.ArrayDeque(listOf(multi()) + (1..4).map { multi(people.take(it).toSet()) } +
            listOf(UiSnapshot(listOf(node("已成功领取奖励 回到主页"))), panel))
        assertEquals(panel, TaobaoQuizTask.runOrSkip(runtime, { pages.removeFirst() }).page)
        assertEquals(5, runtime.taps)
        assertEquals(1, runtime.backs)
    }
    @Test fun multiSelectionFollowsNamesWhenOptionOrderChanges() {
        val runtime = Runtime()
        val order = people.reversed()
        val pages = java.util.ArrayDeque((1..4).map { multi(people.take(it).toSet(), order) })
        val selected = TaobaoQuizTask.selectAnswers(runtime, { pages.removeFirst() }, multi(order = order), people)
        assertEquals(4, selected.nodes.count { it.named("selected") })
        assertEquals(4, runtime.taps)
    }
    @Test fun preselectedCorrectOptionsArePreserved() {
        val runtime = Runtime()
        val pages = java.util.ArrayDeque(listOf(multi(people.take(3).toSet()), multi(people.toSet())))
        TaobaoQuizTask.selectAnswers(runtime, { pages.removeFirst() }, multi(people.take(2).toSet()), people)
        assertEquals(2, runtime.taps)
    }
    @Test fun multiDoesNotSelectEveryOptionUnlessReviewedSetRequiresIt() {
        val runtime = Runtime()
        val labels = listOf("沈万三", "鲁智深")
        val pages = java.util.ArrayDeque(listOf(multi(setOf("沈万三")), multi(labels.toSet())))
        val selected = TaobaoQuizTask.selectAnswers(runtime, { pages.removeFirst() }, multi(), labels)
        assertEquals(2, selected.nodes.count { it.named("selected") })
        assertEquals(2, runtime.taps)
    }
    @Test fun wrongPreselectionOrMissingOptionStopsBeforeFirstTap() {
        val runtime = Runtime()
        assertTrue(runCatching { TaobaoQuizTask.selectAnswers(runtime, { multi() },
            multi(setOf("林黛玉")), listOf("沈万三", "赵公明")) }.isFailure)
        assertTrue(runCatching { TaobaoQuizTask.selectAnswers(runtime, { multi() },
            multi(order = people.take(3)), people) }.isFailure)
        assertEquals(0, runtime.taps)
    }
    @Test fun radioBehaviorOrChangedQuestionStopsBeforeSubmitting() {
        val runtime = Runtime()
        val pages = java.util.ArrayDeque(listOf(multi(setOf("沈万三")), multi(setOf("赵公明"))))
        assertTrue(runCatching { TaobaoQuizTask.runReviewed(runtime, { pages.removeFirst() }, multi(), people) }.isFailure)
        assertEquals(2, runtime.taps)
        val changed = Runtime()
        assertTrue(runCatching { TaobaoQuizTask.runReviewed(changed, { question() }, multi(), people) }.isFailure)
        assertEquals(1, changed.taps)
    }
    @Test fun multiAlreadySelectedSubmitsOnceAndNeverRetriesMissingReward() {
        val runtime = Runtime()
        assertTrue(runCatching { TaobaoQuizTask.runReviewed(runtime, { multi(people.toSet()) },
            multi(people.toSet()), people) }.isFailure)
        assertEquals(1, runtime.taps)
        assertEquals(0, runtime.backs)
    }
}
