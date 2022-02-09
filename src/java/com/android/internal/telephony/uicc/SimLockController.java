package com.android.internal.telephony.uicc;

import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.ServiceConnection;
import android.content.res.Resources;
import android.os.AsyncResult;
import android.os.Handler;
import android.os.IBinder;
import android.telephony.SubscriptionManager;
import android.telephony.TelephonyManager;
import android.text.TextUtils;
import android.util.Log;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;

import com.android.internal.telephony.IccCardConstants;
import com.android.internal.telephony.PhoneConstants;
import com.android.internal.telephony.SimStateTracker;
import com.android.internal.telephony.TelephonyIntents;
import com.android.sprd.telephony.RadioInteractor;
import com.android.sprd.telephony.RadioInteractorCallbackListener;
import com.android.sprd.telephony.uicc.IccCardStatusEx;

import java.lang.reflect.Field;
import java.util.ArrayList;

public class SimLockController extends Handler implements SimStateTracker.OnSimStateChangedListener {
    private static final String TAG = "SimLockController";
    private static final int PHONE_COUNT = TelephonyManager.getDefault().getPhoneCount();
    private static final int EVENT_SET_RADIO_POWER = 0;
    private static final int EVENT_SET_RADIO_POWER_DONE = 1;
    private static SimLockController mInstance;
    private SimStateTracker mSimStateTracker;
    private Context mContext;
    private RadioInteractor mRadioInteractor;
    private RadioInteractorCallbackListener[] mRadioInteractorCallbackListener;
    private boolean mExpireSim1 = false;
    private ArrayList<String> mSimLockWhiteLists = new ArrayList<String>();
    private TelephonyManager mTelephonyManager;
    private boolean mIgnoreExpireSim;

    private BroadcastReceiver mReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            String action = intent.getAction();
            Log.d(TAG, "receive broadcast : " + action);
            if (action.equals(TelephonyIntents.ACTION_SIM_STATE_CHANGED)) {
                int phoneId = intent.getIntExtra(PhoneConstants.PHONE_KEY,
                        SubscriptionManager.INVALID_PHONE_INDEX);
                String simState = intent.getStringExtra(IccCardConstants.INTENT_KEY_ICC_STATE);
                Log.d(TAG, "SIM_STATE_CHANGED: simState[" + phoneId + "] = " + simState);
                if (!SubscriptionManager.isValidPhoneId(phoneId)) {
                    return;
                }
                //SIM Card is Lock, not get nccmnc, so when loaded, setRadioPower again
                if (IccCardConstants.INTENT_VALUE_ICC_LOADED.equals(simState) &&
                        phoneId == 1) {
                    setUnAvailableForExpire(1);
                } else if (IccCardConstants.INTENT_VALUE_ICC_ABSENT.equals(simState)) {
                    if (phoneId == 0) {//Sim1 hot plug out, reset mExpireSim1 to false
                        mExpireSim1 = false;
                    }
                    //Set Emergency false
                    if (mTelephonyManager.hasIccCard(1)) {
                        mRadioInteractor.setEmergencyOnly(false, null, 1);
                    }
                }
            }
        }
    };

    private SimLockController(Context context) {
        mContext = context;
        mIgnoreExpireSim = Resources.getSystem().getBoolean(com.android.internal.R.bool.ignore_expire_sim);
        mTelephonyManager = TelephonyManager.from(mContext);
        mSimStateTracker = SimStateTracker.init(context);
        mSimStateTracker.addOnSimStateChangedListener(this);
        mRadioInteractorCallbackListener = new RadioInteractorCallbackListener[PHONE_COUNT];
        addRadioInteractorListener();
        final IntentFilter filter = new IntentFilter(TelephonyIntents.ACTION_SIM_STATE_CHANGED);
        if (mIgnoreExpireSim) {
            mContext.registerReceiver(mReceiver, filter);
        }
    }

    public static SimLockController init(Context context) {
        Log.d(TAG, "init");
        synchronized (SimLockController.class) {
            if (mInstance == null) {
                mInstance = new SimLockController(context);
            } else {
                Log.wtf(TAG, "init() called multiple times!  mInstance = " + mInstance);
            }
        }
        return mInstance;
    }

    public static SimLockController getInstance() {
        return mInstance;
    }

    private void addRadioInteractorListener() {
        mContext.bindService(new Intent("com.android.sprd.telephony.server.RADIOINTERACTOR_SERVICE")
                .setPackage("com.android.sprd.telephony.server"), new ServiceConnection() {
            @Override
            public void onServiceConnected(ComponentName name, IBinder service) {
                Log.d(TAG, "on radioInteractor service connected");
                if (mRadioInteractor == null) {
                    mRadioInteractor = new RadioInteractor(mContext);
                }
                for (int i = 0; i < PHONE_COUNT; i++) {
                    mRadioInteractorCallbackListener[i] = getRadioInteractorCallbackListener(i);
                    if (mIgnoreExpireSim) {
                        mRadioInteractor.listen(mRadioInteractorCallbackListener[i],
                                RadioInteractorCallbackListener.LISTEN_EXPIRE_SIM_EVENT, false);
                    }
                }
                String simLockWhiteList = mRadioInteractor.getSimlockWhitelist(IccCardStatusEx.UNLOCK_NETWORK, 0);
                addWhiteList(simLockWhiteList);
            }

            @Override
            public void onServiceDisconnected(ComponentName name) {
                for (int i = 0; i < PHONE_COUNT; i++) {
                    mRadioInteractor.listen(mRadioInteractorCallbackListener[i],
                            RadioInteractorCallbackListener.LISTEN_NONE);
                }
            }
        }, Context.BIND_AUTO_CREATE);
    }

    private void addWhiteList(String simLockWhiteList) {
        if (!TextUtils.isEmpty(simLockWhiteList)) {
            String temp[] = simLockWhiteList.split(",");
            if (temp != null && temp.length > 2) {
                for (int i = 2; i < temp.length; i++) {
                    Log.d(TAG, "add white list " + temp[i]);
                    mSimLockWhiteLists.add(temp[i]);
                }
            }
        }
    }

    RadioInteractorCallbackListener getRadioInteractorCallbackListener(final int phoneId) {
        return new RadioInteractorCallbackListener(phoneId) {
            @Override
            public void onExpireSimEvent(Object object) {
                Log.d(TAG, "onExpireSimEvent phoneId= " + phoneId);
                AsyncResult ar = (AsyncResult) object;
                if (ar.exception == null && ar.result != null) {
                    Integer phoneId = (Integer) ar.result;
                    Log.d(TAG, "expire sim = " + phoneId);
                    if (phoneId == 0) {
                        mExpireSim1 = true;
                        setUnAvailableForExpire(1);
                    }
                }
            }
        };
    }

    private void setUnAvailableForExpire(int phoneId) {
        Log.d(TAG, "setUnAvailableForExpire for sim" + phoneId);

        if (mExpireSim1) {
            boolean whiteListCard = isWhiteListCard(phoneId);
            Log.d(TAG, "WhiteListCard = " + whiteListCard);
            if (!whiteListCard) {
                mRadioInteractor.setEmergencyOnly(true, null, phoneId);
            }
        }
    }

    /**
     * Return whether the card is white list card
     */
    public boolean isWhiteListCard(int phoneId) {
        Log.d(TAG, "isWhiteListCard for phoneId " + phoneId);
        if (mSimLockWhiteLists == null || mSimLockWhiteLists.size() == 0) {
            Log.d(TAG, "no white list");
            return true;
        }

        if (mTelephonyManager.getSimState(phoneId) == TelephonyManager.SIM_STATE_NETWORK_LOCKED) {
            return false;
        }

        String simOperatorNumeric = mTelephonyManager.getSimOperatorNumericForPhone(phoneId);
        Log.d(TAG, "simOperatorNumeric = " + simOperatorNumeric);
        if (TextUtils.isEmpty(simOperatorNumeric)) {
            return true;
        }
        return mSimLockWhiteLists.contains(simOperatorNumeric);
    }

    /**
     * Return whether default data card can be hot-switch manually.
     */
    public boolean isDefaultDataCardSwitchAllowed() {
        Log.d(TAG, "isDefaultDataCardSwitchAllowed");
        if (!Resources.getSystem().getBoolean(com.android.internal.R.bool.simlock_restrict_data_card)) {
            Log.d(TAG, "no restrict data card, return true");
            return true;
        }
        boolean isDefaultDataCardSwitchAllowed = true;
        int whiteListCount = 0;
        for (int i = 0; i < PHONE_COUNT; i++) {
            if (isWhiteListCard(i)) {
                whiteListCount++;
                Log.d(TAG, "whiteListCount++");
            }
        }
        Log.d(TAG, "whiteListCount = " + whiteListCount);
        isDefaultDataCardSwitchAllowed = (whiteListCount == PHONE_COUNT);
        Log.d(TAG, "isDefaultDataCardSwitchAllowed = " + isDefaultDataCardSwitchAllowed);
        return isDefaultDataCardSwitchAllowed;
    }

    @Override
    public void onAllSimDetected(boolean isIccChanged) {
        Log.d(TAG, "onAllSimDetected. isIccChanged = " + isIccChanged);
        if (!Resources.getSystem().getBoolean(com.android.internal.R.bool.simlock_hide_unlock_view)) {
            return;
        }
        if (mTelephonyManager.hasIccCard(0) && isWhiteListCard(0)
                && mTelephonyManager.hasIccCard(1) && !isWhiteListCard(1)) {
            return;
        } else if (isAnySimLocked()) {
            int cardCount = getPresentCardCount();
            boolean skipProhibited = cardCount > 1 ? isAllSimNotInWhitelist() : mTelephonyManager.hasIccCard(0) && !isWhiteListCard(0);
            Log.d(TAG, "onAllSimDetected: cardCount = " + cardCount + ", skipProhibited = " + skipProhibited);
            showSimLockDialog(skipProhibited);
        }
    }

    @Override
    public void onSimHotSwaped(int phoneId) {
    }

    private boolean isAnySimLocked() {
        for (int i = 0; i < PHONE_COUNT; i++) {
            if (mTelephonyManager.getSimState(i) == TelephonyManager.SIM_STATE_NETWORK_LOCKED) {
                return true;
            }
        }
        return false;
    }

    private boolean isAllSimNotInWhitelist() {
        for (int i = 0; i < PHONE_COUNT; i++) {
            if (isWhiteListCard(i)) {
                return false;
            }
        }
        return true;
    }

    private int getPresentCardCount() {
        int simCount = 0;
        for (int i = 0; i < PHONE_COUNT; i++) {
            if (mTelephonyManager.hasIccCard(i)) {
                simCount++;
            }
        }
        return simCount;
    }

    private void showSimLockDialog(boolean skipProhibited) {
        Intent intent = new Intent();
        intent.setAction(TelephonyIntents.ACTION_SHOW_OPERATOR_SIMLOCK);
        intent.putExtra("skip_prohibited", skipProhibited);
        mContext.sendBroadcast(intent);
    }
}
