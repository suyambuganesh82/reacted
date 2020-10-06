/*
 * Copyright (c) 2020 , <Pierre Falda> [ pierre@reacted.io ]
 * All rights reserved.
 *
 * This source code is licensed under the BSD-style license found in the
 * LICENSE file in the root directory of this source tree.
 */

package io.reacted.core.reactorsystem;

import io.reacted.core.config.reactors.ReActorConfig;
import io.reacted.core.config.reactors.SniffSubscription;
import io.reacted.core.mailboxes.MailBox;
import io.reacted.core.messages.Message;
import io.reacted.core.messages.reactors.DeliveryStatus;
import io.reacted.core.reactors.ReActions;
import io.reacted.core.reactors.ReActiveEntity;
import io.reacted.core.reactors.ReActor;
import io.reacted.core.runtime.Dispatcher;
import io.reacted.patterns.NonNullByDefault;
import io.reacted.patterns.Try;

import javax.annotation.Nullable;
import java.io.Serializable;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.ReadWriteLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import java.util.function.BiConsumer;
import java.util.function.Function;

@NonNullByDefault
public final class ReActorContext {
    @Nullable
    public static final ReActorContext NO_REACTOR_CTX = null;
    private final MailBox actorMbox;
    private final ReActorRef reactorRef;
    private final ReActorSystem reActorSystem;
    private final List<ReActorRef> children;
    private final ReActorRef parent;
    private final Dispatcher dispatcher;
    private final AtomicBoolean isScheduled;
    private final ReadWriteLock structuralLock;
    private final CompletionStage<Void> hierarchyTermination;
    private final AtomicLong msgExecutionId;
    private final ReActions reActions;

    private SniffSubscription[] interceptRules;

    private volatile boolean stop = false;
    private volatile boolean isAcquired = false;

    private ReActorRef lastMsgSender = ReActorRef.NO_REACTOR_REF;

    private ReActorContext(Builder reActorCtxBuilder) {
        this.actorMbox = Objects.requireNonNull(Objects.requireNonNull(reActorCtxBuilder.mboxProvider).apply(this));
        this.reactorRef = Objects.requireNonNull(reActorCtxBuilder.reactorRef);
        this.reActorSystem = Objects.requireNonNull(reActorCtxBuilder.reActorSystem);
        this.children = new CopyOnWriteArrayList<>();
        this.parent = Objects.requireNonNull(reActorCtxBuilder.parent);
        this.dispatcher = Objects.requireNonNull(reActorCtxBuilder.dispatcher);
        this.isScheduled = new AtomicBoolean(false);
        this.structuralLock = new ReentrantReadWriteLock();
        this.interceptRules = Objects.requireNonNull(reActorCtxBuilder.interceptRules).length == 0
                              ? SniffSubscription.NO_SUBSCRIPTIONS
                              : Arrays.copyOf(reActorCtxBuilder.interceptRules,
                                              reActorCtxBuilder.interceptRules.length);
        this.hierarchyTermination = new CompletableFuture<>();
        this.msgExecutionId = new AtomicLong();
        this.reActions = Objects.requireNonNull(reActorCtxBuilder.reActions);
    }

    public static Builder newBuilder() { return new Builder(); }

    public ReActorRef getSelf() { return reactorRef; }

    public ReActorSystem getReActorSystem() { return reActorSystem; }

    public List<ReActorRef> getChildren() { return children; }

    public ReActorRef getParent() { return parent; }

    public Dispatcher getDispatcher() { return dispatcher; }

    public MailBox getMbox() { return actorMbox; }

    public CompletionStage<Void> getHierarchyTermination() { return hierarchyTermination; }

    public long getNextMsgExecutionId() { return msgExecutionId.getAndIncrement(); }

    public boolean acquireScheduling() {
        return isScheduled.compareAndSet(false, true);
    }

    public void releaseScheduling() { isScheduled.compareAndSet(true, false); }

    @SuppressWarnings("UnusedReturnValue")
    public boolean acquireCoherence() { return !isAcquired; }

    public void releaseCoherence() { isAcquired = false; }

    public void refreshInterceptors(SniffSubscription... newInterceptedClasses) {

        getStructuralLock().writeLock().lock();
        try {
            getReActorSystem().updateMessageInterceptors(this, interceptRules, newInterceptedClasses);
            interceptRules = newInterceptedClasses;
        } finally {
            getStructuralLock().writeLock().unlock();
        }
    }

    public SniffSubscription[] getInterceptRules() {
        SniffSubscription[] interceptedMsgTypes;

        getStructuralLock().readLock().lock();
        interceptedMsgTypes = Arrays.copyOf(this.interceptRules, this.interceptRules.length);
        getStructuralLock().readLock().unlock();

        return interceptedMsgTypes;
    }

    public final void reschedule() {
        getDispatcher().dispatch(this);
    }

    public CompletionStage<Try<DeliveryStatus>> reply(Serializable anyPayload) {
        return reply(getSelf(), anyPayload);
    }

    public CompletionStage<Try<DeliveryStatus>> aReply(Serializable anyPayload) {
        return aReply(getSelf(), anyPayload);
    }

    public CompletionStage<Try<DeliveryStatus>> reply(ReActorRef sender, Serializable anyPayload) {
        return getSender().tell(sender, anyPayload);
    }

    /**
     * Reply sending a message to the sender of the last message processed by this reactor using {@link ReActorRef#aTell(Serializable)}
     * @param sender {@link ReActorRef} identifying the sender of this reply
     * @param anyPayload payload to be sent
     * @return a {@link CompletionStage}&lt;{@link Try}&lt;{@link DeliveryStatus}&gt;&gt; returned by {@link ReActorRef#aTell(ReActorRef, Serializable)}
     */
    public CompletionStage<Try<DeliveryStatus>> aReply(ReActorRef sender, Serializable anyPayload) {
        return getSender().aTell(sender, anyPayload);
    }

    /**
     * {@link ReActorRef#tell(Serializable)} to the current reactor the specified message setting itself as sender for the message
     * @param anyPayload message that should be self-sent
     * @return A {@link CompletionStage}&lt;{@link Try}&lt;{@link DeliveryStatus}&gt;&gt; returned by {@link ReActorRef#tell(Serializable)}
     * complete
     */
    public CompletionStage<Try<DeliveryStatus>> selfTell(Serializable anyPayload) {
        return getSelf().tell(this.getSelf(), anyPayload);
    }

    /**
     * Spawn a new {@link ReActor} child of the spawning one
     * @param reActor the new {@link ReActor} definition
     * @return a {@link Try}&lt;{@link ReActorRef}&gt; pointing to the new {@link ReActor}
     */
    public Try<ReActorRef> spawnChild(ReActor reActor) {
        return getReActorSystem().spawnChild(reActor.getReActions(), getSelf(), reActor.getConfig());
    }

    /**
     * Spawn a new {@link ReActor} child of the spawning one
     * @param reActiveEntity the {@link ReActiveEntity} definition for the new {@link ReActor}
     * @param reActorConfig the {@link ReActorConfig} for the new {@link ReActor}
     * @return a {@link Try}&lt;{@link ReActorRef}&gt; containing a {@link ReActorRef} pointing to the new {@link ReActor}
     */
    public Try<ReActorRef> spawnChild(ReActiveEntity reActiveEntity, ReActorConfig reActorConfig) {
        return getReActorSystem().spawnChild(reActiveEntity.getReActions(), getSelf(), reActorConfig);
    }

    /**
     * Spawn a new {@link ReActor} child of the spawning one
     * @param reActions the {@link ReActions} for the new {@link ReActor}
     * @param reActorConfig the {@link ReActorConfig} for the new {@link ReActor}
     * @return a {@link Try}&lt;{@link ReActorRef}&gt; containing a {@link ReActorRef} pointing to the new {@link ReActor}
     */
    public Try<ReActorRef> spawnChild(ReActions reActions, ReActorConfig reActorConfig) {
        return getReActorSystem().spawnChild(reActions, getSelf(), reActorConfig);
    }

    /**
     * Set the message intercept rules for this reactor to enable passive message sniffing
     * @param interceptRules {@link SniffSubscription} array
     */
    public final void setInterceptRules(SniffSubscription... interceptRules) {
        refreshInterceptors(Objects.requireNonNull(interceptRules).length == 0
                            ? SniffSubscription.NO_SUBSCRIPTIONS
                            : Arrays.copyOf(interceptRules, interceptRules.length));
    }

    /**
     * Request termination for this reactor and the underlying hierachy
     * @return a {@link CompletionStage} that is going to be completed when the last reactor in the hierarchy
     * is terminated
     */
    public CompletionStage<Void> stop() {
        this.stop = true;
        reschedule();
        return getHierarchyTermination();
    }

    public boolean isStop() {
        return this.stop;
    }

    /**
     * Send a logging request for info level to the centralized logger reactor
     *
     * @param descriptionFormat description in sl4j format
     * @param args arguments list
     */
    public void logInfo(String descriptionFormat, Serializable ...args) {
        getReActorSystem().logInfo(descriptionFormat, args);
    }

    /**
     * Send a logging request for error level to the centralized logger reactor
     *
     * @param descriptionFormat description in sl4j format
     * @param args arguments list
     */
    public void logError(String descriptionFormat, Serializable ...args) {
        getReActorSystem().logError(descriptionFormat, args);
    }

    /**
     * Send a logging request for debug level to the centralized logger reactor
     *
     * @param descriptionFormat description in sl4j format
     * @param args arguments list
     */
    public void logDebug(String descriptionFormat, Serializable ...args) {
        getReActorSystem().logDebug(descriptionFormat, args);
    }

    public void reAct(Message msg) {
        this.lastMsgSender = msg.getSender();
        BiConsumer<ReActorContext, Serializable> reAction = reActions.getReAction(msg.getPayload());
        reAction.accept(this, msg.getPayload());
    }

    /**
     * Get the sender of the last message processed by this reactor
     * @return {@link ReActorRef} to the sender
     */
    public ReActorRef getSender() {
        return this.lastMsgSender;
    }

    @Override
    public boolean equals(@Nullable Object o) {
        if (this == o) return true;
        if (!(o instanceof ReActorContext)) return false;
        ReActorContext that = (ReActorContext) o;
        return getSelf().equals(that.getSelf());
    }

    @Override
    public int hashCode() {
        return Objects.hash(getSelf());
    }

    ReadWriteLock getStructuralLock() { return structuralLock; }

    @SuppressWarnings("UnusedReturnValue")
    boolean registerChild(ReActorRef childActor) {
        return children.add(childActor);
    }

    @SuppressWarnings("UnusedReturnValue")
    boolean unregisterChild(ReActorRef childActor) {
        return children.remove(childActor);
    }

    @SuppressWarnings("NotNullFieldNotInitialized")
    public static class Builder {
        private Function<ReActorContext, MailBox> mboxProvider;
        private ReActorRef reactorRef;
        private ReActorSystem reActorSystem;
        private ReActorRef parent;
        private SniffSubscription[] interceptRules;
        private Dispatcher dispatcher;
        private ReActions reActions;

        public Builder setMbox(Function<ReActorContext, MailBox> actorMboxProvider) {
            this.mboxProvider = actorMboxProvider;
            return this;
        }

        public Builder setReactorRef(ReActorRef reactorRef) {
            this.reactorRef = reactorRef;
            return this;
        }

        public Builder setReActorSystem(ReActorSystem reActorSystem) {
            this.reActorSystem = reActorSystem;
            return this;
        }

        public Builder setParentActor(ReActorRef parentActor) {
            this.parent = parentActor;
            return this;
        }

        public Builder setInterceptRules(SniffSubscription... interceptRules) {
            this.interceptRules = interceptRules;
            return this;
        }

        public Builder setDispatcher(Dispatcher dispatcher) {
            this.dispatcher = dispatcher;
            return this;
        }

        public Builder setReActions(ReActions reActions) {
            this.reActions = reActions;
            return this;
        }

        public ReActorContext build() {
            return new ReActorContext(this);
        }
    }
}
