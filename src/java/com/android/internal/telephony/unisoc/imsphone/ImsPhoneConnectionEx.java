/*
 * Copyright (C) 2013 The Android Open Source Project
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
package com.android.internal.telephony.imsphone;

import java.util.List;

import com.android.internal.telephony.Call;
import com.android.internal.telephony.CallStateException;
import com.android.internal.telephony.Connection;
import com.android.internal.telephony.Phone;
import com.android.ims.ImsCall;
import android.telephony.Rlog;
import android.telephony.DisconnectCause;



public class ImsPhoneConnectionEx extends ImsPhoneConnection {

    public static final String LOG_TAG = "ImsPhoneConnectionEx";
    private boolean mIsMultiparty;
    private boolean mIsMtMultiparty;


    /** This is probably an MT call */
    public ImsPhoneConnectionEx(Phone phone, ImsCall imsCall, ImsPhoneCallTracker ct,
            ImsPhoneCall parent, boolean isUnknown){
        super(phone,imsCall,ct,parent,isUnknown);

    }

    /** This is an MO call, created when dialing */
    public ImsPhoneConnectionEx(Phone phone, String dialString, ImsPhoneCallTracker ct,
            ImsPhoneCall parent, boolean isEmergency) {
        super(phone,dialString,ct,parent,isEmergency);
    }

    /** Called when the connection has been disconnected */
    @Override
    public boolean onDisconnect(int cause) {
        Rlog.d(LOG_TAG, "onDisconnect: cause=" + cause+ " mCause=" + mCause);
        if (mCause != DisconnectCause.LOCAL|| cause == DisconnectCause.INCOMING_REJECTED) mCause = cause;
        return onDisconnect();
    }

    public boolean hangupImsCall() {
        int activeCallCount = 0;
        List<Connection> l;
        l = getCall().getConnections();;

        if (l.size() < 2) {
            return false;
        }

        for (int i = 0, s = l.size() ; i < s ; i++) {
            if (l.get(i).getState() == Call.State.ACTIVE && ! l.get(i).getCall().isMultiparty()) {
                activeCallCount ++ ;
            }
        }
        if (activeCallCount >= 2) {
            return true;
        }
        return false;
    }

    /**
     * SPRD: Fix bug#677465
     * Update if the call is a multiparty call.
     * @param imsCall
     * @return
     */
    public boolean updateMultiparty(ImsCall imsCall){
        if (imsCall == null) {
            return false;
        }
        Rlog.d(LOG_TAG, "updateMultiparty: mIsMultiparty = " + mIsMultiparty + " isMultiparty = " + imsCall.isMultiparty());
        boolean changed = (mIsMultiparty != imsCall.isMultiparty());
        mIsMultiparty = imsCall.isMultiparty();
        return changed;
    }

    /**
     * @return {@code true} if the {@link ImsPhoneConnection} or its media capabilities have been
     *     changed, and {@code false} otherwise.
     */
    public boolean update(ImsCall imsCall, ImsPhoneCall.State state) {
        boolean updateParent = false;
        if (state == ImsPhoneCall.State.ACTIVE) {
            if (getCall().getState().isRinging() || getCall() == getOwner().mBackgroundCall) {
                updateParent = true;
            }
        }else if (state == ImsPhoneCall.State.HOLDING){
            if(getCall() == getOwner().mForegroundCall){
                getCall().detach(this);
                changeParent(getOwner().mBackgroundCall);
                getCall().attach(this);
                updateParent = true;
            }
        }
            return super.update(imsCall, state) || updateParent;
    }

    public boolean updateMtMultiparty(ImsCall imsCall){
        if (imsCall == null) {
            return false;
        }
        boolean mtMultiparty = imsCall.getCallProfile().getCallExtraBoolean("is_mt_conf_call", false);
        Rlog.d(LOG_TAG, "updateMtMultiparty: mIsMtMultiparty = " + mIsMtMultiparty + " isMtMultiparty = " + mtMultiparty);
        boolean changed = (mIsMtMultiparty != mtMultiparty);
        mIsMtMultiparty = mtMultiparty;
        if (changed) {
            for (Listener l : mListeners) {
                l.onConnectionCapabilitiesChanged(getConnectionCapabilities());
            }
        }
        return changed;
    }

    public boolean isMtMultiparty () {
        return mIsMtMultiparty;
    }

    @Override
    public String getIndex () {
        ImsCall imsCall = getImsCall();
        if (imsCall != null && imsCall.getCallSession() != null) {
            return imsCall.getCallSession().getCallId();
        }
        return null;
    }
}
