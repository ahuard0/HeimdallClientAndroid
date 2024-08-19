package com.huard.heimdallclientandroid;

import android.util.Log;

import androidx.annotation.NonNull;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

public class HeaderIQ {
    // Constants
    private static final int HEADER_SIZE = 1024;
    private static final int RESERVED_BYTES = 192;

    @SuppressWarnings("unused")
    public static final int FRAME_TYPE_DATA = 0;
    @SuppressWarnings("unused")
    public static final int FRAME_TYPE_DUMMY = 1;
    @SuppressWarnings("unused")
    public static final int FRAME_TYPE_RAMP = 2;
    @SuppressWarnings("unused")
    public static final int FRAME_TYPE_CAL = 3;
    @SuppressWarnings("unused")
    public static final int FRAME_TYPE_TRIGW = 4;
    @SuppressWarnings("unused")
    public static final int FRAME_TYPE_EMPTY = 5;

    public static final int SYNC_WORD = 0x2BF7B95A;

    // Header fields
    private int syncWord;
    private int frameType;
    private String hardwareId;
    private int unitId;
    private int activeAntChs;
    private int iooType;
    private long rfCenterFreq;
    private long adcSamplingFreq;
    private long samplingFreq;
    private long cpiLength;
    private long timeStamp;
    private int daqBlockIndex;
    private int cpiIndex;
    private long extIntegrationCntr;
    private int dataType;
    private int sampleBitDepth;
    private int adcOverdriveFlags;
    private final int[] ifGains = new int[32];
    private int delaySyncFlag;
    private int iqSyncFlag;
    private int syncState;
    private int noiseSourceState;
    private int[] reserved = new int[RESERVED_BYTES];
    private int headerVersion;

    // Logger tag
    private static final String TAG = "HeaderIQ";

    public HeaderIQ() {
        // Initialize header fields to default values
        syncWord = 0;
        frameType = FRAME_TYPE_EMPTY;
        hardwareId = "";
        unitId = 0;
        activeAntChs = 0;
        iooType = 0;
        rfCenterFreq = 0;
        adcSamplingFreq = 0;
        samplingFreq = 0;
        cpiLength = 0;
        timeStamp = 0;
        daqBlockIndex = 0;
        cpiIndex = 0;
        extIntegrationCntr = 0;
        dataType = 0;
        sampleBitDepth = 0;
        adcOverdriveFlags = 0;
        Arrays.fill(ifGains, 0);
        delaySyncFlag = 0;
        iqSyncFlag = 0;
        syncState = 0;
        noiseSourceState = 0;
        Arrays.fill(reserved, 0);
        headerVersion = 0;
    }

    public void decodeHeader(byte[] iqHeaderByteArray) {
        ByteBuffer buffer = ByteBuffer.wrap(iqHeaderByteArray);
        buffer.order(ByteOrder.LITTLE_ENDIAN); // Ensure correct byte order
        buffer.rewind();

        syncWord = buffer.getInt();
        frameType = buffer.getInt();

        byte[] hardwareIdBytes = new byte[16];
        buffer.get(hardwareIdBytes);
        hardwareId = new String(hardwareIdBytes, StandardCharsets.UTF_8).trim();

        unitId = buffer.getInt();
        activeAntChs = buffer.getInt();
        iooType = buffer.getInt();

        rfCenterFreq = decodeLong(buffer);
        adcSamplingFreq = decodeLong(buffer);
        samplingFreq = decodeLong(buffer);

        cpiLength = decodeLong(buffer);
        timeStamp = decodeLong(buffer);

        daqBlockIndex = (int)decodeLong(buffer);
        cpiIndex = buffer.getInt();
        extIntegrationCntr = decodeLong(buffer);

        dataType = buffer.getInt();
        sampleBitDepth = buffer.getInt();
        adcOverdriveFlags = buffer.getInt();

        // Decode the ifGains array (32 unsigned ints)
        for (int i = 0; i < 32; i++) {
            ifGains[i] = buffer.getInt();
        }

        delaySyncFlag = buffer.getInt();
        iqSyncFlag = buffer.getInt();
        syncState = buffer.getInt();
        noiseSourceState = buffer.getInt();

        // Decode the reserved bytes (self.reserved_bytes in Python)
        reserved = new int[RESERVED_BYTES];
        for (int i = 0; i < RESERVED_BYTES; i++) {
            reserved[i] = buffer.getInt();
        }

        headerVersion = buffer.getInt();
    }

    private long decodeLong(@NonNull ByteBuffer buffer) {
        long highBits = buffer.getInt() & 0xFFFFFFFFL << 32;
        long lowBits = (buffer.getInt() & 0xFFFFFFFFL);
        return lowBits | highBits;
    }

    @SuppressWarnings("unused")
    public byte[] encodeHeader() {
        ByteBuffer buffer = ByteBuffer.allocate(HEADER_SIZE);
        buffer.order(ByteOrder.LITTLE_ENDIAN);

        buffer.putInt(syncWord);
        buffer.putInt(frameType);
        buffer.put(Arrays.copyOf(hardwareId.getBytes(StandardCharsets.UTF_8), 16));
        buffer.putInt(unitId);
        buffer.putInt(activeAntChs);
        buffer.putInt(iooType);
        buffer.putLong(rfCenterFreq);
        buffer.putLong(adcSamplingFreq);
        buffer.putLong(samplingFreq);
        buffer.putLong(cpiLength);
        buffer.putLong(timeStamp);
        buffer.putInt(daqBlockIndex);
        buffer.putInt(cpiIndex);
        buffer.putLong(extIntegrationCntr);
        buffer.putInt(dataType);
        buffer.putInt(sampleBitDepth);
        buffer.putInt(adcOverdriveFlags);
        for (int i = 0; i < 32; i++) {
            buffer.putInt(ifGains[i]);
        }
        buffer.putInt(delaySyncFlag);
        buffer.putInt(iqSyncFlag);
        buffer.putInt(syncState);
        buffer.putInt(noiseSourceState);
        for (int i = 0; i < RESERVED_BYTES; i++) {
            buffer.putInt(reserved[i]);
        }
        buffer.putInt(headerVersion);

        return buffer.array();
    }

    @SuppressWarnings("unused")
    public void dumpHeader() {
        Log.i(TAG, "Sync word: " + syncWord);
        Log.i(TAG, "Header version: " + headerVersion);
        Log.i(TAG, "Frame type: " + frameType);
        Log.i(TAG, "Hardware ID: " + hardwareId);
        Log.i(TAG, "Unit ID: " + unitId);
        Log.i(TAG, "Active antenna channels: " + activeAntChs);
        Log.i(TAG, "Illuminator type: " + iooType);
        Log.i(TAG, String.format("RF center frequency: %.2f MHz", rfCenterFreq / 1e6));
        Log.i(TAG, String.format("ADC sampling frequency: %.2f MHz", adcSamplingFreq / 1e6));
        Log.i(TAG, String.format("IQ sampling frequency: %.2f MHz", samplingFreq / 1e6));
        Log.i(TAG, "CPI length: " + cpiLength);
        Log.i(TAG, "Unix Epoch timestamp: " + timeStamp);
        Log.i(TAG, "DAQ block index: " + daqBlockIndex);
        Log.i(TAG, "CPI index: " + cpiIndex);
        Log.i(TAG, "Extended integration counter: " + extIntegrationCntr);
        Log.i(TAG, "Data type: " + dataType);
        Log.i(TAG, "Sample bit depth: " + sampleBitDepth);
        Log.i(TAG, "ADC overdrive flags: " + adcOverdriveFlags);
        for (int i = 0; i < 32; i++) {
            Log.i(TAG, "Ch: " + i + " IF gain: " + (ifGains[i] / 10.0) + " dB");
        }
        Log.i(TAG, "Delay sync flag: " + delaySyncFlag);
        Log.i(TAG, "IQ sync flag: " + iqSyncFlag);
        Log.i(TAG, "Sync state: " + syncState);
        Log.i(TAG, "Noise source state: " + noiseSourceState);
    }

    public long getCpiLength() {
        return cpiLength;
    }

    public int getActiveAntChs() {
        return activeAntChs;
    }

    public int getSampleBitDepth() {
        return sampleBitDepth;
    }

    public int getFrameType() { return frameType; }

    public long getSamplingFreq() { return samplingFreq; }

    public int getSyncState() { return syncState; }

    public int getSyncWord() { return syncWord; }

    public int getNoiseSourceState() { return noiseSourceState; }

    public int getIqSyncFlag() { return iqSyncFlag; }

    public int getDataType() { return dataType; }

    @SuppressWarnings("unused")
    public boolean checkSyncWord() {
        return syncWord == SYNC_WORD;
    }
}
