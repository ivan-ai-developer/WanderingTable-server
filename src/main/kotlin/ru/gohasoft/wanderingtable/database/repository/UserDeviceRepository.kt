package ru.gohasoft.wanderingtable.database.repository

import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import ru.gohasoft.wanderingtable.database.model.UserDevice

interface UserDeviceRepository : JpaRepository<UserDevice, String> {

    fun findByFcmToken(fcmToken: String): UserDevice?

    fun findByUserId(userId: String): List<UserDevice>

    fun findByUserIdNot(userId: String): List<UserDevice>

    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("delete from UserDevice d where d.userId = :userId and d.fcmToken = :fcmToken")
    fun unregister(
        @Param("userId") userId: String,
        @Param("fcmToken") fcmToken: String
    ): Int
}
