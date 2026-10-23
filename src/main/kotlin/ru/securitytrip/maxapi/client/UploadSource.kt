package ru.securitytrip.maxapi.client

import okhttp3.MediaType
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okio.BufferedSink
import okio.source
import java.io.File
import java.io.InputStream
import java.net.URLConnection
import java.nio.file.Path

/**
 * Content of a file to upload with `MaxClient.upload*`.
 *
 * ```kotlin
 * client.uploadImage(UploadSource.of(File("cat.png")))
 * client.uploadFile(UploadSource.of(bytes, "report.pdf"))
 * client.uploadVideo(UploadSource.of(inputStream, "movie.mp4"))
 * ```
 *
 * The content type is guessed from the file name extension.
 *
 * @property fileName name the file is uploaded under; Max shows it for file attachments.
 */
public class UploadSource private constructor(
    public val fileName: String,
    internal val body: RequestBody,
) {
    override fun toString(): String = "UploadSource($fileName)"

    public companion object {
        /** A file on disk, read when the upload starts. */
        @JvmStatic
        public fun of(file: File): UploadSource = UploadSource(file.name, file.asRequestBody(contentType(file.name)))

        /** A file on disk, read when the upload starts. */
        @JvmStatic
        public fun of(path: Path): UploadSource = of(path.toFile())

        /** In-memory content. */
        @JvmStatic
        public fun of(bytes: ByteArray, fileName: String): UploadSource =
            UploadSource(fileName, bytes.toRequestBody(contentType(fileName)))

        /**
         * A stream, read once during the upload and closed afterwards. Such a source can be used
         * for one upload only.
         *
         * @param contentLength size in bytes, or `-1` if unknown.
         */
        @JvmStatic
        @JvmOverloads
        public fun of(stream: InputStream, fileName: String, contentLength: Long = -1): UploadSource =
            UploadSource(fileName, StreamBody(stream, contentType(fileName), contentLength))

        private fun contentType(fileName: String): MediaType? =
            (URLConnection.guessContentTypeFromName(fileName) ?: "application/octet-stream").toMediaTypeOrNull()
    }

    private class StreamBody(
        private val stream: InputStream,
        private val type: MediaType?,
        private val length: Long,
    ) : RequestBody() {
        override fun contentType(): MediaType? = type
        override fun contentLength(): Long = length
        override fun isOneShot(): Boolean = true
        override fun writeTo(sink: BufferedSink) {
            stream.source().use { sink.writeAll(it) }
        }
    }
}
