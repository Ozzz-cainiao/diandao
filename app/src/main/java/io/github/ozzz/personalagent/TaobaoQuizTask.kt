package io.github.ozzz.personalagent

/** Exact reviewed questions only. Answers follow text, never a fixed A/B position. */
internal object TaobaoQuizTask {
    private const val PACKAGE = "com.taobao.taobao"
    private val answers = mapOf(
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
    fun run(runtime: AutomationRuntime, read: () -> UiSnapshot): UiSnapshot {
        var page = read()
        for (attempt in 1..6) {
            if (page.findExact("淘金币趣味答题") != null &&
                page.nodes.any { it.usable && it.text.endsWith("单选题") }) break
            runtime.pause(750)
            page = read()
        }
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
