package ru.gohasoft.wanderingtable.security

import org.slf4j.LoggerFactory
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component

/**
 * Удаляет просроченные коды и resetToken-ы. Нерасходованная строка живёт до тех пор, пока
 * пользователь не запросит новый код, поэтому без уборки таблица росла бы по строке на каждый
 * брошенный сценарий сброса.
 */
@Component
class PasswordResetTokenCleanupJob(
    private val authService: AuthService
) {

    private val log = LoggerFactory.getLogger(javaClass)

    @Scheduled(fixedDelayString = "\${app.cleanup.password-reset-tokens-interval-ms:3600000}")
    fun purgeExpired() {
        val removed = authService.purgeExpiredPasswordResetTokens()
        if (removed > 0) {
            log.info("Purged {} expired password reset tokens", removed)
        }
    }
}
