package br.com.ticketbrasil.event

import br.com.ticketbrasil.config.TicketProperties
import br.com.ticketbrasil.shared.EventNotFoundException
import br.com.ticketbrasil.shared.Idempotency
import br.com.ticketbrasil.shared.IdempotencyKeyReusedException
import br.com.ticketbrasil.shared.IdempotentResult
import br.com.ticketbrasil.shared.InvalidRequestException
import com.github.benmanes.caffeine.cache.Cache
import com.github.benmanes.caffeine.cache.Caffeine
import org.springframework.stereotype.Service
import org.springframework.transaction.support.TransactionTemplate
import java.time.Clock
import java.time.Instant
import java.util.UUID

data class CreateEventCommand(val name: String, val startsAt: Instant, val capacity: Int)

@Service
class EventService(
    private val events: EventRepository,
    private val tx: TransactionTemplate,
    private val clock: Clock,
    properties: TicketProperties,
) {
    /**
     * Availability is read-heavy during a flash sale and is allowed to be eventually
     * consistent. Each instance keeps a tiny local cache so a burst of GETs costs one
     * query per event per TTL window. Correctness never depends on this value: the
     * reservation path always decides against the database row.
     */
    private val availabilityCache: Cache<UUID, Event> = Caffeine.newBuilder()
        .expireAfterWrite(properties.availabilityCacheTtl)
        .maximumSize(10_000)
        .build()

    fun create(command: CreateEventCommand, idempotencyKey: String?): IdempotentResult<Event> {
        val now = clock.instant()
        if (command.name.isBlank()) throw InvalidRequestException("name must not be blank")
        if (command.capacity <= 0) throw InvalidRequestException("capacity must be positive")
        if (!command.startsAt.isAfter(now)) throw InvalidRequestException("startsAt must be in the future")

        val fingerprint = idempotencyKey?.let {
            Idempotency.fingerprint(command.name, command.startsAt, command.capacity)
        }
        val event = Event(
            id = UUID.randomUUID(),
            name = command.name.trim(),
            startsAt = command.startsAt,
            capacity = command.capacity,
            available = command.capacity,
            createdAt = now,
        )

        return tx.execute {
            if (events.insertIfAbsent(event, idempotencyKey, fingerprint)) {
                IdempotentResult(event, replayed = false)
            } else {
                val existing = events.findByIdempotencyKey(idempotencyKey!!)
                    ?: error("Idempotency key conflict reported but no row found for '$idempotencyKey'")
                if (existing.fingerprint != fingerprint) throw IdempotencyKeyReusedException(idempotencyKey)
                IdempotentResult(existing.value, replayed = true)
            }
        }!!
    }

    fun getAvailability(id: UUID): Event =
        availabilityCache.getIfPresent(id)
            ?: (events.findById(id) ?: throw EventNotFoundException(id)).also { availabilityCache.put(id, it) }

    /** Drops the local snapshot so this instance reads its own writes immediately. */
    fun evictAvailability(id: UUID) = availabilityCache.invalidate(id)
}
