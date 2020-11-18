/**
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 * <p>
 * http://www.apache.org/licenses/LICENSE-2.0
 * <p>
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.atlas.notification.spool;

import org.apache.atlas.AtlasException;
import org.apache.atlas.hook.FailedMessagesLogger;
import org.apache.atlas.notification.AbstractNotification;
import org.apache.atlas.notification.NotificationConsumer;
import org.apache.atlas.notification.NotificationException;
import org.apache.atlas.notification.NotificationInterface;
import org.apache.commons.configuration.Configuration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Arrays;
import java.util.List;

public class AtlasFileSpool implements NotificationInterface {
    private static final Logger LOG = LoggerFactory.getLogger(AtlasFileSpool.class);

    private final AbstractNotification notificationHandler;
    private final SpoolConfiguration   config;
    private final IndexManagement      indexManagement;
    private final Spooler              spooler;
    private final Publisher            publisher;
    private       Thread               publisherThread;
    private       Boolean              initDone = null;

    public AtlasFileSpool(Configuration configuration, AbstractNotification notificationHandler) {
        this.notificationHandler = notificationHandler;
        this.config              = new SpoolConfiguration(configuration, notificationHandler.getClass().getSimpleName());
        this.indexManagement     = new IndexManagement(config);
        this.spooler             = new Spooler(config, indexManagement);
        this.publisher           = new Publisher(config, indexManagement, notificationHandler);
    }

    @Override
    public void init(String source, Object failedMessagesLogger) {
        LOG.info("==> AtlasFileSpool.init(source={})", source);

        if (!isInitDone()) {
            try {
                config.setSource(source);

                LOG.info("{}: Initialization: Starting...", this.config.getSourceName());

                indexManagement.init();

                if (failedMessagesLogger instanceof FailedMessagesLogger) {
                    this.spooler.setFailedMessagesLogger((FailedMessagesLogger) failedMessagesLogger);
                }

                startPublisher();

                initDone = true;
            } catch (AtlasException exception) {
                LOG.error("AtlasFileSpool(source={}): initialization failed", this.config.getSourceName(), exception);

                initDone = false;
            } catch (Throwable t) {
                LOG.error("AtlasFileSpool(source={}): initialization failed, unknown error", this.config.getSourceName(), t);
            }
        } else {
            LOG.info("AtlasFileSpool.init(): initialization already done. initDone={}", initDone);
        }

        LOG.info("<== AtlasFileSpool.init(source={})", source);
    }

    @Override
    public void setCurrentUser(String user) {
        this.notificationHandler.setCurrentUser(user);
    }

    @Override
    public <T> List<NotificationConsumer<T>> createConsumers(NotificationType notificationType, int numConsumers) {
        LOG.warn("AtlasFileSpool.createConsumers(): not implemented");

        return null;
    }

    @Override
    public <T> void send(NotificationType type, T... messages) throws NotificationException {
        send(type, Arrays.asList(messages));
    }

    @Override
    public <T> void send(NotificationType type, List<T> messages) throws NotificationException {
        if (hasInitSucceeded() && (this.indexManagement.isPending() || this.publisher.isDestinationDown())) {
            if (LOG.isDebugEnabled()) {
                LOG.debug("AtlasFileSpool.send(): sending to spooler");
            }

            spooler.send(type, messages);
        } else {
            if (LOG.isDebugEnabled()) {
                LOG.debug("AtlasFileSpool.send(): sending to notificationHandler");
            }

            try {
                notificationHandler.send(type, messages);
            } catch (Exception e) {
                if (isInitDone()) {
                    LOG.info("AtlasFileSpool.send(): failed in sending to notificationHandler. Sending to spool", e);

                    publisher.setDestinationDown();

                    spooler.send(type, messages);
                } else {
                    LOG.warn("AtlasFileSpool.send(): failed in sending to notificationHandler. Not sending to spool, as it is not yet initialized", e);

                    throw e;
                }
            }
        }
    }

    @Override
    public void close() {
        try {
            spooler.setDrain();
            publisher.setDrain();
            indexManagement.stop();

            publisherThread.join();
        } catch (InterruptedException e) {
            LOG.error("Interrupted! source={}", this.config.getSourceName(), e);
        }
    }

    private void startPublisher() {
        publisherThread = new Thread(publisher);

        publisherThread.setDaemon(true);
        publisherThread.setContextClassLoader(this.getClass().getClassLoader());
        publisherThread.start();

        LOG.info("{}: publisher started!", this.config.getSourceName());
    }

    private boolean isInitDone() {
        return this.initDone != null;
    }

    private boolean hasInitSucceeded() {
        return this.initDone != null && this.initDone == true;
    }
}