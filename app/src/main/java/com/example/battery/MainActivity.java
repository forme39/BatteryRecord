package com.example.battery;

import android.accessibilityservice.AccessibilityService;
import android.app.Dialog;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.graphics.Color;
import android.os.BatteryManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.Message;
import android.provider.Settings;
import android.text.TextUtils;
import android.util.Log;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;

import com.github.mikephil.charting.charts.LineChart;
import com.github.mikephil.charting.components.AxisBase;
import com.github.mikephil.charting.components.Legend;
import com.github.mikephil.charting.components.XAxis;
import com.github.mikephil.charting.components.YAxis;
import com.github.mikephil.charting.data.Entry;
import com.github.mikephil.charting.data.LineData;
import com.github.mikephil.charting.data.LineDataSet;
import com.github.mikephil.charting.formatter.ValueFormatter;
import com.github.mikephil.charting.interfaces.datasets.ILineDataSet;

import java.util.List;
import java.util.concurrent.TimeUnit;

public class MainActivity extends AppCompatActivity {

    private static final String TAG = "MainActivity";
    private LineChart mLineChart;
    private TextView mBatteryTextView;
    private TextView mTempTextView;
    private TextView mVoltageTextView;
    private TextView mChargingStateTextView;
    private TextView mAvgPowerConsumptionTextView;
    private TextView mUsageTimeTextView;
    private TextView mPredictedRemainingTimeTextView;
    private TextView mAvgPowerHintTextView;

    private BatteryService mBatteryService;
    private float mWh = 0;
    private boolean mIsBound = false;

    private Handler mHandler = new Handler(Looper.getMainLooper()){
        @Override
        public void handleMessage(@NonNull Message msg) {
            super.handleMessage(msg);
            if (mIsBound) {
                updateUiFromService();
            }
            sendEmptyMessageDelayed(0, 5000);
        }
    };

    private final ServiceConnection mConnection = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName className, IBinder service) {
            Log.d(TAG, "Service connected");
            mBatteryService = BatteryService.getInstance();
            mIsBound = true;
            mHandler.sendEmptyMessage(0);
        }

        @Override
        public void onServiceDisconnected(ComponentName arg0) {
            mBatteryService = null;
            mIsBound = false;
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        bindView();
        initChart();
        Intent serviceIntent = new Intent(this, BatteryService.class);
        startService(serviceIntent);
    }

    private void bindView() {
        mLineChart = findViewById(R.id.lineChart);
        mBatteryTextView = findViewById(R.id.tv_battery_capacity);
        mTempTextView = findViewById(R.id.tv_temp);
        mVoltageTextView = findViewById(R.id.tv_voltage);
        mChargingStateTextView = findViewById(R.id.tv_charge_status);
        mAvgPowerConsumptionTextView = findViewById(R.id.tv_avg_power);
        mUsageTimeTextView = findViewById(R.id.tv_used_time);
        mPredictedRemainingTimeTextView = findViewById(R.id.tv_remain_time);
        mAvgPowerHintTextView = findViewById(R.id.tv_avg_power_hint);
        findViewById(R.id.btn_clear).setOnClickListener(v -> {
            Dialog dialog = new AlertDialog.Builder(this)
                    .setTitle("清除数据")
                    .setMessage("确定要清除数据吗？")
                    .setPositiveButton("是", (dialog1, which) -> {
                        mBatteryService.clearData();
                        updateUiFromService();
                        Toast.makeText(this, "数据已清除", Toast.LENGTH_SHORT).show();
                    })
                    .setNegativeButton("取消", null)
                    .create();
            dialog.show();
        });

        float mah = getBatteryCapacity(this);
        mBatteryTextView.setText(String.format("%.0f", mah));
        mWh =  mah * 3.7f / 1000;
    }

    private void initChart() {

        // Configure Y-Axis
        YAxis leftAxis = mLineChart.getAxisLeft();
        leftAxis.setTextColor(getColor(R.color.black));
        leftAxis.setAxisMinimum(0f);
        leftAxis.setAxisMaximum(100f);
        leftAxis.setLabelCount(6, true);
        leftAxis.setValueFormatter(new ValueFormatter() {
            @Override
            public String getAxisLabel(float value, AxisBase axis) {
                return String.format("%.0f%%", value);
            }
        });

        mLineChart.getAxisRight().setEnabled(false);

        // Configure X-Axis
        XAxis xAxis = mLineChart.getXAxis();
        xAxis.setPosition(XAxis.XAxisPosition.BOTTOM);
        xAxis.setTextColor(getColor(R.color.black));
//        xAxis.setLabelCount(6, false); // Set a preferred label count, but not forced

        // Configure Legend
        Legend legend = mLineChart.getLegend();
        legend.setEnabled(false);

        mLineChart.getDescription().setEnabled(false);


        mLineChart.setData(new LineData());
    }

    @Override
    protected void onResume() {
        super.onResume();
        boolean serviceON = isAccessibilityServiceEnabled(this, BatteryService.class);
        Log.d(TAG, "onResume isServiceOn: " + serviceON);
        if (serviceON) {
            Intent intent = new Intent(this, BatteryService.class);
            bindService(intent, mConnection, Context.BIND_AUTO_CREATE);
        } else {
            Toast.makeText(this, "请先开启无障碍服务", Toast.LENGTH_SHORT).show();
            Intent intent = new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS);
            intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(intent);
        }
    }

    public static boolean isAccessibilityServiceEnabled(Context context, Class<? extends AccessibilityService> service) {
        String enabledServices = Settings.Secure.getString(
                context.getContentResolver(),
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        );
        if (TextUtils.isEmpty(enabledServices)) {
            return false;
        }
        return enabledServices.contains(service.getName());
    }

    @Override
    protected void onPause() {
        super.onPause();
        if (mIsBound) {
            unbindService(mConnection);
            mIsBound = false;
        }
    }

    private void updateUiFromService() {
        if (!mIsBound) return;

        List<BatteryService.BatteryData> dataPoints = mBatteryService.getBatteryData();
        if (dataPoints.isEmpty()) {
            mLineChart.getData().clearValues();
            mLineChart.notifyDataSetChanged();
            mLineChart.invalidate();
            mAvgPowerConsumptionTextView.setText("N/A");
            mUsageTimeTextView.setText("N/A");
            mPredictedRemainingTimeTextView.setText("N/A");
            return;
        }

        // Update Chart
        LineData lineData = mLineChart.getData();
        lineData.clearValues();

        LineDataSet currentSet = createDataSet(dataPoints.get(0).mIsScreenOn);
        lineData.addDataSet(currentSet);

        for (int i = 0; i < dataPoints.size(); i++) {
            BatteryService.BatteryData dataPoint = dataPoints.get(i);
            float batteryPct = (dataPoint.mLevel / (float) dataPoint.mScale) * 100;
            Entry newEntry = new Entry((float) dataPoint.mPastTime / 1000, batteryPct);

            if (i > 0 && dataPoint.mIsScreenOn != dataPoints.get(i - 1).mIsScreenOn) {
                currentSet = createDataSet(dataPoint.mIsScreenOn);
                lineData.addDataSet(currentSet);
                // Connect to the previous segment
                ILineDataSet previousSet = lineData.getDataSetByIndex(lineData.getDataSetCount() - 2);
                if (previousSet.getEntryCount() > 0) {
                    Entry lastEntry = previousSet.getEntryForIndex(previousSet.getEntryCount() - 1);
                    currentSet.addEntry(lastEntry);
                }
            }
            currentSet.addEntry(newEntry);
        }

        XAxis xAxis = mLineChart.getXAxis();
        float maxSeconds = (float) dataPoints.get(dataPoints.size() - 1).mPastTime / 1000;

        if (maxSeconds > 3600) { // More than 1 hour
            int count = (int) ((maxSeconds / 3600) + 1);
            long maxX = count * 3600L;
            xAxis.setAxisMaximum(maxX);
            xAxis.setLabelCount(count + 1, true);
            xAxis.setValueFormatter(new ValueFormatter() {
                @Override
                public String getAxisLabel(float value, AxisBase axis) {
                    float hours = value / 3600f;
                    return String.format("%.1fh", hours);
                }
            });
        } else { // Less than or equal to 1 hour
            if (maxSeconds > 1800) {
                xAxis.setAxisMaximum(3600);
            } else {
                xAxis.setAxisMaximum(1800);
            }
            xAxis.setLabelCount(7, true);
            xAxis.setValueFormatter(new ValueFormatter() {
                @Override
                public String getAxisLabel(float value, AxisBase axis) {
                    long minutes = TimeUnit.SECONDS.toMinutes((long) value);
                    return String.format("%dm", minutes);
                }
            });
        }

        lineData.notifyDataChanged();
        mLineChart.notifyDataSetChanged();
        mLineChart.invalidate();

        // Update stats and current values
        BatteryService.BatteryData lastDataPoint = dataPoints.get(dataPoints.size() - 1);
        float temp = lastDataPoint.mTemperature / 10.0f;
        mTempTextView.setText(String.format("%.1f", temp));
        mVoltageTextView.setText(String.format("%.2f", lastDataPoint.mVoltage / 1000.0f));

        String chargingState;
        if (lastDataPoint.mStatus == BatteryManager.BATTERY_STATUS_CHARGING) {
            chargingState = "充电中";
        } else if (lastDataPoint.mStatus == BatteryManager.BATTERY_STATUS_DISCHARGING) {
            chargingState = "未充电";
        } else if (lastDataPoint.mStatus == BatteryManager.BATTERY_STATUS_FULL) {
            chargingState = "已充满";
        } else {
            chargingState = "未知";
        }
        mChargingStateTextView.setText(chargingState);

        updateStatistics(dataPoints);
    }

    private LineDataSet createDataSet(boolean isScreenOn) {
        String label = isScreenOn ? "亮屏" : "灭屏";
        LineDataSet set = new LineDataSet(null, label);
        set.setDrawValues(false);
        set.setDrawCircles(false);
        set.setColor(getColor(R.color.blue));
        set.setLineWidth(3f);

        if (!isScreenOn) {
            set.enableDashedLine(10f, 10f, 0f);
            set.setColor(getColor(R.color.gray));
        }
        return set;
    }

    private void updateStatistics(List<BatteryService.BatteryData> dataPoints) {
        if (dataPoints.size() < 2) return;

        BatteryService.BatteryData firstData = dataPoints.get(0);
        BatteryService.BatteryData lastData = dataPoints.get(dataPoints.size() - 1);

        long elapsedTime = lastData.mPastTime - firstData.mPastTime;

        long screenOnTimeMs = mBatteryService.getScreenOnCount() * Constant.REFRESH_RATE_MS;
        long seconds = screenOnTimeMs / 1000;
        long minutes = seconds / 60;
        long hours = minutes / 60;
        minutes %= 60;

        mUsageTimeTextView.setText(String.format("%dh %dm", hours, minutes));

        float firstLevel = (firstData.mLevel / (float) firstData.mScale) * 100;
        float lastLevel = (lastData.mLevel / (float) lastData.mScale) * 100;
        float levelDrop = firstLevel - lastLevel;

        float current = lastData.mCurrent / 1000.0f; //mA
        float voltage = lastData.mVoltage / 1000.0f; //V
//        Log.d("MainActivity", "current: " + current + ", voltage: " + voltage);
        float power = current * voltage; // W
        mAvgPowerConsumptionTextView.setText(String.format("%.2fW", power));

        if (levelDrop > 0) {
            float screenOnHours = screenOnTimeMs / 3600_000f;
            float avgPower = mWh * levelDrop / 100 / screenOnHours;
            mAvgPowerHintTextView.setText("平均功耗");
            mAvgPowerConsumptionTextView.setText(String.format("%.2fW", avgPower));
            double hoursPerPercent = screenOnHours / levelDrop;
            double remainingHours = lastLevel * hoursPerPercent;
            mPredictedRemainingTimeTextView.setText(String.format("%.1fh", remainingHours));
        } else {
            mPredictedRemainingTimeTextView.setText("N/A");
        }
    }

    public static float getBatteryCapacity(Context context) {
        Object powerProfile;
        float batteryCapacity = 0;
        final String POWER_PROFILE_CLASS = "com.android.internal.os.PowerProfile";

        try {
            powerProfile = Class.forName(POWER_PROFILE_CLASS)
                    .getConstructor(Context.class)
                    .newInstance(context);

            String str = String.valueOf(Class.forName(POWER_PROFILE_CLASS)
                    .getMethod("getBatteryCapacity")
                    .invoke(powerProfile));
            Log.d("Mainactivity", "mah = " + str);
            batteryCapacity = Float.parseFloat(str);
            Log.d("Mainactivity", "parse mah = " + batteryCapacity);
        } catch (Exception e) {
            e.printStackTrace();
        }

        return batteryCapacity;
    }
}
