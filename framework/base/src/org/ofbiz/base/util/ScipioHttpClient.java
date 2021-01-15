package org.ofbiz.base.util;



import org.apache.http.client.config.RequestConfig;
import org.apache.http.config.RegistryBuilder;
import org.apache.http.conn.HttpClientConnectionManager;
import org.apache.http.conn.socket.ConnectionSocketFactory;
import org.apache.http.conn.socket.PlainConnectionSocketFactory;
import org.apache.http.conn.ssl.SSLConnectionSocketFactory;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.impl.client.HttpClients;
import org.apache.http.impl.conn.PoolingHttpClientConnectionManager;
import org.apache.http.impl.nio.client.CloseableHttpAsyncClient;
import org.apache.http.impl.nio.client.HttpAsyncClients;
import org.apache.http.impl.nio.conn.PoolingNHttpClientConnectionManager;
import org.apache.http.impl.nio.reactor.DefaultConnectingIOReactor;
import org.apache.http.nio.conn.NHttpClientConnectionManager;
import org.apache.http.nio.reactor.ConnectingIOReactor;
import org.apache.http.nio.reactor.IOReactorException;
import org.apache.http.ssl.SSLContexts;

import java.io.Closeable;
import java.io.IOException;
import java.io.Serializable;
import java.util.Map;

/**
 * Typical pooling-enabled, SSL-enabled Apache HTTP client config builder readable from properties (SCIPIO),
 * kinder than straight HttpClient; also provides a {@link ScipioHttpClient.Config} class which can be used standalone.
 * ScipioHttpClient itself is a thin wrapper for creating HttpClient on first demand and help close on finalize.
 * TODO?: This has no helper send methods, just use {@link #getHttpClient()} since well-known and too much wrap.
 */
public class ScipioHttpClient implements Closeable {
    private static final Debug.OfbizLogger module = Debug.getOfbizLogger(java.lang.invoke.MethodHandles.lookup().lookupClass());

    protected final Config config;
    protected final boolean autoClose;

    protected volatile HttpClientConnectionManager connectionManager;
    protected volatile NHttpClientConnectionManager asyncConnectionManager;
    protected volatile CloseableHttpClient httpClient;
    protected volatile CloseableHttpAsyncClient asyncHttpClient;

    protected ScipioHttpClient(Config config, boolean autoClose) {
        this.config = config;
        this.autoClose = autoClose;
        this.connectionManager = null;
        this.asyncConnectionManager = null;
        this.httpClient = null;
        this.asyncHttpClient = null;
    }

    public static ScipioHttpClient fromConfig(Config config, boolean autoClose) {
        return config.getFactory().getClient(config, autoClose);
    }

    public static ScipioHttpClient fromConfig(Config config) {
        return fromConfig(config, true);
    }

    /** Gets connection manager (normally PoolingHttpClientConnectionManager), creates if needed. */
    public HttpClientConnectionManager getConnectionManager() {
        HttpClientConnectionManager connectionManager = this.connectionManager;
        if (connectionManager == null) {
            synchronized(this) {
                connectionManager = this.connectionManager;
                if (connectionManager == null) {
                    connectionManager = createConnectionManager();
                    this.connectionManager = connectionManager;
                }
            }
        }
        return connectionManager;
    }

    /** Build method for PoolingHttpClientConnectionManager mainly, always creates. */
    public HttpClientConnectionManager createConnectionManager() {
        return config.createConnectionManager();
    }

    /** Build method for HttpClient, always creates. */
    public CloseableHttpClient createHttpClient(HttpClientConnectionManager connectionManager) {
        return config.createHttpClient(connectionManager);
    }

    /** Build method for HttpClient: always creates HttpClient but reusing the current connection manager initializing as needed. */
    public CloseableHttpClient createHttpClient() {
        return config.createHttpClient(getConnectionManager());
    }

    public CloseableHttpClient getHttpClient() {
        CloseableHttpClient httpClient = this.httpClient;
        if (httpClient == null) {
            synchronized(this) {
                httpClient = this.httpClient;
                if (httpClient == null) {
                    httpClient = createHttpClient(getConnectionManager());
                    this.httpClient = httpClient;
                }
            }
        }
        return httpClient;
    }

    /** SCIPIO: 2020-01-14: NEW ASYNC SUPPORT: Gets connection manager (normally PoolingNHttpClientConnectionManager), creates if needed. */
    public NHttpClientConnectionManager getAsyncConnectionManager() {
        NHttpClientConnectionManager connectionManager = this.asyncConnectionManager;
        if (connectionManager == null) {
            synchronized(this) {
                connectionManager = this.asyncConnectionManager;
                if (connectionManager == null) {
                    connectionManager = createAsyncConnectionManager();
                    this.asyncConnectionManager = connectionManager;
                }
            }
        }
        return connectionManager;
    }

    /** SCIPIO: 2020-01-14: NEW ASYNC SUPPORT: Build method for NPoolingHttpClientConnectionManager mainly, always creates. */
    public NHttpClientConnectionManager createAsyncConnectionManager() {
        return config.createAsyncConnectionManager();
    }

    /** SCIPIO: 2020-01-14: NEW ASYNC SUPPORT: Build method for async HttpClient, always creates. */
    public CloseableHttpAsyncClient createAsyncHttpClient(NHttpClientConnectionManager connectionManager) {
        return config.createAsyncHttpClient(connectionManager);
    }

    /** SCIPIO: 2020-01-14: NEW ASYNC SUPPORT: Build method for async HttpClient: always creates HttpClient but reusing the current connection manager initializing as needed. */
    public CloseableHttpAsyncClient createAsyncHttpClient() {
        return config.createAsyncHttpClient(getAsyncConnectionManager());
    }

    /** SCIPIO: 2020-01-14: NEW ASYNC SUPPORT **/
    public CloseableHttpAsyncClient getAsyncHttpClient() {
        CloseableHttpAsyncClient asyncHttpClient = this.asyncHttpClient;
        if (asyncHttpClient == null) {
            synchronized(this) {
                asyncHttpClient = this.asyncHttpClient;
                if (asyncHttpClient == null) {
                    asyncHttpClient = createAsyncHttpClient(getAsyncConnectionManager());
                    this.asyncHttpClient = asyncHttpClient;
                }
            }
        }
        return asyncHttpClient;
    }

    /** If true, {@link #close()} is called in {@link #finalize()}. WARN: May not be sufficient for safe close. */
    public boolean isAutoClose() {
        return autoClose;
    }

    @Override
    public void close() throws IOException {
        if (httpClient != null) {
            try {
                httpClient.close();
            } catch(Exception e) {
                Debug.logWarning("Could not close HttpClient: " + e.toString(), module);
            }
        }
        try {
            if (connectionManager instanceof PoolingHttpClientConnectionManager) {
                ((PoolingHttpClientConnectionManager) connectionManager).close();
            } else if (connectionManager instanceof PoolingNHttpClientConnectionManager) {
                ((PoolingNHttpClientConnectionManager) connectionManager).closeExpiredConnections();
            }
        } catch(Exception e) {
            Debug.logWarning(e, "Could not close HttpClient connection manager: " + e.toString(), module);
        }
    }

    @Override
    protected void finalize() throws Throwable {
        if (isAutoClose()) {
            close();
        }
    }

    /**
     * Generic HttpClient config/builder, can be used standalone without ScipioHttpClient instance.
     */
    public static class Config implements Serializable {
        private final Factory factory;
        private final Boolean pooling;
        private final Integer maxConnections;
        private final Integer maxConnectionsPerHost;
        private final Integer connectTimeout;
        private final Integer socketTimeout;
        private final Integer connectionRequestTimeout;
        private final Boolean expectContinueEnabled;

        protected Config(Map<String, ?> properties, Factory factory) {
            this.factory = factory;
            this.pooling = UtilProperties.asBoolean(properties.get("pooling"), true);
            this.maxConnections = UtilProperties.asInteger(properties.get("maxConnections"), null);
            this.maxConnectionsPerHost = UtilProperties.asInteger(properties.get("maxConnectionsPerHost"), null);
            this.connectTimeout = UtilProperties.asInteger(properties.get("connectTimeout"), null);
            this.socketTimeout = UtilProperties.asInteger(properties.get("socketTimeout"), null);
            this.connectionRequestTimeout = UtilProperties.asInteger(properties.get("connectionRequestTimeout"), null);
            this.expectContinueEnabled = UtilProperties.asBoolean(properties.get("expectContinueEnabled"), null);
        }

        public static Config fromContext(Map<String, ?> properties) {
            Factory factory = getFactory(properties);
            return factory.getConfig(properties, factory);
        }

        protected static Factory getFactory(Map<String, ?> properties) {
            Factory factory = Factory.DEFAULT;
            String factoryClsName = (String) properties.get("factoryClass");
            if (UtilValidate.isNotEmpty(factoryClsName)) {
                try {
                    factory = (Factory) Thread.currentThread().getContextClassLoader().loadClass(factoryClsName).newInstance();
                } catch (Exception e) {
                    Debug.logError(e, "Could not load factoryClass [" + factoryClsName + "] for ScipioHttpClient config", module);
                }
            }
            return factory;
        }

        public static Config fromProperties(String resource, String prefix) {
            return fromContext(UtilProperties.getPropertiesWithPrefix(UtilProperties.getProperties(resource), prefix));
        }

        /** SCIPIO: 2020-01-14: NEW ASYNC SUPPORT: Extracted from createHttpClient for reuse **/
        protected RequestConfig buildRequestConfig() {
            RequestConfig.Builder config = RequestConfig.custom();
            if (getConnectionRequestTimeout() != null) {
                config.setConnectionRequestTimeout(getConnectionRequestTimeout());
            }
            if (getConnectTimeout() != null) {
                config.setConnectTimeout(getConnectTimeout());
            }
            if (getSocketTimeout() != null) {
                config.setSocketTimeout(getSocketTimeout());
            }
            if (getExpectContinueEnabled() != null) {
                config.setExpectContinueEnabled(true);
            }
            return config.build();
        }

        /** Build method for PoolingHttpClientConnectionManager mainly. */
        public HttpClientConnectionManager createConnectionManager() {
            if (!Boolean.TRUE.equals(getPooling())) {
                return null;
            }
            PoolingHttpClientConnectionManager cm = new PoolingHttpClientConnectionManager(RegistryBuilder.<ConnectionSocketFactory>create()
                    .register("http", PlainConnectionSocketFactory.getSocketFactory())
                    .register("https", new SSLConnectionSocketFactory(SSLContexts.createDefault()))
                    .build());
            if (getMaxConnections() != null) {
                cm.setMaxTotal(getMaxConnections());
            }
            if (getMaxConnectionsPerHost() != null) {
                cm.setDefaultMaxPerRoute(getMaxConnectionsPerHost());
            }
            return cm;
        }

        /** Build method for HttpClient. */
        public CloseableHttpClient createHttpClient(HttpClientConnectionManager connectionManager) {
            return HttpClients.custom()
                .setDefaultRequestConfig(buildRequestConfig())
                .setConnectionManager(connectionManager)
                .build();
        }

        /** SCIPIO: 2020-01-14: NEW ASYNC SUPPORT: Build method for PoolingNHttpClientConnectionManager mainly. */
        public NHttpClientConnectionManager createAsyncConnectionManager() {
            if (!Boolean.TRUE.equals(getPooling())) {
                return null;
            }
            PoolingNHttpClientConnectionManager cm = null;
            try {
                ConnectingIOReactor ioReactor = new DefaultConnectingIOReactor();
                cm = new PoolingNHttpClientConnectionManager(ioReactor);
                if (getMaxConnections() != null) {
                    cm.setMaxTotal(getMaxConnections());
                }
                if (getMaxConnectionsPerHost() != null) {
                    cm.setDefaultMaxPerRoute(getMaxConnectionsPerHost());
                }
            } catch (IOReactorException e) {
                Debug.logError(e, module);
            }

            return cm;
        }

        /** SCIPIO: 2020-01-14: NEW ASYNC SUPPORT: Build method for HttpAsyncClient. */
        public CloseableHttpAsyncClient createAsyncHttpClient(NHttpClientConnectionManager connectionManager) {
            CloseableHttpAsyncClient httpAsyncClient = HttpAsyncClients.custom()
                    .setDefaultRequestConfig(buildRequestConfig())
                    .setConnectionManager(connectionManager)
                    .build();
            if (!httpAsyncClient.isRunning()) {
                httpAsyncClient.start();
            }
            return httpAsyncClient;
        }


        public Factory getFactory() {
            return factory;
        }

        public Boolean getPooling() {
            return pooling;
        }

        public Integer getMaxConnections() {
            return maxConnections;
        }

        public Integer getMaxConnectionsPerHost() {
            return maxConnectionsPerHost;
        }

        public Integer getConnectTimeout() {
            return connectTimeout;
        }

        public Integer getSocketTimeout() {
            return socketTimeout;
        }

        public Integer getConnectionRequestTimeout() {
            return connectionRequestTimeout;
        }

        public Boolean getExpectContinueEnabled() {
            return expectContinueEnabled;
        }
    }

    public interface Factory {
        Factory DEFAULT = new Factory() {};
        default ScipioHttpClient getClient(Config config, boolean autoClose) { return new ScipioHttpClient(config, autoClose); }
        default Config getConfig(Map<String, ?> properties, Factory factory) { return new Config(properties, factory); }
    }
}
