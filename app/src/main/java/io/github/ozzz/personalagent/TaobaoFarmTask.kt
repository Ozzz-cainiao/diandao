package io.github.ozzz.personalagent

/** Visit only: no taps, payments, permissions or game actions inside Alipay. */
internal object TaobaoFarmTask {
    private const val TAOBAO = "com.taobao.taobao"
    private const val ALIPAY = "com.eg.android.AlipayGphone"
    fun run(runtime: AutomationRuntime, entry: UiNode): UiSnapshot {
        val page = runtime.visitAndFinish(TAOBAO, entry.x, entry.y, ALIPAY, "蚂蚁庄园",
            listOf("今日速赚", "今日快速赚奖励已拿完"))
        check(TaobaoQuickTask.allDone(page)) { "远端返回的今日完成证据不足" }
        runtime.log("[庄园] 远端已确认今日奖励全部完成并返回桌面")
        return page
    }
}
