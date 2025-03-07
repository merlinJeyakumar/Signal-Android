package org.thoughtcrime.securesms.jobs;

import android.content.Context;

import androidx.annotation.NonNull;

import com.annimon.stream.Stream;

import net.sqlcipher.database.SQLiteDatabase;

import org.signal.core.util.logging.Log;
import org.thoughtcrime.securesms.database.DatabaseFactory;
import org.thoughtcrime.securesms.database.RecipientDatabase;
import org.thoughtcrime.securesms.database.RecipientDatabase.RecipientSettings;
import org.thoughtcrime.securesms.database.StorageKeyDatabase;
import org.thoughtcrime.securesms.dependencies.ApplicationDependencies;
import org.thoughtcrime.securesms.jobmanager.Data;
import org.thoughtcrime.securesms.jobmanager.Job;
import org.thoughtcrime.securesms.jobmanager.impl.NetworkConstraint;
import org.thoughtcrime.securesms.keyvalue.SignalStore;
import org.thoughtcrime.securesms.migrations.StorageServiceMigrationJob;
import org.thoughtcrime.securesms.recipients.Recipient;
import org.thoughtcrime.securesms.recipients.RecipientId;
import org.thoughtcrime.securesms.storage.AccountRecordProcessor;
import org.thoughtcrime.securesms.storage.ContactRecordProcessor;
import org.thoughtcrime.securesms.storage.GroupV1RecordProcessor;
import org.thoughtcrime.securesms.storage.GroupV2RecordProcessor;
import org.thoughtcrime.securesms.storage.StorageRecordProcessor;
import org.thoughtcrime.securesms.storage.StorageRecordUpdate;
import org.thoughtcrime.securesms.storage.StorageSyncHelper;
import org.thoughtcrime.securesms.storage.StorageSyncHelper.KeyDifferenceResult;
import org.thoughtcrime.securesms.storage.StorageSyncHelper.LocalWriteResult;
import org.thoughtcrime.securesms.storage.StorageSyncHelper.WriteOperationResult;
import org.thoughtcrime.securesms.storage.StorageSyncModels;
import org.thoughtcrime.securesms.storage.StorageSyncValidations;
import org.thoughtcrime.securesms.transport.RetryLaterException;
import org.thoughtcrime.securesms.util.SetUtil;
import org.thoughtcrime.securesms.util.TextSecurePreferences;
import org.thoughtcrime.securesms.util.Util;
import org.whispersystems.libsignal.InvalidKeyException;
import org.whispersystems.libsignal.util.guava.Optional;
import org.whispersystems.signalservice.api.SignalServiceAccountManager;
import org.whispersystems.signalservice.api.push.exceptions.PushNetworkException;
import org.whispersystems.signalservice.api.storage.SignalAccountRecord;
import org.whispersystems.signalservice.api.storage.SignalContactRecord;
import org.whispersystems.signalservice.api.storage.SignalGroupV1Record;
import org.whispersystems.signalservice.api.storage.SignalGroupV2Record;
import org.whispersystems.signalservice.api.storage.SignalRecord;
import org.whispersystems.signalservice.api.storage.SignalStorageManifest;
import org.whispersystems.signalservice.api.storage.SignalStorageRecord;
import org.whispersystems.signalservice.api.storage.StorageId;
import org.whispersystems.signalservice.api.storage.StorageKey;
import org.whispersystems.signalservice.internal.storage.protos.ManifestRecord;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.TimeUnit;

/**
 * Does a full sync of our local storage state with the remote storage state. Will write any pending
 * local changes and resolve any conflicts with remote storage.
 *
 * This should be performed whenever a change is made locally, or whenever we want to retrieve
 * changes that have been made remotely.
 *
 * == Important Implementation Notes ==
 *
 * - We want to use a transaction to guarantee atomicity of our changes and to prevent other threads
 *   from writing while the sync is happening. But that means we also need to be very careful with
 *   what happens inside the transaction. Namely, we *cannot* perform network activity inside the
 *   transaction.
 *
 * - This puts us in a funny situation where we have to get remote data, begin a transaction to
 *   resolve the sync, and then end the transaction (and therefore commit our changes) *before*
 *   we write the data remotely. Normally, this would be dangerous, as our view of the data could
 *   fall out of sync if the network request fails. However, because of how the sync works, as long
 *   as we don't update our local manifest version until after the network request succeeds, it
 *   should all sort itself out in the retry. Because if our network request failed, then we
 *   wouldn't have written all of the new keys, and we'll still see a bunch of remote-only keys that
 *   we'll merge with local data to generate another equally-valid set of remote changes.
 *
 *
 * == Technical Overview ==
 *
 * The Storage Service is, at it's core, a dumb key-value store. It stores various types of records,
 * each of which is given an ID. It also stores a manifest, which has the complete list of all IDs.
 * The manifest has a monotonically-increasing version associated with it. Whenever a change is
 * made to the stored data, you upload a new manifest with the updated ID set.
 *
 * An ID corresponds to an unchanging snapshot of a record. That is, if the underlying record is
 * updated, that update is performed by deleting the old ID/record and inserting a new one. This
 * makes it easy to determine what's changed in a given version of a manifest -- simply diff the
 * list of IDs in the manifest with the list of IDs we have locally.
 *
 * So, at it's core, syncing isn't all that complicated.
 * - If we see the remote manifest version is newer than ours, then we grab the manifest and compute
 *   the diff in IDs.
 * - Then, we fetch the actual records that correspond to the remote-only IDs.
 * - Afterwards, we take those records and merge them into our local data store.
 * - The merging process could result in changes that need to be written back to the service, so
 *   we write those back.
 * - Finally, we look at any other local changes that were made (independent of the ID diff) and
 *   make sure those are written to the service.
 *
 * Of course, you'll notice that there's a lot of code to support that goal. That's mostly because
 * converting local data into a format that can be compared with, merged, and eventually written
 * back to both local and remote data stores is tiresome. There's also lots of general bookkeeping,
 * error handling, cleanup scenarios, logging, etc.
 *
 * == Syncing a new field on an existing record ==
 *
 * - Add the field the the respective proto
 * - Update the respective model (i.e. {@link SignalContactRecord})
 *     - Add getters
 *     - Update the builder
 *     - Update {@link SignalRecord#describeDiff(SignalRecord)}.
 * - Update the respective record processor (i.e {@link ContactRecordProcessor}). You need to make
 *   sure that you're:
 *     - Merging the attributes, likely preferring remote
 *     - Adding to doParamsMatch()
 *     - Adding the parameter to the builder chain when creating a merged model
 * - Update builder usage in StorageSyncModels
 * - Handle the new data when writing to the local storage
 *   (i.e. {@link RecipientDatabase#applyStorageSyncContactUpdate(StorageRecordUpdate)}).
 * - Make sure that whenever you change the field in the UI, we mark the row as dirty and call
 *   {@link StorageSyncHelper#scheduleSyncForDataChange()}.
 * - If you're syncing a field that was otherwise already present in the UI, you'll probably want
 *   to enqueue a {@link StorageServiceMigrationJob} as an app migration to make sure it gets
 *   synced.
 */
public class StorageSyncJobV2 extends BaseJob {

  public static final String KEY       = "StorageSyncJobV2";
  public static final String QUEUE_KEY = "StorageSyncingJobs";

  private static final String TAG = Log.tag(StorageSyncJobV2.class);

  StorageSyncJobV2() {
    this(new Parameters.Builder().addConstraint(NetworkConstraint.KEY)
                                 .setQueue(QUEUE_KEY)
                                 .setMaxInstancesForFactory(2)
                                 .setLifespan(TimeUnit.DAYS.toMillis(1))
                                 .setMaxAttempts(3)
                                 .build());
  }

  private StorageSyncJobV2(@NonNull Parameters parameters) {
    super(parameters);
  }

  @Override
  public @NonNull Data serialize() {
    return Data.EMPTY;
  }

  @Override
  public @NonNull String getFactoryKey() {
    return KEY;
  }

  @Override
  protected void onRun() throws IOException, RetryLaterException {
    if (!SignalStore.kbsValues().hasPin() && !SignalStore.kbsValues().hasOptedOut()) {
      Log.i(TAG, "Doesn't have a PIN. Skipping.");
      return;
    }

    if (!TextSecurePreferences.isPushRegistered(context)) {
      Log.i(TAG, "Not registered. Skipping.");
      return;
    }

    try {
      boolean needsMultiDeviceSync = performSync();

      if (TextSecurePreferences.isMultiDevice(context) && needsMultiDeviceSync) {
        ApplicationDependencies.getJobManager().add(new MultiDeviceStorageSyncRequestJob());
      }

      SignalStore.storageServiceValues().onSyncCompleted();
    } catch (InvalidKeyException e) {
      Log.w(TAG, "Failed to decrypt remote storage! Force-pushing and syncing the storage key to linked devices.", e);

      ApplicationDependencies.getJobManager().startChain(new MultiDeviceKeysUpdateJob())
                                             .then(new StorageForcePushJob())
                                             .then(new MultiDeviceStorageSyncRequestJob())
                                             .enqueue();
    }
  }

  @Override
  protected boolean onShouldRetry(@NonNull Exception e) {
    return e instanceof PushNetworkException || e instanceof RetryLaterException;
  }

  @Override
  public void onFailure() {
  }

  private boolean performSync() throws IOException, RetryLaterException, InvalidKeyException {
    Recipient                   self               = Recipient.self();
    SignalServiceAccountManager accountManager     = ApplicationDependencies.getSignalServiceAccountManager();
    RecipientDatabase           recipientDatabase  = DatabaseFactory.getRecipientDatabase(context);
    StorageKeyDatabase          storageKeyDatabase = DatabaseFactory.getStorageKeyDatabase(context);
    StorageKey                  storageServiceKey  = SignalStore.storageServiceValues().getOrCreateStorageKey();

    boolean                         needsMultiDeviceSync  = false;
    boolean                         needsForcePush        = false;
    long                            localManifestVersion  = TextSecurePreferences.getStorageManifestVersion(context);
    Optional<SignalStorageManifest> remoteManifest        = accountManager.getStorageManifestIfDifferentVersion(storageServiceKey, localManifestVersion);
    long                            remoteManifestVersion = remoteManifest.transform(SignalStorageManifest::getVersion).or(localManifestVersion);

    Log.i(TAG, "Our version: " + localManifestVersion + ", their version: " + remoteManifestVersion);

    if (remoteManifest.isPresent() && remoteManifestVersion > localManifestVersion) {
      Log.i(TAG, "[Remote Sync] Newer manifest version found!");

      List<StorageId>     localStorageIdsBeforeMerge = getAllLocalStorageIds(context, Recipient.self().fresh());
      KeyDifferenceResult keyDifference              = StorageSyncHelper.findKeyDifference(remoteManifest.get().getStorageIds(), localStorageIdsBeforeMerge);

      if (keyDifference.hasTypeMismatches()) {
        Log.w(TAG, "[Remote Sync] Found type mismatches in the key sets! Scheduling a force push after this sync completes.");
        needsForcePush = true;
      }

      if (!keyDifference.isEmpty()) {
        Log.i(TAG, "[Remote Sync] Retrieving records for key difference: " + keyDifference);

        List<SignalStorageRecord> remoteOnly = accountManager.readStorageRecords(storageServiceKey, keyDifference.getRemoteOnlyKeys());

        if (remoteOnly.size() != keyDifference.getRemoteOnlyKeys().size()) {
          Log.w(TAG, "[Remote Sync] Could not find all remote-only records! Requested: " + keyDifference.getRemoteOnlyKeys().size() + ", Found: " + remoteOnly.size() + ". Scheduling a force push after this sync completes.");
          needsForcePush = true;
        }

        List<SignalContactRecord> remoteContacts = new LinkedList<>();
        List<SignalGroupV1Record> remoteGv1      = new LinkedList<>();
        List<SignalGroupV2Record> remoteGv2      = new LinkedList<>();
        List<SignalAccountRecord> remoteAccount  = new LinkedList<>();
        List<SignalStorageRecord> remoteUnknown  = new LinkedList<>();

        for (SignalStorageRecord remote : remoteOnly) {
          if (remote.getContact().isPresent()) {
            remoteContacts.add(remote.getContact().get());
          } else if (remote.getGroupV1().isPresent()) {
            remoteGv1.add(remote.getGroupV1().get());
          } else if (remote.getGroupV2().isPresent()) {
            remoteGv2.add(remote.getGroupV2().get());
          } else if (remote.getAccount().isPresent()) {
            remoteAccount.add(remote.getAccount().get());
          } else {
            remoteUnknown.add(remote);
          }
        }

        WriteOperationResult mergeWriteOperation;

        SQLiteDatabase db = DatabaseFactory.getInstance(context).getRawDatabase();

        db.beginTransaction();
        try {
          StorageRecordProcessor.Result<SignalContactRecord> contactResult = new ContactRecordProcessor(context, self).process(remoteContacts, StorageSyncHelper.KEY_GENERATOR);
          StorageRecordProcessor.Result<SignalGroupV1Record> gv1Result     = new GroupV1RecordProcessor(context).process(remoteGv1, StorageSyncHelper.KEY_GENERATOR);
          StorageRecordProcessor.Result<SignalGroupV2Record> gv2Result     = new GroupV2RecordProcessor(context).process(remoteGv2, StorageSyncHelper.KEY_GENERATOR);
          StorageRecordProcessor.Result<SignalAccountRecord> accountResult = new AccountRecordProcessor(context, self).process(remoteAccount, StorageSyncHelper.KEY_GENERATOR);

          List<SignalStorageRecord> unknownInserts = remoteUnknown;
          List<StorageId>           unknownDeletes = Stream.of(keyDifference.getLocalOnlyKeys()).filter(StorageId::isUnknown).toList();

          storageKeyDatabase.insert(unknownInserts);
          storageKeyDatabase.delete(unknownDeletes);

          List<StorageId> localStorageIdsAfterMerge = getAllLocalStorageIds(context, Recipient.self().fresh());

          if (contactResult.isLocalOnly() && gv1Result.isLocalOnly() && gv2Result.isLocalOnly() && accountResult.isLocalOnly() && unknownInserts.isEmpty() && unknownDeletes.isEmpty()) {
            Log.i(TAG, "Result: No remote updates/deletes");
            Log.i(TAG, "IDs   : " + localStorageIdsBeforeMerge.size() + " IDs before merge, " + localStorageIdsAfterMerge.size() + " IDs after merge");
          } else {
            Log.i(TAG, "Contacts: " + contactResult.toString());
            Log.i(TAG, "GV1     : " + gv1Result.toString());
            Log.i(TAG, "GV2     : " + gv2Result.toString());
            Log.i(TAG, "Account : " + accountResult.toString());
            Log.i(TAG, "Unknowns: " + unknownInserts.size() + " Inserts, " + unknownDeletes.size() + " Deletes");
            Log.i(TAG, "IDs     : " + localStorageIdsBeforeMerge.size() + " IDs before merge, " + localStorageIdsAfterMerge.size() + " IDs after merge");
          }

          //noinspection unchecked Stop yelling at my beautiful method signatures
          mergeWriteOperation = StorageSyncHelper.createWriteOperation(remoteManifest.get().getVersion(), localStorageIdsAfterMerge, contactResult, gv1Result, gv2Result, accountResult);

          KeyDifferenceResult postMergeKeyDifference  = StorageSyncHelper.findKeyDifference(remoteManifest.get().getStorageIds(), mergeWriteOperation.getManifest().getStorageIds());
          List<StorageId>     postMergeLocalOnlyIds   = postMergeKeyDifference.getLocalOnlyKeys();
          List<ByteBuffer>    postMergeRemoteOnlyIds  = Stream.of(postMergeKeyDifference.getRemoteOnlyKeys()).map(StorageId::getRaw).map(ByteBuffer::wrap).toList();
          List<StorageId>     remoteInsertIds         = Stream.of(mergeWriteOperation.getInserts()).map(SignalStorageRecord::getId).toList();
          List<ByteBuffer>    remoteDeleteIds         = Stream.of(mergeWriteOperation.getDeletes()).map(ByteBuffer::wrap).toList();
          Set<StorageId>      unhandledLocalOnlyIds   = SetUtil.difference(postMergeLocalOnlyIds, remoteInsertIds);
          Set<ByteBuffer>     unhandledRemoteOnlyIds  = SetUtil.difference(postMergeRemoteOnlyIds, remoteDeleteIds);

          if (unhandledLocalOnlyIds.size() > 0) {
            Log.i(TAG, "[Remote Sync] After the conflict resolution, there are " + unhandledLocalOnlyIds.size() + " local-only records remaining that weren't otherwise inserted. Adding them as inserts.");

            List<SignalStorageRecord> unhandledInserts = buildLocalStorageRecords(context, self, unhandledLocalOnlyIds);

            mergeWriteOperation = new WriteOperationResult(mergeWriteOperation.getManifest(),
                                                           Util.concatenatedList(mergeWriteOperation.getInserts(), unhandledInserts),
                                                           mergeWriteOperation.getDeletes());

            recipientDatabase.clearDirtyStateForStorageIds(unhandledLocalOnlyIds);
          }

          if (unhandledRemoteOnlyIds.size() > 0) {
            Log.i(TAG, "[Remote Sync] After the conflict resolution, there are " + unhandledRemoteOnlyIds.size() + " remote-only records remaining that weren't otherwise deleted. Adding them as deletes.");

            List<byte[]> unhandledDeletes = Stream.of(unhandledRemoteOnlyIds).map(ByteBuffer::array).toList();

            mergeWriteOperation = new WriteOperationResult(mergeWriteOperation.getManifest(),
                                                           mergeWriteOperation.getInserts(),
                                                           Util.concatenatedList(mergeWriteOperation.getDeletes(), unhandledDeletes));

          }

          db.setTransactionSuccessful();
        } finally {
          db.endTransaction();
          ApplicationDependencies.getDatabaseObserver().notifyConversationListListeners();
        }

        if (!mergeWriteOperation.isEmpty()) {
          Log.i(TAG, "[Remote Sync] WriteOperationResult :: " + mergeWriteOperation);
          Log.i(TAG, "[Remote Sync] We have something to write remotely.");

          StorageSyncValidations.validate(mergeWriteOperation, remoteManifest, needsForcePush);

          Optional<SignalStorageManifest> conflict = accountManager.writeStorageRecords(storageServiceKey, mergeWriteOperation.getManifest(), mergeWriteOperation.getInserts(), mergeWriteOperation.getDeletes());

          if (conflict.isPresent()) {
            Log.w(TAG, "[Remote Sync] Hit a conflict when trying to resolve the conflict! Retrying.");
            throw new RetryLaterException();
          }

          remoteManifestVersion = mergeWriteOperation.getManifest().getVersion();
          remoteManifest        = Optional.of(mergeWriteOperation.getManifest());

          needsMultiDeviceSync = true;
        } else {
          Log.i(TAG, "[Remote Sync] No remote writes needed.");
        }

        Log.i(TAG, "[Remote Sync] Updating local manifest version to: " + remoteManifestVersion);
        TextSecurePreferences.setStorageManifestVersion(context, remoteManifestVersion);
      } else {
        Log.i(TAG, "[Remote Sync] Remote version was newer, there were no remote-only keys.");
        Log.i(TAG, "[Remote Sync] Updating local manifest version to: " + remoteManifest.get().getVersion());
        TextSecurePreferences.setStorageManifestVersion(context, remoteManifest.get().getVersion());
      }
    }

    localManifestVersion = TextSecurePreferences.getStorageManifestVersion(context);

    List<StorageId>               allLocalStorageKeys  = getAllLocalStorageIds(context, self);
    List<RecipientSettings>       pendingUpdates       = recipientDatabase.getPendingRecipientSyncUpdates();
    List<RecipientSettings>       pendingInsertions    = recipientDatabase.getPendingRecipientSyncInsertions();
    List<RecipientSettings>       pendingDeletions     = recipientDatabase.getPendingRecipientSyncDeletions();
    Optional<SignalAccountRecord> pendingAccountInsert = StorageSyncHelper.getPendingAccountSyncInsert(context, self);
    Optional<SignalAccountRecord> pendingAccountUpdate = StorageSyncHelper.getPendingAccountSyncUpdate(context, self);
    Optional<LocalWriteResult>    localWriteResult     = StorageSyncHelper.buildStorageUpdatesForLocal(localManifestVersion,
                                                                                                       allLocalStorageKeys,
                                                                                                       pendingUpdates,
                                                                                                       pendingInsertions,
                                                                                                       pendingDeletions,
                                                                                                       pendingAccountUpdate,
                                                                                                       pendingAccountInsert);

    if (localWriteResult.isPresent()) {
      Log.i(TAG, String.format(Locale.ENGLISH, "[Local Changes] Local changes present. %d updates, %d inserts, %d deletes, account update: %b, account insert: %b.", pendingUpdates.size(), pendingInsertions.size(), pendingDeletions.size(), pendingAccountUpdate.isPresent(), pendingAccountInsert.isPresent()));

      WriteOperationResult localWrite = localWriteResult.get().getWriteResult();

      Log.i(TAG, "[Local Changes] WriteOperationResult :: " + localWrite);

      if (localWrite.isEmpty()) {
        throw new AssertionError("Decided there were local writes, but our write result was empty!");
      }

      StorageSyncValidations.validate(localWrite, remoteManifest, needsForcePush);

      Optional<SignalStorageManifest> conflict = accountManager.writeStorageRecords(storageServiceKey, localWrite.getManifest(), localWrite.getInserts(), localWrite.getDeletes());

      if (conflict.isPresent()) {
        Log.w(TAG, "[Local Changes] Hit a conflict when trying to upload our local writes! Retrying.");
        throw new RetryLaterException();
      }

      List<RecipientId> clearIds = new ArrayList<>(pendingUpdates.size() + pendingInsertions.size() + pendingDeletions.size() + 1);

      clearIds.addAll(Stream.of(pendingUpdates).map(RecipientSettings::getId).toList());
      clearIds.addAll(Stream.of(pendingInsertions).map(RecipientSettings::getId).toList());
      clearIds.addAll(Stream.of(pendingDeletions).map(RecipientSettings::getId).toList());
      clearIds.add(Recipient.self().getId());

      recipientDatabase.clearDirtyState(clearIds);
      recipientDatabase.updateStorageIds(localWriteResult.get().getStorageKeyUpdates());

      needsMultiDeviceSync = true;

      Log.i(TAG, "[Local Changes] Updating local manifest version to: " + localWriteResult.get().getWriteResult().getManifest().getVersion());
      TextSecurePreferences.setStorageManifestVersion(context, localWriteResult.get().getWriteResult().getManifest().getVersion());
    } else {
      Log.i(TAG, "[Local Changes] No local changes.");
    }

    if (needsForcePush) {
      Log.w(TAG, "Scheduling a force push.");
      ApplicationDependencies.getJobManager().add(new StorageForcePushJob());
    }

    return needsMultiDeviceSync;
  }

  private static @NonNull List<StorageId> getAllLocalStorageIds(@NonNull Context context, @NonNull Recipient self) {
    return Util.concatenatedList(DatabaseFactory.getRecipientDatabase(context).getContactStorageSyncIds(),
                                 Collections.singletonList(StorageId.forAccount(self.getStorageServiceId())),
                                 DatabaseFactory.getStorageKeyDatabase(context).getAllKeys());
  }

  private static @NonNull List<SignalStorageRecord> buildLocalStorageRecords(@NonNull Context context, @NonNull Recipient self, @NonNull Collection<StorageId> ids) {
    if (ids.isEmpty()) {
      return Collections.emptyList();
    }

    RecipientDatabase  recipientDatabase  = DatabaseFactory.getRecipientDatabase(context);
    StorageKeyDatabase storageKeyDatabase = DatabaseFactory.getStorageKeyDatabase(context);

    List<SignalStorageRecord> records = new ArrayList<>(ids.size());

    for (StorageId id : ids) {
      switch (id.getType()) {
        case ManifestRecord.Identifier.Type.CONTACT_VALUE:
        case ManifestRecord.Identifier.Type.GROUPV1_VALUE:
        case ManifestRecord.Identifier.Type.GROUPV2_VALUE:
          RecipientSettings settings = recipientDatabase.getByStorageId(id.getRaw());
          if (settings != null) {
            if (settings.getGroupType() == RecipientDatabase.GroupType.SIGNAL_V2 && settings.getSyncExtras().getGroupMasterKey() == null) {
              throw new MissingGv2MasterKeyError();
            } else {
              records.add(StorageSyncModels.localToRemoteRecord(settings));
            }
          } else {
            throw new MissingRecipientModelError("Missing local recipient model! Type: " + id.getType());
          }
          break;
        case ManifestRecord.Identifier.Type.ACCOUNT_VALUE:
          if (!Arrays.equals(self.getStorageServiceId(), id.getRaw())) {
            throw new AssertionError("Local storage ID doesn't match self!");
          }
          records.add(StorageSyncHelper.buildAccountRecord(context, self));
          break;
        default:
          SignalStorageRecord unknown = storageKeyDatabase.getById(id.getRaw());
          if (unknown != null) {
            records.add(unknown);
          } else {
            throw new MissingUnknownModelError("Missing local unknown model! Type: " + id.getType());
          }
          break;
      }
    }

    return records;
  }

  private static final class MissingGv2MasterKeyError extends Error {}

  private static final class MissingRecipientModelError extends Error {
    public MissingRecipientModelError(String message) {
      super(message);
    }
  }

  private static final class MissingUnknownModelError extends Error {
    public MissingUnknownModelError(String message) {
      super(message);
    }
  }

  public static final class Factory implements Job.Factory<StorageSyncJobV2> {
    @Override
    public @NonNull StorageSyncJobV2 create(@NonNull Parameters parameters, @NonNull Data data) {
      return new StorageSyncJobV2(parameters);
    }
  }
}
