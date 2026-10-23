package br.com.ticketbrasil.reservation

import br.com.ticketbrasil.shared.Fingerprinted
import br.com.ticketbrasil.shared.getInstant
import br.com.ticketbrasil.shared.getUuid
import br.com.ticketbrasil.shared.toDb
import org.springframework.jdbc.core.RowMapper
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Repository
import java.time.Instant
import java.util.UUID

@Repository
class ReservationRepository(private val jdbc: JdbcClient) {

    /**
     * Claims [idempotencyKey]. If a concurrent transaction holds the same key, PostgreSQL
     * blocks this INSERT until that transaction finishes, then either inserts (other side
     * rolled back) or does nothing (other side committed). Duplicates are impossible.
     * @return false when the key already belongs to a committed reservation.
     */
    fun insertIfAbsent(reservation: Reservation, idempotencyKey: String, fingerprint: String): Boolean =
        jdbc.sql(
            """
            INSERT INTO reservations (id, event_id, customer_id, quantity, status, idempotency_key,
                                      request_fingerprint, expires_at, created_at, updated_at)
            VALUES (:id, :eventId, :customerId, :quantity, :status, :key, :fingerprint, :expiresAt, :createdAt, :createdAt)
            ON CONFLICT (idempotency_key) DO NOTHING
            """,
        )
            .param("id", reservation.id)
            .param("eventId", reservation.eventId)
            .param("customerId", reservation.customerId)
            .param("quantity", reservation.quantity)
            .param("status", reservation.status.name)
            .param("key", idempotencyKey)
            .param("fingerprint", fingerprint)
            .param("expiresAt", reservation.expiresAt.toDb())
            .param("createdAt", reservation.createdAt.toDb())
            .update() == 1

    fun findById(id: UUID): Reservation? =
        jdbc.sql("SELECT * FROM reservations WHERE id = :id")
            .param("id", id)
            .query(rowMapper)
            .optional()
            .orElse(null)

    /** Row-locks the reservation so state transitions on it are serialized across instances. */
    fun findByIdForUpdate(id: UUID): Reservation? =
        jdbc.sql("SELECT * FROM reservations WHERE id = :id FOR UPDATE")
            .param("id", id)
            .query(rowMapper)
            .optional()
            .orElse(null)

    fun findByIdempotencyKey(key: String): Fingerprinted<Reservation>? =
        jdbc.sql("SELECT * FROM reservations WHERE idempotency_key = :key")
            .param("key", key)
            .query { rs, n -> Fingerprinted(rowMapper.mapRow(rs, n), rs.getString("request_fingerprint")) }
            .optional()
            .orElse(null)

    fun updateStatus(id: UUID, status: ReservationStatus, now: Instant) {
        jdbc.sql("UPDATE reservations SET status = :status, updated_at = :now WHERE id = :id")
            .param("id", id)
            .param("status", status.name)
            .param("now", now.toDb())
            .update()
    }

    /**
     * Locks a batch of elapsed PENDING holds. SKIP LOCKED lets every API instance run the
     * sweeper concurrently: each one grabs a disjoint batch instead of blocking or
     * double-releasing seats, and rows locked by an in-flight confirm/cancel are left alone.
     */
    fun lockElapsedHolds(now: Instant, limit: Int): List<Reservation> =
        jdbc.sql(
            """
            SELECT * FROM reservations
             WHERE status = 'PENDING' AND expires_at <= :now
             ORDER BY expires_at
             LIMIT :limit
               FOR UPDATE SKIP LOCKED
            """,
        )
            .param("now", now.toDb())
            .param("limit", limit)
            .query(rowMapper)
            .list()

    fun markExpired(ids: Collection<UUID>, now: Instant) {
        jdbc.sql("UPDATE reservations SET status = 'EXPIRED', updated_at = :now WHERE id IN (:ids)")
            .param("ids", ids)
            .param("now", now.toDb())
            .update()
    }

    private val rowMapper = RowMapper { rs, _ ->
        Reservation(
            id = rs.getUuid("id"),
            eventId = rs.getUuid("event_id"),
            customerId = rs.getString("customer_id"),
            quantity = rs.getInt("quantity"),
            status = ReservationStatus.valueOf(rs.getString("status")),
            expiresAt = rs.getInstant("expires_at"),
            createdAt = rs.getInstant("created_at"),
            updatedAt = rs.getInstant("updated_at"),
        )
    }
}
