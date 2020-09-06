/*
 * Copyright (c) 2020 , <Pierre Falda> [ pierre@reacted.io ]
 * All rights reserved.
 *
 * This source code is licensed under the BSD-style license found in the
 * LICENSE file in the root directory of this source tree.
 */

package io.reacted.streams;

import io.reacted.core.mailboxes.BackpressuringMbox;
import io.reacted.core.mailboxes.BoundedBasicMbox;
import io.reacted.core.mailboxes.MailBox;
import io.reacted.core.messages.reactors.DeliveryStatus;
import io.reacted.core.messages.reactors.ReActorInit;
import io.reacted.core.messages.reactors.ReActorStop;
import io.reacted.core.reactors.ReActions;
import io.reacted.core.reactorsystem.ReActorContext;
import io.reacted.core.reactorsystem.ReActorRef;
import io.reacted.patterns.NonNullByDefault;
import io.reacted.patterns.Try;
import io.reacted.streams.messages.SubscriberComplete;
import io.reacted.streams.messages.SubscriberError;
import io.reacted.streams.messages.SubscriptionReply;
import io.reacted.streams.messages.SubscriptionRequest;
import io.reacted.streams.messages.UnsubscriptionRequest;

import javax.annotation.Nullable;
import java.io.Serializable;
import java.time.Duration;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.Flow;
import java.util.function.Supplier;

@NonNullByDefault
public class BackpressureManager<PayloadT extends Serializable> implements Flow.Subscription, AutoCloseable {
    private final Flow.Subscriber<? super PayloadT> subscriber;
    private final ReActorRef feedGate;
    private final Executor subscriberExecutor;
    private final BackpressuringMbox backpressuredMailbox;
    private CompletableFuture<Void> executionChainer = CompletableFuture.completedFuture(null);
    @Nullable
    private volatile ReActorContext backpressurerCtx;

    /**
     * It manages a backpressured reactive streams. A reactive stream can accept local (to the reactor system) or
     * remote subscribers
     *
     * @param subscriber subscriber body
     * @param feedGate source of data for the managed stream
     * @param subscriberExecutor thread used to execute the actions of the subscriber body
     * @param bufferSize subscriber data buffer
     * @param backpressureTimeout give up timeout on publication attempt
     */
    BackpressureManager(Flow.Subscriber<? super PayloadT> subscriber, ReActorRef feedGate,
                        Executor subscriberExecutor, int bufferSize, Duration backpressureTimeout) {
        this.subscriber = Objects.requireNonNull(subscriber);
        this.feedGate = Objects.requireNonNull(feedGate);
        this.subscriberExecutor = Objects.requireNonNull(subscriberExecutor);
        this.backpressuredMailbox = new BackpressuringMbox(new BoundedBasicMbox(bufferSize),
                                                           Objects.requireNonNull(backpressureTimeout),
                                                           bufferSize, 0, subscriberExecutor,
                                                           Set.of(ReActorInit.class, ReActorStop.class,
                                                                  SubscriptionRequest.class, SubscriptionReply.class,
                                                                  UnsubscriptionRequest.class, SubscriberError.class),
                                                           Set.of(SubscriberComplete.class));
    }

    @Override
    public void request(long elements) {
        if (elements <= 0) {
            errorTermination(Objects.requireNonNull(backpressurerCtx),
                             new IllegalArgumentException("non-positive subscription request"), this.subscriber);
        } else {
            if (this.backpressurerCtx != null) {
                this.backpressuredMailbox.request(elements);
            }
        }
    }

    @Override
    public void cancel() { close(); }

    @Override
    public void close() {
        if (this.backpressurerCtx != null) {
            var ctx = Objects.requireNonNull(this.backpressurerCtx);
            ctx.stop();
            this.feedGate.tell(ReActorRef.NO_REACTOR_REF, new UnsubscriptionRequest(ctx.getSelf()));
        }
    }

    Supplier<MailBox> getManagerMailbox() {
        return () -> this.backpressuredMailbox;
    }

    ReActions getReActions() {
        return ReActions.newBuilder()
                        .reAct(ReActorInit.class, this::onInit)
                        .reAct(ReActorStop.class, this::onStop)
                        .reAct(SubscriptionReply.class, this::onSubscriptionReply)
                        .reAct(SubscriberError.class, this::onSubscriberError)
                        .reAct(SubscriberComplete.class, this::onSubscriberComplete)
                        .reAct(this::forwarder)
                        .build();
    }

    private void forwarder(ReActorContext raCtx, Object anyPayload) {
        //noinspection unchecked
        this.executionChainer = this.executionChainer.thenAcceptAsync(voidRet -> Try.ofRunnable(() -> this.subscriber.onNext((PayloadT) anyPayload))
                                                                                    .ifError(error -> errorTermination(raCtx, error, this.subscriber)),
                                                                      subscriberExecutor);
    }

    private void onSubscriptionReply(ReActorContext raCtx, SubscriptionReply payload) {
        if (payload.isSuccess()) {
            executionChainer =
                    executionChainer.thenAcceptAsync(voidRet -> Try.ofRunnable(() -> this.subscriber.onSubscribe(this))
                                                                   .ifError(error -> errorTermination(raCtx, error, this.subscriber)),
                                                     subscriberExecutor);
        } else {
            executionChainer = executionChainer.thenAcceptAsync(voidRet -> errorTermination(raCtx,
                                                                                            new RuntimeException(
                                                                                                    "RemoteRegistrationException"), this.subscriber));
        }
    }

    private void onSubscriberError(ReActorContext raCtx, SubscriberError error) {
        executionChainer = executionChainer.thenAcceptAsync(voidRet -> errorTermination(raCtx, error.getError(),
                                                                                        this.subscriber),
                                                            subscriberExecutor);
    }

    private void onSubscriberComplete(ReActorContext raCtx, SubscriberComplete subscriberComplete) {
        executionChainer = executionChainer.thenAcceptAsync(voidRet -> completeTermination(raCtx, this.subscriber),
                                                            subscriberExecutor);

    }

    private void onInit(ReActorContext raCtx, ReActorInit init) {
        this.backpressurerCtx = raCtx;
        this.feedGate.tell(raCtx.getSelf(), new SubscriptionRequest(raCtx.getSelf()))
                     .thenAccept(deliveryStatusTry -> deliveryStatusTry.filter(DeliveryStatus::isDelivered)
                                                                       .ifError(error -> {
                                                                           this.subscriber.onSubscribe(this);
                                                                           errorTermination(raCtx, error,
                                                                                            this.subscriber);
                                                                       }));

    }

    private void onStop(ReActorContext raCtx, ReActorStop stop) {
        this.backpressuredMailbox.close();
    }

    private void completeTermination(ReActorContext raCtx, Flow.Subscriber<? super PayloadT> localSubscriber) {
        close();
        Try.ofRunnable(localSubscriber::onComplete)
           .ifError(error -> raCtx.getReActorSystem().logError("Error in %s onComplete: ", error,
                                                               localSubscriber.getClass().getSimpleName()));
    }

    private void errorTermination(ReActorContext raCtx, Throwable handlingError,
                                  Flow.Subscriber<? super PayloadT> localSubscriber) {
        close();
        Try.ofRunnable(() -> localSubscriber.onError(handlingError))
           .ifError(error -> raCtx.getReActorSystem().logError("Error in %s onError: ", error,
                                                               localSubscriber.getClass().getSimpleName()));
    }
}
