package ru.gohasoft.wanderingtable.mail

import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.mail.javamail.JavaMailSender

/**
 * Выбор реализации [PasswordResetMailer] по конфигурации.
 *
 * `spring.mail.host` намеренно не объявлен в `application.properties`: пустое, но существующее
 * значение удовлетворило бы [ConditionalOnProperty], и приложение выбрало бы SMTP без сервера.
 * Хост приходит переменной окружения `SPRING_MAIL_HOST` (в dev — Mailpit из docker-compose).
 * По той же причине `JavaMailSender` существует ровно тогда, когда хост задан.
 */
@Configuration
class MailConfig {

    @Bean
    @ConditionalOnProperty(name = ["spring.mail.host"])
    fun smtpPasswordResetMailer(
        mailSender: JavaMailSender,
        @Value($$"${app.mail.from}") from: String,
        @Value($$"${app.password-reset.code-ttl-minutes:15}") ttlMinutes: Long
    ): PasswordResetMailer = SmtpPasswordResetMailer(mailSender, from, ttlMinutes)

    /** Объявлен после SMTP-бина: [ConditionalOnMissingBean] внутри одного класса учитывает порядок. */
    @Bean
    @ConditionalOnMissingBean(PasswordResetMailer::class)
    fun loggingPasswordResetMailer(): PasswordResetMailer = LoggingPasswordResetMailer()
}
