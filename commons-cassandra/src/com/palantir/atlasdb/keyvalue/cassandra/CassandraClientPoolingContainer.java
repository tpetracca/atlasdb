// Copyright 2015 Palantir Technologies
//
// Licensed under the BSD-3 License (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
// http://opensource.org/licenses/BSD-3-Clause
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.

package com.palantir.atlasdb.keyvalue.cassandra;

import java.io.IOException;
import java.net.ConnectException;
import java.net.SocketTimeoutException;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import javax.annotation.Nonnull;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;

import org.apache.cassandra.thrift.Cassandra;
import org.apache.cassandra.thrift.Cassandra.Client;
import org.apache.thrift.protocol.TBinaryProtocol;
import org.apache.thrift.protocol.TProtocol;
import org.apache.thrift.protocol.TProtocolException;
import org.apache.thrift.transport.TFramedTransport;
import org.apache.thrift.transport.TSocket;
import org.apache.thrift.transport.TTransport;
import org.apache.thrift.transport.TTransportException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.common.base.MoreObjects;
import com.palantir.common.base.FunctionCheckedException;
import com.palantir.common.concurrent.PTExecutors;
import com.palantir.common.pooling.AbstractPoolingContainer;
import com.palantir.common.remoting.ServiceNotAvailableException;

/**
 * This class will run the passed function with a valid client with an open socket.  An open socket
 * is not a guarantee that it will actually move bytes over the wire.  The default tcp keep alive is
 * normally set to 2 hours, so it may be the case that the other side of the socket has been dead for
 * almost 2 hours.
 * <p>
 * This class will not reuse a socket that has experienced a TTransportException because that socket
 * may not read the TProtocol correctly anymore.
 * <p>
 * This class will return an instance of ClientCreationFailedException if the socket could not be
 * opened successfully or the current keyspace could not be set.
 */
public class CassandraClientPoolingContainer extends AbstractPoolingContainer<Client> {
    private static final Logger log = LoggerFactory.getLogger(CassandraClientPoolingContainer.class);

    final String host;
    final String keyspace;
    final int port;
    final boolean isSsl;
    final AtomicLong count = new AtomicLong();
    final AtomicReference<Exception> waitingForGoodConnectionException =  new AtomicReference<Exception>();
    final ExecutorService executor = PTExecutors.newSingleThreadExecutor(true);

    public CassandraClientPoolingContainer(String host, int port, int poolSize, String keyspace, boolean isSsl) {
        super(poolSize);
        this.host = host;
        this.port = port;
        this.isSsl = isSsl;
        this.keyspace = keyspace;
    }

    @Override
    @Nonnull
    protected Client createNewPooledResource() {
        try {
            return getClient(host, port, keyspace, isSsl);
        } catch (Exception e) {
            throw new ClientCreationFailedException("Failed to construct client for host: " + host, e);
        }
    }

    @Override
    public void shutdownPooling() {
        executor.shutdown();
        super.shutdownPooling();
    }

    @Override
    @SuppressWarnings("unchecked")
    public <V, K extends Exception> V runWithPooledResource(FunctionCheckedException<Client, V, K> f)
            throws K {
        Exception unavailableException = waitingForGoodConnectionException.get();
        if (unavailableException != null) {
            throw new ServiceNotAvailableException("Blocking until this connection is good.", unavailableException);
        }
        final String origName = Thread.currentThread().getName();
        Thread.currentThread().setName(origName
                + " calling cassandra host " + host
                + " started at " + new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssZ").format(new Date())
                + " - " + count.getAndIncrement());
        try {
            return runWithGoodResource(f);
        } catch (Throwable t) {
            log.warn("Failed while connecting to host: " + host, t);
            if (t instanceof Exception) {
                throw (K) t;
            } else {
                throw (Error) t;
            }
        } finally {
            Thread.currentThread().setName(origName);
        }
    }

    @SuppressWarnings("unchecked")
    private <V, K extends Exception> V runWithGoodResource(FunctionCheckedException<Client, V, K> f)
            throws K {
        boolean shouldReuse = true;
        final Client resource = this.<K>getGoodClient();

        try {
            return f.apply(resource);
        } catch (Exception e) {
            if (e instanceof TTransportException
                    || e instanceof TProtocolException) {
                log.warn("Not reusing resource " + resource, e);
                shouldReuse = false;
            }
            discardPoolIfNeeded(e);
            throw (K) e;
        } finally {
            if (shouldReuse) {
                log.info("Returning {} to pool", resource);
                returnResource(resource);
            } else {
                log.info("Discarding: {}", resource);
                cleanupForDiscard(resource);
            }
        }
    }

    private void discardPoolIfNeeded(Exception e) {
        if (!(e instanceof TTransportException)) {
            return;
        }
        TTransportException te = (TTransportException) e;

        if (te.getType() == TTransportException.END_OF_FILE || te.getCause() instanceof ConnectException) {
            if (waitingForGoodConnectionException.compareAndSet(null, e)) {
                // If we are the first one to detect a connection issue we kick off the task to
                // monitor to see when connections become ok again.  While this is set we throw
                // ServiceNotAvailableException.
                submitConenctTask();
                discardCurrentPool();
            }
        } else if (te.getCause() instanceof SocketTimeoutException) {
            // If we have a socket timeout we may be in the case where we can not talk to a node
            // anymore.  If this is the case we should close all pooled connections and get fresh
            // connections for all future requests.  This means the next request will cause a
            // ConnectException instead which will trigger the ServiceNotAvailableException.
            discardCurrentPool();
        }
    }

    private void submitConenctTask() {
        if (executor.isShutdown()) {
            waitingForGoodConnectionException.set(null);
            return;
        }
        try {
            executor.submit(new Callable<Void>() {
                @Override
                public Void call() throws Exception {
                    try {
                        while (true) {
                            try {
                                Client client = getClient(host, port, keyspace, isSsl);
                                cleanupClient(client);
                                return null;
                            } catch (Throwable t) {
                                log.info("Failed while blocking.");
                            }
                        }
                    } finally {
                        waitingForGoodConnectionException.set(null);
                    }
                }
            });
        } catch (Throwable t) {
            waitingForGoodConnectionException.set(null);
            log.info("Failed to submit task.", t);
        }
    }

    @SuppressWarnings("unchecked")
    private <K extends Exception> Client getGoodClient() throws K {
        try {
            Client resource = null;
            do {
                if (resource != null) {
                    cleanupForDiscard(resource);
                }
                resource = getResource();
            } while (!resource.getOutputProtocol().getTransport().isOpen());
            return resource;
        } catch (Exception e) {
            log.warn("Could not connect to host.", e);
            discardPoolIfNeeded(e);
            throw (K) e;
        }
    }

    @Override
    protected void cleanupForDiscard(Client discardedResource) {
        cleanupClient(discardedResource);
        log.info("Closed transport for client {}", discardedResource);
        super.cleanupForDiscard(discardedResource);
    }

    static void cleanupClient(Client toClean) {
        toClean.getOutputProtocol().getTransport().close();
    }

    static class ClientCreationFailedException extends RuntimeException {
        private static final long serialVersionUID = 1L;

        public ClientCreationFailedException(String message, Exception cause) {
            super(message, cause);
        }

        @Override
        public Exception getCause() {
            return (Exception) super.getCause();
        }
    }

    private static Cassandra.Client getClient(String host, int port, String keyspace, boolean isSsl) throws Exception {
        Client ret = getClientInternal(host, port, isSsl);
        try {
            ret.set_keyspace(keyspace);
            log.info("Created new client for {}:{}/{} {}", host, port, keyspace, (isSsl ? "over SSL" : ""));
            return ret;
        } catch (Exception e) {
            cleanupClient(ret);
            throw e;
        }
    }

    static Cassandra.Client getClientInternal(String host, int port, boolean isSsl) throws TTransportException {
        TSocket tSocket = new TSocket(host, port, CassandraConstants.CONNECTION_TIMEOUT_MILLIS);
        tSocket.open();
        tSocket.setTimeout(CassandraConstants.SOCKET_TIMEOUT_MILLIS);
        if (isSsl) {
            boolean success = false;
            try {
                SSLSocketFactory factory = (SSLSocketFactory) SSLSocketFactory.getDefault();
                SSLSocket socket = (SSLSocket) factory.createSocket(tSocket.getSocket(), host, port, true);
                tSocket = new TSocket(socket);
                success = true;
            } catch (IOException e) {
                throw new TTransportException(e);
            } finally {
                if (!success) {
                    tSocket.close();
                }
            }
        }
        TTransport tFramedTransport = new TFramedTransport(tSocket, CassandraConstants.CLIENT_MAX_THRIFT_FRAME_SIZE_BYTES);
        TProtocol protocol = new TBinaryProtocol(tFramedTransport);
        Cassandra.Client client = new Cassandra.Client(protocol);
        return client;
    }
    @Override
    public String toString() {
        return MoreObjects.toStringHelper(getClass())
                .add("host", this.host)
                .add("port", this.port)
                .add("keyspace", this.keyspace)
                .add("isSsl", this.isSsl)
                .toString();
    }
}
