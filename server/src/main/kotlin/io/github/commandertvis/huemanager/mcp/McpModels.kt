package io.github.commandertvis.huemanager.mcp

import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

/**
 * MCP Protocol version
 */
const val MCP_PROTOCOL_VERSION = "2025-11-25"

/**
 * JSON-RPC 2.0 Request
 */
@Serializable
data class JsonRpcRequest(
    val jsonrpc: String = "2.0",
    val id: JsonElement? = null,
    val method: String,
    val params: JsonObject? = null
)

/**
 * JSON-RPC 2.0 Response
 */
@OptIn(ExperimentalSerializationApi::class)
@Serializable
data class JsonRpcResponse(
    @EncodeDefault(EncodeDefault.Mode.ALWAYS)
    val jsonrpc: String = "2.0",
    val id: JsonElement? = null,
    val result: JsonElement? = null,
    val error: JsonRpcError? = null
)

/**
 * JSON-RPC 2.0 Error
 */
@Serializable
data class JsonRpcError(
    val code: Int,
    val message: String,
    val data: JsonElement? = null
)

// Standard JSON-RPC error codes
object JsonRpcErrorCodes {
    const val PARSE_ERROR = -32700
    const val INVALID_REQUEST = -32600
    const val METHOD_NOT_FOUND = -32601
    const val INVALID_PARAMS = -32602
    const val INTERNAL_ERROR = -32603
}

/**
 * MCP Initialize Request Params
 */
@Serializable
data class InitializeParams(
    val protocolVersion: String,
    val capabilities: ClientCapabilities,
    val clientInfo: ClientInfo
)

@Serializable
data class ClientCapabilities(
    val roots: RootsCapability? = null,
    val sampling: JsonObject? = null
)

@Serializable
data class RootsCapability(
    val listChanged: Boolean? = null
)

@Serializable
data class ClientInfo(
    val name: String,
    val version: String
)

/**
 * MCP Initialize Response Result
 */
@Serializable
data class InitializeResult(
    val protocolVersion: String,
    val capabilities: ServerCapabilities,
    val serverInfo: ServerInfo
)

@Serializable
data class ServerCapabilities(
    val tools: ToolsCapability? = null,
    val resources: ResourcesCapability? = null,
    val prompts: PromptsCapability? = null
)

@Serializable
data class ToolsCapability(
    val listChanged: Boolean? = null
)

@Serializable
data class ResourcesCapability(
    val subscribe: Boolean? = null,
    val listChanged: Boolean? = null
)

@Serializable
data class PromptsCapability(
    val listChanged: Boolean? = null
)

@Serializable
data class ServerInfo(
    val name: String,
    val version: String
)

/**
 * MCP Tool Definition
 */
@Serializable
data class Tool(
    val name: String,
    val description: String,
    val inputSchema: ToolInputSchema
)

@OptIn(ExperimentalSerializationApi::class)
@Serializable
data class ToolInputSchema(
    @EncodeDefault(EncodeDefault.Mode.ALWAYS)
    val type: String = "object",
    @EncodeDefault(EncodeDefault.Mode.ALWAYS)
    val properties: Map<String, PropertySchema> = emptyMap(),
    @EncodeDefault(EncodeDefault.Mode.ALWAYS)
    val required: List<String> = emptyList()
)

@Serializable
data class PropertySchema(
    val type: String,
    val description: String,
    val enum: List<String>? = null
)

/**
 * MCP Tools List Response
 */
@Serializable
data class ToolsListResult(
    val tools: List<Tool>
)

/**
 * MCP Tool Call Params
 */
@Serializable
data class ToolCallParams(
    val name: String,
    val arguments: JsonObject? = null
)

/**
 * MCP Tool Call Result
 */
@Serializable
data class ToolCallResult(
    val content: List<ToolContent>,
    val isError: Boolean = false
)

@Serializable
sealed class ToolContent {
    @Serializable
    @SerialName("text")
    data class Text(
        val type: String = "text",
        val text: String
    ) : ToolContent()
}

/**
 * SSE Event wrapper for MCP messages
 */
data class SseEvent(
    val event: String? = null,
    val data: String,
    val id: String? = null
)

fun SseEvent.toSseString(): String = buildString {
    event?.let { append("event: $it\n") }
    id?.let { append("id: $it\n") }
    // Data can be multiline, each line needs "data: " prefix
    data.lines().forEach { line ->
        append("data: $line\n")
    }
    append("\n")
}
