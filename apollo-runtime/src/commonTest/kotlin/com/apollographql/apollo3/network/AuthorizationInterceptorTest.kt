package com.apollographql.apollo3.network

import com.apollographql.apollo3.api.http.HttpHeader
import com.apollographql.apollo3.api.http.HttpMethod
import com.apollographql.apollo3.api.http.HttpRequest
import com.apollographql.apollo3.api.http.HttpResponse
import com.apollographql.apollo3.mpp.currentTimeMillis
import com.apollographql.apollo3.network.http.DefaultHttpInterceptorChain
import com.apollographql.apollo3.network.http.HttpInterceptor
import com.apollographql.apollo3.network.http.HttpInterceptorChain
import com.apollographql.apollo3.testing.runTest
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import okio.ByteString.Companion.encodeUtf8
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.fail

class AuthorizationInterceptorTest {
  class EngineHelper(tokenProvider: AuthorizationInterceptor.TokenProvider, queueSize: Int = 1) {

    private val chain = DefaultHttpInterceptorChain(
        interceptors = listOf(
            AuthorizationInterceptor(tokenProvider, queueSize),
            object : HttpInterceptor {
              override suspend fun intercept(request: HttpRequest, chain: HttpInterceptorChain): HttpResponse {
                val statusCode = request.headers.value("statusCode")?.toIntOrNull() ?: 200
                val delayMillis = request.headers.value("delayMillis")?.toLongOrNull() ?: 0L

                delay(delayMillis)

                return HttpResponse.Builder(
                    statusCode = statusCode
                ).body(
                    bodyString = request.headers.value("Authorization").toString().encodeUtf8()
                ).build()
              }
            }
        ),
        0
    )

    private fun List<HttpHeader>.value(key: String): String? = firstOrNull { it.name == key }?.value

    suspend fun executeRequest(vararg headers: Pair<String, String>): String {
      val request = HttpRequest.Builder(HttpMethod.Get, "unused")
          .headers(headers.map { HttpHeader(it.first, it.second) })
          .build()

      return chain.proceed(request).body!!.readUtf8()
    }

  }

  @Test
  fun noInitialToken() = runTest {
    val tokenProvider = object : AuthorizationInterceptor.TokenProvider {
      private var count = 0
      override suspend fun loadToken(): AuthorizationInterceptor.Token? {
        return null
      }

      override suspend fun refreshToken(oldToken: String?): AuthorizationInterceptor.Token {
        assertEquals(oldToken, null)
        assertEquals(0, count)
        count++
        return AuthorizationInterceptor.Token("0", Long.MAX_VALUE)
      }
    }

    val helper = EngineHelper(tokenProvider)
    assertEquals("Bearer 0", helper.executeRequest())
  }

  @Test
  fun expiredInitialToken() = runTest {
    val tokenProvider = object : AuthorizationInterceptor.TokenProvider {
      private var count = 0
      override suspend fun loadToken(): AuthorizationInterceptor.Token? {
        return AuthorizationInterceptor.Token("0", currentTimeSeconds())
      }

      override suspend fun refreshToken(oldToken: String?): AuthorizationInterceptor.Token {
        assertEquals(oldToken, "0")
        assertEquals(0, count)
        count++
        return AuthorizationInterceptor.Token("1", Long.MAX_VALUE)
      }
    }

    val helper = EngineHelper(tokenProvider)
    assertEquals("Bearer 1", helper.executeRequest())
  }

  @Test
  fun validInitialToken() = runTest {
    val tokenProvider = object : AuthorizationInterceptor.TokenProvider {
      override suspend fun loadToken(): AuthorizationInterceptor.Token? {
        return AuthorizationInterceptor.Token("0", currentTimeSeconds() + 10)
      }

      override suspend fun refreshToken(oldToken: String?): AuthorizationInterceptor.Token {
        error("must never be called")
      }
    }

    val helper = EngineHelper(tokenProvider)
    assertEquals("Bearer 0", helper.executeRequest())
  }

  @Test
  fun concurrentRequestsWithExpiredToken() = runTest {
    val tokenProvider = object : AuthorizationInterceptor.TokenProvider {
      private var count = 0
      override suspend fun loadToken(): AuthorizationInterceptor.Token? {
        return AuthorizationInterceptor.Token("0", currentTimeSeconds())
      }

      override suspend fun refreshToken(oldToken: String?): AuthorizationInterceptor.Token {
        assertEquals(oldToken, "0")
        assertEquals(0, count)
        count++
        return AuthorizationInterceptor.Token("1", Long.MAX_VALUE)
      }
    }

    val helper = EngineHelper(tokenProvider)
    launch {
      assertEquals("Bearer 1", helper.executeRequest("delayMillis" to "50"))
    }
    launch {
      assertEquals("Bearer 1", helper.executeRequest("delayMillis" to "50"))
    }
  }

  @Test
  fun concurrentRequestsWithInvalidToken() = runTest {
    val tokenProvider = object : AuthorizationInterceptor.TokenProvider {
      private var count = 0
      override suspend fun loadToken(): AuthorizationInterceptor.Token? {
        return AuthorizationInterceptor.Token("0", currentTimeSeconds() + 10)
      }

      override suspend fun refreshToken(oldToken: String?): AuthorizationInterceptor.Token {
        assertEquals(oldToken, "0")
        assertEquals(0, count)
        count++
        return AuthorizationInterceptor.Token("1", Long.MAX_VALUE)
      }
    }

    val helper = EngineHelper(tokenProvider)
    launch {
      assertEquals("Bearer 1", helper.executeRequest("delayMillis" to "50", "statusCode" to "401"))
    }
    launch {
      assertEquals("Bearer 1", helper.executeRequest("delayMillis" to "50", "statusCode" to "401"))
    }
  }

  @Test
  fun veryLongRequest() = runTest {
    var count = 0
    val tokenProvider = object : AuthorizationInterceptor.TokenProvider {
      override suspend fun loadToken(): AuthorizationInterceptor.Token? {
        return AuthorizationInterceptor.Token("0", currentTimeSeconds() + 10)
      }

      override suspend fun refreshToken(oldToken: String?): AuthorizationInterceptor.Token {
        count++
        return AuthorizationInterceptor.Token((oldToken!!.toInt() + 1).toString(), Long.MAX_VALUE)
      }
    }

    val helper = EngineHelper(tokenProvider, 2)
    val job1 = launch {
      // This request is long enough that the token will be refreshed twice below
      assertEquals("Bearer 2", helper.executeRequest("delayMillis" to "500", "statusCode" to "401"))
    }
    assertEquals("Bearer 1", helper.executeRequest("delayMillis" to "50", "statusCode" to "401"))
    assertEquals("Bearer 2", helper.executeRequest("delayMillis" to "50", "statusCode" to "401"))

    job1.join()

    assertEquals(2, count)
  }

  @Test
  fun errorRefreshingToken() = runTest {
    val tokenProvider = object : AuthorizationInterceptor.TokenProvider {
      override suspend fun loadToken(): AuthorizationInterceptor.Token? {
        return null
      }

      override suspend fun refreshToken(oldToken: String?): AuthorizationInterceptor.Token {
        throw Exception("invalid token")
      }
    }

    val helper = EngineHelper(tokenProvider)
    try {
      helper.executeRequest("delayMillis" to "50", "statusCode" to "401")
      fail("an exception was expected")
    } catch (e: Exception) {
      assertEquals("invalid token", e.message)
    }
  }

  private fun currentTimeSeconds() = currentTimeMillis() / 1000
}