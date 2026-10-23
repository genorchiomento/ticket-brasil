package br.com.ticketbrasil.shared

import java.sql.ResultSet
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.UUID

// The PostgreSQL driver maps TIMESTAMPTZ to OffsetDateTime, not Instant.
fun Instant.toDb(): OffsetDateTime = atOffset(ZoneOffset.UTC)

fun ResultSet.getInstant(column: String): Instant = getObject(column, OffsetDateTime::class.java).toInstant()

fun ResultSet.getUuid(column: String): UUID = getObject(column, UUID::class.java)
