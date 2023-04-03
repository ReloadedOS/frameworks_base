/*
 * Copyright (C) 2022 The Android Open Source Project
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

/*
 * Changes from Qualcomm Innovation Center are provided under the following license:
 * Copyright (c) 2023 Qualcomm Innovation Center, Inc. All rights reserved.
 * SPDX-License-Identifier: BSD-3-Clause-Clear
 */


package com.android.systemui.statusbar.pipeline.mobile.data.repository.prod

import android.content.Context
import android.content.IntentFilter
import android.database.ContentObserver
import android.provider.Settings.Global
import android.telephony.CellSignalStrength.SIGNAL_STRENGTH_GREAT
import android.telephony.CellSignalStrength.SIGNAL_STRENGTH_GOOD
import android.telephony.CellSignalStrength.SIGNAL_STRENGTH_MODERATE
import android.telephony.CellSignalStrength.SIGNAL_STRENGTH_POOR
import android.telephony.CellSignalStrength.SIGNAL_STRENGTH_NONE_OR_UNKNOWN
import android.telephony.CellSignalStrengthCdma
import android.telephony.CellSignalStrengthLte
import android.telephony.ims.ImsException
import android.telephony.ims.ImsMmTelManager
import android.telephony.ims.ImsReasonInfo
import android.telephony.ims.ImsRegistrationAttributes
import android.telephony.ims.ImsStateCallback
import android.telephony.ims.feature.MmTelFeature.MmTelCapabilities
import android.telephony.ims.RegistrationManager
import android.telephony.ims.stub.ImsRegistrationImplBase.REGISTRATION_TECH_NONE
import android.telephony.ServiceState
import android.telephony.SignalStrength
import android.telephony.SubscriptionInfo
import android.telephony.SubscriptionManager
import android.telephony.SubscriptionManager.INVALID_SUBSCRIPTION_ID
import android.telephony.TelephonyCallback
import android.telephony.TelephonyDisplayInfo
import android.telephony.TelephonyDisplayInfo.OVERRIDE_NETWORK_TYPE_NONE
import android.telephony.TelephonyManager
import android.telephony.TelephonyManager.ERI_FLASH
import android.telephony.TelephonyManager.ERI_ON
import android.telephony.TelephonyManager.EXTRA_SUBSCRIPTION_ID
import android.telephony.TelephonyManager.NETWORK_TYPE_UNKNOWN
import android.util.Log
import com.android.settingslib.Utils
import com.android.systemui.broadcast.BroadcastDispatcher
import com.android.systemui.common.coroutine.ConflatedCallbackFlow.conflatedCallbackFlow
import com.android.systemui.dagger.qualifiers.Application
import com.android.systemui.dagger.qualifiers.Background
import com.android.systemui.log.table.TableLogBuffer
import com.android.systemui.log.table.logDiffsForTable
import com.android.systemui.statusbar.pipeline.mobile.data.MobileInputLogger
import com.android.systemui.statusbar.pipeline.mobile.data.model.DataConnectionState.Disconnected
import com.android.systemui.statusbar.pipeline.mobile.data.model.NetworkNameModel
import com.android.systemui.statusbar.pipeline.mobile.data.model.ResolvedNetworkType.DefaultNetworkType
import com.android.systemui.statusbar.pipeline.mobile.data.model.ResolvedNetworkType.OverrideNetworkType
import com.android.systemui.statusbar.pipeline.mobile.data.model.ResolvedNetworkType.UnknownNetworkType
import com.android.systemui.statusbar.pipeline.mobile.data.model.SystemUiCarrierConfig
import com.android.systemui.statusbar.pipeline.mobile.data.model.toDataConnectionType
import com.android.systemui.statusbar.pipeline.mobile.data.model.toNetworkNameModel
import com.android.systemui.statusbar.pipeline.mobile.data.repository.CarrierConfigRepository
import com.android.systemui.statusbar.pipeline.mobile.data.repository.MobileConnectionRepository
import com.android.systemui.statusbar.pipeline.mobile.data.repository.MobileConnectionRepository.Companion.DEFAULT_NUM_LEVELS
import com.android.systemui.statusbar.pipeline.mobile.util.MobileMappingsProxy
import com.android.systemui.statusbar.pipeline.shared.data.model.DataActivityModel
import com.android.systemui.statusbar.pipeline.shared.data.model.toMobileDataActivityModel
import com.android.systemui.statusbar.policy.FiveGServiceClient
import com.android.systemui.statusbar.policy.FiveGServiceClient.FiveGServiceState
import com.android.systemui.statusbar.policy.FiveGServiceClient.IFiveGStateListener
import com.qti.extphone.NrIconType
import javax.inject.Inject
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.asExecutor
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.scan
import kotlinx.coroutines.flow.stateIn

/**
 * A repository implementation for a typical mobile connection (as opposed to a carrier merged
 * connection -- see [CarrierMergedConnectionRepository]).
 */
@Suppress("EXPERIMENTAL_IS_NOT_ENABLED")
@OptIn(ExperimentalCoroutinesApi::class)
class MobileConnectionRepositoryImpl(
    private val context: Context,
    override val subId: Int,
    defaultNetworkName: NetworkNameModel,
    networkNameSeparator: String,
    private val telephonyManager: TelephonyManager,
    systemUiCarrierConfig: SystemUiCarrierConfig,
    broadcastDispatcher: BroadcastDispatcher,
    private val mobileMappingsProxy: MobileMappingsProxy,
    bgDispatcher: CoroutineDispatcher,
    logger: MobileInputLogger,
    override val tableLogBuffer: TableLogBuffer,
    scope: CoroutineScope,
    private val fiveGServiceClient: FiveGServiceClient,
) : MobileConnectionRepository {
    init {
        if (telephonyManager.subscriptionId != subId) {
            throw IllegalStateException(
                "MobileRepo: TelephonyManager should be created with subId($subId). " +
                    "Found ${telephonyManager.subscriptionId} instead."
            )
        }
    }
    private val tag: String = MobileConnectionRepositoryImpl::class.java.simpleName
    private val imsMmTelManager: ImsMmTelManager = ImsMmTelManager.createForSubscriptionId(subId)
    /**
     * This flow defines the single shared connection to system_server via TelephonyCallback. Any
     * new callback should be added to this listener and funneled through callbackEvents via a data
     * class. See [CallbackEvent] for defining new callbacks.
     *
     * The reason we need to do this is because TelephonyManager limits the number of registered
     * listeners per-process, so we don't want to create a new listener for every callback.
     *
     * A note on the design for back pressure here: We use the [coalesce] operator here to change
     * the backpressure strategy to store exactly the last callback event of _each type_ here, as
     * opposed to the default strategy which is to drop the oldest event (regardless of type). This
     * means that we should never miss any single event as long as the flow has been started.
     */
    private val callbackEvents: StateFlow<TelephonyCallbackState> = run {
        val initial = TelephonyCallbackState()
        callbackFlow {
                val callback =
                    object :
                        TelephonyCallback(),
                        TelephonyCallback.ServiceStateListener,
                        TelephonyCallback.SignalStrengthsListener,
                        TelephonyCallback.DataConnectionStateListener,
                        TelephonyCallback.DataActivityListener,
                        TelephonyCallback.CarrierNetworkListener,
                        TelephonyCallback.DisplayInfoListener,
                        TelephonyCallback.DataEnabledListener,
                        FiveGServiceClient.IFiveGStateListener {
                        override fun onServiceStateChanged(serviceState: ServiceState) {
                            logger.logOnServiceStateChanged(serviceState, subId)
                            trySend(CallbackEvent.OnServiceStateChanged(serviceState))
                        }

                        override fun onSignalStrengthsChanged(signalStrength: SignalStrength) {
                            logger.logOnSignalStrengthsChanged(signalStrength, subId)
                            trySend(CallbackEvent.OnSignalStrengthChanged(signalStrength))
                        }

                        override fun onDataConnectionStateChanged(
                            dataState: Int,
                            networkType: Int
                        ) {
                            logger.logOnDataConnectionStateChanged(dataState, networkType, subId)
                            trySend(CallbackEvent.OnDataConnectionStateChanged(dataState))
                        }

                        override fun onDataActivity(direction: Int) {
                            logger.logOnDataActivity(direction, subId)
                            trySend(CallbackEvent.OnDataActivity(direction))
                        }

                        override fun onCarrierNetworkChange(active: Boolean) {
                            logger.logOnCarrierNetworkChange(active, subId)
                            trySend(CallbackEvent.OnCarrierNetworkChange(active))
                        }

                        override fun onDisplayInfoChanged(
                            telephonyDisplayInfo: TelephonyDisplayInfo
                        ) {
                            logger.logOnDisplayInfoChanged(telephonyDisplayInfo, subId)
                            trySend(CallbackEvent.OnDisplayInfoChanged(telephonyDisplayInfo))
                        }

                        override fun onDataEnabledChanged(enabled: Boolean, reason: Int) {
                            logger.logOnDataEnabledChanged(enabled, subId)
                            trySend(CallbackEvent.OnDataEnabledChanged(enabled))
                        }

                        override fun onStateChanged(serviceState: FiveGServiceState) {
                            logger.logOnNrIconTypeChanged(serviceState.nrIconType, subId)
                            trySend(CallbackEvent.OnNrIconTypeChanged(serviceState.nrIconType))
                        }
                    }
                telephonyManager.registerTelephonyCallback(bgDispatcher.asExecutor(), callback)
                fiveGServiceClient.registerListener(getSlotIndex(subId), callback)
                try {
                    imsMmTelManager.registerImsStateCallback(
                                context.getMainExecutor() , imsStateCallback)
                } catch (exception: ImsException) {
                    Log.e(tag, "failed to call registerImsStateCallback ", exception)
                }
                awaitClose {
                    telephonyManager.unregisterTelephonyCallback(callback)
                    fiveGServiceClient.unregisterListener(getSlotIndex(subId))
                    try {
                        imsMmTelManager.unregisterImsStateCallback(imsStateCallback)
                        imsMmTelManager.unregisterMmTelCapabilityCallback(capabilityCallback)
                        imsMmTelManager.unregisterImsRegistrationCallback(registrationCallback)
                    } catch (exception: Exception) {
                        Log.e(tag, "failed to call unregister ims callback ", exception)
                    }

                }
            }
            .scan(initial = initial) { state, event -> state.applyEvent(event) }
            .stateIn(scope = scope, started = SharingStarted.WhileSubscribed(), initial)
    }

    override val isEmergencyOnly =
        callbackEvents
            .mapNotNull { it.onServiceStateChanged }
            .map { it.serviceState.isEmergencyOnly }
            .stateIn(scope, SharingStarted.WhileSubscribed(), false)

    override val isRoaming =
        callbackEvents
            .mapNotNull { it.onServiceStateChanged }
            .map { it.serviceState.roaming }
            .stateIn(scope, SharingStarted.WhileSubscribed(), false)

    override val operatorAlphaShort =
        callbackEvents
            .mapNotNull { it.onServiceStateChanged }
            .map { it.serviceState.operatorAlphaShort }
            .stateIn(scope, SharingStarted.WhileSubscribed(), null)

    override val isInService =
        callbackEvents
            .mapNotNull { it.onServiceStateChanged }
            .map { Utils.isInService(it.serviceState) }
            .stateIn(scope, SharingStarted.WhileSubscribed(), false)

    override val isGsm =
        callbackEvents
            .mapNotNull { it.onSignalStrengthChanged }
            .map { it.signalStrength.isGsm }
            .stateIn(scope, SharingStarted.WhileSubscribed(), false)

    override val cdmaLevel =
        callbackEvents
            .mapNotNull { it.onSignalStrengthChanged }
            .map {
                it.signalStrength.getCellSignalStrengths(CellSignalStrengthCdma::class.java).let {
                    strengths ->
                    if (strengths.isNotEmpty()) {
                        strengths[0].level
                    } else {
                        SIGNAL_STRENGTH_NONE_OR_UNKNOWN
                    }
                }
            }
            .stateIn(scope, SharingStarted.WhileSubscribed(), SIGNAL_STRENGTH_NONE_OR_UNKNOWN)

    override val primaryLevel =
        callbackEvents
            .mapNotNull { it.onSignalStrengthChanged }
            .map { it.signalStrength.level }
            .stateIn(scope, SharingStarted.WhileSubscribed(), SIGNAL_STRENGTH_NONE_OR_UNKNOWN)

    override val dataConnectionState =
        callbackEvents
            .mapNotNull { it.onDataConnectionStateChanged }
            .map { it.dataState.toDataConnectionType() }
            .stateIn(scope, SharingStarted.WhileSubscribed(), Disconnected)

    override val dataActivityDirection =
        callbackEvents
            .mapNotNull { it.onDataActivity }
            .map { it.direction.toMobileDataActivityModel() }
            .stateIn(
                scope,
                SharingStarted.WhileSubscribed(),
                DataActivityModel(hasActivityIn = false, hasActivityOut = false)
            )

    override val carrierNetworkChangeActive =
        callbackEvents
            .mapNotNull { it.onCarrierNetworkChange }
            .map { it.active }
            .stateIn(scope, SharingStarted.WhileSubscribed(), false)

    override val resolvedNetworkType =
        callbackEvents
            .mapNotNull { it.onDisplayInfoChanged }
            .map {
                if (it.telephonyDisplayInfo.overrideNetworkType != OVERRIDE_NETWORK_TYPE_NONE) {
                    OverrideNetworkType(
                        mobileMappingsProxy.toIconKeyOverride(
                            it.telephonyDisplayInfo.overrideNetworkType
                        ),
                        it.telephonyDisplayInfo.overrideNetworkType
                    )
                } else if (it.telephonyDisplayInfo.networkType != NETWORK_TYPE_UNKNOWN) {
                    DefaultNetworkType(
                        mobileMappingsProxy.toIconKey(it.telephonyDisplayInfo.networkType),
                        it.telephonyDisplayInfo.networkType
                    )
                } else {
                    UnknownNetworkType
                }
            }
            .stateIn(scope, SharingStarted.WhileSubscribed(), UnknownNetworkType)

    override val numberOfLevels =
        systemUiCarrierConfig.shouldInflateSignalStrength
            .map { shouldInflate ->
                if (shouldInflate) {
                    DEFAULT_NUM_LEVELS + 1
                } else {
                    DEFAULT_NUM_LEVELS
                }
            }
            .stateIn(scope, SharingStarted.WhileSubscribed(), DEFAULT_NUM_LEVELS)

    /**
     * There are a few cases where we will need to poll [TelephonyManager] so we can update some
     * internal state where callbacks aren't provided. Any of those events should be merged into
     * this flow, which can be used to trigger the polling.
     */
    private val telephonyPollingEvent: Flow<Unit> = callbackEvents.map { Unit }

    override val cdmaRoaming: StateFlow<Boolean> =
        telephonyPollingEvent
            .mapLatest {
                val cdmaEri = telephonyManager.cdmaEnhancedRoamingIndicatorDisplayNumber
                cdmaEri == ERI_ON || cdmaEri == ERI_FLASH
            }
            .stateIn(scope, SharingStarted.WhileSubscribed(), false)

    override val networkName: StateFlow<NetworkNameModel> =
        broadcastDispatcher
            .broadcastFlow(
                filter = IntentFilter(TelephonyManager.ACTION_SERVICE_PROVIDERS_UPDATED),
                map = { intent, _ -> intent },
            )
            .filter { intent ->
                intent.getIntExtra(EXTRA_SUBSCRIPTION_ID, INVALID_SUBSCRIPTION_ID) == subId
            }
            .map { intent -> intent.toNetworkNameModel(networkNameSeparator) ?: defaultNetworkName }
            .stateIn(scope, SharingStarted.WhileSubscribed(), defaultNetworkName)

    override val dataEnabled = run {
        val initial = telephonyManager.isDataConnectionAllowed
        callbackEvents
            .mapNotNull { it.onDataEnabledChanged }
            .map { it.enabled }
            .stateIn(scope, SharingStarted.WhileSubscribed(), initial)
    }

    override val lteRsrpLevel: StateFlow<Int> =
        callbackEvents
            .mapNotNull { it.onSignalStrengthChanged }
            .map {
                it.signalStrength.getCellSignalStrengths(CellSignalStrengthLte::class.java).let {
                    strengths ->
                        if (strengths.isNotEmpty()) {
                            when (strengths[0].rsrp) {
                                SignalStrength.INVALID -> it.signalStrength.level
                                in -120 until -113 -> SIGNAL_STRENGTH_POOR
                                in -113 until -105 -> SIGNAL_STRENGTH_MODERATE
                                in -105 until -97 -> SIGNAL_STRENGTH_GOOD
                                in -97 until -43 -> SIGNAL_STRENGTH_GREAT
                                else -> SIGNAL_STRENGTH_NONE_OR_UNKNOWN
                            }
                        } else {
                            it.signalStrength.level
                        }
                    }
            }
            .stateIn(scope, SharingStarted.WhileSubscribed(), SIGNAL_STRENGTH_NONE_OR_UNKNOWN)

    override val voiceNetworkType: StateFlow<Int> =
        callbackEvents
            .mapNotNull { it.onServiceStateChanged }
            .map { it.serviceState.voiceNetworkType }
            .stateIn(scope, SharingStarted.WhileSubscribed(), NETWORK_TYPE_UNKNOWN)

    override val dataNetworkType: StateFlow<Int> =
        callbackEvents
            .mapNotNull { it.onServiceStateChanged }
            .map { it.serviceState.dataNetworkType }
            .stateIn(scope, SharingStarted.WhileSubscribed(), NETWORK_TYPE_UNKNOWN)

    override val nrIconType: StateFlow<Int> =
        callbackEvents
            .mapNotNull {it.onNrIconTypeChanged }
            .map { it.nrIconType}
            .stateIn(scope, SharingStarted.WhileSubscribed(), NrIconType.TYPE_NONE)

    private val dataRoamingSettingChangedEvent: Flow<Unit> = conflatedCallbackFlow {
        val observer =
            object : ContentObserver(null) {
                override fun onChange(selfChange: Boolean) {
                    trySend(Unit)
                }
            }

        context.contentResolver.registerContentObserver(
            Global.getUriFor("${Global.DATA_ROAMING}$subId"),
            true,
            observer)

        awaitClose { context.contentResolver.unregisterContentObserver(observer) }
    }

    override val dataRoamingEnabled: StateFlow<Boolean> = run {
        val initial = telephonyManager.isDataRoamingEnabled
        dataRoamingSettingChangedEvent
            .mapLatest { telephonyManager.isDataRoamingEnabled }
            .distinctUntilChanged()
            .logDiffsForTable(
                    tableLogBuffer,
                    columnPrefix = "",
                    columnName = "dataRoamingEnabled",
                    initialValue = initial,
            )
            .stateIn(scope, SharingStarted.WhileSubscribed(), initial)
    }

    override val originNetworkType: StateFlow<Int> =
        callbackEvents
            .mapNotNull { it.onDisplayInfoChanged }
            .map { it.telephonyDisplayInfo.networkType }
            .stateIn(scope, SharingStarted.WhileSubscribed(), NETWORK_TYPE_UNKNOWN)

    val imsStateCallback =
        object : ImsStateCallback() {
            override fun onAvailable() {
                try {
                    imsMmTelManager.registerImsRegistrationCallback(
                        context.mainExecutor, registrationCallback)
                    imsMmTelManager.registerMmTelCapabilityCallback(
                        context.mainExecutor, capabilityCallback)
                } catch (exception: ImsException) {
                    Log.e(tag, "onAvailable failed to call register ims callback ", exception)
                }
            }

            override fun onUnavailable(reason: Int) {
                try {
                    imsMmTelManager.unregisterMmTelCapabilityCallback(capabilityCallback)
                    imsMmTelManager.unregisterImsRegistrationCallback(registrationCallback)
                } catch (exception: Exception) {
                    Log.e(tag, "onUnavailable failed to call unregister ims callback ", exception)
                }
            }

            override fun onError() {
                try {
                    imsMmTelManager.unregisterMmTelCapabilityCallback(capabilityCallback)
                    imsMmTelManager.unregisterImsRegistrationCallback(registrationCallback)
                } catch (exception: Exception) {
                    Log.e(tag, "onUnavailable failed to call unregister ims callback ", exception)
                }
            }
        }

    override val voiceCapable: MutableStateFlow<Boolean> =
        MutableStateFlow<Boolean>(false)

    override val videoCapable: MutableStateFlow<Boolean> =
        MutableStateFlow<Boolean>(false)

    override val imsRegistered: MutableStateFlow<Boolean> =
        MutableStateFlow<Boolean>(false)

    override val imsRegistrationTech: MutableStateFlow<Int> =
        MutableStateFlow<Int>(REGISTRATION_TECH_NONE)

    private val registrationCallback =
        object : RegistrationManager.RegistrationCallback() {
            override fun onRegistered(attributes: ImsRegistrationAttributes) {
                imsRegistered.value = true
                imsRegistrationTech.value = attributes.getRegistrationTechnology()
            }

            override fun onUnregistered(info: ImsReasonInfo) {
                imsRegistered.value = false
                imsRegistrationTech.value = REGISTRATION_TECH_NONE
            }
        }

    private val capabilityCallback =
        object : ImsMmTelManager.CapabilityCallback() {
            override fun onCapabilitiesStatusChanged(config: MmTelCapabilities) {
                voiceCapable.value = config.isCapable(MmTelCapabilities.CAPABILITY_TYPE_VOICE)
                videoCapable.value = config.isCapable(MmTelCapabilities.CAPABILITY_TYPE_VIDEO)
            }
        }

    private fun getSlotIndex(subId: Int): Int {
        var subscriptionManager: SubscriptionManager =
                context.getSystemService(SubscriptionManager::class.java)
        var list: List<SubscriptionInfo> = subscriptionManager.completeActiveSubscriptionInfoList
        var slotIndex: Int = 0
        for (subscriptionInfo in list.iterator()) {
            if (subscriptionInfo.subscriptionId == subId) {
                slotIndex = subscriptionInfo.simSlotIndex
                break
            }
        }
        return slotIndex
    }

    class Factory
    @Inject
    constructor(
        private val broadcastDispatcher: BroadcastDispatcher,
        private val context: Context,
        private val telephonyManager: TelephonyManager,
        private val logger: MobileInputLogger,
        private val carrierConfigRepository: CarrierConfigRepository,
        private val mobileMappingsProxy: MobileMappingsProxy,
        @Background private val bgDispatcher: CoroutineDispatcher,
        @Application private val scope: CoroutineScope,
        private val fiveGServiceClient: FiveGServiceClient,
    ) {
        fun build(
            subId: Int,
            mobileLogger: TableLogBuffer,
            defaultNetworkName: NetworkNameModel,
            networkNameSeparator: String,
        ): MobileConnectionRepository {
            return MobileConnectionRepositoryImpl(
                context,
                subId,
                defaultNetworkName,
                networkNameSeparator,
                telephonyManager.createForSubscriptionId(subId),
                carrierConfigRepository.getOrCreateConfigForSubId(subId),
                broadcastDispatcher,
                mobileMappingsProxy,
                bgDispatcher,
                logger,
                mobileLogger,
                scope,
                fiveGServiceClient,
            )
        }
    }
}

/**
 * Wrap every [TelephonyCallback] we care about in a data class so we can accept them in a single
 * shared flow and then split them back out into other flows.
 */
sealed interface CallbackEvent {
    data class OnCarrierNetworkChange(val active: Boolean) : CallbackEvent
    data class OnDataActivity(val direction: Int) : CallbackEvent
    data class OnDataConnectionStateChanged(val dataState: Int) : CallbackEvent
    data class OnDataEnabledChanged(val enabled: Boolean) : CallbackEvent
    data class OnDisplayInfoChanged(val telephonyDisplayInfo: TelephonyDisplayInfo) : CallbackEvent
    data class OnServiceStateChanged(val serviceState: ServiceState) : CallbackEvent
    data class OnSignalStrengthChanged(val signalStrength: SignalStrength) : CallbackEvent
    data class OnNrIconTypeChanged(val nrIconType: Int) : CallbackEvent
}

/**
 * A simple box type for 1-to-1 mapping of [CallbackEvent] to the batched event. Used in conjunction
 * with [scan] to make sure we don't drop important callbacks due to late subscribers
 */
data class TelephonyCallbackState(
    val onDataActivity: CallbackEvent.OnDataActivity? = null,
    val onCarrierNetworkChange: CallbackEvent.OnCarrierNetworkChange? = null,
    val onDataConnectionStateChanged: CallbackEvent.OnDataConnectionStateChanged? = null,
    val onDataEnabledChanged: CallbackEvent.OnDataEnabledChanged? = null,
    val onDisplayInfoChanged: CallbackEvent.OnDisplayInfoChanged? = null,
    val onServiceStateChanged: CallbackEvent.OnServiceStateChanged? = null,
    val onSignalStrengthChanged: CallbackEvent.OnSignalStrengthChanged? = null,
    val onNrIconTypeChanged: CallbackEvent.OnNrIconTypeChanged? = null,
) {
    fun applyEvent(event: CallbackEvent): TelephonyCallbackState {
        return when (event) {
            is CallbackEvent.OnCarrierNetworkChange -> copy(onCarrierNetworkChange = event)
            is CallbackEvent.OnDataActivity -> copy(onDataActivity = event)
            is CallbackEvent.OnDataConnectionStateChanged ->
                copy(onDataConnectionStateChanged = event)
            is CallbackEvent.OnDataEnabledChanged -> copy(onDataEnabledChanged = event)
            is CallbackEvent.OnDisplayInfoChanged -> copy(onDisplayInfoChanged = event)
            is CallbackEvent.OnServiceStateChanged -> {
                copy(onServiceStateChanged = event)
            }
            is CallbackEvent.OnSignalStrengthChanged -> copy(onSignalStrengthChanged = event)
            is CallbackEvent.OnNrIconTypeChanged -> copy(onNrIconTypeChanged = event)
        }
    }
}
