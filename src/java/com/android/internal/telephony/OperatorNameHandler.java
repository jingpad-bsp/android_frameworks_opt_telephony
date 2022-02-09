
package com.android.internal.telephony;

import com.android.sprd.telephony.aidl.IOperatorNameHandler;
import android.content.Context;
import android.content.res.Resources;
import android.os.Handler;
import android.os.PersistableBundle;
import android.os.SystemProperties;
import android.telephony.CellLocation;
import android.telephony.Rlog;
import android.telephony.SubscriptionManager;
import android.telephony.gsm.GsmCellLocation;
import android.text.TextUtils;
import android.util.Log;
import com.android.internal.telephony.SubscriptionController;
import com.android.internal.telephony.ServiceStateTracker;
import com.android.internal.telephony.Phone;
import com.android.internal.telephony.PhoneFactory;
import com.android.internal.telephony.OperatorInfo;
import android.os.ServiceManager;
import com.android.internal.telephony.uicc.ExtraIccRecords;
import com.android.internal.telephony.uicc.ExtraIccRecordsController;

import java.util.List;
import java.util.Arrays;

public class OperatorNameHandler extends IOperatorNameHandler.Stub {

    private static final boolean DBG = true;
    private static final String LOG_TAG = "OperatorNameHandler";

    private static Context mContext;
    private static OperatorNameHandler mInstance;

    public static OperatorNameHandler init(Context context) {
        synchronized (OperatorNameHandler.class) {
            if (mInstance == null) {
                mInstance = new OperatorNameHandler(context);
            } else {
                Log.wtf(LOG_TAG, "init() called multiple times!  mInstance = " + mInstance);
            }
            return mInstance;
        }
    }

    private OperatorNameHandler(Context context) {
        mContext = context;
        publish();
    }

    public static OperatorNameHandler getInstance() {
        if (mInstance == null) {
            Log.wtf(LOG_TAG, "getInstance null");
        }
        return mInstance;
    }

    /**
     * Update PLMN network name,try to get operator name from SIM if ONS exists or regplmn matched
     * OPL/PNN PLMN showed priorities: OPL/PNN > ONS(CPHS) > NITZ[ril implement] > numeric_operator.xml[ril implement] > mcc+mnc
     * See 3GPP TS 22.101 for details.
     */
    public String getHighPriorityPlmn(int phoneId, String mccmnc, int lac) {
        Rlog.d(LOG_TAG,
                "getHighPriorityPlmn for mccmnc " + mccmnc + " and lac " + lac);
        if (!SubscriptionManager.isValidPhoneId(phoneId) || TextUtils.isEmpty(mccmnc)
                || mccmnc.length() <= 3) {
            return mccmnc;
        }

        String highPriorityPlmn = "";
        ExtraIccRecords exIccRecords = ExtraIccRecordsController.getInstance()
                .getExtraIccRecords(phoneId);
        if (exIccRecords.isSimOplPnnSupport()) {
            // Try to get OPL/PNN
            if (lac == -1) {
                CellLocation cellLoc = getCellLocation(phoneId);
                if (cellLoc != null && cellLoc instanceof GsmCellLocation) {
                    lac = ((GsmCellLocation) cellLoc).getLac();
                }
                if (lac == -1) {
                    String tacCelloc = SystemProperties.get("persist.vendor.radio.sainfo", "");
                    Rlog.d(LOG_TAG, "tacCelloc = " + tacCelloc);
                    if (!tacCelloc.equals("")) {
                        String tac = tacCelloc.split(",")[0];
                        Rlog.d(LOG_TAG, "tac = " + tac);
                        if (tac != null) {
                            lac = Integer.parseInt(tac);
                        }
                    }
                }
                Rlog.d(LOG_TAG, "lac = " + lac);
            }
            highPriorityPlmn = exIccRecords.getPnn(mccmnc, lac);
        }

        if (TextUtils.isEmpty(highPriorityPlmn)) {
            // Try to get ONS
            highPriorityPlmn = exIccRecords.getSimOns(mccmnc);
            if (DBG) {
                Rlog.d(LOG_TAG,
                        "Didn't get pnn from sim, try ons next. ONS = " + highPriorityPlmn);
            }
            // Add for Bug1186158 Ignore ONS read from SIM
            highPriorityPlmn = plmnIgnoreOns(phoneId, highPriorityPlmn);
        }

        if (TextUtils.isEmpty(highPriorityPlmn)) {
            // Try to get NITZ operator name
            String propName = phoneId == 0 ? "vendor.ril.nitz.info"
                    : "vendor.ril.nitz.info" + phoneId;
            String nitzOperatorInfo = SystemProperties.get(propName);
            if (DBG)
                Rlog.d(LOG_TAG, "nitzOperatorInfo = " + nitzOperatorInfo);
            if (!TextUtils.isEmpty(nitzOperatorInfo)) {
                String[] nitzSubs = nitzOperatorInfo.split(",");
                if (nitzSubs.length == 3 && nitzSubs[2].equals(mccmnc)) {
                    highPriorityPlmn = nitzSubs[0];
                    // When long name is empty use short name
                    if (TextUtils.isEmpty(highPriorityPlmn) && !TextUtils.isEmpty(nitzSubs[1])) {
                        highPriorityPlmn = nitzSubs[1];
                    }
                    // Ignore operator name reported by NITZ
                    highPriorityPlmn = plmnIgnoreNitz(highPriorityPlmn, nitzSubs[2]);
                }
            }
        }
        /*UNISOC:Add for Bug949130, When the cards of cards 52001 and 52003 are registered in the 52015 network,
        the PLMN is displayed as AIS-T.@{ */
        highPriorityPlmn = plmnIgnoreDisplayRule(phoneId , mccmnc , highPriorityPlmn);

        return highPriorityPlmn;
    }

    private String plmnIgnoreDisplayRule(int phoneId, String mccmnc,
            String highPriorityPlmn) {
        SubscriptionController controller = SubscriptionController.getInstance();
        int subId = controller.getSubIdUsingPhoneId(phoneId);
        if (SubscriptionManager.isValidSubscriptionId(subId)) {
            Resources res = SubscriptionManager.getResourcesForSubId(mContext,subId);
            String specialPlmn = res.getString(com.android.internal.R.string.network_operator_ignore_rules);
            if (!TextUtils.isEmpty(specialPlmn)) {
                String[] specialPlmnInfo = specialPlmn.split("@");
                if (specialPlmnInfo.length == 2
                        && mccmnc.equals(specialPlmnInfo[0])) {
                    return specialPlmnInfo[1];
                }
            }
        }
        return TextUtils.isEmpty(highPriorityPlmn) ? "" : highPriorityPlmn;
    }

    private String plmnIgnoreNitz(String highPriorityPlmn, String nitzMccmnc) {
        String[] nitzOperators = mContext.getResources().getStringArray(
                com.android.internal.R.array.ignore_nitz_operator);
        List<String> nitzPlmnList = Arrays.asList(nitzOperators);
        if (nitzPlmnList.size() != 0 && nitzPlmnList.contains(nitzMccmnc)) {
            return "";
        }
        return highPriorityPlmn;
    }

    // UNISOC: Add for Bug1186158 Ignore ONS read from SIM
    private String plmnIgnoreOns(int phoneId, String highPriorityPlmn) {
        if (TextUtils.isEmpty(highPriorityPlmn)) {
            return highPriorityPlmn;
        }
        SubscriptionController controller = SubscriptionController.getInstance();
        int subId = controller.getSubIdUsingPhoneId(phoneId);
        if (SubscriptionManager.isValidSubscriptionId(subId)) {
            Resources res = SubscriptionManager.getResourcesForSubId(mContext,subId);
            if (res != null && res.getBoolean(com.android.internal.R.bool.config_ignore_ons)) {
                return "";
            }
        }
        return highPriorityPlmn;
    }

    //UNISOC:Modify the Bug837237,get cellLocation quickly.
    private CellLocation getCellLocation(int phoneId) {
        Phone phone = PhoneFactory.getPhone(phoneId);
        if (phone != null) {
            ServiceStateTracker serviceStateTracker = phone.getServiceStateTracker();
            if (serviceStateTracker != null) {
                return serviceStateTracker.getCellLocation();
            }
        }
        return null;
    }

    public String updateNetworkList(int phoneId, String[] operatorInfo) {
        Rlog.d(LOG_TAG, "updateNetworkList");
        if (!SubscriptionManager.isValidPhoneId(phoneId) || operatorInfo == null
                || operatorInfo.length < 4) {
            return null;
        }

        String operatorName = operatorInfo[0];
        String mccmncAct = operatorInfo[2];
        String stateString = operatorInfo[3];
        Rlog.d(LOG_TAG, "updateNetworkList: " + mccmncAct + " " + stateString);

        // OperatorNumeric is reported in format "mccmnc act",
        if (mccmncAct != null && mccmncAct.length() > 5) {
            String[] mccmncInfos = mccmncAct.split(" ");
            String mccmnc = mccmncInfos[0];

            if (!TextUtils.isEmpty(mccmnc)) {
                String highPriorityPlmn = getHighPriorityPlmn(phoneId, mccmnc, -1);
                //SPRD: modify for Bug685104
                if (!TextUtils.isEmpty(highPriorityPlmn)) {
                    operatorName = highPriorityPlmn;
                }
            }

            if (!TextUtils.isEmpty(operatorName)) {
                operatorName = TeleUtils.translateOperatorName(mccmnc,operatorName);
                //UNISOC: modify for Bug 629352,Circumventing the operator name as "xx xG", if the operator in this case,
                //remove "*G" from operator name and add ACT type again.
                if (operatorName.length() > 2 && operatorName.substring(operatorName.length()-2).matches("\\dG")) {
                    operatorName = operatorName.substring(0,operatorName.length()-2);
                }
                // Display Act as 2G/3G/4G
                // Act code:
                // 0-GSM(2G)/1-GSMCompact(2G)/2-UTRAN(3G)/7-E-UTRAN(4G)
                String act = mccmncInfos.length > 1 ? mccmncInfos[1] : "";
                switch (act) {
                    case "0":
                    case "1":
                        operatorName += " 2G";
                        break;
                    case "2":
                        operatorName += " 3G";
                        break;
                    case "7":
                        // SPRD: MODIFY FOR BUG 627703
                        if (!operatorName.contains("4G")) {
                            operatorName += " 4G";
                        }
                        break;
                    default:
                        Log.e(LOG_TAG, "ACT was not reported by RIL with PLMN "
                            + mccmnc);
                        break;
                }

                // Add display state string UNKNOWN/FORBIDDEN
                Resources res = Resources.getSystem();
                if (OperatorInfo.State.UNKNOWN.toString().equalsIgnoreCase(stateString)) {
                    operatorName += "("+ res.getString(com.android.internal.R.string.unknownName);
                }
            }
        }
        Rlog.d(LOG_TAG, "updateNetworkList operatorName : " + operatorName);
        return operatorName;
    }

    private void publish() {
        Rlog.d(LOG_TAG, "publish: " + this);
        ServiceManager.addService("ions_ex", this);
    }
}
