package com.android.internal.telephony;

import android.app.ActivityThread;
import android.content.Context;
import android.content.res.Resources;
import android.content.res.Resources.NotFoundException;
import android.provider.Settings;
import android.provider.SettingsEx;
import android.telephony.SubscriptionInfo;
import android.text.TextUtils;
import android.util.Log;

public class TeleUtils {
    private static final String LOG_TAG = "TeleUtils";

    /**
     * Converts a byte array into a integer value.
     *
     * @param bytes an array of bytes
     *
     * @return integer value of bytes array
     */
    public static int bytesToInt(byte[] data) {
        if (data == null) {
            return -1;
        }
        int value = 0;
        for (int i = 0; i < data.length; i++) {
            value |= (data[i] & 0xFF) << ((data.length - i - 1) * 8);
        }
        return value;
    }

    /**
     * Converts  a integer value into a byte array.
     *
     * @param integer value ,the length of bytes
     *
     * @return bytes array
     */
    public static byte[] intToBytes(int value, int len) {
        byte[] data = new byte[len];

        for (int i = 0; i < len; i++) {
            data[i] = (byte) ((value >> ((len - i - 1) * 8)) & 0xFF);
        }
        return data;
    }

    public static boolean isAirplaneModeOn(Context context) {
        return Settings.Global.getInt(context.getContentResolver(),
                Settings.Global.AIRPLANE_MODE_ON, 0) != 0;
    }

    public static void setRadioBusy(Context context, boolean busy) {
        Settings.Global.putInt(context.getContentResolver(), SettingsEx.GlobalEx.RADIO_BUSY,
                busy ? 1 : 0);
    }

    public static boolean isRadioBusy(Context context) {
        return Settings.Global.getInt(context.getContentResolver(), SettingsEx.GlobalEx.RADIO_BUSY,
                0) == 1;
    }

    public static boolean isRadioOn(int phoneId) {
        final Phone phone = PhoneFactory.getPhone(phoneId);
        if (phone != null) {
            // XXX Ignore RadioState.RADIO_UNAVAILABLE
            return phone.isRadioOn();
        }
        return false;
    }

    public static boolean isMultiModeSlot(int phoneId) {
        final Phone phone = PhoneFactory.getPhone(phoneId);
        if (phone != null) {
            return phone.getRadioAccessFamily() == ProxyController.getInstance()
                    .getMaxRafSupported();
        }
        return false;
    }

    public static boolean hasIccCard(int phoneId) {
        Phone phone = PhoneFactory.getPhone(phoneId);
        if (phone != null) {
            return phone.getIccCard().hasIccCard();
        }
        return false;
    }

    /**
     * Do not use {@code PhoneFactory.getPhone(phoneId).getIccSerialNumber()}
     * because it will return null when SIM is locked.
     * {@link SubscriptionInfoUpdater} will load ICCID additionally and update
     * database when all ICCIDs are loaded. So use {@link SubscriptionInfo} to
     * get ICCID instead.
     */
    public static String getIccId(int phoneId) {
        SubscriptionController controller = SubscriptionController.getInstance();
        SubscriptionInfo subInfo = controller.getActiveSubscriptionInfoForSimSlotIndex(phoneId,
                ActivityThread.currentApplication().getOpPackageName());
        if (subInfo != null && controller
                .getSimStateForSlotIndex(phoneId) != IccCardConstants.State.ABSENT.ordinal()) {
            return subInfo.getIccId();
        }
        return null;
    }

    /**
     * @param numericPlmn This value is an operator numeric
     * @param oldName This value is an operator name
     * @return expected operator name
     */
    public static String translateOperatorName(String numericPlmn,String oldName) {
        /* To handle the special case: the operator APGT's SIM with the prefix IMSI
         * 46605 camping on the 46697 operator network have to display the operator name GT 4G R,
         * but, could't be considered roaming with the roaming icon. @{ */
        if ("46697".equals(numericPlmn) && ("GT 4G R".equals(oldName) || "GT R".equals(oldName))) {
            Log.d(LOG_TAG, " Not translated GT operator name");
            return oldName;
        }
        /* @} */
        Resources r = Resources.getSystem();
        String newName = oldName;
        Log.d(LOG_TAG, " translateOperatorName: old name= " + oldName);
        try {
            int identify = r.getIdentifier("unisoc_local_config_operator", "array", "android");
            String itemList[] = r.getStringArray(identify);
            Log.d(LOG_TAG, " translateOperatorName: itemList length is " + itemList.length);
            for (String item : itemList) {
                String parts[] = item.split(",");
                if (parts[0].equalsIgnoreCase(numericPlmn)) {
                    newName = parts[1];
                    Log.d(LOG_TAG, "itemList found: parts[0]= " + parts[0] +
                            " parts[1]= " + parts[1] + "  newName= " + newName);
                    return newName;
                }
            }
        } catch (NotFoundException e) {
            Log.e(LOG_TAG, "Error, string array resource ID not found: unisoc_local_config_operator");
        }
        Log.d(LOG_TAG, "translateOperatorName not found: numeric plmn = " + numericPlmn + " newName= " + newName);
        return newName;
    }
}
