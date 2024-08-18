package com.huard.heimdallclientandroid;

import android.util.Log;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class ControlClient {
    private static final String TAG = "ControlClient";

    // Command words
    private static final byte[] CMD_INIT = "INIT".getBytes();
    private static final byte[] CMD_EXIT = "EXIT".getBytes();
    private static final byte[] CMD_STHU = "STHU".getBytes();
    private static final byte[] CMD_FREQ = "FREQ".getBytes();
    private static final byte[] CMD_GAIN = "GAIN".getBytes();
    private static final byte[] CMD_AGC = "AGC ".getBytes();

    // List of acceptable gain values
    private static final List<Integer> acceptableGains = Arrays.asList(
            0, 9, 14, 27, 37, 77, 87, 125, 144, 157, 166, 197, 207, 229, 254, 280, 297, 328, 338, 364, 372, 386, 402, 421, 434, 439, 445, 480, 496
    );

    private final String host;
    private final int port;
    private Socket socket;
    private InputStream inputStream;
    private OutputStream outputStream;
    private final ControlClientListener controlClientListener;
    private final ExecutorService executorService;

    public ControlClient(ControlClientListener listener, String host, int port) {
        this.controlClientListener = listener;
        this.host = host;
        this.port = port;
        this.executorService = Executors.newSingleThreadExecutor();
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
                    inputStream = socket.getInputStream();
                    outputStream = socket.getOutputStream();
                    Log.i(TAG, "Connected to control port " + port);
                    if (controlClientListener != null) {
                        controlClientListener.notifyControlClient("Connected to control port " + port);
                    }
                }
            } catch (IOException e) {
                Log.e(TAG, "Error connecting to control port", e);
            }
        });
    }

    public void disconnect() {
        executorService.execute(() -> {
            try {
                if (socket != null) {
                    socket.close();
                }
                socket = null;
                inputStream = null;
                outputStream = null;
                Log.i(TAG, "Disconnected from control port " + port);
                if (controlClientListener != null) {
                    controlClientListener.notifyControlClient("Disconnected from control port " + port);
                }
            } catch (IOException e) {
                Log.e(TAG, "Error disconnecting from control port", e);
            }
        });
    }

    public void sendInit() {
        sendMessage(CMD_INIT, new byte[0]);
    }

    public void sendExit() {
        sendMessage(CMD_EXIT, new byte[0]);
    }

    @SuppressWarnings("unused")
    public void sendAgc() {
        sendMessage(CMD_AGC, new byte[0]);
    }

    public void sendSquelchThreshold(float thresholdValue) {
        ByteBuffer buffer = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN);
        buffer.putFloat(thresholdValue);
        sendMessage(CMD_STHU, buffer.array());
    }

    public void sendFrequency(float frequency_MHz) {
        ByteBuffer buffer = ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN);
        long frequency = (long) (frequency_MHz*1E6);
        buffer.putLong(frequency);
        sendMessage(CMD_FREQ, buffer.array());
    }

    public void sendGain(int[] gains) {
        ByteBuffer buffer = ByteBuffer.allocate(20).order(ByteOrder.LITTLE_ENDIAN);
        for (int gain : gains) {
            int nearestGain = findNearestGain(gain);
            buffer.putInt(nearestGain);
        }
        sendMessage(CMD_GAIN, buffer.array());
    }

    public int findNearestGain(int value) {
        return acceptableGains.stream().min(Comparator.comparingInt(g -> Math.abs(g - value))).orElse(0);
    }

    private void sendMessage(byte[] command, byte[] parameters) {
        executorService.execute(() -> {
            byte[] message = createMessage(command, parameters);

            try {
                outputStream.write(message);
                outputStream.flush();
                byte[] response = new byte[128];
                int readBytes = inputStream.read(response);
                if (readBytes > 0) {
                    String responseMessage = new String(response).trim();
                    if (controlClientListener != null) {
                        controlClientListener.notifyControlClient("Received response: " + responseMessage);
                    }
                    Log.i(TAG, "Received response: " + Arrays.toString(response));
                }
            } catch (IOException e) {
                Log.e(TAG, "Error sending message", e);
            }
        });
    }

    private byte[] createMessage(byte[] command, byte[] parameters) {
        ByteBuffer buffer = ByteBuffer.allocate(128).order(ByteOrder.LITTLE_ENDIAN);
        buffer.put(Arrays.copyOf(command, 4));
        buffer.put(Arrays.copyOf(parameters, 124));
        return buffer.array();
    }
}
