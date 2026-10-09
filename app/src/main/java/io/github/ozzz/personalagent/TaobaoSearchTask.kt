package io.github.ozzz.personalagent

/** Search suggestions only; scroll results without opening or purchasing a product. */
internal object TaobaoSearchTask {
    private const val PACKAGE = "com.taobao.taobao"
    private const val TITLE = "搜一搜你心仪的宝贝"
    fun run(runtime: AutomationRuntime, read: () -> UiSnapshot): UiSnapshot {
        var page = read()
        check(page.findExact("搜索有福利") != null) { "未确认搜索福利页面" }
        val discovery = checkNotNull(page.findExact("搜索发现")) { "未找到搜索发现" }
        val suggestion = checkNotNull(page.nodes.filter {
            it.usable && it.clickable && it.description.contains("近一个月点击") &&
                it.top >= discovery.top && it.bottom > discovery.bottom &&
                !listOf("贷款", "借钱", "彩票").any { word -> it.description.contains(word) }
        }.minByOrNull { it.top * 10000L + it.left }) { "未找到可用的搜索建议" }
        runtime.log("[搜索] 选择搜索建议：${suggestion.description}")
        runtime.tap(PACKAGE, suggestion.x, suggestion.y)
        runtime.pause(1500)
        return browse(runtime, read)
    }
    internal fun browse(runtime: AutomationRuntime, read: () -> UiSnapshot): UiSnapshot {
        var page: UiSnapshot
        var sawTimer = false
        for (step in 0..10) {
            page = read()
            val root = checkNotNull(page.findExact("金币-固搜-interact")
                ?: page.findExact("金币-固推&自建feeds-interact")) { "搜索奖励页面已变化，停止" }
            val timer = page.findExact("秒可领") != null && page.findExact("浏览") != null
            if (!timer && sawTimer) break
            check(timer) { "未确认搜索浏览计时，停止" }
            sawTimer = true
            check(step < 10) { "搜索浏览达到10次滑动上限，未确认奖励" }
            runtime.log("[搜索] 浏览滑动 ${step + 1}/10")
            val height = root.bottom - root.top
            runtime.swipe(PACKAGE, root.x, root.top + height * 70 / 100,
                root.x, root.top + height * 42 / 100, 650)
            runtime.pause(2000)
        }
        // Timer disappearing is only a reason to verify, never proof of a reward.
        repeat(3) {
            runtime.back(PACKAGE)
            runtime.pause(750)
            page = read()
            if (page.findExact("今日速赚") != null) {
                check(TaobaoQuickTask.allDone(page) ||
                    (page.nodes.any { it.usable && (it.text.startsWith(TITLE) || it.text.startsWith("看看#") || it.text.startsWith("发现精选好物")) } &&
                        page.nodes.filter { it.usable && (it.text.startsWith(TITLE) ||
                            it.text.startsWith("看看#") || it.text.startsWith("发现精选好物")) }
                            .all { TaobaoQuickTask.reward(page, it.text, "+30") == null })) {
                    "返回面板后搜索奖励仍未确认"
                }
                runtime.log("[搜索] 面板确认搜索任务已完成")
                return page
            }
        }
        error("搜索结束后未返回快速赚面板")
    }
}
