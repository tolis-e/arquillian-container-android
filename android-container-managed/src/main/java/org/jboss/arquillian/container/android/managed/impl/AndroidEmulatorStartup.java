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

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.StringTokenizer;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;

import org.jboss.arquillian.android.spi.event.AndroidDeviceReady;
import org.jboss.arquillian.android.spi.event.AndroidSDCardCreate;
import org.jboss.arquillian.android.spi.event.AndroidVirtualDeviceAvailable;
import org.jboss.arquillian.container.android.api.AndroidBridge;
import org.jboss.arquillian.container.android.api.AndroidDevice;
import org.jboss.arquillian.container.android.api.AndroidExecutionException;
import org.jboss.arquillian.container.android.managed.configuration.AndroidManagedContainerConfiguration;
import org.jboss.arquillian.container.android.managed.configuration.AndroidSDK;
import org.jboss.arquillian.container.spi.context.annotation.ContainerScoped;
import org.jboss.arquillian.core.api.Event;
import org.jboss.arquillian.core.api.Instance;
import org.jboss.arquillian.core.api.InstanceProducer;
import org.jboss.arquillian.core.api.annotation.Inject;
import org.jboss.arquillian.core.api.annotation.Observes;

import com.android.ddmlib.AndroidDebugBridge;
import com.android.ddmlib.AndroidDebugBridge.IDeviceChangeListener;
import com.android.ddmlib.IDevice;

/**
 * Starts an emulator and either connects to an existing device or creates one. <br>
 * <br>
 * Observes:
 * <ul>
 * <li>{@link AndroidVirtualDeviceAvailable}</li>
 * </ul>
 *
 * Creates:
 * <ul>
 * <li>{@link AndroidEmulator}</li>
 * <li>{@link AndroidDevice}</li>
 * </ul>
 *
 * Fires:
 * <ul>
 * <li>{@link AndroidDeviceReady}</li>
 * </ul>
 *
 * @author <a href="kpiwko@redhat.com">Karel Piwko</a>
 * @author <a href="smikloso@redhat.com">Stefan Miklosovic</a>
 *
 */
public class AndroidEmulatorStartup {

    private static final Logger logger = Logger.getLogger(AndroidEmulatorStartup.class.getName());

    @Inject
    @ContainerScoped
    private InstanceProducer<AndroidEmulator> androidEmulator;

    @Inject
    @ContainerScoped
    private InstanceProducer<AndroidDevice> androidDevice;

    @Inject
    private Instance<AndroidBridge> androidBridge;

    @Inject
    private Instance<AndroidManagedContainerConfiguration> configuration;

    @Inject
    private Instance<AndroidSDK> androidSDK;

    @Inject
    private Event<AndroidDeviceReady> androidDeviceReady;

    @Inject
    private Event<AndroidSDCardCreate> androidSDCardCreate;

    public void createAndroidEmulator(@Observes AndroidVirtualDeviceAvailable event) throws AndroidExecutionException {
        if (!androidBridge.get().isConnected()) {
            throw new IllegalStateException("Android debug bridge must be connected in order to spawn the emulator");
        }

        AndroidManagedContainerConfiguration configuration = this.configuration.get();
        AndroidDevice emulator = null;

        logger.log(Level.INFO, "before androidSDCardCreate");

        androidSDCardCreate.fire(new AndroidSDCardCreate());

        logger.log(Level.INFO, "after androidSDCardCreate");

        CountDownWatch countdown = new CountDownWatch(configuration.getEmulatorBootupTimeoutInSeconds(), TimeUnit.SECONDS);
        logger.log(Level.INFO, "Waiting {0} seconds for emulator {1} to be started and connected.", new Object[] {
                countdown.timeout(), configuration.getAvdName() });

        ProcessExecutor emulatorProcessExecutor = new ProcessExecutor();
        DeviceConnectDiscovery deviceDiscovery = new DeviceConnectDiscovery();
        AndroidDebugBridge.addDeviceChangeListener(deviceDiscovery);

        Process emulatorProcess = startEmulator(emulatorProcessExecutor);

        androidEmulator.set(new AndroidEmulator(emulatorProcess));

        logger.log(Level.INFO, "Emulator process started, {0} seconds remaining to start the device {1}", new Object[] {
                countdown.timeLeft(), configuration.getAvdName() });

        waitUntilBootUpIsComplete(deviceDiscovery, emulatorProcessExecutor, countdown);

        emulator = deviceDiscovery.getDiscoveredDevice();

        AndroidDebugBridge.removeDeviceChangeListener(deviceDiscovery);

        androidDevice.set(emulator);
        androidDeviceReady.fire(new AndroidDeviceReady(emulator));
    }

    private Process startEmulator(ProcessExecutor executor) throws AndroidExecutionException {

        AndroidSDK sdk = androidSDK.get();
        AndroidManagedContainerConfiguration configuration = this.configuration.get();

        logger.log(Level.INFO, configuration.toString());

        // construct emulator command
        List<String> emulatorCommand = new ArrayList<String>(Arrays.asList(sdk.getEmulatorPath(), "-avd",
                configuration.getAvdName()));

        if (configuration.getSdCard() != null) {
            emulatorCommand.add("-sdCard");
            emulatorCommand.add(configuration.getSdCard());
        }

        logger.log(Level.INFO, "emulator command -> {0}", emulatorCommand.toString());
        emulatorCommand = getEmulatorOptions(emulatorCommand, configuration.getEmulatorOptions());
        logger.log(Level.INFO, "emulator command -> {0}", emulatorCommand.toString());
        // execute emulator
        try {
            return executor.spawn(emulatorCommand);
        } catch (InterruptedException e) {
            throw new AndroidExecutionException(e, "Unable to start emulator for {0} with options {1}",
                    configuration.getAvdName(),
                    configuration.getEmulatorOptions());
        } catch (ExecutionException e) {
            throw new AndroidExecutionException(e, "Unable to start emulator for {0} with options {1}",
                    configuration.getAvdName(),
                    configuration.getEmulatorOptions());
        }

    }

    private void waitUntilBootUpIsComplete(final DeviceConnectDiscovery deviceDiscovery, final ProcessExecutor executor,
            final CountDownWatch countdown)
            throws AndroidExecutionException {

        try {
            boolean isOnline = executor.scheduleUntilTrue(new Callable<Boolean>() {
                @Override
                public Boolean call() throws Exception {
                    return deviceDiscovery.isOnline();
                }
            }, countdown.timeLeft(), countdown.getTimeUnit().convert(1, TimeUnit.SECONDS), countdown.getTimeUnit());

            if (isOnline == false) {
                throw new IllegalStateException(
                        "No emulator device was brough online during "
                                + countdown.timeout()
                                + " seconds to Android Debug Bridge. Please increase the time limit in order to get emulator connected.");
            }

            logger.log(Level.INFO, "before two executors");

            // device is connected to ADB
            final AndroidDevice connectedDevice = deviceDiscovery.getDiscoveredDevice();

            logger.log(Level.INFO, "Serial number: " + connectedDevice.getSerialNumber());

            final String adbPath = androidSDK.get().getAdbPath();
            logger.log(Level.INFO, "adbPath: " + adbPath);
            final String serialNumber = connectedDevice.getSerialNumber();
            logger.log(Level.INFO, "serial number: " + serialNumber);

            isOnline = executor.scheduleUntilTrue(new Callable<Boolean>() {
                @Override
                public Boolean call() throws Exception {
                    // check properties of underlying process
                    List<String> props = executor.execute(Collections.<String, String> emptyMap(), adbPath,
                            "-s", serialNumber, "shell", "getprop");
                    for (String line : props) {
                        if (line.contains("[ro.runtime.firstboot]")) {
                            // boot is completed
                            return true;
                        }
                    }
                    return false;
                }
            }, countdown.timeLeft(), countdown.getTimeUnit().convert(1, TimeUnit.SECONDS), countdown.getTimeUnit());

            if (logger.isLoggable(Level.INFO)) {
                logger.log(Level.INFO, "Android emulator {0} was started within {1} seconds", new Object[] {
                        connectedDevice.getAvdName(), countdown.timeElapsed() });
            }

            if (isOnline == false) {
                throw new AndroidExecutionException("Emulator device hasn't started properly in " + countdown.timeout()
                        + " seconds. Please increase the time limit in order to get emulator booted.");
            }
        } catch (InterruptedException e) {
            throw new AndroidExecutionException(e, "Emulator device startup failed.");
        } catch (ExecutionException e) {
            logger.log(Level.INFO, e.getCause().toString());
            throw new AndroidExecutionException(e, "Emulator device startup failed.");
        }
    }

    private List<String> getEmulatorOptions(List<String> properties, String valueString) {
        if (valueString == null) {
            return properties;
        }

        // FIXME this should accept properties encapsulated in quotes as well
        StringTokenizer tokenizer = new StringTokenizer(valueString, " ");
        while (tokenizer.hasMoreTokens()) {
            String property = tokenizer.nextToken().trim();
            properties.add(property);
        }

        return properties;
    }

    private class DeviceConnectDiscovery implements IDeviceChangeListener {

        private IDevice discoveredDevice;

        private boolean online;

        @Override
        public void deviceChanged(IDevice device, int changeMask) {
            if (discoveredDevice.equals(device) && (changeMask & IDevice.CHANGE_STATE) == 1) {
                if (device.isOnline()) {
                    this.online = true;
                }
            }
        }

        @Override
        public void deviceConnected(IDevice device) {
            this.discoveredDevice = device;
            logger.log(Level.FINE, "Discovered an emulator device id={0} connected to ADB bus",
                    device.getSerialNumber());
        }

        @Override
        public void deviceDisconnected(IDevice device) {
        }

        public AndroidDevice getDiscoveredDevice() {
            return new AndroidDeviceImpl(discoveredDevice);
        }

        public boolean isOnline() {
            return online;
        }
    }
}
