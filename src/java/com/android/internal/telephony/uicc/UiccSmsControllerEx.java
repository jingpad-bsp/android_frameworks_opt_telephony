package com.android.internal.telephony;

import android.util.Log;
import android.os.ServiceManager;
import android.os.RemoteException;
import android.os.Handler;
import android.os.Looper;
import android.os.Message;
import android.os.AsyncResult;
import android.os.SystemProperties;
import android.telephony.Rlog;

import com.android.internal.telephony.ISmsEx;
import com.android.internal.telephony.Phone;
import com.android.internal.telephony.PhoneFactory;

import java.lang.ArrayIndexOutOfBoundsException;
import java.lang.NullPointerException;
import java.util.List;

import com.android.sprd.telephony.RadioInteractor;

import android.app.ActivityThread;
import com.android.internal.telephony.SubscriptionController;
import android.telephony.SubscriptionManager;

/**
 * {@hide}
 */
public class UiccSmsControllerEx extends ISmsEx.Stub {
    private static final String TAG = "UiccSmsControllerEx";

    private Phone[] mPhone;

    /* only one UiccSmsControllerEx exists */
    public UiccSmsControllerEx(Phone[] phone) {
        if (ServiceManager.getService("ismsEx") == null) {
            ServiceManager.addService("ismsEx", this);
        }
        mPhone = phone;
    }

    /**
     * get Icc Sms  interface manager object based on subscription.
     **/
    private IccSmsInterfaceManager getIccSmsInterfaceManager(int subId) {

        int phoneId = SubscriptionController.getInstance().getPhoneId(subId);
        try {
            return mPhone[phoneId].getIccSmsInterfaceManager();
        } catch (NullPointerException e) {
            Rlog.e(TAG, "Exception is :" + e.toString() + " For subscription :" + subId);
            e.printStackTrace(); // To print stack trace
            return null;
        } catch (ArrayIndexOutOfBoundsException e) {
            Rlog.e(TAG, "Exception is :" + e.toString() + " For subscription :" + subId);
            e.printStackTrace();
            return null;
        }
    }
    
  //method to send AT command. bug 489257
    public boolean setCellBroadcastSmsConfig(IccSmsInterfaceManager iccSmsIntMgr, long subId, int[] data) {
        if (iccSmsIntMgr != null) {
            Rlog.d(TAG, "setCellBroadcastSmsConfig...");
            return iccSmsIntMgr.setCellBroadcastSmsConfig(data);
        } else {
            Rlog.e(TAG, "Disabled channel iccSmsIntMgr is null for"
                    + " Subscription: " + subId);
        }
        return false;
    }
    
  //a common Interface for app to call skip adding aidl bug 489257
    @Override
    public boolean setCellBroadcastConfig(int subId, int[] data){
        Rlog.d(TAG, "Enter setCellBroadcastConfig.");
        IccSmsInterfaceManager iccSmsIntMgr = getIccSmsInterfaceManager((int)subId);
        return setCellBroadcastSmsConfig(iccSmsIntMgr, subId, data);
    }

    /*
    only for Android9.0 and lower version.
     */
    @Override
    public boolean commonInterfaceForMessaging(int commonType, long szSubId, String szString, int[] data) {
        Rlog.d(TAG, "add this interface for Android9.0 and lower version.");
        return false;
    }

    @Override
    public int copyMessageToIccEfForSubscriber(int subId, String callingPackage, int status,
                                                   byte[] pdu, byte[] smsc) throws android.os.RemoteException {
        IccSmsInterfaceManager iccSmsIntMgr = getIccSmsInterfaceManager(subId);
        if (iccSmsIntMgr != null) {
            return iccSmsIntMgr.copyMessageToIccEfSprd(callingPackage, status, pdu, smsc);
        } else {
            Rlog.e(TAG,"copyMessageToIccEfForSubscriber iccSmsIntMgr is null" +
                    " for Subscription: " + subId);
            return 0;
        }
    }

    // added by tony for mms upgrade
    @Override
    public String getSimCapacityForSubscriber(int subId){
        // need to use RadioInterface
        RadioInteractor radioInteractor = new RadioInteractor(ActivityThread.currentApplication().getApplicationContext());
        int phoneId = SubscriptionController.getInstance().getPhoneId(subId) ;
        //Fixme: for multi-subscription case
        if (!SubscriptionManager.isValidPhoneId(phoneId)
                || phoneId == SubscriptionManager.DEFAULT_PHONE_INDEX) {
            phoneId = 0;
        }
        String capacity = radioInteractor.getSimCapacity(phoneId);
        return capacity;
    }
    @Override
    public void setCMMSForSubscriber(int subId, int value){

    }
    @Override
    public void setPropertyForSubscriber(int subId, String key,String value){
        SystemProperties.set(key, String.valueOf(value));
    }

    ////UNISOC: Bug892217 begin: get/set smsc number
    @Override
    public String getSmscForSubscriber(int subId) {
        //if (!canReadPhoneState(callingPackage, "getSmscForSubscriber")) {
        //    return null;
        //}
        final int phoneId = SubscriptionController.getInstance().getPhoneId(subId);
        Phone phone = PhoneFactory.getPhone(phoneId);
        if (phone != null) {
            final GetSetSMSC getSMSC = new GetSetSMSC(phone, null);
            getSMSC.start();
            return getSMSC.getSmsc();
        }
        return null;
    }

    @Override
    public boolean setSmscForSubscriber(int subId, String smscAddr) {
        //enforceModifyPermission();
        final int phoneId = SubscriptionController.getInstance().getPhoneId(subId);
        Phone phone = PhoneFactory.getPhone(phoneId);
        if (phone != null ) {
            final GetSetSMSC getSMSC = new GetSetSMSC(phone, smscAddr);
            getSMSC.start();
            return getSMSC.setSmsc();
        }
        return false;
    }

    private static class GetSetSMSC extends Thread {
        // For async handler to identify request type
        private static final int QUERY_SMSC_DONE = 100;
        private static final int UPDATE_SMSC_DONE = 101;

        private final Phone mPhone;
        private final String mSmscStr;
        private boolean mDone = false;
        private String mResult;
        private boolean bResult = false;
        private Handler mHandler;

        public GetSetSMSC(Phone phone, String SmscStr) {
            mPhone = phone;
            mSmscStr = SmscStr;
        }

        @Override
        public void run() {
            Looper.prepare();
            synchronized (GetSetSMSC.this) {
                mHandler = new Handler() {
                    @Override
                    public void handleMessage(Message msg) {
                        AsyncResult ar = (AsyncResult) msg.obj;
                        switch (msg.what) {
                            case QUERY_SMSC_DONE:
                                synchronized (GetSetSMSC.this) {
                                    if (ar.exception == null) {
                                        mResult = (String) ar.result;
                                    } else {
                                        // SPRD: modify for voWifi
                                        mResult = "refresh error";
                                    }
                                    Log.d(TAG, "[GetSetSMSC] mResult: " + mResult);
                                    mDone = true;
                                    GetSetSMSC.this.notifyAll();
                                }
                                getLooper().quit();
                                break;
                            case UPDATE_SMSC_DONE:
                                synchronized (GetSetSMSC.this) {
                                    bResult = ar.exception == null;
                                    Log.d(TAG, "[GetSetSMSC] bResult: " + bResult);
                                    mDone = true;
                                    GetSetSMSC.this.notifyAll();
                                }
                                getLooper().quit();
                                break;
                        }
                    }
                };
                GetSetSMSC.this.notifyAll();
            }
            Looper.loop();
        }

        synchronized String getSmsc() {
            while (mHandler == null) {
                try {
                    wait();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }

            mPhone.getSmscAddress(mHandler.obtainMessage(QUERY_SMSC_DONE));

            while (!mDone) {
                try {
                    Log.d(TAG, "[GetSetSMSC] wait get for done");
                    wait();
                } catch (InterruptedException e) {
                    // Restore the interrupted status
                    Thread.currentThread().interrupt();
                }
            }
            Log.d(TAG, "[GetSetSMSC] getSmsc: " + mResult);
            return mResult;
        }

        synchronized boolean setSmsc() {

            while (mHandler == null) {
                try {
                    wait();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }

            mPhone.setSmscAddress(mSmscStr, mHandler.obtainMessage(UPDATE_SMSC_DONE));

            while (!mDone) {
                try {
                    Log.d(TAG, "[GetSetSMSC] wait set for done");
                    wait();
                } catch (InterruptedException e) {
                    // Restore the interrupted status
                    Thread.currentThread().interrupt();
                }
            }
            Log.d(TAG, "[GetSetSMSC] set done. result= " + bResult);
            return bResult;
        }
    }
    //UNISOC: Bug892217 end: get/set smsc number
}
