package br.com.ticketbrasil.shared

import java.util.UUID

/**
 * Every business failure the API can report. Each subtype maps to exactly one
 * HTTP status and machine-readable code in [ApiExceptionHandler], so clients can
 * branch on `code` instead of parsing messages.
 */
sealed class DomainException(message: String) : RuntimeException(message)

class InvalidRequestException(message: String) : DomainException(message)

class EventNotFoundException(id: UUID) : DomainException("Event $id not found")

class EventAlreadyStartedException(id: UUID) : DomainException("Event $id has already started")

class ReservationNotFoundException(id: UUID) : DomainException("Reservation $id not found")

class InsufficientTicketsException(val eventId: UUID, val requested: Int, val available: Int) :
    DomainException("Requested $requested ticket(s) for event $eventId but only $available available")

class IdempotencyKeyReusedException(key: String) :
    DomainException("Idempotency-Key '$key' was already used with a different request payload")

class ReservationExpiredException(id: UUID) : DomainException("Reservation $id has expired")

class InvalidReservationStateException(id: UUID, status: String, action: String) :
    DomainException("Cannot $action reservation $id in status $status")
