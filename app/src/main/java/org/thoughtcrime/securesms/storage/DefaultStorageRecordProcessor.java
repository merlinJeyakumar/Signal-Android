package org.thoughtcrime.securesms.storage;

import androidx.annotation.NonNull;

import org.signal.core.util.logging.Log;
import org.whispersystems.libsignal.util.guava.Optional;
import org.whispersystems.signalservice.api.storage.SignalRecord;

import java.io.IOException;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

/**
 * An implementation of {@link StorageRecordProcessor} that solidifies a pattern and reduces
 * duplicate code in individual implementations.
 *
 * Concerning the implementation of {@link #compare(Object, Object)}, it's purpose is to detect if
 * two items would map to the same logical entity (i.e. they would correspond to the same record in
 * our local store). We use it for a {@link TreeSet}, so mainly it's just important that the '0'
 * case is correct. Other cases are whatever, just make it something stable.
 */
abstract class DefaultStorageRecordProcessor<E extends SignalRecord> implements StorageRecordProcessor<E>, Comparator<E> {

  private static final String TAG = Log.tag(DefaultStorageRecordProcessor.class);

  /**
   * One type of invalid remote data this handles is two records mapping to the same local data. We
   * have to trim this bad data out, because if we don't, we'll upload an ID set that only has one
   * of the IDs in it, but won't properly delete the dupes, which will then fail our validation
   * checks.
   *
   * This is a bit tricky -- as we process records, ID's are written back to the local store, so we
   * can't easily be like "oh multiple records are mapping to the same local storage ID". And in
   * general we rely on SignalRecords to implement an equals() that includes the StorageId, so using
   * a regular set is out. Instead, we use a {@link TreeSet}, which allows us to define a custom
   * comparator for checking equality. Then we delegate to the subclass to tell us if two items are
   * the same based on their actual data (i.e. two contacts having the same UUID, or two groups
   * having the same MasterKey).
   */
  @Override
  public @NonNull Result<E> process(@NonNull Collection<E> remoteRecords, @NonNull StorageKeyGenerator keyGenerator) throws IOException {
    List<E>                      remoteDeletes  = new LinkedList<>();
    List<StorageRecordUpdate<E>> remoteUpdates  = new LinkedList<>();
    Set<E>                       matchedRecords = new TreeSet<>(this);

    for (E remote : remoteRecords) {
      if (isInvalid(remote)) {
        remoteDeletes.add(remote);
      } else {
        Optional<E> local = getMatching(remote, keyGenerator);

        if (local.isPresent()) {
          E merged = merge(remote, local.get(), keyGenerator);

          if (matchedRecords.contains(local.get())) {
            Log.w(TAG, "Multiple remote records map to the same local record! Marking this one for deletion. (Type: " + local.get().getClass().getSimpleName() + ")");
            remoteDeletes.add(remote);
          } else {
            matchedRecords.add(local.get());

            if (!merged.equals(remote)) {
              remoteUpdates.add(new StorageRecordUpdate<>(remote, merged));
            }

            if (!merged.equals(local.get())) {
              updateLocal(new StorageRecordUpdate<>(local.get(), merged));
            }
          }
        } else {
          insertLocal(remote);
        }
      }
    }

    return new Result<>(remoteUpdates, remoteDeletes);
  }

  /**
   * @return True if the record is invalid and should be removed from storage service, otherwise false.
   */
  abstract boolean isInvalid(@NonNull E remote);

  /**
   * Only records that pass the validity check (i.e. return false from {@link #isInvalid(SignalRecord)}
   * make it to here, so you can assume all records are valid.
   */
  abstract @NonNull Optional<E> getMatching(@NonNull E remote, @NonNull StorageKeyGenerator keyGenerator);

  abstract @NonNull E merge(@NonNull E remote, @NonNull E local, @NonNull StorageKeyGenerator keyGenerator);
  abstract void insertLocal(@NonNull E record) throws IOException;
  abstract void updateLocal(@NonNull StorageRecordUpdate<E> update);
}
