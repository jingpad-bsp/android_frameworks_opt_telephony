package com.android.internal.telephony.uicc;

public class OplNrRecord extends OplRecord{
    public int[] mOplNrplmn = {
            0, 0, 0, 0, 0, 0
    };
    public int mOplNrtac1;
    public int mOplNrtac2;
    public int PNNrecordnum;

    public OplNrRecord(byte[] record) {
        super(record);
        OplNrplmn(record);
        mOplNrtac1 = ((record[3] & 0xff) << 16) | ((record[4] & 0xff) << 8) | (record[5] & 0xff);
        mOplNrtac2 = ((record[6] & 0xff) << 16) | ((record[7] & 0xff) << 8) | (record[8] & 0xff);
        PNNrecordnum = (short) (record[9] & 0xff);
    }

    public void OplNrplmn(byte[] record) {
        // getMCC
        mOplNrplmn[0] = record[0] & 0x0f;
        mOplNrplmn[1] = (record[0] >> 4) & 0x0f;
        mOplNrplmn[2] = record[1] & 0x0f;

        // GetMNC
        mOplNrplmn[3] = record[2] & 0x0f;
        mOplNrplmn[4] = (record[2] >> 4) & 0x0f;

        mOplNrplmn[5] = (record[1] >> 4) & 0x0f;
        if (0x0f == mOplNrplmn[5]) {
            mOplNrplmn[5] = 0;
        }
    }

    public int getPnnRecordNum() {
        return PNNrecordnum;
    }

    @Override
    public String toString() {
        return "OPL Record mOplNrplmn = " + Integer.toHexString(mOplNrplmn[0])
                + Integer.toHexString(mOplNrplmn[1])
                + Integer.toHexString(mOplNrplmn[2]) + Integer.toHexString(mOplNrplmn[3])
                + Integer.toHexString(mOplNrplmn[4])
                + Integer.toHexString(mOplNrplmn[5]) + ", mOplNrtac1 =" + mOplNrtac1 + ", mOplNrtac2 ="
                + mOplNrtac2
                + " ,PNNrecordnum = " + PNNrecordnum;
    }

}
