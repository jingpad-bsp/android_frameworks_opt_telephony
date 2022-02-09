package com.android.internal.telephony;

import com.android.internal.telephony.EncodeException;
import com.android.internal.telephony.GsmAlphabetEx;

import android.app.ActivityManagerNative;
import android.content.Intent;
import android.content.res.Resources;
import android.os.UserHandle;
import android.util.Log;

public class TelePhonebookUtils {
    private static final String TAG = "TelePhonebookUtils";

    public static void broadcastFdnChangedDone(boolean iccFdnEnabled, boolean desiredFdnEnabled,
            int phoneId){
        if(iccFdnEnabled != desiredFdnEnabled){
            Log.e(TAG,"EVENT_CHANGE_FACILITY_FDN_DONE: " + desiredFdnEnabled + " phoneId " + phoneId);
            Intent intent = new Intent("android.fdnintent.action.FDN_STATE_CHANGED" + phoneId);
            intent.putExtra(PhoneConstants.PHONE_KEY, phoneId);
            intent.putExtra(TelephonyIntents.INTENT_KEY_FDN_STATUS, desiredFdnEnabled);
            //UNISOC: add for bug712193, Contacts can not receiver fdn status broadcast
            intent.addFlags(Intent.FLAG_RECEIVER_INCLUDE_BACKGROUND);
            ActivityManagerNative.broadcastStickyIntent(intent, null, UserHandle.USER_ALL);
         }
    }

    public static boolean isSupportOrange() {
        Log.e(TAG,"isSupportOrange = " + Resources.getSystem().getBoolean(com.android.internal.R.bool.config_supportorangeCapable));
        return Resources.getSystem().getBoolean(com.android.internal.R.bool.config_supportorangeCapable);
    }

    public static byte[] stringToGsmAlphaSS(String s)
            throws EncodeException {
        return GsmAlphabetEx.stringToGsmAlphaSS(s);
    }

    public static byte[] isAsciiStringToGsm8BitUnpackedField(String s)
            throws EncodeException {
        return GsmAlphabetEx.isAsciiStringToGsm8BitUnpackedField(s);
    }
}
