/*
 * Copyright (c) 2020 , <Pierre Falda> [ pierre@reacted.io ]
 * All rights reserved.
 *
 * This source code is licensed under the BSD-style license found in the
 * LICENSE file in the root directory of this source tree.
 */

package io.reacted.core.drivers.system;

import io.reacted.core.config.ChannelId;
import io.reacted.core.exceptions.NoRouteToReActorSystem;
import io.reacted.core.messages.AckingPolicy;
import io.reacted.core.messages.Message;
import io.reacted.core.messages.reactors.DeliveryStatus;
import io.reacted.core.reactors.ReActorId;
import io.reacted.core.reactorsystem.ReActorContext;
import io.reacted.core.reactorsystem.ReActorRef;
import io.reacted.core.reactorsystem.ReActorSystem;
import io.reacted.patterns.NonNullByDefault;
import io.reacted.patterns.Try;
import io.reacted.patterns.UnChecked;

import java.io.Serializable;
import java.util.Properties;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

@NonNullByDefault
public final class NullDriver extends ReActorSystemDriver {
    public static final NullDriver NULL_DRIVER = new NullDriver();
    public static final Properties NULL_DRIVER_PROPERTIES = new Properties();

    private NullDriver() {}

    @Override
    public Try<Void> initDriverCtx(ReActorSystem localReActorSystem) {
        return Try.VOID;
    }

    @Override
    public CompletionStage<Try<Void>> stopDriverCtx(ReActorSystem reActorSystem) { return cleanDriverLoop(); }

    @Override
    public void initDriverLoop(ReActorSystem localReActorSystem) throws UnsupportedOperationException{
        throw new UnsupportedOperationException();
    }

    @Override
    public CompletionStage<Try<Void>> cleanDriverLoop() { return CompletableFuture.completedFuture(Try.VOID);  }

    @Override
    public UnChecked.CheckedRunnable getDriverLoop() {
        return () -> {};
    }

    @Override
    public <PayloadT extends Serializable> CompletionStage<Try<DeliveryStatus>> tell(ReActorRef src, ReActorRef dst,
                                                                                     AckingPolicy ackingPolicy,
                                                                                     PayloadT message) {
        return CompletableFuture.completedFuture(Try.ofFailure(new NoRouteToReActorSystem()));
    }

    @Override
    public Try<DeliveryStatus> sendMessage(ReActorContext destination, Message message) {
        return Try.ofFailure(new NoRouteToReActorSystem());
    }

    @Override
    public CompletionStage<Try<DeliveryStatus>> sendAsyncMessage(ReActorContext destination, Message message) {
        return CompletableFuture.completedFuture(sendMessage(destination, message));
    }

    @Override
    public boolean channelRequiresDeliveryAck() { return false; }

    @Override
    public void stop(ReActorId dst) { }

    @Override
    public ChannelId getChannelId() {
        return ChannelId.NO_CHANNEL_ID;
    }

    @Override
    public Properties getChannelProperties() { return NULL_DRIVER_PROPERTIES; }
}
