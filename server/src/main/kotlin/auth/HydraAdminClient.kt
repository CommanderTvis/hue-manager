package io.github.commandertvis.huemanager.auth

import jakarta.ws.rs.Consumes
import jakarta.ws.rs.GET
import jakarta.ws.rs.PUT
import jakarta.ws.rs.Path
import jakarta.ws.rs.Produces
import jakarta.ws.rs.QueryParam
import jakarta.ws.rs.core.MediaType
import kotlinx.serialization.Serializable
import org.eclipse.microprofile.rest.client.inject.RegisterRestClient

/**
 * REST client for Ory Hydra's admin API (the "OAuth2 admin" endpoints used by login/consent
 * providers). Hydra owns the OAuth protocol; we only fetch the pending login/consent requests
 * and accept them after our own password check.
 *
 * Base URL configured via `quarkus.rest-client.hydra-admin.url` (env `HYDRA_ADMIN_URL`,
 * default `http://localhost:4445`).
 */
@RegisterRestClient(configKey = "hydra-admin")
@Path("/admin/oauth2/auth/requests")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
interface HydraAdminClient {

    @GET
    @Path("/login")
    fun getLoginRequest(@QueryParam("login_challenge") challenge: String): HydraLoginRequest

    @PUT
    @Path("/login/accept")
    fun acceptLoginRequest(
        @QueryParam("login_challenge") challenge: String,
        body: HydraAcceptLogin
    ): HydraRedirect

    @PUT
    @Path("/login/reject")
    fun rejectLoginRequest(
        @QueryParam("login_challenge") challenge: String,
        body: HydraRejectRequest
    ): HydraRedirect

    @GET
    @Path("/consent")
    fun getConsentRequest(@QueryParam("consent_challenge") challenge: String): HydraConsentRequest

    @PUT
    @Path("/consent/accept")
    fun acceptConsentRequest(
        @QueryParam("consent_challenge") challenge: String,
        body: HydraAcceptConsent
    ): HydraRedirect
}

@Serializable
data class HydraLoginRequest(
    val challenge: String,
    val skip: Boolean = false,
    val subject: String = "",
    val requested_scope: List<String> = emptyList(),
    val client: HydraClient? = null
)

@Serializable
data class HydraConsentRequest(
    val challenge: String,
    val skip: Boolean = false,
    val subject: String = "",
    val requested_scope: List<String> = emptyList(),
    val requested_access_token_audience: List<String> = emptyList(),
    val client: HydraClient? = null
)

@Serializable
data class HydraClient(
    val client_id: String? = null,
    val client_name: String? = null
)

@Serializable
data class HydraAcceptLogin(
    val subject: String,
    val remember: Boolean = false,
    val remember_for: Long = 0
)

@Serializable
data class HydraAcceptConsent(
    val grant_scope: List<String> = emptyList(),
    val grant_access_token_audience: List<String> = emptyList(),
    val remember: Boolean = false,
    val remember_for: Long = 0
)

@Serializable
data class HydraRejectRequest(
    val error: String,
    val error_description: String
)

@Serializable
data class HydraRedirect(
    val redirect_to: String
)
