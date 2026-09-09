package ru.gohasoft.wanderingtable.security

import org.springframework.beans.factory.annotation.Value
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component

/** Лимитер попыток регистрации, по одному скользящему окну на IP. */
@Component
class RegisterRateLimiter(
    @Value($$"${app.rate-limit.register.max-requests:5}") maxRequests: Int,
    @Value($$"${app.rate-limit.register.window-seconds:60}") windowSeconds: Long
) {

    private val limiter = SlidingWindowRateLimiter(maxRequests, windowSeconds)

    fun tryAcquire(key: String): Boolean = limiter.tryAcquire(key)

    @Scheduled(fixedDelayString = "\${app.rate-limit.register.cleanup-interval-ms:300000}")
    fun evictExpiredWindows() = limiter.evictExpiredWindows()
}
