package io.github.ozzz.personalagent

/** Semantic pages, not changing coins/animation/product text, reset the 60-second clock. */
internal class PageBudget(private val now: () -> Long, private val limitMs: Long = 60_000) {
    @Volatile var page = "启动/加载"
        private set
    private var since = now()
    @Synchronized fun remaining() = (limitMs - (now() - since)).coerceAtLeast(0)
    @Synchronized fun check() { check(remaining() > 0) { "页面超时：$page 已达到${limitMs / 1000}秒，停止任务" } }
    @Synchronized fun observe(key: String): Boolean {
        check()
        if (key.endsWith("/未识别/加载") && page != "启动/加载") return false
        if (page == key) return false
        page = key
        since = now()
        return true
    }
    companion object {
        fun key(packageName: String, page: UiSnapshot): String {
            val kind = when {
                page.findExact("今日速赚") != null -> "快速赚面板"
                page.findExact("搜索有福利") != null -> "搜索福利入口"
                page.findExact("金币-固搜-interact") != null -> "搜索浏览"
                page.findExact("淘宝购物清单") != null -> "清单浏览"
                page.findExact("淘金币趣味答题") != null -> "趣味课堂"
                page.findExact("蚂蚁庄园") != null -> "蚂蚁庄园"
                page.findExact("淘金币标题") != null -> "每日签到"
                page.nodes.any { it.usable && (it.named("图片，按钮。双击可进入详情页。") || it.named("视频，按钮。双击可暂停或播放视频。")) } -> "商品视频"
                page.findExact("领淘金币") != null -> "淘宝首页"
                else -> "未识别/加载"
            }
            return "$packageName/$kind"
        }
    }
}
