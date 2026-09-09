package ru.gohasoft.wanderingtable.database.model

import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.Id
import jakarta.persistence.Index
import jakarta.persistence.Table
import jakarta.persistence.UniqueConstraint
import java.time.Instant

/** Стадия сброса пароля: сначала выданный по почте код, затем обменянный на него токен. */
enum class ResetStage { CODE, CONFIRMED }

/**
 * Одноразовый артефакт сброса пароля (хранится в виде SHA-256 хеша, как и refresh-токен).
 *
 * Одна таблица на обе стадии: строка [ResetStage.CODE] — это отправленный на почту шестизначный
 * код, строка [ResetStage.CONFIRMED] — выданный в обмен на него непрозрачный `resetToken`.
 * Хранение в БД, а не JWT со своим `type`, выбрано ради одноразовости и отзываемости: обе строки
 * удаляются при использовании, а новый запрос кода удаляет все прежние строки пользователя.
 *
 * Первичный ключ суррогатный — расход строки идёт удалением по [id], и проигравший гонку запрос
 * получает `0` затронутых строк (см. `PasswordResetTokenRepository.consumeById`).
 */
@Entity
@Table(
    name = "password_reset_tokens",
    uniqueConstraints = [
        UniqueConstraint(
            name = "uk_password_reset_tokens_hashed_token",
            columnNames = ["hashed_token"]
        )
    ],
    indexes = [
        Index(name = "idx_password_reset_tokens_user_id", columnList = "user_id"),
        Index(name = "idx_password_reset_tokens_expires_at", columnList = "expires_at")
    ]
)
data class PasswordResetToken(
    @Id val id: ObjectId = ObjectId.get(),
    val userId: ObjectId,
    @Enumerated(EnumType.STRING) val stage: ResetStage,
    val hashedToken: String,
    val expiresAt: Instant,
    val createdAt: Instant = nowTruncated()
)
