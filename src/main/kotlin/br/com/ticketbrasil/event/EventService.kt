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
import java.time.temporal.ChronoUnit
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
        // Stored at TIMESTAMPTZ (microsecond) precision so the first response and any replay are identical.
        val startsAt = command.startsAt.truncatedTo(ChronoUnit.MICROS)
        val fingerprint = idempotencyKey?.let { Idempotency.fingerprint(command.name, startsAt, command.capacity) }

        // A replay must return the original event even after its start date has passed.
        if (idempotencyKey != null) replayOf(idempotencyKey, fingerprint)?.let { return it }

        val now = clock.instant()
        if (command.name.isBlank()) throw InvalidRequestException("name must not be blank")
        if (command.capacity <= 0) throw InvalidRequestException("capacity must be positive")
        if (!startsAt.isAfter(now)) throw InvalidRequestException("startsAt must be in the future")

        val event = Event(
            id = UUID.randomUUID(),
            name = command.name.trim(),
            startsAt = startsAt,
            capacity = command.capacity,
            available = command.capacity,
            createdAt = now,
        )

        return tx.execute {
            if (events.insertIfAbsent(event, idempotencyKey, fingerprint)) {
                IdempotentResult(event, replayed = false)
            } else {
                // A concurrent request with the same key committed between the lookup and the insert.
                replayOf(idempotencyKey!!, fingerprint)
                    ?: error("Idempotency key conflict reported but no row found for '$idempotencyKey'")
            }
        }!!
    }

    private fun replayOf(idempotencyKey: String, fingerprint: String?): IdempotentResult<Event>? {
        val existing = events.findByIdempotencyKey(idempotencyKey) ?: return null
        if (existing.fingerprint != fingerprint) throw IdempotencyKeyReusedException(idempotencyKey)
        return IdempotentResult(existing.value, replayed = true)
    }

    /**
     * Loads through the cache so concurrent misses for the same event share one query
     * instead of stampeding the database when an entry expires. Because [evictAvailability]
     * waits for an in-flight load of the same key, a value read before a local write can
     * never be cached after it.
     */
    fun getAvailability(id: UUID): Event =
        availabilityCache.get(id) { events.findById(it) ?: throw EventNotFoundException(it) }

    /** Drops the local snapshot so this instance reads its own writes immediately. */
    fun evictAvailability(id: UUID) = availabilityCache.invalidate(id)
}
