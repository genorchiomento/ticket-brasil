package br.com.ticketbrasil.event

import br.com.ticketbrasil.shared.Fingerprinted
import br.com.ticketbrasil.shared.getInstant
import br.com.ticketbrasil.shared.getUuid
import br.com.ticketbrasil.shared.toDb
import org.springframework.jdbc.core.RowMapper
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Repository
import java.util.UUID

@Repository
class EventRepository(private val jdbc: JdbcClient) {

    /**
     * Inserts the event unless another row already owns [idempotencyKey].
     * NULL keys never conflict, so non-idempotent creates always insert.
     * @return false when the key was already taken.
     */
    fun insertIfAbsent(event: Event, idempotencyKey: String?, fingerprint: String?): Boolean =
        jdbc.sql(
            """
            INSERT INTO events (id, name, starts_at, capacity, available, idempotency_key, request_fingerprint, created_at, updated_at)
            VALUES (:id, :name, :startsAt, :capacity, :available, :key, :fingerprint, :createdAt, :createdAt)
            ON CONFLICT (idempotency_key) DO NOTHING
            """,
        )
            .param("id", event.id)
            .param("name", event.name)
            .param("startsAt", event.startsAt.toDb())
            .param("capacity", event.capacity)
            .param("available", event.available)
            .param("key", idempotencyKey)
            .param("fingerprint", fingerprint)
            .param("createdAt", event.createdAt.toDb())
            .update() == 1

    fun findById(id: UUID): Event? =
        jdbc.sql("SELECT * FROM events WHERE id = :id")
            .param("id", id)
            .query(rowMapper)
            .optional()
            .orElse(null)

    fun findByIdempotencyKey(key: String): Fingerprinted<Event>? =
        jdbc.sql("SELECT * FROM events WHERE idempotency_key = :key")
            .param("key", key)
            .query { rs, n -> Fingerprinted(rowMapper.mapRow(rs, n), rs.getString("request_fingerprint")) }
            .optional()
            .orElse(null)

    /**
     * The anti-oversell primitive. A single conditional UPDATE: PostgreSQL takes a row
     * lock and re-evaluates `available >= :quantity` against the latest committed version,
     * so concurrent callers on any number of API instances can never drive stock below zero.
     * @return false when there is not enough stock.
     */
    fun tryDecrement(id: UUID, quantity: Int): Boolean =
        jdbc.sql(
            """
            UPDATE events
               SET available = available - :quantity, updated_at = now()
             WHERE id = :id AND available >= :quantity
            """,
        )
            .param("id", id)
            .param("quantity", quantity)
            .update() == 1

    fun increment(id: UUID, quantity: Int) {
        jdbc.sql("UPDATE events SET available = available + :quantity, updated_at = now() WHERE id = :id")
            .param("id", id)
            .param("quantity", quantity)
            .update()
    }

    private val rowMapper = RowMapper { rs, _ ->
        Event(
            id = rs.getUuid("id"),
            name = rs.getString("name"),
            startsAt = rs.getInstant("starts_at"),
            capacity = rs.getInt("capacity"),
            available = rs.getInt("available"),
            createdAt = rs.getInstant("created_at"),
        )
    }
}
