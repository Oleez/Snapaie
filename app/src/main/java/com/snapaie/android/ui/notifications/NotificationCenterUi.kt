package com.snapaie.android.ui.notifications

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.NotificationsNone
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.snapaie.android.core.design.DesignTokens
import com.snapaie.android.domain.notifications.InAppNotification
import com.snapaie.android.domain.notifications.NotificationKind
import java.util.concurrent.TimeUnit

/**
 * Bell with an unread badge, ported from the extension's notification button.
 *
 * The extension flashed the button whenever a notification arrived
 * (`flashNotificationButton`); here that becomes a short scale pulse keyed on
 * the unread count, which reads better on a touch target than a colour flash.
 */
@Composable
fun NotificationBell(
    unreadCount: Int,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val pulse = remember { Animatable(1f) }

    LaunchedEffect(unreadCount) {
        if (unreadCount <= 0) return@LaunchedEffect
        pulse.animateTo(1.22f, tween(140))
        pulse.animateTo(1f, tween(220))
    }

    Box(modifier = modifier) {
        IconButton(
            onClick = onClick,
            modifier = Modifier.graphicsLayer {
                scaleX = pulse.value
                scaleY = pulse.value
            },
        ) {
            Icon(
                imageVector = if (unreadCount > 0) Icons.Filled.Notifications else Icons.Filled.NotificationsNone,
                contentDescription = if (unreadCount > 0) {
                    "Notifications, $unreadCount unread"
                } else {
                    "Notifications"
                },
                tint = if (unreadCount > 0) {
                    MaterialTheme.colorScheme.secondary
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
            )
        }
        if (unreadCount > 0) {
            Box(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .offset(x = (-4).dp, y = 4.dp)
                    .size(if (unreadCount > 9) 20.dp else 16.dp)
                    .clip(CircleShape)
                    .background(DesignTokens.DueRed),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = if (unreadCount > 9) "9+" else unreadCount.toString(),
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.Bold,
                    color = Color.White,
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NotificationCenterSheet(
    items: List<InAppNotification>,
    onDismissRequest: () -> Unit,
    onMarkAllRead: () -> Unit,
    onClearAll: () -> Unit,
    onDismissItem: (String) -> Unit,
    onOpenRoute: (String) -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val nowMillis = remember { System.currentTimeMillis() }

    ModalBottomSheet(onDismissRequest = onDismissRequest, sheetState = sheetState) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp)
                .padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    "Notifications",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.SemiBold,
                )
                Row {
                    if (items.any { !it.read }) {
                        TextButton(onClick = onMarkAllRead) { Text("Mark all read") }
                    }
                    if (items.isNotEmpty()) {
                        TextButton(onClick = onClearAll) { Text("Clear") }
                    }
                }
            }

            if (items.isEmpty()) {
                Text(
                    "Nothing here yet. Finished scans, model downloads and Forge Recall milestones land in this list.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(8.dp))
            } else {
                LazyColumn(
                    modifier = Modifier.heightIn(max = 420.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    items(items, key = { it.id }) { notification ->
                        NotificationRow(
                            notification = notification,
                            nowMillis = nowMillis,
                            onDismissItem = onDismissItem,
                            onOpenRoute = onOpenRoute,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun NotificationRow(
    notification: InAppNotification,
    nowMillis: Long,
    onDismissItem: (String) -> Unit,
    onOpenRoute: (String) -> Unit,
) {
    val accent = accentFor(notification.notificationKind)
    val shape = RoundedCornerShape(DesignTokens.RadiusMd)

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(
                if (notification.read) {
                    MaterialTheme.colorScheme.surface.copy(alpha = 0.5f)
                } else {
                    accent.copy(alpha = 0.12f)
                },
            )
            .border(1.dp, accent.copy(alpha = if (notification.read) 0.16f else 0.42f), shape)
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (!notification.read) {
                    Box(
                        Modifier
                            .size(8.dp)
                            .clip(CircleShape)
                            .background(accent),
                    )
                }
                Text(
                    notification.title,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                )
            }
            Text(
                relativeTime(notification.createdAtMillis, nowMillis),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        Text(
            notification.message,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (notification.ctaRoute.isNotBlank()) {
                TextButton(
                    onClick = { onOpenRoute(notification.ctaRoute) },
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
                ) {
                    Text(
                        notification.ctaLabel.ifBlank { "Open" },
                        style = MaterialTheme.typography.labelLarge,
                        color = accent,
                    )
                }
            } else {
                Spacer(Modifier.height(1.dp))
            }
            TextButton(
                onClick = { onDismissItem(notification.id) },
                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
            ) {
                Text(
                    "Dismiss",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun accentFor(kind: NotificationKind): Color = when (kind) {
    NotificationKind.Update -> MaterialTheme.colorScheme.primary
    NotificationKind.Tip -> DesignTokens.Periwinkle
    NotificationKind.Promo -> DesignTokens.XpPurple
    NotificationKind.Achievement -> DesignTokens.Amber
}

/** "just now" / "12m ago" / "3h ago" / "2d ago" — the extension's notification timestamp style. */
internal fun relativeTime(timestampMillis: Long, nowMillis: Long): String {
    val delta = (nowMillis - timestampMillis).coerceAtLeast(0L)
    val minutes = TimeUnit.MILLISECONDS.toMinutes(delta)
    val hours = TimeUnit.MILLISECONDS.toHours(delta)
    val days = TimeUnit.MILLISECONDS.toDays(delta)
    return when {
        minutes < 1 -> "just now"
        minutes < 60 -> "${minutes}m ago"
        hours < 24 -> "${hours}h ago"
        days < 7 -> "${days}d ago"
        else -> "${days / 7}w ago"
    }
}
