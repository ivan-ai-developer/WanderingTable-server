package ru.gohasoft.wanderingtable.mail

import org.springframework.mail.SimpleMailMessage
import org.springframework.mail.javamail.JavaMailSender

/**
 * Отправка через настроенный SMTP. Письмо простым текстом: шаблонизатора в проекте нет,
 * а для шестизначного кода он и не нужен.
 */
class SmtpPasswordResetMailer(
    private val mailSender: JavaMailSender,
    private val from: String,
    private val ttlMinutes: Long
) : PasswordResetMailer {

    override fun sendResetCode(email: String, code: String) {
        val message = SimpleMailMessage().apply {
            setFrom(from)
            setTo(email)
            subject = "Wandering Table: password reset code"
            text = """
                Your password reset code is $code

                It is valid for $ttlMinutes minutes and can be used once.
                If you did not ask to reset your password, just ignore this email.
            """.trimIndent()
        }
        mailSender.send(message)
    }
}
