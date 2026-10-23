package br.com.ticketbrasil.reservation

import java.time.Instant
import java.util.UUID

enum class ReservationStatus {
    /** Seats are held until [Reservation.expiresAt]; awaits confirmation (payment). */
    PENDING,
    CONFIRMED,
    CANCELLED,

    /** Hold elapsed without confirmation; seats were returned to the event. */
    EXPIRED,
}

data class Reservation(
    val id: UUID,
    val eventId: UUID,
    val customerId: String,
    val quantity: Int,
    val status: ReservationStatus,
    val expiresAt: Instant,
    val createdAt: Instant,
    val updatedAt: Instant,
) {
    fun isHoldElapsedAt(now: Instant): Boolean =
        status == ReservationStatus.PENDING && !now.isBefore(expiresAt)

    /**
     * The sweeper releases elapsed holds asynchronously, so a PENDING row can outlive its
     * deadline by up to one sweep interval. Readers must never see such a row as PENDING.
     */
    fun effectiveStatusAt(now: Instant): ReservationStatus =
        if (isHoldElapsedAt(now)) ReservationStatus.EXPIRED else status
}
