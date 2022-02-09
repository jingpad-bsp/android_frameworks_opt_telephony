/*
 * Copyright (C) 2018 The Android Open Source Project
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

package com.android.internal.telephony.unisoc.emergency;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import android.annotation.NonNull;
import android.os.AsyncResult;
import android.os.Message;
import android.text.TextUtils;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;

import android.telephony.CarrierConfigManager;
import android.telephony.PhoneNumberUtils;
import android.telephony.Rlog;
import android.telephony.ServiceState;
import android.telephony.TelephonyManager;
import android.telephony.emergency.EmergencyNumber;

import com.android.internal.telephony.IccCardConstants;
import com.android.internal.telephony.MccTable;
import com.android.internal.telephony.Phone;
import com.android.internal.telephony.PhoneConstants;
import com.android.internal.telephony.TelephonyIntents;
import com.android.internal.telephony.CommandsInterface;
import com.android.internal.telephony.emergency.EmergencyNumberTracker;
import com.android.phone.ecc.nano.ProtobufEccData;
import com.android.phone.ecc.nano.ProtobufEccData.EccInfo;
import com.android.sprd.telephony.RadioInteractorCore;
import com.android.sprd.telephony.RadioInteractorFactory;
import android.telephony.NetworkRegistrationInfo;
import android.telephony.AccessNetworkConstants;

/**
 * Emergency Number Tracker that handles update of emergency number list from RIL and emergency
 * number database. This is multi-sim based and each Phone has a EmergencyNumberTracker.
 */
public class EmergencyNumberTrackerEx extends EmergencyNumberTracker {
    private static final String TAG = EmergencyNumberTrackerEx.class.getSimpleName();

    private final Phone mPhone;
    private TelephonyManager mTelephonyManager;
    private RadioInteractorCore mRadioInteractorCore;

    private BroadcastReceiver mIntentReceiverEx = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (intent.getAction().equals(TelephonyIntents.ACTION_SIM_STATE_CHANGED)) {
                int phoneId = intent.getIntExtra(PhoneConstants.PHONE_KEY, 0);
                String simState = intent.getStringExtra(IccCardConstants.INTENT_KEY_ICC_STATE);
                logd("ACTION_SIM_STATE_CHANGED: PhoneId: " + phoneId + " mPhoneId: "
                        + mPhone.getPhoneId() + " simState " + simState);
                if (phoneId == mPhone.getPhoneId()
                        && IccCardConstants.INTENT_VALUE_ICC_LOADED.equals(simState)) {
                    initializeDatabaseSimLoaded();
                }
                return;
            }
        }
    };

    public EmergencyNumberTrackerEx(Phone phone, CommandsInterface ci) {
        super(phone, ci);
        mPhone = phone;
        // Receive Carrier Config Changes
        IntentFilter filter = new IntentFilter(TelephonyIntents.ACTION_SIM_STATE_CHANGED);
        mPhone.getContext().registerReceiver(mIntentReceiverEx, filter);
        if (getRadioInteractorCore() == null) {
            RadioInteractorFactory.init(mPhone.getContext());
        }
        if (mRadioInteractorCore != null) {
            mRadioInteractorCore.updateEcclist("", null);
        }
        mTelephonyManager = (TelephonyManager) mPhone.getContext().
                getSystemService(Context.TELEPHONY_SERVICE);
    }

    private void initializeDatabaseSimLoaded() {
        // initializeDatabase with SimOperatorNumeric when SimLoaded for airplane mode
        if (mCountryIso == null || TextUtils.equals("", mCountryIso)) {
            mCountryIso = getCountryIsoBySimOperatorNumeric().toLowerCase();
            logd("initializeDatabaseSimLoaded: getCountryIsoBySimOperatorNumeric " + mCountryIso);
            if (TextUtils.equals("", mCountryIso)) {
                return;
            }
            updateEmergencyNumberDatabaseCountryChange(mCountryIso);
        }
    }

    private String getCountryIsoBySimOperatorNumeric () {
        String mcc = null;
        String countryIso = "";
        String numeric = ((TelephonyManager) mPhone.getContext().
                getSystemService(Context.TELEPHONY_SERVICE)).
                getSimOperatorNumericForPhone(mPhone.getPhoneId());
        if (!TextUtils.isEmpty(numeric)) {
            try {
                mcc = numeric.substring(0, 3);
                countryIso = MccTable.countryCodeForMcc(mcc);
            } catch (StringIndexOutOfBoundsException ex) {
                loge("updateLocale: Can't get country from operator numeric. mcc = "
                        + mcc + ". ex=" + ex);
            }
        }
        return countryIso;
    }

    private RadioInteractorCore getRadioInteractorCore() {
        if (mRadioInteractorCore == null && RadioInteractorFactory.getInstance() != null) {
            mRadioInteractorCore = RadioInteractorFactory.getInstance().getRadioInteractorCore(mPhone.getPhoneId());
        }
        return mRadioInteractorCore;
    }

    private String getMnc () {
        String mnc = "";
        String networkNumeric = mTelephonyManager.
                getNetworkOperatorForPhone(mPhone.getPhoneId());
        String simNumeric = mTelephonyManager.
                getSimOperatorNumericForPhone(mPhone.getPhoneId());
        if (!TextUtils.isEmpty(networkNumeric)) {
            try {
                mnc = networkNumeric.substring(3);
            } catch (StringIndexOutOfBoundsException ex) {
                loge("getMnc: Can't get country from operator numeric. mcc = "
                        + mnc + ". ex=" + ex);
            }
        }
        if (mnc.isEmpty()) {
            if (!TextUtils.isEmpty(simNumeric)) {
                try {
                    mnc = simNumeric.substring(3);
                } catch (StringIndexOutOfBoundsException ex) {
                    loge("getMnc: Can't get country from operator numeric. mcc = "
                            + mnc + ". ex=" + ex);
                }
            }
        }
        return mnc;
    }

    @Override
    protected EmergencyNumber convertEmergencyNumberFromEccInfo(EccInfo eccInfo, String countryIso) {
        String phoneNumber = eccInfo.phoneNumber.trim();
        if (phoneNumber.isEmpty()) {
            loge("EccInfo has empty phone number.");
            return null;
        }
        int emergencyServiceCategoryBitmask = 0;
        for (int typeData : eccInfo.types) {
            switch (typeData) {
                case EccInfo.Type.POLICE:
                    emergencyServiceCategoryBitmask = emergencyServiceCategoryBitmask == 0
                            ? EmergencyNumber.EMERGENCY_SERVICE_CATEGORY_POLICE
                            : emergencyServiceCategoryBitmask
                            | EmergencyNumber.EMERGENCY_SERVICE_CATEGORY_POLICE;
                    break;
                case EccInfo.Type.AMBULANCE:
                    emergencyServiceCategoryBitmask = emergencyServiceCategoryBitmask == 0
                            ? EmergencyNumber.EMERGENCY_SERVICE_CATEGORY_AMBULANCE
                            : emergencyServiceCategoryBitmask
                            | EmergencyNumber.EMERGENCY_SERVICE_CATEGORY_AMBULANCE;
                    break;
                case EccInfo.Type.FIRE:
                    emergencyServiceCategoryBitmask = emergencyServiceCategoryBitmask == 0
                            ? EmergencyNumber.EMERGENCY_SERVICE_CATEGORY_FIRE_BRIGADE
                            : emergencyServiceCategoryBitmask
                            | EmergencyNumber.EMERGENCY_SERVICE_CATEGORY_FIRE_BRIGADE;
                    break;
                case EccInfo.Type.MARINE:
                    emergencyServiceCategoryBitmask = emergencyServiceCategoryBitmask == 0
                            ? EmergencyNumber.EMERGENCY_SERVICE_CATEGORY_MARINE_GUARD
                            : emergencyServiceCategoryBitmask
                            | EmergencyNumber.EMERGENCY_SERVICE_CATEGORY_MARINE_GUARD;
                    break;
                case EccInfo.Type.MOUNTAIN:
                    emergencyServiceCategoryBitmask = emergencyServiceCategoryBitmask == 0
                            ? EmergencyNumber.EMERGENCY_SERVICE_CATEGORY_MOUNTAIN_RESCUE
                            : emergencyServiceCategoryBitmask
                            | EmergencyNumber.EMERGENCY_SERVICE_CATEGORY_MOUNTAIN_RESCUE;
                    break;
                case EccInfo.Type.MIEC:
                    emergencyServiceCategoryBitmask = emergencyServiceCategoryBitmask == 0
                            ? EmergencyNumber.EMERGENCY_SERVICE_CATEGORY_MIEC
                            : emergencyServiceCategoryBitmask
                            | EmergencyNumber.EMERGENCY_SERVICE_CATEGORY_MIEC;
                    break;
                case EccInfo.Type.AIEC:
                    emergencyServiceCategoryBitmask = emergencyServiceCategoryBitmask == 0
                            ? EmergencyNumber.EMERGENCY_SERVICE_CATEGORY_AIEC
                            : emergencyServiceCategoryBitmask
                            | EmergencyNumber.EMERGENCY_SERVICE_CATEGORY_AIEC;
                    break;
                default:
                    // Ignores unknown types.
            }
        }

        return new EmergencyNumber(phoneNumber, countryIso, eccInfo.mnc, emergencyServiceCategoryBitmask,
                new ArrayList<String>(), EmergencyNumber.EMERGENCY_NUMBER_SOURCE_DATABASE,
                eccInfo.routing);
    }

    @Override
    protected void updateEmergencyNumberList() {
        mEmergencyNumberListFromDatabase =
                mergeEmergencyNumberFromDatabase(mEmergencyNumberListFromDatabase);
        super.updateEmergencyNumberList();
        mEmergencyNumberList = mergeSameNumbersInEmergencyNumberList(mEmergencyNumberList);
        for (EmergencyNumber num : mEmergencyNumberList) {
            logd("mEmergencyNumberList after merge " + num);
        }
    }
    /**
     * In-place merge same emergency numbers in the emergency number list.
     * @param emergencyNumberListRadio the emergency number list to process
     */
    private List<EmergencyNumber> mergeSameNumbersInEmergencyNumberList(
            List<EmergencyNumber> emergencyNumberList) {
        if (emergencyNumberList.isEmpty()) {
            return emergencyNumberList;
        }

        // UNISOC: remove EmergencyNumber with invalid category.
        // Just do this for EmergencyNumbers that come from EMERGENCY_NUMBER_SOURCE_SIM.
        List<EmergencyNumber> resultEmergencyNumberList = new ArrayList<>();
        for (EmergencyNumber num : emergencyNumberList) {
            //UNISOC: remove duplicate number
            if (resultEmergencyNumberList.contains(num)) {
                logd("mergeSameNumbersInEmergencyNumberList remove duplicate " + num);
                continue;
            }
            if (num.getEmergencyNumberSourceBitmask() == EmergencyNumber.EMERGENCY_NUMBER_SOURCE_SIM) {
                if (num.getEmergencyServiceCategoryBitmask() >= 0
                        && num.getEmergencyServiceCategoryBitmask() < 0xff) {
                    resultEmergencyNumberList.add(num);
                }
            } else {
                resultEmergencyNumberList.add(num);
            }
        }

        if (resultEmergencyNumberList.isEmpty()) {
            return resultEmergencyNumberList;
        }
        Set<EmergencyNumber> mergedEmergencyNumber = new HashSet<>();
        for (int i = 0; i < resultEmergencyNumberList.size(); i++) {
            // Skip the check because it was merged.
            if (mergedEmergencyNumber.contains(resultEmergencyNumberList.get(i))) {
                continue;
            }
            for (int j = i + 1; j < resultEmergencyNumberList.size(); j++) {
                if (areSameEmergencyNumbersWithDiffCategory(
                        resultEmergencyNumberList.get(i), resultEmergencyNumberList.get(j))) {
                    logd("mergeSameNumbersInEmergencyNumberList Found unexpected duplicate numbers with differenct category: "
                            + resultEmergencyNumberList.get(i) + " vs " + resultEmergencyNumberList.get(j));
                    // Set the merged emergency number in the current position
                    resultEmergencyNumberList.set(i, mergeCategoryForSameEmergencyNumber(
                            resultEmergencyNumberList.get(i), resultEmergencyNumberList.get(j)));
                    // Mark the emergency number has been merged
                    mergedEmergencyNumber.add(resultEmergencyNumberList.get(j));
                }
                if (EmergencyNumber.areSameEmergencyNumbers(
                        resultEmergencyNumberList.get(i), resultEmergencyNumberList.get(j))) {
                    logd("mergeSameNumbersInEmergencyNumberList Found unexpected duplicate numbers: "
                            + resultEmergencyNumberList.get(i) + " vs " + resultEmergencyNumberList.get(j));
                    resultEmergencyNumberList.set(i, EmergencyNumber.mergeSameEmergencyNumbers(
                            resultEmergencyNumberList.get(i), resultEmergencyNumberList.get(j)));
                    // Mark the emergency number has been merged
                    mergedEmergencyNumber.add(resultEmergencyNumberList.get(j));
                }
            }
        }
        // Remove the marked emergency number in the orignal list
        for (int i = 0; i < resultEmergencyNumberList.size(); i++) {
            if (mergedEmergencyNumber.contains(resultEmergencyNumberList.get(i))) {
                resultEmergencyNumberList.remove(i--);
            }
        }
        return resultEmergencyNumberList;
    }

    /**
     * In-place merge same emergency numbers from database.
     * if some EmergencyNumber contains mnc, remove EmergencyNumber
     * without mnc but with same number
     * @param emergencyNumberList the emergency number list to process
     */
    private List<EmergencyNumber> mergeEmergencyNumberFromDatabase(
            List<EmergencyNumber> emergencyNumberList) {
        logd("mergeEmergencyNumberFromDatabase");

        List<EmergencyNumber> resultEmergencyNumberList = new ArrayList<>();
        boolean hasEmergencyNumberWithMnc = false;
        for (EmergencyNumber num : emergencyNumberList) {
            if (!num.getMnc().isEmpty() && num.getMnc().equals(getMnc())) {
                hasEmergencyNumberWithMnc = true;
            }
        }
        if (hasEmergencyNumberWithMnc) {
            for (EmergencyNumber num : emergencyNumberList) {
                if (!num.getMnc().isEmpty() && num.getMnc().equals(getMnc())) {
                    resultEmergencyNumberList.add(num);
                }
            }
        } else {
            for (EmergencyNumber num : emergencyNumberList) {
                if (num.getMnc().isEmpty()) {
                    resultEmergencyNumberList.add(num);
                }
            }
        }
        return resultEmergencyNumberList;
    }

    //UNISOC: check if two EmergencyNumbers with same number but different category.
    private boolean areSameEmergencyNumbersWithDiffCategory(@NonNull EmergencyNumber first,
            @NonNull EmergencyNumber second) {
        if (!first.getNumber().equals(second.getNumber())) {
            return false;
        }
        if (!first.getCountryIso().equals(second.getCountryIso())) {
            return false;
        }
        if (!first.getMnc().equals(second.getMnc())) {
            return false;
        }
        if (first.getEmergencyNumberSourceBitmask()
            != second.getEmergencyNumberSourceBitmask()) {
            return false;
        }
        if (!first.getEmergencyUrns().equals(second.getEmergencyUrns())) {
            return false;
        }
        if (first.getEmergencyCallRouting() != second.getEmergencyCallRouting()) {
            return false;
        }
        // Never merge two numbers if one of them is from test mode but the other one is not;
        // This supports to remove a number from the test mode.
        if (first.isFromSources(EmergencyNumber.EMERGENCY_NUMBER_SOURCE_TEST)
            ^ second.isFromSources(EmergencyNumber.EMERGENCY_NUMBER_SOURCE_TEST)) {
            return false;
        }
        if (first.getEmergencyServiceCategoryBitmask()
                == second.getEmergencyServiceCategoryBitmask()) {
            return false;
        }
        return true;
    }

    //UNISOC: merge different EmergencyNumber with same number but different category.
    private EmergencyNumber mergeCategoryForSameEmergencyNumber(@NonNull EmergencyNumber first,
            @NonNull EmergencyNumber second) {
        if (areSameEmergencyNumbersWithDiffCategory(first, second)) {
            return new EmergencyNumber(first.getNumber(), first.getCountryIso(), first.getMnc(),
                first.getEmergencyServiceCategoryBitmask()
                | second.getEmergencyServiceCategoryBitmask(),
                first.getEmergencyUrns(),
                first.getEmergencyNumberSourceBitmask(),
                first.getEmergencyCallRouting());
        }
        return null;
    }

    @Override
    public EmergencyNumber getEmergencyNumber(String emergencyNumber) {
        emergencyNumber = PhoneNumberUtils.stripSeparators(emergencyNumber);
        for (EmergencyNumber num : getEmergencyNumberList()) {
            if (num.getNumber().equals(emergencyNumber)) {
                int routing = num.getEmergencyCallRouting();
                if (mTelephonyManager.getSimState(mPhone.getPhoneId()) != TelephonyManager.SIM_STATE_READY
                        || isEmergencyOnly()) {
                    routing = EmergencyNumber.EMERGENCY_CALL_ROUTING_EMERGENCY;
                }
                return new EmergencyNumber(num.getNumber(),
                        num.getCountryIso(),
                        num.getMnc(),
                        num.getEmergencyServiceCategoryBitmask(),
                        num.getEmergencyUrns(),
                        num.getEmergencyNumberSourceBitmask(),
                        routing);
            }
        }
        return null;
    }

    private boolean isEmergencyOnly() {
        // UNISOC: add for bug1365564
        ServiceState ss = mPhone.getServiceState();
        if (ss == null) {
            logd("isEmergencyOnly false, ss is null");
            return false;
        }

        NetworkRegistrationInfo psInfo = ss.getNetworkRegistrationInfo(
                NetworkRegistrationInfo.DOMAIN_PS, AccessNetworkConstants.TRANSPORT_TYPE_WWAN);

        if (ss.getVoiceRegState() == ServiceState.STATE_IN_SERVICE
                || (psInfo != null && psInfo.isInService())) {
            return false;
        }

        if (ss.isEmergencyOnly() || (psInfo != null && psInfo.isEmergencyEnabled())) {
            logd("isEmergencyOnly");
            return true;
        }
        return false;
    }

    private void logd(String str) {
        Rlog.d(TAG, "[" + mPhone.getPhoneId() + "] " + str);
    }

    private void loge(String str) {
        Rlog.e(TAG, "[" + mPhone.getPhoneId() + "] " + str);
    }

}
