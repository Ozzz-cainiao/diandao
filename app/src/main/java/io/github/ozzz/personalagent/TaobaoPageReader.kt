package io.github.ozzz.personalagent

/** Close only the observed native coupon overlay, using its explicit accessibility control. */
internal object TaobaoPageReader {
    fun read(runtime: AutomationRuntime, record: (String) -> Unit): UiSnapshot {
        repeat(4) { attempt ->
            val xml = runtime.readUi("com.taobao.taobao")
            record(xml)
            val page = UiSnapshot.parse(xml)
            val overlay = page.nodes.any { it.usable && it.id == "com.taobao.taobao:id/poplayer_native_state_id" }
            val coupon = (page.findExact("限时福利砸中你") != null && page.findExact("叠加立减享折上折") != null) ||
                (page.findExact("张消费券共") != null && page.findExact("可叠加官方立减") != null &&
                    page.findExact("去使用") != null)
            if (!overlay || !coupon) return page
            check(attempt < 3) { "消费券弹窗关闭3次后仍出现，停止" }
            val close = page.nodes.singleOrNull { it.usable && it.clickable && it.named("关闭按钮") }
                ?: error("确认消费券弹窗但无法唯一识别关闭按钮，停止")
            runtime.log("[弹窗] 关闭已确认的消费券广告 ${attempt + 1}/3")
            runtime.tap("com.taobao.taobao", close.x, close.y)
            runtime.pause(350)
        }
        error("弹窗未关闭")
    }
}
