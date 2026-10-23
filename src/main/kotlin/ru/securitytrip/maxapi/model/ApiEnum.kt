package ru.securitytrip.maxapi.model

/**
 * An enum that has a string representation on the wire.
 *
 * [value] is exactly what the API sends and expects, e.g. `"message_created"`.
 * Enums that the server may extend in the future also declare an `UNKNOWN` constant:
 * values the library does not know yet are deserialized to it instead of failing.
 */
public interface ApiEnum {
    /** Wire representation of the constant. */
    public val value: String
}
