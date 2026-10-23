package br.com.ticketbrasil.event

import java.time.Instant
import java.util.UUID

data class Event(
    val id: UUID,
    val name: String,
    val startsAt: Instant,
    val capacity: Int,
    val available: Int,
    val createdAt: Instant,
) {
    val soldOut: Boolean get() = available == 0

    fun hasStartedAt(now: Instant): Boolean = !now.isBefore(startsAt)
}
