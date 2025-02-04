/*
 * Copyright (c) 2020 , <Pierre Falda> [ pierre@reacted.io ]
 * All rights reserved.
 *
 * This source code is licensed under the BSD-style license found in the
 * LICENSE file in the root directory of this source tree.
 */

package io.reacted.core.reactorsystem;

import io.reacted.core.config.ChannelId;
import io.reacted.core.config.drivers.NullDriverConfig;
import io.reacted.core.drivers.system.NullDriver;
import io.reacted.core.drivers.system.ReActorSystemDriver;
import io.reacted.core.messages.AckingPolicy;
import io.reacted.core.messages.reactors.DeliveryStatus;
import io.reacted.patterns.NonNullByDefault;

import java.io.Serializable;
import java.util.Properties;
import java.util.concurrent.CompletionStage;

@SuppressWarnings("SameParameterValue")
@NonNullByDefault
public final class NullReActorSystemRef extends ReActorSystemRef {
    public static final NullReActorSystemRef NULL_REACTOR_SYSTEM_REF = new NullReActorSystemRef(ReActorSystemId.NO_REACTORSYSTEM_ID,
                                                                                                NullDriver.NULL_DRIVER_PROPERTIES,
                                                                                                NullDriver.NULL_DRIVER);

    public NullReActorSystemRef() { /* Required by Externalizable */ }
    private NullReActorSystemRef(ReActorSystemId reActorSystemId, Properties channelProperties,
                                ReActorSystemDriver<NullDriverConfig> reActorSystemDriver) {
        super(reActorSystemDriver, channelProperties, ChannelId.NO_CHANNEL_ID, reActorSystemId);
    }

    @Override
    public <PayloadT extends Serializable> DeliveryStatus publish(ReActorRef src, ReActorRef dst,
                                                                  PayloadT message) {
        return NullDriver.NULL_DRIVER.publish(src, dst, message);
    }

    @Override
    public <PayloadT extends Serializable> CompletionStage<DeliveryStatus> apublish(ReActorRef src, ReActorRef dst,
                                                                                    AckingPolicy ackingPolicy,
                                                                                    PayloadT message) {
        return NullDriver.NULL_DRIVER.apublish(src, dst, ackingPolicy, message);
    }

    @Override
    public ReActorSystemId getReActorSystemId() { return ReActorSystemId.NO_REACTORSYSTEM_ID; }
}
