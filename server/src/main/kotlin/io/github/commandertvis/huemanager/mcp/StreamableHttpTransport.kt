package io.github.commandertvis.huemanager.mcp

import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.modelcontextprotocol.kotlin.sdk.shared.AbstractTransport
import io.modelcontextprotocol.kotlin.sdk.shared.TransportSendOptions
import io.modelcontextprotocol.kotlin.sdk.types.JSONRPCMessage
import io.modelcontextprotocol.kotlin.sdk.types.McpJson
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.encodeToString
import org.slf4j.LoggerFactory

/**
 * A simple Streamable HTTP transport for MCP.
 * 
 * This transport handles HTTP POST requests with JSON-RPC messages
 * and returns JSON responses. It's designed for simple request/response
 * scenarios without streaming.
 */
class StreamableHttpTransport : AbstractTransport() {
    private val logger = LoggerFactory.getLogger(StreamableHttpTransport::class.java)
    private val mutex = Mutex()
    
    // Pending responses keyed by a unique request correlation ID
    private val pendingResponses = mutableMapOf<String, CompletableDeferred<JSONRPCMessage>>()
    private var currentRequestId: String? = null
    
    // Readiness signaling - completes when start() is called (session is connected)
    private val readyDeferred = CompletableDeferred<Unit>()
    
    /**
     * Returns true when the transport is ready to handle requests.
     */
    val isReady: Boolean
        get() = readyDeferred.isCompleted
    
    /**
     * Suspends until the transport is ready to handle requests.
     */
    suspend fun awaitReady() {
        readyDeferred.await()
    }
    
    override suspend fun start() {
        logger.info("StreamableHttpTransport started and ready")
        readyDeferred.complete(Unit)
    }
    
    override suspend fun send(message: JSONRPCMessage, options: TransportSendOptions?) {
        logger.debug("Sending message: {}", message)
        mutex.withLock {
            currentRequestId?.let { reqId ->
                pendingResponses[reqId]?.complete(message)
            }
        }
    }
    
    override suspend fun close() {
        logger.debug("StreamableHttpTransport closed")
        _onClose()
    }
    
    /**
     * Handles an incoming HTTP POST request with a JSON-RPC message.
     * 
     * @param call The Ktor ApplicationCall
     * @return true if the request was handled successfully
     */
    suspend fun handlePostRequest(call: ApplicationCall): Boolean {
        // Wait for transport to be ready (session connected) with timeout
        val ready = withTimeoutOrNull(5_000) {
            awaitReady()
            true
        } ?: false
        
        if (!ready) {
            logger.warn("Transport not ready within timeout")
            call.respond(HttpStatusCode.ServiceUnavailable, "MCP server not ready")
            return false
        }
        
        val requestId = java.util.UUID.randomUUID().toString()
        val responseDeferred = CompletableDeferred<JSONRPCMessage>()
        
        try {
            // Register pending response
            mutex.withLock {
                pendingResponses[requestId] = responseDeferred
                currentRequestId = requestId
            }
            
            // Parse incoming message
            val body = call.receiveText()
            logger.debug("Received request body: {}", body)
            
            val message = try {
                McpJson.decodeFromString<JSONRPCMessage>(body)
            } catch (e: Exception) {
                logger.error("Failed to parse JSON-RPC message", e)
                call.respond(HttpStatusCode.BadRequest, "Invalid JSON-RPC message: ${e.message}")
                return false
            }
            
            // Process the message - this triggers server handlers
            _onMessage(message)
            
            // Wait for response with timeout
            val response = withTimeoutOrNull(30_000) {
                responseDeferred.await()
            }
            
            if (response != null) {
                val responseJson = McpJson.encodeToString(response)
                call.response.header(HttpHeaders.ContentType, ContentType.Application.Json.toString())
                call.respondText(responseJson, ContentType.Application.Json)
                return true
            } else {
                logger.warn("Timeout waiting for response")
                call.respond(HttpStatusCode.GatewayTimeout, "Request timeout")
                return false
            }
        } catch (e: Exception) {
            logger.error("Error handling request", e)
            _onError(e)
            call.respond(HttpStatusCode.InternalServerError, "Internal error: ${e.message}")
            return false
        } finally {
            mutex.withLock {
                pendingResponses.remove(requestId)
                if (currentRequestId == requestId) {
                    currentRequestId = null
                }
            }
        }
    }
}
