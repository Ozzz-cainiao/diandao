package io.github.ozzz.personalagent

/** Visit only: no taps, payments, permissions or game actions inside Alipay. */
internal object TaobaoFarmTask {
    private const val TAOBAO = "com.taobao.taobao"
    private const val ALIPAY = "com.eg.android.AlipayGphone"
    fun run(runtime: AutomationRuntime, entry: UiNode, readTaobao: () -> UiSnapshot): UiSnapshot {
        runtime.visitAndReturn(TAOBAO, entry.x, entry.y, ALIPAY, "蚂蚁庄园")
        runtime.log("[庄园] 已确认访问并返回淘宝，核对奖励")
        runtime.pause(1000)
        var page = readTaobao()
        repeat(6) {
            if (!TaobaoQuickTask.allDone(page)) {
                runtime.pause(750)
                page = readTaobao()
            }
        }
        check(TaobaoQuickTask.allDone(page)) { "已访问庄园，但未确认今日快速赚奖励已拿完；停止，不重复跳转" }
        return page
    }
}
