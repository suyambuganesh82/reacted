/*
 * Copyright (c) 2020 , <Pierre Falda> [ pierre@reacted.io ]
 * All rights reserved.
 *
 * This source code is licensed under the BSD-style license found in the
 * LICENSE file in the root directory of this source tree.
 */

package io.reacted.examples.communication.apublish;

import io.reacted.core.config.reactors.ReActorConfig;
import io.reacted.core.config.reactors.ServiceConfig;
import io.reacted.core.drivers.local.SystemLocalDrivers;
import io.reacted.core.messages.reactors.ReActorInit;
import io.reacted.core.messages.services.BasicServiceDiscoverySearchFilter;
import io.reacted.core.messages.services.ServiceDiscoveryRequest;
import io.reacted.core.reactors.ReActions;
import io.reacted.core.reactors.ReActor;
import io.reacted.core.reactorsystem.ReActorContext;
import io.reacted.core.reactorsystem.ReActorRef;
import io.reacted.core.reactorsystem.ReActorSystem;
import io.reacted.core.services.LoadBalancingPolicies;
import io.reacted.core.typedsubscriptions.TypedSubscription;
import io.reacted.drivers.channels.grpc.GrpcDriver;
import io.reacted.drivers.channels.grpc.GrpcDriverConfig;
import io.reacted.drivers.serviceregistries.zookeeper.ZooKeeperDriver;
import io.reacted.drivers.serviceregistries.zookeeper.ZooKeeperDriverConfig;
import io.reacted.examples.ExampleUtils;

import javax.annotation.Nonnull;
import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.Properties;
import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.TimeUnit;

class MessageStormApp {
    public static void main(String[] args) throws InterruptedException {
        Properties zooKeeperProps = new Properties();
        var clientSystemCfg = ExampleUtils.getDefaultReActorSystemCfg("Client",
                                                                   SystemLocalDrivers.DIRECT_COMMUNICATION,
                                                                   //SystemLocalDrivers.getDirectCommunicationSimplifiedLoggerDriver(System.err),
                                                                   List.of(new ZooKeeperDriver(ZooKeeperDriverConfig.newBuilder()
                                                                                                                    .setTypedSubscriptions(TypedSubscription.LOCAL.forType(ServiceDiscoveryRequest.class))
                                                                                                                    .setSessionTimeout(Duration.ofSeconds(10))
                                                                                                                    .setReActorName("ZooKeeperDriver")
                                                                                                                    .setAsyncExecutor(new ForkJoinPool())
                                                                                                                    .setServiceRegistryProperties(zooKeeperProps)
                                                                                                                    .build())),
                                                                   List.of(new GrpcDriver(GrpcDriverConfig.newBuilder()
                                                                                                          .setHostName("localhost")
                                                                                                          .setPort(12345)
                                                                                                          .setChannelName("TestChannel")
                                                                                                          .build())));
        var serverSystemCfg = ExampleUtils.getDefaultReActorSystemCfg("Server",
                                                                   //SystemLocalDrivers.DIRECT_COMMUNICATION,
                                                                      SystemLocalDrivers.getDirectCommunicationSimplifiedLoggerDriver(System.err),
                                                                   List.of(new ZooKeeperDriver(ZooKeeperDriverConfig.newBuilder()
                                                                                                                    .setTypedSubscriptions(TypedSubscription.LOCAL.forType(ServiceDiscoveryRequest.class))
                                                                                                                    .setSessionTimeout(Duration.ofSeconds(10))
                                                                                                                    .setAsyncExecutor(new ForkJoinPool(2))
                                                                                                                    .setReActorName("ZooKeeperDriver")
                                                                                                                    .setServiceRegistryProperties(zooKeeperProps)
                                                                                                                    .build())),
                                                                   List.of(new GrpcDriver(GrpcDriverConfig.newBuilder()
                                                                                                          .setHostName("localhost")
                                                                                                          .setPort(54321)
                                                                                                          .setChannelName("TestChannel")
                                                                                                          .build())));
        var clientSystem = new ReActorSystem(clientSystemCfg).initReActorSystem();
        var serverSystem = new ReActorSystem(serverSystemCfg).initReActorSystem();

        var serverReActor = serverSystem.spawnService(ServiceConfig.newBuilder()
                                                                   .setRouteeProvider(ServerReActor::new)
                                                                   .setLoadBalancingPolicy(LoadBalancingPolicies.ROUND_ROBIN)
                                                                   .setReActorName("ServerService")
                                                                   .setRouteesNum(1)
                                                                   .setIsRemoteService(true)
                                                                   .build()).orElseSneakyThrow();
        TimeUnit.SECONDS.sleep(10);
        var remoteService = clientSystem.serviceDiscovery(BasicServiceDiscoverySearchFilter.newBuilder()
                                                                                           .setServiceName("ServerService")
                                                                                           .build())
                                        .toCompletableFuture().join();

        if (!remoteService.getServiceGates().isEmpty()) {
            var serviceGate = remoteService.getServiceGates()
                                           .iterator().next();

            var clientReActor = clientSystem.spawn(new ClientReActor(serviceGate, 10))
                                            .orElseSneakyThrow();
            clientSystem.getReActorCtx(clientReActor.getReActorId())
                        .getHierarchyTermination()
                        .toCompletableFuture()
                        .join();
        } else {
            System.err.println("Unable to discover service, exiting");
        }
        //The game is finished, shut down
        clientSystem.shutDown();
        serverSystem.shutDown();
    }

    private static class ServerReActor implements ReActor {
        @Nonnull
        @Override
        public ReActions getReActions() { return ReActions.NO_REACTIONS; }

        @Nonnull
        @Override
        public ReActorConfig getConfig() {
            return ReActorConfig.newBuilder()
                                .setReActorName(ServerReActor.class.getSimpleName())
                                .build();
        }
    }

    private static class ClientReActor implements ReActor {
        private final ReActorRef serverReference;
        private int missingCycles;
        private long testStart;
        private ClientReActor(ReActorRef serverReference, int cycles) {
            this.serverReference = Objects.requireNonNull(serverReference);
            this.missingCycles = cycles;
        }

        @Nonnull
        @Override
        public ReActorConfig getConfig() {
            return ReActorConfig.newBuilder()
                    .setReActorName(ClientReActor.class.getSimpleName())
                    .build();
        }

        @Nonnull
        @Override
        public ReActions getReActions() {
            return ReActions.newBuilder()
                    .reAct(ReActorInit.class, (ctx, init) -> onInit(ctx))
                    .reAct(NextRecord.class, (ctx, nextRecord) -> onNextRecord(ctx))
                    .reAct(ReActions::noReAction)
                    .build();
        }

        private void onInit(ReActorContext raCtx) {
            this.testStart = System.nanoTime();
            raCtx.selfPublish(NextRecord.INSTANCE);
        }

        private void onNextRecord(ReActorContext raCtx) {
            if (missingCycles == 0) {
                System.err.printf("Finished Storm. Time %s%n",
                                  Duration.ofNanos(System.nanoTime() - testStart));
                raCtx.stop();
            } else {
                serverReference.apublish(String.format("Async Message %d", missingCycles--))
                               .toCompletableFuture()
                               .handle((deliveryStatus, error) -> {
                                   if (error != null) {
                                       raCtx.stop();
                                       error.printStackTrace();
                                   } else {
                                       if (deliveryStatus.isDelivered()) {
                                           raCtx.selfPublish(NextRecord.INSTANCE);
                                       } else {
                                           System.err.printf("Unable to deliver loop message: %s%n",
                                                             deliveryStatus);
                                           raCtx.stop();
                                       }
                                   }
                                   return null;
                               });
            }
        }
        private enum NextRecord { INSTANCE }
    }
}
