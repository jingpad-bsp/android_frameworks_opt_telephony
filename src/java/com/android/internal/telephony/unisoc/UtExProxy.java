package com.android.internal.telephony;

import java.util.HashMap;

import com.android.ims.internal.IImsUt;
import com.android.ims.ImsUt;
import android.telephony.ims.ImsCallForwardInfo;
import com.android.ims.ImsException;
import android.telephony.ims.ImsReasonInfo;
import android.telephony.ims.ImsSsInfo;
import android.os.RemoteException;
import android.util.Log;
import android.content.Context;
import android.os.IBinder;
import android.os.Message;
import android.os.Bundle;
import android.os.ServiceManager;
import android.os.AsyncResult;
import com.android.internal.telephony.CommandException;
import com.android.internal.telephony.CommandException.Error;
import com.android.ims.internal.ImsManagerEx;
import com.android.ims.internal.IImsUtListenerEx;
import com.android.ims.internal.IImsUtEx;
import com.android.ims.internal.ImsCallForwardInfoEx;

public class UtExProxy {

    private static final String LOG_TAG = "UtExProxy";
    // For synchronization of private variables
    private Object mLockObj = new Object();
    private HashMap<Integer, Message> mPendingCmds =
            new HashMap<Integer, Message>();

    private IImsUtEx mIImsUtEx;
    //TODO:get correct phone id
    private int mPhoneId = 0;
    private static UtExProxy mUtExProxy = null;

    private UtExProxy(){
    }

    public static synchronized UtExProxy getInstance() {
        if (mUtExProxy == null) {
            mUtExProxy = new UtExProxy();
        }
        return mUtExProxy;
    }

    public void setPhoneId(int phoneId) {
        if (mPhoneId != phoneId) {
            mPhoneId = phoneId;
            mIImsUtEx = null;
        }
    }

    private IImsUtEx getIImsUtEx() {
        if (mIImsUtEx == null) {
            mIImsUtEx = ImsManagerEx.getIImsUtEx();
            if (mIImsUtEx != null) {

                try {
                    mIImsUtEx.setListenerEx(mPhoneId, mImsUtListenerExBinder);
                } catch (RemoteException e) {
                }
            }
        }
        return mIImsUtEx;
    }

    public void setCallForwardingOption(int commandInterfaceCFAction,
            int commandInterfaceCFReason,int serviceClass, String dialingNumber,
            int timerSeconds, String ruleSet, Message result){
        synchronized(mLockObj) {
            try {
                int id = getIImsUtEx().setCallForwardingOption(mPhoneId, commandInterfaceCFAction,
                        commandInterfaceCFReason, serviceClass, dialingNumber,
                        timerSeconds, ruleSet);

                if (id < 0) {
                    sendFailureReport(result,
                            new ImsReasonInfo(ImsReasonInfo.CODE_UT_SERVICE_UNAVAILABLE, 0));
                    return;
                }

                mPendingCmds.put(Integer.valueOf(id), result);
            } catch (RemoteException e) {
                sendFailureReport(result,
                        new ImsReasonInfo(ImsReasonInfo.CODE_UT_SERVICE_UNAVAILABLE, 0));
            }
        }
    }

    public void getCallForwardingOption(int commandInterfaceCFReason, int serviceClass,
            String ruleSet, Message result) {
        synchronized(mLockObj) {
            try {
                int id = getIImsUtEx().getCallForwardingOption(mPhoneId, commandInterfaceCFReason, serviceClass,
                        ruleSet);
                if (id < 0) {
                    sendFailureReport(result,
                            new ImsReasonInfo(ImsReasonInfo.CODE_UT_SERVICE_UNAVAILABLE, 0));
                    return;
                }

                mPendingCmds.put(Integer.valueOf(id), result);
            } catch (RemoteException e) {
                sendFailureReport(result,
                        new ImsReasonInfo(ImsReasonInfo.CODE_UT_SERVICE_UNAVAILABLE, 0));
            }
        }
    }

    public void changeBarringPassword(String facility, String oldPwd, String newPwd,
            Message result) {
        synchronized(mLockObj) {
            try {
                int id = getIImsUtEx().changeBarringPassword(mPhoneId, facility, oldPwd,
                        newPwd);
                if (id < 0) {
                    sendFailureReport(result, null, Error.GENERIC_FAILURE.ordinal());
                    return;
                }

                mPendingCmds.put(Integer.valueOf(id), result);
            } catch (RemoteException e) {
                sendFailureReport(result,
                        new ImsReasonInfo(ImsReasonInfo.CODE_UT_SERVICE_UNAVAILABLE, 0));
            }
        }
    }

    public void setFacilityLock(String facility, boolean lockState, String password,
            int serviceClass, Message result) {
        synchronized(mLockObj) {
            try {
                int id = getIImsUtEx().setFacilityLock(mPhoneId, facility, lockState,
                        password, serviceClass);
                if (id < 0) {
                    sendFailureReport(result, null, Error.GENERIC_FAILURE.ordinal());
                    return;
                }

                mPendingCmds.put(Integer.valueOf(id), result);
            } catch (RemoteException e) {
                sendFailureReport(result,
                        new ImsReasonInfo(ImsReasonInfo.CODE_UT_SERVICE_UNAVAILABLE, 0));
            }
        }
    }

    public void queryFacilityLock(String facility, String password, int serviceClass,
            Message result) {
        synchronized(mLockObj) {
            try {
                int id = getIImsUtEx().queryFacilityLock(mPhoneId, facility, password,
                        serviceClass);
                if (id < 0) {
                    sendFailureReport(result, null, Error.GENERIC_FAILURE.ordinal());
                    return;
                }

                mPendingCmds.put(Integer.valueOf(id), result);
            } catch (RemoteException e) {
                sendFailureReport(result,
                        new ImsReasonInfo(ImsReasonInfo.CODE_UT_SERVICE_UNAVAILABLE, 0));
            }
        }
    }

    public void queryRootNode(Message result) {
        synchronized(mLockObj) {
            try {
                int id = getIImsUtEx().queryRootNode(mPhoneId);
                if (id < 0) {
                    sendFailureReport(result, null, Error.GENERIC_FAILURE.ordinal());
                    return;
                }

                mPendingCmds.put(Integer.valueOf(id), result);
            } catch (RemoteException e) {
                sendFailureReport(result,
                        new ImsReasonInfo(ImsReasonInfo.CODE_UT_SERVICE_UNAVAILABLE, 0));
            }
        }
    }

    private final IImsUtListenerEx.Stub mImsUtListenerExBinder = new IImsUtListenerEx.Stub(){
        /**
         * Notifies the result of the supplementary service configuration udpate.
         */
        @Override
        public void utConfigurationUpdated(IImsUt ut, int id) {
            Integer key = Integer.valueOf(id);

            synchronized(mLockObj) {
                sendSuccessReport(mPendingCmds.get(key));
                mPendingCmds.remove(key);
            }
        }

        @Override
        public void utConfigurationUpdateFailed(IImsUt ut, int id, ImsReasonInfo error) {
            Integer key = Integer.valueOf(id);

            synchronized(mLockObj) {
                sendFailureReport(mPendingCmds.get(key), error);
                mPendingCmds.remove(key);
            }
        }

        /**
         * Notifies the result of the supplementary service configuration query.
         */
        @Override
        public void utConfigurationQueried(IImsUt ut, int id, Bundle ssInfo) {
            Integer key = Integer.valueOf(id);

            synchronized(mLockObj) {
                sendSuccessReport(mPendingCmds.get(key), ssInfo);
                mPendingCmds.remove(key);
            }
        }

        @Override
        public void utConfigurationQueryFailed(IImsUt ut, int id, ImsReasonInfo error) {
            Integer key = Integer.valueOf(id);

            synchronized(mLockObj) {
                sendFailureReport(mPendingCmds.get(key), error);
                mPendingCmds.remove(key);
            }
        }

        /**
         * Notifies the status of the call barring supplementary service.
         */
        @Override
        public void utConfigurationCallBarringQueried(IImsUt ut,
                int id, ImsSsInfo[] cbInfo) {
            Integer key = Integer.valueOf(id);

            synchronized(mLockObj) {
                sendSuccessReport(mPendingCmds.get(key), cbInfo);
                mPendingCmds.remove(key);
            }
        }

        /**
         * Notifies the status of the call forwarding supplementary service.
         */
        @Override
        public void utConfigurationCallForwardQueried(IImsUt ut,
                int id, ImsCallForwardInfoEx[] cfInfo) {
            Integer key = Integer.valueOf(id);

            synchronized(mLockObj) {
                sendSuccessReport(mPendingCmds.get(key), cfInfo);
                mPendingCmds.remove(key);
            }
        }

        /**
         * Notifies the status of the call waiting supplementary service.
         */
        @Override
        public void utConfigurationCallWaitingQueried(IImsUt ut,
                int id, ImsSsInfo[] cwInfo) {
            Integer key = Integer.valueOf(id);

            synchronized(mLockObj) {
                sendSuccessReport(mPendingCmds.get(key), cwInfo);
                mPendingCmds.remove(key);
            }
        }

        /**
         * Notifies the status of the call barring supplementary service.
         */
        @Override
        public void utConfigurationCallBarringFailed(int id, int[] result, int errorCode) {
            Integer key = Integer.valueOf(id);
            synchronized(mLockObj) {
                sendFailureReport(mPendingCmds.get(key), result, errorCode);
                mPendingCmds.remove(key);
            }
        }

        /**
         * Notifies the status of the call barring supplementary service.
         */
        @Override
        public void utConfigurationCallBarringResult(int id, int[] result) {
            Integer key = Integer.valueOf(id);
            synchronized(mLockObj) {
                sendSuccessReport(mPendingCmds.get(key), result);
                mPendingCmds.remove(key);
            }
        }
    };

    private void sendFailureReport(Message result, ImsReasonInfo error) {
        if (result == null || error == null) {
            return;
        }

        String errorString;
        // If ImsReasonInfo object does not have a String error code, use a
        // default error string.
        if (error.mExtraMessage == null) {
            errorString = new String("IMS UT exception");
        }
        else {
            errorString = new String(error.mExtraMessage);
        }

        AsyncResult.forMessage(result, null, getCommandException(new ImsException(errorString, error.mCode)));
        result.sendToTarget();
    }

    private void sendSuccessReport(Message result) {
        if (result == null) {
            return;
        }

        AsyncResult.forMessage(result, null, null);
        result.sendToTarget();
    }

    private void sendSuccessReport(Message result, Object ssInfo) {
        if (result == null) {
            return;
        }

        AsyncResult.forMessage(result, ssInfo, null);
        result.sendToTarget();
    }

    private void sendFailureReport(Message result, Object ssInfo, int errorCode) {
        if (result == null) {
            return;
        }

        AsyncResult.forMessage(result, ssInfo,
                CommandException.fromRilErrno(errorCode));
        result.sendToTarget();
    }

    private CommandException getCommandException(Throwable e) {
        CommandException ex = null;

        if (e instanceof ImsException) {
            ex = getCommandException(((ImsException)e).getCode(), e.getMessage());
        } else {
            Log.d(LOG_TAG,"getCommandException generic failure");
            ex = new CommandException(CommandException.Error.GENERIC_FAILURE);
        }
        return ex;
    }

    private CommandException getCommandException(int code, String errorString) {
        Log.d(LOG_TAG,"getCommandException code= " + code + ", errorString= " + errorString);
        CommandException.Error error = CommandException.Error.GENERIC_FAILURE;

        switch(code) {
            case ImsReasonInfo.CODE_UT_NOT_SUPPORTED:
                error = CommandException.Error.REQUEST_NOT_SUPPORTED;
                break;
            case ImsReasonInfo.CODE_UT_CB_PASSWORD_MISMATCH:
                error = CommandException.Error.PASSWORD_INCORRECT;
                break;
            case ImsReasonInfo.CODE_UT_SERVICE_UNAVAILABLE:
                error = CommandException.Error.RADIO_NOT_AVAILABLE;
                break;
            case ImsReasonInfo.CODE_FDN_BLOCKED:
                error = CommandException.Error.FDN_CHECK_FAILURE;
                break;
            default:
                break;
        }

        return new CommandException(error, errorString);
    }

}
