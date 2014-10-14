package com.airbnb.airpal.modules;

import com.airbnb.airlift.http.client.OldJettyHttpClient;
import com.airbnb.airpal.AirpalConfiguration;
import com.airbnb.airpal.core.ManagedESClient;
import com.airbnb.airpal.core.PersistentJobOutputFactory;
import com.airbnb.airpal.core.execution.ExecutionClient;
import com.airbnb.airpal.core.health.PrestoHealthCheck;
import com.airbnb.airpal.core.hive.HiveTableUpdatedCache;
import com.airbnb.airpal.core.store.CachingUsageStore;
import com.airbnb.airpal.core.store.JobHistoryStore;
import com.airbnb.airpal.core.store.QueryStore;
import com.airbnb.airpal.core.store.UsageStore;
import com.airbnb.airpal.core.store.es.CachingESUsageStore;
import com.airbnb.airpal.core.store.es.ESJobHistoryStore;
import com.airbnb.airpal.core.store.es.ESQueryStore;
import com.airbnb.airpal.presto.ClientSessionFactory;
import com.airbnb.airpal.presto.QueryInfoClient;
import com.airbnb.airpal.presto.Table;
import com.airbnb.airpal.presto.metadata.ColumnCache;
import com.airbnb.airpal.presto.metadata.PreviewTableCache;
import com.airbnb.airpal.presto.metadata.SchemaCache;
import com.airbnb.airpal.resources.ExecuteResource;
import com.airbnb.airpal.resources.HealthResource;
import com.airbnb.airpal.resources.LoginResource;
import com.airbnb.airpal.resources.PingResource;
import com.airbnb.airpal.resources.QueryResource;
import com.airbnb.airpal.resources.RedirectRootResource;
import com.airbnb.airpal.resources.TablesResource;
import com.airbnb.airpal.resources.sse.SSEEventSourceServlet;
import com.amazonaws.auth.AWSCredentials;
import com.amazonaws.auth.BasicAWSCredentials;
import com.amazonaws.services.s3.AmazonS3;
import com.amazonaws.services.s3.AmazonS3Client;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.eventbus.AsyncEventBus;
import com.google.common.eventbus.EventBus;
import com.google.inject.AbstractModule;
import com.google.inject.Provider;
import com.google.inject.Provides;
import com.google.inject.Scopes;
import com.google.inject.Singleton;
import com.google.inject.TypeLiteral;
import com.google.inject.name.Names;
import io.airlift.configuration.ConfigurationFactory;
import io.airlift.http.client.AsyncHttpClient;
import io.airlift.http.client.HttpClientConfig;
import io.airlift.units.Duration;
import lombok.extern.slf4j.Slf4j;
import org.apache.shiro.web.env.EnvironmentLoaderListener;

import javax.inject.Named;

import java.net.URI;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static com.airbnb.airpal.presto.QueryRunner.QueryRunnerFactory;

@Slf4j
public class AirpalModule extends AbstractModule
{
    private final AirpalConfiguration config;

    public AirpalModule(AirpalConfiguration config)
    {
        this.config = config;
    }

    @Override
    protected void configure()
    {
        bind(TablesResource.class).in(Scopes.SINGLETON);
        bind(ExecuteResource.class).in(Scopes.SINGLETON);
        bind(QueryResource.class).in(Scopes.SINGLETON);
        bind(RedirectRootResource.class).in(Scopes.SINGLETON);
        bind(HealthResource.class).in(Scopes.SINGLETON);
        bind(PingResource.class).in(Scopes.SINGLETON);
        bind(LoginResource.class).in(Scopes.SINGLETON);
        bind(SSEEventSourceServlet.class).in(Scopes.SINGLETON);

        bind(EnvironmentLoaderListener.class).in(Scopes.SINGLETON);
        bind(new TypeLiteral<List<URI>>(){}).annotatedWith(Names.named("corsAllowedHosts")).toInstance(config.getAirpalHosts());
        bind(String.class).annotatedWith(Names.named("s3Bucket")).toInstance(config.getS3Bucket());

        bind(PrestoHealthCheck.class).in(Scopes.SINGLETON);
        bind(ExecutionClient.class).in(Scopes.SINGLETON);
        bind(PersistentJobOutputFactory.class).in(Scopes.SINGLETON);

        bind(JobHistoryStore.class).to(ESJobHistoryStore.class).in(Scopes.SINGLETON);
        bind(QueryStore.class).to(ESQueryStore.class).in(Scopes.SINGLETON);
    }

    @Singleton
    @Provides
    public ConfigurationFactory provideConfigurationFactory()
    {
        return new ConfigurationFactory(Collections.<String, String>emptyMap());
    }

    @Singleton
    @Named("query-runner-http-client")
    @Provides
    public AsyncHttpClient provideQueryRunnerHttpClient()
    {
        final HttpClientConfig httpClientConfig = new HttpClientConfig().setConnectTimeout(new Duration(10, TimeUnit.SECONDS));

        return new OldJettyHttpClient(httpClientConfig);
    }

    @Named("coordinator-uri")
    @Provides
    public URI providePrestoCoordinatorURI()
    {
        return config.getPrestoCoordinator();
    }

    @Singleton
    @Named("default-catalog")
    @Provides
    public String provideDefaultCatalog()
    {
        return config.getPrestoCatalog();
    }

    @Provides
    @Singleton
    public ClientSessionFactory provideClientSessionFactory(@Named("coordinator-uri") Provider<URI> uriProvider)
    {
        return new ClientSessionFactory(uriProvider,
                config.getPrestoUser(),
                config.getPrestoSource(),
                config.getPrestoCatalog(),
                config.getPrestoSchema(),
                config.isPrestoDebug());
    }

    @Provides
    public QueryRunnerFactory provideQueryRunner(ClientSessionFactory sessionFactory,
            @Named("query-runner-http-client") AsyncHttpClient httpClient)
    {
        return new QueryRunnerFactory(sessionFactory, httpClient);
    }

    @Provides
    public QueryInfoClient provideQueryInfoClient()
    {
        return QueryInfoClient.create();
    }

    @Singleton
    @Provides
    public SchemaCache provideSchemaCache(QueryRunnerFactory queryRunnerFactory,
                                          @Named("presto") ExecutorService executorService)
    {
        final SchemaCache cache = new SchemaCache(queryRunnerFactory, executorService);
        cache.populateCache(config.getPrestoCatalog());

        return cache;
    }

    @Singleton
    @Provides
    public ColumnCache provideColumnCache(QueryRunnerFactory queryRunnerFactory,
                                          @Named("presto") ExecutorService executorService)
    {
        return new ColumnCache(queryRunnerFactory,
                               new Duration(5, TimeUnit.MINUTES),
                               new Duration(60, TimeUnit.MINUTES),
                               executorService);
    }

    @Singleton
    @Provides
    public PreviewTableCache providePreviewTableCache(QueryRunnerFactory queryRunnerFactory,
                                                      @Named("presto") ExecutorService executorService)
    {
        return new PreviewTableCache(queryRunnerFactory,
                                     new Duration(20, TimeUnit.MINUTES),
                                     executorService,
                                     100);
    }

    @Singleton
    @Named("event-bus")
    @Provides
    public ExecutorService provideEventBusExecutorService()
    {
        return Executors.newCachedThreadPool(SchemaCache.daemonThreadsNamed("event-bus-%d"));
    }

    @Singleton
    @Named("presto")
    @Provides
    public ExecutorService provideCompleterExecutorService()
    {
        return Executors.newCachedThreadPool(SchemaCache.daemonThreadsNamed("presto-%d"));
    }

    @Singleton
    @Named("sse")
    @Provides
    public ExecutorService provideSSEExecutorService()
    {
        return Executors.newCachedThreadPool(SchemaCache.daemonThreadsNamed("sse-%d"));
    }

    @Singleton
    @Provides
    public EventBus provideEventBus(@Named("event-bus") ExecutorService executor)
    {
        return new AsyncEventBus(executor);
    }

    @Provides
    public AWSCredentials provideAWSCredentials()
    {
        return new BasicAWSCredentials(config.getS3AccessKey(),
                                       config.getS3SecretKey());
    }

    @Singleton
    @Provides
    public AmazonS3 provideAmazonS3Client(AWSCredentials awsCredentials)
    {
        return new AmazonS3Client(awsCredentials);
    }

    @Singleton
    @Provides
    public UsageStore provideUsageCache(ManagedESClient managedNode)
    {
        UsageStore delegate =  new CachingESUsageStore(
                managedNode,
                config.getUsageWindow(),
                io.dropwizard.util.Duration.minutes(2));

        return new CachingUsageStore(delegate, io.dropwizard.util.Duration.minutes(6));
    }

    @Singleton
    @Provides
    public ManagedESClient provideManagedNode(ObjectMapper mapper)
    {
        return new ManagedESClient(config.getElasticSearchProperties(),
                               mapper);
    }

    @Singleton
    @Provides
    public HiveTableUpdatedCache provideHiveTableUpdatedCache(@Named("presto") ExecutorService executorService)
    {
        AirpalConfiguration.HiveMetastoreConfiguration metastoreConfiguration = config.getMetaStoreConfiguration();

        final HiveTableUpdatedCache updatedCache = new HiveTableUpdatedCache(
                io.dropwizard.util.Duration.hours(1),
                metastoreConfiguration.getDriverName(),
                metastoreConfiguration.getConnectionUrl(),
                metastoreConfiguration.getUserName(),
                metastoreConfiguration.getPassword(),
                executorService);

        executorService.submit(new Runnable()
        {
            @Override
            public void run()
            {
                updatedCache.getAll(Collections.<Table>emptyList());
            }
        });

        return updatedCache;
    }
}
