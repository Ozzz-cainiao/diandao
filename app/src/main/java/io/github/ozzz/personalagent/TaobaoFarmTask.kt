package io.github.ozzz.personalagent

/** Visit only: no taps, payments, permissions or game actions inside Alipay. */
internal object TaobaoFarmTask {
    private const val TAOBAO = "com.taobao.taobao"
    private const val ALIPAY = "com.eg.android.AlipayGphone"
    fun run(runtime: AutomationRuntime, entry: UiNode, allowRemaining: Boolean = false): UiSnapshot {
        val page = runtime.visitAndFinish(TAOBAO, entry.x, entry.y, ALIPAY, "蚂蚁庄园",
            if (allowRemaining) listOf("今日速赚") else listOf("今日速赚", "今日快速赚奖励已拿完"))
        val completedRow = page.nodes.any { label -> label.usable && label.text.startsWith("去蚂蚁庄园逛逛哟") &&
            page.nodes.none { amount -> amount.usable && amount.named("+50") &&
                amount.left > label.right && amount.top <= label.bottom && amount.bottom >= label.top } }
        check(TaobaoQuickTask.allDone(page) ||
            (allowRemaining && page.findExact("今日速赚") != null && completedRow)) {
            "远端返回后庄园领奖结果未确认"
        }
        runtime.log(if (TaobaoQuickTask.allDone(page)) "[庄园] 远端已确认今日奖励全部完成并返回桌面"
            else "[庄园] 面板确认庄园任务完成并返回桌面；跳过的课堂仍未领取")
        return page
    }
}
