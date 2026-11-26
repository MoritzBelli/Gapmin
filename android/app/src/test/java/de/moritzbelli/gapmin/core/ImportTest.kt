package de.moritzbelli.gapmin.core

import org.junit.Test
import java.io.File

class ImportTest {
    @Test
    fun demoExportImports() {
        val demo = listOf("../../web/public/demo/gadgetbridge-demo.zip", "../web/public/demo/gadgetbridge-demo.zip")
            .map(::File).first { it.exists() }
        ImportChecks.run(demo, File(System.getProperty("java.io.tmpdir"), "gapmin-test"))
    }
}
