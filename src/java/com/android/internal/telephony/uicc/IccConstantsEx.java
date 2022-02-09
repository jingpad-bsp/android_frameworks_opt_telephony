
package com.android.internal.telephony.uicc;

public interface IccConstantsEx extends IccConstants {
    static final int EF_DIR = 0x2F00;
    static final int EF_LND = 0x6F44;
    static final int EF_CC = 0x4F23;
    // Add for TS31.121 8.1.2
    static final int EF_PSC = 0x4F22;
    static final int EF_PUID = 0x4F24;
    static final int EF_ECC = 0x6FB7;
    // ISIM access
    static final String DF_ADFISIM = "7FFF";
    // Orange request
    static final int EF_SMSP = 0x6F42; // Short Message Service Parameters
    // UPLMN
    static final int EF_PLMN_ACT = 0x6F60;
    static final int EF_PLMN_SEL = 0x6F30;
    // PS DATA OFF
    static final int EF_PS_DATA_OFF = 0x6FF9;

    static final int UST_PS_DATA_OFF = 117;
    static final int UST_PS_DATA_OFF_INFO = 131;

    //5G opl file
    static final int EF_OPLNR = 0x4F08;
    static final String DF_NRS = "5FC0";
    static final int NRS_OPERATOR_PLMN_LIST = 128;
}
