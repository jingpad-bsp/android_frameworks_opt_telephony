/*
 * Copyright (C) 2006 The Android Open Source Project
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

package com.android.internal.telephony.gsm;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.os.AsyncResult;
import android.os.Message;
import android.os.SystemProperties;
import android.telephony.Rlog;
import android.telephony.TelephonyManager;
import android.text.TextUtils;

import com.android.internal.telephony.gsm.GsmMmiCode;
import com.android.internal.telephony.CallStateException;
import com.android.internal.telephony.CommandException;
import com.android.internal.telephony.CommandsInterface;
import com.android.internal.telephony.GsmCdmaPhone;
import com.android.internal.telephony.uicc.IccCardApplicationStatus.AppState;
import com.android.internal.telephony.uicc.UiccCardApplication;
import com.android.sprd.telephony.RadioInteractor;

/**
 * The motto for this file is:
 * "NOTE:    By using the # as a separator, most cases are expected to be unambiguous." -- TS 22.030
 * 6.5.2 {@hide}
 */
public final class GsmMmiCodeEx extends GsmMmiCode {

    static final String LOG_TAG = "GsmMmiCodeEx";
    static final String SC_COLP = "76";
    static final String SC_COLR = "77";
    static final String SC_CNAP = "300";
    public static final int UNLOCK_PIN = 0;
    public static final int UNLOCK_PIN2 = 1;
    public static final int UNLOCK_PUK = 2;
    public static final int UNLOCK_PUK2 = 3;

    static final int EVENT_QUERY_LI_COMPLETE = 8;
    private static final String PROPERTY_PIN2_REMAINTIMES = "gsm.sim.pin2.remaintimes";
    private static final int MIN_REMAINTIMES = 0;
    /* SPRD: add for bug737275 @{ */
    private RadioInteractor mRi;
    private static final int QUERY_LI_RESPONSE_ERROR = -1;
    private static final int QUERY_LI_RESPONSE_DISABLE = 0;
    private static final int QUERY_LI_RESPONSE_ENABLE = 1;
    private static final int QUERY_LI_FOR_SUPP_PWD_REG = 100;
    private static final int QUERY_LI_SUPP_PWD_REG_SUCCESS = 0;
    private static final int QUERY_LI_SUPP_PWD_REG_FAIL = 1;
    private static final int DEACTIVE_CLIP = 0;
    private static final int ACTIVE_CLIP = 1;
    private static final int EVENT_SET_CLIP_COMPLETE = 9;
    // UNISOC: add for Bug 1000675
    private static final int EVENT_QUERY_COMPLETE = 10;
    /* @} */
    /* SPRD: add for bug711082 & 833973 & 908877 @{ */
    private static final String[] PROP_SIM_PIN_PUK_REMAINTIMES = {
            "vendor.sim.pin.remaintimes",
            "vendor.sim.pin2.remaintimes",
            "vendor.sim.puk.remaintimes",
            "vendor.sim.puk2.remaintimes"
    };
    private final static String DISSMISS_VIDEO_CF_NOTIFICATION_ACTION =
            "android.intent.action.DISMISS_VIDEO_CF_NOTIFICATION";
    private final static String VIDEO_CF_SUB_ID = "video_cf_flag_with_subid";
    private static final String REFRESH_VIDEO_CF_NOTIFICATION_ACTION =
            "android.intent.action.REFRESH_VIDEO_CF_NOTIFICATION";
    private static final String VIDEO_CF_STATUS = "video_cf_status";
    /* @} */

    public GsmMmiCodeEx(GsmCdmaPhone phone, UiccCardApplication app) {
        super(phone, app);
        // SPRD: add for bug737275
        mRi = new RadioInteractor(mPhone.getContext());
    }

    /**
     * Process a MMI code or short code...anything that isn't a dialing number
     */
    @Override
    public void processCode() throws CallStateException {
        try {
            /* SPRD: add for bug737275 @{ */
            if (mSc != null && mSc.equals(SC_CLIP) && (isActivate() || isDeactivate())) {
                Rlog.d(LOG_TAG, "is CLIP active or deactive");
                mRi.updateCLIP(isActivate() ? ACTIVE_CLIP : DEACTIVE_CLIP,
                        obtainMessage(EVENT_SET_CLIP_COMPLETE, this), mPhone.getPhoneId());
            } else if (mSc != null && mSc.equals(SC_COLP)) {
                Rlog.d(LOG_TAG, "is COLP");
                if (isInterrogate()) {
                    int returnValue = mRi.queryColp(mPhone.getPhoneId());
                    Message msg = Message.obtain(this, EVENT_QUERY_LI_COMPLETE);
                    msg.arg1 = returnValue;
                    msg.sendToTarget();
                } else {
                    throw new RuntimeException("Invalid or Unsupported MMI Code");
                }
            } else if (mSc != null && mSc.equals(SC_COLR)) {
                Rlog.d(LOG_TAG, "is COLR");
                if (isInterrogate()) {
                    int returnValue = mRi.queryColr(mPhone.getPhoneId());
                    Message msg = Message.obtain(this, EVENT_QUERY_LI_COMPLETE);
                    msg.arg1 = returnValue;
                    msg.sendToTarget();
                } else {
                    throw new RuntimeException("Invalid or Unsupported MMI Code");
                }
            } else if (isPinPukCommand()) {
                // TODO: This is the same as the code in CmdaMmiCode.java,
                // MmiCode should be an abstract or base class and this and
                // other common variables and code should be promoted.

                // sia = old PIN or PUK
                // sib = new PIN
                // sic = new PIN
                String oldPinOrPuk = mSia;
                String newPinOrPuk = mSib;
                int pinLen = newPinOrPuk.length();
                if (isRegister()) {
                    if (!newPinOrPuk.equals(mSic)) {
                        // password mismatch; return error
                        /**
                         * SPRD: porting ussd feature for bug475740 @{
                         * @orig handlePasswordError(com.android.internal.R.string.mismatchPin);
                         */
                        if (mSc.equals(SC_PIN)) {
                            handlePasswordError(com.android.internal.R.string.mismatchPin);
                        } else if (mSc.equals(SC_PIN2)) {
                            handlePasswordError(com.android.internal.R.string.mismatchPin2);
                        } else if (mSc.equals(SC_PUK)) {
                            handlePasswordError(com.android.internal.R.string.mismatchPuk);
                        } else if (mSc.equals(SC_PUK2)) {
                            handlePasswordError(com.android.internal.R.string.mismatchPuk2);
                        } else {
                        }
                        /** @} */
                    } else if (pinLen < 4 || pinLen > 8) {
                        // invalid length
                        handlePasswordError(com.android.internal.R.string.invalidPin);
                    } else if (mSc.equals(SC_PIN)
                            && mUiccApplication != null
                            && mUiccApplication.getState() == AppState.APPSTATE_PUK) {
                        // Sim is puk-locked
                        handlePasswordError(com.android.internal.R.string.needPuk);
                    } else if (mUiccApplication != null) {
                        Rlog.d(LOG_TAG, "process mmi service code using UiccApp sc=" + mSc);

                        // We have an app and the pre-checks are OK
                        if (mSc.equals(SC_PIN)) {
                            /**
                             * SPRD: porting ussd feature for bug475740 @{
                             * @orig mUiccApplication.changeIccLockPassword(oldPinOrPuk,
                             *newPinOrPuk, obtainMessage(EVENT_SET_COMPLETE, this));
                             */
                            if (oldPinOrPuk.length() < 4 || oldPinOrPuk.length() > 8) {
                                handlePasswordError(com.android.internal.R.string.invalidPin);
                                return;
                            }
                            if (mUiccApplication.getIccLockEnabled() || mUiccApplication.getState() == AppState.APPSTATE_PIN) {
                                mUiccApplication.changeIccLockPassword(oldPinOrPuk, newPinOrPuk,
                                        obtainMessage(EVENT_SET_COMPLETE, this));
                            } else {
                                handlePasswordError(com.android.internal.R.string.enablePin);
                            }
                            /** @} */
                        } else if (mSc.equals(SC_PIN2)) {
                            /* SPRD: add for bug514467 @{ */
                            if (oldPinOrPuk.length() < 4 || oldPinOrPuk.length() > 8) {
                                handlePasswordError(com.android.internal.R.string.invalidPin);
                                return;
                            }
                            /* @} */
                            mUiccApplication.changeIccFdnPassword(oldPinOrPuk, newPinOrPuk,
                                    obtainMessage(EVENT_SET_COMPLETE, this));
                        } else if (mSc.equals(SC_PUK)) {
                            /* SPRD: add for bug494346 @{ */
                            if (oldPinOrPuk.length() != 8) {
                                handlePasswordError(com.android.internal.R.string.invalidPuk);
                                return;
                            }
                            /* @} */
                            mUiccApplication.supplyPuk(oldPinOrPuk, newPinOrPuk,
                                    obtainMessage(EVENT_SET_COMPLETE, this));
                        } else if (mSc.equals(SC_PUK2)) {
                            /* SPRD: add for bug514467 @{ */
                            if (oldPinOrPuk.length() != 8) {
                                handlePasswordError(com.android.internal.R.string.puklengthlimit);
                                return;
                            }
                            /* @} */
                            mUiccApplication.supplyPuk2(oldPinOrPuk, newPinOrPuk,
                                    obtainMessage(EVENT_SET_COMPLETE, this));
                        } else {
                            throw new RuntimeException("uicc unsupported service code=" + mSc);
                        }
                    } else {
                        throw new RuntimeException("No application mUiccApplicaiton is null");
                    }
                } else {
                    throw new RuntimeException("Ivalid register/action=" + mAction);
                }
            /* UNISOC: add for Bug 992248 @{ */
            } else if (mSc != null && mSc.equals(SC_CNAP)) {
                Rlog.d(LOG_TAG, "is CNAP");
                if (isInterrogate()) {
                    int returnValue = mRi.getCnap(mPhone.getPhoneId());
                    Message msg = Message.obtain(this, EVENT_QUERY_LI_COMPLETE);
                    msg.arg1 = returnValue;
                    msg.sendToTarget();
                } else {
                    throw new RuntimeException("Invalid or Unsupported MMI Code");
                }
            /* UNISOC: add for Bug 1000675 @{ */
            } else if (isServiceCodeCallBarring(mSc) && isInterrogate()) {
                // sia = password
                // sib = basic service group

                String password = mSia;
                int serviceClass = siToServiceClass(mSib);
                String facility = scToBarringFacility(mSc);

                mRi.queryFacilityLockForAppExt(facility, password, serviceClass,
                        obtainMessage(EVENT_QUERY_COMPLETE, this), mPhone.getPhoneId());
            /* @} */
            } else {
                super.processCode();
            }
        } catch (RuntimeException exc) {
            Rlog.d(LOG_TAG, "RuntimeException " + exc);
            mState = State.FAILED;
            mMessage = mContext.getText(com.android.internal.R.string.mmiError);
            mPhone.onMMIDone(this);
        }
    }

    private void handlePasswordError(int res) {
        mState = State.FAILED;
        StringBuilder sb = new StringBuilder(getScString());
        sb.append("\n");
        sb.append(mContext.getText(res));
        mMessage = sb;
        mPhone.onMMIDone(this);
    }

    @Override
    public void
    handleMessage(Message msg) {
        AsyncResult ar;

        switch (msg.what) {
            /* SPRD: porting ussd feature for bug475740 @{ */
            case EVENT_QUERY_LI_COMPLETE:
                // SRPD: modify for bug641723
                onQueryLiComplete(msg);
                break;
            /* @} */
            case EVENT_SET_COMPLETE:
                ar = (AsyncResult) (msg.obj);

                onSetComplete(msg, ar);
                break;

            case EVENT_SET_CFF_COMPLETE:
                ar = (AsyncResult) (msg.obj);

                /*
                * msg.arg1 = 1 means to set unconditional voice call forwarding
                * msg.arg2 = 1 means to enable voice call forwarding
                */
                if ((ar.exception == null) && (msg.arg1 == 1)) {
                    boolean cffEnabled = (msg.arg2 == 1);
                    if (mIccRecords != null) {
                        mPhone.setVoiceCallForwardingFlag(1, cffEnabled, mDialingNumber);
                    }
                }
                /* SPRD: add for bug711082 & 820976, 966397 @{ */
                if ((ar.exception == null) && (isErasure() || isDeactivate())) {
                    Rlog.d(LOG_TAG, "erase video call forward sc : " + mSc);
                    if (SC_CFU.equals(mSc) || SC_CF_All.equals(mSc)) {
                        dismissVideoCFNotification();
                    }
                    clearCallForward(mSc);
                }
                /* @} */
                onSetComplete(msg, ar);
                break;
            /* SPRD: add for bug737275 @{ */
            case EVENT_SET_CLIP_COMPLETE:
                ar = (AsyncResult) (msg.obj);
                onSetClipComplete(msg, ar);
                break;
            /* @} */
            /* UNISOC: add for Bug 1000675 @{ */
            case EVENT_QUERY_COMPLETE:
                ar = (AsyncResult) (msg.obj);
                onQueryComplete(ar);
                break;
            /* @} */
            default:
                super.handleMessage(msg);
        }
    }

    private CharSequence getErrorMessage(AsyncResult ar) {

        if (ar.exception instanceof CommandException) {
            CommandException.Error err = ((CommandException) (ar.exception)).getCommandError();
            if (err == CommandException.Error.FDN_CHECK_FAILURE) {
                Rlog.i(LOG_TAG, "FDN_CHECK_FAILURE");
                return mContext.getText(com.android.internal.R.string.mmiFdnError);
            } else if (err == CommandException.Error.USSD_MODIFIED_TO_DIAL) {
                Rlog.i(LOG_TAG, "USSD_MODIFIED_TO_DIAL");
                return mContext.getText(com.android.internal.R.string.stk_cc_ussd_to_dial);
            } else if (err == CommandException.Error.USSD_MODIFIED_TO_SS) {
                Rlog.i(LOG_TAG, "USSD_MODIFIED_TO_SS");
                return mContext.getText(com.android.internal.R.string.stk_cc_ussd_to_ss);
            } else if (err == CommandException.Error.USSD_MODIFIED_TO_USSD) {
                Rlog.i(LOG_TAG, "USSD_MODIFIED_TO_USSD");
                return mContext.getText(com.android.internal.R.string.stk_cc_ussd_to_ussd);
            } else if (err == CommandException.Error.SS_MODIFIED_TO_DIAL) {
                Rlog.i(LOG_TAG, "SS_MODIFIED_TO_DIAL");
                return mContext.getText(com.android.internal.R.string.stk_cc_ss_to_dial);
            } else if (err == CommandException.Error.SS_MODIFIED_TO_USSD) {
                Rlog.i(LOG_TAG, "SS_MODIFIED_TO_USSD");
                return mContext.getText(com.android.internal.R.string.stk_cc_ss_to_ussd);
            } else if (err == CommandException.Error.SS_MODIFIED_TO_SS) {
                Rlog.i(LOG_TAG, "SS_MODIFIED_TO_SS");
                return mContext.getText(com.android.internal.R.string.stk_cc_ss_to_ss);
            }
        }

        return mContext.getText(com.android.internal.R.string.mmiError);
    }

    private CharSequence getScString() {
        if (mSc != null) {
            if (isServiceCodeCallBarring(mSc)) {
                return mContext.getText(com.android.internal.R.string.BaMmi);
            } else if (isServiceCodeCallForwarding(mSc)) {
                // SPRD: modify for bug771213
                return super.getCfType(mSc);
            } else if (mSc.equals(SC_CLIP)) {
                return mContext.getText(com.android.internal.R.string.ClipMmi);
            } else if (mSc.equals(SC_CLIR)) {
                return mContext.getText(com.android.internal.R.string.ClirMmi);
            } else if (mSc.equals(SC_PWD)) {
                return mContext.getText(com.android.internal.R.string.PwdMmi);
            } else if (mSc.equals(SC_WAIT)) {
                return mContext.getText(com.android.internal.R.string.CwMmi);
            } else if (isPinPukCommand()) {
                /**
                 * SPRD: porting ussd feature for bug475740 @{
                 * @orig return mContext.getText(com.android.internal.R.string.PinMmi);
                 */
                if (mSc.equals(SC_PIN)) {
                    return mContext.getText(com.android.internal.R.string.PinMmi);
                } else if (mSc.equals(SC_PIN2)) {
                    return mContext.getText(com.android.internal.R.string.Pin2Mmi);
                } else if (mSc.equals(SC_PUK)) {
                    return mContext.getText(com.android.internal.R.string.PukMmi);
                } else if (mSc.equals(SC_PUK2)) {
                    return mContext.getText(com.android.internal.R.string.Puk2Mmi);
                }
            } else if (mSc.equals(SC_COLP)) {
                return mContext.getText(com.android.internal.R.string.ColpMmi);
            } else if (mSc.equals(SC_COLR)) {
                return mContext.getText(com.android.internal.R.string.ColrMmi);
            }
            /** @} */
        }

        return "";
    }

    private void onSetComplete(Message msg, AsyncResult ar) {
        StringBuilder sb = new StringBuilder(getScString());
        sb.append("\n");

        /* SPRD: modify for bug711082 @{ */
        if (ar.exception != null) {
            mState = State.FAILED;
            if (ar.exception instanceof CommandException) {
                CommandException.Error err = ((CommandException) (ar.exception)).getCommandError();
                if (err == CommandException.Error.PASSWORD_INCORRECT) {
                    if (isPinPukCommand()) {
                        // look specifically for the PUK commands and adjust
                        // the message accordingly.
                        /**
                         * SPRD: porting ussd feature for bug475740 @{
                         * @orig if (mSc.equals(SC_PUK) || mSc.equals(SC_PUK2)) {
                         *       sb.append(mContext.getText( com.android.internal.R.string.badPuk));
                         *       } else { sb.append(mContext.getText(
                         *       com.android.internal.R.string.badPin)); }
                         */
                        int type = 0;
                        if (mSc.equals(SC_PUK)) {
                            sb.append(mContext.getText(
                                    com.android.internal.R.string.badPuk));
                            type = UNLOCK_PUK;
                        } else if (mSc.equals(SC_PUK2)) {
                            sb.append(mContext.getText(
                                    com.android.internal.R.string.badPuk2));
                            type = UNLOCK_PUK2;
                        } else if (mSc.equals(SC_PIN)) {
                            sb.append(mContext.getText(
                                    com.android.internal.R.string.badPin));
                            type = UNLOCK_PIN;
                        } else if (mSc.equals(SC_PIN2)) {
                            sb.append(mContext.getText(
                                    com.android.internal.R.string.badPin2));
                            type = UNLOCK_PIN2;
                        }
                        // Get the No. of retries remaining to unlock PUK/PUK2
                        /**
                         * SPRD: porting ussd feature for bug475740 @{
                         * @orig int attemptsRemaining = msg.arg1;
                         */
                        int attemptsRemaining = getRemainTimes(mContext, type, mPhone.getPhoneId());
                        if (attemptsRemaining <= 0) {
                            Rlog.d(LOG_TAG, "onSetComplete: PUK locked,"
                                    + " cancel as lock screen will handle this");
                            mState = State.CANCELLED;
                            /* SPRD: porting ussd feature for bug475740 and bug1005043 @{ */
                            if (attemptsRemaining == 0 && (type == UNLOCK_PIN||type == UNLOCK_PIN2)) {
                                mState = State.FAILED;
                                sb = new StringBuilder(getScString());
                                sb.append("\n");
                                if (type == UNLOCK_PIN) {
                                    sb.append(mContext.getText(
                                            com.android.internal.R.string.needPuk));
                                } else if (type == UNLOCK_PIN2) {
                                    sb.append(mContext.getText(
                                            com.android.internal.R.string.needPuk2));
                                }
                            }
                            /* @} */
                        } else if (attemptsRemaining > 0) {
                            Rlog.d(LOG_TAG, "onSetComplete: attemptsRemaining="
                                    + attemptsRemaining);
                            sb.append(mContext.getResources().getQuantityString(
                                    com.android.internal.R.plurals.pinpuk_attempts,
                                    attemptsRemaining, attemptsRemaining));
                        }
                    } else {
                        sb.append(mContext.getText(
                                com.android.internal.R.string.passwordIncorrect));
                    }
                } else if (err == CommandException.Error.SIM_PUK2) {
                    sb.append(mContext.getText(
                            com.android.internal.R.string.badPin));
                    sb.append("\n");
                    sb.append(mContext.getText(
                            com.android.internal.R.string.needPuk2));
                } else if (err == CommandException.Error.REQUEST_NOT_SUPPORTED) {
                    if (mSc.equals(SC_PIN)) {
                        sb.append(mContext.getText(com.android.internal.R.string.enablePin));
                    }
                } else if (err == CommandException.Error.FDN_CHECK_FAILURE) {
                    Rlog.i(LOG_TAG, "FDN_CHECK_FAILURE");
                    sb.append(mContext.getText(com.android.internal.R.string.mmiFdnError));
                } else {
                    sb.append(getErrorMessage(ar));
                }
            } else {
                sb.append(mContext.getText(
                        com.android.internal.R.string.mmiError));
            }
        } else if (isActivate()) {
            mState = State.COMPLETE;
            if (isCallFwdReg()) {
                sb.append(mContext.getText(
                        com.android.internal.R.string.serviceRegistered));
            } else {
                sb.append(mContext.getText(
                        com.android.internal.R.string.serviceEnabled));
            }
            // Record CLIR setting
            if (mSc.equals(SC_CLIR)) {
                mPhone.saveClirSetting(CommandsInterface.CLIR_INVOCATION);
            }
            /* UNISOC: add for bug 1006755 @{ */
            if (mSc.equals(SC_WAIT)) {
                Rlog.d(LOG_TAG, "set cw value for vowifi isActivate()");
                SystemProperties.set("gsm.ss.call_waiting", "true");
            }
            /* @} */
        } else if (isDeactivate()) {
            mState = State.COMPLETE;
            sb.append(mContext.getText(
                    com.android.internal.R.string.serviceDisabled));
            // Record CLIR setting
            if (mSc.equals(SC_CLIR)) {
                mPhone.saveClirSetting(CommandsInterface.CLIR_SUPPRESSION);
            }
            /* UNISOC: add for bug 1006755 @{ */
            if (mSc.equals(SC_WAIT)) {
                Rlog.d(LOG_TAG, "set cw value for vowifi isDeactivate()");
                SystemProperties.set("gsm.ss.call_waiting", "false");
            }
            /* @} */
        } else if (isRegister()) {
            mState = State.COMPLETE;
            sb.append(mContext.getText(
                    com.android.internal.R.string.serviceRegistered));
        } else if (isErasure()) {
            mState = State.COMPLETE;
            sb.append(mContext.getText(
                    com.android.internal.R.string.serviceErased));
        } else {
            mState = State.FAILED;
            sb.append(mContext.getText(
                    com.android.internal.R.string.mmiError));
        }

        mMessage = sb;
        Rlog.d(LOG_TAG, "onSetComplete mmi=" + this);
        mPhone.onMMIDone(this);
    }
    /* @} */

    public static GsmMmiCode newNetworkInitiatedUssdError(String ussdMessage,
            boolean isUssdRequest, GsmCdmaPhone phone, UiccCardApplication app) {
        GsmMmiCode ret;
        ret = new GsmMmiCodeEx(phone, app);
        // UNISOC: modify for 1121600
        if (TextUtils.isEmpty(ussdMessage)) {
            ret.mMessage = ret.mContext.getText(com.android.internal.R.string.mmiError);
        } else {
            ret.mMessage = ussdMessage;
        }
        ret.setUssdRequest(isUssdRequest);
        ret.mState = State.FAILED;

        return ret;
    }

    /* SPRD: modify for bug641723 @{ */
    private void onQueryLiComplete(Message msg) {
        StringBuilder sb = new StringBuilder(getScString());
        sb.append("\n");
        Rlog.i(LOG_TAG, "QUERY_LI_RESPONSE msg:" + msg);
        if (QUERY_LI_RESPONSE_ERROR == msg.arg1) {
            mState = State.FAILED;
            sb.append(mContext.getText(com.android.internal.R.string.mmiError));
        } else if (QUERY_LI_FOR_SUPP_PWD_REG == msg.arg2) {
            if (QUERY_LI_SUPP_PWD_REG_SUCCESS == msg.arg1) {
                sb.append(mContext.getText(com.android.internal.R.string.serviceRegistered));
            } else if (QUERY_LI_SUPP_PWD_REG_FAIL == msg.arg1) {
                sb.append(mContext.getText(com.android.internal.R.string.passwordIncorrect));
            } else {
                sb.append(mContext.getText(com.android.internal.R.string.mmiError));
            }
            mState = State.COMPLETE;
        }else {
            if (QUERY_LI_RESPONSE_DISABLE == msg.arg1) {
                sb.append(mContext.getText(com.android.internal.R.string.serviceDisabled));
            } else if (QUERY_LI_RESPONSE_ENABLE == msg.arg1) {
                sb.append(mContext.getText(com.android.internal.R.string.serviceEnabled));
            } else {
                sb.append(mContext.getText(com.android.internal.R.string.mmiError));
            }
            mState = State.COMPLETE;
        }
        mMessage = sb;
        mPhone.onMMIDone(this);
    }
    /* @} */

    /* SPRD: add for bug737275 @{ */
    private void onSetClipComplete(Message msg, AsyncResult ar) {
        StringBuilder sb = new StringBuilder(getScString());
        sb.append("\n");
        Rlog.i(LOG_TAG, "QUERY_CLIP_RESPONSE msg:" + msg);
        if (ar.exception != null) {
            mState = State.FAILED;
            sb.append(mContext.getText(com.android.internal.R.string.mmiError));
        } else {
            if (isDeactivate()) {
                sb.append(mContext.getText(com.android.internal.R.string.serviceDisabled));
            } else if (isActivate()) {
                sb.append(mContext.getText(com.android.internal.R.string.serviceEnabled));
            } else {
                sb.append(mContext.getText(com.android.internal.R.string.mmiError));
            }
            mState = State.COMPLETE;
        }
        mMessage = sb;
        mPhone.onMMIDone(this);
    }
    /* @} */

    /* SPRD: add for bug711082 & 833973 @{ */
    private int getRemainTimes(Context context, int type, int phoneId) {
        String remainTimesProperty = null;
        int remainTimes = MIN_REMAINTIMES;

        if (type >= 0 && type < PROP_SIM_PIN_PUK_REMAINTIMES.length) {
            remainTimesProperty = PROP_SIM_PIN_PUK_REMAINTIMES[type];
        }

        if (!TextUtils.isEmpty(remainTimesProperty)) {
            String propertyValue = TelephonyManager.from(context)
                    .getTelephonyProperty(phoneId, remainTimesProperty, "");

            if (!TextUtils.isEmpty(propertyValue)) {
                try {
                    remainTimes = Integer.valueOf(propertyValue);
                } catch (Exception e) {
                }
            }
        }
        return remainTimes;
    }

    private void dismissVideoCFNotification() {
        if (mContext != null) {
            Intent intent = new Intent(REFRESH_VIDEO_CF_NOTIFICATION_ACTION);
            intent.putExtra(VIDEO_CF_STATUS, false);
            intent.putExtra(VIDEO_CF_SUB_ID, mPhone.getSubId());
            mContext.sendBroadcast(intent);
            Rlog.d(LOG_TAG, "refresh notification for video cf subid : "
                    + mPhone.getSubId() + "; enable : " + false);
        }
    }
    /* @} */

    /* SPRD: add for bug 820976 @{ */
    private void clearCallForward (String reason) {
        final int CfAlways = 0;
        final int CfBusy = 1;
        final int CfUnAnswer = 2;
        final int CfUnreachable = 3;

        switch (reason) {
            case SC_CFU :
                removeCFSharePreference(CfAlways);
                break;
            case SC_CFB:
                removeCFSharePreference(CfBusy);
                break;
            case SC_CFNRy:
                removeCFSharePreference(CfUnAnswer);
                break;
            case SC_CFNR:
                removeCFSharePreference(CfUnreachable);
                break;
            case SC_CF_All:
                removeCFSharePreference(CfAlways);
                removeCFSharePreference(CfBusy);
                removeCFSharePreference(CfUnAnswer);
                removeCFSharePreference(CfUnreachable);
                break;
            case SC_CF_All_Conditional:
                removeCFSharePreference(CfBusy);
                removeCFSharePreference(CfUnAnswer);
                removeCFSharePreference(CfUnreachable);
                break;
            default:
                break;
        }
    }

    private void removeCFSharePreference (int reason) {
        try {
            final String PHONE_PACKAGE = "com.android.phone";
            final String PREF_PREFIX = "phonecallforward_";
            final String video = "Video_";
            final int phoneId = mPhone.getPhoneId();
            Context appContext = mContext.getApplicationContext();
            Context phoneContext = appContext.createPackageContext(
                    PHONE_PACKAGE, Context.CONTEXT_IGNORE_SECURITY);
            SharedPreferences prefs = phoneContext.getSharedPreferences(
                    PREF_PREFIX + phoneId
                    , Context.MODE_PRIVATE | Context.MODE_MULTI_PROCESS);
            SharedPreferences.Editor editor = prefs.edit();
            editor.remove(PREF_PREFIX + phoneId + "_" + reason);
            editor.remove(PREF_PREFIX + video + phoneId + "_" + reason);
            editor.commit();
            Rlog.d(LOG_TAG, " removeCFSharePreference " + reason + " Success");
        } catch (PackageManager.NameNotFoundException e) {
            e.printStackTrace();
        }
    }
    /* @} */

    /* UNISOC: add for Bug 1000675 @{ */
    private void
    onQueryComplete (AsyncResult ar) {
        StringBuilder sb = new StringBuilder(getScString());
        sb.append("\n");

        if (ar.exception != null) {
            mState = State.FAILED;
            sb.append(getErrorMessage(ar));
        } else {
            int[] ints = (int[])ar.result;

            if (ints.length != 0) {
                if (ints[0] == 0) {
                    sb.append(mContext.getText(com.android.internal.R.string.serviceDisabled));
                } else if (mSc.equals(SC_WAIT)) {
                    // Call Waiting includes additional data in the response.
                    sb.append(createQueryCallWaitingResultMessage(ints[1]));
                } else if (isServiceCodeCallBarring(mSc) && ints.length == 2) {
                    // ints[0] for Call Barring is a bit vector of services
                    sb.append(createQueryCallBarringResultMessage(ints[1]));
                } else if (ints[0] == 1) {
                    // for all other services, treat it as a boolean
                    sb.append(mContext.getText(com.android.internal.R.string.serviceEnabled));
                } else {
                    sb.append(mContext.getText(com.android.internal.R.string.mmiError));
                }
            } else {
                sb.append(mContext.getText(com.android.internal.R.string.mmiError));
            }
            mState = State.COMPLETE;
        }

        mMessage = sb;
        Rlog.d(LOG_TAG, "onQueryComplete: mmi=" + this);
        mPhone.onMMIDone(this);
    }
    /* @} */
}
