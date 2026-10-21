package com.violinstudio.domain.feature.link.model

import java.io.File
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Test

class LinkNoticeTest {
    @Test
    fun `la version del aviso del cliente coincide con la del servidor`() {
        val source = File("../functions/src/config/identity.ts").readText()
        val server = Regex("""LINK_NOTICE_VERSION\s*=\s*(\d+)""").find(source)?.groupValues?.get(1)?.toInt()
        assertNotNull(server, "LINK_NOTICE_VERSION no encontrada en identity.ts")
        assertEquals(server, LinkNotice.VERSION)
    }
}
