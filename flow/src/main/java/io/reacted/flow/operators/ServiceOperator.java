/*
 * Copyright (c) 2021 , <Pierre Falda> [ pierre@reacted.io ]
 * All rights reserved.
 *
 * This source code is licensed under the BSD-style license found in the
 * LICENSE file in the root directory of this source tree.
 */

package io.reacted.flow.operators;

import io.reacted.core.mailboxes.BackpressuringMbox;
import io.reacted.core.messages.reactors.ReActorInit;
import io.reacted.core.messages.reactors.ReActorStop;
import io.reacted.core.messages.services.ServiceDiscoverySearchFilter;
import io.reacted.core.reactors.ReActions;
import io.reacted.core.reactorsystem.ReActorContext;
import io.reacted.core.reactorsystem.ReActorRef;
import io.reacted.core.utils.ReActedUtils;
import io.reacted.flow.operators.messages.RefreshOperatorRequest;
import io.reacted.patterns.AsyncUtils;
import io.reacted.patterns.NonNullByDefault;
import java.io.Serializable;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

@NonNullByDefault
public class ServiceOperator extends FlowOperator<ServiceOperatorConfig.Builder,
                                                  ServiceOperatorConfig> {
  private final ReActions reActions;
  private final ExecutorService executorService;
  private final boolean shallStopExecutorService;
  @Nullable
  private ScheduledFuture<?> serviceRefreshTask;
  @SuppressWarnings("NotNullFieldNotInitialized")
  private ReActorRef service;

  protected ServiceOperator(ServiceOperatorConfig config) {
    super(config);
    this.executorService = config.getExecutorService()
                                 .orElseGet(Executors::newSingleThreadExecutor);
    this.shallStopExecutorService = config.getExecutorService().isEmpty();
    this.reActions = ReActions.newBuilder()
                              .from(super.getReActions())
                              .reAct(ReActorInit.class, this::onServiceOperatorInit)
                              .reAct(ReActorStop.class, this::onServiceOperatorStop)
                              .reAct(RefreshServiceRequest.class,
                                     (raCtx, refreshServiceRequest) -> onRefreshServiceRequest(raCtx))
                              .reAct(config.getServiceReplyType(), this::onReply)
                              .build();
  }

  @Nonnull
  @Override
  public ReActions getReActions() { return reActions; }

  @Override
  protected final CompletionStage<Collection<? extends Serializable>>
  onNext(Serializable input, ReActorContext raCtx) {

    return AsyncUtils.asyncForeach(request -> service.atell(raCtx.getSelf(), request),
                                   getOperatorCfg().getToServiceRequests().apply(input).iterator(),
                                   error -> onFailedDelivery(error, raCtx, input), executorService)
                     .thenAccept(noVal -> raCtx.getMbox().request(1))
                     .thenApply(noVal -> FlowOperator.NO_OUTPUT);
  }

  private void onServiceOperatorInit(ReActorContext raCtx, ReActorInit init) {
    super.onInit(raCtx, init);
    BackpressuringMbox.toBackpressuringMailbox(raCtx.getMbox())
                      .ifPresent(mbox -> mbox.addNonDelayedMessageTypes(Set.of(RefreshServiceRequest.class)));
    this.serviceRefreshTask = raCtx.getReActorSystem()
         .getSystemSchedulingService()
         .scheduleWithFixedDelay(() -> ReActedUtils.ifNotDelivered(raCtx.selfTell(new RefreshServiceRequest()),
                                                                   error -> raCtx.logError("Unable to request refresh of service operators")),
                                 0, getOperatorCfg().getServiceRefreshPeriod()
                                                    .toNanos(), TimeUnit.NANOSECONDS);
  }

  private void onServiceOperatorStop(ReActorContext raCtx, ReActorStop stop) {
    super.onStop(raCtx, stop);
    if (serviceRefreshTask != null) {
      serviceRefreshTask.cancel(true);
    }
    if (shallStopExecutorService) {
      executorService.shutdownNow();
    }
  }

  private void onRefreshServiceRequest(ReActorContext raCtx) {

  }
  private <PayloadT extends Serializable> void onReply(ReActorContext raCtx, PayloadT reply) {
    propagate(CompletableFuture.supplyAsync(() -> getOperatorCfg().getFromServiceResponse()
                                                                  .apply(reply), executorService),
              reply, raCtx);
  }

  private static class RefreshServiceRequest implements Serializable {
    @Override
    public String toString() {
      return "RefreshServiceRequest{}";
    }
  }

  private static class RefreshServiceUpdate implements Serializable {
    private final Collection<ReActorRef> serviceGates;
    private RefreshServiceUpdate(Collection<ReActorRef> serviceGates) {
      this.serviceGates = serviceGates;
    }
  }
}
