package com.android.internal.telephony;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

import org.xmlpull.v1.XmlPullParser;
import org.xmlpull.v1.XmlPullParserException;

import com.android.internal.telephony.Phone;
import com.android.internal.telephony.PhoneFactory;
import com.android.internal.telephony.SubscriptionController;
import com.android.internal.telephony.uicc.IccCardApplicationStatus.AppType;
import com.android.internal.telephony.uicc.SimLockController;
import com.android.internal.telephony.uicc.UiccCard;
import com.android.internal.telephony.uicc.UiccController;
import com.android.internal.telephony.uicc.UiccProfile;

import com.android.internal.util.XmlUtils;

import android.app.ActivityThread;
import android.content.Context;
import android.content.res.Resources;
import android.os.SystemProperties;
import android.provider.Settings;
import android.provider.SettingsEx;
import android.telephony.Rlog;
import android.telephony.ServiceState;
import android.telephony.SubscriptionInfo;
import android.telephony.SubscriptionManager;
import android.telephony.TelephonyManager;
import android.text.TextUtils;

public class PrimarySubConfig {

    private static final boolean DBG = true;
    private static final String TAG = "PrimarySubConfig";

    private static PrimarySubConfig mInstance;

    private int mPhoneCount = TelephonyManager.getDefault().getPhoneCount();
    private List<PrimarySubConfigInfo> mAllConfigInfos = new ArrayList<>();
    private PrimarySubConfigInfo[] mIccConfigs = new PrimarySubConfigInfo[mPhoneCount];

    private Context mContext;

    private PrimarySubConfig (Context context) {
        mContext = context;
        loadPrimarySubConfigInfo();
    }

    static PrimarySubConfig init(Context context) {
        if (mInstance == null) {
            mInstance = new PrimarySubConfig(context);
        }
        return mInstance;
    }

    public static PrimarySubConfig getInstance() {
        return mInstance;
    }

    private void loadPrimarySubConfigInfo() {
        try {
            Resources resource = mContext.getApplicationContext().getResources();
            int resId = resource.getIdentifier("primary_sub_conf", "xml",
                    mContext.getPackageName());
            XmlPullParser parser = resource.getXml(resId);

            XmlUtils.beginDocument(parser, "iccConfigs");

            while (true) {
                XmlUtils.nextElement(parser);

                String name = parser.getName();
                if (!"iccConfig".equals(name)) {
                    break;
                }

                mAllConfigInfos.add(new PrimarySubConfigInfo(parser));
            }
        } catch (XmlPullParserException e) {
            logw("Exception in primary_sub_conf parser " + e);
        } catch (IOException e) {
            logw("Exception in primary_sub_conf parser " + e);
        }

        Rlog.d(TAG, "loadPrimarySubConfigInfo done: " + mAllConfigInfos);
    }

    public void update() {
        int phoneCount = TelephonyManager.getDefault().getPhoneCount();
        SubscriptionController controller = SubscriptionController.getInstance();
        mIccConfigs = new PrimarySubConfigInfo[phoneCount];
        for (int i = 0; i < phoneCount; i++) {
            SubscriptionInfo subInfo = controller.getActiveSubscriptionInfoForSimSlotIndex(i,
                    ActivityThread.currentApplication().getOpPackageName());
            if (subInfo != null) {
                for (PrimarySubConfigInfo config : mAllConfigInfos) {
                    PrimarySubConfigInfo newConfig = new PrimarySubConfigInfo(config);
                    if (newConfig.match(subInfo)) {
                        if (mIccConfigs[i] == null
                                || mIccConfigs[i].mMatchedScore < newConfig.mMatchedScore) {
                            mIccConfigs[i] = newConfig;
                        }
                    }
                }
            }
        }

        for (int i = 0; i < mPhoneCount; i++) {
            if (mIccConfigs[i] != null) {
                setNetworkTypeRestrictEnable(false,i);
                mIccConfigs[i].updateRestrictedNetwork();
            }
            logd("Update matched config[" + i + "]: " + mIccConfigs[i]);
        }
    }

    /**
     * Return primary phoneId which has the biggest priority according to matched icc configs.
     */
    public int getPreferredPrimaryCard() {
        int maxPriorityPhoneId = 0;
        if (Resources.getSystem().getBoolean(com.android.internal.R.bool.simlock_restrict_data_card)) {
            logd("simlock_restrict_data_card");
            SimLockController simLockController = SimLockController.getInstance();
            for (int i = 0; i < mPhoneCount; i++) {
                if (simLockController.isWhiteListCard(i)){
                    maxPriorityPhoneId = i;
                }
            }
        } else {
            SubscriptionManager subManager = SubscriptionManager.from(mContext);
            for (int i = 0; i < mPhoneCount; i++) {
                if (mIccConfigs[i] != null
                        && (mIccConfigs[maxPriorityPhoneId] == null || mIccConfigs[maxPriorityPhoneId].mPriority < mIccConfigs[i].mPriority)) {
                    maxPriorityPhoneId = i;
                }
                SubscriptionInfo subInfo = subManager.getActiveSubscriptionInfoForSimSlotIndex(maxPriorityPhoneId);
                if (subInfo != null && (!subManager.isSubscriptionEnabled(subInfo.getSubscriptionId())
                    || subInfo.isOpportunistic()) && i != maxPriorityPhoneId) {
                    logd("the subinfo is disabled or opportunistic");
                    maxPriorityPhoneId = i;
                }
            }
        }
        return maxPriorityPhoneId;
    }

    /**
     * Return whether need popup primary card setting prompt according to icc configs.
     */
    public boolean isNeedPopupPrimaryCardSettingPrompt() {
        if (Resources.getSystem().getBoolean(com.android.internal.R.bool.simlock_restrict_data_card)) {
            logd("simlock_restrict_data_card");
            SimLockController simLockController = SimLockController.getInstance();
            TelephonyManager telephonyManager = TelephonyManager.from(mContext);
            int maxPriorityCount = 0;
            for (int i = 0; i < mPhoneCount; i++) {
                if (TextUtils.isEmpty(telephonyManager.getSimOperatorNumericForPhone(i))) {
                    return false;
                }
                if (simLockController.isWhiteListCard(i)){
                    maxPriorityCount++;
                }
            }
            return maxPriorityCount == mPhoneCount;
        }

        int maxPriorityPhoneId = getPreferredPrimaryCard();
        if (mIccConfigs[maxPriorityPhoneId] == null) {
            return false;
        }

        int maxPriorityCount = 0;
        boolean allowUserPrompt = false;
        for (int i = 0; i < mPhoneCount; i++) {
            if (mIccConfigs[i] != null
                    && mIccConfigs[i].mPriority == mIccConfigs[maxPriorityPhoneId].mPriority) {
                maxPriorityCount++;
                allowUserPrompt |= mIccConfigs[i].mUserPrompt;
            }
        }
        return maxPriorityCount >= 2 && allowUserPrompt;
    }

    /**
     * Return default network type of matched icc config for specified phoneId.
     *
     * @see com.android.internal.telephony.RILConstants#NETWORK_MODE_WCDMA_PREF
     * @see com.android.internal.telephony.RILConstants#NETWORK_MODE_LTE_GSM
     *
     * @return network type if not specified, return -1
     */
    public int getNetworkType(int phoneId) {
        if (mIccConfigs[phoneId] != null) {
            return mIccConfigs[phoneId].mNetwork;
        }
        return -1;
    }

    /**
     * Return restricted network type of matched icc config for specified phoneId.
     * If restricted network type is valid, {@link #getNetworkType} is useless.
     *
     * @see com.android.internal.telephony.RILConstants#NETWORK_MODE_WCDMA_PREF
     * @see com.android.internal.telephony.RILConstants#NETWORK_MODE_LTE_GSM
     *
     * @return restricted network type if not specified, return -1
     */
    public int getRestrictedNetworkType(int phoneId) {
        boolean simlockRestrictNetworktypeGsm = Resources.getSystem().getBoolean(
              com.android.internal.R.bool.simlock_restrict_networktype_2G);
        if (simlockRestrictNetworktypeGsm) {
            logd("simlock_restrict_networktype_2G");
            SimLockController simLockController = SimLockController.getInstance();
            if (!simLockController.isWhiteListCard(phoneId)) {
                return RILConstants.NETWORK_MODE_GSM_ONLY;
            }
        }
        if (mIccConfigs[phoneId] != null && mIccConfigs[phoneId].mIsNetworkRestricted) {
            return mIccConfigs[phoneId].mRestrictedNetwork;
        }
        return -1;
    }

    int getRestrictedPhoneId(int phoneId) {
        int restrictedPhoneId = Resources.getSystem().getInteger(
                com.android.internal.R.integer.restricted_network_mode_phoneid);
        logd("restrictedPhoneId ="+restrictedPhoneId+",phoneid="+phoneId);
        return SubscriptionManager.isValidPhoneId(restrictedPhoneId) ? restrictedPhoneId : phoneId;
    }

    void setNetworkTypeRestrictEnable(boolean enable,int phoneId) {
        Settings.Global.putInt(mContext.getContentResolver(),
                SettingsEx.GlobalEx.RESTRICT_NETWORK_TYPE + phoneId,enable ? 1 : 0);
    }

    /**
     * Return whether current version is fixed primary slot.
     * @return if true, primary slot will never be changed
     */
    public static boolean isFixedSlot() {
        return "true".equals(SystemProperties.get("ro.vendor.radio.fixed_slot", "false"));
    }

    class PrimarySubConfigInfo {
        private static final int ANY_MATCHED_FIALED = -1;
        private static final int MATCHED_PARTTERN = 1 << 8;
        private static final int MATCHED_APP_TYPE = 1 << 4;
        private static final int MATCHED_ROAMING_STATE = 1 << 2;

        private String mTag;
        private String mPatternType;
        private String mPattern;
        private String mAppType;
        private int mPriority = -1;
        private int mNetwork = -1;
        private int mRestrictedNetwork = -1;
        private String mRestrictedBy;
        private boolean mUserPrompt;
        private boolean mIsNetworkRestricted;
        private String mRoaming;
        private String mGid;
        private boolean mForceRestricted;
        // Give each of the optional matching conditions a score and calculate
        // the highest score which means the best matched config.
        private int mMatchedScore;
        private SubscriptionInfo mMatchedSubInfo;

        PrimarySubConfigInfo(XmlPullParser parser) {
            mTag = parser.getAttributeValue(null, "tag");
            mPatternType = parser.getAttributeValue(null, "pattern_type");
            mPattern = parser.getAttributeValue(null, "pattern");
            mAppType = parser.getAttributeValue(null, "app_type");
            mRoaming = parser.getAttributeValue(null, "roaming");
            mPriority = Integer.parseInt(parser.getAttributeValue(null, "priority"));
            String netwrokValue = parser.getAttributeValue(null, "network");
            if (!TextUtils.isEmpty(netwrokValue)) {
                mNetwork = Integer.parseInt(netwrokValue);
            }
            String restrictedNetwrokValue = parser.getAttributeValue(null, "restricted_network");
            if (!TextUtils.isEmpty(restrictedNetwrokValue)) {
                mRestrictedNetwork = Integer.parseInt(restrictedNetwrokValue);
            }
            mRestrictedBy = parser.getAttributeValue(null, "restricted_by");
            mUserPrompt = Boolean.valueOf(parser.getAttributeValue(null, "user_prompt"));
            mGid = parser.getAttributeValue(null, "gid");
            mForceRestricted = Boolean.valueOf(parser.getAttributeValue(null, "force_restricted"));
        }

        PrimarySubConfigInfo(PrimarySubConfigInfo config) {
            copyFrom(config);
        }

        boolean match(SubscriptionInfo subInfo) {
            mMatchedScore = 0;
            if (subInfo != null
                    && SubscriptionManager.isValidSubscriptionId(subInfo.getSubscriptionId())) {
                int phoneId = subInfo.getSimSlotIndex();
                if (!TextUtils.isEmpty(mPattern)) {
                    // According to the agreement, ICCID is the only identifier
                    // for operators, but some special operators such as
                    // 'Reliance' have to use MCC/MNC to identify their SIM
                    // cards of different sub-brands. So use ICCID first unless
                    // "pattern_type" is specified.
                    Pattern p = Pattern.compile(mPattern);
                    if ("mccmnc".equalsIgnoreCase(mPatternType)) {
                        String mccMnc = TelephonyManager.getDefault().
                                getSimOperatorNumeric(subInfo.getSubscriptionId());
                        logd(" mccMnc = " + mccMnc);
                        if (!TextUtils.isEmpty(mccMnc) && p.matcher(mccMnc).find()) {
                            if (!TextUtils.isEmpty(mGid)) {
                                Pattern p1 =Pattern.compile(mGid);
                                Phone phone = PhoneFactory.getPhone(phoneId);
                                String gid1 = phone.getGroupIdLevel1();
                                if (!TextUtils.isEmpty(gid1) && p1.matcher(gid1).find()) {
                                    mMatchedScore |= MATCHED_PARTTERN;
                                }
                            } else {
                                mMatchedScore |= MATCHED_PARTTERN;
                            }
                        }
                    } else {
                        String iccId = subInfo.getIccId();
                        if (!TextUtils.isEmpty(iccId) && p.matcher(iccId).find()) {
                            mMatchedScore |= MATCHED_PARTTERN;
                        }
                    }
                    if ((mMatchedScore & MATCHED_PARTTERN) != MATCHED_PARTTERN) {
                        mMatchedScore = ANY_MATCHED_FIALED;
                        return false;
                    }
                }

                if (!TextUtils.isEmpty(mAppType)) {
                    UiccCard uiccCard = UiccController.getInstance().getUiccCard(phoneId);
                    if (uiccCard != null) {
                        UiccProfile up = uiccCard.getUiccProfile();
                        if (up != null && up.isApplicationOnIcc(AppType.valueOf(mAppType))) {
                            mMatchedScore |= MATCHED_APP_TYPE;
                        }
                    }
                    if ((mMatchedScore & MATCHED_APP_TYPE) != MATCHED_APP_TYPE) {
                        mMatchedScore = ANY_MATCHED_FIALED;
                        return false;
                    }
                }

                if (!TextUtils.isEmpty(mRoaming)) {
                    Phone phone = PhoneFactory.getPhone(phoneId);
                    if (phone != null) {
                        ServiceState ss = phone.getServiceState();
                        boolean isRoaming = ss != null ? ss.getRoaming() : false;
                        if (String.valueOf(isRoaming).equals(mRoaming)) {
                            mMatchedScore |= MATCHED_ROAMING_STATE;
                        }
                    }
                    if ((mMatchedScore & MATCHED_ROAMING_STATE) != MATCHED_ROAMING_STATE) {
                        mMatchedScore = ANY_MATCHED_FIALED;
                        return false;
                    }
                }
            }

            if (mMatchedScore > 0) {
                mMatchedSubInfo = subInfo;
            }

            return mMatchedScore > 0;
        }

        void updateRestrictedNetwork() {
            if (mMatchedSubInfo != null) {
                if (!TextUtils.isEmpty(mRestrictedBy)) {
                    for (PrimarySubConfigInfo config : mIccConfigs) {
                        if ((config != this && config != null && !TextUtils.isEmpty(config.mTag) && mRestrictedBy.matches(config.mTag))
                                || (config != null && config.mForceRestricted
                                && downgradeNetworkCapability(mMatchedSubInfo.getSimSlotIndex()))) {
                            mIsNetworkRestricted = true;
                            return;
                        }
                    }
                }
            }
            mIsNetworkRestricted = false;
        }

        private boolean downgradeNetworkCapability(int phoneId) {
            if (Resources.getSystem().getBoolean(com.android.internal.R.bool.mobile_network_capability_downgrade)) {
                int restrictedPhoneId = PrimarySubConfig.getInstance().getRestrictedPhoneId(phoneId);
                if (!mRestrictedBy.equalsIgnoreCase(mTag) && phoneId == restrictedPhoneId) {
                    PrimarySubConfig.getInstance().setNetworkTypeRestrictEnable(true,restrictedPhoneId);
                    return true;
                }
            }
            return false;
        }

        private void copyFrom(PrimarySubConfigInfo config) {
            mTag = config.mTag;
            mPatternType = config.mPatternType;
            mPattern = config.mPattern;
            mAppType = config.mAppType;
            mRoaming = config.mRoaming;
            mPriority = config.mPriority;
            mNetwork = config.mNetwork;
            mRestrictedNetwork = config.mRestrictedNetwork;
            mRestrictedBy = config.mRestrictedBy;
            mUserPrompt = config.mUserPrompt;
            mGid = config.mGid;
            mForceRestricted = config.mForceRestricted;
        }

        @Override
        public String toString() {
            return "PrimarySubConfigInfo: "
                    + " tag= " + mTag
                    + " patternType= " + mPatternType
                    + " parttern= " + mPattern
                    + " appType= " + mAppType
                    + " priority= " + mPriority
                    + " network= " + mNetwork
                    + " restrictedNetwork= " + mRestrictedNetwork
                    + " restrictedBy= " + mRestrictedBy
                    + " userPrompt= " + mUserPrompt
                    + " isNetworkRestricted= " + mIsNetworkRestricted
                    + " roaming= " + mRoaming
                    + " gid= " + mGid
                    + " forceRestricted=" + mForceRestricted
                    + " matchedScore= " + mMatchedScore
                    + " sub= " + (mMatchedSubInfo == null ? "null" : mMatchedSubInfo.getSubscriptionId())
                    + "\n";
        }
    }

    private void logd(String msg) {
        if (DBG)
            Rlog.d(TAG, msg);
    }

    private void logw(String msg) {
        if (DBG)
            Rlog.w(TAG, msg);
    }
}
