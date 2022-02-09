package com.android.internal.telephony.dataconnection;

import android.app.AlertDialog;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.res.Resources;
import android.content.ServiceConnection;
import android.os.IBinder;
import android.telephony.DataFailCause;
import android.telephony.TelephonyManager;
import android.util.Log;
import android.view.WindowManager;
import android.text.TextUtils;

import com.android.internal.R;
import com.android.sprd.telephony.RadioInteractor;

import java.util.ArrayList;

public class DataConnectionUtils {

    private static DataConnectionUtils mInstance;
    private static final String LOG_TAG = "DataConnectionUtils";
    private static final String RI_SERVICE_NAME =
            "com.android.sprd.telephony.server.RADIOINTERACTOR_SERVICE";
    private static final String RI_SERVICE_PACKAGE =
            "com.android.sprd.telephony.server";

    private static final String VOLTE_CLEAR_CODE = "volte";
    private static final String NON_VOLTE_CLEAR_CODE = "non-volte";

    public static final int RETRY_DELAY_LONG  = 45000;
    public static final int RETRY_DELAY_SHORT = 10000;
    public static final int RETRY_FROM_FAILURE_DELAY = 2 * 3600 * 1000; // 2 hours

    private Context mContext;
    private ArrayList<RadioInteractorCallback> mCallbacks =
            new ArrayList<RadioInteractorCallback>();
    protected RadioInteractor mRi;
    private String mClearCodeConfig = null;

    public interface RadioInteractorCallback {
        void onRiConnected(RadioInteractor ri);
    }

    public DataConnectionUtils() {
    }

    public static DataConnectionUtils getInstance(Context context) {
        if (mInstance == null) {
            mInstance = new DataConnectionUtils();
        }
        Log.d(LOG_TAG, "mInstance = " + mInstance);
        mInstance.init(context);
        return mInstance;
    }

    public void addRadioInteractorCallback(RadioInteractorCallback cb) {
        mCallbacks.add(cb);
        if (mRi != null) {
            cb.onRiConnected(mRi);
        }
    }

    public void removeRadioInteractorCallback(RadioInteractorCallback cb) {
        mCallbacks.remove(cb);
    }

    protected void init(Context context) {
        mContext = context;
            // bind to radio interactor service
            Intent serviceIntent = new Intent(RI_SERVICE_NAME);
            serviceIntent.setPackage(RI_SERVICE_PACKAGE);
            Log.d(LOG_TAG, "bind RI service");
            context.bindService(serviceIntent, mConnection, Context.BIND_AUTO_CREATE);
    }

    private ServiceConnection mConnection = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName name, IBinder service) {
            Log.d(LOG_TAG, "RadioInteractor service connected: service=" + service);
            mRi = new RadioInteractor(mContext);
            for (RadioInteractorCallback cb : mCallbacks) {
                cb.onRiConnected(mRi);
            }
            onRadioInteractorConnected(mRi);
        }

        @Override
        public void onServiceDisconnected(ComponentName name) {
            mRi = null;
        }
    };

    protected void onRadioInteractorConnected(RadioInteractor ri) {
        if(supportSpecialClearCode()){
            final RadioInteractor radioInteractor = ri;
            new Thread() {
                public void run() {
                    TelephonyManager telMgr = TelephonyManager.from(mContext);
                    int numPhone = telMgr.getPhoneCount();
                    for (int i = 0; i < numPhone; i++) {
                        radioInteractor.enableRauNotify(i);
                    }
                    Log.d(LOG_TAG, "enableRauNotify() done");
                }
            }.start();
        }
    }

    public boolean supportSpecialClearCode() {
        mClearCodeConfig = Resources.getSystem().getString(com.android.internal.R.string.config_dataconnection_data_clear_code);
        Log.d(LOG_TAG, "supportSpecialClearCode = " + mClearCodeConfig);
        return (isVolteClearCode() || isNonVolteClearCode());
    }

    public boolean isVolteClearCode() {
        if (!TextUtils.isEmpty(mClearCodeConfig) && VOLTE_CLEAR_CODE.equals(mClearCodeConfig)){
             return true;
        }
        return false;
    }

    public boolean isNonVolteClearCode() {
        if (!TextUtils.isEmpty(mClearCodeConfig) && NON_VOLTE_CLEAR_CODE.equals(mClearCodeConfig)){
             return true;
        }
        return false;
    }

    public boolean isSpecialCode(int fc) {
        if(supportSpecialClearCode()){
            return fc == DataFailCause.USER_AUTHENTICATION
                || fc == DataFailCause.SERVICE_OPTION_NOT_SUBSCRIBED;
        }
        return false;
    }

    public AlertDialog getErrorDialog(int cause) {
        if(supportSpecialClearCode()){
            int res = -1;
            if (cause == DataFailCause.USER_AUTHENTICATION) {
                res = com.android.internal.R.string.failcause_user_authentication;
            } else if (cause == DataFailCause.SERVICE_OPTION_NOT_SUBSCRIBED) {
                res = com.android.internal.R.string.failcause_service_option_not_subscribed;
            }

            if (res == -1) {
                return null;
            }

            AlertDialog dialog = new AlertDialog.Builder(mContext,
                    AlertDialog.THEME_DEVICE_DEFAULT_LIGHT)
                    .setMessage(res)
                    .setPositiveButton(android.R.string.ok, null)
                     .create();
            dialog.getWindow().setType(WindowManager.LayoutParams.TYPE_SYSTEM_ALERT);
            dialog.setCanceledOnTouchOutside(false);

            return dialog;
        }
        return null;
    }

    public boolean isFndEnableSupport() {
        boolean isSupport = false;
        isSupport = Resources.getSystem().getBoolean(com.android.internal.R.bool.config_support_fdn_no_data);
        Log.d(LOG_TAG, "isFndEnableSupport = " + isSupport);
        return isSupport;
    }
    public String getFndNumForData() {
        String number = null;
        number = Resources.getSystem().getString(com.android.internal.R.string.config_fdn_num_for_data);
        Log.d(LOG_TAG, "getFndNumForData = " + number);
        return number;
    }
}
