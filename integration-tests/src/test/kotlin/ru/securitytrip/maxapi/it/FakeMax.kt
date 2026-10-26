package ru.securitytrip.maxapi.it

import com.fasterxml.jackson.databind.ObjectMapper
import mockwebserver3.Dispatcher
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

/**
 * A stand-in for the Max API: hands out queued updates to long polling, like the real server, and
 * records what the bot sends. It outlives the applications started against it, as the real server
 * outlives restarts of a bot.
 */
class FakeMax : AutoCloseable {
    private val server = MockWebServer()
    private val mapper = ObjectMapper()
    private val queued = LinkedBlockingQueue<String>()
    private val delivered = AtomicLong()

    /** Texts of the messages the bot sent, in order. */
    val sentTexts: MutableList<String> = CopyOnWriteArrayList()

    /** The `marker` parameter of every `getUpdates` request, in order. */
    val pollMarkers: MutableList<String?> = CopyOnWriteArrayList()

    /** Bodies of `POST /subscriptions` requests. */
    val subscriptions: MutableList<String> = CopyOnWriteArrayList()

    val url: String get() = server.url("/").toString()

    init {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse = handle(request)
        }
        server.start()
    }

    /** Queues [update] for the next `getUpdates`. */
    fun deliver(update: String) {
        queued += update
    }

    private fun handle(request: RecordedRequest): MockResponse {
        val path = request.url.encodedPath
        return when {
            request.method == "GET" && path == "/updates" -> {
                pollMarkers += request.url.queryParameter("marker")
                // Long polling: wait a little for an update instead of answering empty at once.
                val first = queued.poll(200, TimeUnit.MILLISECONDS)
                    ?: return json("""{"updates":[],"marker":${delivered.get()}}""")
                val page = mutableListOf(first).also { queued.drainTo(it) }
                val marker = delivered.addAndGet(page.size.toLong())
                json("""{"updates":[${page.joinToString(",")}],"marker":$marker}""")
            }
            request.method == "POST" && path == "/messages" -> {
                val text = mapper.readTree(request.body?.utf8()).path("text").asText()
                sentTexts += text
                json(
                    """{"message":{"recipient":{"chat_id":1,"chat_type":"dialog"},"timestamp":1,
                       "body":{"mid":"out.${sentTexts.size}","seq":1,"text":${mapper.writeValueAsString(text)}}}}""",
                )
            }
            request.method == "GET" && path == "/subscriptions" -> json("""{"subscriptions":[]}""")
            request.method == "POST" && path == "/subscriptions" -> {
                subscriptions += request.body?.utf8().orEmpty()
                json(SUCCESS)
            }
            else -> json(SUCCESS)
        }
    }

    private fun json(body: String) = MockResponse.Builder().addHeader("Content-Type", "application/json").body(body).build()

    override fun close() {
        server.close()
    }

    companion object {
        private const val SUCCESS = """{"success":true}"""

        /** A new text message from user 7 in the dialog with chat id 1. */
        fun message(mid: String, text: String): String =
            """{"update_type":"message_created","timestamp":1,"user_locale":"ru","message":{
               "sender":{"user_id":7,"first_name":"Ann","is_bot":false},
               "recipient":{"chat_id":1,"chat_type":"dialog","user_id":100},
               "timestamp":1,"body":{"mid":"$mid","seq":1,"text":"$text"}}}"""
    }
}
