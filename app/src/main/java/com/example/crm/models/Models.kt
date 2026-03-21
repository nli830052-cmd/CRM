package com.example.crm.models

data class Contact(
    val id: String? = null,
    val name: String,
    val phone_number: String,
    val organization: String? = null,
    val created_at: String? = null
)

data class CallRecord(
    val contact_id: String,
    val direction: String = "OUT", // IN or OUT
    val duration: Int,
    val timestamp: String
)

data class MessageRecord(
    val contact_id: String,
    val content: String,
    val direction: String, // INBOUND or OUTBOUND
    val timestamp: String
)

data class TimelineItem(
    val type: String,
    val data: Map<String, Any>,
    val timestamp: String
)
