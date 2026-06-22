package io.github.commandertvis.huemanager.mcp

import io.github.commandertvis.huemanager.automation.AutomationManager
import io.github.commandertvis.huemanager.config.Config
import io.github.commandertvis.huemanager.config.ConfigLoader
import io.github.commandertvis.huemanager.config.GeoLocation
import io.github.commandertvis.huemanager.hue.HueService
import io.github.commandertvis.huemanager.hue.LampStateCache
import io.modelcontextprotocol.kotlin.sdk.client.Client
import io.modelcontextprotocol.kotlin.sdk.types.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.buildJsonObject
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import kotlin.test.*

class McpServerTest {
    private lateinit var hueService: HueService
    private lateinit var lampStateCache: LampStateCache
    private lateinit var automationManager: AutomationManager
    private lateinit var mcpHandler: McpHandler
    private lateinit var client: Client

    @BeforeEach
    fun setUp() {
        val config = testConfig()
        hueService = HueService(config)
        lampStateCache = LampStateCache(hueService)
        hueService.setCache(lampStateCache)
        automationManager = AutomationManager(config, hueService, lampStateCache)
        mcpHandler = McpHandler(hueService, automationManager, lampStateCache)

        val (clientTransport, serverTransport) = InMemoryTransport.createLinkedPair()
        client = Client(
            clientInfo = Implementation(name = "test-client", version = "1.0.0")
        )

        val server = mcpHandler.createServer()

        runBlocking {
            launch { client.connect(clientTransport) }
            launch { server.createSession(serverTransport) }
        }
    }

    @AfterEach
    fun tearDown() {
        automationManager.close()
        lampStateCache.close()
        hueService.close()
    }

    @Test
    fun `lists MCP tools`() = runBlocking {
        val result = client.listTools()
        val toolNames = result.tools.map { it.name }

        val expectedTools = listOf(
            "get_lamp_state",
            "set_lamp_state",
            "set_all_lamps",
            "clear_lamp_override",
            "get_automation_status",
            "wake_up",
            "go_to_sleep"
        )

        expectedTools.forEach { expected ->
            assertTrue(expected in toolNames, "Missing tool: $expected")
        }
    }

    @Test
    fun `lists MCP resources`() = runBlocking {
        val result = client.listResources()
        val resourceUris = result.resources.map { it.uri }
        assertTrue("hue://lamps" in resourceUris)
    }

    @Test
    fun `reads lamps resource when no lamps configured`() = runBlocking {
        val result = client.readResource(ReadResourceRequest(ReadResourceRequestParams("hue://lamps")))
        val contents = result.contents
        assertTrue(contents.isNotEmpty())

        val text = assertIs<TextResourceContents>(contents.first()).text
        assertTrue(text.startsWith("Found 0 lamps"))
    }

    @Test
    fun `get_lamp_state requires lamp_id`() = runBlocking {
        val result = client.callTool(
            CallToolRequest(
                CallToolRequestParams(
                    name = "get_lamp_state",
                    arguments = buildJsonObject { }
                )
            )
        )
        assertEquals(true, result.isError)
        val content = assertIs<TextContent>(result.content.first()).text
        assertTrue(content.contains("Missing required parameter: lamp_id"))
    }

    @Test
    fun `set_all_lamps requires on`() = runBlocking {
        val result = client.callTool(
            CallToolRequest(
                CallToolRequestParams(
                    name = "set_all_lamps",
                    arguments = buildJsonObject { }
                )
            )
        )
        assertEquals(true, result.isError)
        val content = assertIs<TextContent>(result.content.first()).text
        assertTrue(content.contains("Missing required parameter: on"))
    }

    private fun testConfig(): Config = Config(
        passwordHash = ConfigLoader.hashPassword("test-password"),
        region = GeoLocation(latitude = 0.0, longitude = 0.0),
        pseudoSunset = "21:05",
        timezone = "UTC",
        keystorePassword = null,
        hueUsername = null,
        hueClientId = "test-client",
        hueClientSecret = "test-secret",
        hueAppId = "test-app",
        hueRedirectUri = null,
        hueAccessToken = null,
        hueRefreshToken = null,
        databasePath = "build/test-hue.db"
    )
}
