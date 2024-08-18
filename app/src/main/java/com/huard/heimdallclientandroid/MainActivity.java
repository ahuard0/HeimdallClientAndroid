package com.huard.heimdallclientandroid;

import android.content.Intent;
import android.content.res.Configuration;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.provider.Settings;
import android.util.Log;
import android.widget.Button;
import android.widget.EditText;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.TextView;

import androidx.activity.EdgeToEdge;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.app.AppCompatDelegate;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;

import com.github.mikephil.charting.charts.LineChart;
import com.github.mikephil.charting.components.Description;
import com.github.mikephil.charting.components.XAxis;
import com.github.mikephil.charting.components.YAxis;
import com.github.mikephil.charting.data.Entry;
import com.github.mikephil.charting.data.LineData;
import com.github.mikephil.charting.data.LineDataSet;
import com.github.mikephil.charting.formatter.ValueFormatter;

import org.apache.commons.math3.transform.DftNormalization;
import org.apache.commons.math3.transform.FastFourierTransformer;
import org.apache.commons.math3.transform.TransformType;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Random;

public class MainActivity extends AppCompatActivity implements ControlClientListener, DataClientListener {

    private RadioGroup channelGroup;
    private boolean isInitialized = false;
    private LineChart chart;
    private TextView statusBar;
    private EditText txtFreq;
    private static int CHANNEL = 1;
    private static int SAMPLE_SIZE = 16384;
    private static float SAMPLE_BANDWIDTH_MHz = 2.4f; // MHz
    private static final float SINUSOID_FREQUENCY_MHz = -0.3f; // MHz, for generating example FFT data on startup
    private static double[][] iqSamples;

    private static ArrayList<Double> MAX_POWER_dBm;

    private static ArrayList<ArrayList<Double>> FREQUENCY_MHz;
    private static ArrayList<ArrayList<Double>> POWER_dBm;

    private static final ArrayList<Entry> entries = new ArrayList<>();

    private static DataClient dataClient;
    private static ControlClient controlClient;

    private final String TAG = "MainActivity";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // Check current configuration mode
        int currentNightMode = getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK;
        if (currentNightMode != Configuration.UI_MODE_NIGHT_YES) {
            AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_YES);  // Set night mode and restart the activity if it's not in night mode
            recreate(); // Restart the activity to apply the night mode
            return; // Exit onCreate early to prevent initializing in the wrong mode
        }

        if (!Environment.isExternalStorageManager()) {
            Intent intent = new Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION);
            startActivity(intent);
        }

        EdgeToEdge.enable(this);
        setContentView(R.layout.activity_main);

        initialize();
    }

    private void onBtnClickInit() {
        if (isInitialized) {
            sendControlCommands();
        }
    }

    private void onBtnClickGo() {
        if (isInitialized) {
            if (dataClient != null)
                dataClient.disconnect();
            dataClient = new DataClient(this, "192.168.1.10", 5000);
            dataClient.connect();
        }
    }

    private void setOnCheckedChangeListener() {
        if (isInitialized) {
            int channelSel = getSelectedChannel();
            if (channelSel >= 0 && channelSel < 5)
                CHANNEL = channelSel;
        }
    }

    private void sendControlCommands() {
        controlClient = new ControlClient(this, "192.168.1.10", 5001);
        controlClient.connect();

        float freq_MHz;
        try {
            freq_MHz = Float.parseFloat(txtFreq.getText().toString().trim());
        } catch (NumberFormatException e) {
            Log.e("MainActivity", "Invalid frequency input: " + txtFreq.getText().toString());
            return;
        }
        controlClient.sendGain(new int[]{496, 496, 496, 496, 496});
        controlClient.sendFrequency(freq_MHz);
        controlClient.sendSquelchThreshold(0.5f);
        controlClient.sendInit();
        //controlClient.sendExit();
        controlClient.disconnect();
    }

    private void initialize() {
        chart = findViewById(R.id.chart);
        statusBar = findViewById(R.id.statusBar);
        txtFreq = findViewById(R.id.txtFreq);

        // Initialize the data structures for 5 channels
        FREQUENCY_MHz = new ArrayList<>();
        POWER_dBm = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            FREQUENCY_MHz.add(new ArrayList<>());
            POWER_dBm.add(new ArrayList<>());
        }

        MAX_POWER_dBm = new ArrayList<>(5);
        for (int i = 0; i < 5; i++) {
            MAX_POWER_dBm.add(0.0);
        }

        Button btnGo = findViewById(R.id.btnGo);
        btnGo.setOnClickListener(v -> onBtnClickGo());
        Button btnInit = findViewById(R.id.btnInit);
        btnInit.setOnClickListener(v -> onBtnClickInit());

        channelGroup = findViewById(R.id.channelGroup);
        channelGroup.setOnCheckedChangeListener((group, checkedId) -> setOnCheckedChangeListener());

        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main), (v, insets) -> {
            Insets systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars());
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom);
            return insets;
        });

        initializeChart();
        //iqSamples = generateIQSamples();  // generate random IQ Samples for initial FFT Plot
        iqSamples = loadIqDataFromFile();

        computeFFT();
        plotFFT();

        isInitialized = true;
    }

    private int getSelectedChannel() {
        int selectedId = channelGroup.getCheckedRadioButtonId();
        if (selectedId != -1) {
            RadioButton selectedRadioButton = findViewById(selectedId);
            String selectedChannel = selectedRadioButton.getText().toString();
            try {
                return Integer.parseInt(selectedChannel);
            } catch (NumberFormatException e) {
                Log.e(TAG, "Failed to parse selected channel: " + selectedChannel, e);
                return -1;  // Return an error code or handle appropriately
            }
        } else {
            return -1;
        }
    }

    private void computeMaxPower() {
        for (int j = 0; j < 5; j++) {
            List<Double> powerList = POWER_dBm.get(j); // Get the list of power values for the j-th channel
            Double maxPower = Double.NEGATIVE_INFINITY; // Start with the lowest possible value

            // Iterate through the power list to find the maximum value
            for (Double power : powerList) {
                if (power > maxPower) {
                    maxPower = power;
                }
            }

            // Store the maximum power value in MAX_POWER_dBm
            MAX_POWER_dBm.set(j, maxPower);
        }
    }

    private void updateMaxPowerStatus() {
        StringBuilder maxPowerString = new StringBuilder();

        // Iterate through the MAX_POWER_dBm list
        for (int i = 0; i < MAX_POWER_dBm.size(); i++) {
            double maxPower = MAX_POWER_dBm.get(i);

            // Append the max power value
            maxPowerString.append(String.format(Locale.US, "%.1f", maxPower));

            // Add a comma separator if it's not the last element
            if (i < MAX_POWER_dBm.size() - 1) {
                maxPowerString.append(", ");
            }
        }

        // Add " dBm" at the end
        maxPowerString.append(" dBm");

        statusBar.setText(maxPowerString.toString());
    }

    private void plotFFT() {
        if (FREQUENCY_MHz.size() != POWER_dBm.size()) {
            Log.e(TAG, "IllegalArgumentException: The sizes of FREQUENCY_MHz and POWER_dBm must be equal.");
            throw new IllegalArgumentException("The sizes of FREQUENCY_MHz and POWER_dBm must be equal.");
        }

        entries.clear();
        for (int i = 0; i < FREQUENCY_MHz.get(CHANNEL).size(); i++) {
            double fs_MHz = FREQUENCY_MHz.get(CHANNEL).get(i);
            double P_dBm = POWER_dBm.get(CHANNEL).get(i);
            entries.add(new Entry((float)fs_MHz, (float)P_dBm));
        }

        LineDataSet dataSet = new LineDataSet(entries, "FFT");
        dataSet.setColor(android.graphics.Color.WHITE); // Set line color to white
        dataSet.setValueTextColor(android.graphics.Color.WHITE); // Set value text color to white

        LineData lineData = new LineData(dataSet);
        chart.setData(lineData);

        chart.notifyDataSetChanged(); // Notify the chart that the data has changed
        chart.invalidate(); // Refresh the chart
    }

    private void initializeChart() {
        chart.clear();

        final Description desc = new Description();
        desc.setText("Power Received");
        desc.setTextSize(12f);
        chart.setDescription(desc);
        XAxis x = chart.getXAxis();
        x.setPosition(XAxis.XAxisPosition.BOTTOM);
        x.setDrawLabels(true);
        x.setTextColor(android.graphics.Color.WHITE);
        x.setLabelRotationAngle(45);
        x.setValueFormatter(new ValueFormatter() {
            @Override
            public String getFormattedValue(float value) {
                return value + " MHz";
            }
        });
        x.setDrawGridLines(true);
        x.setEnabled(true);
        x.setDrawLimitLinesBehindData(true);
        x.setAxisMaximum(1.3f);
        x.setAxisMinimum(-1.3f);
        x.setGranularity(0.4f);
        x.setGranularityEnabled(true);
        YAxis y = chart.getAxisLeft();
        y.setDrawLabels(true);
        y.setTextColor(android.graphics.Color.WHITE);
        y.setValueFormatter(new ValueFormatter() {
            @Override
            public String getFormattedValue(float value) {
                return value + " dBm";
            }
        });
        y.setDrawGridLines(true);
        y.setEnabled(true);
        y.setDrawLimitLinesBehindData(true);
        y.setGranularity(5f);
        y.setGranularityEnabled(true);
        y.setAxisMaximum(0f);
        y.setAxisMinimum(-60f);
    }

    @NonNull
    private double[][] generateIQSamples() {
        Random random = new Random();
        double[][] iqSamples = new double[5][SAMPLE_SIZE * 2]; // Interleaved IQ samples

        for (int j = 0; j < 5; j++) {
            for (int i = 0; i < SAMPLE_SIZE; i++) {
                double t = i / SAMPLE_BANDWIDTH_MHz;
                // Random noise
                double randomI = 0.01f*(random.nextFloat() - 0.5f);
                double randomQ = 0.01f*(random.nextFloat() - 0.5f);
                // Sinusoid
                double sinusoidI = Math.cos(2 * Math.PI * SINUSOID_FREQUENCY_MHz * t);
                double sinusoidQ = Math.sin(2 * Math.PI * SINUSOID_FREQUENCY_MHz * t);

                iqSamples[j][2 * i] = randomI + sinusoidI;
                iqSamples[j][2 * i + 1] = randomQ + sinusoidQ;
            }
        }

        return iqSamples;
    }

    @SuppressWarnings("unused")
    @NonNull
    private double[] applyHammingWindow(double[] iqSamples) {
        int N = iqSamples.length / 2; // Since iqSamples contains interleaved I/Q samples
        double[] windowedSamples = new double[iqSamples.length];

        for (int i = 0; i < N; i++) {
            double hammingValue = 0.54 - 0.46 * Math.cos(2 * Math.PI * i / (N - 1));
            windowedSamples[2 * i] = iqSamples[2 * i] * hammingValue;     // Real part
            windowedSamples[2 * i + 1] = iqSamples[2 * i + 1] * hammingValue; // Imaginary part
        }

        return windowedSamples;
    }

    @SuppressWarnings("unused")
    @NonNull
    private double[] applyBlackmanWindow(double[] iqSamples) {
        int N = iqSamples.length / 2;
        double[] windowedSamples = new double[iqSamples.length];

        for (int i = 0; i < N; i++) {
            double blackmanValue = 0.42 - 0.5 * Math.cos(2 * Math.PI * i / (N - 1)) + 0.08 * Math.cos(4 * Math.PI * i / (N - 1));
            windowedSamples[2 * i] = iqSamples[2 * i] * blackmanValue;
            windowedSamples[2 * i + 1] = iqSamples[2 * i + 1] * blackmanValue;
        }

        return windowedSamples;
    }

    private double[] applyWindow(double[] iqSamplesRI) {
        int n = iqSamplesRI.length / 2; // Number of complex samples
        for (int i = 0; i < n; i++) {
            double windowValue = 0.5 * (1 - Math.cos(2 * Math.PI * i / (n - 1))); // Example: Hamming window

            // Apply window to both real and imaginary parts
            iqSamplesRI[2 * i] *= windowValue;     // Real part
            iqSamplesRI[2 * i + 1] *= windowValue; // Imaginary part
        }
        return iqSamplesRI;
    }

    public static double[][] convertInterleavedToSeparate(double[] iqSamples) {
        int sampleSize = iqSamples.length / 2;
        double[][] dataRI = new double[2][sampleSize];

        for (int i = 0; i < sampleSize; i++) {
            dataRI[0][i] = iqSamples[2 * i];       // Real part
            dataRI[1][i] = iqSamples[2 * i + 1];   // Imaginary part
        }

        return dataRI;
    }

    @NonNull
    private double[][] computeFFT(@NonNull double[] iq_samples) {  // Verified Good
        double[][] dataRI = convertInterleavedToSeparate(iq_samples);

        FastFourierTransformer.transformInPlace(dataRI, DftNormalization.STANDARD, TransformType.FORWARD);
        return dataRI;
    }

    private void computeFFT() {
        double frequencyStep = SAMPLE_BANDWIDTH_MHz / SAMPLE_SIZE;
        for (int j=0; j<5; j++) {
            double[][] fftResult = computeFFT(iqSamples[j]);
            double[][] shiftedFftResult = fftShift(fftResult); // [real,imag][n_samples]

            FREQUENCY_MHz.get(j).clear();  // clear containers used for calculating max power in a later step
            POWER_dBm.get(j).clear();

            int num_samples = shiftedFftResult[0].length;
            for (int i = 0; i < num_samples; i++) {
                double fs_MHz = i * frequencyStep - SAMPLE_BANDWIDTH_MHz / 2;
                double V_mV_real = shiftedFftResult[0][i];
                double V_mV_imag = shiftedFftResult[1][i];
                double V_mV = (V_mV_real * V_mV_real + V_mV_imag * V_mV_imag)/num_samples; // magnitude
                double psd_uW = V_mV / 50;
                double P_dBm = (10*Math.log10(psd_uW)) - 30;
                FREQUENCY_MHz.get(j).add(fs_MHz);
                POWER_dBm.get(j).add(P_dBm);
            }
        }
    }

    public static double[][] fftShift(double[][] data) {
        int n = data.length;  // Number of rows (real/imag)
        int m = data[0].length;  // Number of columns (length of FFT)
        int halfSize = m / 2;  // Midpoint of the FFT data
        double[][] shiftedData = new double[n][m];  // Array to hold shifted data

        for (int i = 0; i < n; i++) {
            // Shift the second half to the first half
            System.arraycopy(data[i], halfSize, shiftedData[i], 0, m - halfSize);
            // Shift the first half to the second half
            System.arraycopy(data[i], 0, shiftedData[i], m - halfSize, halfSize);
        }
        return shiftedData;
    }


    public void notifyControlClient(String message) {
        runOnUiThread(() -> {
            Log.i(TAG, "Control message received: " + message);
            statusBar.setText(message);
        });
    }

    public void notifyDataClient(float[][] data, HeaderIQ header) {
        runOnUiThread(() -> processData(data, header));
    }

    private void processData(float[][] data, @NonNull HeaderIQ header) {
        iqSamples = convertFloatArrayToDouble(data);
        SAMPLE_SIZE = data[0].length/2;
        SAMPLE_BANDWIDTH_MHz = (float)header.getSamplingFreq()/1E6f;
        Log.i(TAG, "I/Q Data received: Size " + SAMPLE_SIZE);


        computeFFT();

        computeMaxPower();
        updateMaxPowerStatus();

        plotFFT();
        //saveIqDataToFile(iqSamples);
    }

    private void saveIqDataToFile(double[][] iqData) {
        File file = new File(getExternalFilesDir(null), "iq_data.bin");
        try (FileOutputStream fos = new FileOutputStream(file, false)) { // false for overwrite mode
            // Assuming each float corresponds to a real or imaginary part
            ByteBuffer byteBuffer = ByteBuffer.allocate(4 * iqData.length * iqData[0].length); // 4 bytes for a float
            byteBuffer.order(ByteOrder.LITTLE_ENDIAN); // Make sure the order is the same as in Python
            for (int j=0; j<iqData.length; j++) {
                for (int i=0; i<iqData[j].length; i++) {
                    byteBuffer.putFloat((float)iqData[j][i]);
                }
            }
            fos.write(byteBuffer.array());
            fos.close();  // Close the file stream
            Log.i(TAG, "I/Q data saved to file: " + file.getAbsolutePath());
        } catch (IOException e) {
            Log.e(TAG, "Error writing I/Q data to file", e);
        }
    }

    private double[][] loadIqDataFromFile() {
        File file = new File(getExternalFilesDir(null), "iq_data.bin");
        double[][] iqData = null;

        try (FileInputStream fis = new FileInputStream(file)) {
            long fileSize = file.length();
            int numChannels = 5; // Assuming 5 channels, adjust this if necessary
            int numSamples = (int)(fileSize / (4 * numChannels)); // Each sample is a float (4 bytes)

            iqData = new double[numChannels][numSamples];

            ByteBuffer byteBuffer = ByteBuffer.allocate((int)fileSize);
            byteBuffer.order(ByteOrder.LITTLE_ENDIAN);

            int totalBytesRead = 0;
            int bytesRead;
            while (totalBytesRead < fileSize && (bytesRead = fis.read(byteBuffer.array(), totalBytesRead, (int)fileSize - totalBytesRead)) != -1) {
                totalBytesRead += bytesRead;
            }

            byteBuffer.rewind(); // Rewind the buffer to start reading from the beginning

            for (int j = 0; j < numChannels; j++) {
                for (int i = 0; i < numSamples; i++) {
                    iqData[j][i] = byteBuffer.getFloat();
                }
            }

            Log.i(TAG, "I/Q data loaded from file: " + file.getAbsolutePath());
        } catch (IOException e) {
            Log.e(TAG, "Error reading I/Q data from file", e);
        }


        return iqData;
    }


    public double[][] convertFloatArrayToDouble(@NonNull float[][] floatArray) {
        int rows = floatArray.length;
        int cols = floatArray[0].length;
        double[][] doubleArray = new double[rows][cols];

        for (int i = 0; i < rows; i++) {
            for (int j = 0; j < cols; j++) {
                doubleArray[i][j] = floatArray[i][j];
            }
        }

        return doubleArray;
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (dataClient != null) {
            dataClient.connect();
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        if (dataClient != null) {
            dataClient.disconnect();
        }
        if (controlClient != null) {
            controlClient.disconnect();
        }
    }

    @Override
    protected void onStop() {
        super.onStop();
        if (dataClient != null) {
            dataClient.disconnect();
        }
        if (controlClient != null) {
            controlClient.disconnect();
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
    }
}