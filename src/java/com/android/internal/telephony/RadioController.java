
package com.android.internal.telephony;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import android.os.Message;
import android.os.SystemProperties;
import android.telephony.Rlog;
import android.telephony.SubscriptionManager;
import android.telephony.TelephonyManager;
import android.os.SystemProperties;

public class RadioController {

    private static final String TAG = "RadioController";
    public static final int NONE = 0;
    public static final int POWER_ON = 1;
    public static final int POWER_OFF = 2;

    private final int mPhoneCount = TelephonyManager.getDefault().getPhoneCount();
    private static RadioController sInstance = new RadioController();

    private RadioController() {
    }

    public static RadioController getInstance() {
        return sInstance;
    }

    private static List<RadioTask> mRadioTaskList = new ArrayList<RadioTask>();

    public void setRadioPower(int phoneId, boolean onOff, Message msg) {
        int[] ops = new int[mPhoneCount];
        if (SubscriptionManager.isValidPhoneId(phoneId)) {
            ops[phoneId] = onOff ? POWER_ON : POWER_OFF;
        } else if (phoneId == mPhoneCount) {
            Arrays.fill(ops, onOff ? POWER_ON : POWER_OFF);
        }

        setRadioPower(ops, msg);
    }

    public void setRadioPower(int[] ops, Message response) {
        RadioTask newTask = new RadioTask(ops, response);
        boolean handleNow = true;
        synchronized (mRadioTaskList) {
            if (!mRadioTaskList.isEmpty()) {
                handleNow = false;
            }
            mRadioTaskList.add(newTask);
        }

        if (handleNow) {
            handleRadioTask(newTask);
        }
    }

    /**
     * Find invalid task and drop it.
     */
    private boolean needReplaceRadioTask(RadioTask task) {
        if (task.mOps != null) {
            boolean radioOnWithoutCard = false;
            int activeSimCount = 0;
            int activeSim = -1;
            boolean anyRadioOn = false;
            int primarySlot = SystemProperties.getInt("persist.vendor.radio.primarysim",0);
            int secondarySlot = -1;
            for (int i = 0; i < mPhoneCount; i++) {
                if (TeleUtils.hasIccCard(i)) {
                    activeSimCount++;
                    activeSim = i;
                }

                if (TeleUtils.isRadioOn(i)) {
                    anyRadioOn = true;
                    if (!TeleUtils.hasIccCard(i)) {
                        radioOnWithoutCard = true;
                    }
                }
                if (i != primarySlot) {
                    secondarySlot = i;
                }
                Rlog.d(TAG, "hasIccCard[" + i + "]= " + TeleUtils.hasIccCard(i) + " isRadioOn[" + i + "]= "
                        + TeleUtils.isRadioOn(i) + " radioOnWithoutCard= " + radioOnWithoutCard);
            }

            if (activeSimCount == 1 && task.mOps[activeSim] == POWER_ON) {
                boolean isFixSlot = "true".equals(SystemProperties.get("ro.vendor.radio.fixed_slot","false"));
                if ((!TeleUtils.isMultiModeSlot(activeSim) && anyRadioOn && !isFixSlot)) {
                    // To make modem happy [1]: If the only remaining SIM is not
                    // in multi-mode, we must power off all radios first and then
                    // power the SIM's radio on. Because RIL/modem will
                    // automatically reset multi-mode card. If directly power radio
                    // on, modem will assert.
                    int[] ops = new int[mPhoneCount];
                    ops[activeSim] = POWER_ON;
                    mRadioTaskList.add(1, new RadioTask(ops.clone(), task.mResponseMsgList));

                    Arrays.fill(ops, POWER_OFF);
                    mRadioTaskList.add(1, new RadioTask(ops));

                    Rlog.d(TAG, "needReplaceRadioTask: [1]" + mRadioTaskList);
                    return true;
                } else if (TeleUtils.isMultiModeSlot(activeSim) && radioOnWithoutCard) {
                    // To make modem happy [2]: If the only remaining SIM is
                    // in multi-mode, make sure any other radio has been powered
                    // off before power the SIM's radio on. Or, modem will also assert.
                    int[] ops = new int[mPhoneCount];
                    if (!TeleUtils.isRadioOn(activeSim)) {
                        ops[activeSim] = POWER_ON;
                        mRadioTaskList.add(1, new RadioTask(ops.clone(), task.mResponseMsgList));
                        task.mResponseMsgList = null;
                    }

                    Arrays.fill(ops, POWER_OFF);
                    ops[activeSim] = NONE;
                    mRadioTaskList.add(1, new RadioTask(ops, task.mResponseMsgList));

                    Rlog.d(TAG, "needReplaceRadioTask [2]: " + mRadioTaskList);
                    return true;
                }
            } else if (TelephonyManager.getDefault().isMultiSimEnabled() && activeSimCount == 0
                    && !TeleUtils.isRadioOn(primarySlot) && task.mOps[primarySlot] == POWER_ON
                    && task.mOps[secondarySlot] == POWER_OFF) {
                int[] ops = new int[mPhoneCount];
                ops[primarySlot] = POWER_ON;
                mRadioTaskList.add(1, new RadioTask(ops.clone(), task.mResponseMsgList));

                Arrays.fill(ops, POWER_OFF);
                ops[primarySlot] = NONE;
                mRadioTaskList.add(1, new RadioTask(ops));

                Rlog.d(TAG, "mutiSlot" + primarySlot + ", needReplaceRadioTask [3]: " + mRadioTaskList);
                return true;
            }
        }
        return false;
    }

    private int mRadioOperationCount = 0;

    private void handleRadioTask(RadioTask task) {
        Rlog.d(TAG, "handleRadioTask task = " + task.toString());
        if (needReplaceRadioTask(task)) {
            // Don't notify task owner, just drop it.
            task.mResponseMsgList = null;
            task.finish();
        } else if (task.mOps != null) {
            for (int phoneId = 0; phoneId < task.mOps.length; phoneId++) {
                if (task.mOps[phoneId] != NONE) {
                    boolean onOff = task.mOps[phoneId] == POWER_ON;
                    Phone phone = PhoneFactory.getPhone(phoneId);
                    //Modify for bug 625595
                    if (phone != null) {
                        if (TeleUtils.isRadioOn(phoneId) != onOff) {
                            synchronized (RadioController.class) {
                                mRadioOperationCount++;
                            }

                            final RadioInteraction ri = new RadioInteraction(phone);
                            ri.setCallBack(new Runnable() {
                                @Override
                                public void run() {
                                    ri.destoroy();

                                    synchronized (RadioController.class) {
                                        if (--mRadioOperationCount == 0) {
                                            task.finish();
                                        }
                                    }
                                }
                            });

                            if (onOff) {
                                ri.powerOnRadio(RadioInteraction.RADIO_POWER_ON_TIMEOUT);
                            } else {
                                ri.powerOffRadio(RadioInteraction.RADIO_POWER_OFF_TIMEOUT);
                            }
                        }
                    }
                }

            }
        }

        synchronized (RadioController.class) {
            if (mRadioOperationCount == 0) {
                task.finish();
            }
        }
    }

    private class RadioTask {
        private List<Message> mResponseMsgList = new ArrayList<Message>();
        private int[] mOps = null;

        RadioTask(int[] ops, Message response) {
            mOps = ops;
            if (response != null) {
                mResponseMsgList.add(response);
            }
        }

        RadioTask(int[] ops, List<Message> response) {
            mOps = ops;
            if (response != null && !response.isEmpty()) {
                mResponseMsgList.addAll(response);
            }
        }

        RadioTask(int[] ops) {
            mOps = ops;
        }

        void finish() {
            if (mResponseMsgList != null) {
                for (Message msg : mResponseMsgList) {
                    msg.sendToTarget();
                }
                mResponseMsgList = null;
            }

            RadioTask task = null;
            synchronized (mRadioTaskList) {
                mRadioTaskList.remove(this);

                if (!mRadioTaskList.isEmpty()) {
                    task = mRadioTaskList.get(0);
                }
            }

            if (task != null) {
                handleRadioTask(task);
            }
        }

        @Override
        public String toString() {
            String info = "";
            if(mResponseMsgList != null){
                info = mResponseMsgList.toString() + " : ";
            }
            StringBuilder stringBuilder= new StringBuilder(info);
            for (int i = 0; i < mOps.length; i++) {
                stringBuilder.append(mOps[i]);
            }
            return stringBuilder.toString();
        }
    }
}
