/*
 * Copyright (c) 2021 , <Pierre Falda> [ pierre@reacted.io ]
 * All rights reserved.
 *
 * This source code is licensed under the BSD-style license found in the
 * LICENSE file in the root directory of this source tree.
 */

package io.reacted.flow.operators.service;

import io.reacted.core.messages.services.ServiceDiscoverySearchFilter;
import io.reacted.core.reactorsystem.ReActorRef;
import io.reacted.core.services.GateSelectorPolicies;
import io.reacted.flow.operators.FlowOperatorConfig;
import io.reacted.flow.operators.service.ServiceOperatorConfig.Builder;
import io.reacted.patterns.NonNullByDefault;
import io.reacted.patterns.ObjectUtils;
import java.io.Serializable;
import java.time.Duration;
import java.util.Collection;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ExecutorService;
import java.util.function.Function;
import javax.annotation.Nullable;

@NonNullByDefault
public class ServiceOperatorConfig extends FlowOperatorConfig<Builder, ServiceOperatorConfig> {
  public static final Duration DEFAULT_SERVICE_REFRESH_PERIOD = Duration.ofMinutes(1);
  public static final Function<Serializable, Collection<? extends Serializable>> IDENTITY = List::of;
  private final Function<Serializable, Collection<? extends Serializable>> toServiceRequests;
  private final Function<Serializable, Collection<? extends Serializable>> fromServiceResponse;
  private final Class<? extends Serializable> serviceReplyType;
  private final ServiceDiscoverySearchFilter serviceSearchFilter;
  private final Function<Collection<ReActorRef>, Optional<ReActorRef>> gateSelector;
  private final Builder builder;

  private final Duration serviceRefreshPeriod;
  @Nullable
  private final ExecutorService executorService;
  private ServiceOperatorConfig(Builder builder) {
    super(builder);
    this.serviceReplyType = Objects.requireNonNull(builder.serviceReplyType,
                                                   "Reply type cannot be null");
    this.toServiceRequests = Objects.requireNonNull(builder.toServiceRequests,
                                                    "Input mapper cannot be null");
    this.fromServiceResponse = Objects.requireNonNull(builder.fromServiceResponse,
                                                      "Output mapper cannot be null");
    this.serviceSearchFilter = Objects.requireNonNull(builder.serviceSearchFilter,
                                                      "Service search filter cannot be null");
    this.gateSelector = Objects.requireNonNull(builder.gateSelector,
                                               "Gate selector cannot be null");
    this.serviceRefreshPeriod = ObjectUtils.checkNonNullPositiveTimeInterval(builder.serviceRefreshPeriod);
    this.executorService = builder.executorService;
    this.builder = builder;
  }

  public Function<Serializable, Collection<? extends Serializable>> getToServiceRequests() {
    return toServiceRequests;
  }

  public Function<Serializable, Collection<? extends Serializable>> getFromServiceResponse() {
    return fromServiceResponse;
  }

  public Class<? extends Serializable> getServiceReplyType() {
    return serviceReplyType;
  }

  public ServiceDiscoverySearchFilter getServiceSearchFilter() {
    return serviceSearchFilter;
  }

  public Function<Collection<ReActorRef>, Optional<ReActorRef>> getGateSelector() {
    return gateSelector;
  }

  public Optional<ExecutorService> getExecutorService() {
    return Optional.ofNullable(executorService);
  }

  public Duration getServiceRefreshPeriod() { return serviceRefreshPeriod; }

  @Override
  public Builder toBuilder() { return builder; }

  public static Builder newBuilder() { return new Builder(); }
  public static class Builder extends FlowOperatorConfig.Builder<Builder, ServiceOperatorConfig> {
    private Function<Serializable, Collection<? extends Serializable>> toServiceRequests = IDENTITY;
    private Function<Serializable, Collection<? extends Serializable>> fromServiceResponse = IDENTITY;
    @SuppressWarnings("NotNullFieldNotInitialized")
    private Class<? extends Serializable> serviceReplyType;
    @SuppressWarnings("NotNullFieldNotInitialized")
    private ServiceDiscoverySearchFilter serviceSearchFilter;
    private Function<Collection<ReActorRef>, Optional<ReActorRef>> gateSelector = GateSelectorPolicies.RANDOM_GATE;
    @Nullable
    private ExecutorService executorService;

    private Duration serviceRefreshPeriod = DEFAULT_SERVICE_REFRESH_PERIOD;
    private Builder() { super.setRouteeProvider(ServiceOperator::new); }

    public final Builder setServiceReplyType(Class<? extends Serializable> serviceReplyType) {
      this.serviceReplyType = serviceReplyType;
      return this;
    }

    public final Builder setToServiceRequest(Function<Serializable, Collection<? extends Serializable>>
                                           toServiceRequests) {
      this.toServiceRequests = toServiceRequests;
      return this;
    }

    public final Builder setFromServiceResponse(Function<Serializable, Collection<? extends Serializable>>
                                              fromServiceResponse) {
      this.fromServiceResponse = fromServiceResponse;
      return this;
    }

    public final Builder setServiceFilter(ServiceDiscoverySearchFilter serviceSearchFilter) {
      this.serviceSearchFilter = serviceSearchFilter;
      return this;
    }

    public final Builder setGateSelector(Function<Collection<ReActorRef>, Optional<ReActorRef>>
                                   gateSelector) {
      this.gateSelector = gateSelector;
      return this;
    }

    public final Builder setExecutor(ExecutorService executor) {
      this.executorService = executor;
      return this;
    }

    public final Builder setServiceRefreshPeriod(Duration serviceRefreshPeriod) {
      this.serviceRefreshPeriod = serviceRefreshPeriod;
      return this;
    }

    @Override
    public ServiceOperatorConfig build() {
      return new ServiceOperatorConfig(this);
    }
  }
}
