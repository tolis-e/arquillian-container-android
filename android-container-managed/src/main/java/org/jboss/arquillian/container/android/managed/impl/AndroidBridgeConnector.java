/*
 * JBoss, Home of Professional Open Source
 * Copyright 2011, Red Hat Middleware LLC, and individual contributors
 * by the @authors tag. See the copyright.txt in the distribution for a
 * full listing of individual contributors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * http://www.apache.org/licenses/LICENSE-2.0
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.jboss.arquillian.container.android.managed.impl;

import java.io.File;
import java.util.logging.Logger;

import org.jboss.arquillian.android.spi.event.AndroidBridgeInitialized;
import org.jboss.arquillian.android.spi.event.AndroidBridgeTerminated;
import org.jboss.arquillian.android.spi.event.AndroidContainerConfigured;
import org.jboss.arquillian.android.spi.event.AndroidDeviceShutdown;
import org.jboss.arquillian.container.android.api.AndroidBridge;
import org.jboss.arquillian.container.android.api.AndroidExecutionException;
import org.jboss.arquillian.container.android.managed.configuration.AndroidManagedContainerConfiguration;
import org.jboss.arquillian.container.android.managed.configuration.AndroidSDK;
import org.jboss.arquillian.core.api.Event;
import org.jboss.arquillian.core.api.InstanceProducer;
import org.jboss.arquillian.core.api.annotation.Inject;
import org.jboss.arquillian.core.api.annotation.Observes;
import org.jboss.arquillian.test.spi.annotation.SuiteScoped;

/**
 * Creator and destructor of Android Bridge.
 *
 * This must be called after Android Container initialization.
 *
 * Observes:
 * <ul>
 * <li>{@link AndroidContainerConfigured}</li>
 * </ul>
 *
 * Creates:
 * <ul>
 * <li>{@link AndroidBridge}</li>
 * </ul>
 *
 * Fires:
 * <ul>
 * <li>{@link AndroidBridgeTerminated}</li>
 * <li>{@link AndroidBridgeInitialized}</li>
 * </ul>
 *
 * @author <a href="kpiwko@redhat.com">Karel Piwko</a>
 * @author <a href="smikloso@redhat.com">Stefan Miklosovic</a>
 *
 */
public class AndroidBridgeConnector {

    private static final Logger log = Logger.getLogger(AndroidBridgeConnector.class.getName());

    @Inject
    @SuiteScoped
    private InstanceProducer<AndroidBridge> androidBridge;

    @Inject
    private Event<AndroidBridgeInitialized> adbInitialized;

    @Inject
    private Event<AndroidBridgeTerminated> adbTerminated;

    /**
     * Initializes Android Debug Bridge and fires {@link AndroidBridgeInitialized} event.
     *
     * @param event
     * @param sdk
     * @param configuration
     * @throws AndroidExecutionException
     */
    public void initAndroidDebugBridge(@Observes AndroidContainerConfigured event, AndroidSDK sdk,
            AndroidManagedContainerConfiguration configuration) throws AndroidExecutionException {

        long start = System.currentTimeMillis();
        log.info("Initializing Android Debug Bridge");
        AndroidBridge bridge = new AndroidBridgeImpl(new File(sdk.getAdbPath()), configuration.isForce());
        bridge.connect();
        long delta = System.currentTimeMillis() - start;
        log.info("Android debug Bridge was initialized in " + delta + "ms");
        androidBridge.set(bridge);

        adbInitialized.fire(new AndroidBridgeInitialized(bridge));
    }

    /**
     * Destroys Android Debug Bridge and fires {@link AndroidBridgeTerminated} event.
     *
     * @param event
     * @throws AndroidExecutionException
     */
    public void destroyAndroidDebugBridge(@Observes AndroidDeviceShutdown event) throws AndroidExecutionException {
        androidBridge.get().disconnect();
        adbTerminated.fire(new AndroidBridgeTerminated());
    }
}
