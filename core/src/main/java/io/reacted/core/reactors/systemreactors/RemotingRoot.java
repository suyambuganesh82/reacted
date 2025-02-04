/*
 * Copyright (c) 2020 , <Pierre Falda> [ pierre@reacted.io ]
 * All rights reserved.
 *
 * This source code is licensed under the BSD-style license found in the
 * LICENSE file in the root directory of this source tree.
 */

package io.reacted.core.reactors.systemreactors;

import io.reacted.core.config.drivers.ChannelDriverConfig;
import io.reacted.core.drivers.system.RemotingDriver;
import io.reacted.core.messages.reactors.DeliveryStatus;
import io.reacted.core.messages.reactors.ReActorInit;
import io.reacted.core.messages.reactors.ReActorStop;
import io.reacted.core.messages.serviceregistry.DuplicatedPublicationError;
import io.reacted.core.messages.serviceregistry.FilterServiceDiscoveryRequest;
import io.reacted.core.messages.serviceregistry.ReActorSystemChannelIdPublicationRequest;
import io.reacted.core.messages.serviceregistry.RegistryConnectionLost;
import io.reacted.core.messages.serviceregistry.RegistryDriverInitComplete;
import io.reacted.core.messages.serviceregistry.RegistryGateRemoved;
import io.reacted.core.messages.serviceregistry.RegistryGateUpserted;
import io.reacted.core.messages.serviceregistry.RegistryServicePublicationFailed;
import io.reacted.core.messages.serviceregistry.ServiceCancellationRequest;
import io.reacted.core.messages.serviceregistry.ServicePublicationRequest;
import io.reacted.core.messages.serviceregistry.ServiceRegistryNotAvailable;
import io.reacted.core.messages.serviceregistry.SynchronizationWithServiceRegistryComplete;
import io.reacted.core.messages.serviceregistry.SynchronizationWithServiceRegistryRequest;
import io.reacted.core.messages.services.FilterItem;
import io.reacted.core.messages.services.ServiceDiscoveryReply;
import io.reacted.core.reactors.ReActions;
import io.reacted.core.reactorsystem.ReActorContext;
import io.reacted.core.reactorsystem.ReActorSystem;
import io.reacted.core.reactorsystem.ReActorSystemId;

import javax.annotation.concurrent.Immutable;
import java.io.Serializable;
import java.util.Collection;
import java.util.function.Predicate;
import java.util.stream.Collectors;

@Immutable
public class RemotingRoot {
    private final Collection<RemotingDriver<? extends ChannelDriverConfig<?, ?>>> remotingDrivers;
    private final ReActorSystemId localReActorSystem;

    public RemotingRoot(ReActorSystemId localReActorSystem,
                        Collection<RemotingDriver<? extends ChannelDriverConfig<?, ?>>> remotingDrivers) {
        this.remotingDrivers = remotingDrivers;
        this.localReActorSystem = localReActorSystem;
    }

    public ReActions getReActions() {
        return ReActions.newBuilder()
                        .reAct(ReActorInit.class, ReActions::noReAction)
                        .reAct(RegistryDriverInitComplete.class, RemotingRoot::onInitComplete)
                        .reAct(SynchronizationWithServiceRegistryComplete.class, this::onSubscriptionComplete)
                        .reAct(RegistryGateUpserted.class, this::onRegistryGateUpsert)
                        .reAct(RegistryGateRemoved.class, this::onRegistryGateRemoval)
                        .reAct(ServicePublicationRequest.class, RemotingRoot::onPublishService)
                        .reAct(RegistryServicePublicationFailed.class, RemotingRoot::onRegistryServicePublicationFailure)
                        .reAct(ServiceCancellationRequest.class, RemotingRoot::onCancelService)
                        .reAct(FilterServiceDiscoveryRequest.class, RemotingRoot::onFilterServiceDiscoveryRequest)
                        .reAct(RegistryConnectionLost.class, this::onRegistryConnectionLost)
                        .reAct(DuplicatedPublicationError.class, RemotingRoot::onDuplicatedPublicationError)
                        .reAct(ReActorStop.class, RemotingRoot::onStop)
                        .reAct(RemotingRoot::onSpuriousMessage)
                        .build();
    }

    private static void onDuplicatedPublicationError(ReActorContext raCtx,
                                                     DuplicatedPublicationError duplicatedPublicationError) {
        raCtx.logError("CRITIC! Duplicated ReActor System detected. ReActorSystem names must be unique within" +
                       " a cluster. Shutting down reporting driver: {}",
                       raCtx.getSender().getReActorId().getReActorName());
        raCtx.getReActorSystem().stop(raCtx.getSender().getReActorId());
    }
    @SuppressWarnings("EmptyMethod")
    private static void onStop(ReActorContext raCtx, ReActorStop stop) { /* Nothing to do */ }

    private static void onCancelService(ReActorContext raCtx,
                                        ServiceCancellationRequest serviceCancellationRequest) {
        raCtx.getChildren().forEach(serviceRegistryDriver -> serviceRegistryDriver.publish(raCtx.getSelf(),
                                                                                           serviceCancellationRequest));
    }

    private static void onPublishService(ReActorContext raCtx, ServicePublicationRequest publishService) {
        if (raCtx.getChildren().isEmpty()) {
             if (!raCtx.reply(new ServiceRegistryNotAvailable()).isSent()) {
                 raCtx.logError("Unable to make a service discoverable {}",
                                publishService.getServiceProperties());
             }
             return;
        }

        raCtx.getChildren().stream()
             .map(registryDriver -> registryDriver.publish(raCtx.getSelf(), publishService))
             .filter(Predicate.not(DeliveryStatus::isSent))
             .forEach(registryDriver -> raCtx.logError("Unable to deliver service publication request {}",
                                                       publishService.getServiceProperties()));
    }

    private static void onInitComplete(ReActorContext raCtx,
                                       RegistryDriverInitComplete initComplete) {
        raCtx.reply(new SynchronizationWithServiceRegistryRequest());
    }

    private static void onSpuriousMessage(ReActorContext raCtx, Serializable payload) {
        raCtx.logError("Spurious message received", new IllegalStateException(payload.toString()));
    }

    private void onSubscriptionComplete(ReActorContext raCtx,
                                        SynchronizationWithServiceRegistryComplete subCompleted) {
        remotingDrivers.stream()
                       .map(remotingDriver -> new ReActorSystemChannelIdPublicationRequest(localReActorSystem,
                                                                                           remotingDriver.getChannelId(),
                                                                                           remotingDriver.getChannelProperties()))
                       .map(raCtx::reply)
                       .filter(Predicate.not(DeliveryStatus::isSent))
                       .forEach(pubRequest -> raCtx.logError("Unable to publish channel"));
    }

    private static void onRegistryServicePublicationFailure(ReActorContext raCtx,
                                                            RegistryServicePublicationFailed failure) {
        raCtx.logError("Error publishing service {}", failure.getServiceName(), failure.getPublicationError());
    }

    private void onRegistryGateUpsert(ReActorContext raCtx, RegistryGateUpserted upsert) {
        //skip self notifications
        if (!raCtx.getReActorSystem().getLocalReActorSystemId().equals(upsert.getReActorSystemId())) {
            raCtx.logInfo("Gate added in {} : {} -> {}", raCtx.getReActorSystem().getLocalReActorSystemId()
                                                            .getReActorSystemName(),
                          upsert.getChannelId().toString(), upsert.getReActorSystemId().getReActorSystemName());
            raCtx.getReActorSystem().unregisterRoute(upsert.getReActorSystemId(), upsert.getChannelId());
            raCtx.getReActorSystem().registerNewRoute(upsert.getReActorSystemId(), upsert.getChannelId(),
                                                      upsert.getChannelData(), raCtx.getSender());
        }
    }

    private void onRegistryConnectionLost(ReActorContext raCtx, RegistryConnectionLost connectionLost) {
        raCtx.getReActorSystem().flushRemoteGatesForDriver(raCtx.getSender());
    }

    private void onRegistryGateRemoval(ReActorContext raCtx, RegistryGateRemoved removed) {
        if (raCtx.getReActorSystem().getLocalReActorSystemId().equals(removed.getReActorSystem())) {
            //if for any reason we got removed from remove service registry, refresh subscription
            raCtx.getSelf().publish(raCtx.getSender(), new SynchronizationWithServiceRegistryComplete());
            return;
        }
        raCtx.logInfo("Gate removed in {} : {} -> {}",
                      raCtx.getReActorSystem().getLocalReActorSystemId().getReActorSystemName(),
                      removed.getChannelId().toString(), removed.getReActorSystem().getReActorSystemName());
        raCtx.getReActorSystem().unregisterRoute(removed.getReActorSystem(), removed.getChannelId());
    }

    private static void onFilterServiceDiscoveryRequest(ReActorContext raCtx,
                                                        FilterServiceDiscoveryRequest filterThis) {
        var foundServices = filterThis.getServiceDiscoveryResult().stream()
                  .flatMap(filterItem -> ReActorSystem.getRoutedReference(filterItem.serviceGate(),
                                                                          raCtx.getReActorSystem()).stream()
                                                      .map(routedGate -> new FilterItem(routedGate,
                                                                                        filterItem.serviceProperties())))
                  .filter(filterItem -> filterThis.getFilteringRuleToApply().matches(filterItem.serviceProperties(),
                                                                                     filterItem.serviceGate()))
                  .map(FilterItem::serviceGate)
                  .collect(Collectors.toUnmodifiableSet());
        if (!raCtx.reply(new ServiceDiscoveryReply(foundServices)).isSent()) {
            raCtx.logError("Unable to answer with a {}",
                           ServiceDiscoveryReply.class.getSimpleName());
        }
    }
}
