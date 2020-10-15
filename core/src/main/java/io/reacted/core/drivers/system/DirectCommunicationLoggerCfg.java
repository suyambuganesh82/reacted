/*
 * Copyright (c) 2020 , <Pierre Falda> [ pierre@reacted.io ]
 * All rights reserved.
 *
 * This source code is licensed under the BSD-style license found in the
 * LICENSE file in the root directory of this source tree.
 */

package io.reacted.core.drivers.system;

import io.reacted.core.config.drivers.ChannelDriverCfg;
import io.reacted.patterns.NonNullByDefault;

import java.util.Objects;

@NonNullByDefault
public class DirectCommunicationLoggerCfg extends ChannelDriverCfg<DirectCommunicationLoggerCfg.Builder,
                                                                   DirectCommunicationLoggerCfg> {
    private final String logFilePath;
    private DirectCommunicationLoggerCfg(Builder builder) {
        super(builder);
        this.logFilePath = Objects.requireNonNull(builder.logFilePath);
    }

    public String getLogFilePath() { return logFilePath; }

    public static DirectCommunicationLoggerCfg.Builder newBuilder() { return new Builder(); }

    public static class Builder extends ChannelDriverCfg.Builder<DirectCommunicationLoggerCfg.Builder,
            DirectCommunicationLoggerCfg> {
        @SuppressWarnings("NotNullFieldNotInitialized")
        private String logFilePath;
        private Builder() { }

        public Builder setLogFilePath(String logFilePath) {
            this.logFilePath = logFilePath;
            return this;
        }
        @Override
        public DirectCommunicationLoggerCfg build() {
            return new DirectCommunicationLoggerCfg(this);
        }
    }
}