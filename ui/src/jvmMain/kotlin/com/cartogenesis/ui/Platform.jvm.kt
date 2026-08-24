package com.cartogenesis.ui

import java.text.DateFormat
import java.util.Date
import java.util.UUID

actual fun epochMillis(): Long = System.currentTimeMillis()

actual fun randomId(): String = UUID.randomUUID().toString()

actual fun formatTimestamp(millis: Long): String =
    DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(millis))
