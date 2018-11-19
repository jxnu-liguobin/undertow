/*
 * JBoss, Home of Professional Open Source.
 * Copyright 2014 Red Hat, Inc., and individual contributors
 * as indicated by the @author tags.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 */

package io.undertow.server.protocol.http2;

import java.util.Collections;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import org.xnio.ChannelListener;
import org.xnio.IoUtils;
import org.xnio.StreamConnection;

import io.undertow.UndertowLogger;
import io.undertow.UndertowMessages;
import io.undertow.UndertowOptions;
import io.undertow.xnio.conduits.BytesReceivedStreamSourceConduit;
import io.undertow.xnio.conduits.BytesSentStreamSinkConduit;
import io.undertow.connector.ByteBufferPool;
import io.undertow.connector.PooledByteBuffer;
import io.undertow.connector.UndertowOptionMap;
import io.undertow.xnio.protocols.http2.Http2Channel;
import io.undertow.server.ConnectorStatistics;
import io.undertow.server.ConnectorStatisticsImpl;
import io.undertow.server.DelegateOpenListener;
import io.undertow.server.HttpHandler;


/**
 * Open listener for HTTP2 server
 *
 * @author Stuart Douglas
 */
public final class Http2OpenListener implements ChannelListener<StreamConnection>, DelegateOpenListener {


    private final Set<Http2Channel> connections = Collections.newSetFromMap(new ConcurrentHashMap<>());


    public static final String HTTP2 = "h2";
    @Deprecated
    public static final String HTTP2_14 = "h2-14";

    private final ByteBufferPool bufferPool;
    private final int bufferSize;
    private final ChannelListener<Http2Channel> closeTask = new ChannelListener<Http2Channel>() {
        @Override
        public void handleEvent(Http2Channel channel) {
            connectorStatistics.decrementConnectionCount();
        }
    };

    private volatile HttpHandler rootHandler;

    private volatile UndertowOptionMap undertowOptions;
    private volatile boolean statisticsEnabled;
    private final ConnectorStatisticsImpl connectorStatistics;
    private final String protocol;

    public Http2OpenListener(final ByteBufferPool pool) {
        this(pool, UndertowOptionMap.EMPTY);
    }

    public Http2OpenListener(final ByteBufferPool pool, final UndertowOptionMap undertowOptions) {
        this(pool, undertowOptions, HTTP2);
    }

    public Http2OpenListener(final ByteBufferPool pool, final UndertowOptionMap undertowOptions, String protocol) {
        this.undertowOptions = undertowOptions;
        this.bufferPool = pool;
        PooledByteBuffer buf = pool.allocate();
        this.bufferSize = buf.getBuffer().remaining();
        buf.close();
        connectorStatistics = new ConnectorStatisticsImpl();
        statisticsEnabled = undertowOptions.get(UndertowOptions.ENABLE_STATISTICS, false);
        this.protocol = protocol;
    }

    public void handleEvent(final StreamConnection channel, PooledByteBuffer buffer) {
        if (UndertowLogger.REQUEST_LOGGER.isTraceEnabled()) {
            UndertowLogger.REQUEST_LOGGER.tracef("Opened HTTP/2 connection with %s", channel.getPeerAddress());
        }

        //cool, we have a Http2 connection.
        Http2Channel http2Channel = new Http2Channel(channel, protocol, bufferPool, buffer, false, false, undertowOptions);
        Integer idleTimeout = undertowOptions.get(UndertowOptions.IDLE_TIMEOUT);
        if (idleTimeout != null && idleTimeout > 0) {
            http2Channel.setIdleTimeout(idleTimeout);
        }
        if (statisticsEnabled) {
            channel.getSinkChannel().setConduit(new BytesSentStreamSinkConduit(channel.getSinkChannel().getConduit(), connectorStatistics.sentAccumulator()));
            channel.getSourceChannel().setConduit(new BytesReceivedStreamSourceConduit(channel.getSourceChannel().getConduit(), connectorStatistics.receivedAccumulator()));
            connectorStatistics.incrementConnectionCount();
            http2Channel.addCloseTask(closeTask);
        }

        connections.add(http2Channel);
        http2Channel.addCloseTask(new ChannelListener<Http2Channel>() {
            @Override
            public void handleEvent(Http2Channel channel) {
                connections.remove(channel);
            }
        });
        http2Channel.getReceiveSetter().set(new Http2ReceiveListener(rootHandler, getUndertowOptions(), bufferSize, connectorStatistics));
        http2Channel.resumeReceives();

    }

    @Override
    public ConnectorStatistics getConnectorStatistics() {
        if (statisticsEnabled) {
            return connectorStatistics;
        }
        return null;
    }

    @Override
    public void closeConnections() {
        for (Http2Channel i : connections) {
            IoUtils.safeClose(i);
        }
    }

    @Override
    public HttpHandler getRootHandler() {
        return rootHandler;
    }

    @Override
    public void setRootHandler(final HttpHandler rootHandler) {
        this.rootHandler = rootHandler;
    }

    @Override
    public UndertowOptionMap getUndertowOptions() {
        return undertowOptions;
    }

    @Override
    public void setUndertowOptions(final UndertowOptionMap undertowOptions) {
        if (undertowOptions == null) {
            throw UndertowMessages.MESSAGES.argumentCannotBeNull("undertowOptions");
        }
        this.undertowOptions = undertowOptions;
        statisticsEnabled = undertowOptions.get(UndertowOptions.ENABLE_STATISTICS, false);
    }

    @Override
    public ByteBufferPool getBufferPool() {
        return bufferPool;
    }

    @Override
    public void handleEvent(StreamConnection channel) {
        handleEvent(channel, null);
    }
}
