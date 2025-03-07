package org.thoughtcrime.securesms.notifications.v2

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import org.thoughtcrime.securesms.notifications.DeleteNotificationReceiver
import org.thoughtcrime.securesms.notifications.MarkReadReceiver
import org.thoughtcrime.securesms.notifications.NotificationIds
import org.thoughtcrime.securesms.recipients.Recipient

/**
 * Hold all state for notifications for all conversations.
 */
data class NotificationStateV2(val conversations: List<NotificationConversation>) {

  val threadCount: Int = conversations.size
  val isEmpty: Boolean = conversations.isEmpty()

  val messageCount: Int by lazy {
    conversations.fold(0) { messageCount, conversation ->
      messageCount + conversation.messageCount
    }
  }

  val notificationItems: List<NotificationItemV2> by lazy {
    conversations.map { it.notificationItems }
      .flatten()
      .sorted()
  }

  val notificationIds: Set<Int> by lazy {
    conversations.map { it.notificationId }
      .toSet()
  }

  val mostRecentNotification: NotificationItemV2
    get() = notificationItems.last()

  val mostRecentSender: Recipient
    get() = mostRecentNotification.individualRecipient

  fun getDeleteIntent(context: Context): PendingIntent? {
    val ids = LongArray(messageCount)
    val mms = BooleanArray(ids.size)

    conversations.forEach { conversation ->
      conversation.notificationItems.forEachIndexed { index, notificationItem ->
        ids[index] = notificationItem.id
        mms[index] = notificationItem.isMms
      }
    }

    val intent = Intent(context, DeleteNotificationReceiver::class.java)
      .setAction(DeleteNotificationReceiver.DELETE_NOTIFICATION_ACTION)
      .putExtra(DeleteNotificationReceiver.EXTRA_IDS, ids)
      .putExtra(DeleteNotificationReceiver.EXTRA_MMS, mms)
      .makeUniqueToPreventMerging()

    return PendingIntent.getBroadcast(context, 0, intent, PendingIntent.FLAG_UPDATE_CURRENT)
  }

  fun getMarkAsReadIntent(context: Context): PendingIntent? {
    val threadArray = LongArray(conversations.size)

    conversations.forEachIndexed { index, conversation ->
      threadArray[index] = conversation.threadId
    }

    val intent = Intent(context, MarkReadReceiver::class.java).setAction(MarkReadReceiver.CLEAR_ACTION)
      .putExtra(MarkReadReceiver.THREAD_IDS_EXTRA, threadArray)
      .putExtra(MarkReadReceiver.NOTIFICATION_ID_EXTRA, NotificationIds.MESSAGE_SUMMARY)
      .makeUniqueToPreventMerging()

    return PendingIntent.getBroadcast(context, 0, intent, PendingIntent.FLAG_UPDATE_CURRENT)
  }

  companion object {
    val EMPTY = NotificationStateV2(emptyList())
  }
}
