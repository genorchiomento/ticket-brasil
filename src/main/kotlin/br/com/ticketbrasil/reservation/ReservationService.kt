package br.com.ticketbrasil.reservation

import br.com.ticketbrasil.config.TicketProperties
import br.com.ticketbrasil.event.EventRepository
import br.com.ticketbrasil.event.EventService
import br.com.ticketbrasil.reservation.ReservationStatus.CANCELLED
import br.com.ticketbrasil.reservation.ReservationStatus.CONFIRMED
import br.com.ticketbrasil.reservation.ReservationStatus.EXPIRED
import br.com.ticketbrasil.reservation.ReservationStatus.PENDING
import br.com.ticketbrasil.shared.EventAlreadyStartedException
import br.com.ticketbrasil.shared.EventNotFoundException
import br.com.ticketbrasil.shared.Idempotency
import br.com.ticketbrasil.shared.IdempotencyKeyReusedException
import br.com.ticketbrasil.shared.IdempotentResult
import br.com.ticketbrasil.shared.InsufficientTicketsException
import br.com.ticketbrasil.shared.InvalidRequestException
import br.com.ticketbrasil.shared.InvalidReservationStateException
import br.com.ticketbrasil.shared.ReservationExpiredException
import br.com.ticketbrasil.shared.ReservationNotFoundException
import org.springframework.stereotype.Service
import org.springframework.transaction.support.TransactionTemplate
import java.time.Clock
import java.util.UUID

data class ReserveCommand(val eventId: UUID, val customerId: String, val quantity: Int)

@Service
class ReservationService(
    private val reservations: ReservationRepository,
    private val events: EventRepository,
    private val eventService: EventService,
    private val tx: TransactionTemplate,
    private val clock: Clock,
    private val properties: TicketProperties,
) {

    /**
     * Holds seats for a customer. Lock order is always reservation row -> event row
     * (same as cancel and the sweeper) so concurrent flows cannot deadlock.
     */
    fun reserve(command: ReserveCommand, idempotencyKey: String): IdempotentResult<Reservation> {
        validate(command)
        val fingerprint = Idempotency.fingerprint(command.eventId, command.customerId, command.quantity)

        val result = tx.execute {
            val now = clock.instant()
            val event = events.findById(command.eventId) ?: throw EventNotFoundException(command.eventId)
            val candidate = Reservation(
                id = UUID.randomUUID(),
                eventId = event.id,
                customerId = command.customerId,
                quantity = command.quantity,
                status = PENDING,
                expiresAt = now.plus(properties.reservationTtl),
                createdAt = now,
                updatedAt = now,
            )

            if (!reservations.insertIfAbsent(candidate, idempotencyKey, fingerprint)) {
                val existing = reservations.findByIdempotencyKey(idempotencyKey)
                    ?: error("Idempotency key conflict reported but no row found for '$idempotencyKey'")
                if (existing.fingerprint != fingerprint) throw IdempotencyKeyReusedException(idempotencyKey)
                return@execute IdempotentResult(existing.value, replayed = true)
            }

            if (event.hasStartedAt(now)) throw EventAlreadyStartedException(event.id)

            // Throwing here rolls back the INSERT above, freeing the idempotency key so the
            // client may retry the same key later if seats come back.
            if (!events.tryDecrement(event.id, command.quantity)) {
                val available = events.findById(event.id)?.available ?: 0
                throw InsufficientTicketsException(event.id, command.quantity, available)
            }
            IdempotentResult(candidate, replayed = false)
        }!!

        if (!result.replayed) eventService.evictAvailability(command.eventId)
        return result
    }

    fun get(id: UUID): Reservation =
        reservations.findById(id) ?: throw ReservationNotFoundException(id)

    /** Idempotent: cancelling an already cancelled reservation returns it unchanged. */
    fun cancel(id: UUID): Reservation {
        val cancelled = tx.execute {
            val now = clock.instant()
            val reservation = reservations.findByIdForUpdate(id) ?: throw ReservationNotFoundException(id)
            when {
                reservation.status == CANCELLED -> reservation
                reservation.effectiveStatusAt(now) == EXPIRED -> throw ReservationExpiredException(id)
                else -> {
                    reservations.updateStatus(id, CANCELLED, now)
                    events.increment(reservation.eventId, reservation.quantity)
                    reservation.copy(status = CANCELLED, updatedAt = now)
                }
            }
        }!!
        eventService.evictAvailability(cancelled.eventId)
        return cancelled
    }

    /** Turns a hold into a sale (e.g. after payment). Idempotent for CONFIRMED reservations. */
    fun confirm(id: UUID): Reservation = tx.execute {
        val now = clock.instant()
        val reservation = reservations.findByIdForUpdate(id) ?: throw ReservationNotFoundException(id)
        when (reservation.effectiveStatusAt(now)) {
            CONFIRMED -> reservation
            EXPIRED -> throw ReservationExpiredException(id)
            CANCELLED -> throw InvalidReservationStateException(id, CANCELLED.name, "confirm")
            PENDING -> {
                reservations.updateStatus(id, CONFIRMED, now)
                reservation.copy(status = CONFIRMED, updatedAt = now)
            }
        }
    }!!

    private fun validate(command: ReserveCommand) {
        if (command.customerId.isBlank()) throw InvalidRequestException("customerId must not be blank")
        if (command.quantity !in 1..properties.maxTicketsPerReservation) {
            throw InvalidRequestException("quantity must be between 1 and ${properties.maxTicketsPerReservation}")
        }
    }
}
