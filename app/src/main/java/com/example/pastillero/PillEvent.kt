package com.pokkzdev.pastillapp

import java.util.Date

/**
 * Data class representing a pill event scheduled for a specific date
 * @property id Unique identifier for the event
 * @property date The date when the pill should be taken
 * @property pillName Name of the pill/medication
 * @property amount Quantity/dosage of the pill
 * @property time Optional time string for when to take the pill
 * @property dispensed Whether the pill has been dispensed by the ESP32
 */
data class PillEvent(
    val id: String = java.util.UUID.randomUUID().toString(),
    val date: Date,
    val pillName: String,
    val amount: String,
    val time: String = "",
    val dispensed: Boolean = false
)
