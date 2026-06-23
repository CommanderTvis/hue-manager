package io.github.commandertvis.huemanager.hue

import jakarta.ws.rs.GET
import jakarta.ws.rs.HeaderParam
import jakarta.ws.rs.PUT
import jakarta.ws.rs.Path
import jakarta.ws.rs.PathParam
import jakarta.ws.rs.Produces
import jakarta.ws.rs.core.MediaType
import jakarta.ws.rs.core.Response
import org.eclipse.microprofile.rest.client.inject.RegisterRestClient

/**
 * Quarkus REST Client for the authenticated portion of the Philips Hue Remote API
 * (`https://api.meethue.com/route/api/{username}/...`).
 *
 * The bearer token and bridge `username` are dynamic per request, so they are passed as
 * method parameters (`@HeaderParam` / `@PathParam`) rather than configured statically.
 *
 * The OAuth2 token endpoints and the bridge-linking calls are NOT modeled here — they use a
 * different base path (`/v2/oauth2`, `/route/api`), Basic auth and form encoding — and are issued
 * via a JDK [java.net.http.HttpClient] in [HueRemoteClient].
 *
 * Base URL configured as `quarkus.rest-client.hue-api.url` in `application.properties`.
 */
@RegisterRestClient(configKey = "hue-api")
@Produces(MediaType.APPLICATION_JSON)
@Path("/route/api")
interface HueApi {

    @GET
    @Path("/{username}/lights")
    fun getLights(
        @PathParam("username") username: String,
        @HeaderParam("Authorization") authorization: String,
    ): Map<String, HueLight>

    @GET
    @Path("/{username}/lights/{id}")
    fun getLight(
        @PathParam("username") username: String,
        @PathParam("id") id: String,
        @HeaderParam("Authorization") authorization: String,
    ): HueLight

    /**
     * Returns the raw [Response] so callers can inspect the status and refresh the token on 401
     * without an exception being thrown for the non-2xx path.
     */
    @PUT
    @Path("/{username}/lights/{id}/state")
    fun setLightState(
        @PathParam("username") username: String,
        @PathParam("id") id: String,
        @HeaderParam("Authorization") authorization: String,
        state: HueLightStateUpdate,
    ): Response

    @GET
    @Path("/{username}/groups")
    fun getGroups(
        @PathParam("username") username: String,
        @HeaderParam("Authorization") authorization: String,
    ): Map<String, HueGroup>

    @GET
    @Path("/{username}/sensors")
    fun getSensors(
        @PathParam("username") username: String,
        @HeaderParam("Authorization") authorization: String,
    ): Map<String, HueSensor>
}
