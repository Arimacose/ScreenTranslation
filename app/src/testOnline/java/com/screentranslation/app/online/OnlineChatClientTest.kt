package com.screentranslation.app.online

import okhttp3.Call
import okhttp3.Callback
import okhttp3.EventListener
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import okio.Timeout
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.util.concurrent.CancellationException
import java.util.concurrent.Executors
import kotlin.reflect.KClass

class OnlineChatClientTest {
    @Test
    fun `posts one bearer-authenticated chat request and parses translation`() {
        val factory = RecordingCallFactory()
        val scheduler = Executors.newSingleThreadScheduledExecutor()
        try {
            val client = client(factory, scheduler)
            var result: Result<String>? = null
            client.translate("A whole OCR region") { result = it }

            val request = factory.lastCall.request()
            assertEquals(
                "https://api.example.test/v1/chat/completions",
                request.url.toString(),
            )
            assertEquals("Bearer test-key", request.header("Authorization"))
            val buffer = Buffer()
            checkNotNull(request.body).writeTo(buffer)
            val json = JSONObject(buffer.readUtf8())
            assertEquals(
                "A whole OCR region",
                json.getJSONArray("messages").getJSONObject(1).getString("content"),
            )

            factory.lastCall.respond(
                200,
                """{"choices":[{"message":{"content":"整段译文"}}]}""",
            )
            assertEquals("整段译文", checkNotNull(result).getOrThrow())
        } finally {
            scheduler.shutdownNow()
        }
    }

    @Test
    fun `cancellation settles the logical request once`() {
        val factory = RecordingCallFactory()
        val scheduler = Executors.newSingleThreadScheduledExecutor()
        try {
            val client = client(factory, scheduler)
            val results = mutableListOf<Result<String>>()
            val call = client.translate("cancel me") { results += it }

            call.cancel()
            assertTrue(factory.lastCall.isCanceled())
            assertEquals(1, results.size)
            assertTrue(results.single().exceptionOrNull() is CancellationException)
        } finally {
            scheduler.shutdownNow()
        }
    }

    @Test
    fun `gets bearer-authenticated model catalog and preserves returned ids`() {
        val factory = RecordingCallFactory()
        val client = OnlineModelCatalogClient(
            callFactory = factory,
            endpoint = OpenAiEndpoint.parse("https://api.example.test/v1"),
            apiKey = "test-key",
        )
        var result: Result<List<String>>? = null

        client.fetchModels { result = it }

        val request = factory.lastCall.request()
        assertEquals("GET", request.method)
        assertEquals("https://api.example.test/v1/models", request.url.toString())
        assertEquals("Bearer test-key", request.header("Authorization"))
        factory.lastCall.respond(
            200,
            """{"data":[{"id":"chat model"},{"id":"chat-model-v2"}]}""",
        )

        assertEquals(
            listOf("chat model", "chat-model-v2"),
            checkNotNull(result).getOrThrow(),
        )
    }

    @Test
    fun `managed request omits bearer key and pins Hy-MT2 contract`() {
        val factory = RecordingCallFactory()
        val scheduler = Executors.newSingleThreadScheduledExecutor()
        try {
            val client = OnlineChatClient(
                callFactory = factory,
                retryScheduler = scheduler,
                endpoint = OpenAiEndpoint.parse("https://managed.example.test/v1"),
                modelId = ManagedCloudService.PUBLIC_MODEL_ID,
                apiKey = null,
                sourceLanguage = "ja",
                targetLanguage = "zh",
                requestMode = OnlineChatRequestMode.MANAGED_HYMT2,
            )

            client.translate("保存しますか？") {}

            val request = factory.lastCall.request()
            assertEquals(null, request.header("Authorization"))
            val buffer = Buffer()
            checkNotNull(request.body).writeTo(buffer)
            val json = JSONObject(buffer.readUtf8())
            assertEquals(ManagedCloudService.PUBLIC_MODEL_ID, json.getString("model"))
            assertEquals(1, json.getJSONArray("messages").length())
        } finally {
            scheduler.shutdownNow()
        }
    }

    @Test
    fun `managed client rejects any supplied API key`() {
        val scheduler = Executors.newSingleThreadScheduledExecutor()
        try {
            val result = runCatching {
                OnlineChatClient(
                    callFactory = RecordingCallFactory(),
                    retryScheduler = scheduler,
                    endpoint = OpenAiEndpoint.parse("https://managed.example.test/v1"),
                    modelId = ManagedCloudService.PUBLIC_MODEL_ID,
                    apiKey = "must-not-leave-device",
                    sourceLanguage = "en",
                    targetLanguage = "zh",
                    requestMode = OnlineChatRequestMode.MANAGED_HYMT2,
                )
            }

            assertTrue(result.isFailure)
        } finally {
            scheduler.shutdownNow()
        }
    }

    private fun client(
        factory: RecordingCallFactory,
        scheduler: java.util.concurrent.ScheduledExecutorService,
    ) = OnlineChatClient(
        callFactory = factory,
        retryScheduler = scheduler,
        endpoint = OpenAiEndpoint.parse("https://api.example.test/v1"),
        modelId = "translation-model",
        apiKey = "test-key",
        sourceLanguage = "en",
        targetLanguage = "zh",
    )
}

private class RecordingCallFactory : Call.Factory {
    lateinit var lastCall: FakeCall

    override fun newCall(request: Request): Call = FakeCall(request).also {
        lastCall = it
    }
}

private class FakeCall(
    private val originalRequest: Request,
) : Call {
    private var callback: Callback? = null
    private var executed = false
    private var cancelled = false

    override fun request(): Request = originalRequest

    override fun execute(): Response = error("Synchronous execution is not used")

    override fun enqueue(responseCallback: Callback) {
        check(!executed)
        executed = true
        callback = responseCallback
    }

    override fun cancel() {
        if (cancelled) return
        cancelled = true
        callback?.onFailure(this, IOException("cancelled"))
    }

    override fun isExecuted(): Boolean = executed

    override fun isCanceled(): Boolean = cancelled

    override fun timeout(): Timeout = Timeout.NONE

    override fun addEventListener(eventListener: EventListener) = Unit

    override fun <T : Any> tag(type: KClass<T>): T? = null

    override fun <T> tag(type: Class<out T>): T? = null

    override fun <T : Any> tag(type: KClass<T>, computeIfAbsent: () -> T): T =
        computeIfAbsent()

    override fun <T : Any> tag(type: Class<T>, computeIfAbsent: () -> T): T =
        computeIfAbsent()

    override fun clone(): Call = FakeCall(originalRequest)

    fun respond(code: Int, json: String) {
        check(!cancelled)
        val response = Response.Builder()
            .request(originalRequest)
            .protocol(Protocol.HTTP_1_1)
            .code(code)
            .message("test")
            .body(json.toResponseBody("application/json".toMediaType()))
            .build()
        checkNotNull(callback).onResponse(this, response)
    }
}
