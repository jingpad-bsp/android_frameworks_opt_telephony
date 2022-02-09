
package com.android.internal.telephony.uicc;

import com.android.internal.telephony.gsm.SimTlv;

public class PnnRecord {
    private String mPnnLongName;
    private String mPnnShortName;

    static final int TAG_FULL_NETWORK_NAME = 0x43;
    static final int TAG_SHORT_NETWORK_NAME = 0x45;

    public PnnRecord(byte[] data) {
        SimTlv tlv = new SimTlv(data, 0, data.length);

        for (; tlv.isValidObject(); tlv.nextObject()) {
            // PNN long name
            if (tlv.getTag() == TAG_FULL_NETWORK_NAME) {
                mPnnLongName = IccUtils.networkNameToString(tlv.getData(), 0, tlv.getData().length);
            }
            // PNN short name
            if (tlv.getTag() == TAG_SHORT_NETWORK_NAME) {
                mPnnShortName = IccUtils.networkNameToString(tlv.getData(), 0, tlv.getData().length);
                break;
            }
        }
    }

    public String getLongName() {
        return mPnnLongName;
    }

    public String getShortName() {
        return mPnnShortName;
    }

    public String toString() {
        return "PnnLongName = " + mPnnLongName + ", PnnShortName = " + mPnnShortName;
    }
}
