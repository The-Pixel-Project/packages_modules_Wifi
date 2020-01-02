/*
 * Copyright (C) 2016 The Android Open Source Project
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

package com.android.server.wifi;

import static android.net.wifi.WifiManager.WIFI_FEATURE_OWE;

import static com.android.server.wifi.WifiConfigurationTestUtil.SECURITY_EAP;
import static com.android.server.wifi.WifiConfigurationTestUtil.SECURITY_NONE;
import static com.android.server.wifi.WifiConfigurationTestUtil.SECURITY_PSK;
import static com.android.server.wifi.WifiNetworkSelector.experimentIdFromIdentifier;

import static org.hamcrest.Matchers.*;
import static org.junit.Assert.*;
import static org.mockito.Mockito.*;

import android.annotation.NonNull;
import android.content.Context;
import android.net.wifi.ScanResult;
import android.net.wifi.SupplicantState;
import android.net.wifi.WifiConfiguration;
import android.net.wifi.WifiConfiguration.NetworkSelectionStatus;
import android.net.wifi.WifiInfo;
import android.os.SystemClock;
import android.util.LocalLog;

import androidx.test.filters.SmallTest;

import com.android.server.wifi.WifiNetworkSelectorTestUtil.ScanDetailsAndWifiConfigs;
import com.android.server.wifi.hotspot2.NetworkDetail;
import com.android.server.wifi.proto.nano.WifiMetricsProto;
import com.android.wifi.resources.R;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.mockito.Spy;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;

/**
 * Unit tests for {@link com.android.server.wifi.WifiNetworkSelector}.
 */
@SmallTest
public class WifiNetworkSelectorTest extends WifiBaseTest {

    private static final int RSSI_BUMP = 1;
    private static final int DUMMY_EVALUATOR_ID_1 = -2; // lowest index
    private static final int DUMMY_EVALUATOR_ID_2 = -1;
    private static final HashSet<String> EMPTY_BLACKLIST = new HashSet<>();

    /** Sets up test. */
    @Before
    public void setUp() throws Exception {
        MockitoAnnotations.initMocks(this);
        setupContext();
        setupResources();
        setupWifiConfigManager();
        setupWifiInfo();

        mScoringParams = new ScoringParams();
        setupThresholds();

        mLocalLog = new LocalLog(512);
        mThroughputPredictor = new ThroughputPredictor(mContext);

        mWifiNetworkSelector = new WifiNetworkSelector(mContext,
                mWifiScoreCard,
                mScoringParams,
                mWifiConfigManager, mClock,
                mLocalLog,
                mWifiMetrics,
                mWifiNative,
                mThroughputPredictor
        );
        mWifiNetworkSelector.registerNetworkNominator(mDummyEvaluator);
        mDummyEvaluator.setEvaluatorToSelectCandidate(true);
        when(mClock.getElapsedSinceBootMillis()).thenReturn(SystemClock.elapsedRealtime());
        when(mWifiScoreCard.lookupBssid(any(), any())).thenReturn(mPerBssid);
        mCompatibilityScorer = new CompatibilityScorer(mScoringParams);
        mScoreCardBasedScorer = new ScoreCardBasedScorer(mScoringParams);
        mThroughputScorer = new ThroughputScorer(mScoringParams);
        when(mWifiNative.getClientInterfaceName()).thenReturn("wlan0");
        if (WifiNetworkSelector.PRESET_CANDIDATE_SCORER_NAME.equals(
                mThroughputScorer.getIdentifier())) {
            mWifiNetworkSelector.registerCandidateScorer(mThroughputScorer);
        } else {
            mWifiNetworkSelector.registerCandidateScorer(mCompatibilityScorer);
        }
    }

    /** Cleans up test. */
    @After
    public void cleanup() {
        validateMockitoUsage();
    }

    /**
     * All this dummy network evaluator does is to pick the specified network in the scan results.
     */
    public class DummyNetworkNominator implements WifiNetworkSelector.NetworkNominator {
        private static final String NAME = "DummyNetworkEvaluator";

        private boolean mNominatorShouldSelectCandidate = true;

        private int mNetworkIndexToReturn;
        private int mNominatorIdToReturn;

        public DummyNetworkNominator(int networkIndexToReturn, int nominatorIdToReturn) {
            mNetworkIndexToReturn = networkIndexToReturn;
            mNominatorIdToReturn = nominatorIdToReturn;
        }

        public DummyNetworkNominator() {
            this(0, DUMMY_EVALUATOR_ID_1);
        }

        public int getNetworkIndexToReturn() {
            return mNetworkIndexToReturn;
        }

        public void setNetworkIndexToReturn(int networkIndexToReturn) {
            mNetworkIndexToReturn = networkIndexToReturn;
        }

        @Override
        public @NominatorId int getId() {
            return mNominatorIdToReturn;
        }

        @Override
        public String getName() {
            return NAME;
        }

        @Override
        public void update(List<ScanDetail> scanDetails) {}

        /**
         * Sets whether the nominator should return a candidate for connection or null.
         */
        public void setEvaluatorToSelectCandidate(boolean shouldSelectCandidate) {
            mNominatorShouldSelectCandidate = shouldSelectCandidate;
        }

        /**
         * This NetworkEvaluator can be configured to return a candidate or null.  If returning a
         * candidate, the first entry in the provided scanDetails will be selected. This requires
         * that the mock WifiConfigManager be set up to return a WifiConfiguration for the first
         * scanDetail entry, through
         * {@link WifiNetworkSelectorTestUtil#setupScanDetailsAndConfigStore}.
         */
        @Override
        public void nominateNetworks(List<ScanDetail> scanDetails,
                    WifiConfiguration currentNetwork, String currentBssid, boolean connected,
                    boolean untrustedNetworkAllowed,
                    @NonNull OnConnectableListener onConnectableListener) {
            if (!mNominatorShouldSelectCandidate) {
                return;
            }
            for (ScanDetail scanDetail : scanDetails) {
                WifiConfiguration config =
                        mWifiConfigManager.getConfiguredNetworkForScanDetailAndCache(scanDetail);
                mWifiConfigManager.setNetworkCandidateScanResult(
                        config.networkId, scanDetail.getScanResult(), 100);
            }
            ScanDetail scanDetailToReturn = scanDetails.get(mNetworkIndexToReturn);
            WifiConfiguration configToReturn  =
                    mWifiConfigManager.getConfiguredNetworkForScanDetailAndCache(
                            scanDetailToReturn);
            assertNotNull("Saved network must not be null", configToReturn);
            onConnectableListener.onConnectable(scanDetailToReturn, configToReturn);
        }
    }

    private WifiNetworkSelector mWifiNetworkSelector = null;
    private DummyNetworkNominator mDummyEvaluator = new DummyNetworkNominator();
    @Mock private WifiConfigManager mWifiConfigManager;
    @Mock private Context mContext;
    @Mock private WifiScoreCard mWifiScoreCard;
    @Mock private WifiScoreCard.PerBssid mPerBssid;
    @Mock private WifiCandidates.CandidateScorer mCandidateScorer;
    @Mock private WifiMetrics mWifiMetrics;
    @Mock private WifiNative mWifiNative;
    @Mock private WifiNetworkSelector.NetworkNominator mNetworkNominator;

    // For simulating the resources, we use a Spy on a MockResource
    // (which is really more of a stub than a mock, in spite if its name).
    // This is so that we get errors on any calls that we have not explicitly set up.
    @Spy private MockResources mResource = new MockResources();
    @Mock private WifiInfo mWifiInfo;
    @Mock private Clock mClock;
    @Mock private NetworkDetail mNetworkDetail;
    private ScoringParams mScoringParams;
    private LocalLog mLocalLog;
    private int mThresholdMinimumRssi2G;
    private int mThresholdMinimumRssi5G;
    private int mThresholdQualifiedRssi2G;
    private int mThresholdQualifiedRssi5G;
    private int mMinPacketRateActiveTraffic;
    private CompatibilityScorer mCompatibilityScorer;
    private ScoreCardBasedScorer mScoreCardBasedScorer;
    private ThroughputScorer mThroughputScorer;
    private ThroughputPredictor mThroughputPredictor;

    private void setupContext() {
        when(mContext.getResources()).thenReturn(mResource);
    }

    private int setupIntegerResource(int resourceName, int value) {
        doReturn(value).when(mResource).getInteger(resourceName);
        return value;
    }

    private void setupResources() {
        doReturn(true).when(mResource).getBoolean(
                R.bool.config_wifi_framework_enable_associated_network_selection);
        mMinPacketRateActiveTraffic = setupIntegerResource(
                R.integer.config_wifiFrameworkMinPacketPerSecondActiveTraffic, 16);
        doReturn(false).when(mResource).getBoolean(R.bool.config_wifi_11ax_supported);
        doReturn(false).when(mResource).getBoolean(
                R.bool.config_wifi_contiguous_160mhz_supported);
        doReturn(2).when(mResource).getInteger(
                R.integer.config_wifi_max_num_spatial_stream_supported);
    }

    private void setupThresholds() {
        mThresholdMinimumRssi2G = mScoringParams.getEntryRssi(ScoringParams.BAND2);
        mThresholdMinimumRssi5G = mScoringParams.getEntryRssi(ScoringParams.BAND5);

        mThresholdQualifiedRssi2G = mScoringParams.getSufficientRssi(ScoringParams.BAND2);
        mThresholdQualifiedRssi5G = mScoringParams.getSufficientRssi(ScoringParams.BAND5);
    }

    private void setupWifiInfo() {
        // simulate a disconnected state
        when(mWifiInfo.getSupplicantState()).thenReturn(SupplicantState.DISCONNECTED);
        when(mWifiInfo.is24GHz()).thenReturn(true);
        when(mWifiInfo.is5GHz()).thenReturn(false);
        when(mWifiInfo.getFrequency()).thenReturn(2400);
        when(mWifiInfo.getRssi()).thenReturn(-70);
        when(mWifiInfo.getNetworkId()).thenReturn(WifiConfiguration.INVALID_NETWORK_ID);
        when(mWifiInfo.getBSSID()).thenReturn(null);
    }

    private void setupWifiConfigManager() {
        setupWifiConfigManager(WifiConfiguration.INVALID_NETWORK_ID);
    }

    private void setupWifiConfigManager(int networkId) {
        when(mWifiConfigManager.getLastSelectedNetwork())
                .thenReturn(networkId);
    }

    /**
     * No network selection if scan result is empty.
     *
     * ClientModeImpl is in disconnected state.
     * scanDetails is empty.
     *
     * Expected behavior: no network recommended by Network Selector
     */
    @Test
    public void emptyScanResults() {
        String[] ssids = new String[0];
        String[] bssids = new String[0];
        int[] freqs = new int[0];
        String[] caps = new String[0];
        int[] levels = new int[0];
        int[] securities = new int[0];

        ScanDetailsAndWifiConfigs scanDetailsAndConfigs =
                WifiNetworkSelectorTestUtil.setupScanDetailsAndConfigStore(ssids, bssids,
                    freqs, caps, levels, securities, mWifiConfigManager, mClock);
        List<ScanDetail> scanDetails = scanDetailsAndConfigs.getScanDetails();
        HashSet<String> blacklist = new HashSet<String>();
        WifiConfiguration candidate = mWifiNetworkSelector.selectNetwork(scanDetails,
                blacklist, mWifiInfo, false, true, false);
        assertEquals("Expect null configuration", null, candidate);
        assertTrue(mWifiNetworkSelector.getConnectableScanDetails().isEmpty());
    }


    /**
     * No network selection if the RSSI values in scan result are too low.
     *
     * ClientModeImpl is in disconnected state.
     * scanDetails contains a 2.4GHz and a 5GHz network, but both with RSSI lower than
     * the threshold
     *
     * Expected behavior: no network recommended by Network Selector
     */
    @Test
    public void verifyMinimumRssiThreshold() {
        String[] ssids = {"\"test1\"", "\"test2\""};
        String[] bssids = {"6c:f3:7f:ae:8c:f3", "6c:f3:7f:ae:8c:f4"};
        int[] freqs = {2437, 5180};
        String[] caps = {"[WPA2-PSK][ESS]", "[WPA2-EAP-CCMP][ESS]"};
        int[] levels = {mThresholdMinimumRssi2G - 1, mThresholdMinimumRssi5G - 1};
        int[] securities = {SECURITY_PSK, SECURITY_EAP};

        ScanDetailsAndWifiConfigs scanDetailsAndConfigs =
                WifiNetworkSelectorTestUtil.setupScanDetailsAndConfigStore(ssids, bssids,
                    freqs, caps, levels, securities, mWifiConfigManager, mClock);
        List<ScanDetail> scanDetails = scanDetailsAndConfigs.getScanDetails();
        HashSet<String> blacklist = new HashSet<String>();
        WifiConfiguration candidate = mWifiNetworkSelector.selectNetwork(scanDetails,
                blacklist, mWifiInfo, false, true, false);
        assertEquals("Expect null configuration", null, candidate);
        assertTrue(mWifiNetworkSelector.getConnectableScanDetails().isEmpty());
    }

    /**
     * No network selection if WiFi is connected and it is too short from last
     * network selection.
     *
     * ClientModeImpl is in connected state.
     * scanDetails contains two valid networks.
     * Perform a network selection right after the first one.
     *
     * Expected behavior: no network recommended by Network Selector
     */
    @Test
    public void verifyMinimumTimeGapWhenConnected() {
        String[] ssids = {"\"test1\"", "\"test2\""};
        String[] bssids = {"6c:f3:7f:ae:8c:f3", "6c:f3:7f:ae:8c:f4"};
        int[] freqs = {2437, 5180};
        String[] caps = {"[WPA2-PSK][ESS]", "[WPA2-EAP-CCMP][ESS]"};
        int[] levels = {mThresholdMinimumRssi2G + RSSI_BUMP, mThresholdMinimumRssi5G + RSSI_BUMP};
        int[] securities = {SECURITY_PSK, SECURITY_EAP};

        // Make a network selection.
        ScanDetailsAndWifiConfigs scanDetailsAndConfigs =
                WifiNetworkSelectorTestUtil.setupScanDetailsAndConfigStore(ssids, bssids,
                        freqs, caps, levels, securities, mWifiConfigManager, mClock);
        List<ScanDetail> scanDetails = scanDetailsAndConfigs.getScanDetails();
        HashSet<String> blacklist = new HashSet<String>();
        WifiConfiguration candidate = mWifiNetworkSelector.selectNetwork(scanDetails,
                blacklist, mWifiInfo, false, true, false);

        when(mClock.getElapsedSinceBootMillis()).thenReturn(SystemClock.elapsedRealtime()
                + WifiNetworkSelector.MINIMUM_NETWORK_SELECTION_INTERVAL_MS - 2000);

        // Do another network selection with CMI in CONNECTED state.
        candidate = mWifiNetworkSelector.selectNetwork(scanDetails,
                blacklist, mWifiInfo, true, false, false);

        assertEquals("Expect null configuration", null, candidate);
        assertTrue(mWifiNetworkSelector.getConnectableScanDetails().isEmpty());
    }

    /**
     * Perform network selection if WiFi is disconnected even if it is too short from last
     * network selection.
     *
     * ClientModeImpl is in disconnected state.
     * scanDetails contains two valid networks.
     * Perform a network selection right after the first one.
     *
     * Expected behavior: the first network is recommended by Network Selector
     */
    @Test
    public void verifyNoMinimumTimeGapWhenDisconnected() {
        String[] ssids = {"\"test1\"", "\"test2\""};
        String[] bssids = {"6c:f3:7f:ae:8c:f3", "6c:f3:7f:ae:8c:f4"};
        int[] freqs = {2437, 5180};
        String[] caps = {"[WPA2-EAP-CCMP][ESS]", "[WPA2-EAP-CCMP][ESS]"};
        int[] levels = {mThresholdMinimumRssi2G + RSSI_BUMP, mThresholdMinimumRssi5G + RSSI_BUMP};
        int[] securities = {SECURITY_EAP, SECURITY_EAP};

        // Make a network selection.
        ScanDetailsAndWifiConfigs scanDetailsAndConfigs =
                WifiNetworkSelectorTestUtil.setupScanDetailsAndConfigStore(ssids, bssids,
                    freqs, caps, levels, securities, mWifiConfigManager, mClock);
        List<ScanDetail> scanDetails = scanDetailsAndConfigs.getScanDetails();
        WifiConfiguration[] savedConfigs = scanDetailsAndConfigs.getWifiConfigs();
        HashSet<String> blacklist = new HashSet<String>();
        WifiConfiguration candidate = mWifiNetworkSelector.selectNetwork(scanDetails,
                blacklist, mWifiInfo, false, true, false);
        WifiConfigurationTestUtil.assertConfigurationEqual(savedConfigs[0], candidate);

        when(mClock.getElapsedSinceBootMillis()).thenReturn(SystemClock.elapsedRealtime()
                + WifiNetworkSelector.MINIMUM_NETWORK_SELECTION_INTERVAL_MS - 2000);

        // Do another network selection with CMI in DISCONNECTED state.
        candidate = mWifiNetworkSelector.selectNetwork(scanDetails,
                blacklist, mWifiInfo, false, true, false);

        ScanResult chosenScanResult = scanDetails.get(0).getScanResult();
        WifiConfigurationTestUtil.assertConfigurationEqual(savedConfigs[0], candidate);
        WifiNetworkSelectorTestUtil.verifySelectedScanResult(mWifiConfigManager,
                chosenScanResult, candidate);
    }

    /**
     * New network selection is performed if the currently connected network
     * has low RSSI value.
     *
     * ClientModeImpl is connected to a low RSSI 5GHz network.
     * scanDetails contains a valid networks.
     * Perform a network selection after the first one.
     *
     * Expected behavior: the first network is recommended by Network Selector
     */
    @Test
    public void lowRssi5GNetworkIsNotSufficient() {
        String[] ssids = {"\"test1\""};
        String[] bssids = {"6c:f3:7f:ae:8c:f3"};
        int[] freqs = {5180};
        String[] caps = {"[WPA2-PSK][ESS]"};
        int[] levels = {mThresholdQualifiedRssi5G - 2};
        int[] securities = {SECURITY_PSK};

        ScanDetailsAndWifiConfigs scanDetailsAndConfigs =
                WifiNetworkSelectorTestUtil.setupScanDetailsAndConfigStore(ssids, bssids,
                    freqs, caps, levels, securities, mWifiConfigManager, mClock);
        List<ScanDetail> scanDetails = scanDetailsAndConfigs.getScanDetails();
        HashSet<String> blacklist = new HashSet<String>();
        WifiConfiguration[] savedConfigs = scanDetailsAndConfigs.getWifiConfigs();

        // connect to test1
        mWifiNetworkSelector.selectNetwork(scanDetails, blacklist, mWifiInfo, false, true, false);
        when(mWifiInfo.getSupplicantState()).thenReturn(SupplicantState.COMPLETED);
        when(mWifiInfo.getNetworkId()).thenReturn(0);
        when(mWifiInfo.getBSSID()).thenReturn(bssids[0]);
        when(mWifiInfo.is24GHz()).thenReturn(false);
        when(mWifiInfo.is5GHz()).thenReturn(true);
        when(mWifiInfo.getFrequency()).thenReturn(5000);
        when(mWifiInfo.getRssi()).thenReturn(levels[0]);

        when(mClock.getElapsedSinceBootMillis()).thenReturn(SystemClock.elapsedRealtime()
                + WifiNetworkSelector.MINIMUM_NETWORK_SELECTION_INTERVAL_MS + 2000);

        // Do another network selection.
        WifiConfiguration candidate = mWifiNetworkSelector.selectNetwork(scanDetails,
                blacklist, mWifiInfo, true, false, false);

        ScanResult chosenScanResult = scanDetails.get(0).getScanResult();
        WifiNetworkSelectorTestUtil.verifySelectedScanResult(mWifiConfigManager,
                chosenScanResult, candidate);
    }

    /**
     * New network selection is performed if the currently connected network
     * has no internet access although it has active traffic and high RSSI
     *
     * ClientModeImpl is connected to a network with no internet connectivity.
     * scanDetails contains a valid networks.
     * Perform a network selection after the first one.
     *
     * Expected behavior: the first network is recommended by Network Selector
     */
    @Test
    public void noInternetAccessNetworkIsNotSufficient() {
        String[] ssids = {"\"test1\""};
        String[] bssids = {"6c:f3:7f:ae:8c:f3"};
        int[] freqs = {5180};
        String[] caps = {"[WPA2-PSK][ESS]"};
        int[] levels = {mThresholdQualifiedRssi5G + 5};
        int[] securities = {SECURITY_PSK};

        ScanDetailsAndWifiConfigs scanDetailsAndConfigs =
                WifiNetworkSelectorTestUtil.setupScanDetailsAndConfigStore(ssids, bssids,
                        freqs, caps, levels, securities, mWifiConfigManager, mClock);
        List<ScanDetail> scanDetails = scanDetailsAndConfigs.getScanDetails();
        HashSet<String> blacklist = new HashSet<String>();
        WifiConfiguration[] savedConfigs = scanDetailsAndConfigs.getWifiConfigs();

        // connect to test1
        mWifiNetworkSelector.selectNetwork(scanDetails, blacklist, mWifiInfo, false, true, false);
        when(mWifiInfo.getSupplicantState()).thenReturn(SupplicantState.COMPLETED);
        when(mWifiInfo.getNetworkId()).thenReturn(0);
        when(mWifiInfo.getBSSID()).thenReturn(bssids[0]);
        when(mWifiInfo.is24GHz()).thenReturn(false);
        when(mWifiInfo.is5GHz()).thenReturn(true);
        when(mWifiInfo.getFrequency()).thenReturn(5000);
        when(mWifiInfo.getRssi()).thenReturn(levels[0]);
        when(mWifiInfo.getTxSuccessRate()).thenReturn(mMinPacketRateActiveTraffic - 1.0);
        when(mWifiInfo.getRxSuccessRate()).thenReturn(mMinPacketRateActiveTraffic + 1.0);

        when(mClock.getElapsedSinceBootMillis()).thenReturn(SystemClock.elapsedRealtime()
                + WifiNetworkSelector.MINIMUM_NETWORK_SELECTION_INTERVAL_MS + 2000);

        // Increment the network's no internet access reports.
        savedConfigs[0].numNoInternetAccessReports = 5;

        // Do another network selection.
        WifiConfiguration candidate = mWifiNetworkSelector.selectNetwork(scanDetails,
                blacklist, mWifiInfo, true, false, false);

        ScanResult chosenScanResult = scanDetails.get(0).getScanResult();
        WifiNetworkSelectorTestUtil.verifySelectedScanResult(mWifiConfigManager,
                chosenScanResult, candidate);
    }
    /**
     * Ensure that network selector update's network selection status for all configured
     * networks before performing network selection.
     *
     * Expected behavior: the first network is recommended by Network Selector
     */
    @Test
    public void updateConfiguredNetworks() {
        String[] ssids = {"\"test1\"", "\"test2\""};
        String[] bssids = {"6c:f3:7f:ae:8c:f3", "6c:f3:7f:ae:8c:f4"};
        int[] freqs = {2437, 2457};
        String[] caps = {"[WPA2-EAP-CCMP][ESS]", "[WPA2-PSK][ESS]"};
        int[] levels = {mThresholdMinimumRssi2G + 20, mThresholdMinimumRssi2G + RSSI_BUMP};
        int[] securities = {SECURITY_EAP, SECURITY_PSK};

        ScanDetailsAndWifiConfigs scanDetailsAndConfigs =
                WifiNetworkSelectorTestUtil.setupScanDetailsAndConfigStore(ssids, bssids,
                        freqs, caps, levels, securities, mWifiConfigManager, mClock);
        List<ScanDetail> scanDetails = scanDetailsAndConfigs.getScanDetails();
        HashSet<String> blacklist = new HashSet<String>();
        WifiConfiguration[] savedConfigs = scanDetailsAndConfigs.getWifiConfigs();

        // Do network selection.
        mWifiNetworkSelector.selectNetwork(scanDetails,
                blacklist, mWifiInfo, true, false, false);

        verify(mWifiConfigManager).getConfiguredNetworks();
        verify(mWifiConfigManager, times(savedConfigs.length)).tryEnableNetwork(anyInt());
        verify(mWifiConfigManager, times(savedConfigs.length))
                .clearNetworkCandidateScanResult(anyInt());
    }

    /**
     * Blacklisted BSSID is filtered out for network selection.
     *
     * ClientModeImpl is disconnected.
     * scanDetails contains a network which is blacklisted.
     *
     * Expected behavior: no network recommended by Network Selector
     */
    @Test
    public void filterOutBlacklistedBssid() {
        String[] ssids = {"\"test1\""};
        String[] bssids = {"6c:f3:7f:ae:8c:f3"};
        int[] freqs = {5180};
        String[] caps = {"[WPA2-PSK][ESS]"};
        int[] levels = {mThresholdQualifiedRssi5G + 8};
        int[] securities = {SECURITY_PSK};

        ScanDetailsAndWifiConfigs scanDetailsAndConfigs =
                WifiNetworkSelectorTestUtil.setupScanDetailsAndConfigStore(ssids, bssids,
                    freqs, caps, levels, securities, mWifiConfigManager, mClock);
        List<ScanDetail> scanDetails = scanDetailsAndConfigs.getScanDetails();
        HashSet<String> blacklist = new HashSet<String>();
        blacklist.add(bssids[0]);

        WifiConfiguration candidate = mWifiNetworkSelector.selectNetwork(scanDetails,
                blacklist, mWifiInfo, false, true, false);
        assertEquals("Expect null configuration", null, candidate);
        assertTrue(mWifiNetworkSelector.getConnectableScanDetails().isEmpty());
    }

    /**
     * Wifi network selector doesn't recommend any network if the currently connected one
     * doesn't show up in the scan results.
     *
     * ClientModeImpl is under connected state and 2.4GHz test1 is connected.
     * The second scan results contains only test2 which now has a stronger RSSI than test1.
     * Test1 is not in the second scan results.
     *
     * Expected behavior: no network recommended by Network Selector
     */
    @Test
    public void noSelectionWhenCurrentNetworkNotInScanResults() {
        String[] ssids = {"\"test1\"", "\"test2\""};
        String[] bssids = {"6c:f3:7f:ae:8c:f3", "6c:f3:7f:ae:8c:f4"};
        int[] freqs = {2437, 2457};
        String[] caps = {"[WPA2-EAP-CCMP][ESS]", "[WPA2-PSK][ESS]"};
        int[] levels = {mThresholdMinimumRssi2G + 20, mThresholdMinimumRssi2G + RSSI_BUMP};
        int[] securities = {SECURITY_EAP, SECURITY_PSK};

        // Make a network selection to connect to test1.
        ScanDetailsAndWifiConfigs scanDetailsAndConfigs =
                WifiNetworkSelectorTestUtil.setupScanDetailsAndConfigStore(ssids, bssids,
                    freqs, caps, levels, securities, mWifiConfigManager, mClock);
        List<ScanDetail> scanDetails = scanDetailsAndConfigs.getScanDetails();
        HashSet<String> blacklist = new HashSet<String>();
        WifiConfiguration candidate = mWifiNetworkSelector.selectNetwork(scanDetails,
                blacklist, mWifiInfo, false, true, false);

        when(mWifiInfo.getSupplicantState()).thenReturn(SupplicantState.COMPLETED);
        when(mWifiInfo.getNetworkId()).thenReturn(0);
        when(mWifiInfo.getBSSID()).thenReturn(bssids[0]);
        when(mWifiInfo.is24GHz()).thenReturn(true);
        when(mWifiInfo.getScore()).thenReturn(ConnectedScore.WIFI_TRANSITION_SCORE);
        when(mWifiInfo.is5GHz()).thenReturn(false);
        when(mWifiInfo.getFrequency()).thenReturn(2400);
        when(mWifiInfo.getRssi()).thenReturn(levels[0]);
        when(mClock.getElapsedSinceBootMillis()).thenReturn(SystemClock.elapsedRealtime()
                + WifiNetworkSelector.MINIMUM_NETWORK_SELECTION_INTERVAL_MS + 2000);

        // Prepare the second scan results which have no test1.
        String[] ssidsNew = {"\"test2\""};
        String[] bssidsNew = {"6c:f3:7f:ae:8c:f4"};
        int[] freqsNew = {2457};
        String[] capsNew = {"[WPA2-EAP-CCMP][ESS]"};
        int[] levelsNew = {mThresholdMinimumRssi2G + 40};
        scanDetails = WifiNetworkSelectorTestUtil.buildScanDetails(ssidsNew, bssidsNew,
                freqsNew, capsNew, levelsNew, mClock);
        candidate = mWifiNetworkSelector.selectNetwork(scanDetails, blacklist, mWifiInfo,
                true, false, false);

        // The second network selection is skipped since current connected network is
        // missing from the scan results.
        assertEquals("Expect null configuration", null, candidate);
        assertTrue(mWifiNetworkSelector.getConnectableScanDetails().isEmpty());
    }

    /**
     * Ensures that setting the user connect choice updates the
     * NetworkSelectionStatus#mConnectChoice for all other WifiConfigurations in range in the last
     * round of network selection.
     *
     * Expected behavior: WifiConfiguration.NetworkSelectionStatus#mConnectChoice is set to
     *                    test1's configkey for test2. test3's WifiConfiguration is unchanged.
     */
    @Test
    public void setUserConnectChoice() {
        String[] ssids = {"\"test1\"", "\"test2\"", "\"test3\""};
        String[] bssids = {"6c:f3:7f:ae:8c:f3", "6c:f3:7f:ae:8c:f4", "6c:f3:7f:ae:8c:f5"};
        int[] freqs = {2437, 5180, 5181};
        String[] caps = {"[WPA2-PSK][ESS]", "[WPA2-EAP-CCMP][ESS]", "[WPA2-PSK][ESS]"};
        int[] levels = {mThresholdMinimumRssi2G + RSSI_BUMP, mThresholdMinimumRssi5G + RSSI_BUMP,
                mThresholdMinimumRssi5G + RSSI_BUMP};
        int[] securities = {SECURITY_PSK, SECURITY_EAP, SECURITY_PSK};

        ScanDetailsAndWifiConfigs scanDetailsAndConfigs =
                WifiNetworkSelectorTestUtil.setupScanDetailsAndConfigStore(ssids, bssids,
                        freqs, caps, levels, securities, mWifiConfigManager, mClock);

        WifiConfiguration selectedWifiConfig = scanDetailsAndConfigs.getWifiConfigs()[0];
        selectedWifiConfig.getNetworkSelectionStatus()
                .setCandidate(scanDetailsAndConfigs.getScanDetails().get(0).getScanResult());
        selectedWifiConfig.getNetworkSelectionStatus().setNetworkSelectionStatus(
                NetworkSelectionStatus.NETWORK_SELECTION_PERMANENTLY_DISABLED);
        selectedWifiConfig.getNetworkSelectionStatus().setConnectChoice("bogusKey");

        WifiConfiguration configInLastNetworkSelection = scanDetailsAndConfigs.getWifiConfigs()[1];
        configInLastNetworkSelection.getNetworkSelectionStatus()
                .setSeenInLastQualifiedNetworkSelection(true);

        WifiConfiguration configNotInLastNetworkSelection =
                scanDetailsAndConfigs.getWifiConfigs()[2];

        assertTrue(mWifiNetworkSelector.setUserConnectChoice(selectedWifiConfig.networkId));

        verify(mWifiConfigManager).updateNetworkSelectionStatus(selectedWifiConfig.networkId,
                NetworkSelectionStatus.NETWORK_SELECTION_ENABLE);
        verify(mWifiConfigManager).clearNetworkConnectChoice(selectedWifiConfig.networkId);
        verify(mWifiConfigManager).setNetworkConnectChoice(configInLastNetworkSelection.networkId,
                selectedWifiConfig.getKey(), mClock.getWallClockMillis());
        verify(mWifiConfigManager, never()).setNetworkConnectChoice(
                configNotInLastNetworkSelection.networkId, selectedWifiConfig.getKey(),
                mClock.getWallClockMillis());
    }

    /**
     * If two qualified networks, test1 and test2, are in range when the user selects test2 over
     * test1, WifiNetworkSelector will override the NetworkSelector's choice to connect to test1
     * with test2.
     *
     * Expected behavior: test2 is the recommended network
     */
    @Test
    public void userConnectChoiceOverridesNetworkEvaluators() {
        String[] ssids = {"\"test1\"", "\"test2\""};
        String[] bssids = {"6c:f3:7f:ae:8c:f3", "6c:f3:7f:ae:8c:f4"};
        int[] freqs = {2437, 5180};
        String[] caps = {"[WPA2-PSK][ESS]", "[WPA2-PSK][ESS]"};
        int[] levels = {mThresholdMinimumRssi2G + RSSI_BUMP, mThresholdMinimumRssi5G + RSSI_BUMP};
        int[] securities = {SECURITY_PSK, SECURITY_PSK};

        ScanDetailsAndWifiConfigs scanDetailsAndConfigs =
                WifiNetworkSelectorTestUtil.setupScanDetailsAndConfigStore(ssids, bssids,
                        freqs, caps, levels, securities, mWifiConfigManager, mClock);
        List<ScanDetail> scanDetails = scanDetailsAndConfigs.getScanDetails();
        HashSet<String> blacklist = new HashSet<String>();

        // DummyEvaluator always selects the first network in the list.
        WifiConfiguration networkSelectorChoice = scanDetailsAndConfigs.getWifiConfigs()[0];
        networkSelectorChoice.getNetworkSelectionStatus()
                .setSeenInLastQualifiedNetworkSelection(true);

        WifiConfiguration userChoice = scanDetailsAndConfigs.getWifiConfigs()[1];
        userChoice.getNetworkSelectionStatus()
                .setCandidate(scanDetailsAndConfigs.getScanDetails().get(1).getScanResult());

        // With no user choice set, networkSelectorChoice should be chosen.
        WifiConfiguration candidate = mWifiNetworkSelector.selectNetwork(scanDetails,
                blacklist, mWifiInfo, false, true, false);

        ArgumentCaptor<Integer> nominatorIdCaptor = ArgumentCaptor.forClass(int.class);
        verify(mWifiMetrics, atLeastOnce()).setNominatorForNetwork(eq(candidate.networkId),
                nominatorIdCaptor.capture());
        // unknown because DummyEvaluator does not have a nominator ID
        // getValue() returns the argument from the *last* call
        assertEquals(WifiMetricsProto.ConnectionEvent.NOMINATOR_UNKNOWN,
                nominatorIdCaptor.getValue().intValue());

        WifiConfigurationTestUtil.assertConfigurationEqual(networkSelectorChoice, candidate);

        when(mClock.getElapsedSinceBootMillis()).thenReturn(SystemClock.elapsedRealtime()
                + WifiNetworkSelector.MINIMUM_NETWORK_SELECTION_INTERVAL_MS + 2000);

        assertTrue(mWifiNetworkSelector.setUserConnectChoice(userChoice.networkId));

        // After user connect choice is set, userChoice should override networkSelectorChoice.
        candidate = mWifiNetworkSelector.selectNetwork(scanDetails,
                blacklist, mWifiInfo, false, true, false);

        verify(mWifiMetrics, atLeastOnce()).setNominatorForNetwork(eq(candidate.networkId),
                nominatorIdCaptor.capture());
        // getValue() returns the argument from the *last* call
        assertEquals(WifiMetricsProto.ConnectionEvent.NOMINATOR_SAVED_USER_CONNECT_CHOICE,
                nominatorIdCaptor.getValue().intValue());
        WifiConfigurationTestUtil.assertConfigurationEqual(userChoice, candidate);
    }

    /**
     * Tests when multiple evaluators nominate the same candidate, any one of the nominator IDs is
     * acceptable.
     */
    @Test
    public void testMultipleEvaluatorsSetsNominatorIdCorrectly() {
        // first dummy evaluator is registered in setup, returns index 0
        // register a second network evaluator that also returns index 0, but with a different ID
        mWifiNetworkSelector.registerNetworkNominator(new DummyNetworkNominator(0,
                WifiNetworkSelector.NetworkNominator.NOMINATOR_ID_SCORED));
        // register a third network evaluator that also returns index 0, but with a different ID
        mWifiNetworkSelector.registerNetworkNominator(new DummyNetworkNominator(0,
                WifiNetworkSelector.NetworkNominator.NOMINATOR_ID_SAVED));

        String[] ssids = {"\"test1\"", "\"test2\""};
        String[] bssids = {"6c:f3:7f:ae:8c:f3", "6c:f3:7f:ae:8c:f4"};
        int[] freqs = {2437, 5180};
        String[] caps = {"[WPA2-PSK][ESS]", "[WPA2-PSK][ESS]"};
        int[] levels = {mThresholdMinimumRssi2G + RSSI_BUMP, mThresholdMinimumRssi5G + RSSI_BUMP};
        int[] securities = {SECURITY_PSK, SECURITY_PSK};

        ScanDetailsAndWifiConfigs scanDetailsAndConfigs =
                WifiNetworkSelectorTestUtil.setupScanDetailsAndConfigStore(ssids, bssids,
                        freqs, caps, levels, securities, mWifiConfigManager, mClock);
        List<ScanDetail> scanDetails = scanDetailsAndConfigs.getScanDetails();
        HashSet<String> blacklist = new HashSet<>();

        // DummyEvaluator always selects the first network in the list.
        WifiConfiguration networkSelectorChoice = scanDetailsAndConfigs.getWifiConfigs()[0];
        networkSelectorChoice.getNetworkSelectionStatus()
                .setSeenInLastQualifiedNetworkSelection(true);

        WifiConfiguration userChoice = scanDetailsAndConfigs.getWifiConfigs()[1];
        userChoice.getNetworkSelectionStatus()
                .setCandidate(scanDetailsAndConfigs.getScanDetails().get(1).getScanResult());

        WifiConfiguration candidate = mWifiNetworkSelector.selectNetwork(scanDetails,
                blacklist, mWifiInfo, false, true, false);

        ArgumentCaptor<Integer> nominatorIdCaptor = ArgumentCaptor.forClass(int.class);
        verify(mWifiMetrics, atLeastOnce()).setNominatorForNetwork(eq(candidate.networkId),
                nominatorIdCaptor.capture());

        for (int nominatorId : nominatorIdCaptor.getAllValues()) {
            assertThat(nominatorId, is(oneOf(
                    WifiMetricsProto.ConnectionEvent.NOMINATOR_UNKNOWN,
                    WifiMetricsProto.ConnectionEvent.NOMINATOR_EXTERNAL_SCORED,
                    WifiMetricsProto.ConnectionEvent.NOMINATOR_SAVED)));
        }
        verify(mWifiMetrics, atLeastOnce()).setNetworkSelectorExperimentId(anyInt());
    }

    /**
     * Wifi network selector performs network selection when current network has high
     * quality but no active stream
     *
     * Expected behavior: network selection is performed
     */
    @Test
    public void testNoActiveStream() {
        // Rssi after connected.
        when(mWifiInfo.getRssi()).thenReturn(mThresholdQualifiedRssi2G + 1);
        when(mWifiInfo.getTxSuccessRate()).thenReturn(0.0);
        when(mWifiInfo.getRxSuccessRate()).thenReturn(0.0);

        testStayOrTryToSwitch(
                // Parameters for network1:
                mThresholdQualifiedRssi2G + 1 /* rssi before connected */,
                false /* not a 5G network */,
                false /* not open network */,
                false /* not a osu */,
                // Parameters for network2:
                mThresholdQualifiedRssi5G + 1 /* rssi */,
                true /* a 5G network */,
                false /* not open network */,
                // Should try to switch.
                true);
    }

    /**
     * Wifi network selector skips network selection when current network is osu and has low RSSI
     *
     * Expected behavior: network selection is skipped
     */
    @Test
    public void testOsuIsSufficient() {
        // Rssi after connected.
        when(mWifiInfo.getRssi()).thenReturn(mThresholdQualifiedRssi5G - 1);
        when(mWifiInfo.getTxSuccessRate()).thenReturn(0.0);
        when(mWifiInfo.getRxSuccessRate()).thenReturn(0.0);

        testStayOrTryToSwitch(
                // Parameters for network1:
                mThresholdQualifiedRssi5G - 1 /* rssi before connected */,
                false /* not a 5G network */,
                false /* not open network */,
                true /* osu */,
                // Parameters for network2:
                mThresholdQualifiedRssi5G + 1 /* rssi */,
                true /* a 5G network */,
                false /* not open network */,
                // Should not try to switch.
                false);
    }

    /**
     * Wifi network selector will not perform network selection when current network has high
     * quality and active stream
     *
     *
     * Expected behavior: network selection is not performed
     */
    @Test
    public void testSufficientLinkQualityActiveStream() {
        // Rssi after connected.
        when(mWifiInfo.getRssi()).thenReturn(mThresholdQualifiedRssi5G + 1);
        when(mWifiInfo.getTxSuccessRate()).thenReturn(mMinPacketRateActiveTraffic - 1.0);
        when(mWifiInfo.getRxSuccessRate()).thenReturn(mMinPacketRateActiveTraffic * 2.0);

        testStayOrTryToSwitch(
                mThresholdQualifiedRssi5G + 1 /* rssi before connected */,
                true /* a 5G network */,
                false /* not open network */,
                // Should not try to switch.
                false);
    }


    /**
     * New network selection is performed if the currently connected network has bad rssi.
     *
     * Expected behavior: Network Selector perform network selection after connected
     * to the first one.
     */
    @Test
    public void testBadRssi() {
        // Rssi after connected.
        when(mWifiInfo.getRssi()).thenReturn(mThresholdQualifiedRssi2G - 1);
        when(mWifiInfo.getTxSuccessRate()).thenReturn(mMinPacketRateActiveTraffic + 1.0);
        when(mWifiInfo.getRxSuccessRate()).thenReturn(mMinPacketRateActiveTraffic - 1.0);

        testStayOrTryToSwitch(
                mThresholdQualifiedRssi2G + 1 /* rssi before connected */,
                false /* not a 5G network */,
                false /* not open network */,
                // Should try to switch.
                true);
    }

    /**
     * This is a meta-test that given two scan results of various types, will
     * determine whether or not network selection should be performed.
     *
     * It sets up two networks, connects to the first, and then ensures that
     * both are available in the scan results for the NetworkSelector.
     */
    private void testStayOrTryToSwitch(
            int rssiNetwork1, boolean is5GHzNetwork1, boolean isOpenNetwork1,
            boolean isFirstNetworkOsu,
            int rssiNetwork2, boolean is5GHzNetwork2, boolean isOpenNetwork2,
            boolean shouldSelect) {
        String[] ssids = {"\"test1\"", "\"test2\""};
        String[] bssids = {"6c:f3:7f:ae:8c:f3", "6c:f3:7f:ae:8c:f4"};
        int[] freqs = {is5GHzNetwork1 ? 5180 : 2437, is5GHzNetwork2 ? 5180 : 2437};
        String[] caps = {isOpenNetwork1 ? "[ESS]" : "[WPA2-PSK][ESS]",
                         isOpenNetwork2 ? "[ESS]" : "[WPA2-PSK][ESS]"};
        int[] levels = {rssiNetwork1, rssiNetwork2};
        int[] securities = {isOpenNetwork1 ? SECURITY_NONE : SECURITY_PSK,
                            isOpenNetwork2 ? SECURITY_NONE : SECURITY_PSK};
        testStayOrTryToSwitchImpl(ssids, bssids, freqs, caps, levels, securities, isFirstNetworkOsu,
                shouldSelect);
    }

    /**
     * This is a meta-test that given one scan results, will
     * determine whether or not network selection should be performed.
     *
     * It sets up one network, connects to it, and then ensures that it is in
     * the scan results for the NetworkSelector.
     */
    private void testStayOrTryToSwitch(
            int rssi, boolean is5GHz, boolean isOpenNetwork,
            boolean shouldSelect) {
        String[] ssids = {"\"test1\""};
        String[] bssids = {"6c:f3:7f:ae:8c:f3"};
        int[] freqs = {is5GHz ? 5180 : 2437};
        String[] caps = {isOpenNetwork ? "[ESS]" : "[WPA2-PSK][ESS]"};
        int[] levels = {rssi};
        int[] securities = {isOpenNetwork ? SECURITY_NONE : SECURITY_PSK};
        testStayOrTryToSwitchImpl(ssids, bssids, freqs, caps, levels, securities, false,
                shouldSelect);
    }

    private void testStayOrTryToSwitchImpl(String[] ssids, String[] bssids, int[] freqs,
            String[] caps, int[] levels, int[] securities, boolean isFirstNetworkOsu,
            boolean shouldSelect) {
        // Make a network selection to connect to test1.
        ScanDetailsAndWifiConfigs scanDetailsAndConfigs =
                WifiNetworkSelectorTestUtil.setupScanDetailsAndConfigStore(ssids, bssids,
                        freqs, caps, levels, securities, mWifiConfigManager, mClock);
        List<ScanDetail> scanDetails = scanDetailsAndConfigs.getScanDetails();
        HashSet<String> blacklist = new HashSet<String>();
        // DummyNetworkEvaluator always return the first network in the scan results
        // for connection, so this should connect to the first network.
        WifiConfiguration candidate = mWifiNetworkSelector.selectNetwork(scanDetails,
                blacklist, mWifiInfo, false, true, true);
        assertNotNull("Result should be not null", candidate);
        WifiNetworkSelectorTestUtil.verifySelectedScanResult(mWifiConfigManager,
                scanDetails.get(0).getScanResult(), candidate);

        when(mWifiInfo.getSupplicantState()).thenReturn(SupplicantState.COMPLETED);
        when(mWifiInfo.getNetworkId()).thenReturn(0);
        when(mWifiInfo.getBSSID()).thenReturn(bssids[0]);
        when(mWifiInfo.is24GHz()).thenReturn(!ScanResult.is5GHz(freqs[0]));
        when(mWifiInfo.is5GHz()).thenReturn(ScanResult.is5GHz(freqs[0]));
        when(mWifiInfo.getFrequency()).thenReturn(freqs[0]);
        if (isFirstNetworkOsu) {
            WifiConfiguration[] configs = scanDetailsAndConfigs.getWifiConfigs();
            // Force 1st network to OSU
            configs[0].osu = true;
            when(mWifiConfigManager.getConfiguredNetwork(mWifiInfo.getNetworkId()))
                    .thenReturn(configs[0]);
        }

        when(mClock.getElapsedSinceBootMillis()).thenReturn(SystemClock.elapsedRealtime()
                + WifiNetworkSelector.MINIMUM_NETWORK_SELECTION_INTERVAL_MS + 2000);

        candidate = mWifiNetworkSelector.selectNetwork(scanDetails, blacklist, mWifiInfo,
                true, false, false);

        // DummyNetworkEvaluator always return the first network in the scan results
        // for connection, so if network selection is performed, the first network should
        // be returned as candidate.
        if (shouldSelect) {
            assertNotNull("Result should be not null", candidate);
            WifiNetworkSelectorTestUtil.verifySelectedScanResult(mWifiConfigManager,
                    scanDetails.get(0).getScanResult(), candidate);
        } else {
            assertEquals("Expect null configuration", null, candidate);
        }
    }

    /**
     * {@link WifiNetworkSelector#getFilteredScanDetailsForOpenUnsavedNetworks()} should filter out
     * networks that are not open after network selection is made.
     *
     * Expected behavior: return open networks only
     */
    @Test
    public void getfilterOpenUnsavedNetworks_filtersForOpenNetworks() {
        String[] ssids = {"\"test1\"", "\"test2\""};
        String[] bssids = {"6c:f3:7f:ae:8c:f3", "6c:f3:7f:ae:8c:f4"};
        int[] freqs = {2437, 5180};
        String[] caps = {"[WPA2-EAP-CCMP][ESS]", "[ESS]"};
        int[] levels = {mThresholdMinimumRssi2G + RSSI_BUMP, mThresholdMinimumRssi5G + RSSI_BUMP};
        mDummyEvaluator.setEvaluatorToSelectCandidate(false);

        List<ScanDetail> scanDetails = WifiNetworkSelectorTestUtil.buildScanDetails(
                ssids, bssids, freqs, caps, levels, mClock);
        HashSet<String> blacklist = new HashSet<>();

        mWifiNetworkSelector.selectNetwork(scanDetails, blacklist, mWifiInfo, false, true, false);
        List<ScanDetail> expectedOpenUnsavedNetworks = new ArrayList<>();
        expectedOpenUnsavedNetworks.add(scanDetails.get(1));
        assertEquals("Expect open unsaved networks",
                expectedOpenUnsavedNetworks,
                mWifiNetworkSelector.getFilteredScanDetailsForOpenUnsavedNetworks());
    }

    /**
     * {@link WifiNetworkSelector#getFilteredScanDetailsForOpenUnsavedNetworks()} should filter out
     * saved networks after network selection is made. This should return an empty list when there
     * are no unsaved networks available.
     *
     * Expected behavior: return unsaved networks only. Return empty list if there are no unsaved
     * networks.
     */
    @Test
    public void getfilterOpenUnsavedNetworks_filtersOutSavedNetworks() {
        String[] ssids = {"\"test1\""};
        String[] bssids = {"6c:f3:7f:ae:8c:f3"};
        int[] freqs = {2437, 5180};
        String[] caps = {"[ESS]"};
        int[] levels = {mThresholdMinimumRssi2G + RSSI_BUMP};
        int[] securities = {SECURITY_NONE};
        mDummyEvaluator.setEvaluatorToSelectCandidate(false);

        List<ScanDetail> unSavedScanDetails = WifiNetworkSelectorTestUtil.buildScanDetails(
                ssids, bssids, freqs, caps, levels, mClock);
        HashSet<String> blacklist = new HashSet<>();

        mWifiNetworkSelector.selectNetwork(
                unSavedScanDetails, blacklist, mWifiInfo, false, true, false);
        assertEquals("Expect open unsaved networks",
                unSavedScanDetails,
                mWifiNetworkSelector.getFilteredScanDetailsForOpenUnsavedNetworks());

        ScanDetailsAndWifiConfigs scanDetailsAndConfigs =
                WifiNetworkSelectorTestUtil.setupScanDetailsAndConfigStore(ssids, bssids,
                        freqs, caps, levels, securities, mWifiConfigManager, mClock);
        List<ScanDetail> savedScanDetails = scanDetailsAndConfigs.getScanDetails();

        mWifiNetworkSelector.selectNetwork(
                savedScanDetails, blacklist, mWifiInfo, false, true, false);
        // Saved networks are filtered out.
        assertTrue(mWifiNetworkSelector.getFilteredScanDetailsForOpenUnsavedNetworks().isEmpty());
    }

    /**
     * {@link WifiNetworkSelector#getFilteredScanDetailsForOpenUnsavedNetworks()} should filter out
     * bssid blacklisted networks.
     *
     * Expected behavior: do not return blacklisted network
     */
    @Test
    public void getfilterOpenUnsavedNetworks_filtersOutBlacklistedNetworks() {
        String[] ssids = {"\"test1\"", "\"test2\""};
        String[] bssids = {"6c:f3:7f:ae:8c:f3", "6c:f3:7f:ae:8c:f4"};
        int[] freqs = {2437, 5180};
        String[] caps = {"[ESS]", "[ESS]"};
        int[] levels = {mThresholdMinimumRssi2G + RSSI_BUMP, mThresholdMinimumRssi5G + RSSI_BUMP};
        mDummyEvaluator.setEvaluatorToSelectCandidate(false);

        List<ScanDetail> scanDetails = WifiNetworkSelectorTestUtil.buildScanDetails(
                ssids, bssids, freqs, caps, levels, mClock);
        HashSet<String> blacklist = new HashSet<>();
        blacklist.add(bssids[0]);

        mWifiNetworkSelector.selectNetwork(scanDetails, blacklist, mWifiInfo, false, true, false);
        List<ScanDetail> expectedOpenUnsavedNetworks = new ArrayList<>();
        expectedOpenUnsavedNetworks.add(scanDetails.get(1));
        assertEquals("Expect open unsaved networks",
                expectedOpenUnsavedNetworks,
                mWifiNetworkSelector.getFilteredScanDetailsForOpenUnsavedNetworks());
    }

    /**
     * {@link WifiNetworkSelector#getFilteredScanDetailsForOpenUnsavedNetworks()} should return
     * empty list when there are no open networks after network selection is made.
     *
     * Expected behavior: return empty list
     */
    @Test
    public void getfilterOpenUnsavedNetworks_returnsEmptyListWhenNoOpenNetworksPresent() {
        String[] ssids = {"\"test1\"", "\"test2\""};
        String[] bssids = {"6c:f3:7f:ae:8c:f3", "6c:f3:7f:ae:8c:f4"};
        int[] freqs = {2437, 5180};
        String[] caps = {"[WPA2-EAP-CCMP][ESS]", "[WPA2-EAP-CCMP][ESS]"};
        int[] levels = {mThresholdMinimumRssi2G + RSSI_BUMP, mThresholdMinimumRssi5G + RSSI_BUMP};
        mDummyEvaluator.setEvaluatorToSelectCandidate(false);

        List<ScanDetail> scanDetails = WifiNetworkSelectorTestUtil.buildScanDetails(
                ssids, bssids, freqs, caps, levels, mClock);
        HashSet<String> blacklist = new HashSet<>();

        mWifiNetworkSelector.selectNetwork(scanDetails, blacklist, mWifiInfo, false, true, false);
        assertTrue(mWifiNetworkSelector.getFilteredScanDetailsForOpenUnsavedNetworks().isEmpty());
    }

    /**
     * {@link WifiNetworkSelector#getFilteredScanDetailsForOpenUnsavedNetworks()} should return
     * empty list when no network selection has been made.
     *
     * Expected behavior: return empty list
     */
    @Test
    public void getfilterOpenUnsavedNetworks_returnsEmptyListWhenNoNetworkSelectionMade() {
        assertTrue(mWifiNetworkSelector.getFilteredScanDetailsForOpenUnsavedNetworks().isEmpty());
    }

    /**
     * {@link WifiNetworkSelector#getFilteredScanDetailsForOpenUnsavedNetworks()} for device that
     * supports enhanced open networks, should filter out networks that are not open and not
     * enhanced open after network selection is made.
     *
     * Expected behavior: return open and enhanced open networks only
     */
    @Test
    public void getfilterOpenUnsavedNetworks_filtersForOpenAndOweNetworksOweSupported() {
        String[] ssids = {"\"test1\"", "\"test2\"", "\"test3\""};
        String[] bssids = {"6c:f3:7f:ae:8c:f3", "6c:f3:7f:ae:8c:f4", "6c:f3:7f:ae:8c:f5"};
        int[] freqs = {2437, 5180, 2414};
        String[] caps = {"[WPA2-EAP-CCMP][ESS]", "[ESS]", "[RSN-OWE-CCMP][ESS]"};
        int[] levels = {mThresholdMinimumRssi2G, mThresholdMinimumRssi5G + RSSI_BUMP,
                mThresholdMinimumRssi2G + RSSI_BUMP};
        mDummyEvaluator.setEvaluatorToSelectCandidate(false);
        when(mWifiNative.getSupportedFeatureSet(anyString()))
                .thenReturn(new Long(WIFI_FEATURE_OWE));

        List<ScanDetail> scanDetails = WifiNetworkSelectorTestUtil.buildScanDetails(
                ssids, bssids, freqs, caps, levels, mClock);
        HashSet<String> blacklist = new HashSet<>();

        mWifiNetworkSelector.selectNetwork(scanDetails, blacklist, mWifiInfo, false, true, false);
        List<ScanDetail> expectedOpenUnsavedNetworks = new ArrayList<>();
        expectedOpenUnsavedNetworks.add(scanDetails.get(1));
        expectedOpenUnsavedNetworks.add(scanDetails.get(2));
        assertEquals("Expect open unsaved networks",
                expectedOpenUnsavedNetworks,
                mWifiNetworkSelector.getFilteredScanDetailsForOpenUnsavedNetworks());
    }

    /**
     * {@link WifiNetworkSelector#getFilteredScanDetailsForOpenUnsavedNetworks()} for device that
     * does not support enhanced open networks, should filter out both networks that are not open
     * and enhanced open after network selection is made.
     *
     * Expected behavior: return open networks only
     */
    @Test
    public void getfilterOpenUnsavedNetworks_filtersForOpenAndOweNetworksOweNotSupported() {
        String[] ssids = {"\"test1\"", "\"test2\"", "\"test3\""};
        String[] bssids = {"6c:f3:7f:ae:8c:f3", "6c:f3:7f:ae:8c:f4", "6c:f3:7f:ae:8c:f5"};
        int[] freqs = {2437, 5180, 2414};
        String[] caps = {"[WPA2-EAP-CCMP][ESS]", "[ESS]", "[RSN-OWE-CCMP][ESS]"};
        int[] levels = {mThresholdMinimumRssi2G, mThresholdMinimumRssi5G + RSSI_BUMP,
                mThresholdMinimumRssi2G + RSSI_BUMP};
        mDummyEvaluator.setEvaluatorToSelectCandidate(false);
        when(mWifiNative.getSupportedFeatureSet(anyString()))
                .thenReturn(new Long(~WIFI_FEATURE_OWE));

        List<ScanDetail> scanDetails = WifiNetworkSelectorTestUtil.buildScanDetails(
                ssids, bssids, freqs, caps, levels, mClock);
        HashSet<String> blacklist = new HashSet<>();

        mWifiNetworkSelector.selectNetwork(scanDetails, blacklist, mWifiInfo, false, true, false);
        List<ScanDetail> expectedOpenUnsavedNetworks = new ArrayList<>();
        expectedOpenUnsavedNetworks.add(scanDetails.get(1));
        assertEquals("Expect open unsaved networks",
                expectedOpenUnsavedNetworks,
                mWifiNetworkSelector.getFilteredScanDetailsForOpenUnsavedNetworks());
    }

    /**
     * Test that registering a new CandidateScorer causes it to be used
     */
    @Test
    public void testCandidateScorerUse() throws Exception {
        String myid = "Mock CandidateScorer";
        when(mCandidateScorer.getIdentifier()).thenReturn(myid);
        setupWifiConfigManager(13);

        int experimentId = experimentIdFromIdentifier(myid);
        assertTrue("" + myid, 42000000 <=  experimentId && experimentId <= 42999999);
        String diagnose = "" + mScoringParams + " // " + experimentId;
        assertTrue(diagnose, mScoringParams.update("expid=" + experimentId));
        assertEquals(experimentId, mScoringParams.getExperimentIdentifier());

        mWifiNetworkSelector.registerCandidateScorer(mCandidateScorer);

        WifiConfiguration selected = mWifiNetworkSelector.selectNetwork(
                setUpTwoNetworks(-35, -40),
                EMPTY_BLACKLIST, mWifiInfo, false, true, true);

        verify(mCandidateScorer).scoreCandidates(any());
    }

    /**
     * Tests that no metrics are recorded if there is only a single legacy scorer.
     */
    @Test
    public void testCandidateScorerMetrics_onlyOneScorer() {
        testNoActiveStream();

        verify(mWifiMetrics, never()).logNetworkSelectionDecision(
                anyInt(), anyInt(), anyBoolean(), anyInt());
    }

    private static final WifiCandidates.CandidateScorer NULL_SCORER =
            new WifiCandidates.CandidateScorer() {
                @Override
                public String getIdentifier() {
                    return "NULL_SCORER";
                }

                @Override
                public WifiCandidates.ScoredCandidate scoreCandidates(
                        Collection<WifiCandidates.Candidate> group) {
                    return new WifiCandidates.ScoredCandidate(0, 0, false, null);
                }
            };

    private List<ScanDetail> setUpTwoNetworks(int rssiNetwork1, int rssiNetwork2) {
        String[] ssids = {"\"test1\"", "\"test2\""};
        String[] bssids = {"6c:f3:7f:ae:8c:f3", "6c:f3:7f:ae:8c:f4"};
        int[] freqs = {5180, 2437};
        String[] caps = {"[ESS]", "[ESS]"};
        int[] levels = {rssiNetwork1, rssiNetwork2};
        int[] securities = {SECURITY_NONE, SECURITY_NONE};
        ScanDetailsAndWifiConfigs scanDetailsAndConfigs =
                WifiNetworkSelectorTestUtil.setupScanDetailsAndConfigStore(ssids, bssids,
                        freqs, caps, levels, securities, mWifiConfigManager, mClock);
        return scanDetailsAndConfigs.getScanDetails();
    }

    /**
     * Tests that metrics are recorded for 3 scorers.
     */
    @Test
    public void testCandidateScorerMetrics_threeScorers() {
        mWifiNetworkSelector.registerCandidateScorer(mCompatibilityScorer);
        mWifiNetworkSelector.registerCandidateScorer(NULL_SCORER);

        // add a second NetworkEvaluator that returns the second network in the scan list
        mWifiNetworkSelector.registerNetworkNominator(
                new DummyNetworkNominator(1, DUMMY_EVALUATOR_ID_2));

        int compatibilityExpId = experimentIdFromIdentifier(mCompatibilityScorer.getIdentifier());
        mScoringParams.update("expid=" + compatibilityExpId);
        assertEquals(compatibilityExpId, mScoringParams.getExperimentIdentifier());

        testNoActiveStream();

        int nullScorerId = experimentIdFromIdentifier(NULL_SCORER.getIdentifier());

        // Wanted 2 times since testNoActiveStream() calls
        // WifiNetworkSelector.selectNetwork() twice
        verify(mWifiMetrics, times(2)).logNetworkSelectionDecision(nullScorerId,
                compatibilityExpId, false, 2);

        int expid = CompatibilityScorer.COMPATIBILITY_SCORER_DEFAULT_EXPID;
        verify(mWifiMetrics, atLeastOnce()).setNetworkSelectorExperimentId(eq(expid));
    }

    /**
     * Tests that metrics are recorded for two scorers.
     */
    @Test
    public void testCandidateScorerMetricsThroughputScorer() {
        if (WifiNetworkSelector.PRESET_CANDIDATE_SCORER_NAME.equals(
                mThroughputScorer.getIdentifier())) {
            mWifiNetworkSelector.registerCandidateScorer(mCompatibilityScorer);
            return; //TODO(b/142081306) temporarily disabled
        } else {
            mWifiNetworkSelector.registerCandidateScorer(mThroughputScorer);
        }

        // add a second NetworkEvaluator that returns the second network in the scan list
        mWifiNetworkSelector.registerNetworkNominator(
                new DummyNetworkNominator(1, DUMMY_EVALUATOR_ID_2));

        testNoActiveStream();

        int throughputExpId = experimentIdFromIdentifier(mThroughputScorer.getIdentifier());
        int compatibilityExpId = experimentIdFromIdentifier(mCompatibilityScorer.getIdentifier());

        // Wanted 2 times since testNoActiveStream() calls
        // WifiNetworkSelector.selectNetwork() twice
        if (WifiNetworkSelector.PRESET_CANDIDATE_SCORER_NAME.equals(
                mThroughputScorer.getIdentifier())) {
            verify(mWifiMetrics, times(2)).logNetworkSelectionDecision(
                    compatibilityExpId, throughputExpId, true, 2);
        } else {
            verify(mWifiMetrics, times(2)).logNetworkSelectionDecision(throughputExpId,
                    compatibilityExpId, true, 2);
        }
    }

    /**
     * Tests that passpoint network candidate will update SSID with the latest scanDetail.
     */
    @Test
    public void testPasspointCandidateUpdateWithLatestScanDetail() {
        String[] ssids = {"\"test1\"", "\"test2\""};
        String[] bssids = {"6c:f3:7f:ae:8c:f3", "6c:f3:7f:ae:8c:f4"};
        int[] freqs = {2437, 5180};
        String[] caps = {"[WPA2-EAP-CCMP][ESS]", "[WPA2-EAP-CCMP][ESS]"};
        int[] levels = {mThresholdMinimumRssi2G + 1, mThresholdMinimumRssi5G + 1};
        int[] securities = {SECURITY_EAP, SECURITY_EAP};
        HashSet<String> blackList = new HashSet<>();
        ScanDetailsAndWifiConfigs scanDetailsAndConfigs =
                WifiNetworkSelectorTestUtil.setupScanDetailsAndConfigStore(ssids, bssids,
                        freqs, caps, levels, securities, mWifiConfigManager, mClock);
        List<ScanDetail> scanDetails = scanDetailsAndConfigs.getScanDetails();
        WifiConfiguration[] configs = scanDetailsAndConfigs.getWifiConfigs();
        WifiConfiguration existingConfig = WifiConfigurationTestUtil.createPasspointNetwork();
        existingConfig.SSID = ssids[1];
        // Matched wifiConfig is an passpoint network with SSID from last scan.
        when(mWifiConfigManager.getConfiguredNetwork(configs[0].networkId))
                .thenReturn(existingConfig);
        mWifiNetworkSelector.registerNetworkNominator(
                new DummyNetworkNominator(0, DUMMY_EVALUATOR_ID_2));
        WifiConfiguration candidate = mWifiNetworkSelector
                .selectNetwork(scanDetails, blackList, mWifiInfo, false, true, true);
        // Check if the wifiConfig is updated with the latest
        verify(mWifiConfigManager).addOrUpdateNetwork(existingConfig,
                existingConfig.creatorUid, existingConfig.creatorName);
        assertEquals(ssids[0], candidate.SSID);
    }

    /**
     * Test that network which are not accepting new connections(MBO
     * association disallowed attribute in beacons/probe responses)
     * are filtered out from network selection.
     *
     * NetworkDetail contain the parsed association disallowed
     * reason code.
     *
     * Expected behavior: no network recommended by Network Selector
     */
    @Test
    public void filterMboApAdvertisingAssociationDisallowedAttr() {
        String[] ssids = {"\"test1\""};
        String[] bssids = {"6c:f3:7f:ae:8c:f3"};
        int[] freqs = {5180};
        String[] caps = {"[WPA2-PSK][ESS]"};
        int[] levels = {mThresholdQualifiedRssi5G + 8};
        int[] securities = {SECURITY_PSK};
        // MBO-OCE IE with association disallowed attribute.
        byte[][] iesByteStream = {{(byte) 0xdd, (byte) 0x0a,
                        (byte) 0x50, (byte) 0x6F, (byte) 0x9A, (byte) 0x16,
                        (byte) 0x01, (byte) 0x01, (byte) 0x40,
                        (byte) 0x04, (byte) 0x01, (byte) 0x03}};
        HashSet<String> blacklist = new HashSet<String>();

        ScanDetailsAndWifiConfigs scanDetailsAndConfigs =
                WifiNetworkSelectorTestUtil.setupScanDetailsAndConfigStore(ssids, bssids,
                    freqs, caps, levels, securities, mWifiConfigManager, mClock, iesByteStream);
        List<ScanDetail> scanDetails = scanDetailsAndConfigs.getScanDetails();

        WifiConfiguration candidate = mWifiNetworkSelector.selectNetwork(scanDetails,
                blacklist, mWifiInfo, false, true, false);
        assertEquals("Expect null configuration", null, candidate);
        assertTrue(mWifiNetworkSelector.getConnectableScanDetails().isEmpty());
    }
}
