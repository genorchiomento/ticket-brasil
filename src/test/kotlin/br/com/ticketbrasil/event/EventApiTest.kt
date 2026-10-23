package br.com.ticketbrasil.event

import br.com.ticketbrasil.support.IntegrationTest
import org.junit.jupiter.api.Test
import java.time.Duration
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class EventApiTest : IntegrationTest() {

    private fun body(capacity: Any? = 100, name: Any? = "Rock in Rio", startsIn: Duration = Duration.ofDays(10)) =
        mapOf("name" to name, "startsAt" to clock.instant().plus(startsIn).toString(), "capacity" to capacity)

    @Test
    fun `creates event and exposes availability`() {
        val created = api.post("/events", body(capacity = 100))
        assertEquals(201, created.status)
        val id = created.json["id"].asString()
        assertEquals("/events/$id", created.header("Location"))

        val fetched = api.get("/events/$id")
        assertEquals(200, fetched.status)
        assertEquals(100, fetched.json["capacity"].asInt())
        assertEquals(100, fetched.json["available"].asInt())
        assertEquals(false, fetched.json["soldOut"].asBoolean())
    }

    @Test
    fun `same idempotency key returns the original event`() {
        val headers = mapOf("Idempotency-Key" to "evt-1")
        val first = api.post("/events", body(), headers)
        val second = api.post("/events", body(), headers)

        assertEquals(201, second.status)
        assertEquals(first.json["id"], second.json["id"])
        assertEquals("true", second.header("Idempotent-Replayed"))
        assertEquals(1, jdbc.sql("SELECT count(*) FROM events").query(Int::class.java).single())
    }

    @Test
    fun `same idempotency key with different payload is rejected`() {
        val headers = mapOf("Idempotency-Key" to "evt-2")
        api.post("/events", body(capacity = 100), headers)
        val conflict = api.post("/events", body(capacity = 200), headers)

        assertEquals(422, conflict.status)
        assertEquals("IDEMPOTENCY_KEY_REUSED", conflict.json["code"].asString())
    }

    @Test
    fun `rejects invalid payloads with field errors`() {
        val response = api.post("/events", body(capacity = 0, name = ""))
        assertEquals(400, response.status)
        assertEquals("VALIDATION_ERROR", response.json["code"].asString())
        val errors = response.json["errors"]
        val fields = (0 until errors.size()).map { errors[it]["field"].asString() }.toSet()
        assertEquals(setOf("capacity", "name"), fields)
    }

    @Test
    fun `rejects events in the past`() {
        val response = api.post("/events", body(startsIn = Duration.ofMinutes(-1)))
        assertEquals(400, response.status)
        assertEquals("INVALID_REQUEST", response.json["code"].asString())
    }

    @Test
    fun `rejects malformed json`() {
        val response = api.postRaw("/events", "{not json")
        assertEquals(400, response.status)
        assertEquals("MALFORMED_REQUEST", response.json["code"].asString())
    }

    @Test
    fun `unknown event returns 404 problem`() {
        val response = api.get("/events/${UUID.randomUUID()}")
        assertEquals(404, response.status)
        assertEquals("EVENT_NOT_FOUND", response.json["code"].asString())
        assertTrue(response.header("Content-Type")!!.startsWith("application/problem+json"))
    }

    @Test
    fun `invalid id returns 400`() {
        val response = api.get("/events/not-a-uuid")
        assertEquals(400, response.status)
        assertEquals("INVALID_PARAMETER", response.json["code"].asString())
    }
}
