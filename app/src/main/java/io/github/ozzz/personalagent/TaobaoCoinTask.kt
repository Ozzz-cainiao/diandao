package io.github.ozzz.personalagent

/** Daily sign-in only. Never interpret preloaded '已领取' text alone as success. */
object TaobaoCoinTask {
    private const val PACKAGE = "com.taobao.taobao"

    internal fun claimed(page: UiSnapshot): Boolean =
        page.findExact("淘金币标题") != null &&
        page.findExact("签到领金币") == null &&
        page.findExact("赚更多金币") != null &&
        page.findExact("今天") != null

    fun runAndReturnHome(runtime: AutomationRuntime, record: (String) -> Unit = {}): String {
        val result = run(runtime, record)
        runtime.log("[签到] $result；准备返回桌面")
        try { runtime.returnHome(PACKAGE) }
        catch (e: Exception) { throw IllegalStateException("签到已确认，但返回桌面失败：${e.message}", e) }
        return "$result；已发送返回桌面指令"
    }

    fun run(runtime: AutomationRuntime, record: (String) -> Unit = {}, initialPage: UiSnapshot? = null): String {
        fun read(): UiSnapshot = TaobaoPageReader.read(runtime, record)
        var page = initialPage ?: run {
            runtime.log("[状态] 启动淘宝")
            runtime.launch(PACKAGE)
            runtime.pause(2500)
            read()
        }
        for (attempt in 1..3) {
            if (page.findExact("淘金币标题") != null) break
            val entry = page.findExact("领淘金币") ?: break
            runtime.log("[状态] 进入淘金币 $attempt/3")
            runtime.tap(PACKAGE, entry.x, entry.y)
            runtime.pause(1500)
            page = read()
            for (loading in 1..3) {
                if (page.findExact("淘金币标题") != null || page.findExact("领淘金币") != null) break
                runtime.pause(500)
                page = read()
            }
        }
        check(page.findExact("淘金币标题") != null) { "未确认淘金币页面，请手动回到淘宝首页后重试" }
        if (claimed(page)) return "今日已签到，无需重复领取"
        val claim = page.findExact("签到领金币")
            ?: error("未找到可确认的签到按钮；不凭隐藏的已领取文本判断成功")
        runtime.log("[状态] 点击每日签到一次")
        runtime.tap(PACKAGE, claim.x, claim.y)
        repeat(5) {
            runtime.pause(1200)
            page = read()
            if (claimed(page)) {
                return "签到按钮已消失，页面显示已领取"
            }
        }
        error("已点击一次，但尚未确认领取结果；停止重复点击，请查看淘宝页面")
    }
}
