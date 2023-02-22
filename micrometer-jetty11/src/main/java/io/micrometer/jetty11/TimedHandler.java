/*
 * Copyright 2022 VMware, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.micrometer.jetty11;

import io.micrometer.core.instrument.*;
import io.micrometer.core.instrument.binder.BaseUnits;
import io.micrometer.core.instrument.binder.http.DefaultHttpJakartaServletRequestTagsProvider;
import io.micrometer.core.instrument.binder.http.HttpJakartaServletRequestTagsProvider;
import jakarta.servlet.AsyncEvent;
import jakarta.servlet.AsyncListener;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.eclipse.jetty.http.HttpStatus;
import org.eclipse.jetty.server.AsyncContextEvent;
import org.eclipse.jetty.server.Handler;
import org.eclipse.jetty.server.HttpChannelState;
import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.server.handler.HandlerWrapper;
import org.eclipse.jetty.util.component.Graceful;

import java.io.IOException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Adapted from Jetty's <a href=
 * "https://github.com/eclipse/jetty.project/blob/jetty-9.4.x/jetty-server/src/main/java/org/eclipse/jetty/server/handler/StatisticsHandler.java">StatisticsHandler</a>.
 *
 * @author Jon Schneider
 * @since 1.10.0
 */
public class TimedHandler extends HandlerWrapper implements Graceful {

    private static final String SAMPLE_REQUEST_TIMER_ATTRIBUTE = "__micrometer_timer_sample";

    private static final String SAMPLE_REQUEST_LONG_TASK_TIMER_ATTRIBUTE = "__micrometer_ltt_sample";

    private final MeterRegistry registry;

    private final Iterable<Tag> tags;

    private final HttpJakartaServletRequestTagsProvider tagsProvider;

    private final Shutdown shutdown = new Shutdown(this) {
        @Override
        public boolean isShutdownDone() {
            return openRequests.activeTasks() == 0;
        }
    };

    private final LongTaskTimer openRequests;

    private final Counter asyncDispatches;

    private final Counter asyncExpires;

    private final AtomicInteger asyncWaits = new AtomicInteger();

    public TimedHandler(MeterRegistry registry, Iterable<Tag> tags) {
        this(registry, tags, new DefaultHttpJakartaServletRequestTagsProvider());
    }

    public TimedHandler(MeterRegistry registry, Iterable<Tag> tags,
            HttpJakartaServletRequestTagsProvider tagsProvider) {
        this.registry = registry;
        this.tags = tags;
        this.tagsProvider = tagsProvider;

        this.openRequests = LongTaskTimer.builder("jetty.server.dispatches.open")
            .description("Jetty dispatches that are currently in progress")
            .tags(tags)
            .register(registry);

        this.asyncDispatches = Counter.builder("jetty.server.async.dispatches")
            .description("Asynchronous dispatches")
            .tags(tags)
            .register(registry);

        this.asyncExpires = Counter.builder("jetty.server.async.expires")
            .description("Asynchronous operations that timed out before completing")
            .tags(tags)
            .register(registry);

        Gauge.builder("jetty.server.async.waits", asyncWaits, AtomicInteger::doubleValue)
            .description("Pending asynchronous wait operations")
            .baseUnit(BaseUnits.OPERATIONS)
            .tags(tags)
            .register(registry);
    }

    @Override
    public void handle(String path, Request baseRequest, HttpServletRequest request, HttpServletResponse response)
            throws IOException, ServletException {
        Timer.Sample sample = Timer.start(registry);
        LongTaskTimer.Sample requestSample;

        HttpChannelState state = baseRequest.getHttpChannelState();
        if (state.isInitial()) {
            requestSample = openRequests.start();
            request.setAttribute(SAMPLE_REQUEST_TIMER_ATTRIBUTE, sample);
            request.setAttribute(SAMPLE_REQUEST_LONG_TASK_TIMER_ATTRIBUTE, requestSample);
        }
        else {
            asyncDispatches.increment();
            request.setAttribute(SAMPLE_REQUEST_TIMER_ATTRIBUTE, sample);
            requestSample = (LongTaskTimer.Sample) request.getAttribute(SAMPLE_REQUEST_LONG_TASK_TIMER_ATTRIBUTE);
        }

        try {
            Handler handler = getHandler();
            if (handler != null && !shutdown.isShutdown() && isStarted()) {
                handler.handle(path, baseRequest, request, response);
            }
            else {
                if (!baseRequest.isHandled()) {
                    baseRequest.setHandled(true);
                }
                if (!baseRequest.getResponse().isCommitted()) {
                    response.sendError(HttpStatus.SERVICE_UNAVAILABLE_503);
                }
            }
        }
        finally {
            if (state.isSuspended()) {
                if (state.isInitial()) {
                    state.addListener(onCompletion);
                    asyncWaits.incrementAndGet();
                }
            }
            else if (state.isInitial()) {
                sample.stop(Timer.builder("jetty.server.requests")
                    .description("HTTP requests to the Jetty server")
                    .tags(tagsProvider.getTags(request, response))
                    .tags(tags)
                    .register(registry));

                requestSample.stop();

                // If we have no more dispatches, should we signal shutdown?
                if (shutdown.isShutdown()) {
                    shutdown.check();
                }
            }
            // else onCompletion will handle it.
        }
    }

    private final AsyncListener onCompletion = new AsyncListener() {
        @Override
        public void onTimeout(AsyncEvent event) {
            asyncExpires.increment();

            HttpChannelState state = ((AsyncContextEvent) event).getHttpChannelState();
            Request request = state.getBaseRequest();

            LongTaskTimer.Sample lttSample = (LongTaskTimer.Sample) request
                .getAttribute(SAMPLE_REQUEST_LONG_TASK_TIMER_ATTRIBUTE);
            lttSample.stop();
        }

        @Override
        public void onStartAsync(AsyncEvent event) {
            event.getAsyncContext().addListener(this);
        }

        @Override
        public void onError(AsyncEvent event) {
        }

        @Override
        public void onComplete(AsyncEvent event) {
            HttpChannelState state = ((AsyncContextEvent) event).getHttpChannelState();

            Request request = state.getBaseRequest();
            Timer.Sample sample = (Timer.Sample) request.getAttribute(SAMPLE_REQUEST_TIMER_ATTRIBUTE);
            LongTaskTimer.Sample lttSample = (LongTaskTimer.Sample) request
                .getAttribute(SAMPLE_REQUEST_LONG_TASK_TIMER_ATTRIBUTE);

            if (sample != null) {
                sample.stop(Timer.builder("jetty.server.requests")
                    .description("HTTP requests to the Jetty server")
                    .tags(tagsProvider.getTags(request, request.getResponse()))
                    .tags(tags)
                    .register(registry));

                lttSample.stop();
            }

            asyncWaits.decrementAndGet();

            // If we have no more dispatches, should we signal shutdown?
            if (shutdown.isShutdown()) {
                shutdown.check();
            }
        }
    };

    @Override
    protected void doStart() throws Exception {
        shutdown.cancel();
        super.doStart();
    }

    @Override
    protected void doStop() throws Exception {
        shutdown.cancel();
        super.doStop();
    }

    @Override
    public CompletableFuture<Void> shutdown() {
        return shutdown.shutdown();
    }

    @Override
    public boolean isShutdown() {
        return shutdown.isShutdown();
    }

}
