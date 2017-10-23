package com.lakxtab.android.quircdemo;

import java.util.List;

/**
 * Created by lak on 10/20/17.
 */

public class QuircHelper
{
    public static class QrCode
    {
        int mVersion;
        int mEccLevel;
        int mMask;
        int mDataType;
        byte [] mPayload;
        int mPayloadLength;
        long mEci;

        // Called from JNI
        QrCode(int ver, int ecc, int mask, int dataType,
               byte [] payload, int payloadLen, int eci)
        {
            mVersion = ver;
            mEccLevel = ecc;
            mMask = mask;
            mDataType = dataType;
            mPayload = payload;
            mPayloadLength = payloadLen;
            mEci = eci;
        }
    }

    native boolean prepare(int width, int height);
    native boolean resizeFrame(int width, int height);
    /*
     * If writeback become true, frame will be write back as a
     * binary image, it is useful about debug.
     */
    native int detectGrids(byte [] frame, boolean writeback);
    native int decode(List<QrCode> result);
    native void release();

    static
    {
        System.loadLibrary("native-lib");
    }
}
