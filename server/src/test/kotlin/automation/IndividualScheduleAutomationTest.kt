package io.github.commandertvis.huemanager.automation

import io.github.commandertvis.huemanager.config.AppConfig
import io.github.commandertvis.huemanager.config.GeoLocation
import io.github.commandertvis.huemanager.hue.*
import io.github.commandertvis.huemanager.models.LampSchedule
import io.github.commandertvis.huemanager.models.LampScheduleInterval
import io.github.commandertvis.huemanager.persistence.SettingsStore
import kotlinx.coroutines.runBlocking
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import kotlin.time.Clock
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.mockito.ArgumentMatchers.any
import org.mockito.ArgumentMatchers.anyString
import org.mockito.Mockito.*
import java.nio.file.Path

class IndividualScheduleAutomationTest {
    @TempDir
    lateinit var directory: Path
    private lateinit var store: SettingsStore
    private lateinit var config: AppConfig
    private lateinit var service: HueService
    private lateinit var cache: LampStateCache
    private lateinit var manager: AutomationManager
    private val lights = mutableMapOf<String, HueLight>()
    private val writes = mutableListOf<Pair<String, HueLightStateUpdate>>()
    private var entertainment = emptyMap<String, HueGroup>()
    private val plant = LampSchedule("plant", intervals = listOf(
        LampScheduleInterval(start = "00:00", end = "12:00"),
        LampScheduleInterval(start = "12:00", end = "00:00"),
    ))

    @BeforeEach
    fun setup() = runBlocking {
        store = SettingsStore(directory.resolve("hue.db").toString()).also { it.init() }
        config = mock(AppConfig::class.java)
        `when`(config.geoLocation()).thenReturn(GeoLocation(52.52, 13.405))
        `when`(config.timezone()).thenReturn("Europe/Berlin")
        `when`(config.pseudoSunset()).thenReturn("21:05")
        service = mock(HueService::class.java)
        cache = mock(LampStateCache::class.java)
        lights["plant"] = HueLight(HueLightState(on = false, bri = 100, ct = 350, colormode = "ct", reachable = true), "Color temperature light", "Plant")
        lights["room"] = lights.getValue("plant").copy(name = "Room")
        `when`(cache.getLights()).thenAnswer { lights.toMap() }
        `when`(cache.getLight(anyString() ?: "")).thenAnswer { lights[it.getArgument<String>(0)] }
        `when`(cache.getEntertainmentGroups()).thenAnswer { entertainment }
        `when`(service.setLightState(anyString() ?: "", any(HueLightStateUpdate::class.java) ?: HueLightStateUpdate())).thenAnswer {
            val id = it.getArgument<String>(0)
            val state = it.getArgument<HueLightStateUpdate>(1)
            writes.add(id to state)
            val light = lights.getValue(id)
            lights[id] = light.copy(state = light.state.copy(on = state.on ?: light.state.on,
                bri = state.bri ?: light.state.bri, ct = state.ct ?: light.state.ct,
                hue = state.hue ?: light.state.hue, sat = state.sat ?: light.state.sat,
                colormode = if (state.ct != null) "ct" else if (state.hue != null) "hs" else light.state.colormode))
            true
        }
        manager = AutomationManager(config, service, cache, store).also { it.loadPersistedState() }
    }

    @AfterEach
    fun cleanup() {
        manager.close()
        store.close()
    }

    @Test
    fun `plant schedule works while asleep and sleep only changes day night lamps`() = runBlocking {
        manager.setLampSchedules(listOf(plant))
        assertEquals(setOf("room"), manager.getAutomatedLampIds())
        assertEquals(UserState.ASLEEP, manager.getUserState())
        assertEquals(plant.desiredState(Clock.System.now().toLocalDateTime(TimeZone.of("Europe/Berlin"))), writes.single().second)
        writes.clear()
        manager.wakeUp()
        manager.goToSleep()
        assertTrue(writes.isNotEmpty())
        assertTrue(writes.all { it.first == "room" })
    }

    @Test
    fun `restart reloads schedule and reapplies it while asleep`() = runBlocking {
        manager.setLampSchedules(listOf(plant))
        manager.close()
        store.close()
        store = SettingsStore(directory.resolve("hue.db").toString()).also { it.init() }
        lights["plant"] = lights.getValue("plant").copy(state = lights.getValue("plant").state.copy(on = true, bri = 20))
        writes.clear()
        manager = AutomationManager(config, service, cache, store).also { it.loadPersistedState() }
        assertEquals(listOf(plant), manager.getLampSchedules())
        manager.resumeFromPersistedState()
        assertEquals("plant", writes.single().first)
    }

    @Test
    fun `manual override is respected and clearing it restores schedule even during pending operation`() = runBlocking {
        manager.setLampSchedules(listOf(plant))
        manager.addLampOverride("plant")
        lights["plant"] = lights.getValue("plant").copy(state = lights.getValue("plant").state.copy(on = true, bri = 20))
        writes.clear()
        manager.resumeFromPersistedState()
        assertTrue(writes.isEmpty())
        manager.addPendingOperations(listOf("plant"))
        manager.clearLampOverride("plant")
        assertEquals("plant", writes.single().first)
    }

    @Test
    fun `Hue Sync and unreachable lamps are skipped then restored when available`() = runBlocking {
        entertainment = mapOf("sync" to HueGroup("Sync", listOf("plant"), "Entertainment", stream = HueStreamState(active = true)))
        manager.setLampSchedules(listOf(plant))
        assertTrue(writes.isEmpty())
        entertainment = emptyMap()
        lights["plant"] = lights.getValue("plant").copy(state = lights.getValue("plant").state.copy(reachable = false))
        manager.resumeFromPersistedState()
        assertTrue(writes.isEmpty())
        lights["plant"] = lights.getValue("plant").copy(state = lights.getValue("plant").state.copy(reachable = true))
        manager.resumeFromPersistedState()
        assertEquals("plant", writes.single().first)
    }

    @Test
    fun `disabling returns lamp to normal cycle and preserves explicit exclusion`() = runBlocking {
        manager.setLampSchedules(listOf(plant))
        writes.clear()
        manager.setLampSchedules(listOf(plant.copy(enabled = false)))
        assertEquals(setOf("plant", "room"), manager.getAutomatedLampIds())
        assertEquals("plant", writes.single().first)
        assertEquals(false, writes.single().second.on)
        manager.setLampSchedules(listOf(plant))
        manager.setExcludedLamps(setOf("plant"))
        writes.clear()
        manager.setLampSchedules(emptyList())
        assertEquals(setOf("room"), manager.getAutomatedLampIds())
        assertTrue(writes.isEmpty())
    }

    @Test
    fun `invalid schedules are rejected before persistence or writes`() = runBlocking {
        assertThrows(IllegalArgumentException::class.java) { runBlocking { manager.setLampSchedules(listOf(plant, plant)) } }
        assertThrows(IllegalArgumentException::class.java) { runBlocking { manager.setLampSchedules(listOf(plant.copy(lampId = "unknown"))) } }
        assertThrows(IllegalArgumentException::class.java) { runBlocking {
            manager.setLampSchedules(listOf(plant.copy(intervals = listOf(LampScheduleInterval(temperatureKelvin = null, hue = 0, saturation = 254)))))
        } }
        assertTrue(manager.getLampSchedules().isEmpty())
        assertNull(store.get("lamp_schedules"))
        assertTrue(writes.isEmpty())
    }

    @Test
    fun `switching among individual manual and day night selects one controller immediately`() = runBlocking {
        manager.setLampSchedules(listOf(plant))
        writes.clear()
        manager.setLampSchedules(listOf(plant.copy(enabled = false)), setOf("plant"))
        assertEquals(setOf("room"), manager.getAutomatedLampIds())
        assertTrue(manager.getScheduledLampIds().isEmpty())
        assertTrue(writes.isEmpty())
        manager.setLampSchedules(listOf(plant.copy(enabled = false)), emptySet())
        assertEquals(setOf("plant", "room"), manager.getAutomatedLampIds())
        assertEquals("plant", writes.single().first)
        assertEquals(false, writes.single().second.on)
        manager.setLampSchedules(listOf(plant), emptySet())
        assertEquals(setOf("plant"), manager.getScheduledLampIds())
        assertEquals(setOf("room"), manager.getAutomatedLampIds())
    }
}
