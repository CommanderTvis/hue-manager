package io.github.commandertvis.huemanager.mcp

import io.github.commandertvis.huemanager.automation.AutomationManager
import io.github.commandertvis.huemanager.automation.UserState
import io.github.commandertvis.huemanager.hue.HueLightStateUpdate
import io.github.commandertvis.huemanager.hue.HueService
import io.modelcontextprotocol.kotlin.sdk.server.Server
import io.modelcontextprotocol.kotlin.sdk.server.ServerOptions
import io.modelcontextprotocol.kotlin.sdk.types.*
import kotlinx.serialization.json.*
import org.slf4j.LoggerFactory

/**
 * Handler for MCP (Model Context Protocol) requests.
 */
class McpHandler(
    private val hueService: HueService,
    private val automationManager: AutomationManager
) {
    private companion object {
        private val logger = LoggerFactory.getLogger(McpHandler::class.java)
    }

    /**
     * Creates a configured MCP Server instance with lamp control tools and resources.
     */
    fun createServer(): Server {
        val server = Server(
            Implementation(
                name = "hue-manager",
                version = "1.0.0"
            ),
            ServerOptions(
                capabilities = ServerCapabilities(
                    tools = ServerCapabilities.Tools(listChanged = false),
                    resources = ServerCapabilities.Resources(subscribe = false, listChanged = false)
                )
            )
        )

        registerResources(server)
        registerTools(server)
        return server
    }

    private fun registerResources(server: Server) {
        // lamps resource - read-only list of all lamps with their current state
        server.addResource(
            uri = "hue://lamps",
            name = "Philips Hue Lamps",
            description = "List of all Philips Hue lamps with their current state including name, on/off status, brightness, color, and whether they are under automation, manual override, or Hue Sync control.",
            mimeType = "text/plain"
        ) { _ ->
            readLampsResource()
        }
    }

    private fun registerTools(server: Server) {
        // get_lamp_state
        server.addTool(
            name = "get_lamp_state",
            description = "Get detailed state of a specific lamp including its automation status, whether it has a manual override, and if it's controlled by Hue Sync entertainment mode.",
            inputSchema = ToolSchema(
                properties = buildJsonObject {
                    putJsonObject("lamp_id") {
                        put("type", "string")
                        put("description", "The ID of the lamp to get state for")
                    }
                },
                required = listOf("lamp_id")
            )
        ) { request ->
            executeGetLampState(request.arguments)
        }

        // set_lamp_state
        server.addTool(
            name = "set_lamp_state",
            description = "Set the state of a specific lamp. This creates a manual override that lasts for 1 hour. You can control on/off, brightness (0-254), and color via hue (0-65535), saturation (0-254), or color temperature in Mirek (153-500).",
            inputSchema = ToolSchema(
                properties = buildJsonObject {
                    putJsonObject("lamp_id") {
                        put("type", "string")
                        put("description", "The ID of the lamp to control")
                    }
                    putJsonObject("on") {
                        put("type", "boolean")
                        put("description", "Turn the lamp on (true) or off (false)")
                    }
                    putJsonObject("brightness") {
                        put("type", "integer")
                        put("description", "Brightness level from 0 to 254")
                    }
                    putJsonObject("hue") {
                        put("type", "integer")
                        put("description", "Hue value from 0 to 65535 (red=0, green=25500, blue=46920)")
                    }
                    putJsonObject("saturation") {
                        put("type", "integer")
                        put("description", "Color saturation from 0 to 254")
                    }
                    putJsonObject("color_temperature") {
                        put("type", "integer")
                        put("description", "Color temperature in Mirek (153=cold/6500K to 500=warm/2000K)")
                    }
                },
                required = listOf("lamp_id")
            )
        ) { request ->
            executeSetLampState(request.arguments)
        }

        // set_all_lamps
        server.addTool(
            name = "set_all_lamps",
            description = "Set the state of all lamps at once. Useful for 'I left home' / 'I am back' scenarios or setting all lamps to one color. This creates manual overrides for all lamps.",
            inputSchema = ToolSchema(
                properties = buildJsonObject {
                    putJsonObject("on") {
                        put("type", "boolean")
                        put("description", "Turn all lamps on (true) or off (false)")
                    }
                    putJsonObject("brightness") {
                        put("type", "integer")
                        put("description", "Brightness level from 0 to 254")
                    }
                    putJsonObject("hue") {
                        put("type", "integer")
                        put("description", "Hue value from 0 to 65535 (red=0, green=25500, blue=46920)")
                    }
                    putJsonObject("saturation") {
                        put("type", "integer")
                        put("description", "Color saturation from 0 to 254")
                    }
                    putJsonObject("color_temperature") {
                        put("type", "integer")
                        put("description", "Color temperature in Mirek (153=cold/6500K to 500=warm/2000K)")
                    }
                },
                required = listOf("on")
            )
        ) { request ->
            executeSetAllLamps(request.arguments)
        }

        // clear_lamp_override
        server.addTool(
            name = "clear_lamp_override",
            description = "Clear the manual override for a specific lamp, returning it to automation control.",
            inputSchema = ToolSchema(
                properties = buildJsonObject {
                    putJsonObject("lamp_id") {
                        put("type", "string")
                        put("description", "The ID of the lamp to clear override for")
                    }
                },
                required = listOf("lamp_id")
            )
        ) { request ->
            executeClearLampOverride(request.arguments)
        }

        // get_automation_status
        server.addTool(
            name = "get_automation_status",
            description = "Get the current automation status including user state (awake/asleep), current automation mode, target color, and list of overridden lamps.",
            inputSchema = ToolSchema()
        ) { _ ->
            executeGetAutomationStatus()
        }

        // wake_up
        server.addTool(
            name = "wake_up",
            description = "Trigger the 'I woke up!' action. This starts the daylight automation sequence.",
            inputSchema = ToolSchema()
        ) { _ ->
            executeWakeUp()
        }

        // go_to_sleep
        server.addTool(
            name = "go_to_sleep",
            description = "Trigger the 'I'm asleep!' action. This turns off all automated lamps.",
            inputSchema = ToolSchema()
        ) { _ ->
            executeGoToSleep()
        }
    }

    private suspend fun readLampsResource(): ReadResourceResult {
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
                val status = when (id) {
                    in entertainmentLamps -> "hue_sync"
                    in overriddenLamps -> "manual_override"
                    in automatedLamps -> "automation"
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

            ReadResourceResult(
                contents = listOf(TextResourceContents(summary, "hue://lamps", "text/plain"))
            )
        } catch (e: Exception) {
            logger.error("Failed to read lamps resource", e)
            ReadResourceResult(
                contents = listOf(
                    TextResourceContents(
                        "Error reading lamps: ${e.message}",
                        "hue://lamps",
                        "text/plain"
                    )
                )
            )
        }
    }

    private suspend fun executeGetLampState(arguments: JsonObject?): CallToolResult {
        val lampId = arguments?.get("lamp_id")?.jsonPrimitive?.contentOrNull
            ?: return CallToolResult(
                content = listOf(TextContent("Missing required parameter: lamp_id")),
                isError = true
            )

        return try {
            val light = hueService.getLight(lampId)
                ?: return CallToolResult(
                    content = listOf(TextContent("Lamp not found: $lampId")),
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

            CallToolResult(content = listOf(TextContent(info)))
        } catch (e: Exception) {
            logger.error("Failed to get lamp state", e)
            CallToolResult(
                content = listOf(TextContent("Error getting lamp state: ${e.message}")),
                isError = true
            )
        }
    }

    private suspend fun executeSetLampState(arguments: JsonObject?): CallToolResult {
        val lampId = arguments?.get("lamp_id")?.jsonPrimitive?.contentOrNull
            ?: return CallToolResult(
                content = listOf(TextContent("Missing required parameter: lamp_id")),
                isError = true
            )

        return try {
            // Check if lamp is in entertainment mode
            val entertainmentGroups = hueService.getEntertainmentGroups()
            val isInEntertainment = entertainmentGroups.any { (_, group) ->
                group.stream?.active == true && lampId in group.lights
            }

            if (isInEntertainment) {
                return CallToolResult(
                    content = listOf(TextContent("Cannot control lamp '$lampId' - it is currently controlled by Hue Sync entertainment mode.")),
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
                CallToolResult(content = listOf(TextContent(changes)))
            } else {
                CallToolResult(
                    content = listOf(TextContent("Failed to update lamp state")),
                    isError = true
                )
            }
        } catch (e: Exception) {
            logger.error("Failed to set lamp state", e)
            CallToolResult(
                content = listOf(TextContent("Error setting lamp state: ${e.message}")),
                isError = true
            )
        }
    }

    private suspend fun executeSetAllLamps(arguments: JsonObject?): CallToolResult {
        val on = arguments?.get("on")?.jsonPrimitive?.booleanOrNull
            ?: return CallToolResult(
                content = listOf(TextContent("Missing required parameter: on")),
                isError = true
            )

        return try {
            val brightness = arguments["brightness"]?.jsonPrimitive?.intOrNull
            val hue = arguments["hue"]?.jsonPrimitive?.intOrNull
            val saturation = arguments["saturation"]?.jsonPrimitive?.intOrNull
            val colorTemperature = arguments["color_temperature"]?.jsonPrimitive?.intOrNull

            // Add overrides for all lamps
            val allLampIds = hueService.getLights().keys
            allLampIds.forEach { automationManager.addLampOverride(it) }

            val state = HueLightStateUpdate(
                on = on,
                bri = brightness,
                hue = hue,
                sat = saturation,
                ct = colorTemperature
            )

            val success = hueService.setAllLightsState(state)

            if (success) {
                val changes = buildString {
                    val action = if (on) "turned ON" else "turned OFF"
                    append("All lamps $action")
                    brightness?.let { append(" at brightness $it/254") }
                    hue?.let { append(", hue $it (${hueToColorName(it)})") }
                    saturation?.let { append(", saturation $it") }
                    colorTemperature?.let { append(", color temp $it Mirek") }
                    append(". Manual override active for ~1 hour.")
                }
                CallToolResult(content = listOf(TextContent(changes)))
            } else {
                CallToolResult(
                    content = listOf(TextContent("Failed to update all lamps")),
                    isError = true
                )
            }
        } catch (e: Exception) {
            logger.error("Failed to set all lamps", e)
            CallToolResult(
                content = listOf(TextContent("Error setting all lamps: ${e.message}")),
                isError = true
            )
        }
    }

    private suspend fun executeClearLampOverride(arguments: JsonObject?): CallToolResult {
        val lampId = arguments?.get("lamp_id")?.jsonPrimitive?.contentOrNull
            ?: return CallToolResult(
                content = listOf(TextContent("Missing required parameter: lamp_id")),
                isError = true
            )

        return try {
            automationManager.clearLampOverride(lampId)
            CallToolResult(
                content = listOf(TextContent("Override cleared for lamp '$lampId'. It is now back under automation control."))
            )
        } catch (e: Exception) {
            logger.error("Failed to clear lamp override", e)
            CallToolResult(
                content = listOf(TextContent("Error clearing override: ${e.message}")),
                isError = true
            )
        }
    }

    private suspend fun executeGetAutomationStatus(): CallToolResult = try {
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

        CallToolResult(content = listOf(TextContent(info)))
    } catch (e: Exception) {
        logger.error("Failed to get automation status", e)
        CallToolResult(
            content = listOf(TextContent("Error getting automation status: ${e.message}")),
            isError = true
        )
    }

    private suspend fun executeWakeUp(): CallToolResult {
        return try {
            val state = automationManager.wakeUp()
            val stateStr = if (state == UserState.AWAKE) "AWAKE" else "ASLEEP"
            CallToolResult(
                content = listOf(TextContent("Wake-up triggered! User state is now: $stateStr. Daylight automation sequence started."))
            )
        } catch (e: Exception) {
            logger.error("Failed to trigger wake-up", e)
            CallToolResult(
                content = listOf(TextContent("Error triggering wake-up: ${e.message}")),
                isError = true
            )
        }
    }

    private suspend fun executeGoToSleep(): CallToolResult {
        return try {
            val state = automationManager.goToSleep()
            val stateStr = if (state == UserState.AWAKE) "AWAKE" else "ASLEEP"
            CallToolResult(
                content = listOf(TextContent("Sleep triggered! User state is now: $stateStr. All automated lamps are turning off."))
            )
        } catch (e: Exception) {
            logger.error("Failed to trigger sleep", e)
            CallToolResult(
                content = listOf(TextContent("Error triggering sleep: ${e.message}")),
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
}
