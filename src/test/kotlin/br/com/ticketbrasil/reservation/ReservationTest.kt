package br.com.ticketbrasil.reservation

import org.junit.jupiter.api.Test
import java.time.Duration
import java.time.Instant
import java.util.UUID
import kotlin.test.assertEquals

class ReservationTest {
    private val now = Instant.parse("2026-10-22T12:00:00Z")

    private fun reservation(status: ReservationStatus, expiresAt: Instant = now.plus(Duration.ofMinutes(10))) =
        Reservation(UUID.randomUUID(), UUID.randomUUID(), "c", 1, status, expiresAt, now, now)

    @Test
    fun `pending reservation within hold stays pending`() {
        assertEquals(ReservationStatus.PENDING, reservation(ReservationStatus.PENDING).effectiveStatusAt(now))
    }

    @Test
    fun `pending reservation is expired exactly at the deadline`() {
        val r = reservation(ReservationStatus.PENDING, expiresAt = now)
        assertEquals(ReservationStatus.EXPIRED, r.effectiveStatusAt(now))
    }

    @Test
    fun `terminal statuses are not affected by the deadline`() {
        val later = now.plus(Duration.ofDays(1))
        listOf(ReservationStatus.CONFIRMED, ReservationStatus.CANCELLED, ReservationStatus.EXPIRED).forEach {
            assertEquals(it, reservation(it).effectiveStatusAt(later))
        }
    }
}
