package com.example.crm.models

data class Contact(
    val id: String? = null,
    val name: String,
    val phone_number: String,
    val organization: String? = null,
    val created_at: String? = null
)

data class CallRecord(
    val duration: Int,
    val timestamp: String,
    val contact_id: String? = null,
    val phone_number: String? = null,
    val direction: String = "OUT" // IN or OUT
)

data class MessageRecord(
    val content: String,
    val timestamp: String,
    val contact_id: String? = null,
    val phone_number: String? = null,
    val direction: String = "INBOUND" // INBOUND or OUTBOUND
)

data class TimelineItem(
    val type: String,
    val data: Map<String, Any>,
    val timestamp: String
)
