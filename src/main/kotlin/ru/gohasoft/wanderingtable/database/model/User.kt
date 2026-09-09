package ru.gohasoft.wanderingtable.database.model

import jakarta.persistence.CollectionTable
import jakarta.persistence.Column
import jakarta.persistence.ElementCollection
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.FetchType
import jakarta.persistence.Id
import jakarta.persistence.JoinColumn
import jakarta.persistence.Table
import jakarta.persistence.UniqueConstraint
import jakarta.persistence.Version

@Entity
@Table(
    name = "users",
    // Уникальность email обеспечивает БД, а не проверка в сервисе: без этого индекса
    // две одновременные регистрации создают двух пользователей с одним email.
    uniqueConstraints = [UniqueConstraint(name = "uk_users_email", columnNames = ["email"])]
)
data class User(
    @Id val id: ObjectId = ObjectId.get(),
    var name: String,
    /** Всегда в нормализованном виде: trim + lowercase (см. `AuthService.normalizeEmail`). */
    val email: String,
    var hashedPassword: String,
    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(
        name = "user_roles",
        joinColumns = [JoinColumn(name = "user_id")]
    )
    @Enumerated(EnumType.STRING)
    @Column(name = "role")
    var roles: Set<Role> = setOf(Role.PLAYER),
    /** Оптимистичная блокировка: защищает от потери изменений при одновременной правке профиля и ролей. */
    @Version var version: Long = 0
) {
    fun hasRole(role: Role): Boolean = role in roles
}
