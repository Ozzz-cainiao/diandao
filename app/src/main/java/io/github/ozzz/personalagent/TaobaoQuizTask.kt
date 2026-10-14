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
    ).mapValues { (_, answer) -> listOf(answer) } + mapOf(
        "1. 以下哪位人物有可能成为淘金币的忠实用户？多选题" to
            listOf("沈万三", "赵公明", "鲁智深", "林黛玉"),
    )
    private fun questionText(page: UiSnapshot): String {
        check(page.findExact("淘金币趣味答题") != null) { "未确认趣味课堂页面" }
        return checkNotNull(page.nodes.singleOrNull { it.usable && it.text.startsWith("1.") }) {
            "题目未加载或不唯一"
        }.text
    }
    private fun options(page: UiSnapshot, labels: List<String>): List<UiNode> = labels.map { label ->
        checkNotNull(page.nodes.singleOrNull { it.usable && it.named(label) }) { "答案选项缺失或不唯一：$label" }
    }
    internal fun answer(page: UiSnapshot): UiNode {
        val labels = answers[questionText(page)] ?: error("题目尚未支持，停止答题；不会随机选择")
        return options(page, labels).single()
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
        val reason = if (question.text.endsWith("多选题")) "多选题答案尚未收录：${question.text}"
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
        val labels = answers[questionText(initial)] ?: error("题目尚未支持，停止答题；不会随机选择")
        return runReviewed(runtime, read, initial, labels)
    }

    private fun marks(page: UiSnapshot): List<UiNode> = page.nodes.filter { it.usable && it.named("selected") }
        .distinctBy { listOf(it.left, it.top, it.right, it.bottom) }
    private fun beside(mark: UiNode, option: UiNode): Boolean =
        mark.left >= option.right && mark.top < option.bottom && mark.bottom > option.top

    /** Select a reviewed set by text. Existing correct selections are never toggled off. */
    internal fun selectAnswers(runtime: AutomationRuntime, read: () -> UiSnapshot,
                               initial: UiSnapshot, labels: List<String>): UiSnapshot {
        val question = questionText(initial)
        require(labels.isNotEmpty() && labels.size <= 8 && labels.distinct().size == labels.size && labels.all { it.isNotBlank() })
        check((question.endsWith("单选题") && labels.size == 1) || question.endsWith("多选题")) { "题型和答案数量不匹配" }
        var page = initial
        fun verify(): List<UiNode> {
            check(questionText(page) == question) { "选择期间题目变化，不提交" }
            val nodes = options(page, labels)
            check(marks(page).all { mark -> nodes.count { beside(mark, it) } == 1 }) {
                "发现非目标选项已选或选中标记不明确，不提交"
            }
            return nodes
        }
        verify() // Resolve every expected option before the first tap.
        val confirmed = mutableSetOf<String>()
        for (label in labels) {
            val target = verify().single { it.named(label) }
            if (marks(page).none { beside(it, target) }) {
                runtime.log("[课堂] 按答案文字选择：$label")
                runtime.tap(PACKAGE, target.x, target.y)
                runtime.pause(350)
                page = read()
            } else runtime.log("[课堂] 答案已选，保留：$label")
            confirmed.add(label)
            val nodes = verify()
            check(nodes.filter { it.text in confirmed || it.description in confirmed }
                .all { option -> marks(page).count { beside(it, option) } == 1 }) { "未确认答案选中状态，不提交" }
        }
        val nodes = verify()
        check(marks(page).size == labels.size && nodes.all { option -> marks(page).count { beside(it, option) } == 1 }) {
            "选中集合与答案不一致，不提交"
        }
        return page
    }

    internal fun runReviewed(runtime: AutomationRuntime, read: () -> UiSnapshot,
                             initial: UiSnapshot, labels: List<String>): UiSnapshot {
        var page = selectAnswers(runtime, read, initial, labels)
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
