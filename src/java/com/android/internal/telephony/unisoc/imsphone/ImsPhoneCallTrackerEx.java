package com.android.internal.telephony.imsphone;

import java.util.ArrayList;
import java.util.concurrent.ConcurrentHashMap;

import android.app.ActivityManager;
import android.app.AlertDialog;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.DialogInterface.OnClickListener;
import android.content.SharedPreferences;
import android.content.pm.PackageManager.NameNotFoundException;
import android.media.AudioManager;
import android.net.ConnectivityManager;
import android.os.AsyncResult;
import android.os.Bundle;
import android.os.Message;
import android.os.RemoteException;
import android.os.SystemProperties;
import android.os.UserHandle;
import android.os.PersistableBundle;
import android.telephony.DisconnectCause;
import android.telephony.PhoneNumberUtils;
import android.telephony.RadioAccessFamily;
import android.telephony.Rlog;
import android.telephony.ServiceState;
import android.telephony.SubscriptionManager;
import android.view.WindowManager;
import android.widget.Toast;
import android.telecom.TelecomManager;
import android.telephony.TelephonyManager;
import com.android.ims.ImsCall;
import com.android.ims.ImsConfig;
import com.android.ims.ImsException;
import com.android.ims.ImsManager;

import android.telephony.emergency.EmergencyNumber;
import android.telephony.ims.ImsCallProfile;
import android.telephony.ims.ImsReasonInfo;
import android.telephony.ims.ImsStreamMediaProfile;
import com.android.ims.internal.IImsVideoCallProvider;
import com.android.ims.internal.ImsVideoCallProviderWrapper;
import com.android.internal.telephony.Call;
import com.android.internal.telephony.CallStateException;
import com.android.internal.telephony.Connection;
import com.android.internal.telephony.GsmCdmaCall;
import com.android.internal.telephony.GsmCdmaCallTracker;
import com.android.internal.telephony.GsmCdmaConnection;
import com.android.internal.telephony.GsmCdmaPhoneEx;
import com.android.internal.telephony.Phone;
import com.android.internal.telephony.PhoneFactory;
import com.android.internal.telephony.PhoneInternalInterface;
import com.android.internal.telephony.TelephonyProperties;
import android.telephony.ims.ImsCallSession;

import android.os.IBinder;
import android.os.RemoteException;
import android.telephony.CarrierConfigManager;
import android.telephony.CarrierConfigManagerEx;
import android.os.ServiceManager;
import com.android.internal.R;
import android.provider.Settings;
import android.telecom.VideoProfile;
import android.util.Pair;
import android.os.PersistableBundle;
import com.android.internal.telephony.CommandException;
import com.android.internal.telephony.CommandsInterface;
import com.android.internal.telephony.PhoneConstants;
import com.android.internal.telephony.gsm.SuppServiceNotification;
import com.android.internal.telephony.imsphone.ImsPhoneCall;
import com.android.internal.telephony.imsphone.ImsPhoneConnection;
import com.android.internal.telephony.GsmCdmaPhone;

import com.android.internal.telephony.uicc.UiccController;
import com.android.internal.telephony.uicc.UiccCardApplication;
import com.android.internal.telephony.uicc.IccCardApplicationStatus.AppType;
import com.android.sprd.telephony.RadioInteractor;

/**
 * {@hide}
 */
public final class ImsPhoneCallTrackerEx extends ImsPhoneCallTracker {
    static final String LOG_TAG = "ImsPhoneCallTrackerEx";
    static final Boolean DBG = true;
    //***** Constants
    private static final int EVENT_HANGUP_PENDINGMO = 18;
    private static final int EVENT_RESUME_BACKGROUND = 19;
    private static final int EVENT_DIAL_PENDINGMO = 20;

    private static final int TIMEOUT_HANGUP_PENDINGMO = 500;
    private static final int TIMEOUT_REDIAL_ECALL_ON_CS = 10000;

    private Object mSyncHold = new Object();
    private int mServiceId = 0;
    private ImsManager mImsManager;
    public static final String VIDEO_STATE = "video_state";

    private static final int EVENT_EXTRA_BASE          = 10000;
    private static final int EVENT_HANGUP_PENGDINGCALL = EVENT_EXTRA_BASE + 22;
    private static final int EVENT_REDIAL_ON_HANDOVER  = EVENT_EXTRA_BASE + 23;//SPRD:add for bug663110

    private static final int EVENT_SERVICE_STATE_CHANGED  = EVENT_EXTRA_BASE + 26;
    private static final int EVENT_RETRY_WIFI_E911_CALL = EVENT_EXTRA_BASE + 27;

    private static final int EVENT_RETRY_TIMEOUT        = EVENT_EXTRA_BASE + 28;
    private static final int EVENT_WIFI_E911_CALL_TIMEOUT        = EVENT_EXTRA_BASE + 29;


    public static final int CODE_LOCAL_CALL_IMS_HANDOVER_RETRY = 151;

    /*SPRD: add for bug603556 @{*/
    public ImsPhoneCall mTempCall = new ImsPhoneCall(this, ImsPhoneCall.CONTEXT_UNKNOWN);
    private Object mSyncResume = new Object();
    private boolean  mIsResuming = false;
    private ImsCall  mResumingImsCall = null;
    private boolean  mIsDialing = false;
    private String mPendingDialString;
    /* @} */
    private ImsPhoneConnection mMergePeerConn = null;

    //***** Constructors
    public ImsPhoneCallTrackerEx(ImsPhone phone) {
        super(phone);
    }

    @Override
    public Connection
    dial(String dialString, ImsPhone.ImsDialArgs dialArgs) throws CallStateException {
        int clirMode = dialArgs.clirMode;
        int videoState = dialArgs.videoState;
        Bundle intentExtras = dialArgs.intentExtras;
        if(intentExtras == null){
            ImsPhone.ImsDialArgs.Builder builder = new ImsPhone.ImsDialArgs.Builder();
            intentExtras = new Bundle();
            builder.setUusInfo(dialArgs.uusInfo);
            builder.setVideoState(dialArgs.videoState);
            builder.setIntentExtras(dialArgs.intentExtras);
            dialArgs = builder.build();
        }

        if(shouldPostPonedAction()){
            Message m = new Message();
            m.what = SrvccPendingAction.EVENT_DIAL;
            m.arg1 = videoState;
            m.obj = dialString;
            m.setData(intentExtras);
            mPendingActionsMap.put(SrvccPendingAction.EVENT_DIAL, m);
            log("shouldPostPonedAction->dial.");
            return null;
        }
        boolean isConferenceDial =false;
        if(intentExtras != null){
            isConferenceDial= intentExtras.getBoolean("android.intent.extra.IMS_CONFERENCE_REQUEST",false);
        }
        log("dial-> isConferenceDial: " + isConferenceDial + " intentExtras:"+intentExtras);
        if(isConferenceDial){
            return dialConferenceCall(dialString, intentExtras, clirMode);
        }
         /*SPRD: add for bug603556 @{*/
        boolean isEmergencyNumber = PhoneNumberUtils.isEmergencyNumber(dialString);
        synchronized (mSyncResume) {
            if(mIsResuming){
                mIsDialing = true;
                mPendingDialString = dialString;
                setPendingVideoState(videoState);
                setPendingIntentExtras(intentExtras);

                ImsPhoneConnection pendingMO = new ImsPhoneConnectionEx(mPhone, checkForTestEmergencyNumber(dialString), this,
                        mTempCall, isEmergencyNumber);
                pendingMO.setVideoState(videoState);
                setPendingMO(pendingMO);
                addConnectionEx(pendingMO);
                log("dial isResuming pendingMO=" + pendingMO);
                return pendingMO;
            }
        }
        /* @} */
        String callees = PhoneNumberUtils.normalizeNumber(dialString);
        return super.dial(dialString, dialArgs);
    }

    private void
    internalClearDisconnected() {
        mRingingCall.clearDisconnected();
        mForegroundCall.clearDisconnected();
        mBackgroundCall.clearDisconnected();
        mHandoverCall.clearDisconnected();
    }

    private void setVideoCallProvider(ImsPhoneConnection conn, ImsCall imsCall)
            throws RemoteException {
        IImsVideoCallProvider imsVideoCallProvider =
                imsCall.getCallSession().getVideoCallProvider();
        if (imsVideoCallProvider != null) {
            // TODO: Remove this when we can better formalize the format of session modify requests.
            boolean useVideoPauseWorkaround = mPhone.getContext().getResources().
                    getBoolean(com.android.internal.R.bool.config_useVideoPauseWorkaround);

            ImsVideoCallProviderWrapper imsVideoCallProviderWrapper =
                new ImsVideoCallProviderWrapper(imsVideoCallProvider);
            if (useVideoPauseWorkaround) {
                imsVideoCallProviderWrapper.setUseVideoPauseWorkaround(useVideoPauseWorkaround);
            }
            conn.setVideoProvider(imsVideoCallProviderWrapper);
            //imsVideoCallProviderWrapper.registerForDataUsageUpdate(this, EVENT_VT_DATA_USAGE_UPDATE, imsCall);
            imsVideoCallProviderWrapper.addImsVideoProviderCallback(conn);
        }
    }

    /* SPRD: Add for VoLTE @{ */
    public Connection
    dialConferenceCall(String dialString, Bundle extras, int clirMode) throws CallStateException {
        boolean isPhoneInEcmMode = mPhone != null && mPhone.isInEcm();;
        boolean isEmergencyNumber = PhoneNumberUtils.isEmergencyNumber(dialString);

        // note that this triggers call state changed notif
        clearDisconnected();
        mImsManager = ImsManager.getInstance(mPhone.getContext(), mPhone.getPhoneId());
        mServiceId = mPhone.getPhoneId() + 1;

        if (mImsManager == null) {
            throw new CallStateException("service not available");
        }

        if (isPhoneInEcmMode && isEmergencyNumber) {
            log("dialConferenceCall->isPhoneInEcmMode:" + isPhoneInEcmMode);
        }
        String[] callees = extras.getStringArray("android.intent.extra.IMS_CONFERENCE_PARTICIPANTS");
        if(callees == null){
            throw new CallStateException("dialConferenceCall->callees is null!");
        }

        log("dialConferenceCall->mForegroundCall.getState(): " + mForegroundCall.getState());
        if (mForegroundCall.getState() == ImsPhoneCall.State.ACTIVE
                || mForegroundCall.getState() == ImsPhoneCall.State.DIALING
                || mForegroundCall.getState() == ImsPhoneCall.State.ALERTING) {
            if (!mForegroundCall.isMultiparty()) {
                throw new CallStateException("can not add participant to normal call");
            }
            //add participant
            ImsCall imsCall = mForegroundCall.getImsCall();
            if (imsCall == null) {
                throw new CallStateException("can not add participant: No foreground ims call!");
            } else {
                ImsCallSession imsCallSession = imsCall.getCallSession();
                if (imsCallSession != null) {
                    imsCallSession.inviteParticipants(callees);
                    return null;
                } else {
                    throw new CallStateException("can not add participant: ImsCallSession does not exist!");
                }
            }
        }

        ImsPhoneCall.State fgState = ImsPhoneCall.State.IDLE;
        ImsPhoneCall.State bgState = ImsPhoneCall.State.IDLE;

        synchronized (mSyncHold) {
            setPendingMO(new ImsPhoneConnectionEx(mPhone,
                checkForTestEmergencyNumber(dialString), this, mForegroundCall, isEmergencyNumber));
        }
        addConnectionEx(getPendingMO());

        if ((!isPhoneInEcmMode) || (isPhoneInEcmMode && isEmergencyNumber)) {
            if (getPendingMO() == null) {
                return null;
            }

            if (getPendingMO().getAddress()== null || getPendingMO().getAddress().length() == 0
                    || getPendingMO().getAddress().indexOf(PhoneNumberUtils.WILD) >= 0) {
                // Phone number is invalid
                getPendingMO().setDisconnectCause(DisconnectCause.INVALID_NUMBER);
                sendEmptyMessageDelayed(EVENT_HANGUP_PENDINGMO, TIMEOUT_HANGUP_PENDINGMO);
                return null;
            }

            setMute(false);
            int serviceType = PhoneNumberUtils.isEmergencyNumber(getPendingMO().getAddress()) ?
                    ImsCallProfile.SERVICE_TYPE_EMERGENCY : ImsCallProfile.SERVICE_TYPE_NORMAL;

            try {
                ImsCallProfile profile = mImsManager.createCallProfile(
                        serviceType, ImsCallProfile.CALL_TYPE_VOICE);

                profile.setCallExtraInt(ImsCallProfile.EXTRA_OIR, clirMode);

                ImsCall imsCall = mImsManager.makeCall(profile,
                        callees, getImsCallListener());
                getPendingMO().setImsCall(imsCall);

                setVideoCallProvider(getPendingMO(), imsCall);
            } catch (ImsException e) {
                loge("dialInternal : " + e);
                getPendingMO().setDisconnectCause(DisconnectCause.ERROR_UNSPECIFIED);
                sendEmptyMessageDelayed(EVENT_HANGUP_PENDINGMO, TIMEOUT_HANGUP_PENDINGMO);
            } catch (RemoteException e) {
            }
        } else {
            try {
                getEcbmInterface().exitEmergencyCallbackMode();
            } catch (ImsException e) {
                e.printStackTrace();
                throw new CallStateException("service not available");
            }
            mPhone.setOnEcbModeExitResponse(this, EVENT_EXIT_ECM_RESPONSE_CDMA, null);
        }

        updatePhoneStateEx();
        mPhone.notifyPreciseCallStateChanged();

        return getPendingMO();
    }

    @Override
    public int getDisconnectCauseFromReasonInfo(ImsReasonInfo reasonInfo, Call.State callState) {
        int cause = DisconnectCause.ERROR_UNSPECIFIED;

        int code = maybeRemapReasonCode(reasonInfo);
        switch (code) {
            case ImsReasonInfo.CODE_LOCAL_CALL_DECLINE:
            case ImsReasonInfo.CODE_REMOTE_CALL_DECLINE:
                // If the call has been declined locally (on this device), or on remotely (on
                // another device using multiendpoint functionality), mark it as rejected.
            case ImsReasonInfo.CODE_USER_DECLINE: //SPRD: Add for casue set error when reject incomming call
                return DisconnectCause.INCOMING_REJECTED;

            case ImsReasonInfo.CODE_USER_TERMINATED_BY_REMOTE:
            case ImsReasonInfo.CODE_USER_NOANSWER: //SPRD: add for bug643818
                return DisconnectCause.NORMAL;
            /* SPRD:Add for VoLTE @{ */
            case ImsReasonInfo.CODE_SIP_REQUEST_CANCELLED:
                return DisconnectCause.OUTGOING_CANCELED;
            /* @} */
            default:
                cause = super.getDisconnectCauseFromReasonInfo(reasonInfo, callState);
        }
        return cause;
    }

    private Call.SrvccState mSrvccState = Call.SrvccState.NONE;
    private ConcurrentHashMap<Integer,Message> mPendingActionsMap = new ConcurrentHashMap<Integer,Message>();

    @Override
    public void notifySrvccState(Call.SrvccState state) {
        log("notifySrvccState->state:"+state);
        super.notifySrvccState(state);
        mSrvccState = state;
        if(mSrvccState == Call.SrvccState.COMPLETED){
            excutePendingActions();
        }
    }

    private boolean shouldPostPonedAction(){
        return mSrvccState == Call.SrvccState.STARTED;
    }

    @Override
    public void hangup (ImsPhoneConnection conn) throws CallStateException {
        if(shouldPostPonedAction()){
            Message m = new Message();
            m.what = SrvccPendingAction.EVENT_HANGUP;
            m.obj = conn;
            mPendingActionsMap.put(SrvccPendingAction.EVENT_HANGUP, m);
            log("shouldPostPonedAction->hangup.");
            return;
        }
        if (((ImsPhoneConnectionEx) conn).hangupImsCall()) {
            hangup(conn.getImsCall());
            return;
        }
        super.hangup(conn);
    }

    @Override
    public void hangup (ImsPhoneCall call) throws CallStateException {
        if(shouldPostPonedAction()){
            Message m = new Message();
            m.what = SrvccPendingAction.EVENT_HANGUP;
            m.obj = call;
            mPendingActionsMap.put(SrvccPendingAction.EVENT_HANGUP, m);
            log("shouldPostPonedAction->hangup.");
            return;
        }
        ImsCall imsCall = call.getImsCall();
        if(call == mTempCall){
            log("hangup->call == mTempCal.");
            call.onHangupLocal();
            if (imsCall != null) {
                imsCall.terminate(ImsReasonInfo.CODE_USER_TERMINATED);
            } else if (getPendingMO() != null) {
                // is holding a foreground call
                /*SPRD: add for bug603556 @{*/
                synchronized (mSyncResume) {
                    if(mIsResuming || mIsDialing){
                        setPendingIntentExtras(null);
                    }
                    mIsResuming = false;
                    mIsDialing = false;
                    mResumingImsCall = null;
                }
                /* @} */
                clearPendingMO();
                updatePhoneStateEx();
                removeMessages(EVENT_DIAL_PENDINGMO);
            }
            mPhone.notifyPreciseCallStateChanged();
            return;
        }
        //Unisoc: add for bug 1177039
        if(hasMessages(EVENT_REDIAL_ON_HANDOVER)
            && getPendingMO() != null && call.isDialingOrAlerting()) {
            log("remove EVENT_REDIAL_ON_HANDOVER ");
            removeMessages(EVENT_REDIAL_ON_HANDOVER);
            clearPendingMO();
            updatePhoneStateEx();
            mPhone.notifyPreciseCallStateChanged();
            return;
        }
        super.hangup(call);
    }

    @Override
    public void acceptCall (int videoState) throws CallStateException {
        if(shouldPostPonedAction()){
            Message m = new Message();
            m.what = SrvccPendingAction.EVENT_ACCEPT;
            m.arg1 = videoState;
            mPendingActionsMap.put(SrvccPendingAction.EVENT_ACCEPT, m);
            log("shouldPostPonedAction->acceptCall.");
            return;
        }
        if (mForegroundCall.getState().isAlive()
                && mBackgroundCall.getState().isAlive()) {
            throw new CallStateException("cannot accept call");
        }
        if ((mRingingCall.getState() == ImsPhoneCall.State.WAITING)
                && mForegroundCall.getState().isAlive()) {
            boolean answeringWillDisconnect = false;
            ImsCall activeCall = mForegroundCall.getImsCall();
            ImsCall ringingCall = mRingingCall.getImsCall();
            if (mForegroundCall.hasConnections() && mRingingCall.hasConnections()) {
                answeringWillDisconnect =
                        shouldDisconnectActiveCallOnAnswerEx(activeCall, ringingCall);
            }

            /*SPRD:bug858168 add voice accept video call @{*/
            if(!answeringWillDisconnect && ringingCall != null){
                if((ImsCallProfile.getCallTypeFromVideoState(videoState) == ImsCallProfile.CALL_TYPE_VOICE) && (ringingCall.isVideoCall())){
                    log("voice accept video call!");
                    int videoDowngrade = 99/*specail_code_only_send_downgrade_command*/;
                    try {
                        ringingCall.accept(videoDowngrade);
                    } catch (ImsException e) {
                        throw new CallStateException("cannot accept call");
                    }
                }
            }
            /*@}*/
        }

        super.acceptCall(videoState);
    }

    @Override
    public void rejectCall () throws CallStateException {
        if(shouldPostPonedAction()){
            Message m = new Message();
            m.what = SrvccPendingAction.EVENT_REJECT;
            mPendingActionsMap.put(SrvccPendingAction.EVENT_REJECT, m);
            log("shouldPostPonedAction->rejectCall.");
            return;
        }
        super.rejectCall();
    }

    @Override public void
    conference() {
        if(shouldPostPonedAction()){
            Message m = new Message();
            m.what = SrvccPendingAction.EVENT_CONFERENCE;
            mPendingActionsMap.put(SrvccPendingAction.EVENT_CONFERENCE, m);
            log("shouldPostPonedAction->conference.");
            return;
        }
        super.conference();
    }

    @Override
    public void sendDtmf(char c, Message result) {
        if(shouldPostPonedAction()){
            Message m = new Message();
            m.what = SrvccPendingAction.EVENT_SEND_DTMF;
            AsyncResult ar = new AsyncResult(c,result, null);
            m.obj = ar;
            mPendingActionsMap.put(SrvccPendingAction.EVENT_SEND_DTMF, m);
            log("shouldPostPonedAction->sendDtmf.");
            return;
        }
        super.sendDtmf(c,result);
    }

    @Override public void
    startDtmf(char c) {
        if(shouldPostPonedAction()){
            Message m = new Message();
            m.what = SrvccPendingAction.EVENT_START_DTMF;
            m.obj = c;
            mPendingActionsMap.put(SrvccPendingAction.EVENT_START_DTMF, m);
            log("shouldPostPonedAction->startDtmf.");
            return;
        }
        super.startDtmf(c);
    }

    @Override public void
    stopDtmf() {
        super.stopDtmf();
        ImsCall imscall = mForegroundCall.getImsCall();
        if (imscall == null)  {
            // SPRD: Fix 624634
            imscall = mBackgroundCall.getImsCall();
            if (DBG) log("stopDtmf for hold call: " + imscall);
            if (imscall != null) {
                imscall.stopDtmf();
            }
        }
    }

    @Override
    public boolean switchWaitingOrHoldingAndActive() throws CallStateException {
        if(shouldPostPonedAction()){
            Message m = new Message();
            m.what = SrvccPendingAction.EVENT_SWITCH_HOLD_ACTIVE;
            mPendingActionsMap.put(SrvccPendingAction.EVENT_SWITCH_HOLD_ACTIVE, m);
            log("switchWaitingOrHoldingAndActive->conference.");
            return true;
        }
        try {
            if (mForegroundCall.getState().isAlive()) {
                //resume foreground call after holding background call
                //they were switched before holding
                ImsCall imsCall = mForegroundCall.getImsCall();
                if (imsCall != null) {
                    if (imsCall.isOnHold()) {//SPRD: add for bug660974
                        imsCall.resume();
                        /*SPRD: add for bug603556 @{*/
                        mIsResuming = true;
                        mResumingImsCall = imsCall;
                        /* @} */
                        return true;
                    } else {
                        return false;
                    }
                }
            } else if (mRingingCall.getState() == ImsPhoneCall.State.WAITING) {
                //TODO:
                return false;
            } else {
                //Just resume background call.
                //To distinguish resuming call with swapping calls
                //we do not switch calls.here
                //ImsPhoneConnection.update will chnage the parent when completed
                ImsCall imsCall = mBackgroundCall.getImsCall();
                if (imsCall != null) {
                    if (imsCall.isOnHold()) {//SPRD: add for bug660974
                        imsCall.resume();
                        /*SPRD: add for bug603556 @{*/
                        mIsResuming = true;
                        mResumingImsCall = imsCall;
                        /* @} */
                        return true;
                    } else {
                        return false;
                    }
                }
            }
        } catch (ImsException e) {
            throw new CallStateException(e.getMessage());
        }
        return false;
    }

    class SrvccPendingAction{
        public static final int EVENT_DIAL                = 1;
        public static final int EVENT_HANGUP              = 2;
        public static final int EVENT_ACCEPT              = 3;
        public static final int EVENT_REJECT              = 4;
        public static final int EVENT_CONFERENCE          = 5;
        public static final int EVENT_SWITCH_HOLD_ACTIVE  = 6;
        public static final int EVENT_SEND_DTMF           = 7;
        public static final int EVENT_START_DTMF          = 8;
        public static final int EVENT_STOP_DTMF           = 9;
    }

    private void excutePendingActions(){
        log("excutePendingActions.");
        for(Message m : mPendingActionsMap.values()){
            log("excutePendingActions->m.what:"+m.what);
            GsmCdmaCallTracker gsmCdmaCT = (GsmCdmaCallTracker)(mPhone.getDefaultPhone().getCallTracker());
            try{
                switch(m.what){
                    case SrvccPendingAction.EVENT_DIAL:
                        Bundle intentExtras = m.peekData();
                        intentExtras.putShort(VIDEO_STATE, (short) (m.arg1));
                        Connection dialconn = gsmCdmaCT.dialGsm((String)m.obj, null, intentExtras);
                        if(dialconn != null){
                            mPhone.notifyUnknownConnection(dialconn);
                        }
                        break;
                    case SrvccPendingAction.EVENT_HANGUP:
                        Message msg = new Message();
                        msg.what = EVENT_HANGUP_PENGDINGCALL;
                        msg.obj = m.obj;
                        sendMessageDelayed(msg, 200);
                        break;
                    case SrvccPendingAction.EVENT_ACCEPT:
                        gsmCdmaCT.acceptCall();
                        break;
                    case SrvccPendingAction.EVENT_REJECT:
                        gsmCdmaCT.rejectCall();
                        break;
                    case SrvccPendingAction.EVENT_CONFERENCE:
                        gsmCdmaCT.conference();
                        break;
                    case SrvccPendingAction.EVENT_SWITCH_HOLD_ACTIVE:
                        gsmCdmaCT.switchWaitingOrHoldingAndActive();
                        break;
                    case SrvccPendingAction.EVENT_SEND_DTMF:
                        AsyncResult dtmfAR = (AsyncResult)m.obj;
                        mPhone.getDefaultPhone().sendDtmf((char)dtmfAR.userObj);
                        break;
                    case SrvccPendingAction.EVENT_START_DTMF:
                        mPhone.getDefaultPhone().startDtmf((char)m.obj);
                        break;
                    case SrvccPendingAction.EVENT_STOP_DTMF:
                        mPhone.getDefaultPhone().stopDtmf();
                        break;
                    default:
                        log("excutePendingActions->not support action->m.what:"+m.what);
                        break;
                }
            } catch(CallStateException e){
            }
        }
    }

    @Override
    public void addSrvccPendingEvent(Message m){
        log("addSrvccPendingEvent:"+m.what);
        mPendingActionsMap.put(m.what, m);
    }
    @Override
    public void handleMessage(Message msg) {
        switch (msg.what) {
            case EVENT_HANGUP_PENGDINGCALL:
                try {
                    GsmCdmaCallTracker gsmCdmaCT = (GsmCdmaCallTracker)(mPhone.getDefaultPhone().getCallTracker());
                    if(msg.obj instanceof ImsPhoneConnection){
                        GsmCdmaConnection gsmCdmaConnection = getGsmCdmaConnection((ImsPhoneConnection)msg.obj);
                        if(gsmCdmaConnection != null){
                            gsmCdmaCT.hangup(gsmCdmaConnection);
                        }else{
                            log("handleMessage gsmCdmaConnection is null");
                        }
                    } else if(msg.obj instanceof ImsPhoneCall){
                        GsmCdmaCall gsmCdmaCall = getGsmCdmaCall((ImsPhoneCall)msg.obj);
                        if(gsmCdmaCall != null){
                            gsmCdmaCT.hangup(gsmCdmaCall);
                        }else{
                            log("handleMessage gsmCdmaCall is null");
                        }
                    }
                } catch(CallStateException e){
                    log("handleMessage e:" + e);
                }
                break;
            case EVENT_REDIAL_ON_HANDOVER: {//SPRD:add for bug663110
                log("EVENT_REDIAL_ON_HANDOVER msg.obj = " + msg.obj);
                reDialWhenHandOver((ImsCall) msg.obj);
                break;
            }
            case EVENT_SERVICE_STATE_CHANGED: {
                Pair<ImsCall, ImsReasonInfo> callInfo =
                        (Pair<ImsCall, ImsReasonInfo>) ((AsyncResult) msg.obj).userObj;
                if (callInfo == null) {
                    break;
                }
                removeMessages(EVENT_RETRY_TIMEOUT);
                mPhone.getDefaultPhone().getServiceStateTracker()
                        .unregisterForNetworkAttached(this);
                fallbackToCSRedial(callInfo.first, callInfo.second);
                break;
            }
            case EVENT_RETRY_TIMEOUT: {
                Pair<ImsCall, ImsReasonInfo> callInfo = (Pair<ImsCall, ImsReasonInfo>) msg.obj;
                mPhone.getDefaultPhone().getServiceStateTracker()
                        .unregisterForNetworkAttached(this);
                removeMessages(EVENT_SERVICE_STATE_CHANGED);
                sendCallStartFailedDisconnect(callInfo.first, callInfo.second);
                break;
            }
            case EVENT_RETRY_WIFI_E911_CALL: {
                Pair<ImsCall, ImsReasonInfo> callInfo =
                        (Pair<ImsCall, ImsReasonInfo>) ((AsyncResult) msg.obj).userObj;
                if (callInfo == null) {
                    break;
                }
                removeMessages(EVENT_WIFI_E911_CALL_TIMEOUT);
                mPhone.getDefaultPhone().getServiceStateTracker()
                        .unregisterForNetworkAttached(this);
                reDialECallOnCS(callInfo.first, callInfo.second);
                break;
            }
            case EVENT_WIFI_E911_CALL_TIMEOUT: {
                Pair<ImsCall, ImsReasonInfo> callInfo = (Pair<ImsCall, ImsReasonInfo>) msg.obj;
                mPhone.getDefaultPhone().getServiceStateTracker()
                        .unregisterForNetworkAttached(this);
                removeMessages(EVENT_RETRY_WIFI_E911_CALL);
                sendCallStartFailedDisconnect(callInfo.first, callInfo.second);
                break;
            }
            default:
                super.handleMessage(msg);
        }
    }
    public GsmCdmaConnection getGsmCdmaConnection(ImsPhoneConnection imsPhoneConnection){
        log("getGsmCdmaConnection imsPhoneConnection:" + imsPhoneConnection);
        if (imsPhoneConnection == null) return null;
        GsmCdmaCall ringingCall = (GsmCdmaCall)mPhone.getDefaultPhone().getRingingCall();
        GsmCdmaCall foregroundCall = (GsmCdmaCall)mPhone.getDefaultPhone().getForegroundCall();
        GsmCdmaCall backgroundCall = (GsmCdmaCall)mPhone.getDefaultPhone().getBackgroundCall();
        GsmCdmaConnection gsmCdmaConn = null;
        try{
            for (int i = 0; i < ringingCall.getConnections().size(); i++){
                gsmCdmaConn = (GsmCdmaConnection)(ringingCall.getConnections().get(i));
                if (imsPhoneConnection.getImsCall() != null
                        && imsPhoneConnection.getImsCall().getSession() != null
                        && Integer.toString(gsmCdmaConn.getGsmCdmaConnIndex()).equals(imsPhoneConnection.getImsCall().getSession().getCallId())){
                    return gsmCdmaConn;
                }
            }
            for (int i = 0; i < foregroundCall.getConnections().size(); i++){
                gsmCdmaConn = (GsmCdmaConnection)(foregroundCall.getConnections().get(i));
                if (imsPhoneConnection.getImsCall() != null
                        && imsPhoneConnection.getImsCall().getSession() != null
                        && Integer.toString(gsmCdmaConn.getGsmCdmaConnIndex()).equals(imsPhoneConnection.getImsCall().getSession().getCallId())){
                    return gsmCdmaConn;
                }
            }
            for (int i = 0; i < backgroundCall.getConnections().size(); i++){
                gsmCdmaConn = (GsmCdmaConnection)(backgroundCall.getConnections().get(i));
                if (imsPhoneConnection.getImsCall() != null
                        && imsPhoneConnection.getImsCall().getSession() != null
                        && Integer.toString(gsmCdmaConn.getGsmCdmaConnIndex()).equals(imsPhoneConnection.getImsCall().getSession().getCallId())){
                    return gsmCdmaConn;
                }
             }
        } catch(CallStateException e){
            log("getGsmCdmaConnection e:" + e);
        }
        return gsmCdmaConn;
    }

    public GsmCdmaCall getGsmCdmaCall(ImsPhoneCall imsPhoneCall){
        log("getGsmCdmaCall imsPhoneCall:" + imsPhoneCall);
        if (imsPhoneCall == null) return null;
        GsmCdmaCall ringingCall = (GsmCdmaCall)mPhone.getDefaultPhone().getRingingCall();
        GsmCdmaCall foregroundCall = (GsmCdmaCall)mPhone.getDefaultPhone().getForegroundCall();
        GsmCdmaCall backgroundCall = (GsmCdmaCall)mPhone.getDefaultPhone().getBackgroundCall();
        for (int i = 0; i < imsPhoneCall.getConnections().size(); i++){
            ImsPhoneConnection imsPhoneConn = (ImsPhoneConnection)(imsPhoneCall.getConnections().get(i));
            GsmCdmaConnection gsmCdmaConn;
            try{
                for (int j = 0; j < ringingCall.getConnections().size(); j++){
                    gsmCdmaConn = (GsmCdmaConnection)(ringingCall.getConnections().get(j));
                    if (imsPhoneConn.getImsCall() != null
                            && imsPhoneConn.getImsCall().getSession() != null
                            && Integer.toString(gsmCdmaConn.getGsmCdmaConnIndex()).equals(imsPhoneConn.getImsCall().getSession().getCallId())){
                        return ringingCall;
                    }
                }
                for (int j = 0; j < foregroundCall.getConnections().size(); j++){
                    gsmCdmaConn = (GsmCdmaConnection)(foregroundCall.getConnections().get(j));
                    if (imsPhoneConn.getImsCall() != null
                            && imsPhoneConn.getImsCall().getSession() != null
                            && Integer.toString(gsmCdmaConn.getGsmCdmaConnIndex()).equals(imsPhoneConn.getImsCall().getSession().getCallId())){
                        return foregroundCall;
                    }
                }
                for (int j = 0; j < backgroundCall.getConnections().size(); j++){
                    gsmCdmaConn = (GsmCdmaConnection)(backgroundCall.getConnections().get(j));
                    if (imsPhoneConn.getImsCall() != null
                            && imsPhoneConn.getImsCall().getSession() != null
                            && Integer.toString(gsmCdmaConn.getGsmCdmaConnIndex()).equals(imsPhoneConn.getImsCall().getSession().getCallId())){
                        return backgroundCall;
                    }
                }
            } catch(CallStateException e){
                log("getGsmCdmaCall e:" + e);
            }
        }
        return null;
    }

    void hangup (ImsCall imsCall) throws CallStateException {
        if (imsCall == null) {
            throw new CallStateException ("no ImsCall");
        }
        if (DBG) log("hangup imsCall " + imsCall);

        if (imsCall != null) {
            imsCall.terminate(ImsReasonInfo.CODE_USER_TERMINATED);
        }
        mPhone.notifyPreciseCallStateChanged();
    }

    @Override
    public void processImsCallStateChange(ImsCall imsCall, ImsPhoneCall.State state, int cause,
                                boolean ignoreState){
        if (imsCall == null) return;

        boolean changed = false;
        ImsPhoneConnection conn = findImsConnection(imsCall);

        if (conn == null) {
            return;
        }

        super.processImsCallStateChange(imsCall, state, cause, ignoreState);
    }

    @Override
    public boolean onImsCallStartFailed(ImsCall imsCall, ImsReasonInfo reasonInfo) {
        if(reasonInfo.mCode == ImsReasonInfo.CODE_LOCAL_HO_NOT_FEASIBLE){
            ImsCallProfile profile = imsCall.getCallProfile();
            if(profile != null){
                Message m = new Message();
                int videoState = 0;
                if(profile.mCallType == ImsCallProfile.CALL_TYPE_VT){
                    videoState = VideoProfile.STATE_BIDIRECTIONAL;
                }
                m.what = 1/*SrvccPendingAction.EVENT_DIAL*/;
                m.arg1 = videoState;
                m.obj = profile.getCallExtra(ImsCallProfile.EXTRA_OI);
                log("onCallStartFailed->redial.");
                addSrvccPendingEvent(m);
            }
        } else if (reasonInfo.getCode() == ImsReasonInfo.CODE_SIP_ALTERNATE_EMERGENCY_CALL) {
            boolean isAirplaneModeOn = Settings.Global.getInt(mPhone.getContext().getContentResolver(),
                    Settings.Global.AIRPLANE_MODE_ON, 0) > 0;
            log("onImsCallStartFailed: CODE_SIP_ALTERNATE_EMERGENCY_CALL");
            if (!isAirplaneModeOn
                    && mPhone.getDefaultPhone().getServiceStateTracker().mSS != null
                    && ServiceState.STATE_IN_SERVICE != mPhone.getDefaultPhone().getServiceStateTracker().mSS.getState()
                    && !mPhone.getDefaultPhone().getServiceStateTracker().mSS.isEmergencyOnly()) {
                reDialECallOnVowifi(imsCall,reasonInfo);
                return true;
            }
            //close airplaneMode
            final ConnectivityManager mgr = (ConnectivityManager) mPhone.getContext()
                    .getSystemService(Context.CONNECTIVITY_SERVICE);
            if (isAirplaneModeOn) {
                mgr.setAirplaneMode(false);
            }
            Pair<ImsCall, ImsReasonInfo> callInfo = new Pair<>(imsCall, reasonInfo);
            mPhone.getDefaultPhone().getServiceStateTracker().registerForNetworkAttached(
                    ImsPhoneCallTrackerEx.this, EVENT_RETRY_WIFI_E911_CALL, callInfo);
            sendMessageDelayed(obtainMessage(EVENT_WIFI_E911_CALL_TIMEOUT, callInfo),
                    TIMEOUT_REDIAL_ECALL_ON_CS);
            return true;
        } else if(reasonInfo.getCode() != ImsReasonInfo.CODE_USER_TERMINATED && isVowifiEnabled()) {
            ImsCallProfile profile = imsCall.getCallProfile();
            CarrierConfigManager configManager = (CarrierConfigManager) mPhone.getContext().getSystemService(
                    Context.CARRIER_CONFIG_SERVICE);
            boolean supportFallbackToCS = false;
            boolean dialEccOnWifiAir = false;
            if (configManager != null) {
                PersistableBundle config = configManager.getConfigForSubId(mPhone.getSubId());
                if (config != null) {
                    supportFallbackToCS = config.getBoolean(CarrierConfigManagerEx.KEY_CARRIER_ECC_FALLBACK_TO_CS, false);
                    dialEccOnWifiAir = config.getBoolean(CarrierConfigManagerEx.KEY_CARRIER_DIAL_ECC_VOWIFI_WHEN_AIRPLANE, false);
                    log("onImsCallStartFailed supportFallbackToCS: " + supportFallbackToCS
                            + " dialEccOnWifiAir: " + dialEccOnWifiAir);
                }
            }

            if (profile != null && profile.mServiceType == ImsCallProfile.SERVICE_TYPE_EMERGENCY && supportFallbackToCS) {
                boolean isAirplaneModeOn = Settings.Global.getInt(mPhone.getContext().getContentResolver(),
                        Settings.Global.AIRPLANE_MODE_ON, 0) > 0;
                log("onImsCallStartFailed: isAirplaneModeOn = " + isAirplaneModeOn);
                //close airplaneMode
                final ConnectivityManager mgr = (ConnectivityManager) mPhone.getContext()
                        .getSystemService(Context.CONNECTIVITY_SERVICE);
                if (isAirplaneModeOn) {
                    mgr.setAirplaneMode(false);
                }
                Pair<ImsCall, ImsReasonInfo> callInfo = new Pair<>(imsCall, reasonInfo);
                mPhone.getDefaultPhone().getServiceStateTracker().registerForNetworkAttached(
                        ImsPhoneCallTrackerEx.this, EVENT_SERVICE_STATE_CHANGED, callInfo);
                sendMessageDelayed(obtainMessage(EVENT_RETRY_TIMEOUT, callInfo),
                        TIMEOUT_REDIAL_ECALL_ON_CS);
                return true;
            }
        }

        log(" mBackgroundCall.getState() = " +mBackgroundCall.getState()+" mRingingCall.getState() = "+mRingingCall.getState());
        if (getPendingMO() != null) {
           if(reasonInfo.getCode() == CODE_LOCAL_CALL_IMS_HANDOVER_RETRY){
                log("onCallStartFailed send reDial message imsCall = "+imsCall);
                Message mess = new Message();
                mess.what = EVENT_REDIAL_ON_HANDOVER;
                mess.obj = imsCall;
                sendMessageDelayed(mess, 2000);
                return true;
            }
        }
        return false;
    }

    @Override
    public void onImsCallHoldFailed(ImsCall imsCall, ImsReasonInfo reasonInfo) {
        if (reasonInfo.mCode == ImsReasonInfo.CODE_LOCAL_HO_NOT_FEASIBLE) {
            Message m = new Message();
            m.what = 6/*EVENT_SWITCH_HOLD_ACTIVE*/;
            log("onCallHoldFailed->hold.");
            addSrvccPendingEvent(m);
        }
    }

    @Override
    public void onImsCallResumed(ImsCall imsCall) {
    }

    @Override
    public void onImsCallResumeFailed(ImsCall imsCall, ImsReasonInfo reasonInfo) {
        if(reasonInfo.mCode == ImsReasonInfo.CODE_LOCAL_HO_NOT_FEASIBLE){
            Message m = new Message();
            m.what = 6/*EVENT_SWITCH_HOLD_ACTIVE*/;
            log("onCallHoldFailed->resume.");
            addSrvccPendingEvent(m);
        }
    }

    @Override
    public boolean onImsCallMerged() {
        setMergePeerConn(null);//SPRD: add for bug870386
        //SPRD:add for bug678901 & bug758173
        /*TODO
        if (mPhone.getContext().getResources().getBoolean(
                com.android.internal.R.bool.config_show_merge_success)) {
            Toast.makeText(mPhone.getContext(), R.string.merge_success, Toast.LENGTH_SHORT).show();
        }*/
        if(mBackgroundCall.getImsCall() != null && mBackgroundCall.getImsCall().isOnHold()){
            processImsCallStateChange(mBackgroundCall.getImsCall(), ImsPhoneCall.State.HOLDING,
                    DisconnectCause.NOT_DISCONNECTED,false);
            return false;
        }
        return true;
    }

    @Override
    public void onImsCallMergeFailed(ImsCall call, ImsReasonInfo reasonInfo) {
        if(reasonInfo.mCode == ImsReasonInfo.CODE_LOCAL_HO_NOT_FEASIBLE){
            Message m = new Message();
            m.what = 5/*EVENT_CONFERENCE*/;
            log("onCallHoldFailed->resume.");
            addSrvccPendingEvent(m);
        }

        /*SPRD: add for bug870386 and bug1133985 @{*/
        log("onImsCallMergeFailed mMergePeerConn = "+mMergePeerConn);
        if (mMergePeerConn != null) {
            mMergePeerConn.onConferenceMergeFailed();
            mMergePeerConn.handleMergeComplete();
        }
        setMergePeerConn(null);
        /*@}*/
    }

    @Override
    public void onImsCallTerminated(ImsCall imsCall, ImsReasonInfo reasonInfo) {

        //UNISOC：add for vowifi emergency call
        if (mPhone.getState() == PhoneConstants.State.IDLE) {
            GsmCdmaPhone gsmCdmaPhone = (GsmCdmaPhone) mPhone.getDefaultPhone();
            log("gsmCdmaPhone.getState() = " + gsmCdmaPhone.getState());
            if (gsmCdmaPhone.getState() != PhoneConstants.State.IDLE) {
                gsmCdmaPhone.mCT.sendEmptyMessage(EVENT_CALL_STATE_CHANGE);
            }
        }

        if (mForegroundCall.getState() != ImsPhoneCall.State.ACTIVE
                && getPendingMO() != null && !mRingingCall.getState().isRinging()) {
                /*SPRD: add for bug603556 @{*/
            synchronized (mSyncResume) {
                if(mIsResuming && mResumingImsCall == imsCall){
                    mIsResuming = false;
                    mResumingImsCall = null;
                    if(mIsDialing){
                        mIsDialing = false;
                        try {
                            dialDelay(mPendingDialString, CommandsInterface.CLIR_DEFAULT, getPendingVideoState(), getPendingIntentExtras());
                            setPendingIntentExtras(null);
                        } catch (CallStateException e) {
                            if (getPendingMO() != null) {
                                getPendingMO().setDisconnectCause(DisconnectCause.OUTGOING_FAILURE);
                                sendEmptyMessageDelayed(EVENT_HANGUP_PENDINGMO, TIMEOUT_HANGUP_PENDINGMO);
                            }
                            log("onCallTerminated : exception:" + e + " cause:" + e.getMessage());
                        }
                    }
                }
            }
        }else if (mIsResuming && mResumingImsCall == imsCall) {
            log("onCallTerminated  clear the mIsResuming and mResumingImsCall");
            mIsResuming = false;
            mResumingImsCall = null;
        }
    }

    @Override
    public void onImsCallUpdated(ImsCall imsCall) {
        ImsPhoneConnection conn = findImsConnection(imsCall);
        if (conn != null) {
            /* SPRD: updateMtMultiparty when update call. */
            if(conn instanceof ImsPhoneConnectionEx){
                ((ImsPhoneConnectionEx)conn).updateMtMultiparty(imsCall);
            }
            /* @} */
            processImsCallStateChange(imsCall, conn.getCall().mState,
                    DisconnectCause.NOT_DISCONNECTED, true /*ignore state update*/);
        }
    }

    @Override
    public void processImsIncomingCall(ImsCall imsCall) {
        if (imsCall == null) return;
        ImsPhoneConnection conn = findImsConnection(imsCall);
        if (conn == null) {
            return;
        }
        if(conn instanceof ImsPhoneConnectionEx){
            ((ImsPhoneConnectionEx)conn).updateMtMultiparty(imsCall);
        }
    }
    @Override
    public void setMergePeerConn(ImsPhoneConnection conn){
        mMergePeerConn = conn;
        log("setMergePeerConn mMergePeerConn = "+mMergePeerConn);
    }

    /*SPRD: add for bug603556 @{*/
    private void dialDelay(String dialString, int clirMode, int videoState, Bundle intentExtras)
            throws CallStateException {
        boolean isPhoneInEcmMode = mPhone.isInEcm();
        boolean isEmergencyNumber = PhoneNumberUtils.isEmergencyNumber(dialString);
        boolean holdBeforeDial = false;
        if (!shouldCalleeBePlacedOnIms(isEmergencyNumber, dialString)) {
            Rlog.i(LOG_TAG, "dial: shouldNumberBePlacedOnIms = false");
            throw new CallStateException("cs_fallback");
        }

        if (DBG) log("dial clirMode=" + clirMode);
        if (isEmergencyNumber) {
            clirMode = CommandsInterface.CLIR_SUPPRESSION;
            if (DBG) log("dial emergency call, set clirModIe=" + clirMode);
        }
        // note that this triggers call state changed notif
        clearDisconnected();

        // See if there are any issues which preclude placing a call; throw a CallStateException
        // if there is.
        checkForDialIssues();

        // The new call must be assigned to the foreground call.
        // That call must be idle, so place anything that's
        // there on hold
        if (mForegroundCall.getState() == ImsPhoneCall.State.ACTIVE) {
            if (mBackgroundCall.getState() != ImsPhoneCall.State.IDLE) {
                //we should have failed in !canDial() above before we get here
                throw new CallStateException("cannot dial in current state");
            }
            // foreground call is empty for the newly dialed connection
            holdBeforeDial = true;
            // Cache the video state for pending MO call.
            setPendingVideoState(videoState);
            setPendingIntentExtras(null);
            holdActiveCallForPendingCall();
        }

        ImsPhoneCall.State fgState = ImsPhoneCall.State.IDLE;
        ImsPhoneCall.State bgState = ImsPhoneCall.State.IDLE;

        setClirMode(clirMode);

        synchronized (mSyncHold) {
            if (holdBeforeDial) {
                fgState = mForegroundCall.getState();
                bgState = mBackgroundCall.getState();

                //holding foreground call failed
                if (fgState == ImsPhoneCall.State.ACTIVE) {
                    throw new CallStateException("cannot dial in current state");
                }

                //holding foreground call succeeded
                if (bgState == ImsPhoneCall.State.HOLDING) {
                    holdBeforeDial = false;
                }
            }
            setLastDialString(dialString);
            if(getPendingMO() != null){
                if (isEmergencyNumber && intentExtras != null) {
                    boolean isUserIntentEmergencyCall = intentExtras.getBoolean(TelecomManager.EXTRA_IS_USER_INTENT_EMERGENCY_CALL);
                    Rlog.i(LOG_TAG, "dial ims emergency dialer: " + isUserIntentEmergencyCall);
                    getPendingMO().setHasKnownUserIntentEmergency(isUserIntentEmergencyCall);
                }
                mTempCall.detach(getPendingMO());
                getPendingMO().changeParent(mForegroundCall);
                mForegroundCall.attachFake(getPendingMO(), ImsPhoneCall.State.DIALING);
            }
        }

        if (!holdBeforeDial) {
            if ((!isPhoneInEcmMode) || (isPhoneInEcmMode && isEmergencyNumber)) {
                dialImsCallInternal(getPendingMO(), clirMode, videoState, intentExtras);
            } else {
                try {
                    getEcbmInterface().exitEmergencyCallbackMode();
                } catch (ImsException e) {
                    e.printStackTrace();
                    throw new CallStateException("service not available");
                }
                mPhone.setOnEcbModeExitResponse(this, EVENT_EXIT_ECM_RESPONSE_CDMA, null);
                setpendingCallClirMode(clirMode);
                setPendingVideoState(videoState);
                setpendingCallInEcm(true);
            }
        }

        updatePhoneStateEx();
        mPhone.notifyPreciseCallStateChanged();
    }
    /* @} */
    //SPRD:add for bug651208
    public void reDialWhenHandOver(ImsCall imsCall) {

        ImsCallProfile profile = imsCall.getCallProfile();
        log("reDialWhenHandOver " + profile + " pendingMO = "+ getPendingMO());
        if (profile != null && getPendingMO() != null) {
            int clirMode = profile.getCallExtraInt(ImsCallProfile.EXTRA_OIR);
            int videoState = getPendingMO().getVideoState();
            Bundle intentExtras = profile.mCallExtras.getBundle(ImsCallProfile.EXTRA_OEM_EXTRAS);

            if (imsCall != null)
                imsCall.close();
            imsCall = null;

            log("reDialWhenHandOver clirMode = " + clirMode + " videoState = " + videoState);
            dialImsCallInternal(getPendingMO(), clirMode, videoState, intentExtras);
        }
    }


    @Override
    public boolean isUtEnabled() {
        int phoneId = mPhone.getPhoneId();
        boolean isUsim = false;
        boolean isUtEnable = super.isUtEnabled();
        if (getLteRadioCapability()) {
            isUsim =  isUsimCard(phoneId);
        }
        log("isUsim = " + isUsim+" isUtEnable = "+isUtEnable);
        return isUsim && isUtEnable;
    }

    private boolean getLteRadioCapability() {
        Phone phone = PhoneFactory.getPhone(mPhone.getPhoneId());
        if (phone != null) {
            int raf = phone.getRadioAccessFamily();
            return (raf & RadioAccessFamily.RAF_LTE) == RadioAccessFamily.RAF_LTE;
        }
        return false;
    }

    private boolean isUsimCard(int phoneId) {
        if (DBG) log("isUsimCard: ");
        if (!SubscriptionManager.isValidPhoneId(phoneId)) {
            if (DBG) log("Ignore invalid phoneId: " + phoneId);
            return false;
        }
        int appFam = UiccController.APP_FAM_3GPP;
        UiccCardApplication application =
                UiccController.getInstance().getUiccCardApplication(phoneId, appFam);
        if (application != null) {
            return  application.getType() == AppType.APPTYPE_USIM;
        }
        return false;
    }

    private boolean fallbackToCSRedial(ImsCall imsCall, ImsReasonInfo reasonInfo) {
        if (getPendingMO() == null) {
            return false;
        }
        String reDialNum = "";
        ImsPhoneConnection conn = findImsConnection(imsCall);
        if (conn != null) {
            reDialNum = conn.getAddress();
            log("fallbackToCSRedial -> reasonInfo: " + reasonInfo);
            GsmCdmaPhoneEx gsmCdmaPhoneEx = (GsmCdmaPhoneEx) mPhone.getDefaultPhone();
            detachPendingMO(mForegroundCall);
            try {
                if (gsmCdmaPhoneEx != null) {
                    Connection dialconn = gsmCdmaPhoneEx.reDialOnCS(reDialNum,
                            new PhoneInternalInterface.DialArgs.Builder<>()
                            .setVideoState(VideoProfile.STATE_AUDIO_ONLY).build());
                    if (dialconn != null) {
                        gsmCdmaPhoneEx.notifyUnknownConnection(dialconn);
                        return true;
                    }
                    log("onCallStartFailed->CODE_LOCAL_CALL_CS_RETRY_REQUIRED dialconn:" + dialconn);
                } else {
                    log("onCallStartFailed->CODE_LOCAL_CALL_CS_RETRY_REQUIRED gsmCdmaPhone is null");
                }
            } catch (CallStateException e) {
                log("onCallStartFailed->CODE_LOCAL_CALL_CS_RETRY_REQUIRED CallStateException: " + e);
            }
        }
        return false;
    }
    private boolean reDialECallOnCS(ImsCall imsCall, ImsReasonInfo reasonInfo) {
        if (getPendingMO() == null) {
            return false;
        }
        String urn = reasonInfo.getExtraMessage();
        int category = EmergencyNumber.EMERGENCY_SERVICE_CATEGORY_UNSPECIFIED;
        try {
            category = Integer.parseInt(reasonInfo.getExtraMessage());
        } catch (NumberFormatException e) {
        }

      String reDialNum = "";
      ImsPhoneConnection conn = findImsConnection(imsCall);
      if (conn != null) {
          reDialNum = conn.getAddress();
          GsmCdmaPhone gsmCdmaPhone = (GsmCdmaPhone) mPhone.getDefaultPhone();
          detachPendingMO(mForegroundCall);
          if (urn != null && urn.contains("urn:service")) {
              String urnCommand = "AT+WIFISYNC=" + String.valueOf(0) + ",\"" + urn + "\"";
              log("UrnCommand AT command is: " + urnCommand);
              RadioInteractor radioInteractor = new RadioInteractor(mPhone.getContext());
              radioInteractor.sendAtCmd(urnCommand, null, mPhone.getPhoneId());
              try {
                  Thread.sleep(200);
              } catch (InterruptedException e) {
                  // do nothing
              }
          }
          EmergencyNumber emergencyNumberInfo = new EmergencyNumber(reDialNum, "", "", category,
                  new ArrayList<String>(),
                  EmergencyNumber.EMERGENCY_NUMBER_SOURCE_NETWORK_SIGNALING,
                  EmergencyNumber.EMERGENCY_CALL_ROUTING_EMERGENCY);
          gsmCdmaPhone.mCi.dial(reDialNum, true, emergencyNumberInfo, true, CommandsInterface.CLIR_DEFAULT,null);
          updatePhoneStateEx();
          mPhone.notifyPreciseCallStateChanged();
      }
      return true;
    }

    private boolean reDialECallOnVowifi(ImsCall imsCall, ImsReasonInfo reasonInfo) {
        if (imsCall == null || reasonInfo == null) {
            return false;
        }
        String urn = reasonInfo.getExtraMessage();
        ImsCallProfile profile = imsCall.getCallProfile();
        if (profile != null && getPendingMO() != null) {
            profile.setCallExtra(ImsCallProfile.EXTRA_ADDITIONAL_CALL_INFO, urn);
            int clirMode = profile.getCallExtraInt(ImsCallProfile.EXTRA_OIR);
            int videoState = getPendingMO().getVideoState();
            Bundle intentExtras = profile.mCallExtras.getBundle(ImsCallProfile.EXTRA_OEM_EXTRAS);

            if (imsCall != null) {
                imsCall.close();
            }
            imsCall = null;
            log("reDialECallOnVowifi urn = " + urn);
            dialImsCallInternal(getPendingMO(), clirMode, videoState, intentExtras);
            return true;
        }
        return false;
    }
}
