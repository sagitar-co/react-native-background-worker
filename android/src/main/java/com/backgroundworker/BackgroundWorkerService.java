package com.backgroundworker;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.util.Log;

import com.facebook.react.HeadlessJsTaskService;
import com.facebook.react.bridge.Arguments;
import com.facebook.react.jstasks.HeadlessJsTaskConfig;

import java.util.concurrent.TimeUnit;

import javax.annotation.Nullable;

public class BackgroundWorkerService extends HeadlessJsTaskService {
    private static final String TAG = "BackgroundWorkerService";
    private static final int DEFAULT_TIMEOUT_MINUTES = 10;
    private static final int DEFAULT_NOTIFICATION_ID = 123456789;

    @Nullable
    @Override
    protected HeadlessJsTaskConfig getTaskConfig(Intent intent) {
        if (intent == null) {
            Log.e(TAG, "Received null intent");
            return null;
        }

        Bundle extras = intent.getExtras();
        if (extras == null) {
            Log.e(TAG, "No extras found in intent");
            return null;
        }

        try {
            String name = extras.getString("name");
            String title = extras.getString("title");
            String text = extras.getString("text");
            String id = extras.getString("id");

            if (name == null || title == null || text == null) {
                Log.e(TAG, "Missing required parameters: name, title, or text");
                return null;
            }

            int timeout = (int) extras.getDouble("timeout", TimeUnit.MINUTES.toMillis(DEFAULT_TIMEOUT_MINUTES));

            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                createNotificationAndStartForeground(name, title, text, id);
            }

            return new HeadlessJsTaskConfig(
                name, 
                Arguments.fromBundle(extras), 
                timeout,
                true
            );

        } catch (Exception e) {
            Log.e(TAG, "Error creating HeadlessJsTaskConfig", e);
            return null;
        }
    }

    private void createNotificationAndStartForeground(String name, String title, String text, String id) {
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            try {
                NotificationManager notificationManager = 
                    (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);

                NotificationChannel channel = new NotificationChannel(
                    name,
                    name,
                    NotificationManager.IMPORTANCE_MIN
                );
                
                notificationManager.createNotificationChannel(channel);

                int iconResource = getNotificationIcon(name);

                Notification notification = new Notification.Builder(this, name)
                        .setWhen(System.currentTimeMillis())
                        .setContentText(text)
                        .setContentTitle(title)
                        .setSmallIcon(iconResource)
                        .build();

                int notificationId = id != null ? id.hashCode() : DEFAULT_NOTIFICATION_ID;
                startForeground(notificationId, notification);

            } catch (Exception e) {
                Log.e(TAG, "Error creating notification", e);
            }
        }
    }

    private int getNotificationIcon(String name) {
        int customIcon = getResources().getIdentifier(
            name, 
            "drawable", 
            getApplicationContext().getPackageName()
        );
        
        return customIcon != 0 ? customIcon : android.R.drawable.ic_menu_info_details;
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            stopForeground(true);
        }
    }
}
