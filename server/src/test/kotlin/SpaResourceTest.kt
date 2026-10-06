package io.github.commandertvis.huemanager

import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.writeBytes
import kotlin.io.path.writeText

class SpaResourceTest {
    @TempDir
    lateinit var webDir: Path

    @Test
    fun `wasm modules are served with the required MIME type and unchanged bytes`() {
        val bytes = byteArrayOf(0, 0x61, 0x73, 0x6d, 1, 0, 0, 0)
        webDir.resolve("index.html").writeText("<html></html>")
        for (path in listOf("composeApp.wasm", "assets/skiko.wasm")) {
            val file = webDir.resolve(path)
            file.parent.createDirectories()
            file.writeBytes(bytes)

            SpaResource().serve(path, webDir).use { response ->
                assertEquals(200, response.status)
                assertEquals("application/wasm", response.getHeaderString("Content-Type"))
                assertArrayEquals(bytes, response.entity as ByteArray)
            }
        }
    }

    @Test
    fun `client routes still fall back to the HTML entry point`() {
        val html = "<html>Hue Manager</html>"
        webDir.resolve("index.html").writeText(html)

        SpaResource().serve("lamps", webDir).use { response ->
            assertEquals(200, response.status)
            assertEquals("text/html", response.getHeaderString("Content-Type"))
            assertArrayEquals(html.toByteArray(), response.entity as ByteArray)
        }
    }
}
