package ru.gohasoft.wanderingtable.mail

/**
 * Доставка кода сброса пароля пользователю.
 *
 * Отдельный интерфейс, а не прямой вызов `JavaMailSender`: без настроенного SMTP приложение
 * должно запускаться и работать (см. [LoggingPasswordResetMailer]), а тестам нужен перехват кода.
 */
interface PasswordResetMailer {
    fun sendResetCode(email: String, code: String)
}
