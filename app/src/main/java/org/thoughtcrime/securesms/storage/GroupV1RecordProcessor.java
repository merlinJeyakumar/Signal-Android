package org.thoughtcrime.securesms.storage;

import android.content.Context;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import org.signal.core.util.logging.Log;
import org.thoughtcrime.securesms.database.DatabaseFactory;
import org.thoughtcrime.securesms.database.GroupDatabase;
import org.thoughtcrime.securesms.database.RecipientDatabase;
import org.thoughtcrime.securesms.groups.BadGroupIdException;
import org.thoughtcrime.securesms.groups.GroupId;
import org.thoughtcrime.securesms.recipients.RecipientId;
import org.whispersystems.libsignal.util.guava.Optional;
import org.whispersystems.signalservice.api.storage.SignalGroupV1Record;

import java.util.Arrays;

/**
 * Handles merging remote storage updates into local group v1 state.
 */
public final class GroupV1RecordProcessor extends DefaultStorageRecordProcessor<SignalGroupV1Record> {

  private static final String TAG = Log.tag(GroupV1RecordProcessor.class);

  private final GroupDatabase     groupDatabase;
  private final RecipientDatabase recipientDatabase;

  public GroupV1RecordProcessor(@NonNull Context context) {
    this(DatabaseFactory.getGroupDatabase(context), DatabaseFactory.getRecipientDatabase(context));
  }

  GroupV1RecordProcessor(@NonNull GroupDatabase groupDatabase, @NonNull RecipientDatabase recipientDatabase) {
    this.groupDatabase     = groupDatabase;
    this.recipientDatabase = recipientDatabase;
  }

  /**
   * We want to catch:
   * - Invalid group ID's
   * - GV1 ID's that map to GV2 ID's, meaning we've already migrated them.
   *
   * Note: This method could be written more succinctly, but the logs are useful :)
   */
  @Override
  boolean isInvalid(@NonNull SignalGroupV1Record remote) {
    try {
      GroupId.V1 id = GroupId.v1(remote.getGroupId());
      Optional<GroupDatabase.GroupRecord> v2Record = groupDatabase.getGroup(id.deriveV2MigrationGroupId());

      if (v2Record.isPresent()) {
        Log.w(TAG, "We already have an upgraded V2 group for this V1 group -- marking as invalid.");
        return true;
      } else {
        return false;
      }
    } catch (BadGroupIdException e) {
      Log.w(TAG, "Bad Group ID -- marking as invalid.");
      return true;
    }
  }

  @Override
  @NonNull Optional<SignalGroupV1Record> getMatching(@NonNull SignalGroupV1Record record, @NonNull StorageKeyGenerator keyGenerator) {
    GroupId.V1 groupId = GroupId.v1orThrow(record.getGroupId());

    Optional<RecipientId> recipientId = recipientDatabase.getByGroupId(groupId);

    return recipientId.transform(recipientDatabase::getRecipientSettingsForSync)
                      .transform(StorageSyncModels::localToRemoteRecord)
                      .transform(r -> r.getGroupV1().get());
  }

  @Override
  @NonNull SignalGroupV1Record merge(@NonNull SignalGroupV1Record remote, @NonNull SignalGroupV1Record local, @NonNull StorageKeyGenerator keyGenerator) {
    byte[]  unknownFields  = remote.serializeUnknownFields();
    boolean blocked        = remote.isBlocked();
    boolean profileSharing = remote.isProfileSharingEnabled();
    boolean archived       = remote.isArchived();
    boolean forcedUnread   = remote.isForcedUnread();
    long    muteUntil      = remote.getMuteUntil();

    boolean matchesRemote = doParamsMatch(remote, unknownFields, blocked, profileSharing, archived, forcedUnread, muteUntil);
    boolean matchesLocal  = doParamsMatch(local, unknownFields, blocked, profileSharing, archived, forcedUnread, muteUntil);

    if (matchesRemote) {
      return remote;
    } else if (matchesLocal) {
      return local;
    } else {
      return new SignalGroupV1Record.Builder(keyGenerator.generate(), remote.getGroupId())
                                    .setUnknownFields(unknownFields)
                                    .setBlocked(blocked)
                                    .setProfileSharingEnabled(blocked)
                                    .setForcedUnread(forcedUnread)
                                    .setMuteUntil(muteUntil)
                                    .build();
    }
  }

  @Override
  void insertLocal(@NonNull SignalGroupV1Record record) {
    Log.i(TAG, "Local GV1 insert");
    recipientDatabase.applyStorageSyncGroupV1Insert(record);
  }

  @Override
  void updateLocal(@NonNull StorageRecordUpdate<SignalGroupV1Record> update) {
    Log.i(TAG, "Local GV1 update: " + update.toString());
    recipientDatabase.applyStorageSyncGroupV1Update(update);
  }

  @Override
  public int compare(@NonNull SignalGroupV1Record lhs, @NonNull SignalGroupV1Record rhs) {
    if (Arrays.equals(lhs.getGroupId(), rhs.getGroupId())) {
      return 0;
    } else {
      return 1;
    }
  }

  private boolean doParamsMatch(@NonNull SignalGroupV1Record group,
                                @Nullable byte[] unknownFields,
                                boolean blocked,
                                boolean profileSharing,
                                boolean archived,
                                boolean forcedUnread,
                                long muteUntil)
  {
    return Arrays.equals(unknownFields, group.serializeUnknownFields()) &&
           blocked == group.isBlocked()                                 &&
           profileSharing == group.isProfileSharingEnabled()            &&
           archived == group.isArchived()                               &&
           forcedUnread == group.isForcedUnread()                       &&
           muteUntil == group.getMuteUntil();
  }
}
