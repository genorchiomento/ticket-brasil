package br.com.ticketbrasil.reservation

import br.com.ticketbrasil.support.IntegrationTest
import org.junit.jupiter.api.Test
import java.time.Duration
import java.util.UUID
import kotlin.test.assertEquals

class ReservationApiTest : IntegrationTest() {

    @Test
    fun `reserving holds seats and the reservation can be read back`() {
        val eventId = createEvent(capacity = 10)
        val created = reserve(eventId, quantity = 3)

        assertEquals(201, created.status)
        assertEquals("PENDING", created.json["status"].asString())
        assertEquals("false", created.header("Idempotent-Replayed"))
        assertEquals(7, availableOf(eventId))
        assertEquals(7, api.get("/events/$eventId").json["available"].asInt())

        val id = created.json["id"].asString()
        val fetched = api.get("/reservations/$id")
        assertEquals(200, fetched.status)
        assertEquals(3, fetched.json["quantity"].asInt())
        assertEquals(eventId.toString(), fetched.json["eventId"].asString())
    }

    @Test
    fun `cannot reserve more than available`() {
        val eventId = createEvent(capacity = 2)
        val response = reserve(eventId, quantity = 3)

        assertEquals(409, response.status)
        assertEquals("INSUFFICIENT_TICKETS", response.json["code"].asString())
        assertEquals(2, response.json["available"].asInt())
        assertEquals(2, availableOf(eventId))
    }

    @Test
    fun `idempotent retry returns the same reservation without taking more seats`() {
        val eventId = createEvent(capacity = 10)
        val first = reserve(eventId, quantity = 2, key = "retry-1")
        val retry = reserve(eventId, quantity = 2, key = "retry-1")

        assertEquals(201, retry.status)
        assertEquals("true", retry.header("Idempotent-Replayed"))
        assertEquals(first.json, retry.json, "replay must return the original representation")
        assertEquals(8, availableOf(eventId))
    }

    @Test
    fun `reusing a key for a different request is rejected`() {
        val eventId = createEvent(capacity = 10)
        reserve(eventId, quantity = 2, key = "reuse-1")
        val response = reserve(eventId, quantity = 5, key = "reuse-1")

        assertEquals(422, response.status)
        assertEquals("IDEMPOTENCY_KEY_REUSED", response.json["code"].asString())
        assertEquals(8, availableOf(eventId))
    }

    @Test
    fun `a sold out attempt does not burn the idempotency key`() {
        val eventId = createEvent(capacity = 1)
        val holder = reserve(eventId, quantity = 1)
        assertEquals(409, reserve(eventId, quantity = 1, key = "late").status)

        api.delete("/reservations/${holder.json["id"].asString()}")
        assertEquals(201, reserve(eventId, quantity = 1, key = "late").status)
    }

    @Test
    fun `idempotency key header is required`() {
        val eventId = createEvent(capacity = 10)
        val response = api.post("/events/$eventId/reservations", mapOf("customerId" to "c", "quantity" to 1))

        assertEquals(400, response.status)
        assertEquals("INVALID_REQUEST", response.json["code"].asString())
    }

    @Test
    fun `quantity must respect per reservation limit`() {
        val eventId = createEvent(capacity = 100)
        assertEquals(400, reserve(eventId, quantity = 0).status)
        assertEquals(400, reserve(eventId, quantity = 11).status)
        assertEquals(100, availableOf(eventId))
    }

    @Test
    fun `reserving for unknown event returns 404`() {
        val response = reserve(UUID.randomUUID())
        assertEquals(404, response.status)
        assertEquals("EVENT_NOT_FOUND", response.json["code"].asString())
    }

    @Test
    fun `cannot reserve after the event started`() {
        val eventId = createEvent(capacity = 10, startsIn = Duration.ofHours(1))
        clock.advance(Duration.ofHours(2))

        val response = reserve(eventId)
        assertEquals(409, response.status)
        assertEquals("EVENT_ALREADY_STARTED", response.json["code"].asString())
    }

    @Test
    fun `cancelling releases seats and is idempotent`() {
        val eventId = createEvent(capacity = 10)
        val id = reserve(eventId, quantity = 4).json["id"].asString()

        val cancelled = api.delete("/reservations/$id")
        assertEquals(200, cancelled.status)
        assertEquals("CANCELLED", cancelled.json["status"].asString())
        assertEquals(10, availableOf(eventId))

        val again = api.delete("/reservations/$id")
        assertEquals(200, again.status)
        assertEquals("CANCELLED", again.json["status"].asString())
        assertEquals(10, availableOf(eventId))
    }

    @Test
    fun `confirmed reservation can be cancelled and releases seats`() {
        val eventId = createEvent(capacity = 10)
        val id = reserve(eventId, quantity = 2).json["id"].asString()

        val confirmed = api.post("/reservations/$id/confirm")
        assertEquals(200, confirmed.status)
        assertEquals("CONFIRMED", confirmed.json["status"].asString())
        assertEquals(200, api.post("/reservations/$id/confirm").status)

        api.delete("/reservations/$id")
        assertEquals(10, availableOf(eventId))
    }

    @Test
    fun `cancelled reservation cannot be confirmed`() {
        val eventId = createEvent(capacity = 10)
        val id = reserve(eventId).json["id"].asString()
        api.delete("/reservations/$id")

        val response = api.post("/reservations/$id/confirm")
        assertEquals(409, response.status)
        assertEquals("INVALID_RESERVATION_STATE", response.json["code"].asString())
    }

    @Test
    fun `unknown reservation returns 404 for every operation`() {
        val id = UUID.randomUUID()
        listOf(api.get("/reservations/$id"), api.delete("/reservations/$id"), api.post("/reservations/$id/confirm"))
            .forEach {
                assertEquals(404, it.status)
                assertEquals("RESERVATION_NOT_FOUND", it.json["code"].asString())
            }
    }
}
