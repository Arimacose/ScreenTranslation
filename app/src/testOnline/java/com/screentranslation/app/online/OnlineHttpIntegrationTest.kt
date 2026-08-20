package com.screentranslation.app.online

import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.SocketEffect
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.tls.HandshakeCertificates
import okhttp3.tls.HeldCertificate
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.CancellationException

class OnlineHttpIntegrationTest {
    private lateinit var server: MockWebServer
    private lateinit var httpClient: OkHttpClient
    private val retryScheduler = Executors.newSingleThreadScheduledExecutor()

    @Before
    fun setUp() {
        val localhostCertificate = HeldCertificate.Builder()
            .commonName("localhost")
            .addSubjectAlternativeName("localhost")
            .build()
        val serverCertificates = HandshakeCertificates.Builder()
            .heldCertificate(localhostCertificate)
            .build()
        val clientCertificates = HandshakeCertificates.Builder()
            .addTrustedCertificate(localhostCertificate.certificate)
            .build()

        server = MockWebServer().apply {
            protocols = listOf(Protocol.HTTP_1_1)
            useHttps(serverCertificates.sslSocketFactory())
            start()
        }
        httpClient = OkHttpClient.Builder()
            .sslSocketFactory(
                clientCertificates.sslSocketFactory(),
                clientCertificates.trustManager,
            )
            .connectTimeout(500, TimeUnit.MILLISECONDS)
            .writeTimeout(500, TimeUnit.MILLISECONDS)
            .readTimeout(250, TimeUnit.MILLISECONDS)
            .callTimeout(500, TimeUnit.MILLISECONDS)
            .protocols(listOf(Protocol.HTTP_1_1))
            .retryOnConnectionFailure(false)
            .build()
    }

    @After
    fun tearDown() {
        httpClient.dispatcher.cancelAll()
        httpClient.connectionPool.evictAll()
        httpClient.dispatcher.executorService.shutdownNow()
        retryScheduler.shutdownNow()
        server.close()
    }

    @Test
    fun `401 is surfaced once as an actionable credential error`() {
        server.enqueue(MockResponse(code = 401, body = "{}"))

        val result = translateAndAwait()

        val failure = result.exceptionOrNull() as OnlineTranslationException
        assertEquals(OnlineFailureCategory.CREDENTIALS, failure.category)
        assertTrue(failure.message.orEmpty().contains("API Key"))
        assertEquals(1, server.requestCount)
    }

    @Test
    fun `generation timeout is actionable and is never retried`() {
        server.enqueue(
            MockResponse.Builder()
                .onResponseStart(SocketEffect.Stall)
                .build(),
        )

        val result = translateAndAwait()

        val failure = result.exceptionOrNull() as OnlineTranslationException
        assertEquals(OnlineFailureCategory.TIMEOUT, failure.category)
        assertTrue(failure.message.orEmpty().contains("响应更快"))
        assertEquals(1, server.requestCount)
    }

    @Test
    fun `success fixture returns translated text over real TLS client`() {
        server.enqueue(
            MockResponse(
                code = 200,
                body = """{"choices":[{"message":{"content":"译文"}}]}""",
            ),
        )
        assertEquals("译文", translateAndAwait().getOrThrow())
        assertEquals("/v1/chat/completions", server.takeRequest().url.encodedPath)
    }

    @Test
    fun `model catalog fixture preserves descriptor identity`() {
        server.enqueue(
            MockResponse(
                code = 200,
                body = """{"data":[{"id":"model-stable","name":"Friendly"}]}""",
            ),
        )
        val result = AtomicReference<Result<List<OnlineModelDescriptor>>>()
        val latch = CountDownLatch(1)
        OnlineModelCatalogClient(
            callFactory = httpClient,
            endpoint = OpenAiEndpoint.parse(server.url("/v1").toString()),
            apiKey = "test-key",
        ).fetchModels {
            result.set(it)
            latch.countDown()
        }
        assertTrue(latch.await(3, TimeUnit.SECONDS))
        assertEquals("model-stable", result.get().getOrThrow().single().id)
        assertEquals("/v1/models", server.takeRequest().url.encodedPath)
    }

    @Test
    fun `second 429 settles after one bounded retry`() {
        repeat(2) {
            server.enqueue(
                MockResponse.Builder()
                    .code(429)
                    .addHeader("Retry-After", "0")
                    .body("{}")
                    .build(),
            )
        }
        val failure = translateAndAwait().exceptionOrNull() as OnlineTranslationException
        assertEquals(OnlineFailureCategory.RATE_LIMIT, failure.category)
        assertEquals(2, server.requestCount)
    }

    @Test
    fun `explicit cancellation stops stalled network call`() {
        server.enqueue(MockResponse.Builder().onResponseStart(SocketEffect.Stall).build())
        val result = AtomicReference<Result<String>>()
        val latch = CountDownLatch(1)
        val call = OnlineChatClient(
            callFactory = httpClient,
            retryScheduler = retryScheduler,
            endpoint = OpenAiEndpoint.parse(server.url("/v1").toString()),
            modelId = "translation-model",
            apiKey = "test-key",
            sourceLanguage = "en",
            targetLanguage = "zh",
        ).translate("Cancel fixture") {
            result.set(it)
            latch.countDown()
        }
        assertTrue(server.takeRequest(2, TimeUnit.SECONDS) != null)
        call.cancel()
        assertTrue(latch.await(2, TimeUnit.SECONDS))
        assertTrue(result.get().exceptionOrNull() is CancellationException)
    }

    private fun translateAndAwait(): Result<String> {
        val result = AtomicReference<Result<String>>()
        val latch = CountDownLatch(1)
        OnlineChatClient(
            callFactory = httpClient,
            retryScheduler = retryScheduler,
            endpoint = OpenAiEndpoint.parse(server.url("/v1").toString()),
            modelId = "translation-model",
            apiKey = "test-key",
            sourceLanguage = "en",
            targetLanguage = "zh",
        ).translate("Translate this") {
            result.set(it)
            latch.countDown()
        }
        assertTrue("request did not settle", latch.await(3, TimeUnit.SECONDS))
        return result.get()
    }
}
