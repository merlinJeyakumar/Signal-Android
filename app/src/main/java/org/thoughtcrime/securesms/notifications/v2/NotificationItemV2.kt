package org.thoughtcrime.securesms.notifications.v2

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.text.SpannableStringBuilder
import android.text.TextUtils
import androidx.annotation.StringRes
import org.signal.core.util.logging.Log
import org.thoughtcrime.securesms.R
import org.thoughtcrime.securesms.contactshare.Contact
import org.thoughtcrime.securesms.contactshare.ContactUtil
import org.thoughtcrime.securesms.database.DatabaseFactory
import org.thoughtcrime.securesms.database.MentionUtil
import org.thoughtcrime.securesms.database.ThreadBodyUtil
import org.thoughtcrime.securesms.database.model.MessageRecord
import org.thoughtcrime.securesms.database.model.MmsMessageRecord
import org.thoughtcrime.securesms.database.model.ReactionRecord
import org.thoughtcrime.securesms.mms.Slide
import org.thoughtcrime.securesms.mms.SlideDeck
import org.thoughtcrime.securesms.notifications.AbstractNotificationBuilder
import org.thoughtcrime.securesms.recipients.Recipient
import org.thoughtcrime.securesms.recipients.RecipientUtil
import org.thoughtcrime.securesms.service.KeyCachingService
import org.thoughtcrime.securesms.util.MediaUtil
import org.thoughtcrime.securesms.util.MessageRecordUtil
import org.thoughtcrime.securesms.util.SpanUtil
import org.thoughtcrime.securesms.util.TextSecurePreferences
import org.thoughtcrime.securesms.util.Util

private val TAG: String = Log.tag(NotificationItemV2::class.java)
private const val EMOJI_REPLACEMENT_STRING = "__EMOJI__"

/**
 * Base for messaged-based notifications. Represents a single notification.
 */
sealed class NotificationItemV2(val threadRecipient: Recipient, protected val record: MessageRecord) : Comparable<NotificationItemV2> {

  val id: Long = record.id
  val threadId: Long = record.threadId
  val isMms: Boolean = record.isMms
  val slideDeck: SlideDeck? = (record as? MmsMessageRecord)?.slideDeck
  val isJoined: Boolean = record.isJoined

  protected val notifiedTimestamp: Long = record.notifiedTimestamp

  abstract val timestamp: Long
  abstract val individualRecipient: Recipient
  abstract val isNewNotification: Boolean

  protected abstract fun getPrimaryTextActual(context: Context): CharSequence
  abstract fun getStartingPosition(context: Context): Int
  abstract fun getLargeIconUri(): Uri?
  abstract fun getBigPictureUri(): Uri?
  abstract fun getThumbnailInfo(context: Context): ThumbnailInfo
  abstract fun canReply(context: Context): Boolean

  protected fun getMessageContentType(messageRecord: MmsMessageRecord): String {
    val thumbnailSlide: Slide? = messageRecord.slideDeck.thumbnailSlide

    return if (thumbnailSlide == null) {
      val slideContentType: String? = messageRecord.slideDeck.firstSlideContentType
      if (slideContentType != null) {
        slideContentType
      } else {
        Log.w(TAG, "Could not distinguish view-once content type from message record, defaulting to JPEG")
        MediaUtil.IMAGE_JPEG
      }
    } else {
      thumbnailSlide.contentType
    }
  }

  fun getStyledPrimaryText(context: Context, trimmed: Boolean = false): CharSequence {
    return if (TextSecurePreferences.getNotificationPrivacy(context).isDisplayNothing) {
      context.getString(R.string.SingleRecipientNotificationBuilder_new_message)
    } else {
      SpannableStringBuilder().apply {
        append(Util.getBoldedString(individualRecipient.getShortDisplayNameIncludingUsername(context)))
        if (threadRecipient != individualRecipient) {
          append(Util.getBoldedString("@${threadRecipient.getDisplayName(context)}"))
        }
        append(": ")
        append(getPrimaryText(context).apply { if (trimmed) trimToDisplayLength() })
      }
    }
  }

  fun getPersonName(context: Context): CharSequence {
    return if (TextSecurePreferences.getNotificationPrivacy(context).isDisplayContact) {
      individualRecipient.getDisplayName(context)
    } else {
      ""
    }
  }

  override fun compareTo(other: NotificationItemV2): Int {
    return timestamp.compareTo(other.timestamp)
  }

  fun getPersonUri(context: Context): String? {
    return if (TextSecurePreferences.getNotificationPrivacy(context).isDisplayContact && individualRecipient.isSystemContact) {
      individualRecipient.contactUri.toString()
    } else {
      null
    }
  }

  fun getPersonIcon(context: Context): Bitmap? {
    return if (TextSecurePreferences.getNotificationPrivacy(context).isDisplayContact) {
      individualRecipient.getContactDrawable(context).toLargeBitmap(context)
    } else {
      null
    }
  }

  fun getPrimaryText(context: Context): CharSequence {
    return if (TextSecurePreferences.getNotificationPrivacy(context).isDisplayMessage) {
      if (RecipientUtil.isMessageRequestAccepted(context, threadId)) {
        getPrimaryTextActual(context)
      } else {
        SpanUtil.italic(context.getString(R.string.SingleRecipientNotificationBuilder_message_request))
      }
    } else {
      context.getString(R.string.SingleRecipientNotificationBuilder_new_message)
    }
  }

  fun getInboxLine(context: Context): CharSequence? {
    return when {
      TextSecurePreferences.getNotificationPrivacy(context).isDisplayNothing -> null
      else -> getStyledPrimaryText(context, true)
    }
  }

  private fun CharSequence?.trimToDisplayLength(): CharSequence {
    val text: CharSequence = this ?: ""
    return if (text.length <= AbstractNotificationBuilder.MAX_DISPLAY_LENGTH) {
      text
    } else {
      text.subSequence(0, AbstractNotificationBuilder.MAX_DISPLAY_LENGTH)
    }
  }

  data class ThumbnailInfo(val uri: Uri? = null, val contentType: String? = null)
}

/**
 * Represents a notification associated with a new message.
 */
class MessageNotification(threadRecipient: Recipient, record: MessageRecord) : NotificationItemV2(threadRecipient, record) {
  override val timestamp: Long = record.timestamp
  override val individualRecipient: Recipient = record.individualRecipient.resolve()
  override val isNewNotification: Boolean = notifiedTimestamp == 0L

  override fun getPrimaryTextActual(context: Context): CharSequence {
    return if (KeyCachingService.isLocked(context)) {
      SpanUtil.italic(context.getString(R.string.MessageNotifier_locked_message))
    } else if (record.isMms && (record as MmsMessageRecord).sharedContacts.isNotEmpty()) {
      val contact = record.sharedContacts[0]
      ContactUtil.getStringSummary(context, contact)
    } else if (record.isMms && record.isViewOnce) {
      SpanUtil.italic(context.getString(getViewOnceDescription(record as MmsMessageRecord)))
    } else if (record.isRemoteDelete) {
      SpanUtil.italic(context.getString(R.string.MessageNotifier_this_message_was_deleted))
    } else if (record.isMms && !record.isMmsNotification && (record as MmsMessageRecord).slideDeck.slides.isNotEmpty()) {
      ThreadBodyUtil.getFormattedBodyFor(context, record)
    } else if (record.isGroupCall) {
      MessageRecord.getGroupCallUpdateDescription(context, record.body, false).string
    } else {
      MentionUtil.updateBodyWithDisplayNames(context, record)
    }
  }

  @StringRes
  private fun getViewOnceDescription(messageRecord: MmsMessageRecord): Int {
    val contentType = getMessageContentType(messageRecord)
    return if (MediaUtil.isImageType(contentType)) R.string.MessageNotifier_view_once_photo else R.string.MessageNotifier_view_once_video
  }

  override fun getStartingPosition(context: Context): Int {
    return -1
  }

  override fun getLargeIconUri(): Uri? {
    val slide: Slide? = slideDeck?.thumbnailSlide ?: slideDeck?.stickerSlide

    return if (slide?.isInProgress == false) slide.uri else null
  }

  override fun getBigPictureUri(): Uri? {
    val slide: Slide? = slideDeck?.thumbnailSlide

    return if (slide?.isInProgress == false) slide.uri else null
  }

  override fun getThumbnailInfo(context: Context): ThumbnailInfo {
    return if (TextSecurePreferences.getNotificationPrivacy(context).isDisplayMessage && !KeyCachingService.isLocked(context)) {
      val thumbnailSlide: Slide? = slideDeck?.thumbnailSlide
      ThumbnailInfo(thumbnailSlide?.publicUri, thumbnailSlide?.contentType)
    } else {
      ThumbnailInfo()
    }
  }

  override fun canReply(context: Context): Boolean {
    if (KeyCachingService.isLocked(context) ||
      record.isRemoteDelete ||
      record.isGroupCall ||
      record.isViewOnce ||
      record.isJoined
    ) {
      return false
    }

    if (record is MmsMessageRecord) {
      return (record.isMmsNotification || record.slideDeck.slides.isEmpty()) && record.sharedContacts.isEmpty()
    }

    return true
  }

  override fun toString(): String {
    return "MessageNotification(timestamp=$timestamp, isNewNotification=$isNewNotification)"
  }
}

/**
 * Represents a notification associated with a new reaction.
 */
class ReactionNotification(threadRecipient: Recipient, record: MessageRecord, val reaction: ReactionRecord) : NotificationItemV2(threadRecipient, record) {
  override val timestamp: Long = reaction.dateReceived
  override val individualRecipient: Recipient = Recipient.resolved(reaction.author)
  override val isNewNotification: Boolean = timestamp > notifiedTimestamp

  override fun getPrimaryTextActual(context: Context): CharSequence {
    return if (KeyCachingService.isLocked(context)) {
      SpanUtil.italic(context.getString(R.string.MessageNotifier_locked_message))
    } else {
      val text: String = SpanUtil.italic(getReactionMessageBody(context)).toString()
      val parts: Array<String> = text.split(EMOJI_REPLACEMENT_STRING).toTypedArray()
      val builder = SpannableStringBuilder()

      parts.forEachIndexed { i, part ->
        builder.append(SpanUtil.italic(part))
        if (i != parts.size - 1) {
          builder.append(reaction.emoji)
        }
      }

      if (text.endsWith(EMOJI_REPLACEMENT_STRING)) {
        builder.append(reaction.emoji)
      }
      builder
    }
  }

  private fun getReactionMessageBody(context: Context): CharSequence {
    val body: CharSequence = MentionUtil.updateBodyWithDisplayNames(context, record)
    val bodyIsEmpty: Boolean = TextUtils.isEmpty(body)

    return if (MessageRecordUtil.hasSharedContact(record)) {
      val contact: Contact = (record as MmsMessageRecord).sharedContacts[0]
      val summary: CharSequence = ContactUtil.getStringSummary(context, contact)
      context.getString(R.string.MessageNotifier_reacted_s_to_s, EMOJI_REPLACEMENT_STRING, summary)
    } else if (MessageRecordUtil.hasSticker(record)) {
      context.getString(R.string.MessageNotifier_reacted_s_to_your_sticker, EMOJI_REPLACEMENT_STRING)
    } else if (record.isMms && record.isViewOnce) {
      context.getString(R.string.MessageNotifier_reacted_s_to_your_view_once_media, EMOJI_REPLACEMENT_STRING)
    } else if (!bodyIsEmpty) {
      context.getString(R.string.MessageNotifier_reacted_s_to_s, EMOJI_REPLACEMENT_STRING, body)
    } else if (MessageRecordUtil.isMediaMessage(record) && MediaUtil.isVideoType(getMessageContentType((record as MmsMessageRecord)))) {
      context.getString(R.string.MessageNotifier_reacted_s_to_your_video, EMOJI_REPLACEMENT_STRING)
    } else if (MessageRecordUtil.isMediaMessage(record) && MediaUtil.isImageType(getMessageContentType((record as MmsMessageRecord)))) {
      context.getString(R.string.MessageNotifier_reacted_s_to_your_image, EMOJI_REPLACEMENT_STRING)
    } else if (MessageRecordUtil.isMediaMessage(record) && MediaUtil.isAudioType(getMessageContentType((record as MmsMessageRecord)))) {
      context.getString(R.string.MessageNotifier_reacted_s_to_your_audio, EMOJI_REPLACEMENT_STRING)
    } else if (MessageRecordUtil.isMediaMessage(record)) {
      context.getString(R.string.MessageNotifier_reacted_s_to_your_file, EMOJI_REPLACEMENT_STRING)
    } else {
      context.getString(R.string.MessageNotifier_reacted_s_to_s, EMOJI_REPLACEMENT_STRING, body)
    }
  }

  override fun getStartingPosition(context: Context): Int {
    return DatabaseFactory.getMmsSmsDatabase(context).getMessagePositionInConversation(threadId, record.dateReceived)
  }

  override fun getLargeIconUri(): Uri? = null
  override fun getBigPictureUri(): Uri? = null
  override fun getThumbnailInfo(context: Context): ThumbnailInfo = ThumbnailInfo()
  override fun canReply(context: Context): Boolean = false

  override fun toString(): String {
    return "ReactionNotification(timestamp=$timestamp, isNewNotification=$isNewNotification)"
  }
}
