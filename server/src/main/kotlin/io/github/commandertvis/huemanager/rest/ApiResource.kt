package io.github.commandertvis.huemanager.rest

import io.github.commandertvis.huemanager.api.*
import io.github.commandertvis.huemanager.automation.AutomationManager
import io.github.commandertvis.huemanager.automation.ModeColorConfig
import io.github.commandertvis.huemanager.automation.UserState
import io.github.commandertvis.huemanager.hue.HueLightStateUpdate
import io.github.commandertvis.huemanager.hue.HueService
import io.github.commandertvis.huemanager.hue.LampStateCache
import io.github.commandertvis.huemanager.models.*
import jakarta.inject.Inject
import jakarta.ws.rs.*
import jakarta.ws.rs.core.Context
import jakarta.ws.rs.core.HttpHeaders
import jakarta.ws.rs.core.MediaType
import jakarta.ws.rs.core.Response
import io.github.commandertvis.huemanager.models.UserState as ModelUserState

/**
 * REST API ported 1:1 from the Ktor `apiRoutes` block. Read endpoints serve from
 * [LampStateCache]; writes go through [HueService] and create automation overrides.
 * Protected endpoints delegate to [AuthVerifier] (session-JWT bearer check).
 *
 * OAuth callback / authorize / link live in [HueOAuthResource] (they return HTML and
 * need raw header access for redirect-URI resolution).
 */
@Path("/api")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
class ApiResource @Inject constructor(
    private val hueService: HueService,
    private val automationManager: AutomationManager,
    private val lampStateCache: LampStateCache,
    private val authVerifier: AuthVerifier,
) {
    // --- Sync (lightweight, no Hue API calls) ---

    @GET
    @Path("/sync")
    fun sync(): SyncResponse {
        val color = automationManager.getAutomationColor()
        return SyncResponse(
            version = automationManager.getSyncVersion(),
            userState = automationManager.getUserState().toModel(),
            automationMode = automationManager.getCurrentAutomationMode().name,
            automationColor = AutomationColorInfo(
                hue = color.hue,
                saturation = color.saturation,
                colorTemperature = color.colorTemperature,
                brightness = color.brightness,
                description = color.description
            ),
            overriddenLamps = automationManager.getOverriddenLampIds(),
            pendingLampIds = automationManager.getPendingLampIds(),
            entertainmentActive = automationManager.isEntertainmentActive()
        )
    }

    @POST
    @Path("/sync/pending")
    fun addPending(request: PendingOperationRequest): ApiSuccess {
        automationManager.addPendingOperations(request.lampIds)
        return ApiSuccess("Pending operations added")
    }

    @DELETE
    @Path("/sync/pending")
    fun clearPending(request: PendingOperationRequest): ApiSuccess {
        automationManager.clearPendingOperations(request.lampIds)
        return ApiSuccess("Pending operations cleared")
    }

    // --- Status ---

    @GET
    @Path("/status")
    fun status(): StatusResponse = StatusResponse(
        connected = hueService.isConnected,
        bridgeIp = null, // Remote API doesn't expose bridge IP
        needsLinking = hueService.needsLinking,
        needsReauthorization = hueService.needsReauthorization,
        automationState = automationManager.getUserState().toModel(),
        entertainmentActive = automationManager.isEntertainmentActive()
    )

    // --- Lamps ---

    @GET
    @Path("/lamps")
    fun lamps(): LampsResponse {
        val entertainmentLamps = entertainmentLampIds()
        val lamps = lampStateCache.getLights().map { (id, light) ->
            light.toLamp(id, id in entertainmentLamps)
        }
        return LampsResponse(lamps)
    }

    @GET
    @Path("/lamps/{id}")
    fun lamp(@PathParam("id") id: String): Lamp {
        val light = lampStateCache.getLight(id) ?: apiError(Response.Status.NOT_FOUND, "Lamp not found")
        return light.toLamp(id, id in entertainmentLampIds())
    }

    @PUT
    @Path("/lamps/{id}")
    suspend fun updateLamp(
        @PathParam("id") id: String,
        request: LampUpdateRequest,
        @Context headers: HttpHeaders,
    ): ApiSuccess {
        authVerifier.requireAuth(headers)

        // Manual control for 1 hour
        automationManager.addLampOverride(id)

        val state = HueLightStateUpdate(
            on = request.on,
            bri = request.brightness,
            hue = request.hue,
            sat = request.saturation,
            ct = request.colorTemperature,
            transitiontime = request.transitionTime
        )
        if (!hueService.setLightState(id, state)) {
            apiError(Response.Status.INTERNAL_SERVER_ERROR, "Failed to update lamp")
        }
        return ApiSuccess("Lamp updated")
    }

    @PUT
    @Path("/lamps/all")
    suspend fun updateAllLamps(
        request: AllLampsUpdateRequest,
        @Context headers: HttpHeaders,
    ): ApiSuccess {
        authVerifier.requireAuth(headers)
        val state = HueLightStateUpdate(on = request.on, bri = request.brightness)
        if (!hueService.setAllLightsState(state)) {
            apiError(Response.Status.INTERNAL_SERVER_ERROR, "Failed to update lamps")
        }
        return ApiSuccess("All lamps updated")
    }

    @DELETE
    @Path("/lamps/{id}/override")
    suspend fun clearOverride(
        @PathParam("id") id: String,
        @Context headers: HttpHeaders,
    ): ApiSuccess {
        authVerifier.requireAuth(headers)
        automationManager.clearLampOverride(id)
        return ApiSuccess("Override cleared")
    }

    // --- Sensors ---

    @GET
    @Path("/sensors")
    fun sensors(): SensorsResponse = SensorsResponse(
        sensors = lampStateCache.getSensors().map { (id, sensor) ->
            SensorInfo(
                id = id,
                name = sensor.name,
                type = sensor.type,
                modelId = sensor.modelid,
                productName = sensor.productname,
                reachable = sensor.config?.reachable ?: true,
                battery = sensor.config?.battery,
                lastButtonEvent = sensor.state?.buttonevent,
                lastUpdated = sensor.state?.lastupdated,
            )
        }
    )

    // --- Groups ---

    @GET
    @Path("/groups")
    fun groups(): GroupsResponse = GroupsResponse(
        groups = lampStateCache.getGroups().map { (id, group) ->
            Group(
                id = id,
                name = group.name,
                type = GroupType.fromString(group.type),
                lampIds = group.lights,
                allOn = group.state?.all_on ?: false,
                anyOn = group.state?.any_on ?: false
            )
        }
    )

    // --- Automation ---

    @POST
    @Path("/wakeup")
    suspend fun wakeUp(@Context headers: HttpHeaders): WakeUpResponse {
        authVerifier.requireAuth(headers)
        return WakeUpResponse(success = true, state = automationManager.wakeUp().toModel())
    }

    @POST
    @Path("/sleep")
    suspend fun sleep(@Context headers: HttpHeaders): SleepResponse {
        authVerifier.requireAuth(headers)
        return SleepResponse(success = true, state = automationManager.goToSleep().toModel())
    }

    @GET
    @Path("/automation")
    fun automation(): AutomationStatusResponse {
        val color = automationManager.getAutomationColor()
        return AutomationStatusResponse(
            userState = automationManager.getUserState().toModel(),
            wakeUpTime = automationManager.getWakeUpTime()?.toString(),
            pseudoSunset = automationManager.getPseudoSunset().toString(),
            entertainmentActive = automationManager.isEntertainmentActive(),
            overriddenLamps = automationManager.getOverriddenLampIds(),
            automationMode = automationManager.getCurrentAutomationMode().name,
            automationColor = AutomationColorInfo(
                hue = color.hue,
                saturation = color.saturation,
                colorTemperature = color.colorTemperature,
                brightness = color.brightness,
                description = color.description
            )
        )
    }

    // --- Settings ---

    @GET
    @Path("/settings")
    fun getSettings(): SettingsResponse {
        val location = automationManager.getLocation()
        val daylight = automationManager.getDaylightColor()
        val evening = automationManager.getEveningColor()
        val night = automationManager.getNightColor()
        return SettingsResponse(
            pseudoSunset = automationManager.getPseudoSunset().toString(),
            nightTime = automationManager.getNightTime().toString(),
            latitude = location.latitude,
            longitude = location.longitude,
            excludedLampIds = automationManager.getExcludedLampIds().toList(),
            daylightColor = daylight.toApiConfig(),
            eveningColor = evening.toApiConfig(),
            nightColor = night.toApiConfig(),
            toggleButtonSensorId = automationManager.getToggleButtonSensorId(),
        )
    }

    @PUT
    @Path("/settings")
    fun updateSettings(
        request: SettingsUpdateRequest,
        @Context headers: HttpHeaders,
    ): ApiSuccess {
        authVerifier.requireAuth(headers)

        request.pseudoSunset?.let { automationManager.setPseudoSunset(it) }
        request.nightTime?.let { automationManager.setNightTime(it) }
        request.excludedLampIds?.let { automationManager.setExcludedLamps(it.toSet()) }
        request.daylightColor?.let { automationManager.setDaylightColor(it.toModeConfig()) }
        request.eveningColor?.let { automationManager.setEveningColor(it.toModeConfig()) }
        request.nightColor?.let { automationManager.setNightColor(it.toModeConfig()) }
        request.toggleButtonSensorId?.let {
            automationManager.setToggleButtonSensorId(it.takeIf { id -> id.isNotBlank() })
        }

        return ApiSuccess("Settings updated")
    }

    // --- Helpers ---

    private fun entertainmentLampIds(): Set<String> {
        val ids = mutableSetOf<String>()
        for ((_, group) in lampStateCache.getEntertainmentGroups()) {
            if (group.stream?.active == true) ids.addAll(group.lights)
        }
        return ids
    }
}

private fun UserState.toModel(): ModelUserState = when (this) {
    UserState.AWAKE -> ModelUserState.AWAKE
    UserState.ASLEEP -> ModelUserState.ASLEEP
}

private fun io.github.commandertvis.huemanager.hue.HueLight.toLamp(id: String, inEntertainment: Boolean): Lamp = Lamp(
    id = id,
    name = name,
    on = state.on,
    brightness = state.bri,
    hue = state.hue,
    saturation = state.sat,
    colorTemperature = state.ct,
    colorMode = ColorMode.fromString(state.colormode),
    reachable = state.reachable ?: false,
    type = LampType.fromHueType(type),
    inEntertainment = inEntertainment
)

private fun ModeColorConfig.toApiConfig(): AutomationModeColorConfig = AutomationModeColorConfig(
    hue = hue, saturation = saturation, colorTemperature = colorTemperature, brightness = brightness
)

private fun AutomationModeColorConfig.toModeConfig(): ModeColorConfig = ModeColorConfig(
    hue = hue, saturation = saturation, colorTemperature = colorTemperature, brightness = brightness
)
