package ru.gohasoft.wanderingtable.security

import java.time.Duration
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap

/**
 * Скользящее окно попыток, по одному на ключ (IP или email).
 *
 * Лимитер **локален для инстанса**: при горизонтальном масштабировании фактический лимит
 * умножается на число реплик. Для распределённого лимита понадобился бы общий счётчик
 * (например, в Redis).
 *
 * Не бин: конкретные лимитеры ([RegisterRateLimiter], [PasswordResetRateLimiter]) оборачивают
 * его каждый со своими свойствами и своей задачей вытеснения.
 */
class SlidingWindowRateLimiter(
    private val maxRequests: Int,
    windowSeconds: Long
) {

    private class Window(var count: Int, var start: Instant)

    private val window: Duration = Duration.ofSeconds(windowSeconds)
    private val windows = ConcurrentHashMap<String, Window>()

    fun tryAcquire(key: String): Boolean {
        // Решение принимается внутри compute: раньше счётчик читался уже после выхода из
        // атомарного блока, и конкурентный поток успевал его увеличить — из-за чего лимитер
        // под нагрузкой отклонял запросы, которые должен был пропустить.
        var allowed = false
        windows.compute(key) { _, existing ->
            val now = Instant.now()
            val updated = if (existing == null || Duration.between(existing.start, now) > window) {
                Window(count = 1, start = now)
            } else {
                existing.apply { count++ }
            }
            allowed = updated.count <= maxRequests
            updated
        }
        return allowed
    }

    /**
     * Удаляет протухшие окна: без этого map рос неограниченно по числу уникальных ключей,
     * то есть был вектором исчерпания памяти.
     */
    fun evictExpiredWindows() {
        val now = Instant.now()
        windows.entries.removeIf { Duration.between(it.value.start, now) > window }
    }
}
