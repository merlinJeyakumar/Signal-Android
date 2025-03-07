package org.thoughtcrime.securesms.notifications.v2

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.drawable.Drawable
import android.net.Uri
import com.bumptech.glide.load.engine.DiskCacheStrategy
import org.thoughtcrime.securesms.R
import org.thoughtcrime.securesms.contacts.avatars.ContactPhoto
import org.thoughtcrime.securesms.contacts.avatars.FallbackContactPhoto
import org.thoughtcrime.securesms.mms.DecryptableStreamUriLoader.DecryptableUri
import org.thoughtcrime.securesms.mms.GlideApp
import org.thoughtcrime.securesms.recipients.Recipient
import org.thoughtcrime.securesms.util.BitmapUtil
import java.util.concurrent.ExecutionException

fun Drawable?.toLargeBitmap(context: Context): Bitmap? {
  if (this == null) {
    return null
  }

  val largeIconTargetSize: Int = context.resources.getDimensionPixelSize(R.dimen.contact_photo_target_size)

  return BitmapUtil.createFromDrawable(this, largeIconTargetSize, largeIconTargetSize)
}

fun Recipient.getContactDrawable(context: Context): Drawable? {
  val contactPhoto: ContactPhoto? = contactPhoto
  val fallbackContactPhoto: FallbackContactPhoto = fallbackContactPhoto
  return if (contactPhoto != null) {
    try {
      GlideApp.with(context.applicationContext)
        .load(contactPhoto)
        .diskCacheStrategy(DiskCacheStrategy.ALL)
        .circleCrop()
        .submit(
          context.resources.getDimensionPixelSize(android.R.dimen.notification_large_icon_width),
          context.resources.getDimensionPixelSize(android.R.dimen.notification_large_icon_height)
        )
        .get()
    } catch (e: InterruptedException) {
      fallbackContactPhoto.asDrawable(context, color.toConversationColor(context))
    } catch (e: ExecutionException) {
      fallbackContactPhoto.asDrawable(context, color.toConversationColor(context))
    }
  } else {
    fallbackContactPhoto.asDrawable(context, color.toConversationColor(context))
  }
}

fun Uri.toBitmap(context: Context, dimension: Int): Bitmap {
  return try {
    GlideApp.with(context.applicationContext)
      .asBitmap()
      .load(DecryptableUri(this))
      .diskCacheStrategy(DiskCacheStrategy.NONE)
      .submit(dimension, dimension)
      .get()
  } catch (e: InterruptedException) {
    Bitmap.createBitmap(dimension, dimension, Bitmap.Config.RGB_565)
  } catch (e: ExecutionException) {
    Bitmap.createBitmap(dimension, dimension, Bitmap.Config.RGB_565)
  }
}

fun Intent.makeUniqueToPreventMerging(): Intent {
  return setData((Uri.parse("custom://" + System.currentTimeMillis())))
}
