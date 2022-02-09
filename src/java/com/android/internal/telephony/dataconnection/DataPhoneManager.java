/* Created by Spreadst */

package com.android.internal.telephony.dataconnection;

import android.content.Context;
import android.os.AsyncResult;
import android.os.Handler;
import android.os.Message;
import android.provider.Settings;
import android.telephony.AccessNetworkConstants;
import android.telephony.PhoneStateListener;
import android.telephony.TelephonyManager;
import android.telephony.SubscriptionManager;
import android.telephony.AccessNetworkConstants.TransportType;

import android.util.Log;

import com.android.ims.internal.ImsManagerEx;
import com.android.internal.telephony.Phone;
import com.android.internal.telephony.PhoneConstants;
import com.android.internal.telephony.PhoneFactory;

class DataPhoneManager extends Handler {
    private static final String TAG = "DataPhoneManager";

    private static final int EVENT_PRECISE_CALL_STATE_CHANGED = 0;

    private int mMaxActivePhones = 1; // Default: DSDS phone has only one active phone
    private int mPhoneState = TelephonyManager.CALL_STATE_IDLE;
    private int mPhoneId;
    private int mCurrentSubId;

    private PhoneCallStateListener mPhoneCallStateListener;
    private SubscriptionManager mSubscriptionManager;
    private TelephonyManager mTelephonyManager;
    private Phone mPhone;

    private final SubscriptionManager.OnSubscriptionsChangedListener
    mOnSubscriptionsChangeListener =
    new SubscriptionManager.OnSubscriptionsChangedListener() {
        @Override
        public void onSubscriptionsChanged() {
            if (mCurrentSubId != mPhone.getSubId()) {
                log("onSubscriptionsChanged subId: " + mCurrentSubId + " to: "
                        + mPhone.getSubId());
                mCurrentSubId = mPhone.getSubId();
                mPhoneCallStateListener.listen(mCurrentSubId);

                // if subId is invalid and data is suspended, resume data.
                if (!SubscriptionManager.isValidSubscriptionId(mCurrentSubId)) {
                    onVoiceCallEnded(mPhone);
                }
            }
        }
    };

    public DataPhoneManager(Phone phone) {
        mPhoneId = phone.getPhoneId();
        mCurrentSubId = phone.getSubId();
        mPhone = phone;
        mPhoneCallStateListener = new PhoneCallStateListener();
        mPhoneCallStateListener.listen(mCurrentSubId);
        mSubscriptionManager = (SubscriptionManager) mPhone.getContext()
                .getSystemService(Context.TELEPHONY_SUBSCRIPTION_SERVICE);
        mSubscriptionManager.addOnSubscriptionsChangedListener(mOnSubscriptionsChangeListener);
        log("constructor");
    }

    private class PhoneCallStateListener extends PhoneStateListener {

        public PhoneCallStateListener() {
            super();
        }

        @Override
        public void onCallStateChanged(int state, String incomingNumber) {
            int phoneId = SubscriptionManager.getPhoneId(mSubId.intValue());
            Phone phone = PhoneFactory.getPhone(phoneId);
            if (phone != null) {
                if (state != TelephonyManager.CALL_STATE_IDLE &&
                        mPhoneState == TelephonyManager.CALL_STATE_IDLE) {
                    log("phone" + phoneId + " call start");
                    onVoiceCallStarted(phone);
                } else if (mPhoneState != TelephonyManager.CALL_STATE_IDLE &&
                        state == TelephonyManager.CALL_STATE_IDLE) {
                    log("phone" + phoneId + " call end");
                    onVoiceCallEnded(phone);
                }
                mPhoneState = state;
            }
        }

        public void listen(int subId) {
            if (SubscriptionManager.isValidSubscriptionId(subId)) {
                log("PhoneCallStateListener: listen subId = " + subId);
                mSubId = subId;
                mTelephonyManager = TelephonyManager.from(mPhone.getContext()).createForSubscriptionId(subId);
                mTelephonyManager.listen(this, PhoneStateListener.LISTEN_CALL_STATE);
            }
        }
    }

    private void onVoiceCallStarted(Phone phone) {
        for (Phone p : PhoneFactory.getPhones()) {
            if (mMaxActivePhones <= 1 && p != phone) {
                DcTracker dct = p.getDcTracker(AccessNetworkConstants.TRANSPORT_TYPE_WWAN);
                dct.suspendData(Phone.REASON_VOICE_CALL_STARTED);
            }
        }
    }

    private void onVoiceCallEnded(Phone phone) {
        for (Phone p : PhoneFactory.getPhones()) {
            if (mMaxActivePhones <= 1 && p != phone) {
                DcTracker dct = p.getDcTracker(AccessNetworkConstants.TRANSPORT_TYPE_WWAN);
                if (dct.isSuspended()) {
                    dct.resumeData(Phone.REASON_VOICE_CALL_ENDED);
                }
            }
        }
    }

    private void log(String logStr) {
        String logTag = TAG + "[" + String.valueOf(mPhoneId) + "]";
        Log.d(logTag, logStr);
    }
}
