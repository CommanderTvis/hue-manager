package io.github.commandertvis.huemanager

import jakarta.ws.rs.GET
import jakarta.ws.rs.Path
import jakarta.ws.rs.PathParam
import jakarta.ws.rs.core.Response
import java.net.URLConnection
import kotlin.io.path.Path as filePath
import kotlin.io.path.exists
import kotlin.io.path.isDirectory
import kotlin.io.path.isRegularFile
import kotlin.io.path.readBytes

/**
 * Serves the composeApp wasm SPA dist from the runtime `web/` directory with an SPA catch-all that
 * falls back to `index.html` for client-side routes. Reserved prefixes (`/api`, `/mcp`, `/q`,
 * `/.well-known`) are never served here so the REST resources, the MCP SSE endpoint, Quarkus
 * management endpoints, and OIDC/OAuth metadata keep their routes.
 *
 * Note: explicit JAX-RS resources (`/api/...`) and the MCP/`/q`/`.well-known` handlers take priority
 * over this catch-all via JAX-RS most-specific-match, so the guard here is a defensive backstop.
 */
@Path("/")
class SpaResource {
    private val webDir = filePath("web")

    @GET
    fun root(): Response = serve("")

    @GET
    @Path("{path:.*}")
    fun spa(@PathParam("path") path: String): Response = serve(path)

    private fun serve(path: String): Response {
        if (RESERVED_PREFIXES.any { path == it || path.startsWith("$it/") }) {
            return Response.status(Response.Status.NOT_FOUND).build()
        }

        if (!webDir.isDirectory()) {
            return Response.ok("Hue Manager Server - Web UI not available")
                .type("text/plain")
                .build()
        }

        // Prevent path traversal; resolve only within webDir.
        val requested = webDir.resolve(path).normalize()
        if (!requested.startsWith(webDir.normalize())) {
            return Response.status(Response.Status.NOT_FOUND).build()
        }

        val file = if (requested.isRegularFile()) requested else webDir.resolve("index.html")
        if (!file.exists() || !file.isRegularFile()) {
            return Response.status(Response.Status.NOT_FOUND).build()
        }

        val contentType = URLConnection.guessContentTypeFromName(file.fileName.toString())
            ?: "application/octet-stream"
        return Response.ok(file.readBytes()).type(contentType).build()
    }

    private companion object {
        val RESERVED_PREFIXES = listOf("api", "mcp", "q", ".well-known")
    }
}
