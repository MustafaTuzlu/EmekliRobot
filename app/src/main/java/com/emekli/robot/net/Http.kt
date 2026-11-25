package com.emekli.robot.net

import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

/** Tüm sunucu istekleri için tek OkHttp istemcisi. Yönlendirmeler takip edilmez (3xx başarı sayılmaz). */
object Http {
    val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .followRedirects(false)
        .followSslRedirects(false)
        .build()
}
