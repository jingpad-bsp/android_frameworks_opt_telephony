
package com.android.internal.telephony;

import java.util.HashMap;
import java.util.List;
import java.util.Map;


import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.ContextWrapper;
import android.content.Intent;
import android.content.IntentFilter;
import android.database.ContentObserver;
import android.os.Handler;
import android.os.PersistableBundle;
import android.provider.Settings;
import android.provider.Settings.SettingNotFoundException;
import android.telephony.CarrierConfigManager;
import android.telephony.CarrierConfigManagerEx;
import android.telephony.SubscriptionInfo;
import android.telephony.SubscriptionManager;
import android.telephony.TelephonyManager;
import android.util.Log;

public class DataEnableController extends ContextWrapper {

    static final String TAG = "DataEnableController";
    static DataEnableController mInstance;
    private Context mContext;
    private SubscriptionManager mSubscriptionManager;
    private TelephonyManager mTelephonyManager;
    private int mDefaultDataSubId;
    private boolean mNeedUpdateDataEnable;
    final Map<Integer, DataRegisterContentObserver> mDataRegisterContentObservers = new HashMap<Integer, DataRegisterContentObserver>();

    public static DataEnableController getInstance() {
        return mInstance;
    }

    public static DataEnableController init(Context context) {
        synchronized (DataEnableController.class) {
            if (mInstance == null) {
                mInstance = new DataEnableController(context);
            } else {
                Log.wtf(TAG, "init() called multiple times!  mInstance = " + mInstance);
            }
            return mInstance;
        }
    }

    private DataEnableController(Context context) {
        super(context);
        mInstance = this;
        mContext = context;

        mSubscriptionManager = SubscriptionManager.from(mContext);
        mTelephonyManager = TelephonyManager.from(mContext);
        mDefaultDataSubId = SubscriptionManager.getDefaultDataSubscriptionId();
        mTelephonyManager.setDataEnabled(getDataEnable());
        IntentFilter filter = new IntentFilter(
                TelephonyIntents.ACTION_DEFAULT_DATA_SUBSCRIPTION_CHANGED);
        mContext.registerReceiver(mReceiver, filter);
        mSubscriptionManager.addOnSubscriptionsChangedListener(mOnSubscriptionsChangeListener);
        /* UNISOC: Modify for Bug1060471 When SetupWizard ,don't set data enable @{ */
        mContext.getContentResolver().registerContentObserver(
                Settings.Global.getUriFor(Settings.Global.DEVICE_PROVISIONED), false,
                mSetupWizardCompleteObserver);
    }

    private BroadcastReceiver mReceiver = new BroadcastReceiver() {

        @Override
        public void onReceive(Context context, Intent intent) {
            String action = intent.getAction();
            if (TelephonyIntents.ACTION_DEFAULT_DATA_SUBSCRIPTION_CHANGED.equals(action)) {
                if (isDeviceProvisioned()) {
                    selectDataCardUpdate();
                } else {
                    mNeedUpdateDataEnable = true;
                }
            }
        }
    };

    private ContentObserver mSetupWizardCompleteObserver = new ContentObserver(new Handler()) {
        public void onChange(boolean selfChange) {
            super.onChange(selfChange);
            Log.d(TAG, "mSetupWizardCompleteObserver : isDeviceProvisioned = " + isDeviceProvisioned());
            //We need to handle the Data switch after the device provision.
            if (isDeviceProvisioned() && mNeedUpdateDataEnable) {
                mNeedUpdateDataEnable = false;
                int newSubId = SubscriptionManager.getDefaultDataSubscriptionId();
                boolean isDataEnable = getDataEnable();
                if (isDataEnable != mTelephonyManager.getDataEnabled(newSubId)) {
                    Log.d(TAG, "setDataEnabled:" + isDataEnable);
                    mTelephonyManager.setDataEnabled(newSubId, isDataEnable);
                }
                disableDataForOtherSubscriptions(newSubId);
            }
        };
    };

    //This method to check the device whether or not in provisioning stage.
    private boolean isDeviceProvisioned() {
        return Settings.Global.getInt(mContext.getContentResolver(),
                Settings.Global.DEVICE_PROVISIONED, 0) != 0;
    }
    /* @} */

    private final SubscriptionManager.OnSubscriptionsChangedListener mOnSubscriptionsChangeListener = new SubscriptionManager.OnSubscriptionsChangedListener() {
        @Override
        public void onSubscriptionsChanged() {
            updateRegisterContentObserver();
        }
    };

    private void updateRegisterContentObserver() {
        List<SubscriptionInfo> subscriptions = mSubscriptionManager.getActiveSubscriptionInfoList();
        if (subscriptions == null) {
            return;
        }
        HashMap<Integer, DataRegisterContentObserver> cachedContentObservers = new HashMap<Integer, DataRegisterContentObserver>(
                mDataRegisterContentObservers);
        mDataRegisterContentObservers.clear();
        final int num = subscriptions.size();
        for (int i = 0; i < num; i++) {
            int subId = subscriptions.get(i).getSubscriptionId();
            // If we have a copy of this dataRegister already reuse it, otherwise
            // make a new one.
            if (cachedContentObservers.containsKey(subId)) {
                mDataRegisterContentObservers.put(subId, cachedContentObservers.remove(subId));
            } else {
                DataRegisterContentObserver dataRegister = new DataRegisterContentObserver(mContext,
                        subId);
                mDataRegisterContentObservers.put(subId, dataRegister);
            }
        }
        for (Integer key : cachedContentObservers.keySet()) {
            cachedContentObservers.get(key).unRegisterContentObserver();
        }
    }

    private boolean getDataEnable() {
        try {
            Log.d(TAG, "MOBILE_DATA=" + Settings.Global.getInt(mContext.getContentResolver(),
                    Settings.Global.MOBILE_DATA + SubscriptionManager.MAX_SUBSCRIPTION_ID_VALUE));
            return Settings.Global.getInt(mContext.getContentResolver(), Settings.Global.MOBILE_DATA
                    + SubscriptionManager.MAX_SUBSCRIPTION_ID_VALUE) != 0;
        } catch (SettingNotFoundException e) {
            Settings.Global.putInt(mContext.getContentResolver(),
                    Settings.Global.MOBILE_DATA + SubscriptionManager.MAX_SUBSCRIPTION_ID_VALUE, 1);
            return true;
        }
    }

    private void selectDataCardUpdate() {
        int newSubId = SubscriptionManager.getDefaultDataSubscriptionId();
        int newPhoneId = SubscriptionManager.getPhoneId(newSubId);
        boolean isDataEnable = getDataEnable();
        Log.d(TAG, "selectDataCardUpdate: newPhoneId = " + newPhoneId + ",newSubId = " + newSubId);
        if (!SubscriptionManager.isValidPhoneId(newPhoneId)) return;

        if (newSubId != mDefaultDataSubId) {
            if (SubscriptionManager.isValidSubscriptionId(newSubId)) {
                mDefaultDataSubId = newSubId;
            }
            if (isDataEnable != mTelephonyManager.getDataEnabled(newSubId)) {
                Log.d(TAG, "setDataEnabled:" + isDataEnable);
                mTelephonyManager.setDataEnabled(newSubId, isDataEnable);
            }
            disableDataForOtherSubscriptions(newSubId);
        }
    }

    //UNISOC:Modify for Bug929615,the non data SIM card's data switch was on and data connection active when carry out xcap supplementary business.
    private void disableDataForOtherSubscriptions(int subId) {
        SubscriptionManager subManager = SubscriptionManager.from(mContext);
        List<SubscriptionInfo> subInfoList = subManager.getActiveSubscriptionInfoList();
        if (subInfoList != null) {
            for (SubscriptionInfo subInfo : subInfoList) {
                if (subInfo.getSubscriptionId() != subId && mTelephonyManager.getDataEnabled(subInfo.getSubscriptionId())) {
                    Log.d(TAG, "disableDataForOtherSubscriptions =" + subInfo.getSubscriptionId());
                    mTelephonyManager.setDataEnabled(subInfo.getSubscriptionId(), false);
                }
            }
        }
    }

    private void update(int subId) {
        int defaultDataSubId = SubscriptionManager.getDefaultDataSubscriptionId();
        int defaultDataPhoneId = SubscriptionManager.getPhoneId(defaultDataSubId);
        boolean isDataEnable = mTelephonyManager.getDataEnabled(defaultDataSubId);
        if (SubscriptionManager.isValidSubscriptionId(defaultDataSubId)
                && SubscriptionManager.isValidPhoneId(defaultDataPhoneId)
                && subId == defaultDataSubId) {
            Log.d(TAG, "save default data state:" + isDataEnable);
            Settings.Global.putInt(mContext.getContentResolver(),
                    Settings.Global.MOBILE_DATA + SubscriptionManager.MAX_SUBSCRIPTION_ID_VALUE,
                    isDataEnable ? 1 : 0);
        }
    }

    public class DataRegisterContentObserver {

        Context mContext;
        int mSubId;

        public DataRegisterContentObserver(Context context, int subId) {
            mContext = context;
            mSubId = subId;
            mContext.getContentResolver().registerContentObserver(
                    Settings.Global.getUriFor(Settings.Global.MOBILE_DATA + mSubId), true,
                    mMobileDataObserver);
        }

        private ContentObserver mMobileDataObserver = new ContentObserver(new Handler()) {
            @Override
            public void onChange(boolean selfChange) {
                DataEnableController.this.update(mSubId);
            }
        };

        public void unRegisterContentObserver() {
            mContext.getContentResolver().unregisterContentObserver(mMobileDataObserver);
        }
    }
}
