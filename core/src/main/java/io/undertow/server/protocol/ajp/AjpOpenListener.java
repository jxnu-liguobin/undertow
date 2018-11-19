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

package io.undertow.server.protocol.ajp;

import static io.undertow.UndertowOptions.DECODE_URL;
import static io.undertow.UndertowOptions.URL_CHARSET;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import org.xnio.IoUtils;
import org.xnio.Options;
import org.xnio.StreamConnection;

import io.undertow.UndertowLogger;
import io.undertow.UndertowMessages;
import io.undertow.UndertowOptions;
import io.undertow.xnio.conduits.BytesReceivedStreamSourceConduit;
import io.undertow.xnio.conduits.BytesSentStreamSinkConduit;
import io.undertow.conduits.ReadTimeoutStreamSourceConduit;
import io.undertow.conduits.WriteTimeoutStreamSinkConduit;
import io.undertow.connector.ByteBufferPool;
import io.undertow.connector.PooledByteBuffer;
import io.undertow.connector.UndertowOptionMap;
import io.undertow.server.ConnectorStatistics;
import io.undertow.server.ConnectorStatisticsImpl;
import io.undertow.server.HttpHandler;
import io.undertow.server.OpenListener;
import io.undertow.server.ServerConnection;
import io.undertow.xnio.conduits.IdleTimeoutConduit;

/**
 * @author Stuart Douglas
 */
public class AjpOpenListener implements OpenListener {

    private final Set<AjpServerConnection> connections = Collections.newSetFromMap(new ConcurrentHashMap<>());

    private final ByteBufferPool bufferPool;
    private final int bufferSize;

    private volatile String scheme;

    private volatile HttpHandler rootHandler;

    private volatile UndertowOptionMap undertowOptions;

    private volatile AjpRequestParser parser;

    private volatile boolean statisticsEnabled;
    private final ConnectorStatisticsImpl connectorStatistics;

    private final ServerConnection.CloseListener closeListener = new ServerConnection.CloseListener() {
        @Override
        public void closed(ServerConnection connection) {
            connectorStatistics.decrementConnectionCount();
        }
    };

    public AjpOpenListener(final ByteBufferPool pool) {
        this(pool, UndertowOptionMap.EMPTY);
    }

    public AjpOpenListener(final ByteBufferPool pool, final UndertowOptionMap undertowOptions) {
        this.undertowOptions = undertowOptions;
        this.bufferPool = pool;
        PooledByteBuffer buf = pool.allocate();
        this.bufferSize = buf.getBuffer().remaining();
        buf.close();
        parser = new AjpRequestParser(undertowOptions.get(URL_CHARSET, StandardCharsets.UTF_8.name()), undertowOptions.get(DECODE_URL, true), undertowOptions.get(UndertowOptions.MAX_PARAMETERS, UndertowOptions.DEFAULT_MAX_PARAMETERS), undertowOptions.get(UndertowOptions.MAX_HEADERS, UndertowOptions.DEFAULT_MAX_HEADERS), undertowOptions.get(UndertowOptions.ALLOW_ENCODED_SLASH, false), undertowOptions.get(UndertowOptions.ALLOW_UNESCAPED_CHARACTERS_IN_URL, false));
        connectorStatistics = new ConnectorStatisticsImpl();
        statisticsEnabled = undertowOptions.get(UndertowOptions.ENABLE_STATISTICS, false);
    }

    @Override
    public void handleEvent(final StreamConnection channel) {
        if (UndertowLogger.REQUEST_LOGGER.isTraceEnabled()) {
            UndertowLogger.REQUEST_LOGGER.tracef("Opened connection with %s", channel.getPeerAddress());
        }

        //set read and write timeouts
        try {
            Integer readTimeout = channel.getOption(Options.READ_TIMEOUT);
            Integer idle = undertowOptions.get(UndertowOptions.IDLE_TIMEOUT);
            if (idle != null) {
                IdleTimeoutConduit conduit = new IdleTimeoutConduit(channel);
                channel.getSourceChannel().setConduit(conduit);
                channel.getSinkChannel().setConduit(conduit);
            }
            if (readTimeout != null && readTimeout > 0) {
                channel.getSourceChannel().setConduit(new ReadTimeoutStreamSourceConduit(channel.getSourceChannel().getConduit(), channel, this));
            }
            Integer writeTimeout = channel.getOption(Options.WRITE_TIMEOUT);
            if (writeTimeout != null && writeTimeout > 0) {
                channel.getSinkChannel().setConduit(new WriteTimeoutStreamSinkConduit(channel.getSinkChannel().getConduit(), channel, this));
            }
        } catch (IOException e) {
            IoUtils.safeClose(channel);
            UndertowLogger.REQUEST_IO_LOGGER.ioException(e);
        }
        if (statisticsEnabled) {
            channel.getSinkChannel().setConduit(new BytesSentStreamSinkConduit(channel.getSinkChannel().getConduit(), connectorStatistics.sentAccumulator()));
            channel.getSourceChannel().setConduit(new BytesReceivedStreamSourceConduit(channel.getSourceChannel().getConduit(), connectorStatistics.receivedAccumulator()));
            connectorStatistics.incrementConnectionCount();
        }

        AjpServerConnection connection = new AjpServerConnection(channel, bufferPool, rootHandler, undertowOptions, bufferSize);
        AjpReadListener readListener = new AjpReadListener(connection, scheme, parser, statisticsEnabled ? connectorStatistics : null);
        if (statisticsEnabled) {
            connection.addCloseListener(closeListener);
        }
        connection.setAjpReadListener(readListener);

        connections.add(connection);
        connection.addCloseListener(new ServerConnection.CloseListener() {
            @Override
            public void closed(ServerConnection c) {
                connections.remove(connection);
            }
        });


        readListener.startRequest();
        channel.getSourceChannel().setReadListener(readListener);
        readListener.handleEvent(channel.getSourceChannel());
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
        parser = new AjpRequestParser(undertowOptions.get(URL_CHARSET, StandardCharsets.UTF_8.name()), undertowOptions.get(DECODE_URL, true), undertowOptions.get(UndertowOptions.MAX_PARAMETERS, UndertowOptions.DEFAULT_MAX_PARAMETERS), undertowOptions.get(UndertowOptions.MAX_HEADERS, UndertowOptions.DEFAULT_MAX_HEADERS), undertowOptions.get(UndertowOptions.ALLOW_ENCODED_SLASH, false), undertowOptions.get(UndertowOptions.ALLOW_UNESCAPED_CHARACTERS_IN_URL, false));
    }

    @Override
    public ByteBufferPool getBufferPool() {
        return bufferPool;
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
        for (AjpServerConnection i : connections) {
            IoUtils.safeClose(i);
        }
    }

    public String getScheme() {
        return scheme;
    }

    public void setScheme(final String scheme) {
        this.scheme = scheme;
    }
}
