package com.example.battery;

import android.accessibilityservice.AccessibilityService;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.BatteryManager;
import android.os.Binder;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.Message;
import android.util.Log;
import android.view.accessibility.AccessibilityEvent;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.List;

public  class BatteryService extends AccessibilityService {

    private static final String TAG = "BatteryService";
    private static final String NOTIFICATION_CHANNEL_ID = "BatteryMonitorChannel";
    private static final String NOTIFICATION_CHANNEL_NAME = "Battery Monitor";
    private static final int NOTIFICATION_ID = 1;
    private static final String BATTERY_DATA_FILENAME = "battery_data.json";
    private static final int SAVE_INTERVAL = 12; // Save every 12 data points (approx. every minute)
    private static BatteryService mInstance;

    private long mScreenOnCount;

//    private final IBinder mBinder = new LocalBinder();
    private final List<BatteryData> mBatteryDataPoints = new ArrayList<>();
    private DataHandler mDataHandler;
    private boolean mIsScreenOn = true;
    long mStartTime;

    private final BroadcastReceiver mScreenStateReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (Intent.ACTION_SCREEN_ON.equals(intent.getAction())) {
                mIsScreenOn = true;
            } else if (Intent.ACTION_SCREEN_OFF.equals(intent.getAction())) {
                mIsScreenOn = false;
            }
        }
    };

//    public class LocalBinder extends Binder {
//        BatteryService getService() {
//            return BatteryService.this;
//        }
//    }

    private static class DataHandler extends Handler {
        private final WeakReference<BatteryService> mServiceRef;
        private static final int MSG_COLLECT_DATA = 1;

        DataHandler(BatteryService service, Looper looper) {
            super(looper);
            mServiceRef = new WeakReference<>(service);
        }

        @Override
        public void handleMessage(Message msg) {
            BatteryService service = mServiceRef.get();
            if (service == null) {
                return;
            }
            if (msg.what == MSG_COLLECT_DATA) {
                service.collectBatteryData();
                sendEmptyMessageDelayed(MSG_COLLECT_DATA, Constant.REFRESH_RATE_MS);
            }
        }
    }

    public static BatteryService getInstance() {
        return mInstance;
    }


    @Override
    protected void onServiceConnected() {
        super.onServiceConnected();
        mInstance = this;
    }

    @Override
    public boolean onUnbind(Intent intent) {
        mInstance = null;
        return super.onUnbind(intent);
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {

    }


    @Override
    public void onInterrupt() {

    }

    @Override
    public void onCreate() {
        super.onCreate();
        Log.d(TAG, "Service created");
        loadDataFromFile();
        if (!mBatteryDataPoints.isEmpty()) {
            mStartTime = System.currentTimeMillis() - mBatteryDataPoints.get(mBatteryDataPoints.size() -1).mPastTime;
            mScreenOnCount = mBatteryDataPoints.get(mBatteryDataPoints.size() -1).mScreenOnCount;
        } else {
            mStartTime = System.currentTimeMillis();
        }
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        Log.d(TAG, "Service start");

        NotificationChannel channel = new NotificationChannel(NOTIFICATION_CHANNEL_ID, NOTIFICATION_CHANNEL_NAME, NotificationManager.IMPORTANCE_LOW);
        NotificationManager manager = getSystemService(NotificationManager.class);
        manager.createNotificationChannel(channel);

        Notification notification = new Notification.Builder(this, NOTIFICATION_CHANNEL_ID)
                .setContentTitle("Battery Monitor")
                .setContentText("Monitoring battery status.")
                .setSmallIcon(R.mipmap.ic_launcher)
                .build();

//        startForeground(NOTIFICATION_ID, notification);

        mDataHandler = new DataHandler(this, Looper.getMainLooper());
        startDataCollection();
        registerScreenStateReceiver();
        return super.onStartCommand(intent, flags, startId);
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        stopDataCollection();
        unregisterReceiver(mScreenStateReceiver);
        saveDataToFile();
        stopForeground(true);
    }

    private void startDataCollection() {
        mDataHandler.sendEmptyMessage(DataHandler.MSG_COLLECT_DATA);
    }

    private void stopDataCollection() {
        mDataHandler.removeMessages(DataHandler.MSG_COLLECT_DATA);
    }

    private void collectBatteryData() {
        Log.d(TAG, "Collecting battery data");
        IntentFilter filter = new IntentFilter(Intent.ACTION_BATTERY_CHANGED);
        Intent batteryStatus = registerReceiver(null, filter);
        BatteryManager batteryManager = (BatteryManager) getSystemService(Context.BATTERY_SERVICE);


        if (batteryStatus != null) {
            int level = batteryStatus.getIntExtra(BatteryManager.EXTRA_LEVEL, 0);
            int scale = batteryStatus.getIntExtra(BatteryManager.EXTRA_SCALE, 100);
            int temperature = batteryStatus.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, 0);
            int voltage = batteryStatus.getIntExtra(BatteryManager.EXTRA_VOLTAGE, 0);
            int status = batteryStatus.getIntExtra(BatteryManager.EXTRA_STATUS, -1);
            int current = batteryManager.getIntProperty(BatteryManager.BATTERY_PROPERTY_CURRENT_AVERAGE) / 1000;

            synchronized (mBatteryDataPoints) {
                if (mIsScreenOn) {
                    mScreenOnCount++;
                }
                Log.d(TAG, "screenOnCount: " + mScreenOnCount);
                mBatteryDataPoints.add(new BatteryData(System.currentTimeMillis() - mStartTime
                        , level, scale, temperature, voltage, status, mIsScreenOn, current, mScreenOnCount));

                if (mBatteryDataPoints.size() % SAVE_INTERVAL == 0) {
                    saveDataToFile();
                }
            }
        }
    }

    private void saveDataToFile() {
        synchronized (mBatteryDataPoints) {
            JSONArray jsonArray = new JSONArray();
            for (BatteryData dataPoint : mBatteryDataPoints) {
                try {
                    jsonArray.put(dataPoint.toJson());
                } catch (JSONException e) {
                    Log.e(TAG, "Error converting BatteryData to JSON", e);
                }
            }

            try (FileOutputStream fos = openFileOutput(BATTERY_DATA_FILENAME, Context.MODE_PRIVATE)) {
                fos.write(jsonArray.toString().getBytes());
                Log.d(TAG, "Successfully saved " + mBatteryDataPoints.size() + " data points.");
            } catch (IOException e) {
                Log.e(TAG, "Error saving data to file", e);
            }
        }
    }

    private void loadDataFromFile() {
        File file = new File(getFilesDir(), BATTERY_DATA_FILENAME);
        if (!file.exists()) {
            Log.d(TAG, "Data file does not exist. Starting fresh.");
            return;
        }

        try (FileInputStream fis = openFileInput(BATTERY_DATA_FILENAME);
             InputStreamReader inputStreamReader = new InputStreamReader(fis);
             BufferedReader bufferedReader = new BufferedReader(inputStreamReader)) {

            StringBuilder stringBuilder = new StringBuilder();
            String line;
            while ((line = bufferedReader.readLine()) != null) {
                stringBuilder.append(line);
            }

            JSONArray jsonArray = new JSONArray(stringBuilder.toString());
            synchronized (mBatteryDataPoints) {
                mBatteryDataPoints.clear();
                for (int i = 0; i < jsonArray.length(); i++) {
                    JSONObject jsonObject = jsonArray.getJSONObject(i);
                    mBatteryDataPoints.add(new BatteryData(jsonObject));
                }
                Log.d(TAG, "Successfully loaded " + mBatteryDataPoints.size() + " data points.");
            }

        } catch (IOException | JSONException e) {
            Log.e(TAG, "Error loading data from file", e);
        }
    }


    public List<BatteryData> getBatteryData() {
        synchronized (mBatteryDataPoints) {
            return new ArrayList<>(mBatteryDataPoints);
        }
    }

    public long getScreenOnCount() {
        return mScreenOnCount;
    }

    private void registerScreenStateReceiver() {
        IntentFilter screenStateFilter = new IntentFilter();
        screenStateFilter.addAction(Intent.ACTION_SCREEN_ON);
        screenStateFilter.addAction(Intent.ACTION_SCREEN_OFF);
        registerReceiver(mScreenStateReceiver, screenStateFilter);
    }

    public void clearData() {
        Log.d(TAG, "clearData: ");
        synchronized (mBatteryDataPoints) {
            mStartTime = System.currentTimeMillis();
            mScreenOnCount = 0;
            mBatteryDataPoints.clear();
            saveDataToFile();
        }
    }

    public static class BatteryData {
        public final long mPastTime;
        public final int mLevel;
        public final int mScale;
        public final int mTemperature;
        public final int mVoltage;
        public final int mStatus;
        public final boolean mIsScreenOn;
        public final int mCurrent;
        public final long mScreenOnCount;

        public BatteryData(long timestamp, int level, int scale, int temperature, int voltage, int status, boolean isScreenOn, int current, long screenOnCount) {
            this.mPastTime = timestamp;
            this.mLevel = level;
            this.mScale = scale;
            this.mTemperature = temperature;
            this.mVoltage = voltage;
            this.mStatus = status;
            this.mIsScreenOn = isScreenOn;
            this.mCurrent = current;
            this.mScreenOnCount = screenOnCount;
        }

        // Constructor to create from JSONObject
        public BatteryData(JSONObject jsonObject) throws JSONException {
            this.mPastTime = jsonObject.getLong("timestamp");
            this.mLevel = jsonObject.getInt("level");
            this.mScale = jsonObject.getInt("scale");
            this.mTemperature = jsonObject.getInt("temperature");
            this.mVoltage = jsonObject.getInt("voltage");
            this.mStatus = jsonObject.getInt("status");
            this.mIsScreenOn = jsonObject.getBoolean("isScreenOn");
            this.mCurrent = jsonObject.getInt("current");
            this.mScreenOnCount = jsonObject.getLong("screenOnCount");
        }

        // Method to convert to JSONObject
        public JSONObject toJson() throws JSONException {
            JSONObject jsonObject = new JSONObject();
            jsonObject.put("timestamp", mPastTime);
            jsonObject.put("level", mLevel);
            jsonObject.put("scale", mScale);
            jsonObject.put("temperature", mTemperature);
            jsonObject.put("voltage", mVoltage);
            jsonObject.put("status", mStatus);
            jsonObject.put("isScreenOn", mIsScreenOn);
            jsonObject.put("current", mCurrent);
            jsonObject.put("screenOnCount", mScreenOnCount);
            return jsonObject;
        }
    }

}
