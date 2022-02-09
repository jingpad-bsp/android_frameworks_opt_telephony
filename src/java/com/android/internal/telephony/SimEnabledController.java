
package com.android.internal.telephony;

import java.util.HashMap;
import java.util.Map;

import com.android.internal.telephony.Phone;
import com.android.internal.telephony.PhoneFactory;
import com.android.internal.telephony.RadioController;
import com.android.internal.telephony.RadioInteraction;
import com.android.internal.telephony.TeleUtils;

import android.content.Context;
import android.os.Handler;
import android.os.Message;
import android.telephony.Rlog;

public class SimEnabledController {
    private static String TAG = "SimEnabledController";

    private static final int EVENT_SET_SIM_ENABLED = 0;
    private static final int EVENT_POWER_RADIO_OFF_DONE = 1;
    private static final int EVENT_POWER_RADIO_ON_DONE = 2;

    private static SimEnabledController sInstance;
    private static Map<Integer, SetSimEnabled> mSetSimEnabledRequestList = new HashMap<Integer, SetSimEnabled>();
    private Context mContext;
    private RadioController mRC;

    private SimEnabledController(Context context) {
        mContext = context;
        mRC = RadioController.getInstance();
    }

    public static SimEnabledController getInstance() {
        return sInstance;
    }

    public static void init(Context context) {
        if (sInstance == null) {
            sInstance = new SimEnabledController(context);
        }
    }

    public void setSimEnabled(int phoneId, final boolean turnOn) {
        Rlog.d(TAG, "set sim enabled[" + phoneId + "] " + turnOn);
        mHandler.obtainMessage(EVENT_SET_SIM_ENABLED, phoneId, turnOn ? 1 : 0).sendToTarget();
    }

    private Handler mHandler = new Handler() {
        public void handleMessage(Message msg) {
            if (msg.what == EVENT_SET_SIM_ENABLED) {

                TeleUtils.setRadioBusy(mContext, true);

                Phone phone = PhoneFactory.getPhone(msg.arg1);
                if (phone != null) {
                    new SetSimEnabled(phone, msg.arg2 == 1).setSimEnabled();
                }
            }
        };
    };

    private class SetSimEnabled extends Handler {
        private final Phone mPhone;
        private final boolean mEnabled;
        private RadioInteraction mRI;

        public SetSimEnabled(Phone phone, boolean turnOn) {
            mPhone = phone;
            mEnabled = turnOn;
        }

        private void setSimEnabled() {
            synchronized (SimEnabledController.this) {
                if (mSetSimEnabledRequestList.get(mPhone.getPhoneId()) == null) {
                    mSetSimEnabledRequestList.put(mPhone.getPhoneId(), this);
                } else {
                    mSetSimEnabledRequestList.put(mPhone.getPhoneId(), this);
                    return;
                }
            }

            if (mEnabled) {
                mRI = new RadioInteraction(mPhone);
                mRI.setCallBack(new Runnable() {
                    @Override
                    public void run() {
                        mRC.setRadioPower(mPhone.getPhoneId(), true,
                                obtainMessage(EVENT_POWER_RADIO_ON_DONE));
                    }
                });
                mRI.powerOnIccCard(RadioInteraction.ICC_POWER_TIMEOUT);

            } else {
                mRC.setRadioPower(mPhone.getPhoneId(), false,
                        obtainMessage(EVENT_POWER_RADIO_OFF_DONE));
            }
        }

        public void handleMessage(Message msg) {
            switch (msg.what) {
                case EVENT_POWER_RADIO_OFF_DONE:
                    mRI = new RadioInteraction(mPhone);
                    mRI.setCallBack(new Runnable() {
                        @Override
                        public void run() {
                            finish();
                        }
                    });
                    mRI.powerOffIccCard(RadioInteraction.ICC_POWER_TIMEOUT);
                    break;

                case EVENT_POWER_RADIO_ON_DONE:
                    finish();
                    break;

                default:
                    break;
            }
        }

        private void finish() {
            if (mRI != null) {
                mRI.destoroy();
                mRI = null;
            }

            SetSimEnabled sse = null;
            synchronized (SimEnabledController.this) {
                sse = mSetSimEnabledRequestList.remove(mPhone.getPhoneId());
            }

            if (sse != null && sse != this && sse.mEnabled != mEnabled) {
                sse.setSimEnabled();
            } else {
                TeleUtils.setRadioBusy(mContext, false);
            }
        }
    }
}
