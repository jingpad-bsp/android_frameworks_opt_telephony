/* Created by Spreadst */

package com.android.internal.telephony.dataconnection;

import android.app.AlarmManager;
import android.app.AlertDialog;
import android.app.PendingIntent;
import android.content.AsyncQueryHandler;
import android.content.BroadcastReceiver;
import android.content.ContentResolver;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.res.Resources;
import android.content.SharedPreferences;
import android.database.ContentObserver;
import android.database.Cursor;
import android.net.ConnectivityManager;
import android.net.Uri;
import android.net.NetworkInfo;
import android.net.wifi.WifiManager;
import android.os.AsyncResult;
import android.os.BatteryManager;
import android.os.Handler;
import android.os.Message;
import android.os.PersistableBundle;
import android.os.SystemClock;
import android.os.SystemProperties;
import android.preference.PreferenceManager;
import android.provider.Settings;
import android.provider.SettingsEx;
import android.provider.Settings.SettingNotFoundException;
import android.telephony.AccessNetworkConstants;
import android.telephony.CarrierConfigManager;
import android.telephony.CarrierConfigManagerEx;
import android.telephony.DataFailCause;
import android.telephony.Rlog;
import android.telephony.ServiceState;
import android.telephony.SubscriptionManager;
import android.telephony.AccessNetworkConstants.TransportType;
import android.telephony.SubscriptionManager.OnSubscriptionsChangedListener;
import android.telephony.TelephonyManager;
import android.telephony.TelephonyManagerEx;
import android.telephony.data.ApnSetting;
import android.text.TextUtils;
import android.util.Pair;
import com.android.internal.telephony.DctConstants;
import com.android.internal.telephony.Phone;
import com.android.internal.telephony.PhoneConstants;
import com.android.internal.telephony.SettingsObserver;
import com.android.internal.telephony.dataconnection.DcTracker;
import com.android.internal.telephony.TelephonyIntents;
import com.android.internal.telephony.uicc.IccRecords;
import com.android.internal.util.ArrayUtils;
import com.android.sprd.telephony.RadioInteractor;
import com.android.sprd.telephony.RadioInteractorCallbackListener;

import java.util.Arrays;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

public final class DcTrackerEx extends DcTracker
        implements DataConnectionUtils.RadioInteractorCallback {
    protected static final String LOG_TAG = "DctEx";
    private static final boolean DBG = true;
    protected static final String INTENT_CLEANUP_DATA_ALARM =
            "com.sprd.telephony.data-cleanup";
    protected static final int DATA_KEEP_ALIVE_DURATION = 30 * 60 * 1000; // 30 min
    private static final String DATA_ALWAYS_ONLINE = "mobile_data_always_online";
    //UNISOC:FL1000060323-FDN
    private static final String INTENT_KEY_ICC_STATE = "ss";
    private static final String INTENT_VALUE_ICC_READY = "READY";
    private static final String SUBSCRIPTION_KEY  = "subscription";
    private static final String INTENT_EXTRA_SUB_ID = "subid";
    private static final Uri FDN_CONTENT_URI = Uri.parse("content://icc/fdn");
    private static final String FDN_CONTENT_PATH_WITH_SUB_ID = "content://icc/fdn/subId/";
    private static final String[] COLUMN_NAMES = new String[] {
        "name",
        "number"
    };

    private static final int DATA_ROAM_DISABLE = 0;
    private static final int DATA_ROAM_ALL = 1;
    private static final int DATA_ROAM_NATIONAL = 2;

    public static final int GET_DATA_CALL_LIST      = 0;
    public static final int REREGISTER              = 2;

    private static final int MAX_RETRY_FOR_VOLTE = 1;
    private static final int MAX_RETRY = 3;

    private static final String INTENT_RETRY_CLEAR_CODE =
            "com.sprd.internal.telephony.data-retry-clear-code";
    private static final String INTENT_RETRY_CLEAR_CODE_EXTRA_TYPE = "retry_clear_code_extra_type";
    private static final String INTENT_RETRY_CLEAR_CODE_EXTRA_REASON =
            "retry_clear_code_extra_reason";
    private static final String INTENT_RETRY_FROM_FAILURE =
            "com.sprd.internal.telephony.data-retry-from-failure";
    private static final String INTENT_RETRY_FROM_FAILURE_ALARM_EXTRA_TYPE =
            "retry_from_faliure_alarm_extra_type";
    //UNISOC:FL1000060323-FDN
    private static final String ACTION_FDN_STATUS_CHANGED =
            "android.callsettings.action.FDN_STATUS_CHANGED";
    private static final String ACTION_FDN_LIST_CHANGED =
            "android.callsettings.action.FDN_LIST_CHANGED";

    ////UNISOC:config QOS parameter
    private static final String QOS_SDU_ERROR_RATIO = "ril.data.qos_sdu_error_ratio";
    private static final String QOS_RESIDUAL_BIT_ERROR_RATIO = "ril.data.qos_residual_bit_error_ratio";

    //UNISOC:config special fallback cause for operator
    private static final String SPECIAL_FALLBACK_CAUSE = "ril.data.special.fallback.cause";

    /* Copy from DcTracker */
    private Phone mPhone;
    private final int mTransportType;
    private final AlarmManager mAlarmManager;
    private boolean mIsScreenOn = true;
    private ContentResolver mResolver;

    /* Whether network is shared */
    private boolean mNetworkShared = false;
    /* Whether the phone is charging */
    private boolean mCharging = false;
    /* Whether always online */
    private boolean mAlwaysOnline = true;
    /* Handles AOL settings change */
    private AolObserver mAolObserver;
    /* Alarm before going to deep sleep */
    private PendingIntent mCleaupAlarmIntent = null;
    private final Handler mHandler;
    // Plugin interface
    private DataConnectionUtils mUtils;

    private boolean mSupportTrafficClass;

    protected boolean mSupportSpecialClearCode = false;
    private int mPreFailcause = DataFailCause.NONE;
    private AtomicInteger mClearCodeLatch = new AtomicInteger(0);
    private PendingIntent mRetryIntent = null;
    private AlertDialog mErrorDialog = null;
    private ClearCodeRetryController mRetryController;
    private int mFailCount = 0;


    // Interface to radio interactor
    private RadioInteractor mRi;
    private RadioInteractorListener mRadioInteractorListener;

    //UNISOC:FL1000060323-FDN
    private boolean mSupportFdnEnable;
    private boolean mFdnEnable;
    private boolean mLastFdnEnable;
    private boolean hasNumber;
    private QueryHandler mQueryHandler;
    private String mFdnNum;
    private boolean mIsWifiConnected = false;

    private BroadcastReceiver mReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            String action = intent.getAction();
            log("onReceive() action=" + action);
            if (action.equals(Intent.ACTION_SCREEN_ON)) {
                onScreenOn();
            } else if (action.equals(Intent.ACTION_SCREEN_OFF)) {
                onScreenOff();
            } else if (action.equals(ConnectivityManager.ACTION_TETHER_STATE_CHANGED)) {
                ArrayList<String> tetheredIf = intent.getStringArrayListExtra(
                        ConnectivityManager.EXTRA_ACTIVE_TETHER);
                onNetworkShared(tetheredIf != null && !tetheredIf.isEmpty());
            } else if (action.equals(Intent.ACTION_BATTERY_CHANGED)) {
                boolean charging = intent.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) != 0;
                onBatteryChanged(charging);
            } else if (action.equals(INTENT_CLEANUP_DATA_ALARM)) {
                onActionIntentCleanupData();
            } else if (action.startsWith(INTENT_RETRY_CLEAR_CODE)) {
                if (DBG) log("Retry for clear code");
                onActionIntentRetryClearCode(intent);
            } else if (action.startsWith(INTENT_RETRY_FROM_FAILURE)) {
                if (DBG) log("Retry from previous failure");
                onActionIntentRetryFromFailure(intent);
            //UNISOC:FL1000060323-FDN
            } else if (action.equals(TelephonyIntents.ACTION_SIM_STATE_CHANGED)) {
                if (INTENT_VALUE_ICC_READY.equals(intent.getStringExtra(INTENT_KEY_ICC_STATE))) {
                    int subId = intent.getIntExtra(SUBSCRIPTION_KEY, -1);
                    if (subId == mPhone.getSubId()) {
                        queryFdnEnabled();
                    }
                }
            } else if (action.equals(ACTION_FDN_STATUS_CHANGED)) {
                int subId = intent.getIntExtra(INTENT_EXTRA_SUB_ID, -1);
                if (subId == mPhone.getSubId()) {
                    mFdnEnable = getIccFdnEnabled();
                    if (DBG) log("onFdnChanged mLastFdnEnable = " + mLastFdnEnable
                            + ", mFdnEnable = " + mFdnEnable);
                    if (mFdnEnable != mLastFdnEnable){
                        mLastFdnEnable = mFdnEnable;
                        if (mFdnEnable) {
                            queryFdnList();
                        } else {
                            onTrySetupData(Phone.REASON_DATA_ENABLED);
                        }
                    }
                }
            } else if (action.equals(ACTION_FDN_LIST_CHANGED)) {
                int subId = intent.getIntExtra(INTENT_EXTRA_SUB_ID, -1);
                if (subId == mPhone.getSubId()) {
                    queryFdnList();
                }
            }else if (action.equals(WifiManager.NETWORK_STATE_CHANGED_ACTION)) {
                final android.net.NetworkInfo networkInfo = (NetworkInfo)
                intent.getParcelableExtra(WifiManager.EXTRA_NETWORK_INFO);
                boolean connected = (networkInfo != null && networkInfo.isConnected());
                log("NETWORK_STATE_CHANGED_ACTION: old= " + mIsWifiConnected + " new= " +connected);
                if(mFailCount > 0 && mIsWifiConnected && !connected){
                    mRetryController.restartForChanged("WifiDisconnected");
                }
                mIsWifiConnected = connected;
            }
        }
    };

    private SubscriptionManager mSubscriptionManager;
    private final OnSubscriptionsChangedListener mOnSubscriptionsChangedListener =
            new OnSubscriptionsChangedListener() {
        @Override
        public void onSubscriptionsChanged() {
            int subId = mPhone.getSubId();
            log("SubscriptionListener.onSubscriptionInfoChanged, subId=" + subId);
            if (SubscriptionManager.isValidSubscriptionId(subId)) {
                mAlwaysOnline = Settings.Global.getInt(mPhone.getContext().getContentResolver(),
                        SettingsEx.GlobalEx.MOBILE_DATA_ALWAYS_ONLINE + mPhone.getSubId(),1) == 1 ? true : false;
                if (mAolObserver != null) {
                     mAolObserver.unregister();
                 }
                 mAolObserver = new AolObserver(mPhone.getContext(), mHandler);
                 mAolObserver.register(subId);
            }
        }
    };

    /**
     * Listener that mobile data always online change
     */
    public class AolObserver extends ContentObserver {

        public AolObserver(Context context, Handler handler) {
            super(null);
        }

        @Override
        public void onChange(boolean selfChange) {
            boolean aol = Settings.Global.getInt(mPhone.getContext().getContentResolver(),
                    SettingsEx.GlobalEx.MOBILE_DATA_ALWAYS_ONLINE + mPhone.getSubId(),1) == 1 ? true : false;
            if (mAlwaysOnline != aol) {
                log("Always online -> " + aol);
                if (aol) {
                    cancelAlarmForDeepSleep();
                    if (mDeepSleep) {
                        onTrySetupData(Phone.REASON_DATA_ENABLED);
                        mDeepSleep = false;
                    }
                } else if (!mIsScreenOn && !mCharging) {
                    startAlarmForDeepSleep();
                }
                mAlwaysOnline = aol;
            }
        }

        public void register(int subId) {
            mResolver.registerContentObserver(Settings.Global.getUriFor(SettingsEx.GlobalEx.MOBILE_DATA_ALWAYS_ONLINE + subId),
                    true, this);
        }
        public void unregister() {
            mResolver.unregisterContentObserver(this);
        }
    }

    public DcTrackerEx(Phone phone, @TransportType int transportType) {
        super(phone, transportType);
        mPhone = phone;
        mHandler = this;
        mTransportType = transportType;
        if (DBG) log("DctEx.constructor");
        mResolver = phone.getContext().getContentResolver();
        //UNISOC:FL1000060323-FDN
        mUtils = DataConnectionUtils.getInstance(mPhone.getContext());
        mSupportSpecialClearCode = mUtils.supportSpecialClearCode();
        if (transportType == AccessNetworkConstants.TRANSPORT_TYPE_WWAN) {
            DataPhoneManager dataPhoneManager = new DataPhoneManager(phone);
        }
        IntentFilter filter = new IntentFilter();
        filter.addAction(Intent.ACTION_SCREEN_ON);
        filter.addAction(Intent.ACTION_SCREEN_OFF);
        filter.addAction(ConnectivityManager.ACTION_TETHER_STATE_CHANGED);
        filter.addAction(Intent.ACTION_BATTERY_CHANGED);
        filter.addAction(INTENT_CLEANUP_DATA_ALARM);
        //UNISOC:FL1000060323-FDN
        mSupportFdnEnable = mUtils.isFndEnableSupport();
        if (mSupportFdnEnable) {
            filter.addAction(TelephonyIntents.ACTION_SIM_STATE_CHANGED);
            filter.addAction(ACTION_FDN_STATUS_CHANGED);
            filter.addAction(ACTION_FDN_LIST_CHANGED);
            mQueryHandler = new QueryHandler(mResolver);
            mFdnNum = mUtils.getFndNumForData();
        }
        if(mUtils.isVolteClearCode()){
            filter.addAction(WifiManager.NETWORK_STATE_CHANGED_ACTION);
        }
        mPhone.getContext().registerReceiver(mReceiver, filter, null, mPhone);

        mAlarmManager =
                (AlarmManager) phone.getContext().getSystemService(Context.ALARM_SERVICE);

        mSubscriptionManager = SubscriptionManager.from(mPhone.getContext());
        mSubscriptionManager
                .addOnSubscriptionsChangedListener(mOnSubscriptionsChangedListener);

        mSupportTrafficClass = Resources.getSystem().getBoolean(com.android.internal.R.bool.config_dataconnection_traffic_class);
        log("mSupportTrafficClass = " + mSupportTrafficClass);
        mUtils.addRadioInteractorCallback(this);


        if (mSupportSpecialClearCode) {
            for (ApnContext apnContext : mApnContexts.values()) {
                IntentFilter f = new IntentFilter();
                f.addAction(INTENT_RETRY_CLEAR_CODE + '.' + apnContext.getApnType());
                f.addAction(INTENT_RETRY_FROM_FAILURE + '.' + apnContext.getApnType());
                phone.getContext().registerReceiver(mReceiver, f, null, phone);
            }

            mRetryController = new ClearCodeRetryController(phone, this);
        }
    }

    @Override
    public void dispose() {
        if (DBG) log("DctEx.dispose");
        super.dispose();
        if (mAolObserver != null) {
            mAolObserver.unregister();
        }
        mSubscriptionManager
                .removeOnSubscriptionsChangedListener(mOnSubscriptionsChangedListener);
        mUtils.removeRadioInteractorCallback(this);
        if (mRetryController != null) {
            mRetryController.dispose();
            mRetryController = null;
        }
    }

    @Override
    public void handleMessage(Message msg) {
        switch (msg.what) {
            case DctConstants.EVENT_DATA_SETUP_COMPLETE:
                onDataSetupComplete((AsyncResult) msg.obj);
                break;

            case DctConstants.EVENT_RADIO_OFF_OR_NOT_AVAILABLE:
                onRadioOffOrNotAvailable();
                break;

            case DctConstants.EVENT_DATA_RAT_CHANGED:
                if (mRetryController != null) {
                    AsyncResult ar = (AsyncResult) msg.obj;
                    Pair<Integer, Integer> drsRatPair = (Pair<Integer, Integer>) ar.result;
                    mRetryController.handleDataServiceChange(drsRatPair.second,
                            drsRatPair.first);
                }
                break;
            case DctConstants.EVENT_DO_RECOVERY:
                if (getOverallState() == DctConstants.State.CONNECTED) {
                    int recoveryAction = Settings.System.getInt(mResolver,
                            "radio.data.stall.recovery.action", GET_DATA_CALL_LIST);
                    switch (recoveryAction) {
                    case REREGISTER:
                        if (DBG) log("doRecovery() re-register");
                        if(mRi != null){
                            mRi.requestReattach(mPhone.getPhoneId());
                        }else{
                            log("doRecovery() re-register mRi=null");
                        }
                        break;
                    }
                }
                break;
            case DctConstants.EVENT_DATA_ENABLED_CHANGED: {
                if(mUtils.isVolteClearCode()){
                    AsyncResult ar = (AsyncResult) msg.obj;
                    boolean enabled = false;
                    if (ar.result instanceof Pair) {
                        Pair<Boolean, Integer> p = (Pair<Boolean, Integer>) ar.result;
                        enabled = p.first;
                    }
                    log("CMD_SET_USER_DATA_ENABLE enabled=" + enabled);
                    //todo: isUserDataEnable changed
                    if(mFailCount > 0 && enabled){
                        mRetryController.restartForChanged("DataEnableOn");
                    }
                }
                break;
            }
        }
        super.handleMessage(msg);
    }

    protected boolean checkVendorDataAllowed() {
        // Phone is in DSM, don't allow data
        if (mDeepSleep) {
            log("not allowed - deep sleep");
            return false;
        }

        // If support traffic class, we must wait until radio interactor service is started
        if ((mSupportTrafficClass || getTrafficClassEnable()) && mRi == null) {
            log("not allowed - RI not connected");
            return false;
        }

        // Clear code 33/29
        if (mUtils.isSpecialCode(mPreFailcause)) {
            if ((mUtils.isNonVolteClearCode() && mFailCount >= MAX_RETRY) || (mUtils.isVolteClearCode() && mFailCount >= MAX_RETRY_FOR_VOLTE) || mClearCodeLatch.get() > 0) {
                // not allow data if the retrying timer doesn't expire or exceed max retry count
                log("not allowed - clear code " + mPreFailcause);
                return false;
            }
        }

        if(isFdnEnabled()){
            log("not allowed - FDN is enable");
            return false;
        }
        /* UNISOC: bug1098511 @{ */
        if(isSuspended()){
            log("not allowed - data is suspend");
            return false;
        }
        /* @} */
        if (getPsRejectProp()){
            log("not allowed - PS is Reject");
            return false;
        }

        TelephonyManagerEx telephonyManagerEx = TelephonyManagerEx.from(mPhone.getContext());
        boolean simLockDataEnable = Resources.getSystem().getBoolean(com.android.internal.R.bool.simlock_restrict_data);
        if (simLockDataEnable && !telephonyManagerEx.isWhiteListCard(mPhone.getPhoneId())){
            log("not allowed - not in sim lock white list");
            return false;
        }
        return true;
    }

    /*UNISOC: bug981835 support traffic class by carrier configure @{*/
    public boolean getTrafficClassEnable() {
        boolean trafficClassEnable = false;
        trafficClassEnable = SubscriptionManager.getResourcesForSubId(
              mPhone.getContext(), mPhone.getSubId()).getBoolean(com.android.internal.R.bool.traffic_class_enable);
        log("is support traffic class: " + trafficClassEnable);
        return trafficClassEnable;
    }
    /* @} */

    /*UNISOC: bug618350 add single pdp allowed by plmns feature@{*/
    @Override
    public void setSinglePDNAllowedByNetwork() {
        isOnlySingleDcAllowed = SubscriptionManager.getResourcesForSubId(
                mPhone.getContext(), mPhone.getSubId())
                .getBoolean(com.android.internal.R.bool.only_single_dataconnection_allowed);
        if (DBG) {
            log("isOnlySingleDcAllowed = " + isOnlySingleDcAllowed);
        }
        if (mRi != null) {
            mRi.requestSetSinglePDNByNetwork(isOnlySingleDcAllowed, mPhone.getPhoneId());
        }else {
            log("ri is null, do nothings!");
        }
    }
    /* @} */

    public boolean configQos(){
        SystemProperties.set(QOS_RESIDUAL_BIT_ERROR_RATIO,"");
        SystemProperties.set(QOS_SDU_ERROR_RATIO,"");
        String[] QosSettings = null;
        QosSettings = SubscriptionManager.getResourcesForSubId(
              mPhone.getContext(), mPhone.getSubId()).getStringArray(com.android.internal.R.array.config_Qos_setting_string_array);

        /* 1 No QosSetting: do not set mQOSSduErrorRatio、mQOSResidualBitErrorRatio，but set TrafficClass
         * 2 QosSetting.length == 1(2):set mQOSSduErrorRatio(mQOSResidualBitErrorRatio)、TrafficClass
         * 3 QosSetting.length == 3：if match rat，set mQOSSduErrorRatio、mQOSResidualBitErrorRatio、TrafficClass，otherwise do not set anyone
         */
        if (ArrayUtils.isEmpty(QosSettings)) {
            return true;
        }else{
            for (int i = 0; i < QosSettings.length; i++) {
                log("QosSettings[" + i + "]: " + QosSettings[i]);
            }
        }
        int rilRat = mPhone.getServiceState().getRilDataRadioTechnology();
        log("configQos:rilRat = " + rilRat);
        switch(QosSettings.length){
            case 3:
                log("configQos:QosSettings[2] = " + QosSettings[2]);
                String[] ratArray = QosSettings[2].split(",");
                boolean MatchRat = false;
                for (String ratString : ratArray) {
                    int rat = Integer.parseInt(ratString);
                    log("configQos:rat = " + rat);
                    if (rat == rilRat){
                        MatchRat = true;
                        break;
                    }
                }
                log("configQos:MatchRat = " + MatchRat);
                if (!MatchRat) return false;
            case 2:
                SystemProperties.set(QOS_RESIDUAL_BIT_ERROR_RATIO,QosSettings[1]);
            case 1:
                SystemProperties.set(QOS_SDU_ERROR_RATIO,QosSettings[0]);
            default:
                break;
        }
        return true;
    }

    public void configFallbackCause () {
        int fallbackCause = SubscriptionManager.getResourcesForSubId(
                mPhone.getContext(), mPhone.getSubId()).getInteger(com.android.internal.R.integer.specil_fallback_cause);
        log("fallbackCause is " + fallbackCause);
        SystemProperties.set(SPECIAL_FALLBACK_CAUSE, Integer.toString(fallbackCause));
    }

    /*
     * Do some preparation before setting up data call, for exmaple set traffic class
     */
    @Override
    public void prepareDataCall(ApnSetting apnSetting) {
        boolean needConfigTrafficClass = configQos();
        configFallbackCause();

        if ((mSupportTrafficClass || getTrafficClassEnable()) && needConfigTrafficClass) {
            if (mRi != null) {
                try {
                    String trafficClass = apnSetting.getTrafficClass();
                    if (trafficClass != null) {
                        int tc = Integer.parseInt(trafficClass);
                        mRi.requestDCTrafficClass(tc, mPhone.getPhoneId());
                    }
                } catch (NumberFormatException e) {
                    log("Error: illegal traffic class value");
                }
            } else {
                log("Error: radio interactor service not connected");
            }
        }
    }

    public void onRiConnected(RadioInteractor ri) {
        mRi = ri;
        mRadioInteractorListener = new RadioInteractorListener(mPhone.getPhoneId());
        if (mSupportTrafficClass || getTrafficClassEnable()) {
            log("Radio interactor connected, proceed to setup data call");
            onTrySetupData(Phone.REASON_DATA_ENABLED);
        }
        if (TelephonyManagerEx.isVsimProduct() >= TelephonyManagerEx.VSIM_MIFI_VERSION) {
            mRi.listen(mRadioInteractorListener,
                    RadioInteractorCallbackListener.LISTEN_NETWORK_ERROR_CODE_EVENT, false);
            mRi.listen(mRadioInteractorListener,
                    RadioInteractorCallbackListener.LISTEN_AVAILAVLE_NETWORKS_EVENT, false);
        }
    }

    /*UNISOC: Add for VSIM @{*/
    private class RadioInteractorListener extends RadioInteractorCallbackListener {
        public RadioInteractorListener(int slotId) {
            super(slotId);
        }

        @Override
        public void onNetowrkErrorCodeChangedEvent(Object obj) {
            log("onNetowrkErrorCodeChangedEvent");
            AsyncResult ar = (AsyncResult) obj;
            if (ar.exception == null && ar.result != null) {
                int[] cause = (int[]) ar.result;
                if (cause.length == 3) {
                    Intent intent = new Intent("android.intent.action.NW_ERROR_CODE");
                    intent.putExtra("phoneId", mPhone.getPhoneId());
                    intent.putExtra("ReasonType", cause[0]);
                    intent.putExtra("Reason", cause[1]);
                    intent.putExtra("plmn", cause[2]);
                    mPhone.getContext().sendBroadcast(intent);
                }
            }
        }

        @Override
        public void onAvailableNetworksEvent(Object object) {
            log("onAvailableNetworksEvent");
            AsyncResult ar = (AsyncResult) object;
            if (ar.exception == null && ar.result != null) {
                String[] ret = (String[]) ar.result;
                Intent intent = new Intent("android.intent.action.INCREMENTAL_NW_SCAN_IND");
                intent.putExtra("phoneId", mPhone.getPhoneId());
                intent.putExtra("incr_nw_scan_data", ret);
                mPhone.getContext().sendBroadcast(intent);
            }
        }
    }
    /* @} */

    /**
     * Return current {@link android.provider.Settings.Global#DATA_ROAMING} value.
     */
    @Override
    public boolean getDataRoamingEnabled() {
        boolean isDataRoamingEnabled = "true".equalsIgnoreCase(SystemProperties.get(
                "ro.com.android.dataroaming", "false"));
        final int phoneSubId = mPhone.getSubId();
        int dataRoamingSetting = DATA_ROAM_DISABLE;

        if (TelephonyManager.getDefault().getSimCount() == 1) {
            dataRoamingSetting = Settings.Global.getInt(mResolver,
                    Settings.Global.DATA_ROAMING, isDataRoamingEnabled ? 1 : 0);
        } else {
            dataRoamingSetting = Settings.Global.getInt(mResolver,
                    Settings.Global.DATA_ROAMING + phoneSubId,
                    isDataRoamingEnabled ? 1 : 0);
        }

        switch (dataRoamingSetting) {
            case DATA_ROAM_ALL:
                isDataRoamingEnabled = true;
                break;

            case DATA_ROAM_NATIONAL:
                // UNISOC: add for bug683034
                isDataRoamingEnabled = true;
                if (isNationalDataRoamingEnabled()) {
                    if (mPhone.getServiceState().getDataRoamingType()
                            != ServiceState.ROAMING_TYPE_DOMESTIC) {
                        isDataRoamingEnabled = false;
                    }
                }
                break;

            default:
                isDataRoamingEnabled = false;
                break;
        }

        if (DBG) {
            log("getDataOnRoamingEnabled: settingValue=" + dataRoamingSetting +
                    " isDataRoamingEnabled=" + isDataRoamingEnabled);
        }
        return isDataRoamingEnabled;
    }

    protected boolean handleSpecialClearCode(int cause, ApnContext apnContext) {
        log("handleSpecialClearCode(" + cause + ")");
        mPreFailcause = cause;
        if (mUtils.isSpecialCode(cause)) {
            // Handle special clear code
            mFailCount++;
            ApnSetting apn = apnContext.getApnSetting();
            log("[ClearCode] mFailCount=" + mFailCount + ", apn=" + apn);
            if ((mUtils.isNonVolteClearCode() && mFailCount < MAX_RETRY) || (mUtils.isVolteClearCode() && mFailCount < MAX_RETRY_FOR_VOLTE)) {
                log("[ClearCode] next retry");
                apnContext.setState(DctConstants.State.FAILED);
                if (apn != null) {
                    apn.setPermanentFailed(false);
                }
                // If the fail count < 3, retry it after some time
                int delay = DataConnectionUtils.RETRY_DELAY_LONG;
                if (mUtils.isNonVolteClearCode() && is4G()) {
                    delay = DataConnectionUtils.RETRY_DELAY_SHORT;
                }
                log("[ClearCode] retry connect APN delay=" + delay);
                startAlarmForRetryClearCode(delay, apnContext);
            } else {
                log("[ClearCode] process fail");
                if (is4G()) {
                    log("[ClearCode] In 4G, switch to 3G");
                    if (mRetryController != null) {
                        mRetryController.switchTo3G();
                    }
                    // UNISOC: Bug 753361 update apnContext state to FAILED
                    apnContext.setState(DctConstants.State.FAILED);
                } else {
                    log("[ClearCode] Max retry reached, remove all waiting apns");
                    apnContext.setState(DctConstants.State.FAILED);
                    apnContext.setWaitingApns(new ArrayList<ApnSetting>());
                    mPhone.notifyDataConnection(apnContext.getApnType());
                    apnContext.setDataConnection(null);

                    log("[ClearCode] All retry attempts failed, show user notification");
                    userNotification(cause);
                    startAlarmForRetryFromFailure(DataConnectionUtils.RETRY_FROM_FAILURE_DELAY,
                            apnContext);
                }
            }
            // We will do special retry, so stop other retry timer
            cancelAllReconnectAlarms();
            // return true and don't proceed to onDataSetupCompleteError
            return true;
        } else if (mSupportTrafficClass) {
            if (cause == DataFailCause.MISSING_UNKNOWN_APN) {
                ApnSetting apn = apnContext.getApnSetting();
                if (apn != null) {
                    //bug735770 not retry when pdp active fail cause is 27
                    apn.setPermanentFailed(true);
                }
            }
            return false;
        }

        return false;
    }

    private void onScreenOn() {
        mIsScreenOn = true;
        cancelAlarmForDeepSleep();
        if (mDeepSleep) {
            mDeepSleep = false;
            onTrySetupData(Phone.REASON_DATA_ENABLED);
        }
    }

    private void onScreenOff() {
        mIsScreenOn = false;
        if (!mAlwaysOnline && !mCharging) {
            startAlarmForDeepSleep();
        }
    }

    private void onBatteryChanged(boolean charging) {
        if (charging != mCharging) {
            mCharging = charging;
            if (charging) {
                cancelAlarmForDeepSleep();
                // Do we need to re-connecte data when charger is plugged?
                // Currently the screen is turned on when charger is plugged,
                // data will be re-connected in onScreenOn()
            } else if (!mIsScreenOn && !mAlwaysOnline) {
                startAlarmForDeepSleep();
            }
        }
    }

    private void onNetworkShared(boolean shared) {
        mNetworkShared = shared;
    }

    private void onActionIntentCleanupData() {
        cleanUpDataForDeepSleep();
    }

    /* If mobile data always online setting is off and
     *   1) screen is off,
     *   2) battery is not charging,
     * the data will be disconnected after 30min
     */
    private void startAlarmForDeepSleep() {
        Intent intent = new Intent(INTENT_CLEANUP_DATA_ALARM);
        mCleaupAlarmIntent = PendingIntent.getBroadcast(mPhone.getContext(), 0,
                                        intent, PendingIntent.FLAG_UPDATE_CURRENT);
        /* UNISOC: Bug 733607 open data always online function @{ */
        int delay = getDataKeepAliveDuration(DATA_KEEP_ALIVE_DURATION);

        if (DBG) {
            log("startAlarmForDeepSleep delay=" + delay);
        }

        mAlarmManager.setExact(AlarmManager.ELAPSED_REALTIME_WAKEUP,
                SystemClock.elapsedRealtime() + delay, mCleaupAlarmIntent);
        /* @}*/
    }

    private void cancelAlarmForDeepSleep() {
        if (mCleaupAlarmIntent != null) {
            if (DBG) {
                log("cancelAlarmForDeepSleep");
            }
            mAlarmManager.cancel(mCleaupAlarmIntent);
            mCleaupAlarmIntent = null;
        }
    }

    private void cleanUpDataForDeepSleep() {
        if (!mNetworkShared && !mCharging) {
            cleanUpAllConnections(Phone.REASON_DATA_DISABLED_INTERNAL);
            mDeepSleep = true;
        }
    }

    private void onTrySetupData(String reason) {
        sendMessage(obtainMessage(DctConstants.EVENT_TRY_SETUP_DATA, reason));
    }

    private void onDataSetupComplete(AsyncResult ar) {
        if (ar.exception == null) {
            mPreFailcause = DataFailCause.NONE;
            mFailCount = 0;
        }
    }

    private void onRadioOffOrNotAvailable() {
        if (mUtils.isSpecialCode(mPreFailcause)) {
            // shutdown radio, so reset state
            if (mRetryController != null) {
                mRetryController.notifyRadioOffOrNotAvailable();
            }
            stopFailRetryAlarm();
        }
    }

    private void startAlarmForRetryClearCode(int delay, ApnContext apnContext) {
        String apnType = apnContext.getApnType();

        Intent intent = new Intent(INTENT_RETRY_CLEAR_CODE + "." + apnType);
        intent.putExtra(INTENT_RETRY_CLEAR_CODE_EXTRA_REASON, apnContext.getReason());
        intent.putExtra(INTENT_RETRY_CLEAR_CODE_EXTRA_TYPE, apnType);
        // UNISOC: Bug 606723 Send broadcast with foregroud priority
        intent.addFlags(Intent.FLAG_RECEIVER_FOREGROUND);

        // Get current sub id.
        int subId = SubscriptionManager.getDefaultDataSubscriptionId();
        intent.putExtra(PhoneConstants.SUBSCRIPTION_KEY, subId);

        if (DBG) {
            log("startAlarmForRetryClearCode: delay=" + delay + " action=" + intent.getAction()
                    + " apn=" + apnContext);
        }
        // When PDP activation failed with special clear codes, the UE should not issue another PDP
        // request during 45s(or 10s in 4G). We use this flag to lock out the data
        mClearCodeLatch.set(1);

        PendingIntent alarmIntent = PendingIntent.getBroadcast (mPhone.getContext(), 0,
                                        intent, PendingIntent.FLAG_UPDATE_CURRENT);
        mRetryIntent = alarmIntent;
        mAlarmManager.setExact(AlarmManager.ELAPSED_REALTIME_WAKEUP,
                SystemClock.elapsedRealtime() + delay, alarmIntent);
    }

    private void startAlarmForRetryFromFailure(int delay, ApnContext apnContext) {
        String apnType = apnContext.getApnType();
        Intent intent = new Intent(INTENT_RETRY_FROM_FAILURE + "." + apnType);
        intent.putExtra(INTENT_RETRY_FROM_FAILURE_ALARM_EXTRA_TYPE, apnType);
        // UNISOC: Bug 606723 Send broadcast with foregroud priority
        intent.addFlags(Intent.FLAG_RECEIVER_FOREGROUND);

        if (DBG) {
            log("startAlarmForRetryFromFailure: delay=" + delay + " action="
                    + intent.getAction() + " apn=" + apnContext);
        }
        PendingIntent alarmIntent = PendingIntent.getBroadcast(
                mPhone.getContext(), 0, intent,
                PendingIntent.FLAG_UPDATE_CURRENT);
        mRetryIntent = alarmIntent;
        // Sometimes the timer not exact.
        mAlarmManager.setExact(AlarmManager.ELAPSED_REALTIME_WAKEUP,
                SystemClock.elapsedRealtime() + delay, alarmIntent);
    }

    void cancelAllReconnectAlarms() {
        for (ApnContext apnContext : mApnContexts.values()) {
            PendingIntent intent = apnContext.getReconnectIntent();
            if (intent != null) {
                AlarmManager am =
                    (AlarmManager) mPhone.getContext().getSystemService(Context.ALARM_SERVICE);
                am.cancel(intent);
                apnContext.setReconnectIntent(null);
            }
        }
    }

    void stopFailRetryAlarm() {
        if (mRetryIntent != null) {
            mAlarmManager.cancel(mRetryIntent);
            mRetryIntent = null;
        }
    }

    void clearPreFailCause() {
        mPreFailcause = DataFailCause.NONE;
        mFailCount = 0;
        mClearCodeLatch.set(0);
    }

    private void onActionIntentRetryClearCode(Intent intent) {
        // unlock data
        mClearCodeLatch.set(0);

        String reason = intent.getStringExtra(INTENT_RETRY_CLEAR_CODE_EXTRA_REASON);
        String apnType = intent.getStringExtra(INTENT_RETRY_CLEAR_CODE_EXTRA_TYPE);
        int phoneSubId = mPhone.getSubId();
        int currSubId = intent.getIntExtra(PhoneConstants.SUBSCRIPTION_KEY,
                SubscriptionManager.INVALID_SUBSCRIPTION_ID);
        log("onActionIntentRetryClearCode: currSubId = " + currSubId + " phoneSubId=" + phoneSubId);

        // Stop reconnect if not current subId is not correct.
        if (!SubscriptionManager.isValidSubscriptionId(currSubId) || (currSubId != phoneSubId)) {
            log("receive retry alarm but subId incorrect, ignore");
            return;
        }

        ApnContext apnContext = mApnContexts.get(apnType);

        if (DBG) {
            log("onActionIntentRetryClearCode: reason=" + reason +
                    " apnType=" + apnType + " apnContext=" + apnContext);
        }

        if ((apnContext != null) && (apnContext.isEnabled())) {
            apnContext.setReason(reason);
            sendMessage(obtainMessage(DctConstants.EVENT_TRY_SETUP_DATA, apnContext));
        }
    }

    private void onActionIntentRetryFromFailure(Intent intent) {
        mRetryIntent = null;
        String apnType = intent
                .getStringExtra(INTENT_RETRY_FROM_FAILURE_ALARM_EXTRA_TYPE);
        ApnContext apnContext = mApnContexts.get(apnType);
        if (DBG) {
            log("onActionIntentRetryFromFailure: apnType=" + apnType + " apnContext=" + apnContext);
        }
        // restart from begining
        if (mRetryController != null) {
            mRetryController.restartCycle(apnContext);
        }
    }

    private boolean is4G() {
        return mPhone.getServiceState().getRilVoiceRadioTechnology() ==
                 ServiceState.RIL_RADIO_TECHNOLOGY_LTE;
    }

    private void userNotification(int cause) {
        if (mErrorDialog != null && mErrorDialog.isShowing()) {
            mErrorDialog.dismiss();
        }

        mErrorDialog = mUtils.getErrorDialog(cause);
        if (mErrorDialog != null) {
            mErrorDialog.setOnDismissListener(new DialogInterface.OnDismissListener() {
                @Override
                public void onDismiss(DialogInterface dialog) {
                    mErrorDialog = null;
                }
            });
            mErrorDialog.show();
        }
    }

    //UNISOC:FL1000060323-FDN
    private Uri getFdnContentUri() {
        return SubscriptionManager.isValidSubscriptionId(mPhone.getSubId())
                ? Uri.parse(FDN_CONTENT_PATH_WITH_SUB_ID + mPhone.getSubId())
                : FDN_CONTENT_URI;
    }

    private class QueryHandler extends AsyncQueryHandler {
        public QueryHandler(ContentResolver cr) {
            super(cr);
        }

        @Override
        protected void onQueryComplete(int token, Object cookie, Cursor cursor) {
            hasNumber = false;
            if (cursor != null) {
                try {
                    cursor.moveToFirst();
                    do {
                        String number = cursor.getString(1);
                        if (number.equals(mFdnNum)) {
                            hasNumber = true;
                            break;
                        }
                    } while (cursor.moveToNext());
                } catch(Exception e) {
                    if (DBG) log("Exception thrown during hasSpeciaNumber e " + e);
                } finally {
                    if (cursor != null) {
                        cursor.close();
                    }
                }
            }
            mFdnEnable = getIccFdnEnabled();
            if (mFdnEnable) {
                if (hasNumber) {
                    onTrySetupData(Phone.REASON_DATA_ENABLED);
                } else {
                    cleanUpAllConnections(Phone.REASON_DATA_DISABLED_INTERNAL);
                }
            }
        }
    }

    private void queryFdnEnabled() {
        mFdnEnable = getIccFdnEnabled();
        if (mFdnEnable) {
            queryFdnList();
        }
    }

    private void queryFdnList() {
        mQueryHandler.startQuery(0, null, getFdnContentUri(), COLUMN_NAMES, null, null, null);
    }

    public boolean isFdnEnabled() {
        return mSupportFdnEnable && getIccFdnEnabled() && (!hasNumber);
    }

    public boolean getIccFdnEnabled() {
        return mPhone.getIccCard().getIccFdnEnabled();
    }

    /**
     * Is National roaming enabled
     */
    private boolean isNationalDataRoamingEnabled() {
        boolean retVal = false;
        retVal = mPhone.getContext().getResources()
                .getBoolean(com.android.internal.R.bool.national_data_roaming);
        return retVal;
    }

    /**
     * Get the delay before tearing down data
     */
    private int getDataKeepAliveDuration(int defValue) {
        int delayTime = defValue;
        String delay = SubscriptionManager.getResourcesForSubId(
                mPhone.getContext(), mPhone.getSubId())
                .getString(com.android.internal.R.string.dct_data_keep_alive_duration_int);
        if (!TextUtils.isEmpty(delay)) {
            delayTime = Integer.parseInt(delay);
        }
        log("delay " + delayTime + " before tearing down data");
        return delayTime;
    }

    private void log(String s) {
        Rlog.d(LOG_TAG, "[" + mPhone.getPhoneId() + "]" + s);
    }
}
