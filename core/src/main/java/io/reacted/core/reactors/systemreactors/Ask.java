/*
 * Copyright (c) 2020 , <Pierre Falda> [ pierre@reacted.io ]
 * All rights reserved.
 *
 * This source code is licensed under the BSD-style license found in the
 * LICENSE file in the root directory of this source tree.
 */

package io.reacted.core.reactors.systemreactors;

import io.reacted.core.config.reactors.ReActorConfig;
import io.reacted.core.exceptions.DeliveryException;
import io.reacted.core.messages.reactors.ReActorInit;
import io.reacted.core.messages.reactors.ReActorStop;
import io.reacted.core.reactors.ReActions;
import io.reacted.core.reactors.ReActor;
import io.reacted.core.reactorsystem.ReActorContext;
import io.reacted.core.reactorsystem.ReActorRef;
import io.reacted.patterns.NonNullByDefault;
import io.reacted.patterns.ObjectUtils;

import javax.annotation.Nonnull;
import java.io.Serializable;
import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

@NonNullByDefault
public class Ask<ReplyT extends Serializable> implements ReActor {
    private final Duration askTimeout;
    private final Class<ReplyT> expectedReplyType;
    private final CompletableFuture<ReplyT> completionTrigger;
    private final String requestName;
    private final ReActorRef target;
    private final Serializable request;

    public Ask(Duration askTimeout, Class<ReplyT> expectedReplyType, CompletableFuture<ReplyT> completionTrigger,
               String requestName, ReActorRef target, Serializable request) {
        this.askTimeout = ObjectUtils.checkNonNullPositiveTimeInterval(askTimeout);
        this.expectedReplyType = Objects.requireNonNull(expectedReplyType);
        this.completionTrigger = Objects.requireNonNull(completionTrigger);
        this.requestName = Objects.requireNonNull(requestName);
        this.target = Objects.requireNonNull(target);
        this.request = Objects.requireNonNull(request);
    }

    @Nonnull
    @Override
    public ReActions getReActions() {
        return ReActions.newBuilder()
                        .reAct(ReActorInit.class, this::onInit)
                        .reAct(ReActorStop.class, ReActions::noReAction)
                        .reAct(expectedReplyType, this::onExpectedReply)
                        .reAct(this::onUnexpected)
                        .build();
    }

    @Nonnull
    @Override
    public ReActorConfig getConfig() {
        return ReActorConfig.newBuilder()
                            .setReActorName(Ask.class.getSimpleName() + "|" + requestName + "|" +
                                            target.getReActorId() + "|" +
                                            request.getClass().getSimpleName() + "|" +
                                            expectedReplyType.getSimpleName())
                            .build();
    }

    private void onInit(ReActorContext raCtx, ReActorInit init) {
        if (!target.publish(raCtx.getSelf(), request).isSent()) {
            raCtx.stop()
                 .thenAccept(noVal -> completionTrigger.completeExceptionally(new DeliveryException()));
        } else {
            raCtx.getReActorSystem()
                 .getSystemSchedulingService()
                 .schedule(() -> raCtx.stop()
                                      .thenAccept(noVal -> completionTrigger.completeExceptionally(new TimeoutException())),
                           askTimeout.toMillis(), TimeUnit.MILLISECONDS);
        }
    }

    private void onExpectedReply(ReActorContext raCtx, ReplyT reply) {
        raCtx.stop()
             .thenAccept(noVal -> completionTrigger.complete(reply));
    }
    private void onUnexpected(ReActorContext raCtx, Serializable anyType) {
        var failure = new IllegalArgumentException(String.format("Received %s instead of %s",
                                                                 anyType.getClass().getName(),
                                                                 expectedReplyType.getName()));
        raCtx.stop()
             .thenAccept(noVal -> completionTrigger.completeExceptionally(failure));
    }
}
