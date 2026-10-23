package br.com.ticketbrasil.event

import br.com.ticketbrasil.support.IntegrationTest
import org.junit.jupiter.api.Test
import org.springframework.test.context.TestPropertySource
import kotlin.test.assertEquals

/** Runs with the availability cache on (every other suite disables it to read the database directly). */
@TestPropertySource(properties = ["ticket.availability-cache-ttl=PT1H"])
class AvailabilityCacheTest : IntegrationTest() {

    private fun available(eventId: Any) = api.get("/events/$eventId").json["available"].asInt()

    @Test
    fun `serves availability from the cache and drops it on a local write`() {
        val eventId = createEvent(capacity = 10)
        assertEquals(10, available(eventId))

        // Changed behind the app's back: a cached read must not see it.
        jdbc.sql("UPDATE events SET available = 3 WHERE id = :id").param("id", eventId).update()
        assertEquals(10, available(eventId))

        // A reservation on this instance evicts the entry, so the next read hits the database.
        assertEquals(201, reserve(eventId, quantity = 2).status)
        assertEquals(1, available(eventId))
    }

    @Test
    fun `unknown events are not cached`() {
        val response = api.get("/events/00000000-0000-0000-0000-000000000000")
        assertEquals(404, response.status)
        assertEquals("EVENT_NOT_FOUND", response.json["code"].asString())
    }
}
