package ru.securitytrip.maxapi

import kotlinx.coroutines.runBlocking
import mockwebserver3.MockWebServer
import ru.securitytrip.maxapi.client.MaxApiException
import ru.securitytrip.maxapi.client.MaxClient
import ru.securitytrip.maxapi.client.MaxException
import ru.securitytrip.maxapi.client.UploadSource
import ru.securitytrip.maxapi.model.attachment.AudioAttachmentRequest
import ru.securitytrip.maxapi.model.attachment.FileAttachmentRequest
import ru.securitytrip.maxapi.model.attachment.PhotoAttachmentRequest
import ru.securitytrip.maxapi.model.attachment.UploadType
import java.io.File
import java.nio.file.Files
import java.util.concurrent.TimeUnit
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class UploadsTest {
    private lateinit var server: MockWebServer
    private lateinit var client: MaxClient

    @BeforeTest
    fun setUp() {
        server = MockWebServer()
        server.start()
        client = server.client()
    }

    @AfterTest
    fun tearDown() {
        client.close()
        server.close()
    }

    private fun enqueueEndpoint(token: String? = null) {
        val tokenField = token?.let { ""","token":"$it"""" }.orEmpty()
        server.enqueue(json("""{"url":"${server.url("/upload.do")}"$tokenField}"""))
    }

    /** Skips the `POST /uploads` request and returns the upload request. */
    private fun takeUpload() = server.take().let { server.take() }

    @Test
    fun `uploads a file from disk with its name and guessed content type`(): Unit = runBlocking {
        val file = File.createTempFile("report", ".pdf").apply {
            writeText("pdf-content")
            deleteOnExit()
        }
        enqueueEndpoint()
        server.enqueue(json("""{"token":"file-token"}"""))

        val attachment = client.uploadFile(UploadSource.of(file))

        assertEquals("file-token", attachment.payload.token)
        val upload = takeUpload()
        assertTrue(upload.bodyText.contains("""filename="${file.name}""""), upload.bodyText)
        assertTrue(upload.bodyText.contains("Content-Type: application/pdf"), upload.bodyText)
        assertTrue(upload.bodyText.contains("pdf-content"))
    }

    @Test
    fun `uploads from a path`(): Unit = runBlocking {
        val path = Files.createTempFile("photo", ".png").also { Files.writeString(it, "png") }
        try {
            enqueueEndpoint()
            server.enqueue(json("""{"photos":{"a":{"token":"t1"},"b":{"token":"t2"}}}"""))

            val attachment = client.uploadImage(UploadSource.of(path))

            assertEquals(setOf("t1", "t2"), attachment.payload.photos!!.values.map { it.token }.toSet())
            assertTrue(takeUpload().bodyText.contains("""filename="${path.fileName}""""))
        } finally {
            Files.deleteIfExists(path)
        }
    }

    @Test
    fun `uploads a stream of known length`(): Unit = runBlocking {
        enqueueEndpoint(token = "audio-token")
        server.enqueue(json("{}"))

        val attachment = client.uploadAudio(UploadSource.of("voice".byteInputStream(), "voice.mp3", 5))

        assertEquals("audio-token", attachment.payload.token)
        val upload = takeUpload()
        assertTrue(upload.bodyText.contains("voice"))
        assertTrue(upload.bodyText.contains("Content-Type: audio/mpeg"), upload.bodyText)
    }

    @Test
    fun `unknown extensions are uploaded as octet-stream`(): Unit = runBlocking {
        enqueueEndpoint()
        server.enqueue(json("""{"token":"t"}"""))

        client.uploadFile(UploadSource.of(byteArrayOf(1, 2, 3), "data.unknownext"))

        assertTrue(takeUpload().bodyText.contains("Content-Type: application/octet-stream"))
        assertEquals("UploadSource(data.unknownext)", UploadSource.of(byteArrayOf(), "data.unknownext").toString())
    }

    @Test
    fun `image upload accepts a single token response`(): Unit = runBlocking {
        enqueueEndpoint()
        server.enqueue(json("""{"token":"single"}"""))

        val attachment = client.uploadImage(UploadSource.of(byteArrayOf(1), "a.jpg"))

        assertEquals("single", attachment.payload.token)
        assertNull(attachment.payload.photos)
    }

    @Test
    fun `image upload without tokens fails`() {
        enqueueEndpoint()
        server.enqueue(json("""{"photos":{}}"""))

        val error = assertFailsWith<MaxException> {
            runBlocking { client.uploadImage(UploadSource.of(byteArrayOf(1), "a.jpg")) }
        }
        assertTrue(error.message!!.contains("no tokens"))
    }

    @Test
    fun `file upload without a token fails`() {
        enqueueEndpoint()
        server.enqueue(json("<retval>1</retval>"))

        val error = assertFailsWith<MaxException> {
            runBlocking { client.uploadFile(UploadSource.of(byteArrayOf(1), "a.txt")) }
        }
        assertTrue(error.message!!.startsWith("Cannot parse upload response"), error.message)
    }

    @Test
    fun `file upload with a non-text token fails`() {
        enqueueEndpoint()
        server.enqueue(json("""{"token":123}"""))

        assertFailsWith<MaxException> { runBlocking { client.uploadFile(UploadSource.of(byteArrayOf(1), "a.txt")) } }
    }

    @Test
    fun `upload host errors become MaxApiException`() {
        enqueueEndpoint()
        server.enqueue(json("""{"code":"too.big","message":"File is too large"}""", code = 413))

        val error = assertFailsWith<MaxApiException> {
            runBlocking { client.uploadVideo(UploadSource.of(byteArrayOf(1), "a.mp4")) }
        }
        assertEquals(413, error.httpStatus)
        assertEquals("too.big", error.errorCode)
    }

    @Test
    fun `generic upload returns the attachment type for the upload type`(): Unit = runBlocking {
        enqueueEndpoint()
        server.enqueue(json("""{"token":"i"}"""))
        enqueueEndpoint(token = "a")
        server.enqueue(json("{}"))
        enqueueEndpoint()
        server.enqueue(json("""{"token":"f"}"""))

        assertIs<PhotoAttachmentRequest>(client.upload(UploadType.IMAGE, UploadSource.of(byteArrayOf(1), "a.png")))
        assertIs<AudioAttachmentRequest>(client.upload(UploadType.AUDIO, UploadSource.of(byteArrayOf(1), "a.mp3")))
        assertIs<FileAttachmentRequest>(client.upload(UploadType.FILE, UploadSource.of(byteArrayOf(1), "a.txt")))
        assertEquals(listOf("image", "audio", "file"), (0 until 6 step 2).map {
            server.takeRequest(1, TimeUnit.SECONDS)!!.url.queryParameter("type").also { server.take() }
        })
    }

    @Test
    fun `every upload method of the Java facades`() {
        enqueueEndpoint()
        server.enqueue(json("""{"token":"i"}"""))
        enqueueEndpoint(token = "a")
        server.enqueue(json("{}"))
        enqueueEndpoint(token = "v")
        server.enqueue(json("{}"))
        enqueueEndpoint()
        server.enqueue(json("""{"token":"f"}"""))
        enqueueEndpoint()
        server.enqueue(json("""{"token":"g"}"""))

        assertEquals("i", client.blocking().uploadImage(UploadSource.of(byteArrayOf(1), "a.png")).payload.token)
        assertEquals("a", client.blocking().uploadAudio(UploadSource.of(byteArrayOf(1), "a.mp3")).payload.token)
        assertEquals("v", client.async().uploadVideo(UploadSource.of(byteArrayOf(1), "a.mp4")).get().payload.token)
        assertEquals("f", client.async().uploadFile(UploadSource.of(byteArrayOf(1), "a.txt")).get().payload.token)
        assertIs<FileAttachmentRequest>(client.async().upload(UploadType.FILE, UploadSource.of(byteArrayOf(1), "b.txt")).get())
    }

    @Test
    fun `uploads work through the Java facades`() {
        enqueueEndpoint(token = "v")
        server.enqueue(json("{}"))
        enqueueEndpoint()
        server.enqueue(json("""{"token":"f"}"""))
        enqueueEndpoint(token = "a")
        server.enqueue(json("{}"))
        enqueueEndpoint()
        server.enqueue(json("""{"token":"i"}"""))
        enqueueEndpoint()
        server.enqueue(json("""{"token":"g"}"""))

        assertEquals("v", client.blocking().uploadVideo(UploadSource.of(byteArrayOf(1), "a.mp4")).payload.token)
        assertEquals("f", client.blocking().uploadFile(UploadSource.of(byteArrayOf(1), "a.txt")).payload.token)
        assertEquals("a", client.async().uploadAudio(UploadSource.of(byteArrayOf(1), "a.mp3")).get().payload.token)
        assertEquals("i", client.async().uploadImage(UploadSource.of(byteArrayOf(1), "a.png")).get().payload.token)
        assertIs<FileAttachmentRequest>(client.blocking().upload(UploadType.FILE, UploadSource.of(byteArrayOf(1), "b.txt")))
    }
}
