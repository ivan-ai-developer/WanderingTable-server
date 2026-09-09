package ru.gohasoft.wanderingtable.security

import org.springframework.beans.factory.annotation.Value
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component

/**
 * Лимитер сброса пароля: по окну на IP и на email.
 *
 * Для `verify-reset-code` это единственное, что ограничивает перебор шестизначного кода —
 * счётчика попыток на самой строке токена нет, строка расходуется целиком.
 */
@Component
class PasswordResetRateLimiter(
    @Value($$"${app.rate-limit.password-reset.max-requests:10}") maxRequests: Int,
    @Value($$"${app.rate-limit.password-reset.window-seconds:900}") windowSeconds: Long
) {

    private val limiter = SlidingWindowRateLimiter(maxRequests, windowSeconds)

    fun tryAcquire(key: String): Boolean = limiter.tryAcquire(key)

    @Scheduled(fixedDelayString = "\${app.rate-limit.password-reset.cleanup-interval-ms:900000}")
    fun evictExpiredWindows() = limiter.evictExpiredWindows()
}
