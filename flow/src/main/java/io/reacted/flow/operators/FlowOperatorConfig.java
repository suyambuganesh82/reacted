/*
 * Copyright (c) 2021 , <Pierre Falda> [ pierre@reacted.io ]
 * All rights reserved.
 *
 * This source code is licensed under the BSD-style license found in the
 * LICENSE file in the root directory of this source tree.
 */

package io.reacted.flow.operators;

import io.reacted.core.config.reactors.ReActorConfig;
import io.reacted.core.config.reactors.ReActorServiceConfig;
import io.reacted.core.messages.services.ServiceDiscoverySearchFilter;
import io.reacted.core.reactors.ReActor;
import io.reacted.core.reactorsystem.ReActorRef;
import io.reacted.patterns.NonNullByDefault;
import io.reacted.patterns.UnChecked;
import java.io.Serializable;
import java.util.Collection;
import java.util.List;
import java.util.Objects;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.function.Supplier;
import java.util.stream.Stream;
import javax.annotation.concurrent.Immutable;

@NonNullByDefault
@Immutable
public abstract class FlowOperatorConfig<BuilderT extends ReActorServiceConfig.Builder<BuilderT, BuiltT>,
                                         BuiltT extends ReActorServiceConfig<BuilderT, BuiltT>>
    extends ReActorServiceConfig<BuilderT, BuiltT> {
  public static final Predicate<Serializable> NO_FILTERING = element -> true;
  public static final Collection<ServiceDiscoverySearchFilter> NO_OUTPUT = List.of();
  public static final Collection<Stream<? extends Serializable>> NO_INPUT_STREAMS = List.of();
  private final Predicate<Serializable> ifPredicate;
  private final Collection<ServiceDiscoverySearchFilter> ifPredicateOutputOperators;
  private final Collection<ServiceDiscoverySearchFilter> thenElseOutputOperators;
  private final Collection<Stream<? extends Serializable>> inputStreams;
  private final ReActorConfig routeeConfig;
  protected FlowOperatorConfig(Builder<BuilderT, BuiltT> builder) {
    super(builder);
    this.routeeConfig = Objects.requireNonNull(builder.operatorRouteeCfg,
                                               "Operator routee config cannot be null");
    this.ifPredicate = Objects.requireNonNull(builder.ifPredicate, "If predicate cannot be null");
    this.ifPredicateOutputOperators = Objects.requireNonNull(builder.ifPredicateOutputOperators,
                                                             "Output filters for if predicate cannot be null");
    this.thenElseOutputOperators = Objects.requireNonNull(builder.thenElseOutputOperators,
                                                          "Output filters if predicate is false cannot be null");
    this.inputStreams = Objects.requireNonNull(builder.inputStreams, "Input Streams cannot be null");
  }

  public Predicate<Serializable> getIfPredicate() {
    return ifPredicate;
  }

  public Collection<ServiceDiscoverySearchFilter> getIfPredicateOutputOperators() {
    return ifPredicateOutputOperators;
  }

  public Collection<ServiceDiscoverySearchFilter> getThenElseOutputOperators() {
    return thenElseOutputOperators;
  }

  public Collection<Stream<? extends Serializable>> getInputStreams() { return inputStreams; }

  public ReActorConfig getRouteeConfig() { return routeeConfig; }

  public abstract static class Builder<BuilderT, BuiltT> extends ReActorServiceConfig.Builder<BuilderT, BuiltT> {
    private Collection<ServiceDiscoverySearchFilter> ifPredicateOutputOperators = NO_OUTPUT;
    private Collection<ServiceDiscoverySearchFilter> thenElseOutputOperators = NO_OUTPUT;
    private Predicate<Serializable> ifPredicate = NO_FILTERING;
    private Collection<Stream<? extends Serializable>> inputStreams = NO_INPUT_STREAMS;
    private ReActorConfig operatorRouteeCfg = ReActorConfig.newBuilder()
                                                           .setReActorName("ROUTEE")
                                                           .build();
    protected Builder() { /* No implementation required */ }

    public final BuilderT setIfOutputPredicate(Predicate<Serializable> ifPredicate) {
      this.ifPredicate = ifPredicate;
      return getThis();
    }

    public final BuilderT setIfOutputFilter(ServiceDiscoverySearchFilter ifPredicateOutputOperator) {
      return setIfOutputFilter(List.of(ifPredicateOutputOperator));
    }

    public final BuilderT setIfOutputFilter(Collection<ServiceDiscoverySearchFilter> ifPredicateOutputOperators) {
      this.ifPredicateOutputOperators = ifPredicateOutputOperators;
      return getThis();
    }

    public final BuilderT setThenElseOutputFilter(ServiceDiscoverySearchFilter thenElseOutputOperator) {
      return setThenElseOutputFilter(List.of(thenElseOutputOperator));
    }

    public final BuilderT setThenElseOutputFilter(Collection<ServiceDiscoverySearchFilter> thenElseOutputOperators) {
      this.thenElseOutputOperators = thenElseOutputOperators;
      return getThis();
    }

    public final BuilderT setInputStreams(Collection<Stream<? extends Serializable>> inputStreams) {
      this.inputStreams = inputStreams;
      return getThis();
    }

    public final BuilderT setOperatorRouteeCfg(ReActorConfig operatorRouteeCfg) {
      this.operatorRouteeCfg = operatorRouteeCfg;
      return getThis();
    }
  }
}
