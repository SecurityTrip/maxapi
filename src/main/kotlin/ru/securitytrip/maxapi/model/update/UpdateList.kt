package ru.securitytrip.maxapi.model.update

/** A page of updates returned by `MaxClient.getUpdates`. */
public data class UpdateList(
    val updates: List<Update> = emptyList(),
    /**
     * Pass it to the next `getUpdates` call: updates before it are then considered delivered
     * and are not returned again.
     */
    val marker: Long? = null,
)
