package com.example.crm.network

import com.example.crm.models.*
import okhttp3.MultipartBody
import okhttp3.RequestBody
import retrofit2.Response
import retrofit2.http.*

interface ApiService {
    @POST("contacts/bulk/")
    suspend fun createContactsBulk(@Body contacts: List<Contact>): Response<List<Contact>>

    @POST("contacts/")
    suspend fun createContact(@Body contact: Contact): Response<Contact>

    @GET("contacts/")
    suspend fun getContacts(@Query("limit") limit: Int = 9999): Response<List<Contact>>

    @GET("contacts/map/")
    suspend fun getContactsMap(): Response<List<Map<String, String>>>

    @GET("contacts/stats/")
    suspend fun getContactsStats(@Query("recent_only") recentOnly: Boolean = false): Response<List<Map<String, Any>>>

    @GET("sync/last_timestamps/")
    suspend fun getLastSyncTimestamps(): Response<Map<String, String?>>

    @GET("timeline/all/")
    suspend fun getGlobalTimeline(): Response<List<Map<String, Any>>>

    @GET("contacts/phone/{phone_number}")
    suspend fun getContactByPhone(@Path("phone_number") phone: String): Response<Contact>

    @POST("contacts/{contact_id}/favorite")
    suspend fun toggleFavorite(
        @Path("contact_id") contactId: String,
        @Query("is_favorite") isFavorite: Boolean
    ): Response<Contact>

    @POST("calls/")
    suspend fun logCall(@Body call: CallRecord): Response<CallRecord>

    @POST("calls/bulk/")
    suspend fun logCallsBulk(@Body calls: List<CallRecord>): Response<Map<String, Any>>

    @POST("messages/")
    suspend fun logMessage(@Body message: MessageRecord): Response<MessageRecord>

    @POST("messages/bulk/")
    suspend fun logMessagesBulk(@Body messages: List<MessageRecord>): Response<Map<String, Any>>

    @GET("timeline/{contact_id}")
    suspend fun getTimeline(@Path("contact_id") contactId: String): Response<List<TimelineItem>>

    @Multipart
    @POST("recordings/upload/")
    suspend fun uploadRecording(
        @Part file: MultipartBody.Part,
        @Part("phone_number") phoneNumber: RequestBody,
        @Part("contact_name") contactName: RequestBody
    ): Response<Map<String, Any>>
}
