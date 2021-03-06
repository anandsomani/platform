/*
 * Copyright 2010 Proofpoint, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.proofpoint.event.client;

import com.google.common.base.Throwables;
import com.google.common.io.CharStreams;
import com.proofpoint.discovery.client.HttpServiceSelector;
import com.proofpoint.discovery.client.testing.StaticHttpServiceSelector;
import com.proofpoint.http.client.ApacheHttpClient;
import com.proofpoint.http.client.AsyncHttpClient;
import com.proofpoint.http.client.HttpClientConfig;
import com.proofpoint.node.NodeInfo;
import com.proofpoint.units.Duration;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.handler.HandlerCollection;
import org.eclipse.jetty.server.nio.SelectChannelConnector;
import org.eclipse.jetty.servlet.ServletContextHandler;
import org.eclipse.jetty.servlet.ServletHolder;
import org.joda.time.DateTime;
import org.testng.annotations.AfterMethod;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Test;

import javax.servlet.ServletException;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.URI;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static com.google.common.collect.Lists.newArrayList;
import static com.proofpoint.event.client.EventTypeMetadata.getValidEventTypeMetaDataSet;
import static com.proofpoint.event.client.TestingUtils.getNormalizedJson;
import static java.util.Arrays.asList;
import static java.util.concurrent.TimeUnit.SECONDS;
import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertNull;

public class TestHttpEventClient
{
    private DummyServlet servlet;
    private HttpEventClient client;
    private Server server;
    private URI baseUri;

    @Test(expectedExceptions = ServiceUnavailableException.class, expectedExceptionsMessageRegExp = ".*is not available.*")
    public void testFutureFailsWhenServiceUnavailable()
            throws ExecutionException, InterruptedException
    {
        client = newEventClient(Collections.<URI>emptyList());

        try {
            client.post(new FixedDummyEventClass("host", new DateTime(), UUID.randomUUID(), 1, "foo")).get();
        }
        catch (ExecutionException e) {
            throw Throwables.propagate(e.getCause());
        }
    }

    @Test
    public void testCallSucceedsWhenServiceUnavailable()
            throws ExecutionException, InterruptedException
    {
        client = newEventClient(Collections.<URI>emptyList());

        client.post(new FixedDummyEventClass("host", new DateTime(), UUID.randomUUID(), 1, "foo"));

        assertNull(servlet.lastPath);
        assertNull(servlet.lastBody);
    }

    @Test
    public void testReceivesEvent()
            throws ExecutionException, InterruptedException, IOException
    {
        client = newEventClient(asList(baseUri));

        client.post(TestingUtils.getEvents()).get();

        assertEquals(servlet.lastPath, "/v2/event");
        assertEquals(servlet.lastBody, getNormalizedJson("events.json"));
    }

    @Test
    public void loadTest()
            throws ExecutionException, InterruptedException, IOException
    {
        client = newEventClient(asList(baseUri));

        List<Future<Void>> futures = newArrayList();
        for (int i = 0; i < 100; i++) {
            futures.add(client.post(TestingUtils.getEvents()));
        }

        for (Future<Void> future : futures) {
            future.get();
            System.out.println("future " + future);
        }
        assertEquals(servlet.lastPath, "/v2/event");
        assertEquals(servlet.lastBody, getNormalizedJson("events.json"));
    }

    @BeforeMethod
    public void setup()
            throws Exception
    {
        servlet = new DummyServlet();
        server = createServer(servlet);
        server.start();
    }

    @AfterMethod
    public void tearDown()
            throws Exception
    {
        if (server != null) {
            server.stop();
        }
    }

    private HttpEventClient newEventClient(List<URI> v2Uris)
    {
        HttpServiceSelector v1Selector = new StaticHttpServiceSelector("event", "general", Collections.<URI>emptyList());
        HttpServiceSelector v2Selector = new StaticHttpServiceSelector("collector", "general", v2Uris);

        HttpEventClientConfig config = new HttpEventClientConfig();

        Set<EventTypeMetadata<?>> eventTypes = getValidEventTypeMetaDataSet(FixedDummyEventClass.class);
        JsonEventWriter eventWriter = new JsonEventWriter(eventTypes, config);

        return new HttpEventClient(v1Selector,
                v2Selector,
                eventWriter,
                new NodeInfo("test"), config,
                new AsyncHttpClient(
                        new ApacheHttpClient(new HttpClientConfig().setConnectTimeout(new Duration(10, SECONDS))),
                        Executors.newCachedThreadPool()
                ));
    }

    private Server createServer(final DummyServlet servlet)
            throws Exception
    {
        int port;
        ServerSocket socket = new ServerSocket();
        try {
            socket.bind(new InetSocketAddress(0));
            port = socket.getLocalPort();
        }
        finally {
            socket.close();
        }
        baseUri = new URI("http", null, "127.0.0.1", port, null, null, null);

        Server server = new Server();
        server.setSendServerVersion(false);

        SelectChannelConnector httpConnector;
        httpConnector = new SelectChannelConnector();
        httpConnector.setName("http");
        httpConnector.setPort(port);
        server.addConnector(httpConnector);

        ServletHolder servletHolder = new ServletHolder(servlet);
        ServletContextHandler context = new ServletContextHandler(ServletContextHandler.NO_SESSIONS);
        context.addServlet(servletHolder, "/*");
        HandlerCollection handlers = new HandlerCollection();
        handlers.addHandler(context);
        server.setHandler(handlers);
        return server;
    }

    private static class DummyServlet
            extends HttpServlet
    {
        private volatile String lastPath;
        private volatile String lastBody;

        private DummyServlet()
        {
        }

        @Override
        protected void doPost(HttpServletRequest request, HttpServletResponse response)
                throws ServletException, IOException
        {
            lastPath = request.getPathInfo();
            lastBody = CharStreams.toString(new InputStreamReader(request.getInputStream(), "UTF-8"));
        }
    }
}
