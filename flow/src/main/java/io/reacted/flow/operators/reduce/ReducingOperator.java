/*
 * Copyright (c) 2021 , <Pierre Falda> [ pierre@reacted.io ]
 * All rights reserved.
 *
 * This source code is licensed under the BSD-style license found in the
 * LICENSE file in the root directory of this source tree.
 */

package io.reacted.flow.operators.reduce;

import com.google.common.collect.ImmutableMap;
import io.reacted.core.reactorsystem.ReActorContext;
import io.reacted.flow.operators.FlowOperator;
import io.reacted.patterns.NonNullByDefault;
import java.io.Serializable;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.function.Function;
import java.util.stream.Collectors;

@NonNullByDefault
public abstract class ReducingOperator<ConfigBuilderT extends ReducingOperatorConfig.Builder<ConfigBuilderT,
                                                                                      ConfigT>,
                                ConfigT extends ReducingOperatorConfig<ConfigBuilderT, ConfigT>>
    extends FlowOperator<ConfigBuilderT, ConfigT> {
    private static final List<Serializable> NO_ELEMENTS = List.of();
    private final Map<Class<? extends Serializable>,
                      Map<? super ReduceKey, List<Serializable>>> storage;
    protected ReducingOperator(ConfigT config) {
        super(config);
        this.storage = config.getKeyExtractors().keySet().stream()
                             .collect(Collectors.toUnmodifiableMap(Function.identity(),
                                                                   type -> new HashMap<>()));
    }

    @Override
    protected CompletionStage<Collection<? extends Serializable>>
    onNext(Serializable input, ReActorContext raCtx) {
        Collection<? extends Serializable> result = List.of();
        Class<? extends Serializable> inputType = input.getClass();
        if (storage.containsKey(inputType)) {
            var keyExtractorForType = getOperatorCfg().getKeyExtractors()
                                                      .get(input.getClass());
            var key = keyExtractorForType.apply(input);
            storage.get(inputType)
                   .computeIfAbsent(key, newKey -> new LinkedList<>())
                   .add(input);
            if (canReduce(key)) {
                var reduceData = getReduceData(key, getOperatorCfg().getReductionRules());
                result = getOperatorCfg().getReducer().apply(reduceData);
            }
        }
        return CompletableFuture.completedStage(result);
    }

    private Map<Class<? extends Serializable>, List<? extends Serializable>>
    getReduceData(ReduceKey key, Map<Class<? extends Serializable>, Long> reductionRules) {
        ImmutableMap.Builder<Class<? extends Serializable>, List<? extends Serializable>> output;
        output = ImmutableMap.builder();
        for(var entry : storage.entrySet()) {
            Class<? extends Serializable> type = entry.getKey();
            Map<? super ReduceKey, List<Serializable>> payloads = entry.getValue();
            var required = reductionRules.get(type).intValue();
            var elements = payloads.get(key).size() == required
                           ? payloads.remove(key)
                           : removeNfromInput(payloads.get(key), required);
            output.put(type, elements);
        }
        return output.build();
    }

    private static List<Serializable> removeNfromInput(List<Serializable> input,
                                                       long howManyToRemove) {
        List<Serializable> output = new ArrayList<>((int)howManyToRemove);
        for(int iter = 0; iter < howManyToRemove; iter++) {
            output.add(input.remove(0));
        }
        return output;
    }
    private boolean canReduce(ReduceKey reduceKey) {
        return getOperatorCfg().getReductionRules().entrySet().stream()
                               .allMatch(typeToNum -> storage.getOrDefault(typeToNum.getKey(),
                                                                           Map.of())
                                                             .getOrDefault(reduceKey, NO_ELEMENTS)
                                                             .size() >= typeToNum.getValue()
                                                                                 .intValue());
    }
}
