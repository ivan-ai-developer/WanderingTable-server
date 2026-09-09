package ru.gohasoft.wanderingtable.database.repository

import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import ru.gohasoft.wanderingtable.database.model.PasswordResetToken
import ru.gohasoft.wanderingtable.database.model.ResetStage
import java.time.Instant

interface PasswordResetTokenRepository : JpaRepository<PasswordResetToken, String> {

    fun findByHashedTokenAndStage(hashedToken: String, stage: ResetStage): PasswordResetToken?

    /**
     * Атомарно расходует строку, возвращая количество удалённых.
     *
     * `0` означает, что строку уже израсходовал конкурентный запрос. Как и в
     * [RefreshTokenRepository.consume], это bulk-delete через [Query], а не производный
     * `deleteBy...`: производный вариант сначала загружает сущность и возвращает число
     * *загруженных* строк — при гонке оба запроса получили бы по единице.
     */
    @Modifying
    @Query("delete from PasswordResetToken t where t.id = :id")
    fun consumeById(@Param("id") id: String): Int

    /** Новый запрос кода отменяет все прежние: у пользователя всегда живёт не более одного. */
    @Modifying
    @Query("delete from PasswordResetToken t where t.userId = :userId")
    fun deleteAllForUser(@Param("userId") userId: String): Int

    @Modifying
    @Query("delete from PasswordResetToken t where t.expiresAt < :now")
    fun deleteExpired(@Param("now") now: Instant): Int
}
