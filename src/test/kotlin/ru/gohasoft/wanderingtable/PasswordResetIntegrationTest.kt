package ru.gohasoft.wanderingtable

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Primary
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.MvcResult
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import ru.gohasoft.wanderingtable.mail.PasswordResetMailer
import ru.gohasoft.wanderingtable.support.IntegrationTestBase
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/**
 * Сценарий сброса пароля целиком. Письмо перехватывается [RecordingPasswordResetMailer]:
 * SMTP в тестовом профиле не настроен, а код нужен, чтобы пройти сценарий до конца.
 *
 * Каждый тест ходит со своего «IP» ([nextIp]): лимитер живёт в контексте Spring, который
 * кэшируется между тестами, и с общим ключом соседние тесты отравляли бы друг другу окно.
 */
class PasswordResetIntegrationTest : IntegrationTestBase() {

    class RecordingPasswordResetMailer : PasswordResetMailer {
        val codesByEmail = ConcurrentHashMap<String, String>()

        override fun sendResetCode(email: String, code: String) {
            codesByEmail[email] = code
        }
    }

    @TestConfiguration
    class RecordingMailerConfig {
        @Bean
        @Primary
        fun recordingPasswordResetMailer(): PasswordResetMailer = RecordingPasswordResetMailer()
    }

    @Autowired
    private lateinit var mailer: PasswordResetMailer

    private val recorded: RecordingPasswordResetMailer
        get() = mailer as RecordingPasswordResetMailer

    @Test
    fun `sends a code that can be exchanged for a new password`() {
        val email = "reset-happy@example.com"
        register(email)

        assertThat(forgotPassword(email).response.status).isEqualTo(204)
        val resetToken = verifyCode(email, codeFor(email)).let {
            assertThat(it.response.status).isEqualTo(200)
            it.readField("resetToken")
        }

        assertThat(resetPassword(resetToken, NEW_PASSWORD).response.status).isEqualTo(204)
        assertThat(loginStatus(email, DEFAULT_PASSWORD)).isEqualTo(401)
        assertThat(loginStatus(email, NEW_PASSWORD)).isEqualTo(200)
    }

    /** Ответ не должен отличаться от ответа для зарегистрированного адреса. */
    @Test
    fun `answers 204 for an unknown email and sends nothing`() {
        val email = "nobody@example.com"

        assertThat(forgotPassword(email).response.status).isEqualTo(204)
        assertThat(recorded.codesByEmail).doesNotContainKey(email)
    }

    @Test
    fun `rejects a wrong code with 401`() {
        val email = "reset-wrong-code@example.com"
        register(email)
        forgotPassword(email)

        val wrongCode = codeFor(email).let { if (it == "000000") "111111" else "000000" }

        assertThat(verifyCode(email, wrongCode).response.status).isEqualTo(401)
    }

    @Test
    fun `rejects a code reused after a successful exchange`() {
        val email = "reset-reused-code@example.com"
        register(email)
        forgotPassword(email)
        val code = codeFor(email)

        assertThat(verifyCode(email, code).response.status).isEqualTo(200)
        assertThat(verifyCode(email, code).response.status).isEqualTo(401)
    }

    @Test
    fun `rejects an expired code with 401`() {
        val email = "reset-expired-code@example.com"
        register(email)
        forgotPassword(email)
        jdbcTemplate.update("update password_reset_tokens set expires_at = now() - interval '1 hour'")

        assertThat(verifyCode(email, codeFor(email)).response.status).isEqualTo(401)
    }

    /** Новый запрос кода отменяет прежний — иначе живых кодов накапливалось бы сколько угодно. */
    @Test
    fun `invalidates the previous code when a new one is requested`() {
        val email = "reset-second-code@example.com"
        register(email)
        forgotPassword(email)
        val firstCode = codeFor(email)
        forgotPassword(email)
        val secondCode = codeFor(email)

        assertThat(secondCode).isNotEqualTo(firstCode)
        assertThat(verifyCode(email, firstCode).response.status).isEqualTo(401)
        assertThat(verifyCode(email, secondCode).response.status).isEqualTo(200)
    }

    @Test
    fun `rejects a reset token used twice`() {
        val email = "reset-reused-token@example.com"
        register(email)
        forgotPassword(email)
        val resetToken = verifyCode(email, codeFor(email)).readField("resetToken")

        assertThat(resetPassword(resetToken, NEW_PASSWORD).response.status).isEqualTo(204)
        assertThat(resetPassword(resetToken, "AnotherPass3").response.status).isEqualTo(401)
    }

    @Test
    fun `rejects a weak new password with 400 and keeps the old one`() {
        val email = "reset-weak-password@example.com"
        register(email)
        forgotPassword(email)
        val resetToken = verifyCode(email, codeFor(email)).readField("resetToken")

        assertThat(resetPassword(resetToken, "short").response.status).isEqualTo(400)
        assertThat(loginStatus(email, DEFAULT_PASSWORD)).isEqualTo(200)
    }

    /**
     * Без отзыва сессий укравший старый пароль сохранял бы доступ через уже выданный
     * refresh-токен ещё 30 дней после смены пароля.
     */
    @Test
    fun `revokes refresh tokens issued before the reset`() {
        val email = "reset-revokes-sessions@example.com"
        register(email)
        val refreshToken = login(email).refreshToken
        forgotPassword(email)
        val resetToken = verifyCode(email, codeFor(email)).readField("resetToken")
        resetPassword(resetToken, NEW_PASSWORD)

        val result = postJson("/auth/refresh", mapOf("refreshToken" to refreshToken))

        assertThat(result.response.status).isEqualTo(401)
    }

    @Test
    fun `throttles repeated requests from one client with 429`() {
        val email = "reset-throttled@example.com"
        register(email)
        val ip = nextIp()

        val statuses = (1..MAX_RESET_REQUESTS + 1).map { forgotPassword(email, ip).response.status }

        assertThat(statuses.take(MAX_RESET_REQUESTS)).containsOnly(204)
        assertThat(statuses.last()).isEqualTo(429)
    }

    // --- Хелперы ------------------------------------------------------------------------

    private fun codeFor(email: String): String =
        requireNotNull(recorded.codesByEmail[email]) { "no reset code was sent to $email" }

    private fun forgotPassword(email: String, ip: String = nextIp()): MvcResult =
        postFrom("/auth/forgot-password", mapOf("email" to email), ip)

    private fun verifyCode(email: String, code: String, ip: String = nextIp()): MvcResult =
        postFrom("/auth/verify-reset-code", mapOf("email" to email, "code" to code), ip)

    private fun resetPassword(resetToken: String, newPassword: String): MvcResult =
        postJson("/auth/reset-password", mapOf("resetToken" to resetToken, "newPassword" to newPassword))

    private fun loginStatus(email: String, password: String): Int =
        postJson("/auth/login", mapOf("email" to email, "password" to password)).response.status

    private fun postFrom(path: String, body: Any, ip: String): MvcResult =
        mockMvc.perform(
            post(path)
                .contentType(MediaType.APPLICATION_JSON)
                .content(json(body))
                .header("X-Forwarded-For", ip)
        ).andReturn()

    private companion object {
        const val NEW_PASSWORD = "NewPassword2"

        /** Должно совпадать с `app.rate-limit.password-reset.max-requests`. */
        const val MAX_RESET_REQUESTS = 10

        private val ipCounter = AtomicInteger()

        fun nextIp(): String = "203.0.113.${ipCounter.incrementAndGet() % 250}"
    }
}
