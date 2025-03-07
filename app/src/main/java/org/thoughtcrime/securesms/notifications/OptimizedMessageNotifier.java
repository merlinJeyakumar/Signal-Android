package org.thoughtcrime.securesms.notifications;

import android.app.Application;
import android.content.Context;
import android.os.Handler;

import androidx.annotation.MainThread;
import androidx.annotation.NonNull;

import org.signal.core.util.concurrent.SignalExecutors;
import org.thoughtcrime.securesms.notifications.v2.MessageNotifierV2;
import org.thoughtcrime.securesms.recipients.Recipient;
import org.thoughtcrime.securesms.util.BubbleUtil;
import org.thoughtcrime.securesms.util.FeatureFlags;
import org.thoughtcrime.securesms.util.LeakyBucketLimiter;
import org.thoughtcrime.securesms.util.Util;

/**
 * Uses a leaky-bucket strategy to limiting notification updates.
 */
public class OptimizedMessageNotifier implements MessageNotifier {

  private final LeakyBucketLimiter limiter;

  private final DefaultMessageNotifier messageNotifierV1;
  private final MessageNotifierV2      messageNotifierV2;

  @MainThread
  public OptimizedMessageNotifier(@NonNull Application context) {
    this.limiter           = new LeakyBucketLimiter(5, 1000, new Handler(SignalExecutors.getAndStartHandlerThread("signal-notifier").getLooper()));
    this.messageNotifierV1 = new DefaultMessageNotifier();
    this.messageNotifierV2 = new MessageNotifierV2(context);
  }

  @Override
  public void setVisibleThread(long threadId) {
    getNotifier().setVisibleThread(threadId);
  }

  @Override
  public long getVisibleThread() {
    return getNotifier().getVisibleThread();
  }

  @Override
  public void clearVisibleThread() {
    getNotifier().clearVisibleThread();
  }

  @Override
  public void setLastDesktopActivityTimestamp(long timestamp) {
    getNotifier().setLastDesktopActivityTimestamp(timestamp);
  }

  @Override
  public void notifyMessageDeliveryFailed(Context context, Recipient recipient, long threadId) {
    getNotifier().notifyMessageDeliveryFailed(context, recipient, threadId);
  }

  @Override
  public void cancelDelayedNotifications() {
    getNotifier().cancelDelayedNotifications();
  }

  @Override
  public void updateNotification(@NonNull Context context) {
    runOnLimiter(() -> getNotifier().updateNotification(context));
  }

  @Override
  public void updateNotification(@NonNull Context context, long threadId) {
    runOnLimiter(() -> getNotifier().updateNotification(context, threadId));
  }

  @Override
  public void updateNotification(@NonNull Context context, long threadId, @NonNull BubbleUtil.BubbleState defaultBubbleState) {
    runOnLimiter(() -> getNotifier().updateNotification(context, threadId, defaultBubbleState));
  }

  @Override
  public void updateNotification(@NonNull Context context, long threadId, boolean signal) {
    runOnLimiter(() -> getNotifier().updateNotification(context, threadId, signal));
  }

  @Override
  public void updateNotification(@NonNull Context context, long threadId, boolean signal, int reminderCount, @NonNull BubbleUtil.BubbleState defaultBubbleState) {
    runOnLimiter(() -> getNotifier().updateNotification(context, threadId, signal, reminderCount, defaultBubbleState));
  }

  @Override
  public void clearReminder(@NonNull Context context) {
    getNotifier().clearReminder(context);
  }

  private void runOnLimiter(@NonNull Runnable runnable) {
    Throwable prettyException = new Throwable();
    limiter.run(() -> {
      try {
        runnable.run();
      } catch (RuntimeException e) {
        throw Util.appendStackTrace(e, prettyException);
      }
    });
  }

  private MessageNotifier getNotifier() {
    if (FeatureFlags.useNewNotificationSystem()) {
      return messageNotifierV2;
    } else {
      return messageNotifierV1;
    }
  }
}
