package com.proxai.shell

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ShellLogicTest {
    @Test fun infoKeepsOnlyKnownFeatures() {
        val i = ShellLogic.parseInfo("""{"id":"tip-calc","name":"Tip Calc","version":"1.2",
            "features":["vibrate","internet","camera","notifications"],"color":"#22aa88"}""")
        assertEquals("tip-calc", i.id)
        assertEquals(setOf("vibrate", "notifications"), i.features)
        assertEquals(setOf("pc-stats"), ShellLogic.parseInfo("""{"features":["pc-stats","gpu"]}""").features)
        assertEquals(0xFF22AA88.toInt(), i.color)
    }

    @Test fun scoutFeatureAndApi() {
        assertEquals(setOf("scout"), ShellLogic.parseInfo("""{"features":["scout"]}""").features)
        assertTrue(ShellLogic.SHIM.contains("scout: function"))
        assertTrue(ShellLogic.SHIM.contains("n.scoutUpdate(id, JSON.stringify(change || {}))"))
    }

    @Test fun infoDefaults() {
        val i = ShellLogic.parseInfo("{}")
        assertEquals("ProxAI app", i.name)
        assertEquals(emptySet<String>(), i.features)
        assertEquals(ShellLogic.BRAND, i.color)
    }

    @Test fun assetPathsStayInsideWww() {
        assertEquals("www/index.html", ShellLogic.assetPath("/"))
        assertEquals("www/index.html", ShellLogic.assetPath(null))
        assertEquals("www/js/app.js", ShellLogic.assetPath("/js/app.js"))
        assertEquals("www/sub/index.html", ShellLogic.assetPath("/sub/"))
        assertEquals("www/x", ShellLogic.assetPath("//x"))   // extra leading slashes are harmless
        for (bad in listOf("/../proxai-app.json", "/js/../../x", "/.hidden", "/a//b", "/a/./b"))
            assertNull(bad, ShellLogic.assetPath(bad))
    }

    @Test fun mimeTypes() {
        assertEquals("text/javascript", ShellLogic.mimeType("www/a.JS"))
        assertEquals("image/svg+xml", ShellLogic.mimeType("www/i.svg"))
        assertEquals("application/octet-stream", ShellLogic.mimeType("www/blob"))
    }

    @Test fun shimGoesFirstInHead() {
        val out = ShellLogic.injectShim("<html><head><title>x</title></head><body><script>ProxAI.toast('hi')</script></body></html>")
        assertTrue(out.indexOf("window.ProxAI") < out.indexOf("<title>"))
        assertTrue(ShellLogic.injectShim("<p>no head</p>").startsWith("<script>"))
        assertTrue(ShellLogic.SHIM.contains("pcStats: function()"))
    }
}
