package com.android.internal.telephony;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.location.Criteria;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.Message;
import android.os.PowerManager;
import android.telephony.Rlog;
import android.text.TextUtils;
import android.util.Log;

/**
 * UNISOC: add for bug958569 on 18-11-2.
 */
public class EmcLocationManger {
    private static final String TAG = "EmcLocationManger";

    private static final int EVENT_START_REQUARE_LOCATION = 1;
    private static final int EVENT_REMOVE_REQUARE_LOCATION = 2;
    private static final int EVENT_BOOT_REQUARE_LOCATION = 3;

    PhoneLocation mPhoneLocation = new PhoneLocation();
    LocationManager mLocationManager = null;
    Location mSosLocation = null;
    boolean mStartRecordLocation = false;
    Context mContext;
    public static EmcLocationManger mInstance;

    public static EmcLocationManger getInstance(Context context) {
        return mInstance;
    }

    public static EmcLocationManger init(Context context) {
        if (mInstance == null) {
            mInstance = new EmcLocationManger(context);
        }
        return mInstance;
    }

    private EmcLocationManger(Context context){
        mContext = context;

        Rlog.d(TAG,"create EmcLocationManger");
        IntentFilter filter = new IntentFilter();
        filter.addAction(Intent.ACTION_SCREEN_ON);
        filter.addAction(Intent.ACTION_SCREEN_OFF);
        filter.addAction(Intent.ACTION_BOOT_COMPLETED);
        filter.addAction(LocationManager.PROVIDERS_CHANGED_ACTION);
        mContext.registerReceiver(mBroadcastReceiverL, filter);
        startReceivingLocationUpdates();
    }

    public Location getSosLocation(){
        return mSosLocation;
    }

    Handler handler = new Handler() {
        @Override
        public void handleMessage(Message msg) {

            switch (msg.what) {
                case EVENT_START_REQUARE_LOCATION:
                    startReceivingLocationUpdates();
                    break;
                case EVENT_REMOVE_REQUARE_LOCATION:
                    stopReceivingLocationUpdates();
                    break;
                case EVENT_BOOT_REQUARE_LOCATION:
                    startReceivingAfterBoot();
                    break;
            }
        }
    };

    public void startReceivingLocationUpdates() {

        if (mLocationManager == null) {
            mLocationManager = (LocationManager) (mContext.getSystemService(Context.LOCATION_SERVICE));
            Rlog.i(TAG, "startReceivingLocationUpdates mLocationManager " + mLocationManager);
        }

        String provider = null;

        if (mLocationManager != null ) {

            String bestProvider = mLocationManager.getBestProvider(new Criteria(), true);
            if (mSosLocation == null && !TextUtils.isEmpty(bestProvider)) {
                mSosLocation = mLocationManager.getLastKnownLocation(bestProvider);
            }

            if(mLocationManager.isProviderEnabled(LocationManager.NETWORK_PROVIDER)){
                provider = LocationManager.NETWORK_PROVIDER;
            }else if(mLocationManager.isProviderEnabled(LocationManager.PASSIVE_PROVIDER)){
                provider = LocationManager.PASSIVE_PROVIDER;
            }
            Rlog.d(TAG, "startReceivingLocationUpdates: provider = " + provider+ " mSosLocation = "+mSosLocation);
            if(provider != null){
                try {
                    mLocationManager.requestLocationUpdates(
                            provider,
                            30*1000,
                            0F,
                            mPhoneLocation);
                } catch (SecurityException ex) {
                    Rlog.d(TAG, "fail to request location update, ignore" + ex.getMessage());
                } catch (IllegalArgumentException ex) {
                    Rlog.d(TAG, "provider does not exist " + ex.getMessage());
                } catch (RuntimeException ex) {
                    Rlog.d(TAG, "the calling thread has no Looper " + ex.getMessage());
                }
                mStartRecordLocation = true;
                Rlog.d(TAG, "startReceivingLocationUpdates mStartRecordLocation: " + mStartRecordLocation);
            }else{
                Rlog.d(TAG, "locationProvider does not exit");
            }
        } else {
            Rlog.d(TAG, "LocationManager does not exit mLocationManager = " + mLocationManager);
        }
    }

    public void stopReceivingLocationUpdates() {
        Log.v(TAG, "stopping location updates");
        if (mLocationManager != null) {
            try {
                mLocationManager.removeUpdates(mPhoneLocation);
            } catch (Exception ex) {
                Log.i(TAG, "fail to remove location listners, ignore", ex);
            }
            mStartRecordLocation = false;
            mSosLocation = null;
            Rlog.d(TAG, "stopReceivingLocationUpdates");
        }
    }

    private void startReceivingAfterBoot() {

        PowerManager powerManager = (PowerManager) mContext.getSystemService(Context.POWER_SERVICE);
        if (powerManager != null) {
            boolean isOpen = powerManager.isScreenOn();
            if (isOpen) {
                startReceivingLocationUpdates();
            }
        }
    }

    private BroadcastReceiver mBroadcastReceiverL = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            Rlog.d(TAG, "mBroadcastReceiver: action " + intent.getAction());

            if (intent.getAction().equals(Intent.ACTION_SCREEN_OFF)) {
                if (mStartRecordLocation) {
                    //remove Location Updates
                    handler.sendMessage(handler.obtainMessage(EVENT_REMOVE_REQUARE_LOCATION));
                }
            } else {
                Message mess = null;
                if (intent.getAction().equals(Intent.ACTION_SCREEN_ON)) {
                    mess = handler.obtainMessage(EVENT_START_REQUARE_LOCATION);
                } else if (intent.getAction().equals(LocationManager.PROVIDERS_CHANGED_ACTION)
                        || intent.getAction().equals(Intent.ACTION_BOOT_COMPLETED)) {
                    mess = handler.obtainMessage(EVENT_BOOT_REQUARE_LOCATION);
                }
                if (!mStartRecordLocation) {
                    //send message to request Location Updates
                    handler.sendMessage(mess);
                } else {
                    if (handler.hasMessages(EVENT_REMOVE_REQUARE_LOCATION)) {
                        handler.removeMessages(EVENT_REMOVE_REQUARE_LOCATION);
                    }
                }
            }
        }
    };

    class PhoneLocation implements LocationListener {
        @Override
        public void onLocationChanged(Location location) {
            Rlog.d(TAG,"onLocationChanged :" + location);
            mSosLocation = location;
        }

        @Override
        public void onStatusChanged(String provider, int status, Bundle extras) {
            // Do nothing.
            Rlog.d(TAG,"onStatusChanged :" + provider);
        }

        @Override
        public void onProviderEnabled(String provider) {
            // Do nothing.
            Rlog.d(TAG,"onProviderEnabled :" + provider);
        }

        @Override
        public void onProviderDisabled(String provider) {
            // Do nothing.
            Rlog.d(TAG,"onProviderDisabled :" + provider);
        }
    }
}