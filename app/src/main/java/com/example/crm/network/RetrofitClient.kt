package com.example.crm.network

import okhttp3.OkHttpClient
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import java.util.concurrent.TimeUnit

object RetrofitClient {
    private const val BASE_URL = "http://192.168.200.178:8000/"

    // 녹음 파일 1533개 업로드를 위해 타임아웃을 충분히 길게 설정
    private val okHttpClient = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)       // 연결 대기: 30초
        .readTimeout(300, TimeUnit.SECONDS)         // 응답 대기: 5분
        .writeTimeout(300, TimeUnit.SECONDS)        // 업로드 대기: 5분
        .build()

    val apiService: ApiService by lazy {
        Retrofit.Builder()
            .baseUrl(BASE_URL)
            .client(okHttpClient)
            .addConverterFactory(GsonConverterFactory.create())
            .build()
            .create(ApiService::class.java)
    }
}
