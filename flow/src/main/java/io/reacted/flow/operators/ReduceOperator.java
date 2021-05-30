/*
 * Copyright (c) 2020 , <Pierre Falda> [ pierre@reacted.io ]
 * All rights reserved.
 *
 * This source code is licensed under the BSD-style license found in the
 * LICENSE file in the root directory of this source tree.
 */

package io.reacted.flow.operators;

import io.reacted.core.config.reactors.ReActorConfig;
import io.reacted.core.reactorsystem.ReActorContext;
import io.reacted.core.reactorsystem.ReActorRef;
import io.reacted.core.reactorsystem.ReActorSystem;
import io.reacted.patterns.NonNullByDefault;

import io.reacted.patterns.Try;
import java.io.Serializable;
import java.util.Collection;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.function.Function;
import java.util.stream.Collectors;

@NonNullByDefault
public class ReduceOperator extends FlowOperator {
    private final Map<Class<? extends Serializable>, List<Serializable>> storage;
    private final Map<Class<? extends Serializable>, Long> typeToRequiredForMerge;
    private final Function<Map<Class<? extends Serializable>, List<? extends Serializable>>,
                           Collection<? extends Serializable>> reducer;
    private ReduceOperator(Collection<Class<? extends Serializable>> dataTypesToBeReduced,
                           Function<Map<Class<? extends Serializable>, List<? extends Serializable>>,
                               Collection<? extends Serializable>> reducer) {
        this.reducer = reducer;
        this.storage = dataTypesToBeReduced.stream()
                                           .collect(Collectors.toUnmodifiableMap(Function.identity(),
                                                                                 dataType -> new LinkedList<>()));
        this.typeToRequiredForMerge = Map.copyOf(dataTypesToBeReduced.stream()
                                                                     .collect(Collectors.groupingBy(Function.identity(),
                                                                                                    Collectors.counting())));
    }

    public static CompletionStage<Try<ReActorRef>>
    of(ReActorSystem localReActorSystem, ReActorConfig operatorCfg,
       Collection<Class<? extends Serializable>> dataTypesToBeMerged,
       Function<Map<Class<? extends Serializable>, List<? extends Serializable>>,
                Collection<? extends Serializable>> reducer) {
        return CompletableFuture.completedStage(localReActorSystem.spawn(new ReduceOperator(dataTypesToBeMerged,
                                                                                            reducer),
                                                                         operatorCfg));
    }
    @Override
    protected CompletionStage<Collection<? extends Serializable>>
    onNext(Serializable input, ReActorContext raCtx) {
        if (typeToRequiredForMerge.containsKey(input.getClass())) {
            storage.get(input.getClass()).add(input);
            if (canReduce()) {
                return CompletableFuture.completedStage(reducer.apply(retrieveReduceData()));
            }
        }
        return CompletableFuture.completedStage(List.of());
    }

    private Map<Class<? extends Serializable>, List<? extends Serializable>> retrieveReduceData() {
        return typeToRequiredForMerge.entrySet().stream()
                              .collect(Collectors.toMap(Entry::getKey,
                                                        entry -> removeNfromInput(storage.get(entry.getKey()),
                                                                                  entry.getValue()),
                                                        (f, s) -> { throw new UnsupportedOperationException(); }));
    }

    private boolean canReduce() {
        return typeToRequiredForMerge.entrySet().stream()
                              .allMatch(entry -> entry.getValue() == storage.get(entry.getKey())
                                                                            .size());
    }

    private static List<Serializable> removeNfromInput(List<Serializable> input, long n) {
        var output = new LinkedList<Serializable>();
        for(int removedNum = 0; removedNum < n; removedNum++) {
            output.add(input.remove(0));
        }
        return output;
    }
}
