package ru.gohasoft.wanderingtable.mail

import org.slf4j.LoggerFactory

/**
 * Запасной вариант, когда `spring.mail.host` не задан: код пишется в лог.
 *
 * Так весь сценарий сброса пароля проходится локально и в тестах без SMTP-сервера.
 * В проде хост задан, и вместо этого бина поднимается [SmtpPasswordResetMailer].
 */
class LoggingPasswordResetMailer : PasswordResetMailer {

    private val log = LoggerFactory.getLogger(javaClass)

    override fun sendResetCode(email: String, code: String) {
        log.info("SMTP is not configured; password reset code for {} is {}", email, code)
    }
}
