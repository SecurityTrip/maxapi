package ru.securitytrip.maxapi.model.attachment

import com.fasterxml.jackson.annotation.JsonValue
import ru.securitytrip.maxapi.model.ApiEnum

/**
 * Kind of an uploaded file. Limits of a single multipart upload:
 *
 * - [IMAGE]: JPG, JPEG, PNG, GIF, TIFF, BMP, HEIC; up to 50 MB and 7680×7680 px;
 * - [VIDEO]: MP4, MOV, MKV, WEBM; up to 250 MB;
 * - [AUDIO]: MP3, WAV, M4A and others; up to 256 MB and 60 minutes;
 * - [FILE]: any common format; up to 4 GB.
 */
public enum class UploadType(@get:JsonValue override val value: String) : ApiEnum {
    IMAGE("image"),
    VIDEO("video"),
    AUDIO("audio"),
    FILE("file"),
}
