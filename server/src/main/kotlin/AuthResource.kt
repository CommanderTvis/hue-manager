package io.github.commandertvis.huemanager

import io.github.commandertvis.huemanager.auth.JwtService
import io.github.commandertvis.huemanager.models.AuthRequest
import io.github.commandertvis.huemanager.models.AuthResponse
import jakarta.ws.rs.Consumes
import jakarta.ws.rs.POST
import jakarta.ws.rs.Path
import jakarta.ws.rs.Produces
import jakarta.ws.rs.core.MediaType
import jakarta.ws.rs.core.Response

@Path("/api/auth")
class AuthResource(
    private val auth: PasswordAuthService,
    private val jwtService: JwtService,
) {
    @POST
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_JSON)
    fun authenticate(request: AuthRequest): Response =
        if (auth.verifyPassword(request.password)) {
            Response.ok(AuthResponse.success(jwtService.issue())).build()
        } else {
            Response.status(Response.Status.UNAUTHORIZED)
                .entity(AuthResponse.failure("Invalid password"))
                .build()
        }
}
