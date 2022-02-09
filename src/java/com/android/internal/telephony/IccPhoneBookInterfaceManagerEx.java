package com.android.internal.telephony;

import java.util.HashMap;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.List;
import java.util.ArrayList;
import java.util.Arrays;

import android.annotation.UnsupportedAppUsage;
import android.os.AsyncResult;
import android.os.Looper;
import android.os.HandlerThread;
import android.os.Handler;
import android.content.pm.PackageManager;
import android.os.Message;

import com.android.internal.telephony.uicc.IccRecords;
import com.android.internal.telephony.uicc.IccCardApplicationStatus.AppType;
import com.android.internal.telephony.uicc.IccCardApplicationStatus;
import com.android.internal.telephony.IccPhoneBookOperationException;
import com.android.internal.telephony.uicc.AdnRecordEx;
import com.android.internal.telephony.uicc.IccConstantsEx;
import com.android.internal.telephony.gsm.UsimPhoneBookManager;
import com.android.internal.telephony.uicc.UiccController;
import com.android.internal.telephony.uicc.UiccCardApplication;
import com.android.internal.telephony.uicc.UiccCard;
import com.android.internal.telephony.uicc.IccCardStatus.CardState;
import com.android.internal.telephony.uicc.UiccProfile;
import com.android.internal.telephony.uicc.IccFileHandler;
import com.android.internal.telephony.uicc.RuimFileHandler;

/**
 * {@hide}
 */
public class IccPhoneBookInterfaceManagerEx extends IccPhoneBookInterfaceManager {

    private HandlerThread mUpdateThread;
    @UnsupportedAppUsage
    private UpdateThreadHandler mBaseHandler;

    /* UNISOC: Bug985089 Phonebook reads sim contact optimization. @{ */
    /*private List<AdnRecordEx> mAdnRecordsEx;
    private List<AdnRecordEx> mFdnRecordsEx;
    private List<AdnRecordEx> mSdnRecordsEx;
    private List<AdnRecordEx> mLndRecordsEx;

    private boolean mReadAdnRecordSuccess = false;
    private boolean mReadFdnRecordSuccess = false;
    private boolean mReadSdnRecordSuccess = false;
    private boolean mReadLndRecordSuccess = false;*/
    /* @} */
    private final HashMap<Integer, Request> mAllLoadRequest = new HashMap<>();

    //UNISOC: add for bug1189141, Optimize the speed of importing sim contacts
    private boolean mIsPhonebookLoading = false;
    private Object mLockForReadSizes = new Object();

    public IccPhoneBookInterfaceManagerEx(Phone phone) {
        super(phone);
        createUpdateThread();
        //UNISOC: add for bug1152952, java.lang.NullPointerException
        mUiccController = UiccController.getInstance();
        //UNISOC: add for bug1189141, Optimize the speed of importing sim contacts
        registerForIccChanged(phone);
    }

    protected class UpdateThreadHandler extends Handler {
        public UpdateThreadHandler(Looper looper) {
            super(looper);
        }

        @Override
        public void handleMessage(Message msg) {
            AsyncResult ar = (AsyncResult) msg.obj;
            Request request = (Request) ar.userObj;

            switch (msg.what) {
            case EVENT_GET_SIZE_DONE:
                int[] recordSize = null;
                if (ar.exception == null) {
                    recordSize = (int[]) ar.result;
                    // recordSize[0]  is the record length
                    // recordSize[1]  is the total length of the EF file
                    // recordSize[2]  is the number of records in the EF file
                    logd("GET_RECORD_SIZE Size: " + Arrays.toString(recordSize));
                } else {
                    loge("EVENT_GET_SIZE_DONE failed, ex = " + ar.exception);
                }
                notifyPending(request, recordSize);
                break;
            case EVENT_UPDATE_DONE:
                boolean success = (ar.exception == null);
                int simIndex = -1;
                logd("EVENT_UPDATE_DONE : success " + success);
                if (success) {
                    simIndex = getInsertIndex();
                } else {
                    loge("EVENT_UPDATE_DONE failed; ex = " + ar.exception);
                    if (ar.exception instanceof IccPhoneBookOperationException) {
                        simIndex = ((IccPhoneBookOperationException) ar.exception).mErrorCode;
                    } else {
                        simIndex = -1;
                    }
                }
                logd("EVENT_UPDATE_DONE : simIndex =  " + simIndex);
                notifyPending(request, simIndex);
                break;
            case EVENT_LOAD_DONE:
                List<AdnRecordEx> records = null;
                if (ar.exception == null) {
                    logd("EVENT_LOAD_DONE, msg.arg1 = " + msg.arg1);
                    records = (List<AdnRecordEx>) ar.result;
                } else {
                    logd("Cannot load ADN records");
                }
                notifyPending(request, records);
                break;
            }
        }

        private void notifyPending(Request request, Object result) {
            if (request != null) {
                synchronized (request) {
                    request.mResult = result;
                    request.mStatus.set(true);
                    request.notifyAll();
                }
            }
        }
    };

    private void createUpdateThread() {
        mUpdateThread = new HandlerThread("RunningState:Background");
        mUpdateThread.start();
        mBaseHandler = new UpdateThreadHandler(mUpdateThread.getLooper());
    }

    public void updateIccRecords(IccRecords iccRecords) {
        super.updateIccRecords(iccRecords);
    }

    @UnsupportedAppUsage
    private int updateEfForIccType(int efid) {
        boolean isPbrFileExisting = false;
        boolean isContainAdnInPbr = false;
        if (mAdnCache != null && mAdnCache.getUsimPhoneBookManager() != null) {
            isPbrFileExisting = mAdnCache.getUsimPhoneBookManager().isPbrFileExisting();
            isContainAdnInPbr = mAdnCache.getUsimPhoneBookManager().isContainAdnInPbr();
        }
        // Check if we are trying to read ADN records
        if (efid == IccConstantsEx.EF_ADN) {
            logd("isPbrFileExisting = " + isPbrFileExisting + ", isContainAdnInPbr = " + isContainAdnInPbr);
            if (isHasUsimApp() && isPbrFileExisting
                    && isContainAdnInPbr) {
                return IccConstantsEx.EF_PBR;
            }
        }
        return efid;
    }

    public List<AdnRecordEx> getAdnRecordsInEfEx(int efid) {

        if (mPhone.getContext().checkCallingOrSelfPermission(
                android.Manifest.permission.READ_CONTACTS) != PackageManager.PERMISSION_GRANTED) {
            throw new SecurityException(
                    "Requires android.permission.READ_CONTACTS permission");
        }

        efid = updateEfForIccType(efid);
        logd("getAdnRecordsInEFEx: efid = 0x" + Integer.toHexString(efid).toUpperCase());

        checkThread();
        Request loadRequest = mAllLoadRequest.get(efid);
        if (loadRequest == null) {
            loadRequest = new Request();
            mAllLoadRequest.put(efid, loadRequest);
        }
        synchronized (loadRequest) {
            Message response = mBaseHandler.obtainMessage(EVENT_LOAD_DONE, efid, 0, loadRequest);
            logd("requestLoadAllAdnLike  efid = 0x" + Integer.toHexString(efid).toUpperCase());
            //UNISOC: add for bug1129485/1177870, Disable and enable sim card, sim contacts load fail.
            loadRequest.mStatus.set(false);
            if (mAdnCache != null) {
                mAdnCache.requestLoadAllAdnLike(efid, mAdnCache.extensionEfForEf(efid), response);
                waitForResult(loadRequest);
            } else {
                loge("Failure while trying to load from SIM due to uninitialised adncache");
            }
        }

        logd("getAdnRecordsInEFEx done :efid = 0x" + Integer.toHexString(efid).toUpperCase());
        return (List<AdnRecordEx>) loadRequest.mResult;
    }

    @Override
    public int[] getAdnRecordsSize(int efid) {
        logd("getAdnRecordsSize :" + efid);
        //UNISOC: add for bug1189141/985089, Optimize the speed of importing sim contacts
        synchronized (mLockForReadSizes) {
            if (mIsPhonebookLoading) {
                try {
                    mLockForReadSizes.wait();
                } catch (InterruptedException e) {
                    loge("Interrupted Exception in getAdnRecordsSize");
                }
            }
        }
        //UNISOC: add for bug903038, Special pbr in this card, adnSize is null
        if (isHasUsimApp()
                && (efid == IccConstantsEx.EF_ADN)
                && updateEfForIccType(efid) == IccConstantsEx.EF_PBR) {
            int[] size = getUsimAdnRecordsSize();
            if (null == size || size[0] == 0) {
                size = getRecordsSize(efid);
            }
            return size;
        } else {
            return getRecordsSize(efid);
        }
    }

    public int[] getRecordsSize(int efid) {
        logd("getRecordsSize: efid = 0x" + Integer.toHexString(efid).toUpperCase());

        if (efid <= 0) {
            loge("the efid is invalid");
            return null;
        }
        checkThread();
        Request getSizeRequest = new Request();
        synchronized (getSizeRequest) {
            //Using mBaseHandler, no difference in EVENT_GET_SIZE_DONE handling
            Message response = mBaseHandler.obtainMessage(EVENT_GET_SIZE_DONE, getSizeRequest);
            /** UNISOC: modify for bug1062887 Sim capacity display error @{ */
            IccFileHandler fh = mPhone.getIccFileHandler();
            if (fh != null) {
                //UNISOC: add for bug1152952, java.lang.NullPointerException
                if (fh instanceof RuimFileHandler && mUiccController != null) {
                    IccFileHandler iccFileHandler3gpp = mUiccController.getIccFileHandler(mPhone.getPhoneId(), UiccController.APP_FAM_3GPP);
                    if (iccFileHandler3gpp != null) {
                        fh = iccFileHandler3gpp;
                    }
                }
                logd("fh = " + fh);
                fh.getEFLinearRecordSize(efid, response);
                waitForResult(getSizeRequest);
            } else {
                loge("getRecordsSize, fh is null");
            }
            /** @｝ */
        }

        return getSizeRequest.mResult == null ? new int[3] : (int[]) getSizeRequest.mResult;
    }

    @Override
    public boolean updateAdnRecordsInEfBySearch(int efid, String oldTag,
            String oldPhoneNumber, String newTag, String newPhoneNumber,
            String pin2) {

        if (mPhone.getContext().checkCallingOrSelfPermission(
                android.Manifest.permission.WRITE_CONTACTS) != PackageManager.PERMISSION_GRANTED) {
            throw new SecurityException(
                    "Requires android.permission.WRITE_CONTACTS permission");
        }

        logd("updateAdnRecordsInEfBySearch: efid = 0x" + Integer.toHexString(efid).toUpperCase()
                + " (" + oldTag + "," + oldPhoneNumber + ")" + "==>" + " (" + newTag + ","
                + newPhoneNumber + ")" + ", pin2=" + pin2);

        efid = updateEfForIccType(efid);

        checkThread();
        Request updateRequest = new Request();
        synchronized (updateRequest) {
            Message response = mBaseHandler.obtainMessage(EVENT_UPDATE_DONE, updateRequest);
            AdnRecordEx oldAdn = new AdnRecordEx(oldTag, oldPhoneNumber);
            AdnRecordEx newAdn = new AdnRecordEx(newTag, newPhoneNumber);
            if (mAdnCache != null) {
                mAdnCache.updateAdnBySearchEx(efid, oldAdn, newAdn, pin2, response);
                waitForResult(updateRequest);
            } else {
                loge("Failure while trying to update by search due to uninitialised adncache");
            }
        }
        return (boolean) updateRequest.mResult;
    }

    public int updateAdnRecordsInEfBySearch(int efid, String oldTag,
            String oldPhoneNumber, String[] oldEmailList, String oldAnr,
            String oldSne, String oldGrp, String newTag, String newPhoneNumber,
            String[] newEmailList, String newAnr, String newAas, String newSne,
            String newGrp, String newGas, String pin2) {

        if (mPhone.getContext().checkCallingOrSelfPermission(
                android.Manifest.permission.WRITE_CONTACTS) != PackageManager.PERMISSION_GRANTED) {
            throw new SecurityException(
                    "Requires android.permission.WRITE_CONTACTS permission");
        }
        logd("updateAdnRecordsInEfBySearchEx: efid = 0x" + Integer.toHexString(efid).toUpperCase()
                + " (" + newTag + "," + newPhoneNumber + ")" + ", pin2=" + pin2);

        int newid = updateEfForIccType(efid);

        checkThread();
        Request updateRequest = new Request();
        synchronized (updateRequest) {
            Message response = mBaseHandler.obtainMessage(EVENT_UPDATE_DONE, updateRequest);
            AdnRecordEx oldAdn = null;
            AdnRecordEx newAdn = null;
            if (mAdnCache == null) {
                loge("updateAdnRecordsInEfBySearchEx failed because mAdnCache is null");
                return -1;
            }
            if (newid == IccConstantsEx.EF_LND) {
                logd("insertLNDRecord: efid = 0x" + Integer.toHexString(efid).toUpperCase()
                        + " (" + newTag + "," + newPhoneNumber + ")" + ", pin2=" + pin2);
                oldAdn = new AdnRecordEx(oldTag, oldPhoneNumber);
                newAdn = new AdnRecordEx(newTag, newPhoneNumber);
                mAdnCache.insertLndBySearch(newid, oldAdn, newAdn, pin2, response);
            } else if (newid == IccConstantsEx.EF_PBR) {
                oldAdn = new AdnRecordEx(oldTag, oldPhoneNumber, oldEmailList,
                        oldAnr, "", oldSne, oldGrp, "");
                newAdn = new AdnRecordEx(newTag, newPhoneNumber, newEmailList,
                        newAnr, newAas, newSne, newGrp, newGas);

                mAdnCache.updateUSIMAdnBySearch(newid, oldAdn, newAdn, pin2,
                        response);

            } else {
                oldAdn = new AdnRecordEx(oldTag, oldPhoneNumber);
                newAdn = new AdnRecordEx(newTag, newPhoneNumber);
                mAdnCache.updateAdnBySearchEx(newid, oldAdn, newAdn, pin2,
                        response);
            }
            waitForResult(updateRequest);
        }
        return (int) updateRequest.mResult;
    }

    public int updateAdnRecordsInEfByIndex(int efid, String newTag,
            String newPhoneNumber, String[] newEmailList, String newAnr,
            String newAas, String newSne, String newGrp, String newGas,
            int index, String pin2) {

        if (mPhone.getContext().checkCallingOrSelfPermission(
                android.Manifest.permission.WRITE_CONTACTS) != PackageManager.PERMISSION_GRANTED) {
            throw new SecurityException(
                    "Requires android.permission.WRITE_CONTACTS permission");
        }

        logd("updateAdnRecordsInEfByIndexEx: efid = 0x" + Integer.toHexString(efid).toUpperCase()
                + " (" + newTag + "," + newPhoneNumber + ")" + ", index=" + index);

        int newid = updateEfForIccType(efid);

        logd("updateAdnRecordsInEfByIndexEx: newid = 0x" + Integer.toHexString(newid).toUpperCase());

        checkThread();
        Request updateRequest = new Request();
        synchronized (updateRequest) {
            Message response = mBaseHandler.obtainMessage(EVENT_UPDATE_DONE, updateRequest);
            AdnRecordEx oldAdn = null;
            AdnRecordEx newAdn = null;
            if (mAdnCache == null) {
                loge("updateAdnRecordsInEfByIndexEx failed because mAdnCache is null");
                return -1;
            }
            if (newid == IccConstantsEx.EF_PBR) {
                newAdn = new AdnRecordEx(newTag, newPhoneNumber, newEmailList,
                        newAnr, newAas, newSne, newGrp, newGas);
                mAdnCache.updateUSIMAdnByIndex(newid, index, newAdn, pin2,
                        response);
            } else {
                newAdn = new AdnRecordEx(newTag, newPhoneNumber);
                mAdnCache.updateAdnByIndexEx(newid, newAdn, index, pin2,
                        response);
            }
            waitForResult(updateRequest);
        }
        return (int) updateRequest.mResult;
    }

    public synchronized List<String> getGasInEf() {
        logd("getGasInEf");
        if (mPhone.getContext().checkCallingOrSelfPermission(
                android.Manifest.permission.READ_CONTACTS) != PackageManager.PERMISSION_GRANTED) {
            throw new SecurityException(
                    "Requires android.permission.READ_CONTACTS permission");
        }
        if (!isHasUsimApp()) {
            loge("Can not get gas from a sim card");
            return null;
        }
        if (mAdnCache == null) {
            loge("getGasInEf failed because mAdnCache is null");
            return new ArrayList<String>();
        }
        return mAdnCache.loadGasFromUsim();
    }

    public int updateUsimGroupBySearch(String oldName, String newName) {

        if (mPhone.getContext().checkCallingOrSelfPermission(
                android.Manifest.permission.WRITE_CONTACTS) != PackageManager.PERMISSION_GRANTED) {
            throw new SecurityException(
                    "Requires android.permission.WRITE_CONTACTS permission");
        }
        if (mAdnCache == null) {
            loge("updateUsimGroupBySearchEx failed because mAdnCache is null");
            return -1;
        }
        return mAdnCache.updateGasBySearch(oldName, newName);
    }

    public int updateUsimGroupByIndex(String newName, int groupId) {

        if (mPhone.getContext().checkCallingOrSelfPermission(
                android.Manifest.permission.WRITE_CONTACTS) != PackageManager.PERMISSION_GRANTED) {
            throw new SecurityException(
                    "Requires android.permission.WRITE_CONTACTS permission");
        }
        if (mAdnCache == null) {
            loge("updateUsimGroupById failed because mAdnCache is null");
            return -1;
        }
        return mAdnCache.updateGasByIndex(newName, groupId);
    }

    public boolean isApplicationOnIcc(int type) {
        /**
         * UNISOC: add for bug1028767 support CDMA,change the interface of support USIM
         * @{
         */
        IccCardApplicationStatus.AppType simType = IccCardApplicationStatus.AppType.APPTYPE_SIM;
        if((IccCardApplicationStatus.AppType.values()[type] == simType
                && mPhone.getCurrentUiccAppType() == simType)
                || isHasUsimApp()) {
            return true;
        }
        /*
         * @}
         */
        return false;
    }

    private UsimPhoneBookManager getUsimPhoneBookManager() {
        /**
         * UNISOC: add for bug1033036 java.lang.NullPointerException* @{
         */
        if (mAdnCache != null && isHasUsimApp()) {
            return mAdnCache.getUsimPhoneBookManager();
        }
        /*
         * @}
         */
        return null;
    }
    /**
     * UNISOC: modify for bug1036307 Remove redundant judgments* @{
     */
    private int[] getUsimAdnRecordsSize() {
        logd("getUsimAdnRecordsSize");
        UsimPhoneBookManager mUsimPhoneBookManager = getUsimPhoneBookManager();
        if (mUsimPhoneBookManager == null) {
            return null;
        }
        return mUsimPhoneBookManager.getAdnRecordsSize();
    }

    public int[] getEmailRecordsSize() {
        logd("getEmailRecordsSize");
        UsimPhoneBookManager mUsimPhoneBookManager = getUsimPhoneBookManager();
        if (mUsimPhoneBookManager == null) {
            return null;
        }
        return mUsimPhoneBookManager.getEmailRecordsSize();
    }

    public int[] getAnrRecordsSize() {
        logd("getAnrRecordsSize");
        UsimPhoneBookManager mUsimPhoneBookManager = getUsimPhoneBookManager();
        if (mUsimPhoneBookManager == null) {
            return null;
        }
        int efid;
        int[] recordSizeAnr, recordSizeTotal = new int[3];
        for (int num = 0; num < mUsimPhoneBookManager.getNumRecs(); num++) {
            efid = mUsimPhoneBookManager.findEFAnrInfo(num);
            if (efid <= 0) {
                return null;
            }
            recordSizeAnr = getRecordsSize(efid);
            recordSizeTotal[0] = recordSizeAnr[0];
            recordSizeTotal[1] += recordSizeAnr[1];
            recordSizeTotal[2] += recordSizeAnr[2];
        }
        return recordSizeTotal;
    }

    public int getEmailNum() {
        int[] record = null;
        UsimPhoneBookManager mUsimPhoneBookManager = getUsimPhoneBookManager();
        if (mUsimPhoneBookManager == null) {
            return 0;
        }
        return mUsimPhoneBookManager.getEmailNum();
    }

    public int getGroupNum() {
        UsimPhoneBookManager mUsimPhoneBookManager = getUsimPhoneBookManager();
        if (mUsimPhoneBookManager == null) {
            return 0;
        }
        return mUsimPhoneBookManager.getGroupNum();
    }

    public int getAnrNum() {
        UsimPhoneBookManager mUsimPhoneBookManager = getUsimPhoneBookManager();
        if (mUsimPhoneBookManager == null) {
            return 0;
        }
        return mUsimPhoneBookManager.getAnrNum();
    }

    //UNISOC: modify for bug1115157, code optimization
    public int getEmailMaxLen() {
        logd("getEmailMaxLen");
        UsimPhoneBookManager mUsimPhoneBookManager = getUsimPhoneBookManager();
        if (mUsimPhoneBookManager == null) {
            return 0;
        }
        return mUsimPhoneBookManager.getEmailMaxLen();
    }

    // If the telephone number or SSC is longer than 20 digits, the first 20
    // digits are stored in this data item and the remainder is stored in an
    // associated record in the EFEXT1.
    public int getPhoneNumMaxLen() {
        UsimPhoneBookManager mUsimPhoneBookManager = getUsimPhoneBookManager();
        if (mUsimPhoneBookManager == null) {
            return AdnRecordEx.MAX_LENTH_NUMBER;
        } else {
            return mUsimPhoneBookManager.getPhoneNumMaxLen();
        }
    }

    public int getUsimGroupNameMaxLen() {
        logd("getGroupNameMaxLen");
        UsimPhoneBookManager mUsimPhoneBookManager = getUsimPhoneBookManager();
        if (mUsimPhoneBookManager == null) {
            return -1;
        }
        int gasEfId = mUsimPhoneBookManager.findEFGasInfo();
        int[] gasSize = getRecordsSize(gasEfId);
        if (gasSize == null)
            return -1;
        return gasSize[0];
    }

    //UNISOC: modify for bug1115157, code optimization
    public int[] getUsimGroupSize() {
        logd("getUsimGroupSize");
        UsimPhoneBookManager mUsimPhoneBookManager = getUsimPhoneBookManager();
        if (mUsimPhoneBookManager == null) {
            loge("mUsimPhoneBookManager == null");
            return null;
        }
        int gasEfId = mUsimPhoneBookManager.findEFGasInfo();
        int[] gasSize = getRecordsSize(gasEfId);
        return gasSize;
    }

    public int[] getAvalibleEmailCount(String name, String number,
            String[] emails, String anr, int[] emailNums) {
        UsimPhoneBookManager mUsimPhoneBookManager = getUsimPhoneBookManager();
        if (mUsimPhoneBookManager == null) {
            return null;
        }
        return mUsimPhoneBookManager.getAvalibleEmailCount(name, number,
                emails, anr, emailNums);
    }

    public int[] getAvalibleAnrCount(String name, String number,
            String[] emails, String anr, int[] anrNums) {
        int[] record = null;
        UsimPhoneBookManager mUsimPhoneBookManager = getUsimPhoneBookManager();
        if (mUsimPhoneBookManager == null) {
            return null;
        }
        return mUsimPhoneBookManager.getAvalibleAnrCount(name, number,
                emails, anr, anrNums);
    }
    /*
     * @}
     */

    public int getInsertIndex() {
        if (mAdnCache == null) {
            loge("getInsertIndex:adn cache is null");
            return -1;
        }
        return mAdnCache.getInsertId();
    }

    /* UNISOC: Bug985089 Phonebook reads sim contact optimization. @{ */
    /*private void setReadRecordOfEfid(int efid, boolean readSuccess, List<AdnRecordEx> records) {
        switch (efid) {
            case IccConstantsEx.EF_ADN:
            case IccConstantsEx.EF_PBR:
                mReadAdnRecordSuccess = readSuccess;
                mAdnRecordsEx = records;
                break;
            case IccConstantsEx.EF_SDN:
                mReadSdnRecordSuccess = readSuccess;
                mSdnRecordsEx = records;
                break;
            case IccConstantsEx.EF_FDN:
                mReadFdnRecordSuccess = readSuccess;
                mFdnRecordsEx = records;
                break;
            case IccConstantsEx.EF_LND:
                mReadLndRecordSuccess = readSuccess;
                mLndRecordsEx = records;
                break;
        }
    }

    private List<AdnRecordEx> getReadRecordResult(int efid) {
        switch (efid) {
            case IccConstantsEx.EF_ADN:
            case IccConstantsEx.EF_PBR:
                return mAdnRecordsEx;
            case IccConstantsEx.EF_SDN:
                return mSdnRecordsEx;
            case IccConstantsEx.EF_FDN:
                return mFdnRecordsEx;
            case IccConstantsEx.EF_LND:
                return mLndRecordsEx;
            default:
                return null;
        }
    }

    private boolean getReadRecordOfEfid(int efid) {
        switch (efid) {
        case IccConstantsEx.EF_ADN:
        case IccConstantsEx.EF_PBR:
                return mReadAdnRecordSuccess;
        case IccConstantsEx.EF_SDN:
                return mReadSdnRecordSuccess;
        case IccConstantsEx.EF_FDN:
                return mReadFdnRecordSuccess;
        case IccConstantsEx.EF_LND:
                return mReadLndRecordSuccess;
        default: return false;
       }
    }*/
    /* @} */

    public synchronized List<String> getAasInEf() {
        logd("getAasInEf");
        if (mPhone.getContext().checkCallingOrSelfPermission(
                android.Manifest.permission.READ_CONTACTS) != PackageManager.PERMISSION_GRANTED) {
            throw new SecurityException(
                    "Requires android.permission.READ_CONTACTS permission");
        }
        if (!isHasUsimApp()) {
            loge("Can not get aas from a sim card");
            return null;
        }
        if (mAdnCache == null) {
            loge("getAasInEf failed because mAdnCache is null");
            return new ArrayList<String>();
        }
        return mAdnCache.loadAasFromUsim();
    }

    public int updateUsimAasBySearch(String oldName, String newName) {
        if (mPhone.getContext().checkCallingOrSelfPermission(
                android.Manifest.permission.WRITE_CONTACTS) != PackageManager.PERMISSION_GRANTED) {
            throw new SecurityException(
                    "Requires android.permission.WRITE_CONTACTS permission");
        }
        if (mAdnCache == null) {
            loge("updateUsimAasBySearch failed because mAdnCache is null");
            return -1;
        }
        return mAdnCache.updateAasBySearch(oldName, newName);
    }

    public int updateUsimAasByIndex(String newName, int aasIndex) {
        if (mPhone.getContext().checkCallingOrSelfPermission(
                android.Manifest.permission.WRITE_CONTACTS) != PackageManager.PERMISSION_GRANTED) {
            throw new SecurityException(
                    "Requires android.permission.WRITE_CONTACTS permission");
        }
        if (mAdnCache == null) {
            loge("updateUsimAasByIndex failed because mAdnCache is null");
            return -1;
        }
        return mAdnCache.updateAasByIndex(newName, aasIndex);
    }

    public synchronized int getSneSize() {
        logd("getSneSize");
        if (mPhone.getContext().checkCallingOrSelfPermission(
                android.Manifest.permission.READ_CONTACTS) != PackageManager.PERMISSION_GRANTED) {
            throw new SecurityException(
                    "Requires android.permission.READ_CONTACTS permission");
        }
        if (!isHasUsimApp()) {
            loge("Can not get sne size from a sim card");
            return 0;
        }
        if (mAdnCache == null) {
            loge("getSneSize failed because mAdnCache is null");
            return 0;
        }
        return mAdnCache.getSneSize();
    }

    public int[] getSneLength() {
        if (mPhone.getContext().checkCallingOrSelfPermission(
                android.Manifest.permission.READ_CONTACTS) != PackageManager.PERMISSION_GRANTED) {
            throw new SecurityException(
                    "Requires android.permission.READ_CONTACTS permission");
        }
        if (!isHasUsimApp()) {
            loge("Can not get sne length from a sim card");
            return null;
        }
        if (mAdnCache == null) {
            loge("getSneLength failed because mAdnCache is null");
            return null;
        }
        return mAdnCache.getSneLength();
    }
    /**
     * UNISOC: Add for bug, Optimize the speed for importing sim contacts.
     * When sim loaded, read and cache PHB
     */
    private IccRecords mIccRecords = null;
    private UiccCardApplication mUiccApplication = null;
    private UiccController mUiccController;
    private UiccCard mUiccCard = null;
    private int mPhoneId = -1;
    private static final int EVENT_ICC_CHANGED            = 100;
    private static final int EVENT_RECORDS_LOADED         = 101;
    private HandlerThread mCachePHBThread;
    private CachePHBHandler mCachePHBHandler;
    private void onUpdateIccAvailability() {
        if (mUiccController == null || mPhoneId == -1) {
            return;
        }
        UiccCard newCard = mUiccController.getUiccCard(mPhoneId);
        UiccCardApplication newApp = null;
        IccRecords newRecords = null;
        if (newCard != null) {
            newApp = newCard.getApplication(UiccController.APP_FAM_3GPP);
            if (newApp != null) {
                newRecords = newApp.getIccRecords();
            }
        }
        if (mIccRecords != newRecords || mUiccApplication != newApp
                || mUiccCard != newCard) {
            logd("Icc changed. Reregestering.");
            unregisterUiccCardEvents();
            mIccRecords = newRecords;
            mUiccCard = newCard;
            mUiccApplication = newApp;
            registerUiccCardEvents();
        }
    }

    private void registerUiccCardEvents() {
        if (mIccRecords != null) {
            mIccRecords.registerForRecordsLoaded(mCachePHBHandler, EVENT_RECORDS_LOADED, null);
        }
    }

    private void unregisterUiccCardEvents() {
        if (mIccRecords != null) {
            mIccRecords.unregisterForRecordsLoaded(mCachePHBHandler);
        }
    }
    protected class CachePHBHandler extends Handler {
        public CachePHBHandler(Looper looper) {
            super(looper);
        }

        @Override
        public void handleMessage(Message msg) {
            switch (msg.what) {
            case EVENT_ICC_CHANGED:
                logd("EVENT_ICC_CHANGED");
                onUpdateIccAvailability();
                break;
            case EVENT_RECORDS_LOADED:
                logd("RECORDS LOADED Read PHB");
                //UNISOC: add for bug1189141/985089, Optimize the speed of importing sim contacts
                mIsPhonebookLoading = true;
                getAdnRecordsInEfEx(IccConstantsEx.EF_ADN);
                getAdnRecordsInEfEx(IccConstantsEx.EF_SDN);
                getGasInEf();
                synchronized (mLockForReadSizes) {
                    mLockForReadSizes.notifyAll();
                }
                mIsPhonebookLoading = false;
                break;
            }
        }
    };
    private void registerForIccChanged(Phone phone) {
        mPhoneId = phone.getPhoneId();
        mCachePHBThread = new HandlerThread("RunningState:Background");
        mCachePHBThread.start();
        mCachePHBHandler = new CachePHBHandler(mCachePHBThread.getLooper());
        if(mUiccController != null){
            mUiccController.registerForIccChanged(mCachePHBHandler, EVENT_ICC_CHANGED, null);
        }
    }

    /**
     * UNISOC: add for bug1028767 support CDMA,change the interface of support USIM
     * @{
     */
    private boolean isHasUsimApp() {
        try {
            return mPhone.getUiccCard().getUiccProfile().isApplicationOnIcc(IccCardApplicationStatus.AppType.APPTYPE_USIM);
        } catch (NullPointerException e) {
            logd("isHasUsimApp NullPointException : " + e);
            return false;
        }
    }
    /*
     * @}
     */
}
