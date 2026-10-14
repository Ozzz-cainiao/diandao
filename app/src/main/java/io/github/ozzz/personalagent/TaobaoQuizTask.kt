package io.github.ozzz.personalagent

/** Exact reviewed questions only. Answers follow text, never a fixed A/B position. */
internal object TaobaoQuizTask {
    private const val PACKAGE = "com.taobao.taobao"
    private val answers = mapOf(
        "1. 成语“专心致志”最初说的是学习哪种技艺的故事单选题" to "下棋",
        "1. 涮火锅时常见的“贡菜”究竟是什么菜单选题" to "莴笋加工成的干菜",
        "1. 成语“司空见惯”中的“司空”在古代指的是单选题" to "官职",
        "1. 猜一猜：下列哪个词是古人对钱币的别称单选题" to "孔方兄",
        "1. 为什么手机屏幕沾上水会影响触控效果单选题" to "水能导电",
        "1. 猜一猜：成语“白云苍狗”的典故与哪位诗人有关单选题" to "杜甫",
        "1. 猜一猜：土豆为什么得名马铃薯单选题" to "与马铃铛有关",
    )
    internal fun answer(page: UiSnapshot): UiNode {
        check(page.findExact("淘金币趣味答题") != null) { "未确认趣味课堂页面" }
        val question = answers.keys.singleOrNull { page.findExact(it) != null }
            ?: error("题目尚未支持，停止答题；不会随机选择")
        return checkNotNull(page.findExact(answers.getValue(question))) { "答案选项未确认" }
    }
    data class Outcome(val page: UiSnapshot, val skippedReason: String? = null)

    private fun load(runtime: AutomationRuntime, read: () -> UiSnapshot, initial: UiSnapshot?): UiSnapshot {
        var page = initial ?: read()
        for (attempt in 1..6) {
            if (page.findExact("淘金币趣味答题") != null &&
                page.nodes.any { it.usable && it.text.startsWith("1.") }) break
            runtime.pause(750)
            page = read()
        }
        return page
    }

    /** Only an identified unsupported question may be skipped; failures after a tap still stop. */
    fun runOrSkip(runtime: AutomationRuntime, read: () -> UiSnapshot, initial: UiSnapshot? = null): Outcome {
        val page = load(runtime, read, initial)
        check(page.findExact("淘金币趣味答题") != null) { "未确认趣味课堂页面，不能跳过" }
        val question = checkNotNull(page.nodes.singleOrNull { it.usable && it.text.startsWith("1.") }) {
            "题目仍未加载或不唯一，不能跳过未知页面"
        }
        if (question.text in answers) return Outcome(runLoaded(runtime, read, page))
        val reason = if (question.text.endsWith("多选题")) "多选题暂未支持：${question.text}"
            else "题库未收录或题型未支持：${question.text}"
        val submitTop = page.findExact("我选好了")?.top ?: Int.MAX_VALUE
        val choices = page.nodes.filter { it.usable && it.top >= question.bottom && it.bottom <= submitTop }
            .map { it.text.ifBlank { it.description } }
            .filter { it.isNotBlank() && !it.matches(Regex("[A-Z]")) && !it.startsWith("题目来源") }
            .distinct().take(12)
        if (choices.isNotEmpty()) runtime.log("[课堂选项] ${choices.joinToString(" / ")}")
        runtime.log("[课堂跳过] $reason；不选择、不提交，返回面板继续其他任务")
        runtime.back(PACKAGE)
        runtime.pause(1000)
        var panel = read()
        repeat(6) {
            if (panel.findExact("今日速赚") == null) {
                runtime.pause(750)
                panel = read()
            }
        }
        check(panel.findExact("今日速赚") != null) { "跳过课堂后未回到任务面板，停止" }
        return Outcome(panel, reason)
    }

    fun run(runtime: AutomationRuntime, read: () -> UiSnapshot): UiSnapshot =
        runLoaded(runtime, read, load(runtime, read, null))

    private fun runLoaded(runtime: AutomationRuntime, read: () -> UiSnapshot, initial: UiSnapshot): UiSnapshot {
        var page = initial
        val option = answer(page)
        runtime.log("[课堂] 匹配已确认题目，按答案文字选择：${option.text}")
        runtime.tap(PACKAGE, option.x, option.y)
        runtime.pause(350)
        page = read()
        val chosen = answer(page)
        check(chosen.text == option.text && page.nodes.any { it.usable && it.named("selected") &&
            it.left >= chosen.right && it.top < chosen.bottom && it.bottom > chosen.top }) {
            "未确认答案选中状态，不提交"
        }
        val submit = checkNotNull(page.findExact("我选好了")) { "未确认答题提交按钮" }
        runtime.tap(PACKAGE, submit.x, submit.y)
        runtime.log("[课堂] 已提交一次，等待明确领奖结果")
        repeat(6) {
            runtime.pause(750)
            page = read()
            if (page.findExact("已成功领取奖励 回到主页") != null) {
                runtime.log("[课堂] 页面确认已成功领取奖励")
                runtime.back(PACKAGE)
                runtime.pause(1000)
                page = read()
                repeat(4) {
                    if (page.findExact("今日速赚") == null) {
                        runtime.pause(750)
                        page = read()
                    }
                }
                check(page.findExact("今日速赚") != null &&
                    page.nodes.any { n -> n.usable && n.text.startsWith("淘金币趣味课堂") } &&
                    TaobaoQuickTask.reward(page, "淘金币趣味课堂", "+30") == null) {
                    "课堂已显示领奖成功，但任务面板结果未确认，停止"
                }
                return page
            }
        }
        error("答题已提交一次，尚未确认领奖结果，停止重复提交")
    }
}
