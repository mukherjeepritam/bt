/*
 * Copyright (C) 2017 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.bluetooth.btservice;

import static org.mockito.Mockito.*;

import android.bluetooth.BluetoothA2dp;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothHeadset;
import android.bluetooth.BluetoothProfile;
import android.bluetooth.BluetoothUuid;
import android.content.BroadcastReceiver;
import android.content.Intent;
import android.os.HandlerThread;
import android.os.ParcelUuid;
import android.os.TestLooperManager;
import android.support.test.InstrumentationRegistry;
import android.support.test.filters.MediumTest;
import android.support.test.runner.AndroidJUnit4;

import com.android.bluetooth.TestUtils;
import com.android.bluetooth.a2dp.A2dpService;
import com.android.bluetooth.hfp.HeadsetService;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.util.ArrayList;

@MediumTest
@RunWith(AndroidJUnit4.class)
public class PhonePolicyTest {
    private static final int MAX_CONNECTED_AUDIO_DEVICES = 5;

    private HandlerThread mHandlerThread;
    private TestLooperManager mTestLooperManager;
    private BluetoothAdapter mAdapter;
    private BluetoothDevice mTestDevice;

    @Mock private AdapterService mAdapterService;
    @Mock private ServiceFactory mServiceFactory;
    @Mock private HeadsetService mHeadsetService;
    @Mock private A2dpService mA2dpService;

    @Before
    public void setUp() throws Exception {
        MockitoAnnotations.initMocks(this);
        // Prepare the TestUtils
        TestUtils.setAdapterService(mAdapterService);
        // Configure the maximum connected audio devices
        doReturn(MAX_CONNECTED_AUDIO_DEVICES).when(mAdapterService).getMaxConnectedAudioDevices();
        // Setup the mocked factory to return mocked services
        doReturn(mHeadsetService).when(mServiceFactory).getHeadsetService();
        doReturn(mA2dpService).when(mServiceFactory).getA2dpService();
        // Start handler thread for this test
        mHandlerThread = new HandlerThread("PhonePolicyTestHandlerThread");
        mHandlerThread.start();
        mTestLooperManager = InstrumentationRegistry.getInstrumentation()
                .acquireLooperManager(mHandlerThread.getLooper());
        // Mock the looper
        doReturn(mHandlerThread.getLooper()).when(mAdapterService).getMainLooper();
        // Tell the AdapterService that it is a mock (see isMock documentation)
        doReturn(true).when(mAdapterService).isMock();
        // Must be called to initialize services
        mAdapter = BluetoothAdapter.getDefaultAdapter();
        mTestDevice = mAdapter.getRemoteDevice("00:01:02:03:04:05");
    }

    @After
    public void tearDown() throws Exception {
        mTestLooperManager.release();
        mHandlerThread.quit();
        TestUtils.clearAdapterService(mAdapterService);
    }

    /**
     * Test that when new UUIDs are refreshed for a device then we set the priorities for various
     * profiles accurately. The following profiles should have ON priorities:
     *     A2DP, HFP, HID and PAN
     */
    @Test
    public void testProcessInitProfilePriorities() {
        // Mock the HeadsetService to return undefined priority
        when(mHeadsetService.getPriority(mTestDevice)).thenReturn(
                BluetoothProfile.PRIORITY_UNDEFINED);

        // Mock the A2DP service to return undefined priority
        when(mA2dpService.getPriority(mTestDevice)).thenReturn(BluetoothProfile.PRIORITY_UNDEFINED);

        PhonePolicy phPol = new PhonePolicy(mAdapterService, mServiceFactory);

        // Get the broadcast receiver to inject events.
        BroadcastReceiver injector = phPol.getBroadcastReceiver();

        // Inject an event for UUIDs updated for a remote device with only HFP enabled
        Intent intent = new Intent(BluetoothDevice.ACTION_UUID);
        intent.putExtra(BluetoothDevice.EXTRA_DEVICE, mTestDevice);
        ParcelUuid[] uuids = new ParcelUuid[2];
        uuids[0] = BluetoothUuid.Handsfree;
        uuids[1] = BluetoothUuid.AudioSink;

        intent.putExtra(BluetoothDevice.EXTRA_UUID, uuids);
        injector.onReceive(null /* context */, intent);

        // Check that the priorities of the devices for preferred profiles are set to ON
        executePendingMessages(1);
        verify(mHeadsetService, times(1)).setPriority(eq(mTestDevice),
                eq(BluetoothProfile.PRIORITY_ON));
        verify(mA2dpService, times(1)).setPriority(eq(mTestDevice),
                eq(BluetoothProfile.PRIORITY_ON));
    }

    /**
     * Test that when the adapter is turned ON then we call autoconnect on devices that have HFP and
     * A2DP enabled. NOTE that the assumption is that we have already done the pairing previously
     * and hence the priorities for the device is already set to AUTO_CONNECT over HFP and A2DP (as
     * part of post pairing process).
     */
    @Test
    public void testAdapterOnAutoConnect() {
        // Return desired values from the mocked object(s)
        when(mAdapterService.getState()).thenReturn(BluetoothAdapter.STATE_ON);
        when(mAdapterService.isQuietModeEnabled()).thenReturn(false);

        // Return a list of bonded devices (just one)
        BluetoothDevice[] bondedDevices = new BluetoothDevice[1];
        bondedDevices[0] = mTestDevice;
        when(mAdapterService.getBondedDevices()).thenReturn(bondedDevices);

        // Return PRIORITY_AUTO_CONNECT over HFP and A2DP
        when(mHeadsetService.getPriority(mTestDevice)).thenReturn(
                BluetoothProfile.PRIORITY_AUTO_CONNECT);
        when(mA2dpService.getPriority(mTestDevice)).thenReturn(
                BluetoothProfile.PRIORITY_AUTO_CONNECT);

        PhonePolicy phPol = new PhonePolicy(mAdapterService, mServiceFactory);

        // Get the broadcast receiver to inject events
        BroadcastReceiver injector = phPol.getBroadcastReceiver();

        // Inject an event that the adapter is turned on.
        Intent intent = new Intent(BluetoothAdapter.ACTION_STATE_CHANGED);
        intent.putExtra(BluetoothAdapter.EXTRA_STATE, BluetoothAdapter.STATE_ON);
        injector.onReceive(null /* context */, intent);

        // Check that we got a request to connect over HFP and A2DP
        executePendingMessages(1);
        verify(mHeadsetService, times(1)).connect(eq(mTestDevice));
        verify(mA2dpService, times(1)).connect(eq(mTestDevice));
    }

    /**
     * Test that we will try to re-connect to a profile on a device if an attempt failed previously.
     * This is to add robustness to the connection mechanism
     */
    @Test
    public void testReconnectOnPartialConnect() {
        // Return a list of bonded devices (just one)
        BluetoothDevice[] bondedDevices = new BluetoothDevice[1];
        bondedDevices[0] = mTestDevice;
        when(mAdapterService.getBondedDevices()).thenReturn(bondedDevices);

        // Return PRIORITY_AUTO_CONNECT over HFP and A2DP. This would imply that the profiles are
        // auto-connectable.
        when(mHeadsetService.getPriority(mTestDevice)).thenReturn(
                BluetoothProfile.PRIORITY_AUTO_CONNECT);
        when(mA2dpService.getPriority(mTestDevice)).thenReturn(
                BluetoothProfile.PRIORITY_AUTO_CONNECT);

        when(mAdapterService.getState()).thenReturn(BluetoothAdapter.STATE_ON);

        PhonePolicy phPol = new PhonePolicy(mAdapterService, mServiceFactory);

        // We want to trigger (in CONNECT_OTHER_PROFILES_TIMEOUT) a call to connect A2DP
        // To enable that we need to make sure that HeadsetService returns the device as list of
        // connected devices
        ArrayList<BluetoothDevice> hsConnectedDevices = new ArrayList<>();
        hsConnectedDevices.add(mTestDevice);
        when(mHeadsetService.getConnectedDevices()).thenReturn(hsConnectedDevices);
        // Also the A2DP should say that its not connected for same device
        when(mA2dpService.getConnectionState(mTestDevice)).thenReturn(
                BluetoothProfile.STATE_DISCONNECTED);

        // Get the broadcast receiver to inject events
        BroadcastReceiver injector = phPol.getBroadcastReceiver();

        // We send a connection successful for one profile since the re-connect *only* works if we
        // have already connected successfully over one of the profiles
        Intent intent = new Intent(BluetoothHeadset.ACTION_CONNECTION_STATE_CHANGED);
        intent.putExtra(BluetoothDevice.EXTRA_DEVICE, mTestDevice);
        intent.putExtra(BluetoothProfile.EXTRA_PREVIOUS_STATE, BluetoothProfile.STATE_DISCONNECTED);
        intent.putExtra(BluetoothProfile.EXTRA_STATE, BluetoothProfile.STATE_CONNECTED);
        intent.addFlags(Intent.FLAG_RECEIVER_INCLUDE_BACKGROUND);
        injector.onReceive(null /* context */, intent);

        // Check that we get a call to A2DP connect
        executePendingMessages(2);
        verify(mA2dpService, times(1)).connect(eq(mTestDevice));
    }

    /**
     * Test that a second device will auto-connect if there is already one connected device.
     */
    @Test
    public void testAutoConnectMultipleDevices() {
        final int kMaxTestDevices = 2;
        BluetoothDevice[] testDevices = new BluetoothDevice[kMaxTestDevices];
        ArrayList<BluetoothDevice> hsConnectedDevices = new ArrayList<>();
        ArrayList<BluetoothDevice> a2dpConnectedDevices = new ArrayList<>();
        BluetoothDevice a2dpNotConnectedDevice = null;

        for (int i = 0; i < kMaxTestDevices; i++) {
            BluetoothDevice testDevice = TestUtils.getTestDevice(mAdapter, i);
            testDevices[i] = testDevice;

            // Return PRIORITY_AUTO_CONNECT over HFP and A2DP. This would imply that the profiles
            // are auto-connectable.
            when(mHeadsetService.getPriority(testDevice)).thenReturn(
                    BluetoothProfile.PRIORITY_AUTO_CONNECT);
            when(mA2dpService.getPriority(testDevice)).thenReturn(
                    BluetoothProfile.PRIORITY_AUTO_CONNECT);
            // We want to trigger (in CONNECT_OTHER_PROFILES_TIMEOUT) a call to connect A2DP
            // To enable that we need to make sure that HeadsetService returns the device as list
            // of connected devices.
            hsConnectedDevices.add(testDevice);
            // Connect A2DP for all devices except the last one
            if (i < kMaxTestDevices - 1) {
                a2dpConnectedDevices.add(testDevice);
            } else {
                a2dpNotConnectedDevice = testDevice;
            }
        }
        when(mAdapterService.getBondedDevices()).thenReturn(testDevices);
        when(mAdapterService.getState()).thenReturn(BluetoothAdapter.STATE_ON);
        when(mHeadsetService.getConnectedDevices()).thenReturn(hsConnectedDevices);
        when(mA2dpService.getConnectedDevices()).thenReturn(a2dpConnectedDevices);
        // One of the A2DP devices is not connected
        when(mA2dpService.getConnectionState(a2dpNotConnectedDevice)).thenReturn(
                BluetoothProfile.STATE_DISCONNECTED);

        // Get the broadcast receiver to inject events
        PhonePolicy phPol = new PhonePolicy(mAdapterService, mServiceFactory);
        BroadcastReceiver injector = phPol.getBroadcastReceiver();

        // We send a connection successful for one profile since the re-connect *only* works if we
        // have already connected successfully over one of the profiles
        Intent intent = new Intent(BluetoothHeadset.ACTION_CONNECTION_STATE_CHANGED);
        intent.putExtra(BluetoothDevice.EXTRA_DEVICE, a2dpNotConnectedDevice);
        intent.putExtra(BluetoothProfile.EXTRA_PREVIOUS_STATE, BluetoothProfile.STATE_DISCONNECTED);
        intent.putExtra(BluetoothProfile.EXTRA_STATE, BluetoothProfile.STATE_CONNECTED);
        intent.addFlags(Intent.FLAG_RECEIVER_INCLUDE_BACKGROUND);
        injector.onReceive(null /* context */, intent);

        // Check that we get a call to A2DP connect
        executePendingMessages(2);
        verify(mA2dpService, times(1)).connect(eq(a2dpNotConnectedDevice));
    }

    /**
     * Test that the connect priority of all devices are set as appropriate if there is one
     * connected device.
     * - The HFP and A2DP connect priority for connected devices is set to
     *   BluetoothProfile.PRIORITY_AUTO_CONNECT
     * - The HFP and A2DP connect priority for bonded devices is set to
     *   BluetoothProfile.PRIORITY_ON
     */
    @Test
    public void testSetPriorityMultipleDevices() {
        // testDevices[0] - connected for both HFP and A2DP
        // testDevices[1] - connected only for HFP - will auto-connect for A2DP
        // testDevices[2] - connected only for A2DP - will auto-connect for HFP
        // testDevices[3] - not connected
        final int kMaxTestDevices = 4;
        BluetoothDevice[] testDevices = new BluetoothDevice[kMaxTestDevices];
        ArrayList<BluetoothDevice> hsConnectedDevices = new ArrayList<>();
        ArrayList<BluetoothDevice> a2dpConnectedDevices = new ArrayList<>();

        for (int i = 0; i < kMaxTestDevices; i++) {
            BluetoothDevice testDevice = TestUtils.getTestDevice(mAdapter, i);
            testDevices[i] = testDevice;

            // Connect HFP and A2DP for each device as appropriate.
            // Return PRIORITY_AUTO_CONNECT only for testDevices[0]
            if (i == 0) {
                hsConnectedDevices.add(testDevice);
                a2dpConnectedDevices.add(testDevice);
                when(mHeadsetService.getPriority(testDevice)).thenReturn(
                        BluetoothProfile.PRIORITY_AUTO_CONNECT);
                when(mA2dpService.getPriority(testDevice)).thenReturn(
                        BluetoothProfile.PRIORITY_AUTO_CONNECT);
            }
            if (i == 1) {
                hsConnectedDevices.add(testDevice);
                when(mHeadsetService.getPriority(testDevice)).thenReturn(
                        BluetoothProfile.PRIORITY_ON);
                when(mA2dpService.getPriority(testDevice)).thenReturn(
                        BluetoothProfile.PRIORITY_ON);
            }
            if (i == 2) {
                a2dpConnectedDevices.add(testDevice);
                when(mHeadsetService.getPriority(testDevice)).thenReturn(
                        BluetoothProfile.PRIORITY_ON);
                when(mA2dpService.getPriority(testDevice)).thenReturn(
                        BluetoothProfile.PRIORITY_ON);
            }
            if (i == 3) {
                // Device not connected
                when(mHeadsetService.getPriority(testDevice)).thenReturn(
                        BluetoothProfile.PRIORITY_ON);
                when(mA2dpService.getPriority(testDevice)).thenReturn(
                        BluetoothProfile.PRIORITY_ON);
            }
        }
        when(mAdapterService.getBondedDevices()).thenReturn(testDevices);
        when(mAdapterService.getState()).thenReturn(BluetoothAdapter.STATE_ON);
        when(mHeadsetService.getConnectedDevices()).thenReturn(hsConnectedDevices);
        when(mA2dpService.getConnectedDevices()).thenReturn(a2dpConnectedDevices);
        // Some of the devices are not connected
        // testDevices[0] - connected for both HFP and A2DP
        when(mHeadsetService.getConnectionState(testDevices[0])).thenReturn(
                BluetoothProfile.STATE_CONNECTED);
        when(mA2dpService.getConnectionState(testDevices[0])).thenReturn(
                BluetoothProfile.STATE_CONNECTED);
        // testDevices[1] - connected only for HFP - will auto-connect for A2DP
        when(mHeadsetService.getConnectionState(testDevices[1])).thenReturn(
                BluetoothProfile.STATE_CONNECTED);
        when(mA2dpService.getConnectionState(testDevices[1])).thenReturn(
                BluetoothProfile.STATE_DISCONNECTED);
        // testDevices[2] - connected only for A2DP - will auto-connect for HFP
        when(mHeadsetService.getConnectionState(testDevices[2])).thenReturn(
                BluetoothProfile.STATE_DISCONNECTED);
        when(mA2dpService.getConnectionState(testDevices[2])).thenReturn(
                BluetoothProfile.STATE_CONNECTED);
        // testDevices[3] - not connected
        when(mHeadsetService.getConnectionState(testDevices[3])).thenReturn(
                BluetoothProfile.STATE_DISCONNECTED);
        when(mA2dpService.getConnectionState(testDevices[3])).thenReturn(
                BluetoothProfile.STATE_DISCONNECTED);

        // Get the broadcast receiver to inject events
        PhonePolicy phPol = new PhonePolicy(mAdapterService, mServiceFactory);
        BroadcastReceiver injector = phPol.getBroadcastReceiver();

        // Generate connection state changed for HFP for testDevices[1] and trigger
        // auto-connect for A2DP.
        Intent intent = new Intent(BluetoothHeadset.ACTION_CONNECTION_STATE_CHANGED);
        intent.putExtra(BluetoothDevice.EXTRA_DEVICE, testDevices[1]);
        intent.putExtra(BluetoothProfile.EXTRA_PREVIOUS_STATE, BluetoothProfile.STATE_DISCONNECTED);
        intent.putExtra(BluetoothProfile.EXTRA_STATE, BluetoothProfile.STATE_CONNECTED);
        intent.addFlags(Intent.FLAG_RECEIVER_INCLUDE_BACKGROUND);
        injector.onReceive(null /* context */, intent);
        // Check that we get a call to A2DP connect
        executePendingMessages(2);
        verify(mA2dpService, times(1)).connect(eq(testDevices[1]));

        // testDevices[1] auto-connect completed for A2DP
        a2dpConnectedDevices.add(testDevices[1]);
        when(mA2dpService.getConnectedDevices()).thenReturn(a2dpConnectedDevices);
        when(mA2dpService.getConnectionState(testDevices[1])).thenReturn(
                BluetoothProfile.STATE_CONNECTED);

        // Check the connect priorities for all devices
        // - testDevices[0] - connected for HFP and A2DP: setPriority() should not be called
        // - testDevices[1] - connection state changed for HFP - auto-connect completed for A2DP
        //                    expect setPriority(PRIORITY_AUTO_CONNECT) for HFP
        // - testDevices[2] - connected for A2DP: setPriority() should not be called
        // - testDevices[3] - not connected for HFP nor A2DP: setPriority() should not be called
        verify(mHeadsetService, times(0)).setPriority(eq(testDevices[0]), anyInt());
        verify(mA2dpService, times(0)).setPriority(eq(testDevices[0]), anyInt());
        verify(mHeadsetService, times(1)).setPriority(eq(testDevices[1]),
                                                      eq(BluetoothProfile.PRIORITY_AUTO_CONNECT));
        verify(mA2dpService, times(0)).setPriority(eq(testDevices[1]), anyInt());
        verify(mHeadsetService, times(0)).setPriority(eq(testDevices[2]), anyInt());
        verify(mA2dpService, times(0)).setPriority(eq(testDevices[2]), anyInt());
        verify(mHeadsetService, times(0)).setPriority(eq(testDevices[3]), anyInt());
        verify(mA2dpService, times(0)).setPriority(eq(testDevices[3]), anyInt());
        clearInvocations(mHeadsetService, mA2dpService);

        // Generate connection state changed for A2DP for testDevices[2] and trigger
        // auto-connect for HFP.
        intent = new Intent(BluetoothA2dp.ACTION_CONNECTION_STATE_CHANGED);
        intent.putExtra(BluetoothDevice.EXTRA_DEVICE, testDevices[2]);
        intent.putExtra(BluetoothProfile.EXTRA_PREVIOUS_STATE, BluetoothProfile.STATE_DISCONNECTED);
        intent.putExtra(BluetoothProfile.EXTRA_STATE, BluetoothProfile.STATE_CONNECTED);
        intent.addFlags(Intent.FLAG_RECEIVER_INCLUDE_BACKGROUND);
        injector.onReceive(null /* context */, intent);
        // Check that we get a call to HFP connect
        executePendingMessages(2);
        verify(mHeadsetService, times(1)).connect(eq(testDevices[2]));

        // testDevices[2] auto-connect completed for HFP
        hsConnectedDevices.add(testDevices[2]);
        when(mHeadsetService.getConnectedDevices()).thenReturn(hsConnectedDevices);
        when(mHeadsetService.getConnectionState(testDevices[2])).thenReturn(
                BluetoothProfile.STATE_CONNECTED);

        // Check the connect priorities for all devices
        // - testDevices[0] - connected for HFP and A2DP: setPriority() should not be called
        // - testDevices[1] - connected for HFP and A2DP: setPriority() should not be called
        // - testDevices[2] - connection state changed for A2DP - auto-connect completed for HFP
        //                    expected setPriority(PRIORITY_AUTO_CONNECT) for A2DP
        // - testDevices[3] - not connected for HFP nor A2DP: setPriority() should not be called
        verify(mHeadsetService, times(0)).setPriority(eq(testDevices[0]), anyInt());
        verify(mA2dpService, times(0)).setPriority(eq(testDevices[0]), anyInt());
        verify(mHeadsetService, times(0)).setPriority(eq(testDevices[1]), anyInt());
        verify(mA2dpService, times(0)).setPriority(eq(testDevices[1]), anyInt());
        verify(mHeadsetService, times(0)).setPriority(eq(testDevices[2]), anyInt());
        verify(mA2dpService, times(1)).setPriority(eq(testDevices[2]),
                                                   eq(BluetoothProfile.PRIORITY_AUTO_CONNECT));
        verify(mHeadsetService, times(0)).setPriority(eq(testDevices[3]), anyInt());
        verify(mA2dpService, times(0)).setPriority(eq(testDevices[3]), anyInt());
        clearInvocations(mHeadsetService, mA2dpService);
    }

    /**
     * Test that we will not try to reconnect on a profile if all the connections failed
     */
    @Test
    public void testNoReconnectOnNoConnect() {
        // Return a list of bonded devices (just one)
        BluetoothDevice[] bondedDevices = new BluetoothDevice[1];
        bondedDevices[0] = mTestDevice;
        when(mAdapterService.getBondedDevices()).thenReturn(bondedDevices);

        // Return PRIORITY_AUTO_CONNECT over HFP and A2DP. This would imply that the profiles are
        // auto-connectable.
        when(mHeadsetService.getPriority(mTestDevice)).thenReturn(
                BluetoothProfile.PRIORITY_AUTO_CONNECT);
        when(mA2dpService.getPriority(mTestDevice)).thenReturn(
                BluetoothProfile.PRIORITY_AUTO_CONNECT);

        when(mAdapterService.getState()).thenReturn(BluetoothAdapter.STATE_ON);

        // Return an empty list simulating that the above connection successful was nullified
        ArrayList<BluetoothDevice> hsConnectedDevices = new ArrayList<>();
        when(mHeadsetService.getConnectedDevices()).thenReturn(hsConnectedDevices);

        // Also the A2DP should say that its not connected for same device
        when(mA2dpService.getConnectionState(mTestDevice)).thenReturn(
                BluetoothProfile.STATE_DISCONNECTED);

        PhonePolicy phPol = new PhonePolicy(mAdapterService, mServiceFactory);

        // Get the broadcast receiver to inject events
        BroadcastReceiver injector = phPol.getBroadcastReceiver();

        // We send a connection successful for one profile since the re-connect *only* works if we
        // have already connected successfully over one of the profiles
        Intent intent = new Intent(BluetoothHeadset.ACTION_CONNECTION_STATE_CHANGED);
        intent.putExtra(BluetoothDevice.EXTRA_DEVICE, mTestDevice);
        intent.putExtra(BluetoothProfile.EXTRA_PREVIOUS_STATE, BluetoothProfile.STATE_DISCONNECTED);
        intent.putExtra(BluetoothProfile.EXTRA_STATE, BluetoothProfile.STATE_CONNECTED);
        intent.addFlags(Intent.FLAG_RECEIVER_INCLUDE_BACKGROUND);
        injector.onReceive(null /* context */, intent);

        // Check that we don't get any calls to reconnect
        executePendingMessages(1);
        verify(mA2dpService, never()).connect(eq(mTestDevice));
        verify(mHeadsetService, never()).connect(eq(mTestDevice));
    }

    /**
     * Test that a device with no supported uuids is initialized properly and does not crash the
     * stack
     */
    @Test
    public void testNoSupportedUuids() {
        // Mock the HeadsetService to return undefined priority
        when(mHeadsetService.getPriority(mTestDevice)).thenReturn(
                BluetoothProfile.PRIORITY_UNDEFINED);

        // Mock the A2DP service to return undefined priority
        when(mA2dpService.getPriority(mTestDevice)).thenReturn(BluetoothProfile.PRIORITY_UNDEFINED);

        PhonePolicy phPol = new PhonePolicy(mAdapterService, mServiceFactory);

        // Get the broadcast receiver to inject events.
        BroadcastReceiver injector = phPol.getBroadcastReceiver();

        // Inject an event for UUIDs updated for a remote device with only HFP enabled
        Intent intent = new Intent(BluetoothDevice.ACTION_UUID);
        intent.putExtra(BluetoothDevice.EXTRA_DEVICE, mTestDevice);

        // Put no UUIDs
        injector.onReceive(null /* context */, intent);

        // Check that we do not crash and not call any setPriority methods
        executePendingMessages(1);
        verify(mHeadsetService, never()).setPriority(eq(mTestDevice),
                eq(BluetoothProfile.PRIORITY_ON));
        verify(mA2dpService, never()).setPriority(eq(mTestDevice),
                eq(BluetoothProfile.PRIORITY_ON));
    }

    private void executePendingMessages(int numMessage) {
        while (numMessage > 0) {
            mTestLooperManager.execute(mTestLooperManager.next());
            numMessage--;
        }
    }
}
