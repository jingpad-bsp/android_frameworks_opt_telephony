package com.android.internal.telephony;

import com.android.ims.internal.IImsServiceEx;
import com.android.ims.internal.ImsManagerEx;
import com.android.internal.telephony.CallStateException;
import com.android.internal.telephony.Connection;
import com.android.internal.telephony.GsmCdmaCall;
import com.android.internal.telephony.GsmCdmaConnection;
import com.android.internal.telephony.GsmCdmaPhone;
import com.android.internal.telephony.UUSInfo;
import com.android.internal.telephony.imsphone.ImsPhone;
import com.android.internal.telephony.metrics.TelephonyMetrics;
import com.android.sprd.telephony.RadioInteractor;
import com.android.ims.internal.IImsPdnStateListener;
import com.android.sprd.telephony.RadioInteractorCallbackListener;
import com.android.sprd.telephony.RadioInteractorFactory;

import android.content.Context;
import android.os.AsyncResult;
import android.os.Bundle;
import android.os.Looper;
import android.os.Message;
import android.os.RegistrantList;
import android.os.RemoteException;
import android.os.SystemProperties;
import android.telecom.TelecomManager;
import android.telecom.VideoProfile;
import android.telephony.DisconnectCause;
import android.telephony.PhoneNumberUtils;
import android.telephony.ServiceState;
import android.telephony.Rlog;

import java.util.List;
import android.os.PersistableBundle;
import android.os.RemoteException;
import android.telephony.CarrierConfigManager;
import android.telephony.CarrierConfigManagerEx;

public final class GsmCdmaCallTrackerEx extends GsmCdmaCallTracker {

    private static final String LOG_TAG = "GsmCdmaCallTrackerEx";

    private static final boolean DBG_POLL = false;
    /*SPRD: add for bug675852 vowifi emergency call feature @{*/
    public static final int EVENT_DIAL_DELAY = 10001;
    public static final int EVENT_PDN_STATE_CHANGE = 10002;

    private boolean mDelayDial = false;
    private int mPendingClirMode;
    private UUSInfo mPendingUusInfo;
    private String mPendingOrigNumber;
    private IImsServiceEx mImsServiceEx;
    private boolean mHasRegistedPdnListener = false;
    /*@}*/

    private Bundle mLastDialExtras = null;//UNISOC: add for bug969605
    private GsmCdmaPhone mPhone;
    private int mMoVideoState = 0;
    RadioInteractor mRi;
    private TelephonyMetrics mMetrics = TelephonyMetrics.getInstance();
    //UNISOC: Add for bug Bug 1113109
    private RadioInteractorCallbackListener mCallbackListener;

    public GsmCdmaCallTrackerEx (GsmCdmaPhone phone) {
        super(phone);
        this.mPhone = phone;
        mRi = new RadioInteractor(mPhone.getContext());
        /* UNISOC: add for bug 1113109 @{ */
        RadioInteractorFactory.init(mPhone.getContext());
        registerCnapEvent();
        /*@}*/
    }

  //GSM
    /**
     * clirMode is one of the CLIR_ constants
     */
    @Override
    public synchronized Connection dialGsm(String dialString, int clirMode, UUSInfo uusInfo,
                                        Bundle intentExtras)
            throws CallStateException {
        // note that this triggers call state changed notif
        clearDisconnected();
        // Check for issues which would preclude dialing and throw a CallStateException.
        boolean isEmergencyCall = PhoneNumberUtils.isLocalEmergencyNumber(mPhone.getContext(),dialString);
        checkForDialIssues(isEmergencyCall);

        String origNumber = dialString;
        dialString = convertNumberIfNecessary(mPhone, dialString);

        // The new call must be assigned to the foreground call.
        // That call must be idle, so place anything that's
        // there on hold
        if (mForegroundCall.getState() == GsmCdmaCall.State.ACTIVE) {
            // this will probably be done by the radio anyway
            // but the dial might fail before this happens
            // and we need to make sure the foreground call is clear
            // for the newly dialed connection
            switchWaitingOrHoldingAndActive();
            // This is a hack to delay DIAL so that it is sent out to RIL only after
            // EVENT_SWITCH_RESULT is received. We've seen failures when adding a new call to
            // multi-way conference calls due to DIAL being sent out before SWITCH is processed
            try {
                Thread.sleep(500);
            } catch (InterruptedException e) {
                // do nothing
            }

            // Fake local state so that
            // a) foregroundCall is empty for the newly dialed connection
            // b) hasNonHangupStateChanged remains false in the
            // next poll, so that we don't clear a failed dialing call
            fakeHoldForegroundBeforeDial();
        }

        if (mForegroundCall.getState() != GsmCdmaCall.State.IDLE) {
            //we should have failed in !canDial() above before we get here
            throw new CallStateException("cannot dial in current state");
        }

        boolean isIntentEmergencyCall = false;
        if(intentExtras != null){
            mLastDialExtras = intentExtras;
            mMoVideoState = intentExtras.getShort(GsmCdmaPhoneEx.VIDEO_STATE,
                    (short) VideoProfile.STATE_AUDIO_ONLY);
            isIntentEmergencyCall = intentExtras.getBoolean(TelecomManager.EXTRA_IS_USER_INTENT_EMERGENCY_CALL);
            Rlog.d(LOG_TAG, "dialGsm - emergency dialer: " + isIntentEmergencyCall);
        }
        Rlog.d(LOG_TAG, "====callTracker-dial: videoState = " + mMoVideoState);
        setPendingMO(new GsmCdmaConnectionEx(mPhone, checkForTestEmergencyNumber(dialString),
                this, mForegroundCall, isEmergencyCall, VideoProfile.isBidirectional(mMoVideoState)));
        getPendingMO().setHasKnownUserIntentEmergency(isIntentEmergencyCall);
        setHangupPendingMO(false);
        mMetrics.writeRilDial(mPhone.getPhoneId(), getPendingMO(), clirMode, uusInfo);


        if ( getPendingMO().getAddress() == null || getPendingMO().getAddress().length() == 0
                || getPendingMO().getAddress().indexOf(PhoneNumberUtils.WILD) >= 0) {
            // Phone number is invalid
        	getPendingMO().mCause = DisconnectCause.INVALID_NUMBER;

            // handlePollCalls() will notice this call not present
            // and will mark it as dropped.
            pollCallsWhenSafe();
        } else {
            /*SPRD: add for bug675852 vowifi emergency call feature*/
            if(intentExtras != null){
                mDelayDial = intentExtras.getBoolean("delay_dial", false);
            }
            Rlog.d(LOG_TAG, " ====callTrack-dial Delay = " + mDelayDial);
            if (mDelayDial) {
                tryRegisterPdnListener();
                mPendingClirMode = clirMode;
                mPendingUusInfo = uusInfo;
                mPendingOrigNumber = origNumber;
                sendEmptyMessageDelayed(EVENT_DIAL_DELAY, 3000);//Delay 3000ms dial
                return getPendingMO();
            } else {
                // Always unmute when initiating a new call
                if (shouldMute()) {
                    setMute(false);
                }
                boolean supportVT = SystemProperties.getBoolean("persist.sys.csvt", false);
                Rlog.d(LOG_TAG, "====callTrack-dial supportVT:" + supportVT);

                if (mMoVideoState != VideoProfile.STATE_AUDIO_ONLY && supportVT) {
                    Rlog.d(LOG_TAG, "====callTrack-dial video");
                    mRi.dialVP(getPendingMO().getAddress(), null, 0, obtainCompleteMessage(), mPhone.getPhoneId());
                } else {
                    Rlog.d(LOG_TAG, "====callTrack-dial audio");
                    mCi.dial(getPendingMO().getAddress(), getPendingMO().isEmergencyCall(),
                             getPendingMO().getEmergencyNumberInfo(), getPendingMO().hasKnownUserIntentEmergency(),
                             clirMode, uusInfo, obtainCompleteMessage());
                }
            }
        }

        if (mNumberConverted) {
            getPendingMO().setConverted(origNumber);
            mNumberConverted = false;
        }

        updatePhoneStateEx();
        mPhone.notifyPreciseCallStateChanged();

        return getPendingMO();
    }

    private void fakeHoldForegroundBeforeDial() {
        List<Connection> connCopy;

        // We need to make a copy here, since fakeHoldBeforeDial()
        // modifies the lists, and we don't want to reverse the order
        connCopy = (List<Connection>) mForegroundCall.mConnections.clone();

        for (int i = 0, s = connCopy.size() ; i < s ; i++) {
            GsmCdmaConnection conn = (GsmCdmaConnection)connCopy.get(i);

            conn.fakeHoldBeforeDial();
        }
    }

    private Message obtainCompleteMessage() {
        return obtainCompleteMessage(EVENT_OPERATION_COMPLETE);
    }

    private Message obtainCompleteMessage(int what) {
        mPendingOperations++;
        mLastRelevantPoll = null;
        mNeedsPoll = true;

        if (DBG_POLL) log("obtainCompleteMessage: pendingOperations=" +
                mPendingOperations + ", needsPoll=" + mNeedsPoll);

        return obtainMessage(what);
    }

    private void operationComplete() {
        mPendingOperations--;

        if (DBG_POLL) log("operationComplete: pendingOperations=" +
                mPendingOperations + ", needsPoll=" + mNeedsPoll);

        if (mPendingOperations == 0 && mNeedsPoll) {
            mLastRelevantPoll = obtainMessage(EVENT_POLL_CALLS_RESULT);
            mCi.getCurrentCalls(mLastRelevantPoll);
        } else if (mPendingOperations < 0) {
            // this should never happen
            Rlog.e(LOG_TAG,"GsmCdmaCallTracker.pendingOperations < 0");
            mPendingOperations = 0;
        }
    }

    void fallBack() throws CallStateException {
        log("fall back");
        if (mRingingCall.getState().isRinging()) {
            try {
                mRi.fallBackVP(null, mPhone.getPhoneId());
            } catch (IllegalStateException ex) {
                log("fallBack failed");
            }
        } else {
            throw new CallStateException("phone not ringing");
        }
    }

    private boolean isPhoneTypeGsm() {
        return mPhone.getPhoneType() == PhoneConstants.PHONE_TYPE_GSM;
    }

    /*SPRD: add for bug675852 vowifi emergency call feature @{*/
    @Override
    public void handleMessage(Message msg) {

        Rlog.d(LOG_TAG,"handle message msg.what = "+msg.what);
        switch (msg.what) {
            case EVENT_DIAL_DELAY:
                DelayDial();
                break;
            case EVENT_PDN_STATE_CHANGE:
                onPdnStateChange(msg.arg1);
                break;
            /*SPRD: add for bug678753 & 969605 @{*/
            case EVENT_GET_LAST_CALL_FAIL_CAUSE:
                if(processFailedCall(msg)){
                    //reDial Emergency call on imsphone if register EE net work
                    return;
                }
                //gsmcdmaCallTracker will continue to handle message} /*@}*/
            default:
                super.handleMessage(msg);
        }
    }

    private void onPdnStateChange(int state) {
        Rlog.d(LOG_TAG, "onPdnStateChange state= " + state + " mDelayDial = " + mDelayDial);
        if ((state == ImsManagerEx.IMS_PDN_ACTIVE_FAILED || state == ImsManagerEx.IMS_PDN_READY) && mDelayDial) {
            removeMessages(EVENT_DIAL_DELAY);
            DelayDial();
        }
    }

    public synchronized void DelayDial() {

        if (hasMessages(EVENT_DIAL_DELAY)) {
            removeMessages(EVENT_DIAL_DELAY);
        }
        mDelayDial = false;
        unregisterPdnListener();
        if (mPendingOrigNumber == null || getPendingMO() == null) {
            Rlog.d(LOG_TAG, "====callTrack-DelayDial mPendingOrigNumber = " + mPendingOrigNumber + " mPendingUusInfo = " + mPendingUusInfo);
            return;
        }
        // Always unmute when initiating a new call
        if (shouldMute()) {
            setMute(false);
        }
        boolean supportVT = SystemProperties.getBoolean("persist.sys.csvt", false);
        Rlog.d(LOG_TAG, "====callTrack-DelayDial supportVT:" + supportVT);

        if (mMoVideoState != VideoProfile.STATE_AUDIO_ONLY && supportVT) {
            Rlog.d(LOG_TAG, "====callTrack-DelayDial video");
            mRi.dialVP(getPendingMO().getAddress(), null, 0, obtainCompleteMessage(), mPhone.getPhoneId());
        } else {
            Rlog.d(LOG_TAG, "====callTrack-DelayDial audio");
            mCi.dial(getPendingMO().getAddress(), getPendingMO().isEmergencyCall(),
                    getPendingMO().getEmergencyNumberInfo(), getPendingMO().hasKnownUserIntentEmergency(),
                    mPendingClirMode, mPendingUusInfo, obtainCompleteMessage());
        }

        if (mNumberConverted) {
            getPendingMO().setConverted(mPendingOrigNumber);
            mNumberConverted = false;
        }

        mPendingClirMode = 0;
        mPendingOrigNumber = null;
        mPendingUusInfo = null;

        updatePhoneStateEx();
        mPhone.notifyPreciseCallStateChanged();
    }/*@}*/

    private void tryRegisterPdnListener(){
        if(mHasRegistedPdnListener){
            Rlog.d(LOG_TAG, "addImsPdnStateListener-> already registered");
            return;
        }
        try {
            if(mImsServiceEx == null) {
                mImsServiceEx = ImsManagerEx.getIImsServiceEx();
            }
            if(mImsServiceEx != null) {
                mImsServiceEx.addImsPdnStateListener(mPhone.getPhoneId(), mImsPdnStateListener);
                Rlog.d(LOG_TAG, "addImsPdnStateListener");
                mHasRegistedPdnListener = true;
            }
        } catch (RemoteException e) {
            e.printStackTrace();
        }
    }

    private void unregisterPdnListener(){
        if(!mHasRegistedPdnListener){
            Rlog.d(LOG_TAG, "addImsPdnStateListener -> already unregistered");
            return;
        }
        try {
            if(mImsServiceEx == null) {
                mImsServiceEx = ImsManagerEx.getIImsServiceEx();
            }
            if(mImsServiceEx != null) {
                mImsServiceEx.removeImsPdnStateListener(mPhone.getPhoneId(), mImsPdnStateListener);
                Rlog.d(LOG_TAG, "removeImsPdnStateListener");
            }
        } catch (RemoteException e) {
            e.printStackTrace();
        }
    }

    private final IImsPdnStateListener.Stub mImsPdnStateListener = new IImsPdnStateListener.Stub(){

        /**
         * Notifies the status of IMS PDN.
         * param isRegister: IMS PDN status
         * IMS_PDN_ACTIVE_FAILED = 0;
         * IMS_PDN_READY = 1;
         * IMS_PDN_START = 2;
         */
        @Override
        public void imsPdnStateChange(int status){
            obtainMessage(EVENT_PDN_STATE_CHANGE, status, -1).sendToTarget();
        }
    };

    /*SPRD: add for bug678753 @{*/
    public void reDialEccOnIms(String address){
        Rlog.d(LOG_TAG, "reDialEccOnIms address = "+address);
        try {
            if(mPhone != null && mPhone.getImsPhone() != null) {
                Connection conn = mPhone.getImsPhone().dial(address, new ImsPhone.ImsDialArgs.Builder().build());
                mPhone.notifyUnknownConnection(conn);
                Rlog.d(LOG_TAG, "reDialEccOnIms conn:" + conn);
            }else{
                Rlog.d(LOG_TAG, "reDialEccOnIms mPhone or imsPhone is null");
            }
        } catch (CallStateException e) {
            Rlog.d(LOG_TAG, "reDialEccOnIms CallStateException: "+e);
        }
    }

    public Boolean processFailedCall(Message msg) {

        AsyncResult ar;
        ar = (AsyncResult) msg.obj;
        int causeCode;
        String vendorCause = null;

        if (ar.exception != null) {
            // An exception occurred...just treat the disconnect
            // cause as "normal"
            causeCode = CallFailCause.NORMAL_CLEARING;
            Rlog.i(LOG_TAG,
                    "Exception during getLastCallFailCause, assuming normal disconnect");
        } else {
            LastCallFailCause failCause = (LastCallFailCause) ar.result;
            causeCode = failCause.causeCode;
            vendorCause = failCause.vendorCause;
        }

        boolean reDialEccOnWifi = supportReDialEccOnVowiifi();
        if (causeCode != CallFailCause.NORMAL_CLEARING && reDialEccOnWifi) {
            for (int i = getDroppedDuringPoll().size() - 1; i >= 0; i--) {
                GsmCdmaConnection conn = getDroppedDuringPoll().get(i);
                Rlog.d(LOG_TAG, "processFailedCall handle Ecc conn " + conn);
                if (conn != null && conn.getAddress() != null) {
                    if (PhoneNumberUtils.isEmergencyNumber(conn.getAddress())) {
                        if (getPendingMO() != null) {
                            if (getPendingMO().getCall() != null) {
                                getPendingMO().getCall().detach(getPendingMO());
                            }
                            setPendingMO(null);
                        }
                        reDialEccOnIms(conn.getAddress());
                        // UNISOC: detach gsm connection when redial ecc on ims.
                        if (conn.getCall() != null) {
                            conn.getCall().detach(conn);
                        }
                        removeConnectionFromDroppedDuringPoll(i);
                        break;
                    }
                }
            }
        } else if(causeCode == CallFailCause.REDIAL_WHEN_IMS_REGISTERING){//UNISOC: add for bug969605
            for (int i = getDroppedDuringPoll().size() - 1; i >= 0; i--) {
                GsmCdmaConnection conn = getDroppedDuringPoll().get(i);
                Rlog.d(LOG_TAG, "processFailedCall handle conn " + conn);
                if (conn != null && conn.getAddress() != null) {
                     if(conn.getState() == GsmCdmaCall.State.DIALING){
                        if (getPendingMO() != null && (getPendingMO().getCall() != null)) {
                           getPendingMO().getCall().detach(getPendingMO());
                           setPendingMO(null);
                        }
                        if((getDroppedDuringPoll().get(i)!= null) && (getDroppedDuringPoll().get(i).getCall() != null)){
                            getDroppedDuringPoll().get(i).getCall().detach(getDroppedDuringPoll().get(i));
                        }
                        redialWhenImsRegistering(conn.getAddress());
                        removeConnectionFromDroppedDuringPoll(i);
                        break;
                    }
                }
            }
        }
        return false;
    }

    public boolean supportReDialEccOnVowiifi() {

        Rlog.d(LOG_TAG, "supportReDialEccOnVowiifi");
        boolean isSupport = false;
        CarrierConfigManager carrierConfigManager = (CarrierConfigManager) mPhone.getContext().getSystemService(
                Context.CARRIER_CONFIG_SERVICE);
        if (carrierConfigManager != null) {
            PersistableBundle globalConfig = carrierConfigManager.getConfigForSubId(mPhone.getSubId());
            if (globalConfig != null) {
                isSupport = globalConfig.getBoolean(CarrierConfigManagerEx.KEY_CARRIER_SUPPORTS_VOWIFI_EMERGENCY_CALL);
                isSupport = isSupport && globalConfig.getBoolean(CarrierConfigManagerEx.KEY_CARRIER_RETRY_ECC_VOWIFI);
                Rlog.d(LOG_TAG, "isSupportVoWifiEccCall isSupport = " + isSupport);
            }
        }

        GsmCdmaPhoneEx phone = (GsmCdmaPhoneEx) mPhone;
        ImsPhone imsPhone = (ImsPhone) phone.getImsPhone();
        if (isSupport && imsPhone != null && imsPhone.isWifiCallingEnabled()) {
            Rlog.d(LOG_TAG, "supportReDialEccOnVowiifi true");
            return true;
        }
        return false;
    }
    /*@}*/

    /*UNISOC: add for bug969605 @{*/
    public synchronized void redialWhenImsRegistering(String address){
        try {
            if(mPhone != null) {
                Connection conn = mPhone.dial(address, new PhoneInternalInterface.DialArgs.Builder()
                .setVideoState(mMoVideoState)
                .setIntentExtras(mLastDialExtras)
                .build());
                mPhone.notifyUnknownConnection(conn);
                Rlog.d(LOG_TAG, "redialWhenImsRegistering conn:" + conn);
            }else{
                Rlog.d(LOG_TAG, "redialWhenImsRegistering mPhone null");
            }
        } catch (CallStateException e) {
            Rlog.d(LOG_TAG, "reDialMakeFailedCall CallStateException: "+e);
        }
    }
    /*@}*/

   /*UNISOC: add for bug1108786 @{*/
   public void setVoWifiUnregister(){
       mPhone.setVoWifiUnregister();
   }
   /*@}*/

   /*UNISOC: add for bug1157411 @{*/
   public boolean shouldMute(){
       if (mForegroundCall.getState() == GsmCdmaCall.State.ACTIVE || mBackgroundCall.getState() == GsmCdmaCall.State.HOLDING ){
           return false;
       }
       return true;
   }
   /*@}*/

    /* UNISOC: add for bug 1113109 @{ */
    private void registerCnapEvent() {
        mCallbackListener = new RadioInteractorCallbackListener(mPhone.getPhoneId(), Looper.getMainLooper()) {
            @Override
            public void onCnapEvent(Object object) {
                if (object != null) {
                    mCnapCallingName = (String) ((AsyncResult) object).result;
                    Rlog.i(LOG_TAG, "onCnapEvent: mCnapCallingName = " + mCnapCallingName);
                    if (!mCnapCallingName.equals("")) {
                        notifyCnap(mCnapCallingName);
                    }
                }
            }
        };

        Rlog.i(LOG_TAG, "registerCnapEvent: (mPhone.getPhoneId() = " + mPhone.getPhoneId());
        if (mRi != null) {
            mRi.listen(mCallbackListener,
                    RadioInteractorCallbackListener.LISTEN_CNAP_EVENT,
                    false);
        }
    }

    private void notifyCnap(String cnapCallingName) {
        int count = mRingingCall.mConnections.size();
        for (int i = 0; i < count; i++) {
            GsmCdmaConnection cn = (GsmCdmaConnection)mRingingCall.mConnections.get(i);
            Rlog.i(LOG_TAG, "notifyCnap: cn = " + cn);
            if (!cn.mDisconnected) {
                Rlog.i(LOG_TAG, "notifyCnap: cnapCallingName = " + cnapCallingName);
                cn.updateCnap(cnapCallingName);
                mPhone.notifyPreciseCallStateChanged();
                mCnapCallingName = "";
                break;
            }
        }
    }
    /* @} */
}
