package io.github.commandertvis.huemanager

import io.ktor.server.application.*
import io.ktor.server.http.content.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlin.io.path.Path
import kotlin.io.path.isDirectory

fun Route.webRoutes() {
    val webDir = Path("web")
    if (webDir.isDirectory()) {
        singlePageApplication {
            filesPath = "web"
            // Exclude MCP and API paths from SPA routing
            ignoreFiles { path ->
                path.startsWith("/mcp") ||
                        path.startsWith("/api") ||
                        path.startsWith("/.well-known")
            }
        }
    } else {
        get("/") {
            call.respondText("Hue Manager Server v1.0.0 - Web UI not available")
        }
    }
}
