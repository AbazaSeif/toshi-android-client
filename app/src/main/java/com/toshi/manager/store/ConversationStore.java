/*
 * 	Copyright (c) 2017. Toshi Inc
 *
 * 	This program is free software: you can redistribute it and/or modify
 *     it under the terms of the GNU General Public License as published by
 *     the Free Software Foundation, either version 3 of the License, or
 *     (at your option) any later version.
 *
 *     This program is distributed in the hope that it will be useful,
 *     but WITHOUT ANY WARRANTY; without even the implied warranty of
 *     MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *     GNU General Public License for more details.
 *
 *     You should have received a copy of the GNU General Public License
 *     along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */

package com.toshi.manager.store;


import android.support.annotation.NonNull;
import android.support.annotation.Nullable;

import com.toshi.model.local.Conversation;
import com.toshi.model.local.ConversationObservables;
import com.toshi.model.local.Group;
import com.toshi.model.local.LocalStatusMessage;
import com.toshi.model.local.Recipient;
import com.toshi.model.local.User;
import com.toshi.model.sofa.SofaAdapters;
import com.toshi.model.sofa.SofaMessage;
import com.toshi.util.LogUtil;
import com.toshi.view.BaseApplication;

import org.jetbrains.annotations.NotNull;

import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import io.realm.Realm;
import io.realm.RealmQuery;
import io.realm.RealmResults;
import io.realm.Sort;
import rx.Completable;
import rx.Observable;
import rx.Single;
import rx.schedulers.Schedulers;
import rx.subjects.PublishSubject;

public class ConversationStore {

    private static final int FIFTEEN_MINUTES = 1000 * 60 * 15;
    private static final String THREAD_ID_FIELD = "threadId";
    private static final String MESSAGE_ID_FIELD = "privateKey";

    private static String watchedThreadId;
    private final static PublishSubject<SofaMessage> NEW_MESSAGE_SUBJECT = PublishSubject.create();
    private final static PublishSubject<SofaMessage> UPDATED_MESSAGE_SUBJECT = PublishSubject.create();
    private final static PublishSubject<SofaMessage> DELETED_MESSAGE_SUBJECT = PublishSubject.create();
    private final static PublishSubject<Conversation> CONVERSATION_CHANGED_SUBJECT = PublishSubject.create();
    private final static PublishSubject<Conversation> CONVERSATION_UPDATED_SUBJECT = PublishSubject.create();
    private final static ExecutorService dbThread = Executors.newSingleThreadExecutor();

    public ConversationObservables registerForChanges(final String threadId) {
        watchedThreadId = threadId;
        return new ConversationObservables(NEW_MESSAGE_SUBJECT, UPDATED_MESSAGE_SUBJECT, CONVERSATION_UPDATED_SUBJECT);
    }

    public Observable<SofaMessage> registerForDeletedMessages(final String threadId) {
        watchedThreadId = threadId;
        return DELETED_MESSAGE_SUBJECT.asObservable();
    }

    public void stopListeningForChanges(final String threadId) {
        // Avoids the race condition where a second activity has already registered
        // before the first activity is destroyed. Thus the first activity can't deregister
        // changes for the second activity.
        if (watchedThreadId != null && watchedThreadId.equals(threadId)) {
            watchedThreadId = null;
        }
    }

    public Observable<Conversation> getConversationChangedObservable() {
        return CONVERSATION_CHANGED_SUBJECT
                .filter(thread -> thread != null);
    }

    public void saveGroup(@NonNull final Group group) {
        copyOrUpdateGroup(group)
                .observeOn(Schedulers.immediate())
                .subscribe(
                        this::handleGroupSaved,
                        this::handleError
                );
    }

    @NonNull
    private void handleGroupSaved(final Conversation conversation) {
        broadcastConversationChanged(conversation);
        broadcastConversationUpdated(conversation);
    }

    public Single<Conversation> createNewConversationFromGroup(@NonNull final Group group) {
        return createEmptyConversation(new Recipient(group))
                .flatMap(this::addGroupCreatedStatusMessage)
                .observeOn(Schedulers.immediate())
                .doOnSuccess(this::broadcastConversationChanged)
                .doOnError(this::handleError);
    }

    public Single<Conversation> saveNewMessageSingle(@NonNull final Recipient receiver,
                                                     @NonNull final SofaMessage message) {
        return saveMessage(receiver, message)
                .observeOn(Schedulers.immediate())
                .doOnSuccess(this::broadcastConversationChanged)
                .doOnError(throwable -> LogUtil.e(getClass(), "Error while saving message " + throwable));
    }

    public void saveNewMessage(
            @NonNull final Recipient receiver,
            @NonNull final SofaMessage message) {
        saveMessage(receiver, message)
        .observeOn(Schedulers.immediate())
        .subscribe(
                this::broadcastConversationChanged,
                this::handleError
        );
    }

    private Single<Conversation> copyOrUpdateGroup(@NonNull final Group group) {
        return Single.fromCallable(() -> {
            final Conversation conversationToStore = getOrCreateConversation(group);
            final Realm realm = BaseApplication.get().getRealm();
            realm.beginTransaction();
            conversationToStore.updateRecipient(new Recipient(group));
            final Conversation storedConversation = realm.copyToRealmOrUpdate(conversationToStore);
            realm.commitTransaction();
            final Conversation conversationForBroadcast = realm.copyFromRealm(storedConversation);
            realm.close();

            return conversationForBroadcast;
        })
        .subscribeOn(Schedulers.from(dbThread));
    }

    private Single<Conversation> addGroupCreatedStatusMessage(@NonNull final Conversation conversation) {
        final LocalStatusMessage localStatusMessage = new LocalStatusMessage(LocalStatusMessage.NEW_GROUP);
        final String localStatusMessageJson = SofaAdapters.get().toJson(localStatusMessage);
        final SofaMessage sofaMessage = new SofaMessage().makeNewLocalStatusMessage(localStatusMessageJson);
        return saveMessage(conversation.getRecipient(), sofaMessage);
    }

    private Single<Conversation> saveMessage(
            @NonNull final Recipient receiver,
            @Nullable final SofaMessage message) {
        return Single.fromCallable(() -> {
            final Conversation conversationToStore = getOrCreateConversation(receiver);

            if (message != null && shouldSaveTimestampMessage(message, conversationToStore)) {
                final SofaMessage timestampMessage =
                        generateTimestampMessage();
                conversationToStore.addMessage(timestampMessage);
                broadcastNewChatMessage(receiver.getThreadId(), timestampMessage);
            }

            final Realm realm = BaseApplication.get().getRealm();
            realm.beginTransaction();

            if (message != null) {
                final SofaMessage storedMessage = realm.copyToRealmOrUpdate(message);
                if(conversationToStore.getThreadId().equals(watchedThreadId)) {
                    conversationToStore.setLatestMessage(storedMessage);
                } else {
                    conversationToStore.setLatestMessageAndUpdateUnreadCounter(storedMessage);
                }
                broadcastNewChatMessage(receiver.getThreadId(), message);
            }

            final Conversation storedConversation = realm.copyToRealmOrUpdate(conversationToStore);
            realm.commitTransaction();
            final Conversation conversationForBroadcast = realm.copyFromRealm(storedConversation);
            realm.close();

            return conversationForBroadcast;
        })
        .subscribeOn(Schedulers.from(dbThread));
    }

    @NonNull
    private Conversation getOrCreateConversation(final Group group) {
        final Recipient recipient = new Recipient(group);
        return getOrCreateConversation(recipient);
    }

    @NonNull
    private Conversation getOrCreateConversation(final Recipient recipient) {
        final Conversation existingConversation = loadWhere(THREAD_ID_FIELD, recipient.getThreadId());
        return existingConversation == null
                ? new Conversation(recipient)
                : existingConversation;
    }

    private SofaMessage generateTimestampMessage() {
        return new SofaMessage().makeNewTimeStampMessage();
    }

    private boolean shouldSaveTimestampMessage(final SofaMessage message,
                                               final Conversation conversation) {
        if (!message.isUserVisible()) return false;
        final long newMessageTimestamp = message.getCreationTime();
        final long latestMessageTimestamp = conversation.getUpdatedTime();
        return newMessageTimestamp - latestMessageTimestamp > FIFTEEN_MINUTES;
    }

    public void resetUnreadMessageCounter(final String threadId) {
        Single.fromCallable(() -> {
            final Conversation storedConversation = loadWhere(THREAD_ID_FIELD, threadId);
            if (storedConversation == null) {
                return null;
            }

            final Realm realm = BaseApplication.get().getRealm();
            realm.beginTransaction();
            storedConversation.resetUnreadCounter();
            realm.insertOrUpdate(storedConversation);
            realm.commitTransaction();
            realm.close();
            return storedConversation;
        })
        .observeOn(Schedulers.immediate())
        .subscribeOn(Schedulers.from(dbThread))
        .subscribe(
                this::broadcastConversationChanged,
                this::handleError
        );
    }

    public Single<List<Conversation>> loadAllAcceptedConversation() {
        return loadAllConversations(true);
    }

    public Single<List<Conversation>> loadAllUnacceptedConversation() {
        return loadAllConversations(false);
    }

    private Single<List<Conversation>> loadAllConversations(final boolean isAccepted) {
        return Single.fromCallable(() -> {
            final Realm realm = BaseApplication.get().getRealm();
            final RealmQuery<Conversation> query =
                    realm.where(Conversation.class)
                            .equalTo("conversationStatus.isAccepted", isAccepted)
                            .isNotEmpty("allMessages");
            final RealmResults<Conversation> results = query.findAllSorted("updatedTime", Sort.DESCENDING);
            final List<Conversation> allConversations = realm.copyFromRealm(results);
            realm.close();
            return allConversations;
        })
        .subscribeOn(Schedulers.from(dbThread));
    }

    private void broadcastConversationChanged(final Conversation conversation) {
        CONVERSATION_CHANGED_SUBJECT.onNext(conversation);
    }

    public Single<Conversation> loadByThreadId(final String threadId) {
        return Single.fromCallable(
            () -> loadWhere(THREAD_ID_FIELD, threadId)
        ).subscribeOn(Schedulers.from(dbThread));
    }

    private Conversation loadWhere(final String fieldName, final String value) {
        final Realm realm = BaseApplication.get().getRealm();
        final Conversation result = realm
                .where(Conversation.class)
                .equalTo(fieldName, value)
                .findFirst();
        final Conversation queriedConversation = result == null ? null : realm.copyFromRealm(result);
        realm.close();
        return queriedConversation;
    }

    public void updateMessage(final Recipient receiver, final SofaMessage message) {
        Completable.fromAction(() -> {
            final Realm realm = BaseApplication.get().getRealm();
            realm.beginTransaction();
            realm.insertOrUpdate(message);
            realm.commitTransaction();
            realm.close();
        })
        .observeOn(Schedulers.immediate())
        .subscribeOn(Schedulers.from(dbThread))
        .subscribe(
                () -> broadcastUpdatedChatMessage(receiver.getThreadId(), message),
                this::handleError
        );
    }

    public Completable deleteByThreadId(final String threadId) {
        return Completable.fromAction(() -> {
            final Realm realm = BaseApplication.get().getRealm();
            realm.beginTransaction();
            realm
                    .where(Conversation.class)
                    .equalTo(THREAD_ID_FIELD, threadId)
                    .findFirst()
                    .deleteFromRealm();
            realm.commitTransaction();
            realm.close();
        })
        .subscribeOn(Schedulers.from(dbThread));
    }

    public Completable deleteMessageById(final Recipient receiver, final SofaMessage message) {
        return Completable.fromAction(() -> {
            final Realm realm = BaseApplication.get().getRealm();
            realm.beginTransaction();
            realm
                    .where(SofaMessage.class)
                    .equalTo(MESSAGE_ID_FIELD, message.getPrivateKey())
                    .findFirst()
                    .deleteFromRealm();
            realm.commitTransaction();
            realm.close();
        })
        .observeOn(Schedulers.immediate())
        .subscribeOn(Schedulers.from(dbThread))
        .andThen(updateLatestMessage(receiver.getThreadId()))
        .doOnCompleted(() -> broadcastDeletedChatMessage(receiver.getThreadId(), message))
        .doOnError(this::handleError);
    }

    private Completable updateLatestMessage(final String threadId) {
        return Completable.fromAction(() -> {
            final Realm realm = BaseApplication.get().getRealm();
            final Conversation conversation = realm
                    .where(Conversation.class)
                    .equalTo(THREAD_ID_FIELD, threadId)
                    .findFirst();
            if (conversation == null) {
                realm.close();
                return;
            }
            if (conversation.getAllMessages() != null && conversation.getAllMessages().size() > 0) {
                final SofaMessage lastMessage = conversation.getAllMessages().get(conversation.getAllMessages().size() - 1);
                realm.beginTransaction();
                conversation.updateLatestMessage(lastMessage);
                realm.commitTransaction();
            }
            realm.close();
        })
        .subscribeOn(Schedulers.from(dbThread));
    }

    public void removeUserFromGroup(@NotNull User user, @NotNull String groupId) {
        loadByThreadId(groupId)
                .flatMap(conversation -> addUserLeftStatusMessage(conversation, user))
                .map(conversation -> conversation.getRecipient().getGroup())
                .map(group -> group.removeMember(user))
                .flatMap(this::copyOrUpdateGroup)
                .subscribe(
                        this::broadcastConversationUpdated,
                        this::handleError
                );
    }

    private Single<Conversation> addUserLeftStatusMessage(@NonNull final Conversation conversation, @NonNull User sender) {
        final LocalStatusMessage localStatusMessage = new LocalStatusMessage(LocalStatusMessage.USER_LEFT, sender);
        final String localStatusMessageJson = SofaAdapters.get().toJson(localStatusMessage);
        final SofaMessage sofaMessage = new SofaMessage().makeNewLocalStatusMessage(localStatusMessageJson);
        return saveMessage(conversation.getRecipient(), sofaMessage);
    }

    public Single<Conversation> addNewGroupParticipantsStatusMessage(@NonNull final String groupId,
                                                     @NonNull final User sender,
                                                     final List<User> newUsers) {
        return loadByThreadId(groupId)
                .flatMap(conversation -> addNewGroupParticipantsStatusMessage(conversation, sender, newUsers))
                .doOnSuccess(this::broadcastConversationUpdated)
                .doOnError(throwable -> LogUtil.e(getClass(), "Error while creating new status message " + throwable));
    }

    private Single<Conversation> addNewGroupParticipantsStatusMessage(final Conversation conversation,
                                                                      final User sender,
                                                                      final List<User> newUsers) {
        final SofaMessage statusMessage = generateAddStatusMessage(sender, newUsers);
        if (statusMessage == null) return Single.just(conversation);
        return saveMessage(conversation.getRecipient(), statusMessage);
    }

    private SofaMessage generateAddStatusMessage(final User sender, final List<User> newUsers) {
        if (newUsers.isEmpty()) return null;
        final LocalStatusMessage localStatusMessage = new LocalStatusMessage(LocalStatusMessage.USER_ADDED, sender, newUsers);
        final String localStatusMessageJson = SofaAdapters.get().toJson(localStatusMessage);
        return new SofaMessage().makeNewLocalStatusMessage(localStatusMessageJson);
    }

    public boolean areUnreadMessages() {
        final Realm realm = BaseApplication.get().getRealm();
        final Conversation result = realm
                .where(Conversation.class)
                .greaterThan("numberOfUnread", 0)
                .findFirst();
        final boolean areUnreadMessages = result != null;
        realm.close();
        return areUnreadMessages;
    }

    public Single<SofaMessage> getSofaMessageById(final String id) {
        return Single.fromCallable(() -> {
            final Realm realm = BaseApplication.get().getRealm();
            final SofaMessage result =
                    realm
                            .where(SofaMessage.class)
                            .equalTo("privateKey", id)
                            .findFirst();
            final SofaMessage sofaMessage = realm.copyFromRealm(result);
            realm.close();
            return sofaMessage;
        })
        .subscribeOn(Schedulers.from(dbThread));
    }

    public Single<Conversation> createEmptyConversation(final Recipient recipient) {
        return Single.fromCallable(() -> {
            final Realm realm = BaseApplication.get().getRealm();
            realm.beginTransaction();
            final Conversation conversation = new Conversation(recipient);
            conversation.getConversationStatus().setAccepted(true);
            realm.copyToRealmOrUpdate(conversation);
            realm.commitTransaction();
            realm.close();
            return conversation;
        })
        .subscribeOn(Schedulers.from(dbThread));
    }

    public Single<Conversation> muteConversation(final Conversation conversation, final boolean mute) {
        return Single.fromCallable(() -> {
            final Realm realm = BaseApplication.get().getRealm();
            realm.beginTransaction();
            conversation.getConversationStatus().setMuted(mute);
            realm.copyToRealmOrUpdate(conversation);
            realm.commitTransaction();
            realm.close();
            return conversation;
        })
        .subscribeOn(Schedulers.from(dbThread));
    }

    public Single<Conversation> acceptConversation(final Conversation conversation) {
        return Single.fromCallable(() -> {
            final Realm realm = BaseApplication.get().getRealm();
            realm.beginTransaction();
            conversation.getConversationStatus().setAccepted(true);
            realm.copyToRealmOrUpdate(conversation);
            realm.commitTransaction();
            realm.close();
            return conversation;
        })
        .subscribeOn(Schedulers.from(dbThread));
    }

    private void broadcastNewChatMessage(final String threadId, final SofaMessage newMessage) {
        if (watchedThreadId == null || !watchedThreadId.equals(threadId)) {
            return;
        }
        NEW_MESSAGE_SUBJECT.onNext(newMessage);
    }

    private void broadcastUpdatedChatMessage(final String threadId, final SofaMessage updatedMessage) {
        if (watchedThreadId == null || !watchedThreadId.equals(threadId)) {
            return;
        }
        UPDATED_MESSAGE_SUBJECT.onNext(updatedMessage);
    }

    private void broadcastDeletedChatMessage(final String threadId, final SofaMessage deletedMessage) {
        if (watchedThreadId == null || !watchedThreadId.equals(threadId)) {
            return;
        }
        DELETED_MESSAGE_SUBJECT.onNext(deletedMessage);
    }

    private void broadcastConversationUpdated(final Conversation conversation) {
        if (watchedThreadId == null || !watchedThreadId.equals(conversation.getThreadId())) {
            return;
        }
        CONVERSATION_UPDATED_SUBJECT.onNext(conversation);
    }

    private void handleError(final Throwable throwable) {
        LogUtil.exception(getClass(), throwable);
    }
}
