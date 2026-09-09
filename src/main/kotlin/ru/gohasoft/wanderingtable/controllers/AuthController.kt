package ru.gohasoft.wanderingtable.controllers

import jakarta.servlet.http.HttpServletRequest
import jakarta.validation.Valid
import org.springframework.http.HttpStatus
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.server.ResponseStatusException
import ru.gohasoft.wanderingtable.controllers.dto.ForgotPasswordRequest
import ru.gohasoft.wanderingtable.controllers.dto.LoginRequest
import ru.gohasoft.wanderingtable.controllers.dto.RefreshRequest
import ru.gohasoft.wanderingtable.controllers.dto.RegisterRequest
import ru.gohasoft.wanderingtable.controllers.dto.ResetPasswordRequest
import ru.gohasoft.wanderingtable.controllers.dto.ResetTokenResponse
import ru.gohasoft.wanderingtable.controllers.dto.TokenPairResponse
import ru.gohasoft.wanderingtable.controllers.dto.UserResponse
import ru.gohasoft.wanderingtable.controllers.dto.VerifyResetCodeRequest
import ru.gohasoft.wanderingtable.controllers.dto.toResponse
import ru.gohasoft.wanderingtable.security.AuthService
import ru.gohasoft.wanderingtable.security.PasswordResetRateLimiter
import ru.gohasoft.wanderingtable.security.RegisterRateLimiter
import ru.gohasoft.wanderingtable.security.TokenPair

@RestController
@RequestMapping("/auth")
class AuthController(
    private val authService: AuthService,
    private val registerRateLimiter: RegisterRateLimiter,
    private val passwordResetRateLimiter: PasswordResetRateLimiter
) {

    @PostMapping("/register")
    fun register(
        @Valid @RequestBody body: RegisterRequest,
        request: HttpServletRequest
    ): UserResponse {
        if (!registerRateLimiter.tryAcquire(request.clientIp())) {
            throw ResponseStatusException(
                HttpStatus.TOO_MANY_REQUESTS,
                "Too many registration attempts. Try again later."
            )
        }
        return authService.register(body.name, body.email, body.password).toResponse()
    }

    @PostMapping("/login")
    fun login(@Valid @RequestBody body: LoginRequest): TokenPairResponse =
        authService.login(body.email, body.password).toResponse()

    @PostMapping("/refresh")
    fun refresh(@Valid @RequestBody body: RefreshRequest): TokenPairResponse =
        authService.refresh(body.refreshToken).toResponse()

    /** Отзывает переданный refresh-токен. Идемпотентен — всегда 204. */
    @PostMapping("/logout")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    fun logout(@Valid @RequestBody body: RefreshRequest) {
        authService.logout(body.refreshToken)
    }

    /**
     * Отправляет код сброса пароля на почту. Всегда 204, даже для незарегистрированного адреса:
     * иначе по коду ответа можно было бы перебором выяснить, кто состоит в клубе.
     */
    @PostMapping("/forgot-password")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    fun forgotPassword(
        @Valid @RequestBody body: ForgotPasswordRequest,
        request: HttpServletRequest
    ) {
        // Ограничение и по IP, и по адресу: только по IP один клиент забивал бы чужой почтовый
        // ящик через разные адреса, только по адресу — рассылал бы письма всему клубу.
        requirePasswordResetQuota(request.clientIp())
        requirePasswordResetQuota(AuthService.normalizeEmail(body.email))
        authService.requestPasswordReset(body.email)
    }

    /** Обменивает код из письма на одноразовый resetToken. */
    @PostMapping("/verify-reset-code")
    fun verifyResetCode(
        @Valid @RequestBody body: VerifyResetCodeRequest,
        request: HttpServletRequest
    ): ResetTokenResponse {
        // Единственное, что ограничивает перебор шестизначного кода: счётчика попыток
        // на самой строке токена нет, она расходуется целиком при первой же проверке.
        requirePasswordResetQuota(request.clientIp())
        requirePasswordResetQuota(AuthService.normalizeEmail(body.email))
        return ResetTokenResponse(authService.verifyResetCode(body.email, body.code))
    }

    @PostMapping("/reset-password")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    fun resetPassword(@Valid @RequestBody body: ResetPasswordRequest) {
        authService.resetPassword(body.resetToken, body.newPassword)
    }

    private fun requirePasswordResetQuota(key: String) {
        if (!passwordResetRateLimiter.tryAcquire(key)) {
            throw ResponseStatusException(
                HttpStatus.TOO_MANY_REQUESTS,
                "Too many password reset attempts. Try again later."
            )
        }
    }
}

private fun TokenPair.toResponse() = TokenPairResponse(
    accessToken = accessToken,
    refreshToken = refreshToken
)

private fun HttpServletRequest.clientIp(): String {
    return getHeader("X-Forwarded-For")
        ?.substringBefore(",")
        ?.trim()
        ?.takeIf { it.isNotEmpty() }
        ?: remoteAddr
}
