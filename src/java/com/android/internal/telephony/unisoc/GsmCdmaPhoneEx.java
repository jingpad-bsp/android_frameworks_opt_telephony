package com.android.internal.telephony;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.content.pm.ServiceInfo;
import android.content.SharedPreferences;
import android.Manifest;
import android.os.AsyncResult;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.Message;
import android.os.ResultReceiver;
import android.os.SystemProperties;
import android.telecom.VideoProfile;
import android.telephony.CarrierConfigManager;
import android.telephony.PhoneNumberUtils;
import android.telephony.Rlog;
import android.telephony.ServiceState;
import com.android.ims.ImsManager;
import android.os.PersistableBundle;
import android.preference.PreferenceManager;
import android.telephony.SubscriptionManager;
import android.telephony.TelephonyManager;
import android.text.TextUtils;
import android.util.Log;
import android.provider.Settings;

import com.android.internal.telephony.CommandsInterface;
import com.android.internal.telephony.PhoneInternalInterface.DialArgs;
import com.android.internal.telephony.gsm.GsmMmiCode;
import com.android.internal.telephony.gsm.GsmMmiCodeEx;
import com.android.internal.telephony.CallStateException;
import com.android.internal.telephony.Connection;
import com.android.internal.telephony.GsmAlphabet;
import com.android.internal.telephony.GsmCdmaPhone;
import com.android.internal.telephony.MmiCode;
import com.android.internal.telephony.Phone;
import com.android.internal.telephony.PhoneConstants;
import com.android.internal.telephony.PhoneNotifier;
import com.android.internal.telephony.TelephonyComponentFactory;
import com.android.internal.telephony.uicc.IccRecords;
import com.android.internal.telephony.uicc.IccUtils;
import com.android.internal.telephony.uicc.UiccCardApplication;
import android.telephony.CarrierConfigManagerEx;
import android.telephony.emergency.EmergencyNumber;

import com.android.sprd.telephony.uicc.IccCardStatusEx;
import java.util.ArrayList;
import java.util.List;

import com.android.ims.ImsConfig;
import com.android.ims.internal.ImsManagerEx;
import com.android.ims.internal.IImsServiceEx;

import android.os.RemoteException;
import android.os.ServiceManager;
import android.os.IBinder;
import android.location.Location;
import com.android.sprd.telephony.RadioInteractor;
import com.android.internal.telephony.SubscriptionController;

public class GsmCdmaPhoneEx extends GsmCdmaPhone {
    private static final String TAG = "GsmCdmaPhoneEx";
    private static final boolean LOCAL_DEBUG = true;
    private static final boolean DBG = true;
    private static final int GSM_TYPE = 15;
    private static final int UCS2_TYPE = 72;
    private static final int EVENT_SET_CALL_BARRING_DONE = 40;
    private static final int EVENT_GET_CALL_BARRING_DONE = 41;
    private static final int EVENT_CHANGE_CALL_BARRING_PASSWORD_DONE = 44;
    private static final int EVENT_SET_FACILITY_LOCK_DONE = 45;
    /* SPRD: add for bug699778 @{ */
    public static final int CB_REASON_AO = 0;
    public static final int CB_REASON_OI = 1;
    public static final int CB_REASON_OX = 2;
    public static final int CB_REASON_AI = 3;
    public static final int CB_REASON_IR = 4;
    public static final int CB_REASON_AB = 5;
    public static final int CB_ACTION_DISABLE = 0;
    public static final int CB_ACTION_ENABLE = 1;
    /* @} */
    public static final String VIDEO_STATE = "video_state";
    public static final String SERVICE_INTERFACE = "android.telephony.csvt.CsvtService";

    private IImsServiceEx mImsServiceEx;
    private boolean mDelayDial = false;
    private boolean mDeregisterVowifi = false;

    private RadioInteractor mRi;
    boolean mIsSubCarrierConfigLoaded = false; // UNISOC: Add for bug1033847

    public GsmCdmaPhoneEx(Context context, CommandsInterface ci, PhoneNotifier notifier,
                int phoneId,
                int precisePhoneType, TelephonyComponentFactory telephonyComponentFactory) {
        this(context, ci, notifier, false, phoneId, precisePhoneType, telephonyComponentFactory);
    }

    public GsmCdmaPhoneEx(Context context, CommandsInterface ci, PhoneNotifier notifier,
                          boolean unitTestMode, int phoneId, int precisePhoneType,
                          TelephonyComponentFactory telephonyComponentFactory) {
        super(context, ci, notifier, unitTestMode, phoneId, precisePhoneType,
                telephonyComponentFactory);
        if(phoneId == 0){
            bindNewCsvtService();
        }
        mRi = new RadioInteractor(mContext);

        //UNISOC: add for bug958569
        if (mContext.getResources().getBoolean(
                com.android.internal.R.bool.support_ecc_location_feature)) {
            EmcLocationManger.init(mContext);
        }
    }

    @Override
    public void handleMessage(Message msg) {
        AsyncResult ar;
        Message onComplete;
        switch (msg.what) {
            case EVENT_USSD:
                ar = (AsyncResult) msg.obj;
                Rlog.w(LOG_TAG, "handle EVENT_USSD message");
                String[] ussdResult = (String[]) ar.result;
                ussdResult = decryptUssdStrings(ussdResult);
                if (ussdResult.length > 1) {
                    try {
                        onIncomingUSSD(Integer.parseInt(ussdResult[0]), ussdResult[1]);
                    } catch (NumberFormatException e) {
                        Rlog.w(LOG_TAG, "error parsing USSD");
                    }
                }
                break;
            /* SPRD: add for bug699778 @{ */
            case EVENT_SET_CALL_BARRING_DONE:
                ar = (AsyncResult) msg.obj;
                if (ar.exception == null) {
                    // nothing to do so far
                }
                onComplete = (Message) ar.userObj;
                if (onComplete != null) {
                    AsyncResult.forMessage(onComplete, ar.result, ar.exception);
                    onComplete.sendToTarget();
                }
                break;
            /* @} */
            case EVENT_GET_CALL_BARRING_DONE:
                ar = (AsyncResult) msg.obj;
                if (ar.exception == null) {
                    // nothing to do so far
                }
                onComplete = (Message) ar.userObj;
                if (onComplete != null) {
                    AsyncResult.forMessage(onComplete, ar.result, ar.exception);
                    onComplete.sendToTarget();
                }
                break;

            case EVENT_CHANGE_CALL_BARRING_PASSWORD_DONE:
                ar = (AsyncResult) msg.obj;
                if (ar.exception == null) {
                    // nothing to do so far
                }
                onComplete = (Message) ar.userObj;
                if (onComplete != null) {
                    AsyncResult.forMessage(onComplete, ar.result, ar.exception);
                    onComplete.sendToTarget();
                }
                break;
                /* UNISOC: Support SimLock @{ */
                case EVENT_SET_FACILITY_LOCK_DONE:
                    ar = (AsyncResult) msg.obj;
                    int type = msg.arg2;
                    onSetFacilityLockDone(ar, type);
                    break;
                /* @} */
            default:
                super.handleMessage(msg);
        }
    }

    private void onIncomingUSSD(int ussdMode, String ussdMessage) {
        boolean isUssdRequest = (ussdMode == CommandsInterface.USSD_MODE_REQUEST);

        boolean isUssdError = (ussdMode != CommandsInterface.USSD_MODE_NOTIFY
                && ussdMode != CommandsInterface.USSD_MODE_REQUEST
                // SPRD: add for bug494828
                && ussdMode != CommandsInterface.USSD_MODE_NW_RELEASE);

        boolean isUssdRelease = (ussdMode == CommandsInterface.USSD_MODE_NW_RELEASE);
        // See comments in GsmMmiCode.java
        // USSD requests aren't finished until one
        // of these two events happen
        GsmMmiCode found = null;
        List<MmiCode> pendingMMIList = (List<MmiCode>) getPendingMmiCodes();
        int size = pendingMMIList.size();
        for (int i = 0; i < size; i++) {
            if (((GsmMmiCode) pendingMMIList.get(i)).isPendingUSSD()) {
                found = (GsmMmiCode) pendingMMIList.get(i);
                break;
            }
        }
        if (LOCAL_DEBUG) {
            Rlog.d(TAG, "ussdMode = " + ussdMode + ", found = " + found + ", ussdMessage = "
                    + ussdMessage);
        }

        if (found != null) {
            // Complete pending USSD
            if (LOCAL_DEBUG) {
                Rlog.d(TAG, "USSD state = " + found.getState());
            }

            if (isUssdRelease) {
                found.onUssdRelease();
            } else if (isUssdError) {
                found.onUssdFinishedError();
            } else {
                found.onUssdFinished(ussdMessage, isUssdRequest);
            }
        } else { // pending USSD not found
            // The network may initiate its own USSD request

            // ignore everything that isnt a Notify or a Request
            // also, discard if there is no message to present
            if (!isUssdError && ussdMessage != null) {
                GsmMmiCode mmi;
                mmi = GsmMmiCode.newNetworkInitiatedUssd(ussdMessage,
                        isUssdRequest,
                        this,
                        mUiccApplication.get());
                onNetworkInitiatedUssd(mmi);
            } else if (isUssdError) {
                GsmMmiCode mmi;
                //UNISOC: porting ussd feature
                mmi = GsmMmiCodeEx.newNetworkInitiatedUssdError(ussdMessage,
                        isUssdRequest,
                        this,
                        mUiccApplication.get());
                onNetworkInitiatedUssd(mmi);
            } else {
            }
        }
    }

    private void onNetworkInitiatedUssd(GsmMmiCode mmi) {
        mMmiCompleteRegistrants.notifyRegistrants(new AsyncResult(null, mmi, null));
    }

    /**
     * decrypt ussd message
     *
     * @param response
     * @return
     */
    private String[] decryptUssdStrings(String[] response) {
        if (response.length > 2) {
            int num = Integer.parseInt(response[2]);
            if (num == GSM_TYPE) {
                byte[] dataUssd = IccUtils.hexStringToBytes(response[1]);
                response[1] = GsmAlphabet.gsm8BitUnpackedToString(dataUssd, 0, dataUssd.length);
            } else if (num == UCS2_TYPE) {
                byte[] bytes = new byte[response[1].length() / 2];
                for (int i = 0; i < response[1].length(); i += 2) {
                    bytes[i / 2] = (byte) (Integer.parseInt(response[1].substring(i, i + 2), 16));
                }
                try {
                    String utfString = new String(bytes, "UTF-16");
                    response[1] = utfString;
                } catch (Exception e) {
                }
            } else {
            }
        }
        return response;
    }

    @Override
    public Connection dial(String dialString, DialArgs dialArgs)
            throws CallStateException {
        if (!isPhoneTypeGsm() && dialArgs.uusInfo != null) {
            throw new CallStateException("Sending UUS information NOT supported in CDMA!");
        }

        boolean isEmergency = PhoneNumberUtils.isEmergencyNumber(getSubId(),dialString);
        Phone imsPhone = mImsPhone;

        CarrierConfigManager configManager =
                (CarrierConfigManager) mContext.getSystemService(Context.CARRIER_CONFIG_SERVICE);
        boolean alwaysTryImsForEmergencyCarrierConfig = configManager.getConfigForSubId(getSubId())
                .getBoolean(CarrierConfigManager.KEY_CARRIER_USE_IMS_FIRST_FOR_EMERGENCY_BOOL);

        /** Check if the call is Wireless Priority Service call */
        boolean isWpsCall = dialString != null ? dialString.startsWith("*272") : false;
        boolean allowWpsOverIms = configManager.getConfigForSubId(getSubId())
            .getBoolean(CarrierConfigManager.KEY_SUPPORT_WPS_OVER_IMS_BOOL);


        boolean useImsForCall = isImsUseEnabled()
                && imsPhone != null
                && (imsPhone.isVolteEnabled() || imsPhone.isWifiCallingEnabled() ||
                (imsPhone.isVideoEnabled() && VideoProfile.isVideo(dialArgs.videoState)))
                && (imsPhone.getServiceState().getState() == ServiceState.STATE_IN_SERVICE)
                && (isWpsCall ? allowWpsOverIms : true);

        boolean useImsForEmergency = imsPhone != null
                && isEmergency
                && alwaysTryImsForEmergencyCarrierConfig
                && ImsManager.getInstance(mContext, mPhoneId).isNonTtyOrTtyOnVolteEnabled()
                && imsPhone.isImsAvailable()
                //UNISOC: Trying (non-IMS) CS Ecall when (non-IMS) in call
                && !isInCall();

        String dialPart = PhoneNumberUtils.extractNetworkPortionAlt(PhoneNumberUtils.
                stripSeparators(dialString));
        boolean isUt = (dialPart.startsWith("*") || dialPart.startsWith("#"))
                && dialPart.endsWith("#");

        boolean useImsForUt = imsPhone != null && imsPhone.isUtEnabled();

        /*SPRD: add for vowifi emergency call bug1073005@{*/
        int type = getCurrentImsFeature();
        if (LOCAL_DEBUG){
            Rlog.d(LOG_TAG, "type = " + type);
        }
        mDelayDial = false;
        mDeregisterVowifi = false;
        if (isEmergency ) {
            if (type == ImsConfig.FeatureConstants.FEATURE_TYPE_VOICE_OVER_WIFI) {
                if (dialECallOnIms()
                        || isFakeEmergencyNumber(dialString)) {
                    useImsForCall = true;
                    useImsForEmergency = true;
                } else {
                    useImsForCall = false;
                    useImsForEmergency = false;
                }
            }

            //UNISOC: add for bug958569
            if (mContext.getResources().getBoolean(
                    com.android.internal.R.bool.support_ecc_location_feature)) {
                sendLocationToCP();
            }
        }

        DialArgs.Builder builder = new DialArgs.Builder();
        Bundle intentExtras = new Bundle();
        intentExtras.putBoolean("delay_dial",mDelayDial);
        intentExtras.putShort(VIDEO_STATE, (short) dialArgs.videoState);
        builder.setUusInfo(dialArgs.uusInfo);
        builder.setVideoState(dialArgs.videoState);
        if(dialArgs.intentExtras != null){
            dialArgs.intentExtras.putShort(VIDEO_STATE, (short) dialArgs.videoState);//UNISOC:modify for bug926188
        }
        builder.setIntentExtras(dialArgs.intentExtras);
        dialArgs = builder.build();
        /*@}*/

        /* UNISOC:add for bug 1087183,dial emergency on cs. @{ */
        boolean alwaysDialEccOnCs = isEmergency && configManager.getConfigForSubId(getSubId())
                .getBoolean(CarrierConfigManagerEx.KEY_CARRIER_ALWAYS_DIAL_ECC_ON_CS);
        /* @} */

        if (DBG) {
            logd("useImsForCall=" + useImsForCall
                    + ", useImsForEmergency=" + useImsForEmergency
                    + ", useImsForUt=" + useImsForUt
                    + ", isUt=" + isUt
                    + ", isWpsCall=" + isWpsCall
                    + ", allowWpsOverIms=" + allowWpsOverIms
                    + ", imsPhone=" + imsPhone
                    + ", imsPhone.isVolteEnabled()="
                    + ((imsPhone != null) ? imsPhone.isVolteEnabled() : "N/A")
                    + ", imsPhone.isVowifiEnabled()="
                    + ((imsPhone != null) ? imsPhone.isWifiCallingEnabled() : "N/A")
                    + ", imsPhone.isVideoEnabled()="
                    + ((imsPhone != null) ? imsPhone.isVideoEnabled() : "N/A")
                    + ", imsPhone.getServiceState().getState()="
                    + ((imsPhone != null) ? imsPhone.getServiceState().getState() : "N/A"));
        }

        Phone.checkWfcWifiOnlyModeBeforeDial(mImsPhone, mPhoneId, mContext);

        if (!alwaysDialEccOnCs &&((useImsForCall && !isUt) || (isUt && useImsForUt) || useImsForEmergency)) {
            try {
                if (DBG) logd("Trying IMS PS call");
                return imsPhone.dial(dialString, dialArgs);
            } catch (CallStateException e) {
                if (DBG) logd("IMS PS call exception " + e +
                        "useImsForCall =" + useImsForCall + ", imsPhone =" + imsPhone);
                // Do not throw a CallStateException and instead fall back to Circuit switch
                // for emergency calls and MMI codes.
                /** UNISOC: fix bug1206321. See {@code ImsPhoneCallTracker.checkForDialIssues()}
                 * The following cases should preclude falling back to CS for emergency calls:
                 * 1. System property "ro.telephony.disable-call" is set to true.
                 * 2. already exists a Pending MO.
                 * 3. a ringing call exists.
                 * 4. too many alive calls: a hold call and a active call.
                 */
                if (Phone.CS_FALLBACK.equals(e.getMessage())
                        || isEmergency && (e.getError() == CallStateException.ERROR_INVALID)) {
                    logd("IMS call failed with Exception: " + e.getMessage() + ". Falling back "
                            + "to CS.");
                } else {
                    CallStateException ce = new CallStateException(e.getError(), e.getMessage());
                    ce.setStackTrace(e.getStackTrace());
                    throw ce;
                }
            }
        }

        if (mSST != null && mSST.mSS.getState() == ServiceState.STATE_OUT_OF_SERVICE
                && mSST.mSS.getDataRegState() != ServiceState.STATE_IN_SERVICE && !isEmergency) {
            throw new CallStateException("cannot dial in current state");
        }
        // Check non-emergency voice CS call - shouldn't dial when POWER_OFF
        if (mSST != null && mSST.mSS.getState() == ServiceState.STATE_POWER_OFF /* CS POWER_OFF */
                && !VideoProfile.isVideo(dialArgs.videoState) /* voice call */
                && !isEmergency /* non-emergency call */
                && !(isUt && useImsForUt) /* not UT */) {
            throw new CallStateException(
                    CallStateException.ERROR_POWER_OFF,
                    "cannot dial voice call in airplane mode");
        }
        // Check for service before placing non emergency CS voice call.
        // Allow dial only if either CS is camped on any RAT (or) PS is in LTE service.
        if (mSST != null
                && mSST.mSS.getState() == ServiceState.STATE_OUT_OF_SERVICE /* CS out of service */
                && !(mSST.mSS.getDataRegState() == ServiceState.STATE_IN_SERVICE
                && ServiceState.isLte(mSST.mSS.getRilDataRadioTechnology())) /* PS not in LTE */
                && !VideoProfile.isVideo(dialArgs.videoState) /* voice call */
                && !isEmergency /* non-emergency call */
                && !(mSST.mSS.getDataNetworkType() == TelephonyManager.NETWORK_TYPE_NR)) {/* in 5g UNISOC: add for bug1592667 */
            throw new CallStateException(
                    CallStateException.ERROR_OUT_OF_SERVICE,
                    "cannot dial voice call in out of service");
        }
        if (DBG) logd("Trying (non-IMS) CS call");

        if (isPhoneTypeGsm()) {
            return dialInternal(dialString, new DialArgs.Builder<>()
                    .setIntentExtras(dialArgs.intentExtras)
                    .build());
        } else {
            return dialInternal(dialString, dialArgs);
        }
    }


    @Override
    public void acceptCall(int videoState) throws CallStateException {
        Phone imsPhone = mImsPhone;
        if ( imsPhone != null && imsPhone.getRingingCall().isRinging() ) {
            imsPhone.acceptCall(videoState);
        } else {
            for (Connection c : getRingingCall().getConnections()) {
                if (videoState == 0 && c != null
                        && c.getVideoState() == VideoProfile.STATE_BIDIRECTIONAL) {
                    ((GsmCdmaCallTrackerEx)mCT).fallBack();
                    return;
                }
            }
            mCT.acceptCall();
        }
    }

    private void logd(String s) {
        Rlog.d(LOG_TAG, "[GsmCdmaPhoneEx] " + s);
    }

    public void setCallForwardingOption(int commandInterfaceCFAction,
                                        int commandInterfaceCFReason,int serviceClass, String dialingNumber,
                                        int timerSeconds, String ruleSet, Message onComplete) {
        Rlog.d(LOG_TAG, "setCallForwardingOption: ");
        UtExProxy ut = UtExProxy.getInstance();
        ut.setPhoneId(getPhoneId());
        ut.setCallForwardingOption(commandInterfaceCFAction, commandInterfaceCFReason,
                serviceClass, dialingNumber, timerSeconds, ruleSet, onComplete);
    }

    public void getCallForwardingOption(int commandInterfaceCFReason, int serviceClass,
                                        String ruleSet, Message onComplete) {
        Rlog.d(LOG_TAG, "getCallForwardingOption: ");
        UtExProxy ut = UtExProxy.getInstance();
        ut.setPhoneId(getPhoneId());
        ut.getCallForwardingOption(commandInterfaceCFReason, serviceClass, ruleSet, onComplete);
    }

    public void changeBarringPassword(String facility, String oldPwd, String newPwd,
                                      Message onComplete) {
        Rlog.d(LOG_TAG, "changeBarringPassword: " + facility);
        if (isPhoneTypeGsm()) {
            Phone imsPhone = mImsPhone;
            if ((imsPhone != null)
                    && ((imsPhone.getServiceState().getState() == ServiceState.STATE_IN_SERVICE)
                    || imsPhone.isUtEnabled())) {
                UtExProxy ut = UtExProxy.getInstance();
                ut.setPhoneId(getPhoneId());
                ut.changeBarringPassword(facility, oldPwd, newPwd, onComplete);
                return;
            }
            mCi.changeBarringPassword(facility, oldPwd, newPwd,
                    obtainMessage(EVENT_CHANGE_CALL_BARRING_PASSWORD_DONE, onComplete));
        } else {
            logd("changeBarringPassword: not possible in CDMA");
        }
    }

    public void setFacilityLock(String facility, boolean lockState, String password,
                                int serviceClass, Message onComplete) {
        Rlog.d(LOG_TAG, "setFacilityLock: " + facility);
        if (isPhoneTypeGsm()) {
            Phone imsPhone = mImsPhone;
            if ((imsPhone != null)
                    && ((imsPhone.getServiceState().getState() == ServiceState.STATE_IN_SERVICE)
                    || imsPhone.isUtEnabled())) {
                UtExProxy ut = UtExProxy.getInstance();
                ut.setPhoneId(getPhoneId());
                ut.setFacilityLock(facility, lockState, password, serviceClass, onComplete);
                return;
            }
            mCi.setFacilityLock(facility, lockState, password,
                    serviceClass,
                    obtainMessage(EVENT_SET_CALL_BARRING_DONE, onComplete));
        } else {
            logd("setFacilityLock: not possible in CDMA");
        }
    }

    public void queryFacilityLock(String facility, String password, int serviceClass,
                                  Message onComplete) {
        Rlog.d(LOG_TAG, "queryFacilityLock: " + facility);
        if (isPhoneTypeGsm()) {
            Phone imsPhone = mImsPhone;
            if ((imsPhone != null)
                    && ((imsPhone.getServiceState().getState() == ServiceState.STATE_IN_SERVICE)
                    || imsPhone.isUtEnabled())) {
                UtExProxy ut = UtExProxy.getInstance();
                ut.setPhoneId(getPhoneId());
                ut.queryFacilityLock(facility, password, serviceClass, onComplete);
                return;
            }
            mCi.queryFacilityLock(facility, password, serviceClass,
                    obtainMessage(EVENT_GET_CALL_BARRING_DONE, onComplete));
        } else {
            logd("queryFacilityLock: not possible in CDMA");
        }
    }

    public void queryRootNode(int phoneId, Message onComplete) {
        Rlog.d(LOG_TAG, "queryRootNode: phoneId" + phoneId);
        if (isPhoneTypeGsm()) {
            Phone imsPhone = mImsPhone;
            if ((imsPhone != null)
                    && ((imsPhone.getServiceState().getState() == ServiceState.STATE_IN_SERVICE)
                    || imsPhone.isUtEnabled())) {
                UtExProxy ut = UtExProxy.getInstance();
                ut.setPhoneId(getPhoneId());
                ut.queryRootNode(onComplete);
                return;
            }
            logd("queryRootNode: sendToTarget back");
            onComplete.sendToTarget();
        } else {
            logd("queryRootNode: not possible in CDMA");
            onComplete.sendToTarget();
        }
    }

    //UNISOC: add for bug958569
    public void sendLocationToCP() {

        if (mContext != null && EmcLocationManger.getInstance(mContext) != null) {
            Location location = EmcLocationManger.getInstance(mContext).getSosLocation();
            if (location != null && mRi != null) {
                String longitude = String.format("%.4f", location.getLongitude());
                String latitude = String.format("%.4f", location.getLatitude());

                logd("sendLocationToCP: longitude= " + longitude + " latitude = " + latitude);
                mRi.setLocationInfo(longitude, latitude, null, mPhoneId);
            }
        }
    }

    /* UNISOC: Add for bug 1127883 @{ */
    /*
     * Determines if video calling is enabled for the phone.
     *
     * @return {@code true} if video calling is enabled, {@code false} otherwise.
    * */
    @Override
    public boolean isVideoEnabled() {
        if (SystemProperties.getBoolean("persist.sys.support.vt", false)
                && SystemProperties.getBoolean("persist.sys.csvt", false)){
            if(SubscriptionController.getInstance() != null
                    && SubscriptionController.getInstance().getDefaultDataSubId() == getSubId()){
                logd("isVideoEnabled, CSVT is supported!");
                return true;
            }
        }
        return super.isVideoEnabled();
    }
    /* @} */

    /**
     * Register for notifications when a supplementary service attempt fails.
     * Message.obj will contain an AsyncResult.
     *
     * @param h Handler that receives the notification message.
     * @param what User-defined message code.
     * @param obj User object.
     */
    @Override
    public void registerForSuppServiceFailed(Handler h, int what, Object obj) {
        checkCorrectThread(h);
        mSuppServiceFailedRegistrants.add(h, what, obj);
    }

    /**
     * Verifies the current thread is the same as the thread originally
     * used in the initialization of this instance. Throws RuntimeException
     * if not.
     *
     * @exception RuntimeException if the current thread is not
     * the thread that originally obtained this Phone instance.
     */
    private void checkCorrectThread(Handler h) {
        if (h.getLooper() != Looper.myLooper()) {
            throw new RuntimeException(
                                       "com.android.internal.telephony.Phone must be used from within one thread");
        }
    }

    @Override
    public PhoneConstants.State getState() {
        if (mImsPhone != null) {
            PhoneConstants.State imsState = mImsPhone.getState();
            //TODO:
            if (imsState != PhoneConstants.State.IDLE && mImsPhone.isImsRegistered()) {
                return imsState;
            }
        }

        return mCT.mState;
    }

    @Override
    public String getVoiceMailAlphaTag() {
        String ret = "";

        if (isPhoneTypeGsm()) {
            IccRecords r = mIccRecords.get();

            ret = (r != null) ? r.getVoiceMailAlphaTag() : "";
        }

        //Sprd: Add for bug815992
        if (ret == null || (ret != null && TextUtils.isEmpty(ret.trim())) || ret.length() == 0) {

            return mContext.getText(
                    com.android.internal.R.string.defaultVoiceMailAlphaTag).toString();
        }

        /* SPRD: Add feature for bug764125 Claro operator @{ */
        return ret;
        //TODO: should use CarrierConfig?
        // return ClaroTelephonyHelper.getInstance().getVoiceMailAlphaTag(ret);
        /* @} */
    }
    private void bindNewCsvtService(){
        Intent serviceIntent = new Intent(SERVICE_INTERFACE);

        PackageManager packageManager = mContext.getPackageManager();
        for (ResolveInfo entry : packageManager.queryIntentServicesAsUser(
                serviceIntent,
                PackageManager.GET_META_DATA,
                mContext.getUserId())) {
            ServiceInfo serviceInfo = entry.serviceInfo;
            logd("bindNewCsvtService serviceInfo = " + serviceInfo);

            if (serviceInfo != null
                    && TextUtils.equals(serviceInfo.permission,
                    Manifest.permission.BIND_IMS_SERVICE)) {
                ComponentName component = new ComponentName(serviceInfo.packageName, serviceInfo.name);
                Intent csvtServiceIntent = new Intent(SERVICE_INTERFACE).setComponent(
                component);
                mContext.startService(csvtServiceIntent);
            }
        }
    }
    /* SPRD: fix bug 513309 @{ */
    @Override
    public void notifyNewRingingConnection(Connection c) {
         boolean supportVT = SystemProperties.getBoolean("persist.sys.support.vt", false);
         boolean supportCSVT = SystemProperties.getBoolean("persist.sys.csvt", false);
         if (c.getVideoState() == VideoProfile.STATE_BIDIRECTIONAL && !supportVT && supportCSVT) {
             try {
                 ((GsmCdmaCallTrackerEx)mCT).fallBack();
             } catch (CallStateException ex) {
                  logd("WARN: unexpected error on fallback   "+ ex);
             }
         }else{
             super.notifyNewRingingConnectionP(c);
         }
    }
    /* @} */

    /*UNISOC: add for bug1033847 @{ */
    public void setCarrierConfigParameter(Intent intent) {
        if(intent == null) return;
        int CONFIG_CHANGED_SUB = CarrierConfigManagerEx.CONFIG_SUBINFO;
        int phoneId = intent.getIntExtra(PhoneConstants.PHONE_KEY, -1);
        int configChangedType = intent.getIntExtra(CarrierConfigManagerEx.CARRIER_CONFIG_CHANGED_TYPE, -1);
        int simState = SubscriptionManager.getSimStateForSlotIndex(mPhoneId);
        if (simState == TelephonyManager.SIM_STATE_LOADED) {
            if ((phoneId == mPhoneId) && (configChangedType == CONFIG_CHANGED_SUB)) {
                mIsSubCarrierConfigLoaded = true;
                ImsManager.getInstance(mContext, mPhoneId).initializeEnhanced4gLteModeSetting(); //UNISOC:Modify for bug1126104
            }
        } else {
            mIsSubCarrierConfigLoaded = false;
        }
    }
    public boolean getCarrierConfigParameter() {
        return mIsSubCarrierConfigLoaded;
    }
    /* @} */

    public Connection reDialOnCS(String dialString, DialArgs dialArgs)
            throws CallStateException {
        return dialInternal(dialString, dialArgs);
    }

    private int getCurrentImsFeature() {
        int type = -2;
        try {
            if (mImsServiceEx == null) {
                mImsServiceEx = ImsManagerEx.getIImsServiceEx();
            }
            if (mImsServiceEx != null) {
                type = mImsServiceEx.getCurrentImsFeatureForPhone(getPhoneId());
            } else {
                if (LOCAL_DEBUG) {
                    Rlog.d(LOG_TAG, "mImsServiceEx is null");
                }
            }
        } catch (RemoteException e) {
            e.printStackTrace();
        }
        return type;
    }

    private boolean isFakeEmergencyNumber (String number) {
        int routing = getEmergencyNumberTracker().getEmergencyCallRouting(number);
        if (routing == EmergencyNumber.EMERGENCY_CALL_ROUTING_NORMAL) {
            logd("isFakeEmergencyNumber: routing of " + number + " is " + routing);
            return true;
        }
        return false;
    }

    private boolean dialECallOnIms() {

        logd("dial-state = " + getServiceState());
        logd("dial-imsPhone mSS = " + (mImsPhone != null ? mImsPhone.getServiceState() : mImsPhone));

        boolean eccOnIms = getCarrierValueByKey(CarrierConfigManagerEx.KEY_CARRIER_ECC_VIA_IMS, mContext, getSubId());
        if(eccOnIms){
            logd("DialECallOnIms: ECall on ims");
            return true;
        }
        //UNISOC: add for bug1009987, cellC try eCall from vowifi first, if fail, retry from cs
        if(getCarrierValueByKey(CarrierConfigManagerEx.KEY_CARRIER_ECC_ON_VOWIFI_FIRST,mContext, getSubId())){
            logd("dialECallOnIms: ECall on vowifi first");
            return true;
        }

        boolean useImsForEmergency = false;

        if (isSupportVoWifiECall() && mSST != null) {
            logd("dial-mSST.mSS.getState()" + mSST.mSS.getState());
            switch (mSST.mSS.getState()) {
                //UNISOC: add for bug1050247, dial from vowifi when power off
                case ServiceState.STATE_POWER_OFF:
                    useImsForEmergency = true;
                    logd("dialECallOnIms: ECall on vowifi when power off");
                    break;
                case ServiceState.STATE_OUT_OF_SERVICE: //dial from Vowifi
                    if (!mSST.mSS.isEmergencyOnly()) {
                        useImsForEmergency = true;
                        logd("dialECallOnIms: ECall on vowifi when out of service");
                    }
                    break;
                case ServiceState.STATE_IN_SERVICE: {//EE deregisgterVowifi before dial ecc on cs
                    int netWorkType = mSST.mSS.getDataNetworkType();
                    int netWorkCalss = TelephonyManager.getNetworkClass(netWorkType);
                    logd("dialECallOnIms netWorkType = " + netWorkType + " netWorkCalss = " + netWorkCalss);
                    if (shouldDeregisterVowifi()) {
                        if (netWorkCalss == TelephonyManager.NETWORK_CLASS_2_G
                                || netWorkCalss == TelephonyManager.NETWORK_CLASS_3_G) {
                            try {
                                mDelayDial = true;
                                mDeregisterVowifi = true;
                                useImsForEmergency = false;
                                logd("dialECallOnIms setVowifiRegister action = de-register");
                                mImsServiceEx.setVowifiRegister(0); //deregister Vowifi

                            } catch (RemoteException e) {
                                e.printStackTrace();
                            }
                        }
                    }
                }
                break;
            }
        } else {
            logd("dial mSST is null");
        }
        //UNISOC: add for bug1161791
        if (!useImsForEmergency && deRegisterVowifiWhenCellularPreffered()) {
            setVoWifiUnregister();
        }
        return useImsForEmergency;
    }

    public boolean getCarrierValueByKey(String key, Context context, int subId) {
        logd("getCarrierValueBykey key = " + key + " context = " + context+ " subId = " + subId);
        boolean value = false;
        CarrierConfigManager carrierConfigManager = (CarrierConfigManager) getContext().getSystemService(
                Context.CARRIER_CONFIG_SERVICE);
        if (carrierConfigManager != null) {
            PersistableBundle globalConfig = carrierConfigManager.getConfigForSubId(subId);
            if (globalConfig != null) {
                value = globalConfig.getBoolean(key);
                logd("getCarrierValueBykey Value = " + value);
            }
        }
        return value;
    }

    /*SPRD: add for emergency call bug661375@{*/
    public boolean isSupportVoWifiECall() {
        return getCarrierValueByKey(CarrierConfigManagerEx.KEY_CARRIER_SUPPORTS_VOWIFI_EMERGENCY_CALL, mContext, getSubId());
    }

    public boolean shouldDeregisterVowifi(){
        return getCarrierValueByKey(CarrierConfigManagerEx.KEY_CARRIER_DEREG_VOWIFI_BEFORE_ECC, mContext, getSubId());
    /*@}*/
    }

    /*SPRD: add for bug675852 @{*/
    @Override
    public void notifyPhoneStateChanged() {
        mNotifier.notifyPhoneState(this);

        if (mDeregisterVowifi && mCT.getState() == PhoneConstants.State.IDLE && isSupportVoWifiECall()) {
            try {
                logd("notifyPhoneStateChanged setVowifiRegister action = register");
                if(mImsServiceEx == null) {
                    mImsServiceEx = ImsManagerEx.getIImsServiceEx();
                }
                mImsServiceEx.setVowifiRegister(1/*register Vowifi*/);
                mDeregisterVowifi = false;
            } catch (RemoteException e) {
                e.printStackTrace();
            }
        }
    }/*@}*/

    /* SPRD: Support SimLock @{ */
    public void supplySimLock(boolean isLock, String password, Message onComplete) {
        int serviceClassX = 0;
        int type = (int)onComplete.obj;
        String facility = getSimLockType(type);
        Rlog.d(LOG_TAG, "supplySimLock facility : " + facility + " type :" + type);
        mCi.setFacilityLock(facility, isLock, password, serviceClassX,
                obtainMessage(EVENT_SET_FACILITY_LOCK_DONE, 0, type, onComplete));
    }

    private String getSimLockType(int type){
        String facility = "";
        switch(type) {
        case IccCardStatusEx.UNLOCK_SIM:
            facility = IccCardStatusEx.CB_FACILITY_BA_PS;
            break;
        case IccCardStatusEx.UNLOCK_NETWORK:
            facility = IccCardStatusEx.CB_FACILITY_BA_PN;
            break;
        case IccCardStatusEx.UNLOCK_NETWORK_SUBSET:
            facility = IccCardStatusEx.CB_FACILITY_BA_PU;
            break;
        case IccCardStatusEx.UNLOCK_SERVICE_PORIVDER:
            facility = IccCardStatusEx.CB_FACILITY_BA_PP;
            break;
        case IccCardStatusEx.UNLOCK_CORPORATE:
            facility = IccCardStatusEx.CB_FACILITY_BA_PC;
            break;
        case IccCardStatusEx.UNLOCK_NETWORK_PUK:
            facility = IccCardStatusEx.CB_FACILITY_BA_PN_PUK;
            break;
        case IccCardStatusEx.UNLOCK_NETWORK_SUBSET_PUK:
            facility = IccCardStatusEx.CB_FACILITY_BA_PU_PUK;
            break;
        case IccCardStatusEx.UNLOCK_SERVICE_PORIVDER_PUK:
            facility = IccCardStatusEx.CB_FACILITY_BA_PP_PUK;
            break;
        case IccCardStatusEx.UNLOCK_CORPORATE_PUK:
            facility = IccCardStatusEx.CB_FACILITY_BA_PC_PUK;
            break;
        case IccCardStatusEx.UNLOCK_SIM_PUK:
            facility = IccCardStatusEx.CB_FACILITY_BA_PS_PUK;
            break;
         default :
            facility = IccCardStatusEx.CB_FACILITY_BA_PN;
            break;
        }
        return facility;
   }

    private void onSetFacilityLockDone(AsyncResult ar, int type) {
        int attemptsRemaining = -1;

        if (ar.exception != null) {
            attemptsRemaining = parseFacilityErrorResult(ar);
            Rlog.e(LOG_TAG, "Error set facility lock with exception "
                    + ar.exception);
        }
        Message response = (Message) ar.userObj;
        AsyncResult.forMessage(response).exception = ar.exception;
        response.arg1 = attemptsRemaining;
        response.arg2 = type;
        response.sendToTarget();
    }

    private int parseFacilityErrorResult(AsyncResult ar) {
        int[] result = (int[]) ar.result;
        if (result == null) {
            return -1;
        } else {
            int length = result.length;
            int attemptsRemaining = -1;
            if (length > 0) {
                attemptsRemaining = result[0];
            }
            Rlog.d(LOG_TAG, "parsePinPukErrorResult: attemptsRemaining="
                    + attemptsRemaining);
            return attemptsRemaining;
        }
    }
   /* @} */

    /*UNISOC: add for bug1108786 @{*/
    public void setVoWifiUnregister(){
        try {
            if (mImsServiceEx == null) {
                mImsServiceEx = ImsManagerEx.getIImsServiceEx();
            }
            if (mImsServiceEx != null && ImsManagerEx.isVoWiFiRegisteredForPhone(getPhoneId())) {
                mImsServiceEx.setVoWifiUnavailable(1, false);
                mDeregisterVowifi = false;
            }
        } catch (RemoteException e) {
          logd("WARN: Fail setVoWifiUnavailable e: " + e);
        }
    }
    /*@}*/

    /**
     * Return Wifi-calling mode.
     */
    private int getWfcMode() {
        boolean isRoaming = false;
        TelephonyManager tm = TelephonyManager.from(mContext);
        if (tm != null) {
            isRoaming = tm.isNetworkRoaming(getSubId());
        }
        return ImsManager.getInstance(mContext, getPhoneId()).getWfcMode(isRoaming);
    }

    private boolean deRegisterVowifiWhenCellularPreffered() {
        return getCarrierValueByKey(CarrierConfigManagerEx.KEY_CARRIER_DEREG_VOWIFI_WHEN_CELLULAR_PREFERRED, mContext, getSubId())
                && getWfcMode() == ImsConfig.WfcModeFeatureValueConstants.CELLULAR_PREFERRED;
    }
}
