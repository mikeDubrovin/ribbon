/*
 *
 * Copyright 2014 Netflix, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 */
package com.netflix.client.netty;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufInputStream;
import io.netty.buffer.Unpooled;
import io.reactivex.netty.protocol.http.client.HttpClientRequest;
import io.reactivex.netty.protocol.http.client.HttpClientResponse;
import io.reactivex.netty.protocol.text.sse.ServerSentEvent;

import java.io.IOException;
import java.util.Iterator;
import java.util.List;
import java.util.Random;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicReference;

import org.codehaus.jackson.map.ObjectMapper;
import org.junit.BeforeClass;
import org.junit.Test;

import rx.Observable;
import rx.functions.Action1;
import rx.functions.Func1;

import com.google.common.collect.Lists;
import com.google.mockwebserver.MockResponse;
import com.google.mockwebserver.MockWebServer;
import com.netflix.client.ClientException;
import com.netflix.client.RequestSpecificRetryHandler;
import com.netflix.client.RetryHandler;
import com.netflix.client.config.CommonClientConfigKey;
import com.netflix.client.config.DefaultClientConfigImpl;
import com.netflix.client.config.IClientConfig;
import com.netflix.client.config.IClientConfigKey;
import com.netflix.client.netty.http.NettyHttpClient;
import com.netflix.client.netty.http.NettyHttpClientBuilder;
import com.netflix.client.netty.http.NettyHttpLoadBalancerErrorHandler;
import com.netflix.client.netty.http.NettyHttpLoadBalancingClient;
import com.netflix.client.netty.http.SSEClient;
import com.netflix.client.netty.http.SSELoadBalancingClient;
import com.netflix.loadbalancer.AvailabilityFilteringRule;
import com.netflix.loadbalancer.BaseLoadBalancer;
import com.netflix.loadbalancer.DummyPing;
import com.netflix.loadbalancer.Server;
import com.netflix.loadbalancer.ServerStats;
import com.netflix.ribbon.test.resources.EmbeddedResources;
import com.netflix.ribbon.test.resources.EmbeddedResources.Person;
import com.netflix.serialization.JacksonCodec;
import com.netflix.serialization.SerializationUtils;
import com.netflix.serialization.TypeDef;
import com.sun.jersey.api.container.httpserver.HttpServerFactory;
import com.sun.jersey.api.core.PackagesResourceConfig;
import com.sun.net.httpserver.HttpServer;

public class NettyClientTest {
    
    private static HttpServer server = null;
    private static String SERVICE_URI;
    private static int port;
    private static final String host = "localhost";
    
    @BeforeClass 
    public static void init() throws Exception {
        PackagesResourceConfig resourceConfig = new PackagesResourceConfig("com.netflix.ribbon.test.resources");
        port = (new Random()).nextInt(1000) + 4000;
        SERVICE_URI = "http://localhost:" + port + "/";
        ExecutorService service = Executors.newFixedThreadPool(20);
        try{
            server = HttpServerFactory.create(SERVICE_URI, resourceConfig);           
            server.setExecutor(service);
            server.start();
        } catch(Exception e) {
            e.printStackTrace();
            fail("Unable to start server");
        }
        // LogManager.getRootLogger().setLevel((Level)Level.DEBUG);
    }
    
    private static Observable<Person> getPersonObservable(Observable<HttpClientResponse<ByteBuf>> response) {
        return response.flatMap(new Func1<HttpClientResponse<ByteBuf>, Observable<ByteBuf>>() {
            @Override
            public Observable<ByteBuf> call(HttpClientResponse<ByteBuf> t1) {
                return t1.getContent();
            }
            
        }).map(new Func1<ByteBuf, Person>() {
            @Override
            public Person call(ByteBuf t1) {
                try {
                    return JacksonCodec.<Person>getInstance().deserialize(new ByteBufInputStream(t1), TypeDef.fromClass(Person.class));
                } catch (IOException e) {
                    e.printStackTrace();
                    return null;
                }
            }
        });
    }
    
    @Test
    public void testObservable() throws Exception {
        HttpClientRequest<ByteBuf> request = HttpClientRequest.createGet(SERVICE_URI + "testAsync/person");
        NettyHttpClient observableClient = NettyHttpClientBuilder.newBuilder().buildHttpClient();
        // final List<Person> result = Lists.newArrayList();
        Observable<HttpClientResponse<ByteBuf>> response = observableClient.submit(host, port, request);
        Person person = getPersonObservable(response).toBlockingObservable().single();
        assertEquals(EmbeddedResources.defaultPerson, person);
        assertEquals(1, observableClient.getIdleConnectionsInPool());
    }

    @Test
    public void testPostWithObservable() throws Exception {
        Person myPerson = new Person("netty", 5);
        HttpClientRequest<ByteBuf> request = HttpClientRequest.createPost(SERVICE_URI + "testAsync/person")
                .withHeader("Content-type", "application/json")
                .withContent(SerializationUtils.serializeToBytes(JacksonCodec.getInstance(), myPerson, null));
        NettyHttpClient observableClient = new NettyHttpClient(DefaultClientConfigImpl.getClientConfigWithDefaultValues().setPropertyWithType(CommonClientConfigKey.ReadTimeout, 10000));
        Observable<HttpClientResponse<ByteBuf>> response = observableClient.submit(host, port, request);
        Person person = getPersonObservable(response).toBlockingObservable().single();
        assertEquals(myPerson, person);
    }

    
    @Test
    public void testPostWithByteBuf() throws Exception {
        Person myPerson = new Person("netty", 5);
        ObjectMapper mapper = new ObjectMapper();
        byte[] raw = mapper.writeValueAsBytes(myPerson);
        ByteBuf buffer = Unpooled.copiedBuffer(raw);
        HttpClientRequest<ByteBuf> request = HttpClientRequest.createPost(SERVICE_URI + "testAsync/person")
                .withHeader("Content-type", "application/json")
                .withHeader("Content-length", String.valueOf(raw.length))
                .withContent(buffer);
        NettyHttpClient observableClient = new NettyHttpClient(DefaultClientConfigImpl.getClientConfigWithDefaultValues().setPropertyWithType(CommonClientConfigKey.ReadTimeout, 10000));
        Observable<HttpClientResponse<ByteBuf>> response = observableClient.submit(host, port, request);
        Person person = getPersonObservable(response).toBlockingObservable().single();
        assertEquals(myPerson, person);
    }

    
    @Test
    public void testConnectTimeout() throws Exception {
        NettyHttpClient observableClient = new NettyHttpClient(
                DefaultClientConfigImpl.getClientConfigWithDefaultValues().withProperty(CommonClientConfigKey.ConnectTimeout, "1"));
        HttpClientRequest<ByteBuf> request = HttpClientRequest.createGet("http://www.google.com:81/");
        Observable<HttpClientResponse<ByteBuf>> observable = observableClient.submit("www.google.com", 81, request);
        
        ObserverWithLatch<HttpClientResponse<ByteBuf>> observer = new ObserverWithLatch<HttpClientResponse<ByteBuf>>();
        observable.subscribe(observer);
        observer.await();
        assertNotNull(observer.error);
        assertTrue(observer.error instanceof io.netty.channel.ConnectTimeoutException);
    }
    
    @Test
    public void testReadTimeout() throws Exception {
        NettyHttpClient observableClient = new NettyHttpClient(
                DefaultClientConfigImpl.getClientConfigWithDefaultValues().withProperty(CommonClientConfigKey.ReadTimeout, "100"));
        HttpClientRequest<ByteBuf> request = HttpClientRequest.createGet(SERVICE_URI + "testAsync/readTimeout");
        Observable<HttpClientResponse<ByteBuf>> observable = observableClient.submit(host, port, request);
        ObserverWithLatch<HttpClientResponse<ByteBuf>> observer = new ObserverWithLatch<HttpClientResponse<ByteBuf>>();
        observable.subscribe(observer);
        observer.await();
        assertTrue(observer.error instanceof io.netty.handler.timeout.ReadTimeoutException);      
    }
    
    @Test
    public void testObservableWithMultipleServers() throws Exception {
        IClientConfig config = DefaultClientConfigImpl.getClientConfigWithDefaultValues().withProperty(CommonClientConfigKey.ConnectTimeout, "1000");
        HttpClientRequest<ByteBuf> request = HttpClientRequest.createGet("/testAsync/person");
        
        BaseLoadBalancer lb = new BaseLoadBalancer(new DummyPing(), new AvailabilityFilteringRule());
        Server badServer = new Server("localhost:12345");
        Server goodServer = new Server("localhost:" + port);
        List<Server> servers = Lists.newArrayList(badServer, badServer, badServer, goodServer);
        NettyHttpLoadBalancingClient lbObservables = NettyHttpClientBuilder.newBuilder()
                .withClientConfig(config)
                .withLoadBalancer(lb)
                .withFixedServerList(servers)
                .withLoadBalancerRetryHandler(new NettyHttpLoadBalancerErrorHandler(1, 3, true))
                .buildLoadBalancingClient();
        Person person = getPersonObservable(lbObservables.submitToLoadBalancer(request)).toBlockingObservable().single();
        assertEquals(EmbeddedResources.defaultPerson, person);
        ServerStats stats = lbObservables.getServerStats(badServer);
        // two requests to bad server because retry same server is set to 1
        assertEquals(4, stats.getTotalRequestsCount());
        assertEquals(0, stats.getActiveRequestsCount());
        assertEquals(4, stats.getSuccessiveConnectionFailureCount());
        
        stats = lbObservables.getServerStats(goodServer);
        // two requests to bad server because retry same server is set to 1
        assertEquals(1, stats.getTotalRequestsCount());
        assertEquals(0, stats.getActiveRequestsCount());
        assertEquals(0, stats.getSuccessiveConnectionFailureCount());
    }
    
    @Test
    public void testLoadBalancingObservablesWithReadTimeout() throws Exception {
        NettyHttpLoadBalancerErrorHandler errorHandler = new NettyHttpLoadBalancerErrorHandler(1, 3, true);
        MockWebServer server = new MockWebServer();
        String content = "{\"name\": \"ribbon\", \"age\": 2}";
        server.enqueue(new MockResponse().setResponseCode(200).setHeader("Content-type", "application/json")
                .setBody(content));       
        server.play();

        IClientConfig config = DefaultClientConfigImpl.getClientConfigWithDefaultValues()
                .setPropertyWithType(CommonClientConfigKey.ReadTimeout, 100);
        HttpClientRequest<ByteBuf> request = HttpClientRequest.createGet("/testAsync/readTimeout");//.withHeader("Content-length", "-1");
        
        BaseLoadBalancer lb = new BaseLoadBalancer(new DummyPing(), new AvailabilityFilteringRule());
        NettyHttpLoadBalancingClient lbObservables = new NettyHttpLoadBalancingClient(lb, config, errorHandler);
        Server goodServer = new Server("localhost:" + server.getPort());
        Server badServer = new Server("localhost:" + port);
        List<Server> servers = Lists.newArrayList(goodServer, badServer, badServer, goodServer);
        lb.setServersList(servers);
        Observable<Person> observableWithRetries = getPersonObservable(lbObservables.submitToLoadBalancer(request));
        ObserverWithLatch<Person> observer = new ObserverWithLatch<Person>();
        observableWithRetries.subscribe(observer);
        observer.await();
        assertEquals("ribbon", observer.obj.name);
        assertEquals(2, observer.obj.age);
        ServerStats stats = lbObservables.getServerStats(badServer);
        server.shutdown();
        // two requests to bad server because retry same server is set to 1
        assertEquals(4, stats.getTotalRequestsCount());
        assertEquals(0, stats.getActiveRequestsCount());
        assertEquals(4, stats.getSuccessiveConnectionFailureCount());
        
        stats = lbObservables.getServerStats(goodServer);
        // two requests to bad server because retry same server is set to 1
        assertEquals(1, stats.getTotalRequestsCount());
        assertEquals(0, stats.getActiveRequestsCount());
        assertEquals(0, stats.getSuccessiveConnectionFailureCount());
    }
   
    @Test
    public void testLoadBalancingPostWithReadTimeout() throws Exception {
        MockWebServer server = new MockWebServer();
        String content = "{\"name\": \"ribbon\", \"age\": 2}";
        server.enqueue(new MockResponse().setResponseCode(200).setHeader("Content-type", "application/json")
                .setBody(content));       
        server.play();

        IClientConfig config = DefaultClientConfigImpl.getClientConfigWithDefaultValues()
                .setPropertyWithType(CommonClientConfigKey.ReadTimeout, 100);
        HttpClientRequest<ByteBuf> request = HttpClientRequest.createPost("/testAsync/postTimeout")
                .withContent(SerializationUtils.serializeToBytes(JacksonCodec.getInstance(), EmbeddedResources.defaultPerson, null))
                .withHeader("Content-type", "application/json");
        NettyHttpLoadBalancerErrorHandler errorHandler = new NettyHttpLoadBalancerErrorHandler(1, 3, true);
        BaseLoadBalancer lb = new BaseLoadBalancer(new DummyPing(), new AvailabilityFilteringRule());
        NettyHttpLoadBalancingClient lbObservables = new NettyHttpLoadBalancingClient(lb, config, errorHandler);
        Server goodServer = new Server("localhost:" + server.getPort());
        Server badServer = new Server("localhost:" + port);
        List<Server> servers = Lists.newArrayList(badServer, badServer, badServer, goodServer);
        lb.setServersList(servers);
        RetryHandler handler = new RequestSpecificRetryHandler(true, true, errorHandler, null) {
            @Override
            public boolean isRetriableException(Throwable e, boolean sameServer) {
                return true;
            }
        };
        Observable<Person> observableWithRetries = getPersonObservable(lbObservables.submitToLoadBalancer(request, handler, null));
        ObserverWithLatch<Person> observer = new ObserverWithLatch<Person>();
        observableWithRetries.subscribe(observer);
        observer.await();
        if (observer.error != null) {
            observer.error.printStackTrace();
        }
        assertEquals("ribbon", observer.obj.name);
        assertEquals(2, observer.obj.age);
        ServerStats stats = lbObservables.getServerStats(badServer);
        server.shutdown();
        assertEquals(4, stats.getTotalRequestsCount());
        assertEquals(0, stats.getActiveRequestsCount());
        assertEquals(4, stats.getSuccessiveConnectionFailureCount());
        
        stats = lbObservables.getServerStats(goodServer);
        // two requests to bad server because retry same server is set to 1
        assertEquals(1, stats.getTotalRequestsCount());
        assertEquals(0, stats.getActiveRequestsCount());
        assertEquals(0, stats.getSuccessiveConnectionFailureCount());
    }


    
    @Test
    public void testObservableWithMultipleServersFailed() throws Exception {
        NettyHttpLoadBalancerErrorHandler errorHandler = new NettyHttpLoadBalancerErrorHandler(1, 3, true);
        IClientConfig config = DefaultClientConfigImpl.getClientConfigWithDefaultValues().withProperty(CommonClientConfigKey.ConnectTimeout, "100");
        HttpClientRequest<ByteBuf> request = HttpClientRequest.createGet("/testAsync/person");
        BaseLoadBalancer lb = new BaseLoadBalancer(new DummyPing(), new AvailabilityFilteringRule());        
        NettyHttpLoadBalancingClient lbObservables = new NettyHttpLoadBalancingClient(lb, config, errorHandler);
        Server badServer = new Server("localhost:12345");
        Server badServer1 = new Server("localhost:12346");
        Server badServer2 = new Server("localhost:12347");

        List<Server> servers = Lists.newArrayList(badServer, badServer1, badServer2);
        lb.setServersList(servers);
        Observable<Person> observableWithRetries = getPersonObservable(lbObservables.submitToLoadBalancer(request));
        ObserverWithLatch<Person> observer = new ObserverWithLatch<Person>();
        observableWithRetries.subscribe(observer);
        observer.await();
        assertNull(observer.obj);
        assertTrue(observer.error instanceof ClientException);
        ServerStats stats = lbObservables.getServerStats(badServer);
        // two requests to bad server because retry same server is set to 1
        assertEquals(2, stats.getTotalRequestsCount());
        assertEquals(0, stats.getActiveRequestsCount());
        assertEquals(2, stats.getSuccessiveConnectionFailureCount());
    }
    
    private List<Person> getPersonList(Observable<ServerSentEvent> events) {
        List<Person> result = Lists.newArrayList();
        Iterator<Person> iterator = events.map(new Func1<ServerSentEvent, Person>() {
            @Override
            public Person call(ServerSentEvent t1) {
                String content = t1.getEventData();
                try {
                    return SerializationUtils.deserializeFromString(JacksonCodec.<Person>getInstance(), content, TypeDef.fromClass(Person.class));
                } catch (IOException e) {
                    e.printStackTrace();
                    return null;
                }
            }
        }).toBlockingObservable().getIterator();
        while (iterator.hasNext()) {
            result.add(iterator.next());
        }
        return result;
    }
    
    
    @Test
    public void testStream() throws Exception {
        HttpClientRequest<ByteBuf> request = HttpClientRequest.createGet(SERVICE_URI + "testAsync/personStream");
        SSEClient observableClient = new SSEClient();
        List<Person> result = getPersonList(observableClient.observeServerSentEvent(host, port, request, null));
        assertEquals(EmbeddedResources.entityStream, result);
    }
    
    
    @Test
    public void testStreamWithLoadBalancer() throws Exception {
        NettyHttpLoadBalancerErrorHandler errorHandler = new NettyHttpLoadBalancerErrorHandler(1, 3, true);
        IClientConfig config = DefaultClientConfigImpl.getClientConfigWithDefaultValues().withProperty(CommonClientConfigKey.ConnectTimeout, "1000");
        BaseLoadBalancer lb = new BaseLoadBalancer(new DummyPing(), new AvailabilityFilteringRule());
        SSELoadBalancingClient lbObservables = new SSELoadBalancingClient(lb, config, errorHandler);
        HttpClientRequest<ByteBuf> request = HttpClientRequest.createGet("/testAsync/personStream");
        List<Person> result = Lists.newArrayList();
        Server goodServer = new Server("localhost:" + port);
        Server badServer = new Server("localhost:12245");
        List<Server> servers = Lists.newArrayList(badServer, badServer, badServer, goodServer);
        lb.setServersList(servers);
        result = getPersonList(lbObservables.observeServerSentEvent(request, null));
        assertEquals(EmbeddedResources.entityStream, result);
    }
    
    @Test
    public void testQuery() throws Exception {
        Person myPerson = new Person("hello_world", 4);
        HttpClientRequest<ByteBuf> request = HttpClientRequest.createGet(SERVICE_URI + "testAsync/personQuery?name=" + myPerson.name + "&age=" + myPerson.age);
        NettyHttpClient observableClient = new NettyHttpClient();
        Person person = getPersonObservable(observableClient.submit(host, port, request)).toBlockingObservable().single();
        assertEquals(myPerson, person);
    }
    
        
    @Test
    public void testUnexpectedResponse() throws Exception {
        HttpClientRequest<ByteBuf> request = HttpClientRequest.createGet(SERVICE_URI + "testAsync/throttle");
        NettyHttpClient client = new NettyHttpClient();
        Observable<HttpClientResponse<ByteBuf>> responseObservable = client.submit(host, port, request);
        final AtomicReference<Throwable> error = new AtomicReference<Throwable>();
        final CountDownLatch latch = new CountDownLatch(1);
        responseObservable.subscribe(new Action1<HttpClientResponse<ByteBuf>>() {

            @Override
            public void call(HttpClientResponse<ByteBuf> t1) {
                System.err.println("Get response: " + t1.getStatus().code());
                latch.countDown();
            }
            
        }, new Action1<Throwable>(){

            @Override
            public void call(Throwable t1) {
                error.set(t1);
                latch.countDown();
            }
        });
        latch.await();
        assertTrue(error.get() instanceof ClientException);
        ClientException ce = (ClientException) error.get();
        assertTrue(ce.getErrorType() == ClientException.ErrorType.SERVER_THROTTLED);
    }
    
    @Test
    public void testLoadBalancerThrottle() throws Exception {
        HttpClientRequest<ByteBuf> request = HttpClientRequest.createGet(SERVICE_URI + "testAsync/throttle");
        IClientConfig config = DefaultClientConfigImpl.getClientConfigWithDefaultValues().setPropertyWithType(IClientConfigKey.CommonKeys.MaxAutoRetriesNextServer, 1);
        BaseLoadBalancer lb = new BaseLoadBalancer(new DummyPing(), new AvailabilityFilteringRule());        
        NettyHttpLoadBalancingClient lbObservables = new NettyHttpLoadBalancingClient(lb, config);
        Server server = new Server(host, port);

        List<Server> servers = Lists.newArrayList(server, server, server);
        lb.setServersList(servers);
        Observable<HttpClientResponse<ByteBuf>> response = lbObservables.submitToLoadBalancer(request);
        final CountDownLatch latch = new CountDownLatch(1);
        final AtomicReference<Throwable> error = new AtomicReference<Throwable>();
        response.subscribe(new Action1<HttpClientResponse<ByteBuf>>() {

            @Override
            public void call(HttpClientResponse<ByteBuf> t1) {
                System.err.println("Get response: " + t1.getStatus().code());
                latch.countDown();
            }
            
        }, new Action1<Throwable>(){
            @Override
            public void call(Throwable t1) {
                error.set(t1);
                latch.countDown();
            }
        });
        latch.await();
        assertTrue(error.get() instanceof ClientException);
        ClientException ce = (ClientException) error.get();
        assertTrue(ce.getErrorType() == ClientException.ErrorType.NUMBEROF_RETRIES_NEXTSERVER_EXCEEDED);
        assertEquals(2, lbObservables.getServerStats(server).getSuccessiveConnectionFailureCount());
    }
    
    @Test
    public void testRedirect() throws Exception {
        HttpClientRequest<ByteBuf> request = HttpClientRequest.createGet(SERVICE_URI + "testAsync/redirect?port=" + port);
        NettyHttpClient observableClient = NettyHttpClientBuilder.newBuilder().buildHttpClient();
        
        Person person = getPersonObservable(observableClient.submit(host, port, request)).toBlockingObservable().single();
        assertEquals(EmbeddedResources.defaultPerson, person);
    } 
}
