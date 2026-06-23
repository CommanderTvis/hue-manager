package io.github.commandertvis.huemanager.mcp

import io.github.commandertvis.huemanager.automation.AutomationManager
import io.github.commandertvis.huemanager.automation.UserState
import io.github.commandertvis.huemanager.hue.HueLightStateUpdate
import io.github.commandertvis.huemanager.hue.HueService
import io.github.commandertvis.huemanager.hue.LampStateCache
import io.quarkiverse.mcp.server.Resource
import io.quarkiverse.mcp.server.Tool
import io.quarkiverse.mcp.server.ToolArg
import jakarta.enterprise.context.ApplicationScoped
import jakarta.inject.Inject
import kotlinx.coroutines.runBlocking
import org.jboss.logging.Logger

/**
 * MCP (Model Context Protocol) server exposing Hue lamp control as tools and resources.
 *
 * Tools and the `hue://lamps` resource are scanned by the quarkus-mcp-server extension,
 * which handles the SSE transport. OAuth resource-server protection of `/mcp` is configured
 * separately via quarkus-oidc.
 */
@ApplicationScoped
class McpServer @Inject constructor(
    private val hueService: HueService,
    private val automationManager: AutomationManager,
    private val lampStateCache: LampStateCache
) {
    private companion object {
        private val logger: Logger = Logger.getLogger(McpServer::class.java)
    }

    @Resource(
        uri = "hue://lamps",
        name = "Philips Hue Lamps",
        description = "List of all Philips Hue lamps with their current state including name, on/off status, brightness, color, and whether they are under automation, manual override, or Hue Sync control.",
        mimeType = "text/plain"
    )
    fun lamps(): String = try {
        val lights = lampStateCache.getLights()
        val entertainmentGroups = lampStateCache.getEntertainmentGroups()
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

        "Found ${lights.size} lamps:\n\n${lampsInfo.joinToString("\n")}"
    } catch (e: Exception) {
        logger.error("Failed to read lamps resource", e)
        "Error reading lamps: ${e.message}"
    }

    @Tool(
        name = "get_lamp_state",
        description = "Get detailed state of a specific lamp including its automation status, whether it has a manual override, and if it's controlled by Hue Sync entertainment mode."
    )
    fun getLampState(
        @ToolArg(name = "lamp_id", description = "The ID of the lamp to get state for") lampId: String
    ): String = try {
        val light = lampStateCache.getLight(lampId)
        if (light == null) {
            "Lamp not found: $lampId"
        } else {
            val entertainmentGroups = lampStateCache.getEntertainmentGroups()
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

            buildString {
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
        }
    } catch (e: Exception) {
        logger.error("Failed to get lamp state", e)
        "Error getting lamp state: ${e.message}"
    }

    @Tool(
        name = "set_lamp_state",
        description = "Set the state of a specific lamp. This creates a manual override that lasts for 1 hour. You can control on/off, brightness (0-254), and color via hue (0-65535), saturation (0-254), or color temperature in Mirek (153-500)."
    )
    fun setLampState(
        @ToolArg(name = "lamp_id", description = "The ID of the lamp to control") lampId: String,
        @ToolArg(description = "Turn the lamp on (true) or off (false)", required = false) on: Boolean? = null,
        @ToolArg(description = "Brightness level from 0 to 254", required = false) brightness: Int? = null,
        @ToolArg(description = "Hue value from 0 to 65535 (red=0, green=25500, blue=46920)", required = false) hue: Int? = null,
        @ToolArg(description = "Color saturation from 0 to 254", required = false) saturation: Int? = null,
        @ToolArg(
            name = "color_temperature",
            description = "Color temperature in Mirek (153=cold/6500K to 500=warm/2000K)",
            required = false
        ) colorTemperature: Int? = null
    ): String = runBlocking {
      try {
        val entertainmentGroups = lampStateCache.getEntertainmentGroups()
        val isInEntertainment = entertainmentGroups.any { (_, group) ->
            group.stream?.active == true && lampId in group.lights
        }

        if (isInEntertainment) {
            "Cannot control lamp '$lampId' - it is currently controlled by Hue Sync entertainment mode."
        } else {
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
                buildString {
                    append("Lamp '$lampId' updated successfully:\n")
                    on?.let { append("  Power: ${if (it) "ON" else "OFF"}\n") }
                    brightness?.let { append("  Brightness: $it/254\n") }
                    hue?.let { append("  Hue: $it\n") }
                    saturation?.let { append("  Saturation: $it\n") }
                    colorTemperature?.let { append("  Color Temperature: $it Mirek\n") }
                    append("\nManual override active for ~1 hour.")
                }
            } else {
                "Failed to update lamp state"
            }
        }
      } catch (e: Exception) {
        logger.error("Failed to set lamp state", e)
        "Error setting lamp state: ${e.message}"
      }
    }

    @Tool(
        name = "set_all_lamps",
        description = "Set the state of all lamps at once. Useful for 'I left home' / 'I am back' scenarios or setting all lamps to one color. This creates manual overrides for all lamps."
    )
    fun setAllLamps(
        @ToolArg(description = "Turn all lamps on (true) or off (false)") on: Boolean,
        @ToolArg(description = "Brightness level from 0 to 254", required = false) brightness: Int? = null,
        @ToolArg(description = "Hue value from 0 to 65535 (red=0, green=25500, blue=46920)", required = false) hue: Int? = null,
        @ToolArg(description = "Color saturation from 0 to 254", required = false) saturation: Int? = null,
        @ToolArg(
            name = "color_temperature",
            description = "Color temperature in Mirek (153=cold/6500K to 500=warm/2000K)",
            required = false
        ) colorTemperature: Int? = null
    ): String = runBlocking {
      try {
        val allLampIds = lampStateCache.getLights().keys
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
            buildString {
                val action = if (on) "turned ON" else "turned OFF"
                append("All lamps $action")
                brightness?.let { append(" at brightness $it/254") }
                hue?.let { append(", hue $it (${hueToColorName(it)})") }
                saturation?.let { append(", saturation $it") }
                colorTemperature?.let { append(", color temp $it Mirek") }
                append(". Manual override active for ~1 hour.")
            }
        } else {
            "Failed to update all lamps"
        }
      } catch (e: Exception) {
        logger.error("Failed to set all lamps", e)
        "Error setting all lamps: ${e.message}"
      }
    }

    @Tool(
        name = "clear_lamp_override",
        description = "Clear the manual override for a specific lamp, returning it to automation control."
    )
    fun clearLampOverride(
        @ToolArg(name = "lamp_id", description = "The ID of the lamp to clear override for") lampId: String
    ): String = runBlocking {
      try {
        automationManager.clearLampOverride(lampId)
        "Override cleared for lamp '$lampId'. It is now back under automation control."
      } catch (e: Exception) {
        logger.error("Failed to clear lamp override", e)
        "Error clearing override: ${e.message}"
      }
    }

    @Tool(
        name = "get_automation_status",
        description = "Get the current automation status including user state (awake/asleep), current automation mode, target color, and list of overridden lamps."
    )
    fun getAutomationStatus(): String = try {
        val userState = automationManager.getUserState()
        val mode = automationManager.getCurrentAutomationMode()
        val color = automationManager.getAutomationColor()
        val overriddenLamps = automationManager.getOverriddenLampIds()
        val entertainmentActive = automationManager.isEntertainmentActive()

        buildString {
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
            append("Evening time: ${automationManager.getPseudoSunset()}\n")
        }
    } catch (e: Exception) {
        logger.error("Failed to get automation status", e)
        "Error getting automation status: ${e.message}"
    }

    @Tool(
        name = "wake_up",
        description = "Trigger the 'I woke up!' action. This starts the daylight automation sequence."
    )
    fun wakeUp(): String = runBlocking {
      try {
        val state = automationManager.wakeUp()
        val stateStr = if (state == UserState.AWAKE) "AWAKE" else "ASLEEP"
        "Wake-up triggered! User state is now: $stateStr. Daylight automation sequence started."
      } catch (e: Exception) {
        logger.error("Failed to trigger wake-up", e)
        "Error triggering wake-up: ${e.message}"
      }
    }

    @Tool(
        name = "go_to_sleep",
        description = "Trigger the 'Lamps off' action. This clears all manual overrides and turns off all lamps. Use when going to sleep or leaving home."
    )
    fun goToSleep(): String = runBlocking {
      try {
        val state = automationManager.goToSleep()
        val stateStr = if (state == UserState.AWAKE) "AWAKE" else "ASLEEP"
        "Lamps off triggered! User state is now: $stateStr. All manual overrides cleared and all lamps are turning off."
      } catch (e: Exception) {
        logger.error("Failed to trigger sleep", e)
        "Error triggering sleep: ${e.message}"
      }
    }

    private fun hueToColorName(hue: Int): String = when {
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
