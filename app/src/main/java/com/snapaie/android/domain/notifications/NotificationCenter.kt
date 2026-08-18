package com.snapaie.android.domain.notifications

import com.snapaie.android.data.preferences.AppPreferencesRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlin.random.Random

private const val AUTO_DISMISS_MILLIS = 10_000L

/**
 * Kind of in-app notification. Ported from the extension's
 * `pushPersistentNotification` type axis (`update | promo | tip | newsletter`),
 * with `newsletter` dropped (snapaie has no mailing list) and `achievement`
 * added for Forge Recall milestones.
 *
 * [autoDismissMillis] mirrors the extension rule that purely system/update
 * notices self-clear so the unread badge never nags; marketing and tips stay
 * until the user reads them.
 */
enum class NotificationKind(val autoDismissMillis: Long?) {
    Update(AUTO_DISMISS_MILLIS),
    Tip(null),
    Promo(null),
    Achievement(null),
    ;

    companion object {
        fun fromStored(value: String): NotificationKind =
            entries.firstOrNull { it.name.equals(value, ignoreCase = true) } ?: Update
    }
}

@Serializable
data class InAppNotification(
    val id: String,
    val title: String,
    val message: String,
    val kind: String = NotificationKind.Update.name,
    val createdAtMillis: Long,
    val read: Boolean = false,
    /** Optional in-app destination, e.g. `Routes.scanDetail(12)`. Never a URL — snapaie stays offline. */
    val ctaRoute: String = "",
    val ctaLabel: String = "",
) {
    val notificationKind: NotificationKind get() = NotificationKind.fromStored(kind)
}

/**
 * Persistent in-app notification list with an unread badge.
 *
 * Ported from the extension's notification centre (`popup.js`
 * `pushPersistentNotification` / `renderNotifications`), keeping its three
 * behaviours that make the badge trustworthy:
 *
 *  - identical title+message inside [DEDUPE_WINDOW_MILLIS] is dropped,
 *  - the list is capped at [MAX_NOTIFICATIONS] newest-first,
 *  - `Update` notices auto-mark themselves read after 10s.
 *
 * Unlike the extension this never opens an external URL; a CTA is an in-app
 * route, so the app keeps its "no network" promise.
 */
class NotificationCenter(
    private val preferences: AppPreferencesRepository,
    private val scope: CoroutineScope,
    private val now: () -> Long = System::currentTimeMillis,
) {
    private val json = Json { ignoreUnknownKeys = true }
    private val mutex = Mutex()
    private var loaded = false

    private val _items = MutableStateFlow<List<InAppNotification>>(emptyList())
    val items: StateFlow<List<InAppNotification>> = _items.asStateFlow()

    val unreadCount: StateFlow<Int> = _items
        .map { list -> list.count { !it.read } }
        .stateIn(scope, SharingStarted.Eagerly, 0)

    init {
        scope.launch { mutate { it } }
    }

    /** Adds a notification unless an identical one arrived in the last 5 minutes. */
    fun push(
        message: String,
        title: String = "Update",
        kind: NotificationKind = NotificationKind.Update,
        ctaRoute: String = "",
        ctaLabel: String = "",
    ) {
        val body = message.trim()
        if (body.isEmpty()) return
        val stamp = now()
        val id = "$stamp-${Random.nextInt(0, 1_000_000)}"

        scope.launch {
            var added = false
            mutate { current ->
                val duplicate = current.any {
                    it.message == body &&
                        it.title == title &&
                        it.createdAtMillis >= stamp - DEDUPE_WINDOW_MILLIS
                }
                if (duplicate) {
                    current
                } else {
                    added = true
                    val next = InAppNotification(
                        id = id,
                        title = title,
                        message = body,
                        kind = kind.name,
                        createdAtMillis = stamp,
                        ctaRoute = ctaRoute,
                        ctaLabel = ctaLabel,
                    )
                    (listOf(next) + current).take(MAX_NOTIFICATIONS)
                }
            }
            if (!added) return@launch
            val delayMillis = kind.autoDismissMillis ?: return@launch
            kotlinx.coroutines.delay(delayMillis)
            markRead(id)
        }
    }

    fun markRead(id: String) {
        scope.launch {
            mutate { current ->
                current.map { if (it.id == id && !it.read) it.copy(read = true) else it }
            }
        }
    }

    fun markAllRead() {
        scope.launch { mutate { current -> current.map { it.copy(read = true) } } }
    }

    fun dismiss(id: String) {
        scope.launch { mutate { current -> current.filterNot { it.id == id } } }
    }

    fun clearAll() {
        scope.launch { mutate { emptyList() } }
    }

    /**
     * Loads once, applies [transform], then persists. Everything funnels through
     * here so a push that lands before the first disk read cannot be overwritten
     * by that read.
     */
    private suspend fun mutate(transform: (List<InAppNotification>) -> List<InAppNotification>) {
        mutex.withLock {
            if (!loaded) {
                _items.value = decode(preferences.notificationsJson.first())
                loaded = true
            }
            val next = transform(_items.value)
            if (next == _items.value) return@withLock
            _items.value = next
            preferences.setNotificationsJson(encode(next))
        }
    }

    private fun decode(raw: String): List<InAppNotification> =
        if (raw.isBlank()) {
            emptyList()
        } else {
            runCatching { json.decodeFromString<List<InAppNotification>>(raw) }.getOrDefault(emptyList())
        }

    private fun encode(items: List<InAppNotification>): String = json.encodeToString(items)

    companion object {
        const val DEDUPE_WINDOW_MILLIS = 5 * 60 * 1000L
        const val MAX_NOTIFICATIONS = 100
    }
}
