package ru.gohasoft.wanderingtable.security

import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.http.HttpStatus
import org.springframework.security.authentication.BadCredentialsException
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import org.springframework.web.server.ResponseStatusException
import ru.gohasoft.wanderingtable.database.model.ObjectId
import ru.gohasoft.wanderingtable.database.model.PasswordResetToken
import ru.gohasoft.wanderingtable.database.model.RefreshToken
import ru.gohasoft.wanderingtable.database.model.ResetStage
import ru.gohasoft.wanderingtable.database.model.Role
import ru.gohasoft.wanderingtable.database.model.User
import ru.gohasoft.wanderingtable.database.model.nowTruncated
import ru.gohasoft.wanderingtable.database.repository.PasswordResetTokenRepository
import ru.gohasoft.wanderingtable.database.repository.RefreshTokenRepository
import ru.gohasoft.wanderingtable.database.repository.UserRepository
import ru.gohasoft.wanderingtable.mail.PasswordResetMailer
import java.security.MessageDigest
import java.security.SecureRandom
import java.time.Duration
import java.time.Instant
import java.util.Base64

/** Пара токенов доменного уровня; в HTTP-контракт отдаётся как `TokenPairResponse`. */
data class TokenPair(
    val accessToken: String,
    val refreshToken: String
)

@Service
class AuthService(
    private val jwtService: JwtService,
    private val userRepository: UserRepository,
    private val hashEncoder: HashEncoder,
    private val refreshTokenRepository: RefreshTokenRepository,
    private val passwordResetTokenRepository: PasswordResetTokenRepository,
    private val passwordResetMailer: PasswordResetMailer,
    @Value($$"${app.password-reset.code-ttl-minutes:15}") private val codeTtlMinutes: Long,
    @Value($$"${app.password-reset.token-ttl-minutes:10}") private val tokenTtlMinutes: Long
) {

    private val log = LoggerFactory.getLogger(javaClass)
    private val secureRandom = SecureRandom()

    fun register(name: String, email: String, password: String): User {
        val normalizedEmail = normalizeEmail(email)
        if (userRepository.findByEmail(normalizedEmail) != null) {
            throw ResponseStatusException(HttpStatus.CONFLICT, DUPLICATE_EMAIL)
        }
        return try {
            userRepository.save(
                User(
                    name = name.trim(),
                    email = normalizedEmail,
                    hashedPassword = hashEncoder.encode(password),
                    roles = setOf(Role.PLAYER)
                )
            )
        } catch (e: DataIntegrityViolationException) {
            // Проверка выше — лишь быстрый путь. Корректность обеспечивает уникальный индекс
            // uk_users_email: при двух одновременных регистрациях сюда попадает проигравший запрос.
            throw ResponseStatusException(HttpStatus.CONFLICT, DUPLICATE_EMAIL)
        }
    }

    fun login(email: String, password: String): TokenPair {
        val user = userRepository.findByEmail(normalizeEmail(email))
            ?: throw BadCredentialsException(INVALID_CREDENTIALS)

        if (!hashEncoder.matches(password, user.hashedPassword)) {
            throw BadCredentialsException(INVALID_CREDENTIALS)
        }

        return issueTokens(user.id)
    }

    @Transactional
    fun refresh(refreshToken: String): TokenPair {
        if (!jwtService.validateRefreshToken(refreshToken)) {
            throw unauthorized("Invalid refresh token.")
        }

        val userId = jwtService.getUserIdFromToken(refreshToken)
        if (!userRepository.existsById(userId)) {
            throw unauthorized("Invalid refresh token.")
        }

        // Ротация атомарна: consume() — bulk-delete, возвращающий число затронутых строк.
        // PostgreSQL блокирует строку на время транзакции, поэтому из двух одновременных
        // запросов с одним токеном ровно один получит 1, а второй — 0.
        if (refreshTokenRepository.consume(userId, hashToken(refreshToken)) == 0) {
            throw unauthorized("Refresh token not recognized (maybe used or expired?)")
        }

        return issueTokens(ObjectId(userId))
    }

    /** Отзыв одного refresh-токена. Идемпотентен: повторный вызов не ошибка. */
    @Transactional
    fun logout(refreshToken: String) {
        if (!jwtService.validateRefreshToken(refreshToken)) return
        val userId = jwtService.getUserIdFromToken(refreshToken)
        refreshTokenRepository.consume(userId, hashToken(refreshToken))
    }

    /**
     * Отправляет на почту одноразовый код сброса пароля.
     *
     * Для несуществующего адреса завершается молча: контроллер в любом случае отвечает 204,
     * иначе эндпоинт превратился бы в проверку "зарегистрирован ли такой email".
     * Прежние коды и уже выданные resetToken пользователя удаляются — живым остаётся один.
     */
    @Transactional
    fun requestPasswordReset(email: String) {
        val user = userRepository.findByEmail(normalizeEmail(email)) ?: return

        passwordResetTokenRepository.deleteAllForUser(user.id.value)
        val code = generateResetCode()
        passwordResetTokenRepository.save(
            PasswordResetToken(
                userId = user.id,
                stage = ResetStage.CODE,
                hashedToken = hashResetCode(user.id.value, code),
                expiresAt = nowTruncated().plus(Duration.ofMinutes(codeTtlMinutes))
            )
        )

        // Недоступный SMTP не должен превращать запрос в 500: это и потеря сценария, и утечка
        // самого факта существования аккаунта (для чужого адреса ответ остался бы 204).
        runCatching { passwordResetMailer.sendResetCode(user.email, code) }
            .onFailure { log.error("Failed to send the password reset code", it) }
    }

    /** Обменивает код из письма на одноразовый resetToken. Код расходуется в любом случае. */
    @Transactional
    fun verifyResetCode(email: String, code: String): String {
        val user = userRepository.findByEmail(normalizeEmail(email))
            ?: throw unauthorized(INVALID_RESET_CODE)

        val stored = passwordResetTokenRepository
            .findByHashedTokenAndStage(hashResetCode(user.id.value, code), ResetStage.CODE)
            ?: throw unauthorized(INVALID_RESET_CODE)

        consumeOrReject(stored, INVALID_RESET_CODE)

        val resetToken = generateResetToken()
        passwordResetTokenRepository.save(
            PasswordResetToken(
                userId = user.id,
                stage = ResetStage.CONFIRMED,
                hashedToken = hashToken(resetToken),
                expiresAt = nowTruncated().plus(Duration.ofMinutes(tokenTtlMinutes))
            )
        )
        return resetToken
    }

    /**
     * Устанавливает новый пароль и разлогинивает все устройства.
     *
     * Отзыв refresh-токенов обязателен: без него укравший старый пароль сохранял бы доступ
     * через уже выданную сессию ещё 30 дней после смены пароля.
     */
    @Transactional
    fun resetPassword(resetToken: String, newPassword: String) {
        val stored = passwordResetTokenRepository
            .findByHashedTokenAndStage(hashToken(resetToken), ResetStage.CONFIRMED)
            ?: throw unauthorized(INVALID_RESET_TOKEN)

        consumeOrReject(stored, INVALID_RESET_TOKEN)

        val user = userRepository.findById(stored.userId.value).orElse(null)
            ?: throw unauthorized(INVALID_RESET_TOKEN)

        user.hashedPassword = hashEncoder.encode(newPassword)
        userRepository.save(user)
        refreshTokenRepository.deleteAllForUser(user.id.value)
    }

    /** Удаляет просроченные токены: строки не чистились и таблица росла бесконечно. */
    @Transactional
    fun purgeExpiredTokens(): Int = refreshTokenRepository.deleteExpired(Instant.now())

    @Transactional
    fun purgeExpiredPasswordResetTokens(): Int =
        passwordResetTokenRepository.deleteExpired(Instant.now())

    /**
     * Расходует строку сброса пароля. Просроченная всё равно удаляется — это и отказ, и уборка.
     * Ноль затронутых строк означает, что строку уже забрал конкурентный запрос.
     */
    private fun consumeOrReject(stored: PasswordResetToken, reason: String) {
        val consumed = passwordResetTokenRepository.consumeById(stored.id.value)
        if (consumed == 0 || stored.expiresAt.isBefore(Instant.now())) {
            throw unauthorized(reason)
        }
    }

    private fun issueTokens(userId: ObjectId): TokenPair {
        val accessToken = jwtService.generateAccessToken(userId.value)
        val refreshToken = jwtService.generateRefreshToken(userId.value)
        storeRefreshToken(userId, refreshToken)
        return TokenPair(accessToken = accessToken, refreshToken = refreshToken)
    }

    private fun storeRefreshToken(userId: ObjectId, rawRefreshToken: String) {
        refreshTokenRepository.save(
            RefreshToken(
                userId = userId,
                expiresAt = nowTruncated().plusMillis(jwtService.refreshTokenValidityMs),
                hashedToken = hashToken(rawRefreshToken)
            )
        )
    }

    private fun generateResetCode(): String = "%06d".format(secureRandom.nextInt(1_000_000))

    private fun generateResetToken(): String {
        val bytes = ByteArray(RESET_TOKEN_BYTES)
        secureRandom.nextBytes(bytes)
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
    }

    private fun hashToken(token: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val hashBytes = digest.digest(token.encodeToByteArray())
        return Base64.getEncoder().encodeToString(hashBytes)
    }

    /**
     * Хеш кода солится идентификатором пользователя: у шестизначного кода всего 10^6 значений,
     * и голый SHA-256 от него — тривиальная радужная таблица по всей утёкшей таблице сразу.
     */
    private fun hashResetCode(userId: String, code: String): String = hashToken("$userId:$code")

    private fun unauthorized(reason: String) =
        ResponseStatusException(HttpStatus.UNAUTHORIZED, reason)

    companion object {
        private const val DUPLICATE_EMAIL = "A user with that email already exists."
        private const val INVALID_CREDENTIALS = "Invalid credentials."
        private const val INVALID_RESET_CODE = "Invalid or expired reset code."
        private const val INVALID_RESET_TOKEN = "Invalid or expired reset token."
        private const val RESET_TOKEN_BYTES = 32

        /**
         * Единая нормализация email. Раньше `register` искал по `email.trim()`, но сохранял
         * необрезанное значение, а `login` искал необрезанное — в итоге пользователь,
         * зарегистрированный с пробелом или заглавными буквами, не мог войти.
         */
        fun normalizeEmail(email: String): String = email.trim().lowercase()
    }
}
