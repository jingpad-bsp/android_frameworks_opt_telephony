
package com.android.internal.telephony;

import android.app.ActivityThread;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.database.ContentObserver;
import android.os.Handler;
import android.os.Message;
import android.os.SystemProperties;
import android.provider.Settings;
import android.provider.SettingsEx;
import android.telephony.Rlog;
import android.telephony.SubscriptionManager;
import android.telephony.SubscriptionInfo;
import android.telephony.TelephonyManager;
import android.telephony.TelephonyManagerEx;
import android.text.TextUtils;
import android.util.Log;

import com.android.internal.telephony.PhoneConstants;
import com.android.internal.telephony.RadioController;
import com.android.internal.telephony.TelephonyIntents;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Iterator;
import java.util.List;

public class SimStateTracker extends Handler {

    private static boolean DBG = true;
    private static String TAG = "SimStateTracker";

    private static final String ACTION_MODEM_STAT_CHANGE = "com.android.modemassert.MODEM_STAT_CHANGE";

    public static final String ICC_ID_PREFS_NAME = "msms.info.iccid";
    public static final String ICC_ID = "icc_id";

    private static final int SIM_STATE_NULL = -1;
    private static final int NEW_REQUEST_NONE = 0;
    private static final int NEW_REQUEST = 1;
    private static final int NEW_REQUEST_HOT_SWAP = 2;
    private static final int SET_PC_STATUS_IDLE = 0;
    private static final int SET_PC_STATUS_PREPARING = 1;
    private static final int EVENT_RESET_RADIO_STATE_COMPLETE = 1;

    private static SimStateTracker mInstance;

    private Context mContext;
    private int[] mSimState;
    private int mSetRadioStatus;
    private int mNewRequest = NEW_REQUEST_NONE;
    private TelephonyManager mTeleMgr;
    private TelephonyManagerEx mTeleMgrEx;
    private SubscriptionController mSubController;
    private int mPhoneCount;
    private boolean mShuttingDown;
    private PrimarySubConfig mPrimarySubConfig;
    private List<OnSimStateChangedListener> mOnSimStateChangedListeners = new ArrayList<>();
    /*UNISOC: Feature for DM Telephony @{ */
    private int[] mSimStateLoadedFlag;
    private int mSimStateAbsentFlag;
    /* @} */

    private SimStateTracker(Context context) {
        mContext = context;
        mTeleMgr = (TelephonyManager) context.getSystemService(Context.TELEPHONY_SERVICE);
        mTeleMgrEx = (TelephonyManagerEx) TelephonyManagerEx.from(mContext);
        mSubController = SubscriptionController.getInstance();
        mPrimarySubConfig = PrimarySubConfig.getInstance();
        mPhoneCount = mTeleMgr.getPhoneCount();
        mSimState = new int[mPhoneCount];
        Arrays.fill(mSimState, SIM_STATE_NULL);
        mSetRadioStatus = SET_PC_STATUS_IDLE;
        /*UNISOC: Feature for DM Telephony @{ */
        mSimStateLoadedFlag = new int[mPhoneCount];
        Arrays.fill(mSimStateLoadedFlag, SIM_STATE_NULL);
        mSimStateAbsentFlag = 0;
        /* @} */
        TeleUtils.setRadioBusy(mContext,false);
        IntentFilter filter = new IntentFilter();
        filter.addAction(Intent.ACTION_SHUTDOWN);
        filter.addAction(ACTION_MODEM_STAT_CHANGE);
        filter.addAction(TelephonyManager.ACTION_SIM_CARD_STATE_CHANGED);
        filter.addAction(TelephonyManager.ACTION_SIM_APPLICATION_STATE_CHANGED);
        context.registerReceiver(mReceiver, filter);
        context.getContentResolver().registerContentObserver(
                Settings.Global.getUriFor(SettingsEx.GlobalEx.RADIO_BUSY), false,
                mRadioBusyObserver);
    }

    public static SimStateTracker init(Context context) {
        Rlog.d(TAG, "-- init --");
        synchronized (SimStateTracker.class) {
            if (mInstance == null) {
                mInstance = new SimStateTracker(context);
            } else {
                Log.wtf(TAG, "init() called multiple times!  mInstance = " + mInstance);
            }
        }
        return mInstance;
    }

    public static SimStateTracker getInstance() {
        return mInstance;
    }

    public interface OnSimStateChangedListener {
        void onSimHotSwaped(int phoneId);

        void onAllSimDetected(boolean isIccChanged);
    }

    public void addOnSimStateChangedListener(OnSimStateChangedListener listener) {
        if (!mOnSimStateChangedListeners.contains(listener)) {
            mOnSimStateChangedListeners.add(listener);
        }
    }

    public void removeOnSimStateChangedListener(OnSimStateChangedListener listener) {
        if (mOnSimStateChangedListeners.contains(listener)) {
            mOnSimStateChangedListeners.remove(listener);
        }
    }

    private void notifySimHotSwaped(int phoneId) {
        logd("notifySimHotSwaped: " + phoneId);
        for (OnSimStateChangedListener listener : mOnSimStateChangedListeners) {
            listener.onSimHotSwaped(phoneId);
        }
        mPrimarySubConfig.update();
        onAllSimAvailable(true, true);
    }

    private void notifyAllSimDetected(boolean isIccChanged) {
        logd("notifyAllSimDetected: " + isIccChanged);
        for (OnSimStateChangedListener listener : mOnSimStateChangedListeners) {
            listener.onAllSimDetected(isIccChanged);
        }
        mPrimarySubConfig.update();
        onAllSimAvailable(isIccChanged, false);
    }

    private void onAllSimAvailable(boolean isIccChanged, boolean isHotSwap) {
        checkRestrictedNetworkTypeFromConfig();
        if (isIccChanged) {
            setPreferredNetworkTypeFromConfig();
            autoSetDefaultVoiceSubId();
            autoSetDefaultSmsSubId();
            autoSetRadioState(isHotSwap);
        }
    }

    private void autoSetRadioState(boolean isHotSwap) {
        // If already in process, just hold the new request and handle it after
        // the previous one complete.
         logd("autoSetRadioState mSetRadioStatus = "+mSetRadioStatus+",mNewRequest="+mNewRequest);
        if (mSetRadioStatus != SET_PC_STATUS_IDLE || TeleUtils.isRadioBusy(mContext)) {
            mNewRequest = isHotSwap ? NEW_REQUEST_HOT_SWAP : NEW_REQUEST;
        } else {
            mSetRadioStatus = SET_PC_STATUS_PREPARING;
            mNewRequest = NEW_REQUEST_NONE;
            TeleUtils.setRadioBusy(mContext,true);
            resetRadioStateAccordingToSimState(isHotSwap);
        }
    }

    @Override
    public void handleMessage(Message msg) {
        logd("[handleMessage]: " + msg.what);
        switch (msg.what) {
            case EVENT_RESET_RADIO_STATE_COMPLETE:
                mSetRadioStatus = SET_PC_STATUS_IDLE;
                TeleUtils.setRadioBusy(mContext,false);
                break;
            default:
                logd("Unknown msg:" + msg.what);
        }
    }

    /**
     * Check whether radio state can match SIM status. If not, reset radios and
     * don't set primary card until radio operations are entirely complete.
     */
    private void resetRadioStateAccordingToSimState(final boolean isHotSwap) {
        boolean isAllSimAbsent = isAllSimAbsent();
        if (DBG)logd("[resetRadioState]: isAllSimAbsent = " + isAllSimAbsent);
        int[] ops = new int[mPhoneCount];
        for (int phoneId = 0; phoneId < mPhoneCount; phoneId++) {
            boolean desiredRadioState;

            if (isAllSimAbsent) {
                // Both slots are in multi-mode in L+L device. Power on the
                // first multi-mode slot if no SIM card.
                desiredRadioState = findFirstMultiModeSlot() == phoneId;
            } else {
                desiredRadioState = TeleUtils.hasIccCard(phoneId) && mTeleMgrEx.isSimEnabled(phoneId);
            }
            desiredRadioState = desiredRadioState && !TeleUtils.isAirplaneModeOn(mContext);
            ops[phoneId] = desiredRadioState ? RadioController.POWER_ON : RadioController.POWER_OFF;
        }
        RadioController.getInstance().setRadioPower(ops,
                obtainMessage(EVENT_RESET_RADIO_STATE_COMPLETE, isHotSwap ? 1 : 0, -1));
    }

    private ContentObserver mRadioBusyObserver = new ContentObserver(new Handler()) {
        public void onChange(boolean selfChange) {
            super.onChange(selfChange);
            logd("Radio busy changed: " + TeleUtils.isRadioBusy(mContext) + ", new request: "
                    + mNewRequest);
            if (!TeleUtils.isRadioBusy(mContext)) {
                if (mNewRequest != NEW_REQUEST_NONE) {
                    autoSetRadioState(mNewRequest == NEW_REQUEST_HOT_SWAP);
                }
            }
        };
    };

    private int findFirstMultiModeSlot() {
        // SPRD:Modify for bug835361 & Bug953255
        int multeModeSlot = SystemProperties.getInt("persist.vendor.radio.primarysim", 0);
        if (!mTeleMgrEx.isSimEnabled(multeModeSlot)) {
            for (int phoneId = 0; phoneId < mPhoneCount; phoneId++) {
                if (phoneId != multeModeSlot) {
                    return phoneId;
                }
            }
        }
        return multeModeSlot;
    }

    private BroadcastReceiver mReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            String action = intent.getAction();

            if (TelephonyManager.ACTION_SIM_CARD_STATE_CHANGED.equals(action)
                    || TelephonyManager.ACTION_SIM_APPLICATION_STATE_CHANGED.equals(action)) {
                onSimStateChanged(intent);
            } else if (Intent.ACTION_SHUTDOWN.equals(action)) {
                mShuttingDown = true;
            } else if (ACTION_MODEM_STAT_CHANGE.equals(action)) {
                String modemState = intent.getStringExtra("modem_stat");
                String info = intent.getStringExtra("modem_info");
                if ("modem_assert".equals(modemState) && info!= null && info.contains("Modem Assert")) {
                    // Clear mSimState when modem assert happen.
                    logd("Modem assert happen, clear SIM state.");
                    Arrays.fill(mSimState, SIM_STATE_NULL);
                }
            }
        }
    };

    private void onSimStateChanged(Intent intent) {
        int phoneId = intent.getIntExtra(PhoneConstants.PHONE_KEY, -1);
        int state = intent.getIntExtra(TelephonyManager.EXTRA_SIM_STATE,
                TelephonyManager.SIM_STATE_UNKNOWN);
        if (state == TelephonyManager.SIM_STATE_NOT_READY
                //UNISOC:Modify for Bug1144549
                || state == TelephonyManager.SIM_STATE_UNKNOWN) return;
        if (mShuttingDown) return;
        logd("handleSimStateChanged: " + phoneId + " " + state);

        if (!SubscriptionManager.isValidPhoneId(phoneId)) {
            return;
        }

        int oldState = mSimState[phoneId];
        if (oldState == state) {
            // SIM state isn't changed actually.
            logd("SIM state isn't changed actually.");
            return;
        }

        mSimState[phoneId] = state;

        if (isAllSimDetected()) {
            boolean isIccChanged = handleIccCardChanged();
            logd("All SIM detected, ICC changed: " + isIccChanged);
            if ((oldState != SIM_STATE_NULL && oldState != state)
                    && (TelephonyManager.SIM_STATE_ABSENT == oldState
                    || TelephonyManager.SIM_STATE_ABSENT == state)) {
                logd("SIM hot swaped: " + phoneId);
                notifySimHotSwaped(phoneId);
                /*UNISOC: Feature for DM Telephony @{ */
                if (mPhoneCount == 2) {
                    if (mSimStateLoadedFlag[phoneId] == -1 &&
                            TelephonyManager.SIM_STATE_LOADED == mSimState[phoneId]) {
                        mSimStateLoadedFlag[phoneId] = phoneId;
                    }
                    broadcastSimStateChanged(phoneId);
                }
                /* @} */
            } else {
                notifyAllSimDetected(isIccChanged);
            }
        }
        /*UNISOC: Feature for DM Telephony @{ */
        if (TelephonyManager.SIM_STATE_ABSENT == mSimState[phoneId]) {
            mSimStateAbsentFlag |= (1 << phoneId);
        }
        logd("mSimStateAbsentFlag: " + mSimStateAbsentFlag);

        if(mSimStateAbsentFlag ==((1 << mPhoneCount) - 1)){
            broadcastSimStateChanged(phoneId);
            mSimStateAbsentFlag = 0;
        }

        if (mPhoneCount == 2) {
            logd("mSimStateLoadedFlag :" + mSimStateLoadedFlag[phoneId]);
            if (mSimStateLoadedFlag[phoneId] == -1 &&
                    TelephonyManager.SIM_STATE_LOADED == mSimState[phoneId]) {
                mSimStateLoadedFlag[phoneId] = phoneId;
                broadcastSimStateChanged(phoneId);
            }
        }
        /*UNISOCs @} */
    }

    private boolean handleIccCardChanged() {
        boolean isIccChanged = false;
        // No need to save ICCID if any card is unknown because both SIMs may
        // haven't been changed.
        if (!mShuttingDown) {
            SharedPreferences preferences = mContext.getSharedPreferences(ICC_ID_PREFS_NAME, 0);
            for (int i = 0; i < mPhoneCount; i++) {
                String lastIccId = preferences.getString(ICC_ID + i, null);
                String newIccId = TeleUtils.getIccId(i);
                logd("[handleIccCardChanged] lastIccId = " + lastIccId + " newIccId = " + newIccId);
                if (!TextUtils.equals(lastIccId, newIccId)) {
                    if (!isSimLocked(i)) {
                        SharedPreferences.Editor editor = preferences.edit();
                        editor.putString(ICC_ID + i, newIccId);
                        editor.commit();
                        logd("[handleIccCardChanged] SIM " + i + " changed, save new iccid: "
                                + newIccId);
                    }
                    isIccChanged = true;
                }
            }
        }
        return isIccChanged;
    }

    public boolean isAllSimDetected() {
        for (int phoneId = 0; phoneId < mPhoneCount; phoneId++) {
            if (!isSimDetected(phoneId)) {
                return false;
            }
        }
        return true;
    }

    public boolean isAllSimLoaded() {
        for (int i = 0; i < mPhoneCount; i++) {
            if (TelephonyManager.SIM_STATE_LOADED != mSimState[i]) {
                return false;
            }
        }
        return true;
    }

    private boolean isSimLocked(int phoneId) {
        return  mSimState[phoneId] == TelephonyManager.SIM_STATE_PIN_REQUIRED
                || mSimState[phoneId] == TelephonyManager.SIM_STATE_PUK_REQUIRED
                || mSimState[phoneId] == TelephonyManager.SIM_STATE_NETWORK_LOCKED;
    }

    public boolean hasSimLocked() {
        for (int i = 0; i < mPhoneCount; i++) {
            if (mSimState[i] == TelephonyManager.SIM_STATE_PIN_REQUIRED
                   || mSimState[i] == TelephonyManager.SIM_STATE_PUK_REQUIRED
                   || mSimState[i] == TelephonyManager.SIM_STATE_NETWORK_LOCKED) {
                return true;
            }
        }
        return false;
    }

    public boolean isAllSimAbsent() {
        for (int phoneId = 0; phoneId < mPhoneCount; phoneId++) {
            if (TeleUtils.hasIccCard(phoneId)) {
                return false;
            }
        }
        return true;
    }

    public boolean hasIccCard(int phoneId) {
        return mSimState[phoneId] != SIM_STATE_NULL
                && mSimState[phoneId] != TelephonyManager.SIM_STATE_ABSENT;
    }

    private boolean isSimDetected(int phoneId) {
        return mSimState[phoneId] == TelephonyManager.SIM_STATE_ABSENT
                || mSimState[phoneId] == TelephonyManager.SIM_STATE_LOADED
                || mSimState[phoneId] == TelephonyManager.SIM_STATE_UNKNOWN // SIM busy
                || mSimState[phoneId] == TelephonyManager.SIM_STATE_PIN_REQUIRED
                || mSimState[phoneId] == TelephonyManager.SIM_STATE_PUK_REQUIRED
                || mSimState[phoneId] == TelephonyManager.SIM_STATE_NETWORK_LOCKED
                || mSimState[phoneId] == TelephonyManager.SIM_STATE_PERM_DISABLED;
    }

    public int getSimState(int phoneId) {
        if (SubscriptionManager.isValidPhoneId(phoneId)) {
            return mSimState[phoneId];
        }
        return SIM_STATE_NULL;
    }

    public int getPresentCardCount() {
        int simCount = 0;
        for (int i = 0; i < mPhoneCount; i++) {
            if (TeleUtils.hasIccCard(i)) {
                simCount++;
            }
        }
        return simCount;
    }

    private void checkRestrictedNetworkTypeFromConfig() {
        for (int i =0;i < mPhoneCount;i++) {
            int restrictedType = mPrimarySubConfig.getRestrictedNetworkType(i);
            if (restrictedType >= 0 && i == mPrimarySubConfig.getRestrictedPhoneId(i)) {
                if (DBG) logd("[SUB" + i + "] set restricted network type: " + restrictedType);
                setNetworkTypeForPhone(i);
            }
        }
    }

    private void setNetworkTypeForPhone(int phoneId) {
        if (hasIccCard(phoneId)) {
            int subId = SubscriptionController.getInstance().getSubIdUsingPhoneId(phoneId);
            if (SubscriptionManager.isValidSubscriptionId(subId)) {

                Phone phone = PhoneFactory.getPhone(phoneId);
                if (phone != null) {
                    int calType = PhoneFactory.calculatePreferredNetworkType(mContext,
                            phone.getSubId());
                    phone.setPreferredNetworkType(calType, null);
                }
            }
        }
    }

    private void setPreferredNetworkTypeFromConfig() {
        for (int i =0;i < mPhoneCount;i++) {
            int restrictedType = mPrimarySubConfig.getRestrictedNetworkType(i);
            if (restrictedType >= 0) {
                continue;
            }

            if (DBG) logd("[SUB" + i + "] set default preferred network type");
            setNetworkTypeForPhone(i);
        }
    }

    /**
     * Set the default voice/SMS sub to reasonable values if the user hasn't
     * selected a sub or the user selected sub is not present. This method won't
     * change user preference.
     */
    private void autoSetDefaultVoiceSubId() {
        List<SubscriptionInfo> activeSubInfoList = getActiveSubInfoList();
        List<SubscriptionInfo> subInfoList = mSubController.getActiveSubscriptionInfoList(mContext.getOpPackageName());
        int defaultVoiceSubId = SubscriptionManager.getDefaultVoiceSubscriptionId();
        if (subInfoList != null && subInfoList.size() > 0) {
            // Set unlocked SIM as default voice card first.
            int targetDefaultVoiceSubId = activeSubInfoList.size() > 0
                    ? activeSubInfoList.get(0).getSubscriptionId()
                    : subInfoList.get(0).getSubscriptionId();
            mSubController.setDefaultVoiceSubId(targetDefaultVoiceSubId);
            if (activeSubInfoList.size() > 1) {
                mSubController.setDefaultVoiceSubId(SubscriptionManager.INVALID_SUBSCRIPTION_ID);
            }
            logd("[autoSetDefaultPhones]: targetDefaultVoiceSubId= " + targetDefaultVoiceSubId);
        }
    }

    private void autoSetDefaultSmsSubId() {
        List<SubscriptionInfo> activeSubInfoList = getActiveSubInfoList();
        List<SubscriptionInfo> subInfoList = mSubController.getActiveSubscriptionInfoList(mContext.getOpPackageName());
        int defaultSmsSubId = SubscriptionManager.getDefaultSmsSubscriptionId();
        if (subInfoList != null && subInfoList.size() > 0) {
            // Set unlocked SIM as default sms card first.
            int targetDefaultSmsSubId = activeSubInfoList.size() > 0
                    ? activeSubInfoList.get(0).getSubscriptionId()
                    : subInfoList.get(0).getSubscriptionId();
            mSubController.setDefaultSmsSubId(targetDefaultSmsSubId);
            if (activeSubInfoList.size() > 1) {
                mSubController.setDefaultSmsSubId(SubscriptionManager.INVALID_SUBSCRIPTION_ID);
            }
            logd("[autoSetDefaultPhones]: targetDefaultSmsSubId= " + targetDefaultSmsSubId);
        }
    }

    private List<SubscriptionInfo> getActiveSubInfoList() {
        List<SubscriptionInfo> availableSubInfoList = mSubController.
                getActiveSubscriptionInfoList(mContext.getOpPackageName());
        if (availableSubInfoList == null) {
            return new ArrayList<SubscriptionInfo>();
        }
        Iterator<SubscriptionInfo> iterator = availableSubInfoList.iterator();
        while (iterator.hasNext()) {
            SubscriptionInfo subInfo = iterator.next();
            int phoneId = subInfo.getSimSlotIndex();
            boolean isSimReady = mTeleMgr.getSimState(phoneId) == TelephonyManager.SIM_STATE_READY;
            boolean isSimEnable = mSubController.isSubscriptionEnabled(subInfo.getSubscriptionId());
            if (!isSimReady || !isSimEnable || subInfo.isOpportunistic()) {
                iterator.remove();
            }
        }
        return availableSubInfoList;
    }

    private void logd(String msg) {
        if (DBG)
            Rlog.d(TAG, msg);
    }

    /*UNISOC: Feature for DM Telephony @{ */
    private void broadcastSimStateChanged(int phoneId) {
        int currentSimState = mTeleMgr.getSimState(phoneId);
        Intent dmyIntent = new Intent(
                com.dmyk.android.telephony.DmykAbsTelephonyManager.ACTION_SIM_STATE_CHANGED);
        dmyIntent.setPackage(com.dmyk.android.telephony.DmykAbsTelephonyManager.PACKAGE_NAME);
        dmyIntent.putExtra(com.dmyk.android.telephony.DmykAbsTelephonyManager.EXTRA_SIM_PHONEID, phoneId);
        dmyIntent.putExtra(com.dmyk.android.telephony.DmykAbsTelephonyManager.EXTRA_SIM_STATE,
                currentSimState);
        mContext.sendBroadcast(dmyIntent);
    }
    /*UNISOC: @} */
}
