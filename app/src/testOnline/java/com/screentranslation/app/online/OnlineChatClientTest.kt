package com.screentranslation.app.online

import com.screentranslation.app.ml.OnlineByokProviderContract
import com.screentranslation.app.ml.TranslationCloseBehavior
import com.screentranslation.app.ml.TranslationPerRequestCancellation
import com.screentranslation.app.ml.TranslationProviderProfiles
import okhttp3.Call
import okhttp3.Callback
import okhttp3.EventListener
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import okio.BufferedSource
import okio.Timeout
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.util.concurrent.CancellationException
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.ScheduledThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.reflect.KClass

class OnlineChatClientTest {
    @Test
    fun `request limit and cancellation behavior match Online profile`() {
        val profile = TranslationProviderProfiles.onlineByok

        assertEquals(OnlineByokProviderContract.MAX_INPUT_CHARACTERS, OnlineChatClient.MAX_INPUT_CHARACTERS)
        assertEquals(OnlineChatClient.MAX_INPUT_CHARACTERS, profile.input.maximumCharacters)
        assertEquals(
            TranslationPerRequestCancellation.ACTIVE_REQUEST_BEST_EFFORT,
            profile.cancellation.perRequest,
        )
        assertEquals(
            TranslationCloseBehavior.PREEMPT_ACTIVE_AND_DISCARD_QUEUED,
            profile.cancellation.onClose,
        )
    }

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
    fun `429 retry starts after the prior response closes and succeeds once`() {
        val priorBodyClosed = AtomicBoolean(false)
        val retryObservedPriorBodyClosed = AtomicBoolean(false)
        val factory = RecordingCallFactory { callIndex ->
            if (callIndex == 1) {
                retryObservedPriorBodyClosed.set(priorBodyClosed.get())
            }
        }
        val scheduler = ImmediateScheduledExecutor()
        try {
            val client = client(factory, scheduler)
            var result: Result<String>? = null
            client.translate("retry me") { result = it }

            factory.calls.single().respond(
                code = 429,
                body = CloseTrackingResponseBody("{}", priorBodyClosed),
                headers = mapOf("Retry-After" to "0"),
            )

            assertEquals(2, factory.calls.size)
            assertTrue(retryObservedPriorBodyClosed.get())
            factory.calls[1].respond(
                200,
                """{"choices":[{"message":{"content":"重试成功"}}]}""",
            )
            assertEquals("重试成功", checkNotNull(result).getOrThrow())
            assertEquals(2, factory.calls.size)
        } finally {
            scheduler.shutdownNow()
        }
    }

    @Test
    fun `second 429 settles as rate limit without a third attempt`() {
        val factory = RecordingCallFactory()
        val scheduler = ImmediateScheduledExecutor()
        try {
            val client = client(factory, scheduler)
            var result: Result<String>? = null
            client.translate("retry twice") { result = it }

            factory.calls.single().respond(
                code = 429,
                body = "{}".toResponseBody("application/json".toMediaType()),
                headers = mapOf("Retry-After" to "0"),
            )
            assertEquals(2, factory.calls.size)

            factory.calls[1].respond(
                code = 429,
                body = "{}".toResponseBody("application/json".toMediaType()),
                headers = mapOf("Retry-After" to "0"),
            )

            val failure = checkNotNull(result).exceptionOrNull() as OnlineTranslationException
            assertEquals(OnlineFailureCategory.RATE_LIMIT, failure.category)
            assertEquals(429, failure.statusCode)
            assertEquals(2, factory.calls.size)
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

private class RecordingCallFactory(
    private val onNewCall: (callIndex: Int) -> Unit = {},
) : Call.Factory {
    lateinit var lastCall: FakeCall
    val calls = mutableListOf<FakeCall>()

    override fun newCall(request: Request): Call = FakeCall(request).also {
        onNewCall(calls.size)
        calls += it
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
        respond(code, json.toResponseBody("application/json".toMediaType()))
    }

    fun respond(
        code: Int,
        body: ResponseBody,
        headers: Map<String, String> = emptyMap(),
    ) {
        check(!cancelled)
        val responseBuilder = Response.Builder()
            .request(originalRequest)
            .protocol(Protocol.HTTP_1_1)
            .code(code)
            .message("test")
            .body(body)
        headers.forEach { (name, value) -> responseBuilder.header(name, value) }
        val response = responseBuilder.build()
        checkNotNull(callback).onResponse(this, response)
    }
}

private class CloseTrackingResponseBody(
    json: String,
    private val closed: AtomicBoolean,
) : ResponseBody() {
    private val delegate = json.toResponseBody("application/json".toMediaType())

    override fun contentType() = delegate.contentType()

    override fun contentLength() = delegate.contentLength()

    override fun source(): BufferedSource = delegate.source()

    override fun close() {
        closed.set(true)
        delegate.close()
    }
}

private class ImmediateScheduledExecutor : ScheduledThreadPoolExecutor(1) {
    override fun schedule(
        command: Runnable,
        delay: Long,
        unit: TimeUnit,
    ): ScheduledFuture<*> {
        command.run()
        return super.schedule({}, 0L, TimeUnit.MILLISECONDS)
    }
}
