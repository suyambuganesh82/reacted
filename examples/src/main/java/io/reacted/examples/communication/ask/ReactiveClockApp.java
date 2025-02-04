/*
 * Copyright (c) 2020 , <Pierre Falda> [ pierre@reacted.io ]
 * All rights reserved.
 *
 * This source code is licensed under the BSD-style license found in the
 * LICENSE file in the root directory of this source tree.
 */

package io.reacted.examples.communication.ask;

import io.reacted.core.config.reactors.ReActorConfig;
import io.reacted.core.mailboxes.BoundedMbox;
import io.reacted.core.reactors.ReActions;
import io.reacted.core.runtime.Dispatcher;
import io.reacted.core.typedsubscriptions.TypedSubscription;
import io.reacted.examples.ExampleUtils;

import java.io.FileNotFoundException;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;

public class ReactiveClockApp {
    public static void main(String[] args) throws FileNotFoundException {
        var reActorSystem = ExampleUtils.getDefaultInitedReActorSystem(ReactiveClockApp.class.getSimpleName());

        var reactiveClockReactions = ReActions.newBuilder()
                                              .reAct(TimeRequest.class,
                                                     (raCtx, request) -> raCtx.getSender()
                                                                              .publish(Instant.now()))
                                              //For any other message type simply ignore the message
                                              .reAct((ctx, any) -> {
                                              })
                                              .build();
        var reactiveClockConfig = ReActorConfig.newBuilder()
                                               .setTypedSubscriptions(TypedSubscription.NO_SUBSCRIPTIONS)
                                               //Accept at maximum 5 messages in the mailbox at the same time,
                                               //drop the ones in excess causing the delivery to fail
                                               .setMailBoxProvider(ctx -> new BoundedMbox(5))
                                               .setReActorName("Reactive Clock")
                                               .setDispatcherName(
                                                   Dispatcher.DEFAULT_DISPATCHER_NAME)
                                               .build();
        var reactiveClock = reActorSystem.spawn(reactiveClockReactions, reactiveClockConfig)
                                         .orElseSneakyThrow();
        //Note: we do not need another reactor to intercept the answer
        reactiveClock.ask(new TimeRequest(), Instant.class, "What's the time?")
                     //Ignore the exception, it's just an example
                     .thenAccept(time -> System.out.printf("It's %s%n",
                                                           ZonedDateTime.ofInstant(time, ZoneId.systemDefault())))
                     .thenAcceptAsync(nullValue -> reActorSystem.shutDown());
    }
}
