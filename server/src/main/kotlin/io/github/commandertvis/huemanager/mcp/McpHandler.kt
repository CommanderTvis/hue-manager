package io.github.commandertvis.huemanager.mcp

import io.github.commandertvis.huemanager.automation.AutomationManager
import io.github.commandertvis.huemanager.automation.UserState
import io.github.commandertvis.huemanager.hue.HueLightStateUpdate
import io.github.commandertvis.huemanager.hue.HueService
import kotlinx.serialization.json.*
import org.slf4j.LoggerFactory

/**
 * Handler for MCP (Model Context Protocol) requests.
 * Implements JSON-RPC 2.0 over HTTP with SSE support.
 */
class McpHandler(
    private val hueService: HueService,
    private val automationManager: AutomationManager,
    private val password: String
) {
    private companion object {
        private val logger = LoggerFactory.getLogger(McpHandler::class.java)

        private val json = Json {
            ignoreUnknownKeys = true
            encodeDefaults = false
            explicitNulls = true
        }
    }

    // Track initialized sessions
    private val initializedSessions = mutableSetOf<String>()

    /**
     * Available MCP tools for lamp control
     */
    private val tools = listOf(
        Tool(
            name = "list_lamps",
            description = "List all Philips Hue lamps with their current state including name, on/off status, brightness, color, and whether they are under automation, manual override, or Hue Sync control.",
            inputSchema = ToolInputSchema(
                type = "object",
                properties = emptyMap(),
                required = emptyList()
            )
        ),
        Tool(
            name = "get_lamp_state",
            description = "Get detailed state of a specific lamp including its automation status, whether it has a manual override, and if it's controlled by Hue Sync entertainment mode.",
            inputSchema = ToolInputSchema(
                type = "object",
                properties = mapOf(
                    "lamp_id" to PropertySchema(
                        type = "string",
                        description = "The ID of the lamp to get state for"
                    )
                ),
                required = listOf("lamp_id")
            )
        ),
        Tool(
            name = "set_lamp_state",
            description = "Set the state of a specific lamp. This creates a manual override that lasts for 1 hour. You can control on/off, brightness (0-254), and color via hue (0-65535), saturation (0-254), or color temperature in Mirek (153-500).",
            inputSchema = ToolInputSchema(
                type = "object",
                properties = mapOf(
                    "lamp_id" to PropertySchema(
                        type = "string",
                        description = "The ID of the lamp to control"
                    ),
                    "on" to PropertySchema(
                        type = "boolean",
                        description = "Turn the lamp on (true) or off (false)"
                    ),
                    "brightness" to PropertySchema(
                        type = "integer",
                        description = "Brightness level from 0 to 254"
                    ),
                    "hue" to PropertySchema(
                        type = "integer",
                        description = "Hue value from 0 to 65535 (red=0, green=25500, blue=46920)"
                    ),
                    "saturation" to PropertySchema(
                        type = "integer",
                        description = "Color saturation from 0 to 254"
                    ),
                    "color_temperature" to PropertySchema(
                        type = "integer",
                        description = "Color temperature in Mirek (153=cold/6500K to 500=warm/2000K)"
                    )
                ),
                required = listOf("lamp_id")
            )
        ),
        Tool(
            name = "set_all_lamps",
            description = "Set the state of all lamps at once. Useful for 'I left home' / 'I am back' scenarios. This creates manual overrides for all lamps.",
            inputSchema = ToolInputSchema(
                type = "object",
                properties = mapOf(
                    "on" to PropertySchema(
                        type = "boolean",
                        description = "Turn all lamps on (true) or off (false)"
                    ),
                    "brightness" to PropertySchema(
                        type = "integer",
                        description = "Brightness level from 0 to 254"
                    )
                ),
                required = listOf("on")
            )
        ),
        Tool(
            name = "clear_lamp_override",
            description = "Clear the manual override for a specific lamp, returning it to automation control.",
            inputSchema = ToolInputSchema(
                type = "object",
                properties = mapOf(
                    "lamp_id" to PropertySchema(
                        type = "string",
                        description = "The ID of the lamp to clear override for"
                    )
                ),
                required = listOf("lamp_id")
            )
        ),
        Tool(
            name = "get_automation_status",
            description = "Get the current automation status including user state (awake/asleep), current automation mode, target color, and list of overridden lamps.",
            inputSchema = ToolInputSchema(
                type = "object",
                properties = emptyMap(),
                required = emptyList()
            )
        ),
        Tool(
            name = "wake_up",
            description = "Trigger the 'I woke up!' action. This starts the daylight automation sequence.",
            inputSchema = ToolInputSchema(
                type = "object",
                properties = emptyMap(),
                required = emptyList()
            )
        ),
        Tool(
            name = "go_to_sleep",
            description = "Trigger the 'I'm asleep!' action. This turns off all automated lamps.",
            inputSchema = ToolInputSchema(
                type = "object",
                properties = emptyMap(),
                required = emptyList()
            )
        )
    )

    /**
     * Process a JSON-RPC request and return the response
     */
    suspend fun handleRequest(requestBody: String, sessionToken: String?): JsonRpcResponse {
        val request = try {
            json.decodeFromString<JsonRpcRequest>(requestBody)
        } catch (e: Exception) {
            logger.warn("Failed to parse JSON-RPC request: ${e.message}")
            return JsonRpcResponse(
                id = JsonNull,
                error = JsonRpcError(
                    code = JsonRpcErrorCodes.PARSE_ERROR,
                    message = "Failed to parse JSON-RPC request: ${e.message}"
                )
            )
        }

        logger.debug("MCP request: method={}, id={}", request.method, request.id)

        return when (request.method) {
            "initialize" -> handleInitialize(request)
            "initialized" -> handleInitialized(request, sessionToken)
            "tools/list" -> handleToolsList(request, sessionToken)
            "tools/call" -> handleToolCall(request, sessionToken)
            "ping" -> handlePing(request)
            else -> {
                logger.warn("Unknown MCP method: ${request.method}")
                JsonRpcResponse(
                    id = request.id,
                    error = JsonRpcError(
                        code = JsonRpcErrorCodes.METHOD_NOT_FOUND,
                        message = "Method not found: ${request.method}"
                    )
                )
            }
        }
    }

    private fun handleInitialize(request: JsonRpcRequest): JsonRpcResponse {
        val result = InitializeResult(
            protocolVersion = MCP_PROTOCOL_VERSION,
            capabilities = ServerCapabilities(
                tools = ToolsCapability(listChanged = false)
            ),
            serverInfo = ServerInfo(
                name = "hue-manager",
                version = "1.0.0"
            )
        )

        return JsonRpcResponse(
            id = request.id,
            result = json.encodeToJsonElement(result)
        )
    }

    private fun handleInitialized(request: JsonRpcRequest, sessionToken: String?): JsonRpcResponse {
        // Mark this session as initialized
        sessionToken?.let { initializedSessions.add(it) }
        
        // This is a notification, no response needed but we return empty success
        return JsonRpcResponse(
            id = request.id,
            result = JsonObject(emptyMap())
        )
    }

    private fun handlePing(request: JsonRpcRequest): JsonRpcResponse {
        return JsonRpcResponse(
            id = request.id,
            result = JsonObject(emptyMap())
        )
    }

    private fun validatePassword(providedPassword: String?): Boolean {
        return providedPassword != null && providedPassword == password
    }

    private fun handleToolsList(request: JsonRpcRequest, providedPassword: String?): JsonRpcResponse {
        // Check authentication for tool listing
        if (!validatePassword(providedPassword)) {
            return JsonRpcResponse(
                id = request.id,
                error = JsonRpcError(
                    code = JsonRpcErrorCodes.INVALID_REQUEST,
                    message = "Authentication required. Please provide your password in the Authorization header (Bearer <PASSWORD>)."
                )
            )
        }

        val result = ToolsListResult(tools = tools)
        return JsonRpcResponse(
            id = request.id,
            result = json.encodeToJsonElement(result)
        )
    }

    private suspend fun handleToolCall(request: JsonRpcRequest, providedPassword: String?): JsonRpcResponse {
        // Check authentication
        if (!validatePassword(providedPassword)) {
            return JsonRpcResponse(
                id = request.id,
                error = JsonRpcError(
                    code = JsonRpcErrorCodes.INVALID_REQUEST,
                    message = "Authentication required. Please provide your password in the Authorization header (Bearer <PASSWORD>)."
                )
            )
        }

        val params = try {
            request.params?.let { json.decodeFromJsonElement<ToolCallParams>(it) }
        } catch (e: Exception) {
            return JsonRpcResponse(
                id = request.id,
                error = JsonRpcError(
                    code = JsonRpcErrorCodes.INVALID_PARAMS,
                    message = "Invalid tool call params: ${e.message}"
                )
            )
        }

        if (params == null) {
            return JsonRpcResponse(
                id = request.id,
                error = JsonRpcError(
                    code = JsonRpcErrorCodes.INVALID_PARAMS,
                    message = "Missing tool call params"
                )
            )
        }

        logger.info("MCP tool call: ${params.name}")

        val result = when (params.name) {
            "list_lamps" -> executeListLamps()
            "get_lamp_state" -> executeGetLampState(params.arguments)
            "set_lamp_state" -> executeSetLampState(params.arguments)
            "set_all_lamps" -> executeSetAllLamps(params.arguments)
            "clear_lamp_override" -> executeClearLampOverride(params.arguments)
            "get_automation_status" -> executeGetAutomationStatus()
            "wake_up" -> executeWakeUp()
            "go_to_sleep" -> executeGoToSleep()
            else -> ToolCallResult(
                content = listOf(ToolContent.Text(text = "Unknown tool: ${params.name}")),
                isError = true
            )
        }

        return JsonRpcResponse(
            id = request.id,
            result = json.encodeToJsonElement(result)
        )
    }

    private suspend fun executeListLamps(): ToolCallResult {
        return try {
            val lights = hueService.getLights()
            val entertainmentGroups = hueService.getEntertainmentGroups()
            val entertainmentLamps = mutableSetOf<String>()
            
            for ((_, group) in entertainmentGroups) {
                if (group.stream?.active == true) {
                    entertainmentLamps.addAll(group.lights)
                }
            }
            
            val overriddenLamps = automationManager.getOverriddenLampIds()
            val automatedLamps = automationManager.getAutomatedLampIds()

            val lampsInfo = lights.map { (id, light) ->
                val status = when {
                    id in entertainmentLamps -> "hue_sync"
                    id in overriddenLamps -> "manual_override"
                    id in automatedLamps -> "automation"
                    else -> "unmanaged"
                }
                
                buildString {
                    append("- ${light.name} (ID: $id)\n")
                    append("  Status: $status\n")
                    append("  On: ${light.state.on}\n")
                    append("  Brightness: ${light.state.bri}/254\n")
                    light.state.hue?.let { append("  Hue: $it\n") }
                    light.state.sat?.let { append("  Saturation: $it\n") }
                    light.state.ct?.let { append("  Color Temperature: $it Mirek\n") }
                    append("  Color Mode: ${light.state.colormode ?: "unknown"}\n")
                    append("  Reachable: ${light.state.reachable ?: "unknown"}\n")
                }
            }

            val summary = "Found ${lights.size} lamps:\n\n${lampsInfo.joinToString("\n")}"
            
            ToolCallResult(
                content = listOf(ToolContent.Text(text = summary))
            )
        } catch (e: Exception) {
            logger.error("Failed to list lamps", e)
            ToolCallResult(
                content = listOf(ToolContent.Text(text = "Error listing lamps: ${e.message}")),
                isError = true
            )
        }
    }

    private suspend fun executeGetLampState(arguments: JsonObject?): ToolCallResult {
        val lampId = arguments?.get("lamp_id")?.jsonPrimitive?.contentOrNull
            ?: return ToolCallResult(
                content = listOf(ToolContent.Text(text = "Missing required parameter: lamp_id")),
                isError = true
            )

        return try {
            val light = hueService.getLight(lampId)
                ?: return ToolCallResult(
                    content = listOf(ToolContent.Text(text = "Lamp not found: $lampId")),
                    isError = true
                )

            val entertainmentGroups = hueService.getEntertainmentGroups()
            val isInEntertainment = entertainmentGroups.any { (_, group) ->
                group.stream?.active == true && lampId in group.lights
            }

            val isOverridden = lampId in automationManager.getOverriddenLampIds()
            val isAutomated = lampId in automationManager.getAutomatedLampIds()

            val status = when {
                isInEntertainment -> "Controlled by Hue Sync (entertainment mode active)"
                isOverridden -> "Manual override active (will expire in ~1 hour)"
                isAutomated -> "Under automation control"
                else -> "Unmanaged (not part of automation)"
            }

            val info = buildString {
                append("Lamp: ${light.name} (ID: $lampId)\n\n")
                append("Control Status: $status\n\n")
                append("Current State:\n")
                append("  Power: ${if (light.state.on) "ON" else "OFF"}\n")
                val brightnessPercent = light.state.bri?.let { it * 100 / 254 } ?: 0
                append("  Brightness: ${light.state.bri ?: 0}/254 ($brightnessPercent%)\n")
                append("  Color Mode: ${light.state.colormode ?: "unknown"}\n")
                light.state.hue?.let { 
                    append("  Hue: $it (${hueToColorName(it)})\n") 
                }
                light.state.sat?.let { append("  Saturation: $it/254\n") }
                light.state.ct?.let { 
                    val kelvin = 1000000 / it
                    append("  Color Temperature: $it Mirek (~${kelvin}K)\n") 
                }
                append("  Reachable: ${light.state.reachable ?: "unknown"}\n")
                append("  Type: ${light.type}\n")
            }

            ToolCallResult(
                content = listOf(ToolContent.Text(text = info))
            )
        } catch (e: Exception) {
            logger.error("Failed to get lamp state", e)
            ToolCallResult(
                content = listOf(ToolContent.Text(text = "Error getting lamp state: ${e.message}")),
                isError = true
            )
        }
    }

    private suspend fun executeSetLampState(arguments: JsonObject?): ToolCallResult {
        val lampId = arguments?.get("lamp_id")?.jsonPrimitive?.contentOrNull
            ?: return ToolCallResult(
                content = listOf(ToolContent.Text(text = "Missing required parameter: lamp_id")),
                isError = true
            )

        return try {
            // Check if lamp is in entertainment mode
            val entertainmentGroups = hueService.getEntertainmentGroups()
            val isInEntertainment = entertainmentGroups.any { (_, group) ->
                group.stream?.active == true && lampId in group.lights
            }

            if (isInEntertainment) {
                return ToolCallResult(
                    content = listOf(ToolContent.Text(text = "Cannot control lamp '$lampId' - it is currently controlled by Hue Sync entertainment mode.")),
                    isError = true
                )
            }

            val on = arguments["on"]?.jsonPrimitive?.booleanOrNull
            val brightness = arguments["brightness"]?.jsonPrimitive?.intOrNull
            val hue = arguments["hue"]?.jsonPrimitive?.intOrNull
            val saturation = arguments["saturation"]?.jsonPrimitive?.intOrNull
            val colorTemperature = arguments["color_temperature"]?.jsonPrimitive?.intOrNull

            // Add override for manual control
            automationManager.addLampOverride(lampId)

            val state = HueLightStateUpdate(
                on = on,
                bri = brightness,
                hue = hue,
                sat = saturation,
                ct = colorTemperature
            )

            val success = hueService.setLightState(lampId, state)
            
            if (success) {
                val changes = buildString {
                    append("Lamp '$lampId' updated successfully:\n")
                    on?.let { append("  Power: ${if (it) "ON" else "OFF"}\n") }
                    brightness?.let { append("  Brightness: $it/254\n") }
                    hue?.let { append("  Hue: $it\n") }
                    saturation?.let { append("  Saturation: $it\n") }
                    colorTemperature?.let { append("  Color Temperature: $it Mirek\n") }
                    append("\nManual override active for ~1 hour.")
                }
                ToolCallResult(content = listOf(ToolContent.Text(text = changes)))
            } else {
                ToolCallResult(
                    content = listOf(ToolContent.Text(text = "Failed to update lamp state")),
                    isError = true
                )
            }
        } catch (e: Exception) {
            logger.error("Failed to set lamp state", e)
            ToolCallResult(
                content = listOf(ToolContent.Text(text = "Error setting lamp state: ${e.message}")),
                isError = true
            )
        }
    }

    private suspend fun executeSetAllLamps(arguments: JsonObject?): ToolCallResult {
        val on = arguments?.get("on")?.jsonPrimitive?.booleanOrNull
            ?: return ToolCallResult(
                content = listOf(ToolContent.Text(text = "Missing required parameter: on")),
                isError = true
            )

        return try {
            val brightness = arguments["brightness"]?.jsonPrimitive?.intOrNull

            // Add overrides for all lamps
            val allLampIds = hueService.getLights().keys
            allLampIds.forEach { automationManager.addLampOverride(it) }

            val state = HueLightStateUpdate(
                on = on,
                bri = brightness
            )

            val success = hueService.setAllLightsState(state)
            
            if (success) {
                val action = if (on) "turned ON" else "turned OFF"
                val brightnessInfo = brightness?.let { " at brightness $it/254" } ?: ""
                ToolCallResult(
                    content = listOf(ToolContent.Text(text = "All lamps ${action}${brightnessInfo}. Manual override active for ~1 hour."))
                )
            } else {
                ToolCallResult(
                    content = listOf(ToolContent.Text(text = "Failed to update all lamps")),
                    isError = true
                )
            }
        } catch (e: Exception) {
            logger.error("Failed to set all lamps", e)
            ToolCallResult(
                content = listOf(ToolContent.Text(text = "Error setting all lamps: ${e.message}")),
                isError = true
            )
        }
    }

    private suspend fun executeClearLampOverride(arguments: JsonObject?): ToolCallResult {
        val lampId = arguments?.get("lamp_id")?.jsonPrimitive?.contentOrNull
            ?: return ToolCallResult(
                content = listOf(ToolContent.Text(text = "Missing required parameter: lamp_id")),
                isError = true
            )

        return try {
            automationManager.clearLampOverride(lampId)
            ToolCallResult(
                content = listOf(ToolContent.Text(text = "Override cleared for lamp '$lampId'. It is now back under automation control."))
            )
        } catch (e: Exception) {
            logger.error("Failed to clear lamp override", e)
            ToolCallResult(
                content = listOf(ToolContent.Text(text = "Error clearing override: ${e.message}")),
                isError = true
            )
        }
    }

    private suspend fun executeGetAutomationStatus(): ToolCallResult {
        return try {
            val userState = automationManager.getUserState()
            val mode = automationManager.getCurrentAutomationMode()
            val color = automationManager.getAutomationColor()
            val overriddenLamps = automationManager.getOverriddenLampIds()
            val entertainmentActive = automationManager.isEntertainmentActive()

            val info = buildString {
                append("Automation Status\n")
                append("=================\n\n")
                append("User State: ${if (userState == UserState.AWAKE) "AWAKE" else "ASLEEP"}\n")
                append("Automation Mode: ${mode.name}\n")
                append("Entertainment (Hue Sync) Active: $entertainmentActive\n\n")
                
                append("Target Color:\n")
                append("  ${color.description}\n")
                append("  Brightness: ${color.brightness}/254\n")
                color.hue?.let { append("  Hue: $it\n") }
                color.saturation?.let { append("  Saturation: $it\n") }
                color.colorTemperature?.let { append("  Color Temperature: $it Mirek\n") }
                
                append("\nOverridden Lamps: ")
                if (overriddenLamps.isEmpty()) {
                    append("None\n")
                } else {
                    append("${overriddenLamps.joinToString(", ")}\n")
                }

                automationManager.getWakeUpTime()?.let {
                    append("\nWake-up Time: $it\n")
                }
                append("Pseudo-sunset: ${automationManager.getPseudoSunset()}\n")
            }

            ToolCallResult(
                content = listOf(ToolContent.Text(text = info))
            )
        } catch (e: Exception) {
            logger.error("Failed to get automation status", e)
            ToolCallResult(
                content = listOf(ToolContent.Text(text = "Error getting automation status: ${e.message}")),
                isError = true
            )
        }
    }

    private suspend fun executeWakeUp(): ToolCallResult {
        return try {
            val state = automationManager.wakeUp()
            val stateStr = if (state == UserState.AWAKE) "AWAKE" else "ASLEEP"
            ToolCallResult(
                content = listOf(ToolContent.Text(text = "Wake-up triggered! User state is now: $stateStr. Daylight automation sequence started."))
            )
        } catch (e: Exception) {
            logger.error("Failed to trigger wake-up", e)
            ToolCallResult(
                content = listOf(ToolContent.Text(text = "Error triggering wake-up: ${e.message}")),
                isError = true
            )
        }
    }

    private suspend fun executeGoToSleep(): ToolCallResult {
        return try {
            val state = automationManager.goToSleep()
            val stateStr = if (state == UserState.AWAKE) "AWAKE" else "ASLEEP"
            ToolCallResult(
                content = listOf(ToolContent.Text(text = "Sleep triggered! User state is now: $stateStr. All automated lamps are turning off."))
            )
        } catch (e: Exception) {
            logger.error("Failed to trigger sleep", e)
            ToolCallResult(
                content = listOf(ToolContent.Text(text = "Error triggering sleep: ${e.message}")),
                isError = true
            )
        }
    }

    private fun hueToColorName(hue: Int): String {
        return when {
            hue < 5500 -> "red"
            hue < 11000 -> "orange"
            hue < 18000 -> "yellow"
            hue < 25500 -> "green"
            hue < 36000 -> "cyan"
            hue < 46920 -> "blue"
            hue < 54000 -> "purple"
            else -> "magenta/pink"
        }
    }

    /**
     * Serialize response to JSON string.
     * Manually constructs JSON to ensure proper JSON-RPC 2.0 compliance:
     * - Success responses have "result" but NOT "error"
     * - Error responses have "error" but NOT "result"
     * - The "id" field MUST always be present (required by MCP SDK)
     */
    fun serializeResponse(response: JsonRpcResponse): String = buildJsonObject {
        put("jsonrpc", response.jsonrpc)
        // Always include id field - MCP SDK requires it to be present (not just non-null)
        put("id", response.id ?: JsonNull)
        // Only include result OR error, never both
        if (response.error != null) {
            put("error", json.encodeToJsonElement(response.error))
        } else {
            // For successful responses, always include result (even if empty)
            put("result", response.result ?: JsonObject(emptyMap()))
        }
    }.toString()
}
