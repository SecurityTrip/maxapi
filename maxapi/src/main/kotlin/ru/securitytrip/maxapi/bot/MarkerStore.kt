package ru.securitytrip.maxapi.bot

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption

/**
 * Keeps the long polling marker across restarts, set with [BotConfig.markerStore].
 *
 * The bot loads the marker when polling starts and saves it after all updates received before it
 * were handled. After a crash or restart the bot continues where it stopped: updates that were not
 * handled yet are received again (at-least-once delivery), so handlers may see an update twice and
 * should tolerate it. Without a store, polling starts from the updates the server still keeps.
 */
public interface MarkerStore {
    /** The saved marker, or `null` if there is none. */
    public suspend fun load(): Long?

    /** Saves [marker]. */
    public suspend fun save(marker: Long)
}

/** Keeps the marker in memory: survives restarts of polling, but not of the process. */
public class InMemoryMarkerStore : MarkerStore {
    @Volatile
    private var marker: Long? = null

    override suspend fun load(): Long? = marker

    override suspend fun save(marker: Long) {
        this.marker = marker
    }
}

/**
 * Keeps the marker in a text file at [path]. Every save writes a temporary file next to it and
 * moves it over [path] atomically, so a crash never leaves a half-written marker.
 */
public class FileMarkerStore(private val path: Path) : MarkerStore {
    override suspend fun load(): Long? = withContext(Dispatchers.IO) {
        if (Files.exists(path)) Files.readString(path).trim().toLongOrNull() else null
    }

    override suspend fun save(marker: Long): Unit = withContext(Dispatchers.IO) {
        Files.createDirectories(path.toAbsolutePath().parent)
        val temp = path.resolveSibling("${path.fileName}.tmp")
        Files.writeString(temp, marker.toString())
        Files.move(temp, path, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
    }
}

/**
 * Base class for a [MarkerStore] with blocking I/O (JDBC, Redis clients), for Java. [loadMarker] and
 * [saveMarker] run on `Dispatchers.IO`.
 */
public abstract class BlockingMarkerStore : MarkerStore {
    /** The saved marker, or `null` if there is none. */
    protected abstract fun loadMarker(): Long?

    /** Saves [marker]. */
    protected abstract fun saveMarker(marker: Long)

    final override suspend fun load(): Long? = withContext(Dispatchers.IO) { loadMarker() }

    final override suspend fun save(marker: Long): Unit = withContext(Dispatchers.IO) { saveMarker(marker) }
}
