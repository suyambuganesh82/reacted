/*
 * Copyright (c) 2020 , <Pierre Falda> [ pierre@reacted.io ]
 * All rights reserved.
 *
 * This source code is licensed under the BSD-style license found in the
 * LICENSE file in the root directory of this source tree.
 */

package io.reacted.core.messages.services;

import io.reacted.patterns.NonNullByDefault;
import javax.annotation.concurrent.Immutable;
import java.io.Serializable;

@Immutable
@NonNullByDefault
public class ServiceDiscoveryRequest implements Serializable {
    private final BasicServiceDiscoverySearchFilter searchFilter;
    public ServiceDiscoveryRequest(BasicServiceDiscoverySearchFilter searchFilter) {
        this.searchFilter = searchFilter;
    }

    public BasicServiceDiscoverySearchFilter getSearchFilter() { return searchFilter; }
}
