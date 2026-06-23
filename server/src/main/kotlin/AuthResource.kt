package io.github.commandertvis.huemanager

import io.github.commandertvis.huemanager.auth.JwtService
import io.github.commandertvis.huemanager.models.AuthRequest
import io.github.commandertvis.huemanager.models.AuthResponse
import jakarta.ws.rs.Consumes
import jakarta.ws.rs.POST
import jakarta.ws.rs.Path
import jakarta.ws.rs.Produces
import jakarta.ws.rs.core.MediaType
import org.jboss.resteasy.reactive.RestResponse

@Path("/api/auth")
class AuthResource(
    private val auth: PasswordAuthService,
    private val jwtService: JwtService,
) {
    // Returns RestResponse<AuthResponse> (not a raw jakarta Response) so Quarkus registers the
    // AuthResponse kotlinx serializer at build time. With a raw Response the type is opaque and the
    // native image fails the reflective serializer lookup at runtime (500). See README/CLAUDE notes.
    @POST
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_JSON)
    fun authenticate(request: AuthRequest): RestResponse<AuthResponse> =
        if (auth.verifyPassword(request.password)) {
            RestResponse.ok(AuthResponse.success(jwtService.issue()))
        } else {
            RestResponse.ResponseBuilder
                .create<AuthResponse>(RestResponse.Status.UNAUTHORIZED)
                .entity(AuthResponse.failure("Invalid password"))
                .build()
        }
}
