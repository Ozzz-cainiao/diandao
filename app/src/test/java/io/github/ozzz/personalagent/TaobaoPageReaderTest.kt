package io.github.ozzz.personalagent

import org.junit.Assert.*
import org.junit.Test

class TaobaoPageReaderTest {
    private val popup = """<hierarchy>
        <node id="com.taobao.taobao:id/poplayer_native_state_id" bounds="115,666,965,1688" enabled="true" visible="true"/>
        <node desc="限时福利砸中你" bounds="115,666,965,1688" enabled="true" visible="true"/>
        <node text="叠加立减享折上折" bounds="115,1392,965,1464" enabled="true" visible="true"/>
        <node desc="关闭按钮" bounds="488,1792,592,1896" clickable="true" enabled="true" visible="true"/>
        </hierarchy>"""
    private class Runtime(val pages: List<String>) : AutomationRuntime {
        var taps = 0
        var reads = 0
        override fun readUi(packageName: String) = pages[minOf(reads++, pages.lastIndex)]
        override fun tap(packageName: String, x: Int, y: Int) { assertEquals(540, x); assertEquals(1844, y); taps++ }
        override fun launch(packageName: String) {}
        override fun back(expectedPackage: String) {}
        override fun returnHome(expectedPackage: String) {}
        override fun swipe(packageName: String, startX: Int, startY: Int, endX: Int, endY: Int, durationMs: Int) = error("no swipe")
        override fun pause(milliseconds: Long) {}
        override fun log(message: String) {}
    }
    @Test fun observedCouponIsClosedThenReread() {
        val runtime = Runtime(listOf(popup, "<hierarchy/>"))
        assertTrue(TaobaoPageReader.read(runtime) {}.nodes.isEmpty())
        assertEquals(1, runtime.taps)
    }
    @Test fun unknownOverlayAndNonclickableCloseAreNotBlindlyClicked() {
        val unknown = Runtime(listOf(popup.replace("限时福利砸中你", "未知弹窗")))
        TaobaoPageReader.read(unknown) {}
        assertEquals(0, unknown.taps)
        val disabled = Runtime(listOf(popup.replace("clickable=\"true\"", "clickable=\"false\"")))
        assertTrue(runCatching { TaobaoPageReader.read(disabled) {} }.isFailure)
        assertEquals(0, disabled.taps)
    }
    @Test fun newOfficialDiscountCouponIsClosed() {
        val updated = popup.replace("限时福利砸中你", "张消费券共")
            .replace("叠加立减享折上折", "可叠加官方立减")
            .replace("</hierarchy>", "<node text=\"去使用\" bounds=\"462,1430,618,1499\" enabled=\"true\" visible=\"true\"/></hierarchy>")
        val runtime = Runtime(listOf(updated, "<hierarchy/>"))
        assertTrue(TaobaoPageReader.read(runtime) {}.nodes.isEmpty())
        assertEquals(1, runtime.taps)
    }
    @Test fun repeatedPopupIsBounded() {
        val runtime = Runtime(listOf(popup))
        assertTrue(runCatching { TaobaoPageReader.read(runtime) {} }.isFailure)
        assertEquals(3, runtime.taps)
    }
}
