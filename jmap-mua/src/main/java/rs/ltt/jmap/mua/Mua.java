/*
 * Copyright 2019 Daniel Gultsch
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 */

package rs.ltt.jmap.mua;

import com.google.common.base.Function;
import com.google.common.base.Optional;
import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.util.concurrent.*;
import org.checkerframework.checker.nullness.compatqual.NonNullDecl;
import org.checkerframework.checker.nullness.compatqual.NullableDecl;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import rs.ltt.jmap.client.JmapClient;
import rs.ltt.jmap.client.MethodResponses;
import rs.ltt.jmap.client.session.SessionCache;
import rs.ltt.jmap.client.session.SessionFileCache;
import rs.ltt.jmap.common.Request;
import rs.ltt.jmap.common.entity.Thread;
import rs.ltt.jmap.common.entity.*;
import rs.ltt.jmap.common.entity.filter.Filter;
import rs.ltt.jmap.common.method.call.email.GetEmailMethodCall;
import rs.ltt.jmap.common.method.call.email.QueryChangesEmailMethodCall;
import rs.ltt.jmap.common.method.call.email.QueryEmailMethodCall;
import rs.ltt.jmap.common.method.call.email.SetEmailMethodCall;
import rs.ltt.jmap.common.method.call.identity.GetIdentityMethodCall;
import rs.ltt.jmap.common.method.call.mailbox.GetMailboxMethodCall;
import rs.ltt.jmap.common.method.call.submission.SetEmailSubmissionMethodCall;
import rs.ltt.jmap.common.method.call.thread.GetThreadMethodCall;
import rs.ltt.jmap.common.method.response.email.*;
import rs.ltt.jmap.common.method.response.identity.ChangesIdentityMethodResponse;
import rs.ltt.jmap.common.method.response.identity.GetIdentityMethodResponse;
import rs.ltt.jmap.common.method.response.mailbox.ChangesMailboxMethodResponse;
import rs.ltt.jmap.common.method.response.mailbox.GetMailboxMethodResponse;
import rs.ltt.jmap.common.method.response.mailbox.SetMailboxMethodResponse;
import rs.ltt.jmap.common.method.response.submission.SetEmailSubmissionMethodResponse;
import rs.ltt.jmap.common.method.response.thread.ChangesThreadMethodResponse;
import rs.ltt.jmap.common.method.response.thread.GetThreadMethodResponse;
import rs.ltt.jmap.common.util.Patches;
import rs.ltt.jmap.mua.cache.*;
import rs.ltt.jmap.mua.entity.QueryResultItem;
import rs.ltt.jmap.mua.util.CreateUtil;
import rs.ltt.jmap.mua.util.MailboxUtils;
import rs.ltt.jmap.mua.util.QueryResult;
import rs.ltt.jmap.mua.util.UpdateUtil;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;

public class Mua {

    private static final Logger LOGGER = LoggerFactory.getLogger(Mua.class);
    private final JmapClient jmapClient;
    private final Cache cache;
    private Integer queryPageSize = null;
    private ListeningExecutorService ioExecutorService = MoreExecutors.listeningDecorator(Executors.newSingleThreadExecutor());

    private Mua(JmapClient jmapClient, Cache cache) {
        this.jmapClient = jmapClient;
        this.cache = cache;
    }

    public static Builder builder() {
        return new Builder();
    }

    public JmapClient getJmapClient() {
        return jmapClient;
    }

    public void shutdown() {
        ioExecutorService.shutdown();
        jmapClient.getExecutorService().shutdown();
    }

    public ListenableFuture<Status> refreshIdentities() {
        final ListenableFuture<String> identityStateFuture = ioExecutorService.submit(new Callable<String>() {
            @Override
            public String call() throws Exception {
                return cache.getIdentityState();
            }
        });
        return Futures.transformAsync(identityStateFuture, new AsyncFunction<String, Status>() {
            @Override
            public ListenableFuture<Status> apply(@NullableDecl final String state) throws Exception {
                if (state == null) {
                    return loadIdentities();
                } else {
                    return updateIdentities(state);
                }
            }
        }, MoreExecutors.directExecutor());
    }

    private ListenableFuture<Status> loadIdentities() {
        JmapClient.MultiCall multiCall = jmapClient.newMultiCall();
        ListenableFuture<Status> future = loadIdentities(multiCall);
        multiCall.execute();
        return future;
    }

    private ListenableFuture<Status> updateIdentities(final String state) {
        JmapClient.MultiCall multiCall = jmapClient.newMultiCall();
        ListenableFuture<Status> future = updateIdentities(state, multiCall);
        multiCall.execute();
        return future;
    }

    private ListenableFuture<Status> loadIdentities(JmapClient.MultiCall multiCall) {
        final SettableFuture<Status> settableFuture = SettableFuture.create();
        final ListenableFuture<MethodResponses> responseFuture = multiCall.call(new GetIdentityMethodCall());
        responseFuture.addListener(new Runnable() {
            @Override
            public void run() {
                try {
                    final GetIdentityMethodResponse response = responseFuture.get().getMain(GetIdentityMethodResponse.class);
                    final Identity[] identities = response.getList();
                    cache.setIdentities(response.getTypedState(), identities);
                    settableFuture.set(Status.of(identities.length > 0));
                } catch (Exception e) {
                    settableFuture.setException(extractException(e));
                }
            }
        }, ioExecutorService);
        return settableFuture;
    }

    private ListenableFuture<Status> updateIdentities(final String state, JmapClient.MultiCall multiCall) {
        Preconditions.checkNotNull(state, "State can not be null when updating identities");
        final SettableFuture<Status> settableFuture = SettableFuture.create();
        final UpdateUtil.MethodResponsesFuture methodResponsesFuture = UpdateUtil.identities(multiCall, state);
        methodResponsesFuture.changes.addListener(new Runnable() {
            @Override
            public void run() {
                try {
                    ChangesIdentityMethodResponse changesResponse = methodResponsesFuture.changes.get().getMain(ChangesIdentityMethodResponse.class);
                    GetIdentityMethodResponse createdResponse = methodResponsesFuture.created.get().getMain(GetIdentityMethodResponse.class);
                    GetIdentityMethodResponse updatedResponse = methodResponsesFuture.updated.get().getMain(GetIdentityMethodResponse.class);
                    final Update<Identity> update = Update.of(changesResponse, createdResponse, updatedResponse);
                    if (update.hasChanges()) {
                        cache.updateIdentities(update);
                    }
                    settableFuture.set(Status.of(update));
                } catch (Exception e) {
                    settableFuture.setException(extractException(e));
                }
            }
        }, ioExecutorService);
        return settableFuture;
    }

    private static Throwable extractException(final Exception exception) {
        if (exception instanceof ExecutionException) {
            final Throwable cause = exception.getCause();
            if (cause != null) {
                return cause;
            }
        }
        return exception;
    }

    public ListenableFuture<Status> refreshMailboxes() {
        final ListenableFuture<String> mailboxStateFuture = ioExecutorService.submit(new Callable<String>() {
            @Override
            public String call() {
                return cache.getMailboxState();
            }
        });

        return Futures.transformAsync(mailboxStateFuture, new AsyncFunction<String, Status>() {
            @Override
            public ListenableFuture<Status> apply(@NullableDecl final String state) throws Exception {
                if (state == null) {
                    return loadMailboxes();
                } else {
                    return updateMailboxes(state);
                }
            }
        }, MoreExecutors.directExecutor());
    }

    private ListenableFuture<Status> loadMailboxes() {
        final JmapClient.MultiCall multiCall = jmapClient.newMultiCall();
        final ListenableFuture<Status> future = loadMailboxes(multiCall);
        multiCall.execute();
        return future;
    }

    private ListenableFuture<Status> updateMailboxes(final String state) {
        final JmapClient.MultiCall multiCall = jmapClient.newMultiCall();
        final ListenableFuture<Status> future = updateMailboxes(state, multiCall);
        multiCall.execute();
        return future;
    }

    private ListenableFuture<Status> loadMailboxes(JmapClient.MultiCall multiCall) {
        final SettableFuture<Status> settableFuture = SettableFuture.create();
        final ListenableFuture<MethodResponses> getMailboxMethodResponsesFuture = multiCall.call(new GetMailboxMethodCall());
        getMailboxMethodResponsesFuture.addListener(new Runnable() {
            @Override
            public void run() {
                try {
                    GetMailboxMethodResponse response = getMailboxMethodResponsesFuture.get().getMain(GetMailboxMethodResponse.class);
                    Mailbox[] mailboxes = response.getList();
                    cache.setMailboxes(response.getTypedState(), mailboxes);
                    settableFuture.set(Status.of(mailboxes.length > 0));
                } catch (InterruptedException | ExecutionException | CacheWriteException e) {
                    settableFuture.setException(extractException(e));
                }

            }
        }, ioExecutorService);
        return settableFuture;
    }

    private ListenableFuture<Status> updateMailboxes(final String state, final JmapClient.MultiCall multiCall) {
        Preconditions.checkNotNull(state, "State can not be null when updating mailboxes");
        final SettableFuture<Status> settableFuture = SettableFuture.create();
        final UpdateUtil.MethodResponsesFuture methodResponsesFuture = UpdateUtil.mailboxes(multiCall, state);
        methodResponsesFuture.changes.addListener(new Runnable() {
            @Override
            public void run() {
                try {
                    final ChangesMailboxMethodResponse changesResponse = methodResponsesFuture.changes.get().getMain(ChangesMailboxMethodResponse.class);
                    final GetMailboxMethodResponse createdResponse = methodResponsesFuture.created.get().getMain(GetMailboxMethodResponse.class);
                    final GetMailboxMethodResponse updatedResponse = methodResponsesFuture.updated.get().getMain(GetMailboxMethodResponse.class);
                    final Update<Mailbox> update = Update.of(changesResponse, createdResponse, updatedResponse);
                    if (update.hasChanges()) {
                        cache.updateMailboxes(update, changesResponse.getUpdatedProperties());
                    }
                    settableFuture.set(Status.of(update));
                } catch (InterruptedException | ExecutionException | CacheWriteException | CacheConflictException e) {
                    settableFuture.setException(extractException(e));
                }
            }
        }, ioExecutorService);
        return settableFuture;
    }

    /**
     * Stores an email as a draft. This method will take care of adding the draft and seen keyword and moving the email
     * to the draft mailbox.
     *
     * @param email The email that should be saved as a draft
     * @return
     */
    public ListenableFuture<Boolean> draft(final Email email) {
        return Futures.transformAsync(getMailboxes(), new AsyncFunction<Collection<? extends IdentifiableMailboxWithRole>, Boolean>() {
            @Override
            public ListenableFuture<Boolean> apply(@NullableDecl Collection<? extends IdentifiableMailboxWithRole> mailboxes) {
                return draft(email, MailboxUtils.find(mailboxes, Role.DRAFTS));
            }
        }, MoreExecutors.directExecutor());
    }

    private ListenableFuture<Collection<? extends IdentifiableMailboxWithRole>> getMailboxes() {
        return ioExecutorService.submit(new Callable<Collection<? extends IdentifiableMailboxWithRole>>() {
            @Override
            public Collection<? extends IdentifiableMailboxWithRole> call() throws Exception {
                return cache.getSpecialMailboxes();
            }
        });
    }

    /**
     * Stores an email as a draft. This method will take care of adding the draft and seen keyword and moving the email
     * to the draft mailbox.
     *
     * @param email  The email that should be saved as a draft
     * @param drafts A reference to the Drafts mailbox. Can be null and a new Draft mailbox will automatically be created.
     *               Do not pass null if a Drafts mailbox exists on the server as this call will attempt to create one
     *               and fail.
     * @return
     */
    public ListenableFuture<Boolean> draft(final Email email, final IdentifiableMailboxWithRole drafts) {
        final JmapClient.MultiCall multiCall = jmapClient.newMultiCall();
        final ListenableFuture<Boolean> future = draft(email, drafts, multiCall);
        multiCall.execute();
        return future;
    }

    private ListenableFuture<Boolean> draft(final Email email, final IdentifiableMailboxWithRole drafts, final JmapClient.MultiCall multiCall) {
        Preconditions.checkNotNull(email, "Email can not be null when attempting to create a draft");
        Preconditions.checkState(email.getId() == null, "id is a server-set property");
        Preconditions.checkState(email.getBlobId() == null, "blobId is a server-set property");
        Preconditions.checkState(email.getThreadId() == null, "threadId is a server-set property");
        final Email.EmailBuilder emailBuilder = email.toBuilder();
        final Optional<ListenableFuture<MethodResponses>> mailboxCreateFutureOptional = CreateUtil.mailbox(multiCall, drafts, Role.DRAFTS);
        if (drafts == null) {
            emailBuilder.mailboxId(CreateUtil.createIdReference(Role.DRAFTS), true);
        } else if (!email.getMailboxIds().containsKey(drafts.getId())) {
            emailBuilder.mailboxId(drafts.getId(), true);
        }
        if (!email.getKeywords().containsKey(Keyword.DRAFT)) {
            emailBuilder.keyword(Keyword.DRAFT, true);
        }
        if (!email.getKeywords().containsKey(Keyword.SEEN)) {
            emailBuilder.keyword(Keyword.SEEN, true);
        }
        final ListenableFuture<MethodResponses> future = multiCall.call(new SetEmailMethodCall(ImmutableMap.of("e0", emailBuilder.build())));
        return Futures.transformAsync(future, new AsyncFunction<MethodResponses, Boolean>() {
            @Override
            public ListenableFuture<Boolean> apply(@NullableDecl MethodResponses methodResponses) throws Exception {
                if (mailboxCreateFutureOptional.isPresent()) {
                    SetMailboxMethodResponse setMailboxResponse = mailboxCreateFutureOptional.get().get().getMain(SetMailboxMethodResponse.class);
                    SetMailboxException.throwIfFailed(setMailboxResponse);
                }
                SetEmailMethodResponse setEmailMethodResponse = methodResponses.getMain(SetEmailMethodResponse.class);
                SetEmailException.throwIfFailed(setEmailMethodResponse);
                return Futures.immediateFuture(setEmailMethodResponse.getUpdatedCreatedCount() > 0);
            }
        }, MoreExecutors.directExecutor());

    }

    public ListenableFuture<Boolean> submit(final Email email, final Identity identity) {
        return Futures.transformAsync(getMailboxes(), new AsyncFunction<Collection<? extends IdentifiableMailboxWithRole>, Boolean>() {
            @Override
            public ListenableFuture<Boolean> apply(@NullableDecl Collection<? extends IdentifiableMailboxWithRole> mailboxes) {
                Preconditions.checkNotNull(mailboxes, "SpecialMailboxes collection must not be null but can be empty");
                final IdentifiableMailboxWithRole drafts = MailboxUtils.find(mailboxes, Role.DRAFTS);
                final String draftMailboxId;
                if (drafts == null || !email.getMailboxIds().containsKey(drafts.getId())) {
                    draftMailboxId = null;
                } else {
                    draftMailboxId = drafts.getId();
                }
                final IdentifiableMailboxWithRole sent = MailboxUtils.find(mailboxes, Role.SENT);
                return submit(email.getId(), identity, draftMailboxId, sent);
            }
        }, MoreExecutors.directExecutor());
    }

    /**
     * Submits (sends / EmailSubmission) a previously drafted email. The email will be removed from the Drafts mailbox
     * and put into the Sent mailbox after successful submission. Additionally the draft keyword will be removed.
     *
     * @param emailId        The id of the email that should be submitted
     * @param identity       The identity used to submit that email
     * @param draftMailboxId The id of the draft mailbox. After successful submission the email will be removed from
     *                       this mailbox. Can be null to skip this operation and not remove the email from that mailbox.
     *                       If not null the caller should ensure that the id belongs to the draft mailbox and the email
     *                       is in that mailbox.
     * @param sent           A reference to the Sent mailbox. Can be null and a new sent mailbox will automatically be created.
     *                       Do not pass null if a Sent mailbox exists on the server as this call will attempt to create one and
     *                       fail.
     * @return
     */
    public ListenableFuture<Boolean> submit(final String emailId, final Identity identity, @NullableDecl String draftMailboxId, final IdentifiableMailboxWithRole sent) {
        final JmapClient.MultiCall multiCall = jmapClient.newMultiCall();
        final ListenableFuture<Boolean> future = submit(emailId, identity, draftMailboxId, sent, multiCall);
        multiCall.execute();
        return future;
    }

    private ListenableFuture<Boolean> submit(@NonNullDecl final String emailId, @NonNullDecl final Identity identity, @NullableDecl String draftMailboxId, @NullableDecl final IdentifiableMailboxWithRole sent, final JmapClient.MultiCall multiCall) {
        Preconditions.checkNotNull(emailId, "emailId can not be null when attempting to submit");
        Preconditions.checkNotNull(identity, "identity can not be null when attempting to submit an email");
        final Optional<ListenableFuture<MethodResponses>> mailboxCreateFutureOptional = CreateUtil.mailbox(multiCall, sent, Role.SENT);
        final Patches.Builder patchesBuilder = Patches.builder();
        patchesBuilder.remove("keywords/" + Keyword.DRAFT);
        patchesBuilder.set("mailboxIds/" + (sent == null ? CreateUtil.createIdReference(Role.SENT) : sent.getId()), true);
        if (draftMailboxId != null) {
            patchesBuilder.remove("mailboxIds/" + draftMailboxId);
        }
        final ListenableFuture<MethodResponses> setEmailSubmissionFuture = multiCall.call(new SetEmailSubmissionMethodCall(
                ImmutableMap.of(
                        "es0",
                        EmailSubmission.builder()
                                .emailId(emailId)
                                .identityId(identity.getId())
                                .build()
                ),
                ImmutableMap.of(
                        "#es0",
                        patchesBuilder.build()
                )
        ));
        return Futures.transformAsync(setEmailSubmissionFuture, new AsyncFunction<MethodResponses, Boolean>() {
            @Override
            public ListenableFuture<Boolean> apply(@NullableDecl MethodResponses methodResponses) throws Exception {
                if (mailboxCreateFutureOptional.isPresent()) {
                    SetMailboxMethodResponse setMailboxResponse = mailboxCreateFutureOptional.get().get().getMain(SetMailboxMethodResponse.class);
                    SetMailboxException.throwIfFailed(setMailboxResponse);
                }
                SetEmailSubmissionMethodResponse setEmailSubmissionMethodResponse = methodResponses.getMain(SetEmailSubmissionMethodResponse.class);
                SetEmailSubmissionException.throwIfFailed(setEmailSubmissionMethodResponse);
                return Futures.immediateFuture(setEmailSubmissionMethodResponse.getUpdatedCreatedCount() > 0);
            }
        }, MoreExecutors.directExecutor());

    }

    /**
     * Submits (sends / EmailSubmission) a previously drafted email. The email will be removed from the Drafts mailbox
     * and put into the Sent mailbox after successful submission. Additionally the draft keyword will be removed.
     *
     * @param emailId  The id of the email that should be submitted
     * @param identity The identity used to submit that email
     * @return
     */
    public ListenableFuture<Boolean> submit(final String emailId, final Identity identity) {
        return Futures.transformAsync(getMailboxes(), new AsyncFunction<Collection<? extends IdentifiableMailboxWithRole>, Boolean>() {
            @Override
            public ListenableFuture<Boolean> apply(@NullableDecl Collection<? extends IdentifiableMailboxWithRole> mailboxes) {
                Preconditions.checkNotNull(mailboxes, "SpecialMailboxes collection must not be null but can be empty");
                final IdentifiableMailboxWithRole drafts = MailboxUtils.find(mailboxes, Role.DRAFTS);
                final IdentifiableMailboxWithRole sent = MailboxUtils.find(mailboxes, Role.SENT);
                return submit(emailId, identity, drafts == null ? null : drafts.getId(), sent);
            }
        }, MoreExecutors.directExecutor());
    }

    public ListenableFuture<Boolean> send(final Email email, final Identity identity) {
        return Futures.transformAsync(getMailboxes(), new AsyncFunction<Collection<? extends IdentifiableMailboxWithRole>, Boolean>() {
            @Override
            public ListenableFuture<Boolean> apply(@NullableDecl Collection<? extends IdentifiableMailboxWithRole> mailboxes) {
                Preconditions.checkNotNull(mailboxes, "SpecialMailboxes collection must not be null but can be empty");
                final IdentifiableMailboxWithRole draft = MailboxUtils.find(mailboxes, Role.DRAFTS);
                final IdentifiableMailboxWithRole sent = MailboxUtils.find(mailboxes, Role.SENT);
                return send(email, identity, draft, sent);
            }
        }, MoreExecutors.directExecutor());
    }

    public ListenableFuture<Boolean> send(final Email email, final Identity identity, final IdentifiableMailboxWithRole drafts, final IdentifiableMailboxWithRole sent) {
        final JmapClient.MultiCall multiCall = jmapClient.newMultiCall();
        ListenableFuture<List<Boolean>> future = Futures.allAsList(
                draft(email, drafts, multiCall),
                submit("#e0", identity, drafts == null ? CreateUtil.createIdReference(Role.DRAFTS) : drafts.getId(), sent, multiCall)
        );
        multiCall.execute();
        return Futures.transform(future, new Function<List<Boolean>, Boolean>() {
            @NullableDecl
            @Override
            public Boolean apply(@NullableDecl List<Boolean> booleans) {
                return booleans != null && !booleans.contains(false);
            }
        }, MoreExecutors.directExecutor());
    }

    public ListenableFuture<Boolean> setKeyword(final Collection<? extends IdentifiableEmailWithKeywords> emails, final String keyword) {
        return Futures.transformAsync(getObjectsState(), new AsyncFunction<ObjectsState, Boolean>() {
            @Override
            public ListenableFuture<Boolean> apply(@NullableDecl ObjectsState objectsState) throws Exception {
                return setKeyword(emails, keyword, objectsState);
            }
        }, MoreExecutors.directExecutor());
    }

    private ListenableFuture<ObjectsState> getObjectsState() {
        return ioExecutorService.submit(new Callable<ObjectsState>() {
            @Override
            public ObjectsState call() {
                return cache.getObjectsState();
            }
        });
    }

    private ListenableFuture<Boolean> setKeyword(Collection<? extends IdentifiableEmailWithKeywords> emails, String keyword, ObjectsState objectsState) {
        final ImmutableMap.Builder<String, Map<String, Object>> emailPatchObjectMapBuilder = ImmutableMap.builder();
        for (IdentifiableEmailWithKeywords email : emails) {
            if (!email.getKeywords().containsKey(keyword)) {
                emailPatchObjectMapBuilder.put(email.getId(), Patches.set("keywords/" + keyword, true));
            }
        }
        final ImmutableMap<String, Map<String, Object>> patches = emailPatchObjectMapBuilder.build();
        LOGGER.info(String.format("set keyword(%s) on %d emails", keyword, patches.size()));
        if (patches.size() == 0) {
            return Futures.immediateFuture(false);
        }
        JmapClient.MultiCall multiCall = jmapClient.newMultiCall();
        ListenableFuture<MethodResponses> future = multiCall.call(new SetEmailMethodCall(objectsState.emailState, patches));
        if (objectsState.emailState != null) {
            updateEmails(objectsState.emailState, multiCall);
        }
        multiCall.execute();
        return Futures.transformAsync(future, new AsyncFunction<MethodResponses, Boolean>() {
            @Override
            public ListenableFuture<Boolean> apply(@NullableDecl MethodResponses methodResponses) throws Exception {
                SetEmailMethodResponse setEmailMethodResponse = methodResponses.getMain(SetEmailMethodResponse.class);
                SetEmailException.throwIfFailed(setEmailMethodResponse);
                return Futures.immediateFuture(setEmailMethodResponse.getUpdatedCreatedCount() > 0);
            }
        }, ioExecutorService);
    }

    private ListenableFuture<Status> updateEmails(final String state, final JmapClient.MultiCall multiCall) {
        Preconditions.checkNotNull(state, "state can not be null when updating emails");
        final SettableFuture<Status> settableFuture = SettableFuture.create();
        final UpdateUtil.MethodResponsesFuture methodResponsesFuture = UpdateUtil.emails(multiCall, state);
        methodResponsesFuture.changes.addListener(new Runnable() {
            @Override
            public void run() {
                try {
                    final ChangesEmailMethodResponse changesResponse = methodResponsesFuture.changes.get().getMain(ChangesEmailMethodResponse.class);
                    final GetEmailMethodResponse createdResponse = methodResponsesFuture.created.get().getMain(GetEmailMethodResponse.class);
                    final GetEmailMethodResponse updatedResponse = methodResponsesFuture.updated.get().getMain(GetEmailMethodResponse.class);
                    final Update<Email> update = Update.of(changesResponse, createdResponse, updatedResponse);
                    if (update.hasChanges()) {
                        cache.updateEmails(update, Email.MUTABLE_PROPERTIES);
                    }
                    settableFuture.set(Status.of(update));
                } catch (InterruptedException | ExecutionException | CacheWriteException | CacheConflictException e) {
                    settableFuture.setException(extractException(e));
                }
            }
        }, ioExecutorService);
        return settableFuture;
    }

    public ListenableFuture<Boolean> removeKeyword(final Collection<? extends IdentifiableEmailWithKeywords> emails, final String keyword) {
        return Futures.transformAsync(getObjectsState(), new AsyncFunction<ObjectsState, Boolean>() {
            @Override
            public ListenableFuture<Boolean> apply(@NullableDecl ObjectsState objectsState) {
                return removeKeyword(emails, keyword, objectsState);
            }
        }, MoreExecutors.directExecutor());
    }

    private ListenableFuture<Boolean> removeKeyword(final Collection<? extends IdentifiableEmailWithKeywords> emails, final String keyword, final ObjectsState objectsState) {
        final ImmutableMap.Builder<String, Map<String, Object>> emailPatchObjectMapBuilder = ImmutableMap.builder();
        for (IdentifiableEmailWithKeywords email : emails) {
            if (email.getKeywords().containsKey(keyword)) {
                emailPatchObjectMapBuilder.put(email.getId(), Patches.remove("keywords/" + keyword));
            }
        }
        final ImmutableMap<String, Map<String, Object>> patches = emailPatchObjectMapBuilder.build();
        LOGGER.info(String.format("remove keyword(%s) from %d emails", keyword, patches.size()));
        if (patches.size() == 0) {
            return Futures.immediateFuture(false);
        }
        JmapClient.MultiCall multiCall = jmapClient.newMultiCall();
        ListenableFuture<MethodResponses> future = multiCall.call(new SetEmailMethodCall(objectsState.emailState, patches));
        if (objectsState.emailState != null) {
            updateEmails(objectsState.emailState, multiCall);
        }
        multiCall.execute();
        return Futures.transformAsync(future, new AsyncFunction<MethodResponses, Boolean>() {
            @Override
            public ListenableFuture<Boolean> apply(@NullableDecl MethodResponses methodResponses) throws Exception {
                SetEmailMethodResponse setEmailMethodResponse = methodResponses.getMain(SetEmailMethodResponse.class);
                SetEmailException.throwIfFailed(setEmailMethodResponse);
                return Futures.immediateFuture(setEmailMethodResponse.getUpdatedCreatedCount() > 0);
            }
        }, ioExecutorService);
    }

    /**
     * Removes the individual emails in this collection (usually applied to an entire thread) from a given mailbox. If a
     * certain email was not in this mailbox it will be skipped. If removing an email from this mailbox would otherwise
     * lead to the email having no mailbox it will be moved to the Archive mailbox
     *
     * @param emails  A collection of emails. Usually all messages in a thread
     * @param mailbox The mailbox from which those emails should be removed
     * @param archive A reference to the Archive mailbox. Can be null and a new Archive mailbox will automatically be
     *                created.
     *                Do not pass null if an Archive mailbox exists on the server as this call will attempt to create
     *                one and fail.
     */
    public ListenableFuture<Boolean> removeFromMailbox(Collection<? extends IdentifiableEmailWithMailboxIds> emails, @NonNullDecl Mailbox mailbox, @NullableDecl final IdentifiableMailboxWithRole archive) {
        Preconditions.checkNotNull(mailbox, "Mailbox can not be null when attempting to remove it from a collection of emails");
        return removeFromMailbox(emails, mailbox.getId(), archive);
    }

    private ListenableFuture<Boolean> removeFromMailbox(final Collection<? extends IdentifiableEmailWithMailboxIds> emails, final String mailboxId, @NullableDecl final IdentifiableMailboxWithRole archive) {
        return Futures.transformAsync(getObjectsState(), new AsyncFunction<ObjectsState, Boolean>() {
            @Override
            public ListenableFuture<Boolean> apply(@NullableDecl ObjectsState objectsState) throws Exception {
                return removeFromMailbox(emails, mailboxId, archive, objectsState);
            }
        }, MoreExecutors.directExecutor());
    }

    private ListenableFuture<Boolean> removeFromMailbox(Collection<? extends IdentifiableEmailWithMailboxIds> emails, String mailboxId, @NullableDecl final IdentifiableMailboxWithRole archive, final ObjectsState objectsState) {
        Preconditions.checkNotNull(emails, "emails can not be null when attempting to remove them from a mailbox");
        Preconditions.checkNotNull(mailboxId, "mailboxId can not be null when attempting to remove emails");
        final JmapClient.MultiCall multiCall = jmapClient.newMultiCall();
        final Optional<ListenableFuture<MethodResponses>> mailboxCreateFutureOptional = CreateUtil.mailbox(multiCall, archive, Role.ARCHIVE);
        ImmutableMap.Builder<String, Map<String, Object>> emailPatchObjectMapBuilder = ImmutableMap.builder();
        for (IdentifiableEmailWithMailboxIds email : emails) {
            if (!email.getMailboxIds().containsKey(mailboxId)) {
                continue;
            }
            Patches.Builder patchesBuilder = Patches.builder();
            patchesBuilder.remove("mailboxIds/" + mailboxId);
            if (email.getMailboxIds().size() == 1) {
                if (mailboxCreateFutureOptional.isPresent()) {
                    patchesBuilder.set("mailboxIds/" + CreateUtil.createIdReference(Role.ARCHIVE), true);
                } else {
                    patchesBuilder.set("mailboxIds/" + archive.getId(), true);
                }
            }
            emailPatchObjectMapBuilder.put(email.getId(), patchesBuilder.build());

        }
        final ImmutableMap<String, Map<String, Object>> patches = emailPatchObjectMapBuilder.build();
        if (patches.size() == 0) {
            return Futures.immediateFuture(false);
        }
        final ListenableFuture<MethodResponses> setEmailFuture = multiCall.call(new SetEmailMethodCall(objectsState.emailState, patches));
        if (objectsState.emailState != null) {
            updateEmails(objectsState.emailState, multiCall);
        }
        multiCall.execute();
        return Futures.transformAsync(setEmailFuture, new AsyncFunction<MethodResponses, Boolean>() {
            @Override
            public ListenableFuture<Boolean> apply(@NullableDecl MethodResponses methodResponses) throws Exception {
                if (mailboxCreateFutureOptional.isPresent()) {
                    SetMailboxMethodResponse setMailboxResponse = mailboxCreateFutureOptional.get().get().getMain(SetMailboxMethodResponse.class);
                    SetMailboxException.throwIfFailed(setMailboxResponse);
                }
                SetEmailMethodResponse setEmailResponse = setEmailFuture.get().getMain(SetEmailMethodResponse.class);
                SetEmailException.throwIfFailed(setEmailResponse);
                return Futures.immediateFuture(setEmailResponse.getUpdatedCreatedCount() > 0);
            }
        }, ioExecutorService);
    }

    /**
     * Removes the individual emails in this collection (usually applied to an entire thread) from a given mailbox. If a
     * certain email was not in this mailbox it will be skipped. If removing an email from this mailbox would otherwise
     * lead to the email having no mailbox it will be moved to the Archive mailbox.
     *
     * @param emails    A collection of emails. Usually all messages in a thread
     * @param mailboxId The id of the mailbox from which those emails should be removed
     * @return
     */
    public ListenableFuture<Boolean> removeFromMailbox(final Collection<? extends IdentifiableEmailWithMailboxIds> emails, final String mailboxId) {
        return Futures.transformAsync(getMailboxes(), new AsyncFunction<Collection<? extends IdentifiableMailboxWithRole>, Boolean>() {
            @Override
            public ListenableFuture<Boolean> apply(@NullableDecl Collection<? extends IdentifiableMailboxWithRole> mailboxes) throws Exception {
                Preconditions.checkNotNull(mailboxes, "SpecialMailboxes collection must not be null but can be empty");
                final IdentifiableMailboxWithRole archive = MailboxUtils.find(mailboxes, Role.ARCHIVE);
                return removeFromMailbox(emails, mailboxId, archive);
            }
        }, MoreExecutors.directExecutor());
    }

    /**
     * Moves all emails in this collection (usually applied to an entire thread) to the trash mailbox. The emails will
     * be removed from all other mailboxes. If a certain email in this collection is already only in the trash mailbox
     * this email will not be processed.
     *
     * @param emails A collection of emails. Usually all messages in a thread
     */
    public ListenableFuture<Boolean> moveToTrash(final Collection<? extends IdentifiableEmailWithMailboxIds> emails) {
        return Futures.transformAsync(getMailboxes(), new AsyncFunction<Collection<? extends IdentifiableMailboxWithRole>, Boolean>() {
            @Override
            public ListenableFuture<Boolean> apply(@NullableDecl Collection<? extends IdentifiableMailboxWithRole> mailboxes) {
                Preconditions.checkNotNull(mailboxes, "SpecialMailboxes collection must not be null but can be empty");
                return moveToTrash(emails, MailboxUtils.find(mailboxes, Role.TRASH));
            }
        }, MoreExecutors.directExecutor());
    }

    /**
     * Moves all emails in this collection (usually applied to an entire thread) to the trash mailbox. The emails will
     * be removed from all other mailboxes. If a certain email in this collection is already only in the trash mailbox
     * this email will not be processed.
     *
     * @param emails A collection of emails. Usually all messages in a thread
     * @param trash  A reference to the Trash mailbox. Can be null and a new trash mailbox will automatically be created.
     *               Do not pass null if a Trash mailbox exists on the server as this call will attempt to create one
     *               and fail.
     */

    public ListenableFuture<Boolean> moveToTrash(final Collection<? extends IdentifiableEmailWithMailboxIds> emails, @NullableDecl final IdentifiableMailboxWithRole trash) {
        return Futures.transformAsync(getObjectsState(), new AsyncFunction<ObjectsState, Boolean>() {
            @Override
            public ListenableFuture<Boolean> apply(@NullableDecl ObjectsState objectsState) throws Exception {
                return moveToTrash(emails, trash, objectsState);
            }
        }, MoreExecutors.directExecutor());
    }

    private ListenableFuture<Boolean> moveToTrash(Collection<? extends IdentifiableEmailWithMailboxIds> emails, @NullableDecl final IdentifiableMailboxWithRole trash, final ObjectsState objectsState) {
        final JmapClient.MultiCall multiCall = jmapClient.newMultiCall();
        final Optional<ListenableFuture<MethodResponses>> mailboxCreateFutureOptional = CreateUtil.mailbox(multiCall, trash, Role.TRASH);
        ImmutableMap.Builder<String, Map<String, Object>> emailPatchObjectMapBuilder = ImmutableMap.builder();
        for (IdentifiableEmailWithMailboxIds email : emails) {
            if (trash != null && email.getMailboxIds().size() == 1 && email.getMailboxIds().containsKey(trash.getId())) {
                continue;
            }
            Patches.Builder patchesBuilder = Patches.builder();
            if (mailboxCreateFutureOptional.isPresent()) {
                patchesBuilder.set("mailboxIds", ImmutableMap.of(CreateUtil.createIdReference(Role.TRASH), true));
            } else {
                patchesBuilder.set("mailboxIds", ImmutableMap.of(trash.getId(), true));
            }
            emailPatchObjectMapBuilder.put(email.getId(), patchesBuilder.build());
        }

        final ImmutableMap<String, Map<String, Object>> patches = emailPatchObjectMapBuilder.build();

        if (patches.size() == 0) {
            return Futures.immediateFuture(false);
        }

        final ListenableFuture<MethodResponses> setEmailFuture = multiCall.call(new SetEmailMethodCall(objectsState.emailState, patches));
        if (objectsState.emailState != null) {
            updateEmails(objectsState.emailState, multiCall);
        }
        multiCall.execute();
        return Futures.transformAsync(setEmailFuture, new AsyncFunction<MethodResponses, Boolean>() {
            @Override
            public ListenableFuture<Boolean> apply(@NullableDecl MethodResponses methodResponses) throws Exception {
                if (mailboxCreateFutureOptional.isPresent()) {
                    SetMailboxMethodResponse setMailboxResponse = mailboxCreateFutureOptional.get().get().getMain(SetMailboxMethodResponse.class);
                    SetMailboxException.throwIfFailed(setMailboxResponse);
                }
                SetEmailMethodResponse setEmailResponse = setEmailFuture.get().getMain(SetEmailMethodResponse.class);
                SetEmailException.throwIfFailed(setEmailResponse);
                return Futures.immediateFuture(setEmailResponse.getUpdatedCreatedCount() > 0);
            }
        }, ioExecutorService);
    }

    public ListenableFuture<Status> refresh() {
        return Futures.transformAsync(getObjectsState(), new AsyncFunction<ObjectsState, Status>() {
            @Override
            public ListenableFuture<Status> apply(@NullableDecl ObjectsState objectsState) throws Exception {
                return refresh(objectsState);
            }
        }, MoreExecutors.directExecutor());
    }

    private ListenableFuture<Status> refresh(ObjectsState objectsState) {
        final JmapClient.MultiCall multiCall = jmapClient.newMultiCall();
        List<ListenableFuture<Status>> futuresList = piggyBack(objectsState, multiCall);
        multiCall.execute();
        return transform(futuresList);
    }

    private List<ListenableFuture<Status>> piggyBack(ObjectsState objectsState, JmapClient.MultiCall multiCall) {
        ImmutableList.Builder<ListenableFuture<Status>> futuresListBuilder = new ImmutableList.Builder<>();
        if (objectsState.mailboxState != null) {
            futuresListBuilder.add(updateMailboxes(objectsState.mailboxState, multiCall));
        } else {
            futuresListBuilder.add(loadMailboxes(multiCall));
        }

        //update to emails should happen before update to threads
        //when mua queries threads the corresponding emails should already be in the cache

        if (objectsState.emailState != null) {
            futuresListBuilder.add(updateEmails(objectsState.emailState, multiCall));
        }
        if (objectsState.threadState != null) {
            futuresListBuilder.add(updateThreads(objectsState.threadState, multiCall));
        }
        return futuresListBuilder.build();
    }

    private static ListenableFuture<Status> transform(List<ListenableFuture<Status>> list) {
        return Futures.transform(Futures.allAsList(list), new Function<List<Status>, Status>() {
            @NullableDecl
            @Override
            public Status apply(@NullableDecl List<Status> statuses) {
                if (statuses.contains(Status.HAS_MORE)) {
                    return Status.HAS_MORE;
                }
                if (statuses.contains(Status.UPDATED)) {
                    return Status.UPDATED;
                }
                return Status.UNCHANGED;
            }
        }, MoreExecutors.directExecutor());
    }

    private ListenableFuture<Status> updateThreads(final String state, final JmapClient.MultiCall multiCall) {
        Preconditions.checkNotNull(state, "state can not be null when updating threads");
        final SettableFuture<Status> settableFuture = SettableFuture.create();
        final UpdateUtil.MethodResponsesFuture methodResponsesFuture = UpdateUtil.threads(multiCall, state);
        methodResponsesFuture.changes.addListener(new Runnable() {
            @Override
            public void run() {
                try {
                    final ChangesThreadMethodResponse changesResponse = methodResponsesFuture.changes.get().getMain(ChangesThreadMethodResponse.class);
                    final GetThreadMethodResponse createdResponse = methodResponsesFuture.created.get().getMain(GetThreadMethodResponse.class);
                    final GetThreadMethodResponse updatedResponse = methodResponsesFuture.updated.get().getMain(GetThreadMethodResponse.class);
                    final Update<Thread> update = Update.of(changesResponse, createdResponse, updatedResponse);
                    if (update.hasChanges()) {
                        cache.updateThreads(update);
                    }
                    settableFuture.set(Status.of(update));
                } catch (InterruptedException | ExecutionException | CacheWriteException | CacheConflictException e) {
                    settableFuture.setException(extractException(e));
                }
            }
        }, ioExecutorService);
        return settableFuture;
    }

    public ListenableFuture<Status> query(Filter<Email> filter) {
        return query(EmailQuery.of(filter));
    }

    public ListenableFuture<Status> query(@NonNullDecl final EmailQuery query) {
        final ListenableFuture<QueryStateWrapper> queryStateFuture = ioExecutorService.submit(new Callable<QueryStateWrapper>() {
            @Override
            public QueryStateWrapper call() throws Exception {
                return cache.getQueryState(query.toQueryString());
            }
        });

        return Futures.transformAsync(queryStateFuture, new AsyncFunction<QueryStateWrapper, Status>() {
            @Override
            public ListenableFuture<Status> apply(@NullableDecl QueryStateWrapper queryStateWrapper) {
                Preconditions.checkNotNull(queryStateWrapper, "QueryStateWrapper can not be null");

                if (queryStateWrapper.queryState == null) {
                    return initialQuery(query, queryStateWrapper);
                } else {
                    Preconditions.checkNotNull(queryStateWrapper.objectsState, "ObjectsState can not be null if queryState was not");
                    Preconditions.checkNotNull(queryStateWrapper.objectsState.emailState, "emailState can not be null if queryState was not");
                    Preconditions.checkNotNull(queryStateWrapper.objectsState.threadState, "threadState can not be null if queryState was not");
                    return refreshQuery(query, queryStateWrapper);
                }
            }
        }, MoreExecutors.directExecutor());
    }

    public ListenableFuture<Boolean> query(@NonNullDecl final EmailQuery query, final String afterEmailId) {
        final ListenableFuture<QueryStateWrapper> queryStateFuture = ioExecutorService.submit(new Callable<QueryStateWrapper>() {
            @Override
            public QueryStateWrapper call() throws Exception {
                return cache.getQueryState(query.toQueryString());
            }
        });
        return Futures.transformAsync(queryStateFuture, new AsyncFunction<QueryStateWrapper, Boolean>() {
            @Override
            public ListenableFuture<Boolean> apply(@NullableDecl QueryStateWrapper queryStateWrapper) {
                return query(query, afterEmailId, queryStateWrapper);
            }
        }, MoreExecutors.directExecutor());
    }

    private ListenableFuture<Boolean> query(@NonNullDecl final EmailQuery query, @NonNullDecl final String afterEmailId, final QueryStateWrapper queryStateWrapper) {
        Preconditions.checkNotNull(query, "Query can not be null");
        Preconditions.checkNotNull(afterEmailId, "afterEmailId can not be null");
        Preconditions.checkNotNull(queryStateWrapper, "QueryStateWrapper can not be null when paging");


        //TODO: this currently means we can’t page in queries that aren’t cacheble (=don’t have a queryState)
        //TODO: we should probably get rid of that check and instead simply don’t do the update call
        //TODO: likewise we probably need to be able to ignore a canNotCalculate Changes error on the update response
        if (queryStateWrapper.queryState == null) {
            throw new InconsistentQueryStateException("QueryStateWrapper needs queryState for paging");
        }
        if (!afterEmailId.equals(queryStateWrapper.upTo)) {
            throw new InconsistentQueryStateException("upToId from QueryState needs to match the supplied afterEmailId");
        }
        final SettableFuture<Boolean> settableFuture = SettableFuture.create();
        JmapClient.MultiCall multiCall = jmapClient.newMultiCall();
        final ListenableFuture<Status> queryRefreshFuture = refreshQuery(query, queryStateWrapper, multiCall);

        final Request.Invocation queryInvocation = Request.Invocation.create(new QueryEmailMethodCall(query, afterEmailId, this.queryPageSize));
        Request.Invocation getThreadIdsInvocation = Request.Invocation.create(new GetEmailMethodCall(queryInvocation.createReference(Request.Invocation.ResultReference.Path.IDS), new String[]{"threadId"}));
        final ListenableFuture<MethodResponses> queryResponsesFuture = multiCall.add(queryInvocation);
        final ListenableFuture<MethodResponses> getThreadIdsResponsesFuture = multiCall.add(getThreadIdsInvocation);

        queryResponsesFuture.addListener(new Runnable() {
            @Override
            public void run() {
                try {
                    QueryEmailMethodResponse queryResponse = queryResponsesFuture.get().getMain(QueryEmailMethodResponse.class);
                    GetEmailMethodResponse getThreadIdsResponse = getThreadIdsResponsesFuture.get().getMain(GetEmailMethodResponse.class);

                    final QueryResult queryResult = QueryResult.of(queryResponse, getThreadIdsResponse);

                    //processing order is:
                    //  1) refresh the existent query (which in our implementation also piggybacks email and thread updates)
                    //  2) store new items

                    //TODO status=has_more should probably throw; but cache will eventually throw anyway
                    //TODO as mentioned above we probably need to ignore canNotCalculate changes errors and the like otherwise we won’t be able to page through queries that aren’t cachable
                    queryRefreshFuture.get();

                    cache.addQueryResult(query.toQueryString(), queryResult);

                    fetchMissing(query.toQueryString()).addListener(new Runnable() {
                        @Override
                        public void run() {
                            settableFuture.set(queryResult.items.length > 0);
                        }
                    }, MoreExecutors.directExecutor());
                } catch (Exception e) {
                    settableFuture.setException(extractException(e));
                }
            }
        }, ioExecutorService);
        multiCall.execute();
        return settableFuture;
    }

    private ListenableFuture<Status> refreshQuery(@NonNullDecl final EmailQuery query, @NonNullDecl final QueryStateWrapper queryStateWrapper) {
        final JmapClient.MultiCall multiCall = jmapClient.newMultiCall();
        ListenableFuture<Status> future = refreshQuery(query, queryStateWrapper, multiCall);
        multiCall.execute();
        return future;
    }

    private ListenableFuture<Status> refreshQuery(@NonNullDecl final EmailQuery query, @NonNullDecl final QueryStateWrapper queryStateWrapper, final JmapClient.MultiCall multiCall) {
        Preconditions.checkNotNull(queryStateWrapper.queryState, "QueryState can not be null when attempting to refresh query");
        final SettableFuture<Status> settableFuture = SettableFuture.create();

        final List<ListenableFuture<Status>> piggyBackedFuturesList = piggyBack(queryStateWrapper.objectsState, multiCall);

        final Request.Invocation queryChangesInvocation = Request.Invocation.create(new QueryChangesEmailMethodCall(queryStateWrapper.queryState, query));
        final ListenableFuture<MethodResponses> queryChangesResponsesFuture = multiCall.add(queryChangesInvocation);
        final ListenableFuture<MethodResponses> getThreadIdResponsesFuture = multiCall.call(new GetEmailMethodCall(queryChangesInvocation.createReference(Request.Invocation.ResultReference.Path.ADDED_IDS), new String[]{"threadId"}));

        queryChangesResponsesFuture.addListener(new Runnable() {
            @Override
            public void run() {
                try {
                    QueryChangesEmailMethodResponse queryChangesResponse = queryChangesResponsesFuture.get().getMain(QueryChangesEmailMethodResponse.class);
                    GetEmailMethodResponse getThreadIdsResponse = getThreadIdResponsesFuture.get().getMain(GetEmailMethodResponse.class);
                    List<AddedItem<QueryResultItem>> added = QueryResult.of(queryChangesResponse, getThreadIdsResponse);

                    final QueryUpdate<Email, QueryResultItem> queryUpdate = QueryUpdate.of(queryChangesResponse, added);

                    //processing order is:
                    //  1) update Objects (Email, Threads, and Mailboxes)
                    //  2) store query results; If query cache sees an outdated email state it will fail

                    Status piggybackStatus = transform(piggyBackedFuturesList).get(); //wait for updates before attempting to fetch
                    Status queryUpdateStatus = Status.of(queryUpdate);

                    if (queryUpdate.hasChanges()) {
                        cache.updateQueryResults(query.toQueryString(), queryUpdate, getThreadIdsResponse.getTypedState());
                    }

                    if (piggybackStatus == Status.UNCHANGED && queryUpdateStatus == Status.UNCHANGED) {
                        settableFuture.set(Status.UNCHANGED);
                    } else {
                        final List<ListenableFuture<Status>> list = new ArrayList<>();
                        list.add(Futures.immediateFuture(piggybackStatus));
                        list.add(Futures.immediateFuture(queryUpdateStatus));
                        //TODO this should be unnecessary. At the time of an refresh we have previously loaded all ids
                        //TODO: however it might be that a previous fetchMissing() has failed. so better safe than sorry
                        list.add(fetchMissing(query.toQueryString()));
                        settableFuture.setFuture(transform(list));
                    }

                } catch (InterruptedException | ExecutionException | CacheWriteException | CacheConflictException e) {
                    settableFuture.setException(extractException(e));
                }
            }
        }, ioExecutorService);

        return settableFuture;
    }

    private ListenableFuture<Status> initialQuery(@NonNullDecl final EmailQuery query, @NonNullDecl final QueryStateWrapper queryStateWrapper) {

        Preconditions.checkState(queryStateWrapper.queryState == null, "QueryState must be NULL when calling initialQuery");

        final SettableFuture<Status> settableFuture = SettableFuture.create();
        JmapClient.MultiCall multiCall = jmapClient.newMultiCall();

        //these need to be processed *before* the Query call or else the fetchMissing will not honor newly fetched ids
        final List<ListenableFuture<Status>> piggyBackedFuturesList = piggyBack(queryStateWrapper.objectsState, multiCall);

        final Request.Invocation queryInvocation = Request.Invocation.create(new QueryEmailMethodCall(query, this.queryPageSize));
        Request.Invocation getThreadIdsInvocation = Request.Invocation.create(new GetEmailMethodCall(queryInvocation.createReference(Request.Invocation.ResultReference.Path.IDS), new String[]{"threadId"}));
        final ListenableFuture<MethodResponses> queryResponsesFuture = multiCall.add(queryInvocation);
        final ListenableFuture<MethodResponses> getThreadIdsResponsesFuture = multiCall.add(getThreadIdsInvocation);


        final Optional<ListenableFuture<MethodResponses>> getThreadsResponsesFutureOptional;
        final Optional<ListenableFuture<MethodResponses>> getEmailResponsesFutureOptional;
        if (queryStateWrapper.objectsState.threadState == null && queryStateWrapper.objectsState.emailState == null) {
            Request.Invocation getThreadsInvocation = Request.Invocation.create(new GetThreadMethodCall(getThreadIdsInvocation.createReference(Request.Invocation.ResultReference.Path.LIST_THREAD_IDS)));
            Request.Invocation getEmailInvocation = Request.Invocation.create(new GetEmailMethodCall(getThreadsInvocation.createReference(Request.Invocation.ResultReference.Path.LIST_EMAIL_IDS), true));
            getThreadsResponsesFutureOptional = Optional.of(multiCall.add(getThreadsInvocation));
            getEmailResponsesFutureOptional = Optional.of(multiCall.add(getEmailInvocation));
        } else {
            getThreadsResponsesFutureOptional = Optional.absent();
            getEmailResponsesFutureOptional = Optional.absent();
        }

        multiCall.execute();
        queryResponsesFuture.addListener(new Runnable() {
            @Override
            public void run() {
                try {
                    QueryEmailMethodResponse queryResponse = queryResponsesFuture.get().getMain(QueryEmailMethodResponse.class);
                    GetEmailMethodResponse getThreadIdsResponse = getThreadIdsResponsesFuture.get().getMain(GetEmailMethodResponse.class);

                    QueryResult queryResult = QueryResult.of(queryResponse, getThreadIdsResponse);

                    //processing order is:
                    //  1) update Objects (Email, Threads, and Mailboxes)
                    //  2) if getThread or getEmails calls where made process those results
                    //  3) store query results; If query cache sees an outdated email state it will fail
                    transform(piggyBackedFuturesList).get();

                    if (getThreadsResponsesFutureOptional.isPresent()) {
                        GetThreadMethodResponse getThreadsResponse = getThreadsResponsesFutureOptional.get().get().getMain(GetThreadMethodResponse.class);
                        cache.setThreads(getThreadsResponse.getTypedState(), getThreadsResponse.getList());
                    }
                    if (getEmailResponsesFutureOptional.isPresent()) {
                        GetEmailMethodResponse getEmailResponse = getEmailResponsesFutureOptional.get().get().getMain(GetEmailMethodResponse.class);
                        cache.setEmails(getEmailResponse.getTypedState(), getEmailResponse.getList());
                    }

                    if (queryResult.position != 0) {
                        throw new IllegalStateException("Server reported position " + queryResult.position + " in response to initial query. We expected 0");
                    }

                    cache.setQueryResult(query.toQueryString(), queryResult);

                    if (getEmailResponsesFutureOptional.isPresent() && getEmailResponsesFutureOptional.isPresent()) {
                        settableFuture.set(Status.UPDATED);
                    } else {
                        List<ListenableFuture<Status>> list = new ArrayList<>();
                        list.add(Futures.immediateFuture(Status.UPDATED));
                        list.add(fetchMissing(query.toQueryString()));
                        settableFuture.setFuture(transform(list));
                    }
                } catch (InterruptedException | ExecutionException | CacheWriteException e) {
                    settableFuture.setException(extractException(e));
                }
            }
        }, ioExecutorService);
        return settableFuture;
    }

    private ListenableFuture<Status> fetchMissing(@NonNullDecl final String queryString) {
        Preconditions.checkNotNull(queryString, "QueryString can not be null");
        try {
            return fetchMissing(cache.getMissing(queryString));
        } catch (CacheReadException e) {
            return Futures.immediateFailedFuture(e);
        }
    }

    private ListenableFuture<Status> fetchMissing(final Missing missing) {
        Preconditions.checkNotNull(missing, "Missing can not be null");
        Preconditions.checkNotNull(missing.threadIds, "Missing.ThreadIds can not be null; pass empty list instead");
        if (missing.threadIds.size() == 0) {
            return Futures.immediateFuture(Status.UNCHANGED);
        }
        LOGGER.info("fetching " + missing.threadIds.size() + " missing threads");
        final SettableFuture<Status> settableFuture = SettableFuture.create();
        final JmapClient.MultiCall multiCall = jmapClient.newMultiCall();
        final ListenableFuture<Status> updateThreadsFuture = updateThreads(missing.threadState, multiCall);
        final ListenableFuture<Status> updateEmailsFuture = updateEmails(missing.emailState, multiCall);
        Request.Invocation getThreadsInvocation = Request.Invocation.create(new GetThreadMethodCall(missing.threadIds.toArray(new String[0])));
        final ListenableFuture<MethodResponses> getThreadsResponsesFuture = multiCall.add(getThreadsInvocation);
        final ListenableFuture<MethodResponses> getEmailsResponsesFuture = multiCall.call(new GetEmailMethodCall(getThreadsInvocation.createReference(Request.Invocation.ResultReference.Path.LIST_EMAIL_IDS), true));
        multiCall.execute();
        getThreadsResponsesFuture.addListener(new Runnable() {
            @Override
            public void run() {
                try {
                    Status updateThreadsStatus = updateThreadsFuture.get();
                    if (updateThreadsStatus == Status.HAS_MORE) {
                        //throw
                    }

                    Status updateEmailStatus = updateEmailsFuture.get();
                    if (updateEmailStatus == Status.HAS_MORE) {
                        //throw
                    }

                    GetThreadMethodResponse getThreadMethodResponse = getThreadsResponsesFuture.get().getMain(GetThreadMethodResponse.class);
                    cache.addThreads(getThreadMethodResponse.getTypedState(), getThreadMethodResponse.getList());

                    GetEmailMethodResponse getEmailMethodResponse = getEmailsResponsesFuture.get().getMain(GetEmailMethodResponse.class);
                    cache.addEmails(getEmailMethodResponse.getTypedState(), getEmailMethodResponse.getList());

                    settableFuture.set(Status.UPDATED);

                } catch (Exception e) {
                    settableFuture.setException(extractException(e));
                }

            }
        }, ioExecutorService);
        return settableFuture;
    }

    public static class Builder {
        private String username;
        private String password;
        private SessionCache sessionCache = new SessionFileCache();
        private Cache cache = new InMemoryCache();
        private Integer queryPageSize = null;

        private Builder() {

        }

        public Builder username(String username) {
            this.username = username;
            return this;
        }

        public Builder password(String password) {
            this.password = password;
            return this;
        }

        public Builder queryPageSize(Integer queryPageSize) {
            this.queryPageSize = queryPageSize;
            return this;
        }

        public Builder sessionCache(SessionCache sessionCache) {
            this.sessionCache = sessionCache;
            return this;
        }

        public Builder cache(Cache cache) {
            this.cache = cache;
            return this;
        }

        public Mua build() {
            JmapClient jmapClient = new JmapClient(this.username, this.password);
            jmapClient.setSessionCache(this.sessionCache);
            Mua mua = new Mua(jmapClient, cache);
            mua.queryPageSize = this.queryPageSize;
            return mua;
        }
    }

}