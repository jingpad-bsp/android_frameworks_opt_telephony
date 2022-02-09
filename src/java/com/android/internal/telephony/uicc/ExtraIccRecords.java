package com.android.internal.telephony.uicc;

import java.util.ArrayList;

import com.android.internal.telephony.CommandsInterface;
import com.android.internal.telephony.Phone;
import com.android.internal.telephony.PhoneFactory;
import com.android.internal.telephony.uicc.IccCardApplicationStatus.AppType;
import com.android.internal.telephony.uicc.IccConstantsEx;
import com.android.internal.telephony.SubscriptionController;
import com.android.sprd.telephony.RadioInteractor;
import java.util.Arrays;
import android.content.Context;
import android.os.AsyncResult;
import android.os.Handler;
import android.os.Message;
import android.os.RegistrantList;
import android.telephony.CellLocation;
import android.telephony.Rlog;
import android.telephony.SubscriptionManager;
import android.telephony.TelephonyManager;
import android.telephony.PhoneStateListener;
import android.telephony.PhoneNumberUtils;
import android.text.TextUtils;
import android.util.Log;
import java.util.List;

public class ExtraIccRecords extends Handler implements IccConstantsEx{

    private static final boolean DBG = true;
    private static final String LOG_TAG = "ExtraIccRecords";

    private static final int EVENT_GET_CPHS_SPN_DONE = 0;
    private static final int EVENT_GET_SST_DONE = 1;
    private static final int EVENT_GET_ALL_OPL_LOAD_DONE = 2;
    private static final int EVENT_GET_ALL_PNN_LOAD_DONE = 3;
    private static final int EVENT_SIM_REFRESH = 4;
    private static final int EVENT_GET_SIM_ECC_DONE = 5;
    private static final int EVENT_GET_USIM_ECC_DONE = 6;
    private static final int EVENT_GET_PS_DATA_OFF_DONE = 7;
    private static final int EVENT_GET_ALL_OPLNR_LOAD_DONE = 8;

    // Each emergency call code is coded on three bytes From TS 51.011 EF[ECC] section.
    private static final int ECC_BYTES_COUNT = 3;
    private static final int CATEGORY_BYTES_COUNT = 1;

    private ArrayList<OplRecord> mOplRecords = null;
    private ArrayList<OplNrRecord> mOplNrRecords = null;
    private ArrayList<PnnRecord> mPnnRecords = null;

    /**
     * States only used by getCPHSSpnFsm FSM
     */
    private GetCPHSSpnFsmState mOnsState;
    private String mOns = null;
    private String mPnnHomeName = null;

    private int mSstPlmnOplPnnValue = 0;
    private boolean mSimPnnEnabled = false;
    private boolean mSimOplPnnEnabled = false;
    private boolean mSimOplNrPnnEnabled = false;
    private boolean mSimOplEnabled = false;
    private boolean mSimOplNrEnabled = false;

    private int mONSRecordsToLoad = 0;
    private int mEccRecordsToLoad = 0;
    protected RegistrantList mONSRecordsLoadedRegistrants = new RegistrantList();
    protected RegistrantList mEccRecordsLoadedRegistrants = new RegistrantList();
    private int mPhoneId = -1;

    private CommandsInterface mCi;
    private Context mContext;
    private RadioInteractor mRadioInteractor;

    private UsimServiceTable mUsimServiceTable;
    private int mPsDataOffStateValue = -1;
    private int mHomeExceptService = 0;
    private int mRomingExceptService = 0;

    private PhoneStateListener mPhoneStateListener;

    public ExtraIccRecords(Context c, int phoneId, CommandsInterface ci) {
        mCi = ci;
        mPhoneId = phoneId;
        mContext = c;
        mCi.registerForIccRefresh(this, EVENT_SIM_REFRESH, null);
        if (DBG)
            log("Create ExtendedIccRecords");
    }

    public void registerForCellLocationChanged() {
        int subId = SubscriptionController.getInstance().getSubIdUsingPhoneId(mPhoneId);
        if (SubscriptionManager.isValidSubscriptionId(subId)) {
            TelephonyManager tm = (TelephonyManager)mContext.getSystemService(Context.TELEPHONY_SERVICE);
            tm.createForSubscriptionId(subId).listen(getPhoneStateListener(), PhoneStateListener.LISTEN_CELL_LOCATION);
        }
    }

    private PhoneStateListener getPhoneStateListener() {
        mPhoneStateListener = new PhoneStateListener() {
            @Override
            public void onCellLocationChanged(CellLocation location) {
                updatePlmn();
            }
        };
        return mPhoneStateListener;
    }

    public void fetchExtendedIccRecords() {
        TelephonyManager tm = (TelephonyManager)mContext.getSystemService(Context.TELEPHONY_SERVICE);
        if (tm != null && tm.getSimState(mPhoneId)
                == TelephonyManager.SIM_STATE_READY) {
            loadOperatorNameFiles();
        }
        loadEccFiles();
    }

    private void loadOperatorNameFiles() {
        if (DBG)
            log("loadOperatorNameFiles");
        IccFileHandler ifh = getIccFileHandler();
        if (ifh != null) {
            ifh.loadEFTransparent(EF_SST, obtainMessage(EVENT_GET_SST_DONE));
            mONSRecordsToLoad++;
            ifh.loadEFLinearFixedAll(EF_OPL, obtainMessage(EVENT_GET_ALL_OPL_LOAD_DONE));
            mONSRecordsToLoad++;
            ifh.loadEFLinearFixedAll(EF_PNN, obtainMessage(EVENT_GET_ALL_PNN_LOAD_DONE));
            mONSRecordsToLoad++;
            // Load ONS
            getCPHSSpnFsm(true, null);
        }
    }

    private void loadEccFiles() {
        UiccCardApplication app = getUiccCardApp();
        IccFileHandler ifh = getIccFileHandler();
        if (app != null && ifh != null) {
            if (app.getType() == AppType.APPTYPE_USIM) {
                if (DBG)
                    log("Load USIM eccList");
                ifh.loadEFLinearFixedAll(EF_ECC, MF_SIM + DF_ADF,
                        obtainMessage(EVENT_GET_USIM_ECC_DONE));
            } else {
                if (DBG)
                    log("Load SIM eccList");
                ifh.loadEFTransparent(EF_ECC, obtainMessage(EVENT_GET_SIM_ECC_DONE));
            }
        }
        mEccRecordsToLoad++;
    }

    @Override
    public void handleMessage(Message msg) {
        AsyncResult ar;
        byte data[];
        boolean isEccRecordLoadResponse = false;
        boolean isOnsRecordLoadResponse = false;

        try {
            switch (msg.what) {
                case EVENT_SIM_REFRESH:
                    ar = (AsyncResult) msg.obj;
                    if (DBG)
                        log("Sim REFRESH with exception: " + ar.exception);
                    if (ar.exception == null) {
                        handleSimRefresh((IccRefreshResponse) ar.result);
                    }
                    break;
                case EVENT_GET_CPHS_SPN_DONE:
                    isOnsRecordLoadResponse = true;
                    ar = (AsyncResult) msg.obj;
                    Log.d(LOG_TAG, "Load ons false");
                    getCPHSSpnFsm(false, ar);
                    break;

                case EVENT_GET_SST_DONE:
                    isOnsRecordLoadResponse = true;

                    ar = (AsyncResult) msg.obj;
                    data = (byte[]) ar.result;

                    if (ar.exception != null) {
                        break;
                    }
                    mUsimServiceTable = new UsimServiceTable(data);
                    handleSstOPLPNNData(data);

                    break;

                case EVENT_GET_ALL_OPL_LOAD_DONE:
                    isOnsRecordLoadResponse = true;
                    ar = (AsyncResult) msg.obj;
                    if (ar.exception != null) {
                        loge("Exception in fetching OPL Records " + ar.exception);
                        mSimOplEnabled = false;
                        return;
                    }

                    handleGetOplResponse(ar);
                    updatePlmn();
                    break;

                case EVENT_GET_ALL_PNN_LOAD_DONE:
                    isOnsRecordLoadResponse = true;
                    ar = (AsyncResult) msg.obj;
                    if (ar.exception != null) {
                        loge("Exception in fetching PNN Records " + ar.exception);
                        return;
                    }

                    handleGetPnnResponse(ar);
                    updatePlmn();
                    break;

                case EVENT_GET_USIM_ECC_DONE:
                    isEccRecordLoadResponse = true;
                    ar = (AsyncResult) msg.obj;
                    if (ar.exception != null) {
                        return;
                    }

                    handleUsimEccResponse(ar);
                    break;

                case EVENT_GET_SIM_ECC_DONE:
                    isEccRecordLoadResponse = true;
                    ar = (AsyncResult) msg.obj;
                    if (ar.exception != null) {
                        return;
                    }

                    handleSimEccResponse(ar);
                    break;

                case EVENT_GET_PS_DATA_OFF_DONE:
                    ar = (AsyncResult) msg.obj;
                    data = (byte[]) ar.result;

                    if (ar.exception != null) {
                        return;
                    }
                    handleGetPsDataOffResponse(data);
                    break;

                case EVENT_GET_ALL_OPLNR_LOAD_DONE:
                    isOnsRecordLoadResponse = true;
                    ar = (AsyncResult) msg.obj;
                    if (ar.exception != null) {
                        loge("Exception in fetching OPLNR Records " + ar.exception);
                        mSimOplNrEnabled = false;
                        return;
                    }

                    handleGetOplNrResponse(ar);
                    updatePlmn();
                    break;
                default:
                    super.handleMessage(msg);
                    break;
            }
        } catch (RuntimeException exc) {
            // I don't want these exceptions to be fatal
            logw("Exception parsing SIM record ex", exc);
        } finally {
            // Count up record load responses even if they are fails
            if (isOnsRecordLoadResponse) {
                onONSRecordLoaded();
            } else if (isEccRecordLoadResponse) {
                onEccRecordLoaded();
            }
        }
    }

    private void onONSRecordLoaded() {
        // One record loaded successfully or failed, In either case
        // we need to update the recordsToLoad count
        if (DBG)
            log("mONSRecordsToLoad " + mONSRecordsToLoad);

        if (--mONSRecordsToLoad == 0) {
            mONSRecordsLoadedRegistrants.notifyRegistrants(new AsyncResult(null, mPhoneId, null));
        } else if (mONSRecordsToLoad < 0) {
            loge("mONSRecordsToLoad < 0, programmer error suspected");
            mONSRecordsToLoad = 0;
        }
    }

    private void onEccRecordLoaded() {
        if (DBG)
            log("mEccRecordsToLoad " + mEccRecordsToLoad);

        if (--mEccRecordsToLoad == 0) {
            mEccRecordsLoadedRegistrants.notifyRegistrants(new AsyncResult(null, mPhoneId, null));
        } else if (mEccRecordsToLoad < 0) {
            loge("mEccRecordsToLoad < 0, programmer error suspected");
            mEccRecordsToLoad = 0;
        }
    }

    private void handleSstOPLPNNData(byte[] data) {
        mSstPlmnOplPnnValue = -1;
        if (data == null) {
            return;
        }
        UiccCardApplication app = getUiccCardApp();

        if (app != null && app.getType() == AppType.APPTYPE_SIM) {
            // 2GSim,51: PLMN Network Name,52: Operator PLMN List
            if (data.length > 12) {
                mSstPlmnOplPnnValue = ((data[12] >> 4) & 0x0F);
                log("SSTOPLPNN: 2G Sim,sstPlmnOplPnnValue: " + mSstPlmnOplPnnValue);
                if (mSstPlmnOplPnnValue == 0x0F) {
                    mSimOplPnnEnabled = true;
                } else if (mSstPlmnOplPnnValue == 0x03) {
                    mSimPnnEnabled = true;
                }
                log("SSTOPLPNN: 2G Sim,sstPlmnOplPnnValue: " + mSstPlmnOplPnnValue
                        + ", simOplPnnEnabled:" + mSimOplPnnEnabled
                        + ", simPnnEnabled:" +
                        mSimPnnEnabled);
            }
        } else if (app != null && app.getType() == AppType.APPTYPE_USIM) {
            // 5GSim,129:5GS Operator PLMN List
            if (mUsimServiceTable != null && mUsimServiceTable.isAvailable(NRS_OPERATOR_PLMN_LIST)) {
                mSimOplNrPnnEnabled = true;
            }
            if (mSimOplNrPnnEnabled) {
                IccFileHandler ifh = getIccFileHandler();
                if (ifh != null) {
                    ifh.loadEFLinearFixedAll(EF_OPLNR, obtainMessage(EVENT_GET_ALL_OPLNR_LOAD_DONE));
                    mONSRecordsToLoad++;
                    log("read from OPLNR");
                }
            }
            // 3GUSim.45: PLMN Network Name,46: Operator PLMN List
            if (data.length > 5) {
                mSstPlmnOplPnnValue = ((data[5] >> 4) & 0x03);
                if (mSstPlmnOplPnnValue == 0x03) {
                    mSimOplPnnEnabled = true;
                } else if (mSstPlmnOplPnnValue == 0x01) {
                    mSimPnnEnabled = true;
                }
            }
            log("SSTOPLPNN: 3G Sim,sstPlmnOplPnnValue: " + mSstPlmnOplPnnValue
                    + ", simOplPnnEnabled:" + mSimOplPnnEnabled
                    + ", SimOplNrPnnEnabled:" + mSimOplNrPnnEnabled
                    + ", simPnnEnabled:" + mSimPnnEnabled);

            //3GPP-31.102: Service n°117  1:  need to read 6FF9 file
            if (mUsimServiceTable != null && mUsimServiceTable.isAvailable(UST_PS_DATA_OFF)) {
                IccFileHandler ifh = getIccFileHandler();
                mPsDataOffStateValue = mUsimServiceTable.isAvailable(UST_PS_DATA_OFF_INFO) ? 1 : 0;
                if (ifh != null) {
                    ifh.loadEFTransparent(EF_PS_DATA_OFF,
                            obtainMessage(EVENT_GET_PS_DATA_OFF_DONE));
                }
            }
        } else {
            log("SSTOPLPNN: sstPlmnOplPnnValue:" + mSstPlmnOplPnnValue);
        }
    }

    private void handleGetPsDataOffResponse(byte[] data) {
        log("handleGetPsDataOffResponse ");
        if (data == null) {
            return;
        }
        /* 3GPP-31.102: Service n° 131
         * 0: byte 1 contains home and roming ps data off state in 6FF9;
         * 1: byte 1 contains home ps data off state, byte 2 contains roming ps data off;
         */
        if (mPsDataOffStateValue == 1) {
            if (data.length > 2) {
                mHomeExceptService = (data[1] >> 5) & 0xFF;
                mRomingExceptService = (data[2] >> 5) & 0xFF;
                log(" mHomeExceptService: " + mHomeExceptService +
                        " mRomingExceptService: " + mRomingExceptService);
            }
        } else if (mPsDataOffStateValue == 0) {
            if (data.length > 1) {
                mHomeExceptService = (data[1] >> 5) & 0xFF;
                mRomingExceptService = mHomeExceptService;
                log(" mHomeExceptService: " + mHomeExceptService +
                        " mRomingExceptService: " + mRomingExceptService);
            }
        }
    }

    public int getHomeExceptService() {
        return mHomeExceptService;
    }

    public int getRomingExceptService() {
        return mRomingExceptService;
    }

    private void handleGetOplResponse(AsyncResult ar) {
        OplRecord opl;

        if (ar.exception != null) {
            loge("Exception in fetching OPL Records " + ar.exception);
            mSimOplEnabled = false;
            return;
        }
        ArrayList<byte[]> dataOpl = (ArrayList<byte[]>) (ar.result);
        mOplRecords = new ArrayList<OplRecord>(dataOpl.size());
        for (int i = 0, s = dataOpl.size(); i < s; i++) {
            opl = new OplRecord(dataOpl.get(i));
            mOplRecords.add(opl);
            if (DBG)
                log("OPL" + i + ": " + opl);
        }
        mSimOplEnabled = true;
    }

    private void handleGetOplNrResponse(AsyncResult ar) {
        OplNrRecord oplNr;

        if (ar.exception != null) {
            loge("Exception in fetching OPLNR Records " + ar.exception);
            mSimOplNrEnabled = false;
            return;
        }
        ArrayList<byte[]> dataOplNr = (ArrayList<byte[]>) (ar.result);
        mOplNrRecords = new ArrayList<OplNrRecord>(dataOplNr.size());
        for (int i = 0, s = dataOplNr.size(); i < s; i++) {
            oplNr = new OplNrRecord(dataOplNr.get(i));
            mOplNrRecords.add(oplNr);
            if (DBG)
                log("OPLNr" + i + ": " + oplNr);
        }
        mSimOplNrEnabled = true;
    }

    private void handleGetPnnResponse(AsyncResult ar) {
        PnnRecord pnn;

        if (ar.exception != null) {
            loge("Exception in fetching PNN Records " + ar.exception);
            return;
        }
        ArrayList<byte[]> dataPnn = (ArrayList<byte[]>) (ar.result);
        mPnnRecords = new ArrayList<PnnRecord>(dataPnn.size());
        for (int i = 0, s = dataPnn.size(); i < s; i++) {
            pnn = new PnnRecord(dataPnn.get(i));
            mPnnRecords.add(pnn);
            if (i == 0) {
                mPnnHomeName = pnn.getLongName();
            }
            if (DBG)
                log("PNN" + i + ": " + pnn);
        }
    }

    private void updatePlmn(){
        if (mRadioInteractor == null) {
            mRadioInteractor = new RadioInteractor(mContext);
        }
        String mccmnc = TelephonyManager.from(mContext).getNetworkOperatorForPhone(mPhoneId);
        if (!TextUtils.isEmpty(mccmnc)) {
            mRadioInteractor.updateOperatorName(mccmnc, mPhoneId);
        }
    }

    /**
     * States of Get SPN Finite State Machine which only used by getSpnFsm()
     */
    private enum GetCPHSSpnFsmState {
        IDLE, // No initialized
        INIT, // Start FSM
        READ_SPN_CPHS, // Load EF_SPN_CPHS secondly
        READ_SPN_SHORT_CPHS // Load EF_SPN_SHORT_CPHS last
    }

    /**
     * Finite State Machine to load Service Provider Name , which can be stored in either
     * EF_SPN_CPHS, or EF_SPN_SHORT_CPHS (CPHS4.2), EF_SPN (3GPP) After starting, FSM will search
     * SPN EFs in order and stop after finding the first valid SPN
     *
     * @param start set true only for initialize loading
     * @param ar the AsyncResult from loadEFTransparent ar.exception holds exception in error
     *            ar.result is byte[] for data in success
     */
    private void getCPHSSpnFsm(boolean start, AsyncResult ar) {
        byte[] data;

        IccFileHandler ifh = getIccFileHandler();
        if (ifh == null) {
            mOnsState = GetCPHSSpnFsmState.IDLE;
            return;
        }

        if (start) {
            mOnsState = GetCPHSSpnFsmState.INIT;
        }

        switch (mOnsState) {
            case INIT:
                mOns = null;

                ifh.loadEFTransparent(EF_SPN_CPHS,
                        obtainMessage(EVENT_GET_CPHS_SPN_DONE));
                mONSRecordsToLoad++;
                // mojo require firstly read CPHS firstly
                mOnsState = GetCPHSSpnFsmState.READ_SPN_CPHS;
                break;
            case READ_SPN_CPHS:
                if (ar != null && ar.exception == null) {
                    data = (byte[]) ar.result;
                    mOns = IccUtils.adnStringFieldToString(
                            data, 0, data.length - 1);

                    if (isInvalidONS(mOns)) {
                        // If ONS loaded from SIM card is invalid, just drop it.
                        Log.d(LOG_TAG, "Drop invalid ONS: " + mOns);
                        mOns = null;
                    }

                    if (DBG)
                        log("Load EF_SPN_CPHS: " + mOns);

                    mOnsState = GetCPHSSpnFsmState.IDLE;
                } else {
                    ifh.loadEFTransparent(
                            EF_SPN_SHORT_CPHS, obtainMessage(EVENT_GET_CPHS_SPN_DONE));
                    mONSRecordsToLoad++;

                    mOnsState = GetCPHSSpnFsmState.READ_SPN_SHORT_CPHS;
                }
                break;
            case READ_SPN_SHORT_CPHS:
                if (ar != null && ar.exception == null) {
                    data = (byte[]) ar.result;
                    mOns = IccUtils.adnStringFieldToString(
                            data, 0, data.length - 1);

                    if (isInvalidONS(mOns)) {
                        // If ONS loaded from SIM card is invalid, just drop it.
                        Log.d(LOG_TAG, "Drop invalid ONS: " + mOns);
                        mOns = null;
                    }

                    if (DBG)
                        log("Load EF_SPN_SHORT_CPHS: " + mOns);
                } else {
                    if (DBG)
                        log("Load EF_SPN_SHORT_CPHS Failure");
                }
                mOnsState = GetCPHSSpnFsmState.IDLE;
                break;
            default:
                mOnsState = GetCPHSSpnFsmState.IDLE;
        }
    }

    /**
     * Returns SIM OPL/PNN support
     */
    public boolean isSimOplPnnSupport() {
        return mSimPnnEnabled || mSimOplPnnEnabled;
    }

    /**
     * Returns SIM Operator Name String
     */
    public String getPnn(String regPlmn, int lac) {
        String pnn = "";
        if (mSimOplNrPnnEnabled && mSimOplNrEnabled) {
            // TODO dependent on IMSI loaded
            pnn = getOperatorNameFromOplNrPnn(regPlmn, lac);
        } else if (mSimOplPnnEnabled && mSimOplEnabled) {
            pnn = getOperatorNameFromOplPnn(regPlmn, lac);
        } else if (mSimPnnEnabled || (mSimOplPnnEnabled && !mSimOplEnabled)
                || (mSimOplNrPnnEnabled && !mSimOplNrEnabled)) {
            pnn = getFirstPnn(regPlmn);
        }

        return pnn;
    }

    public String getSimOns(String plmn) {
        // ONS(CPHS) is allowed to be shown as PLMN only when UE register on HPLMN or SPDI.
        if (isOnMatchingPlmn(plmn)) { // TODO dependent on IMSI and SPDI loaded
            return mOns;
        }
        return "";
    }

    /**
     * Checks if plmn is HPLMN or on the spdiNetworks list.
     */
    private boolean isOnMatchingPlmn(String plmn) {
        if (plmn == null)
            return false;
        Phone phone = PhoneFactory.getPhone(mPhoneId);
        IccRecords iccRecords = phone == null ? null : phone.getIccCard().getIccRecords();
        if (iccRecords != null && iccRecords instanceof SIMRecords) {
            String[] spdi = iccRecords.getServiceProviderDisplayInformation();
            List<String> spdiNetworks = spdi!= null ? Arrays.asList(spdi) : null;
            if (spdiNetworks != null) {
                for (String spdiNet : spdiNetworks) {
                    if (plmn.equals(spdiNet)) {
                        return true;
                    }
                }
            }
            // SPRD: MODIFY FOR BUG 599169
            String homePlmn = ((SIMRecords) iccRecords).getOperatorNumeric();
            if (plmn.equals(homePlmn)) {
                return true;
            }
        }
        return false;
    }

    private String getOperatorNameFromOplPnn(String regPlmn, int lac) {

        if (regPlmn == null) {
            log("regplmn is  null,doesn't  getOperatorNameFromOplPnn from sim.");
            return null;
        }

        if (mPnnRecords == null) {
            log("mPnnRecords is null");
            return null;
        }

        int PnnRecordnum = getPnnRecordnum(regPlmn, lac);

        if (PnnRecordnum <= 0 || PnnRecordnum > mPnnRecords.size()) {
            log("invalid PnnRecordnum = " + PnnRecordnum);
            return null;
        } else {
            log("mPnnRecords.get(PnnRecordnum - 1).getLongName(): "
                    + mPnnRecords.get(PnnRecordnum - 1).getLongName());
            return mPnnRecords.get(PnnRecordnum - 1).getLongName();
        }
    }

    private String getOperatorNameFromOplNrPnn(String regPlmn, int lac) {

        if (regPlmn == null) {
            log("regplmn is  null,doesn't  getOperatorNameFromOplNrPnn from sim.");
            return null;
        }

        if (mPnnRecords == null) {
            log("mPnnRecords is null");
            return null;
        }

        int PnnRecordnumNr = getPnnRecordnum(regPlmn, lac);

        if (PnnRecordnumNr <= 0 || PnnRecordnumNr > mPnnRecords.size()) {
            log("invalid PnnRecordnumNr = " + PnnRecordnumNr);
            return null;
        } else {
            log("mPnnRecords.get(PnnRecordnumNr - 1).getLongName(): "
                    + mPnnRecords.get(PnnRecordnumNr - 1).getLongName());
            return mPnnRecords.get(PnnRecordnumNr - 1).getLongName();
        }
    }

    private String getFirstPnn(String regPlmn) {

        if (regPlmn == null) {
            log("regplmn is  null, doesn't get pnn name from sim.");
            return null;
        }

        String homePlmn = TelephonyManager.getDefault().getSimOperatorNumericForPhone(mPhoneId);
        if (regPlmn.equals(homePlmn)) {
            if (mPnnRecords != null) {
                log("PNN first record name: " + mPnnRecords.get(0).getLongName());
                return mPnnRecords.get(0).getLongName();
            }
        }

        return null;
    }

    /*
     * UNISOC:If the OPL5G file exists, read from OPL5G, if the opl5g file does not exist and
     * the OPL file exists, read from the OPL file
     */
    private int getPnnRecordnum(String regplmn, int lac) {
        int[] regplmnarray = {
                0, 0, 0, 0, 0, 0
        };

        // UNISOC bug1040315 GET_CELL_INFO_LIST crash
        // PLMN for neighboring CellInfo is assigned to MAX_INT and is invalid
        if (regplmn == null || regplmn.length() > regplmnarray.length) {
            log("regplmn is invalid, fail to get pnn name from sim.");
            return -1;
        }

        if (lac == -1) {
            log("invalid lac");
            return -1;
        }

        if (mOplNrRecords != null || mOplRecords != null) {
            for (int i = 0; i < regplmn.length(); i++) {
                regplmnarray[i] = regplmn.charAt(i) - '0';
            }
        } else {
            log("OplRecord or OplNrRecords not exist");
            return -1;
        }

        if (mOplNrRecords != null) {
            for (OplNrRecord record : mOplNrRecords) {
                if (matchOplplmn(record.mOplNrplmn, regplmnarray)) {
                    log("getPnnRecordnumNr  tac:" + lac + ", record.mOplNrtac1:" + record.mOplNrtac1
                            + ", record.mOplNrtac2:" + record.mOplNrtac2);
                    if ((record.mOplNrtac1 <= lac) && (lac <= record.mOplNrtac2)) {
                        log("record.getPnnRecordNumNr() = " + record.getPnnRecordNum());
                        return record.getPnnRecordNum();
                    }
                }
            }
        } else if (mOplRecords != null) {
            for (OplRecord record : mOplRecords) {
                if (matchOplplmn(record.mOplplmn, regplmnarray)) {
                    log("getPnnRecordnum  lac:" + lac + ", record.mOpllac1:" + record.mOpllac1
                            + ", record.mOpllac2:" + record.mOpllac2);
                    if ((record.mOpllac1 <= lac) && (lac <= record.mOpllac2)) {
                        log("record.getPnnRecordNum() = " + record.getPnnRecordNum());
                        return record.getPnnRecordNum();
                    }
                }
            }
        }

        log("No invalid pnn record match");
        return -1;
    }

    private boolean matchOplplmn(int oplplmn[], int regplmn[]) {
        boolean match = true;
        int SpecialDigit = 0x0D;

        if (regplmn == null | oplplmn == null) {
            return false;
        }

        if (regplmn.length != oplplmn.length) {
            log("regplmn length is not equal oplmn length");
            return false;
        }

        for (int i = 0; i < regplmn.length; i++) {
            if (oplplmn[i] == SpecialDigit) {
                oplplmn[i] = regplmn[i];
            }
        }

        for (int i = 0; i < regplmn.length; i++) {
            if (oplplmn[i] != regplmn[i]) {
                match = false;
                break;
            }
        }

        log("matchOplplmn match:" + match);
        return match;
    }

    private void handleSimRefresh(IccRefreshResponse refreshResponse) {
        if (refreshResponse == null) {
            if (DBG)
                log("handleSimRefresh received without input");
            return;
        }

        UiccCardApplication app = getUiccCardApp();
        if (refreshResponse.aid != null && app != null
                && !refreshResponse.aid.equals(app.getAid())) {
            // This is for different app. Ignore.
            return;
        }

        switch (refreshResponse.refreshResult) {
            case IccRefreshResponse.REFRESH_RESULT_FILE_UPDATE:
                if (DBG)
                    log("handleSimRefresh with SIM_FILE_UPDATED");
                handleFileUpdate(refreshResponse.efId);
                break;
            default:
                // UiccController and SIMRecords will handler the other refresh operations.
                if (DBG)
                    log("handleSimRefresh other operation");
                break;
        }
    }

    private void handleFileUpdate(int efId) {
        loadOperatorNameFiles();
    }

    public String getPnnHomeName() {
        return mPnnHomeName;
    }

    void resetRecords() {
        log("reset records");

        mOplRecords = null;
        mOplNrRecords = null;
        mPnnRecords = null;
        mSstPlmnOplPnnValue = 0;
        mSimPnnEnabled = false;
        mSimOplPnnEnabled = false;
        mSimOplNrPnnEnabled = false;
        mSimOplEnabled = false;
        mSimOplNrEnabled = false;
        mOns = null;
    }

    private boolean isInvalidONS(String ons) {
        return "@".equals(ons);
    }

    private void handleUsimEccResponse(AsyncResult ar) {
        String eccList = "";
        // Linear fixed EF: ((AsyncResult)(onLoaded.obj)).result is an ArrayList<byte[]>
        ArrayList<byte[]> results = (ArrayList<byte[]>) ar.result;
        if (results != null) {
            for (int i = 0; i < results.size(); i++) {
                String number = PhoneNumberUtils.calledPartyBCDFragmentToString(
                        results.get(i), 0, ECC_BYTES_COUNT);
                String category = (results.get(i)[results.get(i).length-1] & 0xFF) + "";
                eccList = concatenateEccList(eccList, concatenateCategoryList(number,category));
            }
            log("USIM ECC List: " + eccList);
            if (!TextUtils.isEmpty(eccList)) {
                if (mRadioInteractor == null) {
                    mRadioInteractor = new RadioInteractor(mContext);
                }
                mRadioInteractor.updateRealEccList(eccList, mPhoneId);
            }
        }
    }

    private void handleSimEccResponse(AsyncResult ar) {
        String eccList = "";
        // Transparent EF: ((AsyncResult)(onLoaded.obj)).result is the byte[]
        byte[] numbers = (byte[]) ar.result;
        if (numbers != null) {
            for (int offSet = 0; offSet < numbers.length / ECC_BYTES_COUNT; offSet++) {
                String number = PhoneNumberUtils.calledPartyBCDFragmentToString(numbers,
                        offSet * ECC_BYTES_COUNT, ECC_BYTES_COUNT,PhoneNumberUtils.BCD_EXTENDED_TYPE_EF_ADN);
                eccList = concatenateEccList(eccList, number);
            }
            log("ECC list: " + eccList);
            if (!TextUtils.isEmpty(eccList)) {
                if (mRadioInteractor == null) {
                    mRadioInteractor = new RadioInteractor(mContext);
                }
                mRadioInteractor.updateRealEccList(eccList, mPhoneId);
            }
        }
    }

    private String concatenateEccList (String eccList, String number) {
        if (!TextUtils.isEmpty(number)) {
            if (!TextUtils.isEmpty(eccList)) {
                eccList += "," + number;
            } else {
                eccList = number;
            }
        }
        return eccList;
    }

    private String concatenateCategoryList (String eccList, String category) {
        if (!TextUtils.isEmpty(category)) {
            if (!TextUtils.isEmpty(eccList)) {
                eccList += "@" + category;
            }
        }
        return eccList;
    }

    private UiccCardApplication getUiccCardApp() {
        UiccProfile uiccProfile = UiccController.getInstance().getUiccProfileForPhone(mPhoneId);
        return uiccProfile == null ? null : uiccProfile.getApplication(UiccController.APP_FAM_3GPP);
    }

    private IccFileHandler getIccFileHandler() {
        Phone phone = PhoneFactory.getPhone(mPhoneId);
        return phone == null ? null : phone.getIccFileHandler();
    }

    private void log(String s) {
        Rlog.d(LOG_TAG, "[ExtraIccRecords" + mPhoneId + "] " + s);
    }

    private void loge(String s) {
        Rlog.e(LOG_TAG, "[ExtraIccRecords" + mPhoneId + "] " + s);
    }

    private void logw(String s, Throwable tr) {
        Rlog.w(LOG_TAG, "[ExtraIccRecords" + mPhoneId + "] " + s, tr);
    }

    private void logv(String s) {
        Rlog.v(LOG_TAG, "[ExtraIccRecords" + mPhoneId + "] " + s);
    }

}
