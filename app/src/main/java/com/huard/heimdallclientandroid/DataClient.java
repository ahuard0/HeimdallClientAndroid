package com.huard.heimdallclientandroid;

import android.util.Log;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class DataClient {
    private static final String TAG = "DataClient";

    private final String host;
    private final int port;
    private Socket socket;
    private InputStream inputStream;
    private OutputStream outputStream;
    private final DataClientListener dataClientListener;
    private final ExecutorService executorService;
    private volatile boolean running = true;
    private final HeaderIQ iqHeader;

    public DataClient(DataClientListener listener, String host, int port) {
        this.dataClientListener = listener;
        this.host = host;
        this.port = port;
        this.executorService = Executors.newSingleThreadExecutor();
        this.iqHeader = new HeaderIQ();
    }

    public boolean isConnected() {
        return socket != null && socket.isConnected() && !socket.isClosed();
    }

    public void connect() {
        executorService.execute(() -> {
            try {
                if (!isConnected()) {
                    Log.i(TAG, "Attempting to access host " + host + " at port " + port);
                    socket = new Socket(host, port);
                    socket.setKeepAlive(false);
                    socket.setSoTimeout(15000);  // Set timeout for blocking operations
                    socket.setReceiveBufferSize(15*1024 * 1024);
                    inputStream = socket.getInputStream();
                    outputStream = socket.getOutputStream();
                    running = true;
                    beginStreaming();
                    listen();
                    Log.i(TAG, "Connected to data port " + port);
                }
            } catch (IOException e) {
                Log.e(TAG, "IOException on connect", e);
            }
        });
    }

    private void beginStreaming() throws IOException {
        outputStream.write("streaming".getBytes()); // Start streaming
    }

    public static float computeRMS(float[] vector) {
        float sumOfSquares = 0.0f;
        int totalElements = 0;

        for (float value : vector) {
            sumOfSquares += value * value;
            totalElements++;
        }

        float meanOfSquares = sumOfSquares / totalElements;
        return (float) Math.sqrt(meanOfSquares);
    }

    private boolean checkIntegrityIQ(float[][] iqFrame) {
        float rmsValue = computeRMS(iqFrame[4]);  // check for variations in the unused channel
        if (rmsValue < 0.01) {
            Log.i(TAG, "IQ RMS mV: " + rmsValue);
            return false;
        } else
            return true;
    }

    private void listen() {
        executorService.execute(() -> {
            try {
                while (running && !Thread.currentThread().isInterrupted()) {
                    if (isConnected()) {
                        outputStream.write("IQDownload".getBytes()); // Request IQ data

                        float[][] iqFrame = receiveIqFrame(); // Implement this to process incoming data
                        if (iqHeader.getFrameType() == HeaderIQ.FRAME_TYPE_DATA)
                            if (dataClientListener != null)
                                if (iqFrame != null)
                                    if (checkIntegrityIQ(iqFrame))
                                        dataClientListener.notifyDataClient(iqFrame, iqHeader);
                    } else {
                        Log.e(TAG, "Socket is not connected, attempting to reconnect...");
                        reconnect();
                    }
                }
            } catch (IOException e) {
                Log.e(TAG, "IOException on listenForData", e);
                reconnect();
            }
        });
    }

    @SuppressWarnings("unused")
    private boolean isConnectionHealthy() {
        try {
            // Try writing a small byte to check if the connection is alive
            outputStream.write(0);
            outputStream.flush();
            return true;
        } catch (IOException e) {
            return false;
        }
    }

    private void reconnect() {
        disconnect();  // Close the current connection
        try {
            Thread.sleep(100); // Wait before attempting to reconnect
        } catch (InterruptedException e) {
            Log.e(TAG, "Reconnection interrupted", e);
        }
        connect();  // Attempt to reconnect
    }

    /**
     *  Receives a data frame containing I/Q samples and stores them in a ndarray.
     *         Returns:
     *             self.iq_samples: An (NxM) ndarray representing complex float 32 (4+4 bytes) M-samples for N-channels.
     *         Description:
     *             This method processes a received data frame containing interleaved I/Q samples for multiple channels.
     *             It converts the byte stream into a numpy ndarray with a shape of (N, M), where:
     *                 - N is the number of channels
     *                 - M is the number of complex samples per channel
     *             The I/Q samples are complex float 32 numbers that are unpacked and reshaped into the ndarray.
     */
    private float[][] receiveIqFrame() throws IOException {
        int totalReceivedBytes = 0;
        byte[] iqHeaderBytes = new byte[1024];  // Allocate buffer for the header

        // Log debug message
        Log.d(TAG, "Starting IQ header reception");

        // Receive IQ header
        while (totalReceivedBytes < iqHeaderBytes.length) {
            int bytesRead = inputStream.read(iqHeaderBytes, totalReceivedBytes, iqHeaderBytes.length - totalReceivedBytes);
            if (bytesRead == -1) {
                Log.e(TAG, "Stream closed while receiving IQ header");
                return null;
            }
            totalReceivedBytes += bytesRead;
        }

        // Decode the header
        iqHeader.decodeHeader(iqHeaderBytes);
        Log.d(TAG, "IQ header received and decoded");

        // iqHeader.dumpHeader();  // Uncomment to view the IQ Header data

        // Calculate the payload size based on the header information
        int incomingPayloadSize = (int)iqHeader.getCpiLength() * iqHeader.getActiveAntChs() * 2 * (iqHeader.getSampleBitDepth() / 8);

        if (incomingPayloadSize > 0) {
            byte[] iqDataBytes = new byte[incomingPayloadSize];  // Allocate array for IQ data
            totalReceivedBytes = 0;

            Log.d(TAG, "Total bytes to receive: " + incomingPayloadSize);

            // Receive the IQ data
            while (totalReceivedBytes < incomingPayloadSize) {
                int bytesRead = inputStream.read(iqDataBytes, totalReceivedBytes, iqDataBytes.length - totalReceivedBytes);
                if (bytesRead == -1) {
                    Log.e(TAG, "Stream closed while receiving IQ data");
                    return null;
                }
                totalReceivedBytes += bytesRead;
            }

            Log.d(TAG, "IQ data successfully received");

            // Convert the raw bytes to complex float32 IQ samples
            ByteBuffer iqDataBuffer = ByteBuffer.wrap(iqDataBytes).order(ByteOrder.LITTLE_ENDIAN);
            float[][] iqSamples = new float[iqHeader.getActiveAntChs()][(int) iqHeader.getCpiLength() * 2];

            for (int ch = 0; ch < iqHeader.getActiveAntChs(); ch++) {
                for (int j = 0; j < iqHeader.getCpiLength(); j++) {
                    int index = 2 * j;
                    iqSamples[ch][index] = iqDataBuffer.getFloat();       // Store the real part
                    iqSamples[ch][index + 1] = iqDataBuffer.getFloat();   // Store the imaginary part
                }
            }

            if (iqHeader.getFrameType() == HeaderIQ.FRAME_TYPE_DATA) {
                Log.d(TAG, "FRAME_TYPE_DATA");
            }
            else if (iqHeader.getFrameType() == HeaderIQ.FRAME_TYPE_CAL) {
                Log.d(TAG, "FRAME_TYPE_CAL");
            }
            else if (iqHeader.getFrameType() == HeaderIQ.FRAME_TYPE_DUMMY) {
                Log.d(TAG, "FRAME_TYPE_DUMMY");
            }
            else if (iqHeader.getFrameType() == HeaderIQ.FRAME_TYPE_RAMP) {
                Log.d(TAG, "FRAME_TYPE_RAMP");
            }
            else if (iqHeader.getFrameType() == HeaderIQ.FRAME_TYPE_EMPTY) {
                Log.d(TAG, "FRAME_TYPE_EMPTY");
            }
            else if (iqHeader.getFrameType() == HeaderIQ.FRAME_TYPE_TRIGW) {
                Log.d(TAG, "FRAME_TYPE_TRIGW");
            }
            else {
                Log.d(TAG, "Unknown Frame Type");
            }

            if (iqHeader.getSyncWord() != HeaderIQ.SYNC_WORD) {
                Log.d(TAG, "Sync Word Mismatch");
            }

            if (iqHeader.getSyncState() < 1) {
                Log.d(TAG, "Out of Sync");
            }

            if (iqHeader.getNoiseSourceState() > 0) {
                Log.d(TAG, "Noise Source On");
            }

            if (iqHeader.getIqSyncFlag() < 1) {
                Log.d(TAG, "IQ Out of Sync");
            }

            if (iqHeader.getDataType() != 3) {
                Log.d(TAG, "Data Type is not 3");
            }


            return iqSamples;  // Return the array of IQ samples
        } else {
            return null;  // No data to process
        }
    }

    public void disconnect() {
        running = false; // Stop the listening loop
        try {
            if (socket != null) {
                socket.close();
            }
            inputStream = null;
            outputStream = null;
        } catch (IOException e) {
            Log.e(TAG, "IOException on disconnect", e);
        }
    }
}
