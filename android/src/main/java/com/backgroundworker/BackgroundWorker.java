package com.backgroundworker;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Bundle;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.work.Data;
import androidx.work.RxWorker;
import androidx.work.WorkerParameters;

import com.facebook.react.bridge.Arguments;
import com.facebook.react.modules.core.DeviceEventManagerModule;

import java.util.Map;

import io.reactivex.Single;

public class BackgroundWorker extends RxWorker {
    private static final String TAG = "BackgroundWorker";
    private final Map<String, Object> worker;
    private final String id;
    private BroadcastReceiver receiver;

    public BackgroundWorker(@NonNull Context appContext, @NonNull WorkerParameters workerParams) {
        super(appContext, workerParams);
        worker = workerParams.getInputData().getKeyValueMap();
        id = workerParams.getId().toString();
    }

    @NonNull
    @Override
    public Single<Result> createWork() {
        if (BackgroundWorkerModule.context == null) {
            Log.w(TAG, "React context is null, retrying work");
            return Single.just(Result.retry());
        }

        String name = (String) worker.get("name");
        String payload = (String) worker.get("payload");

        if (name == null) {
            Log.e(TAG, "Worker name is null");
            return Single.just(Result.failure());
        }

        Bundle extras = new Bundle();
        extras.putString("id", id);
        if (payload != null) extras.putString("payload", payload);

        return Single.create(emitter -> {
            try {
                receiver = new BroadcastReceiver() {
                    @Override
                    public void onReceive(Context context, Intent intent) {
                        try {
                            String value = intent.getStringExtra("value");
                            String result = intent.getStringExtra("result");
                            
                            Data outputData = new Data.Builder()
                                    .putString("value", value)
                                    .build();

                            if (result == null) {
                                Log.e(TAG, "Received null result");
                                emitter.onSuccess(Result.failure(outputData));
                                return;
                            }

                            switch (result) {
                                case "success":
                                    emitter.onSuccess(Result.success(outputData));
                                    break;
                                case "retry":
                                    emitter.onSuccess(Result.retry());
                                    break;
                                default:
                                    Log.w(TAG, "Unknown result type: " + result);
                                    emitter.onSuccess(Result.failure(outputData));
                            }
                        } catch (Exception e) {
                            Log.e(TAG, "Error processing broadcast result", e);
                            emitter.onSuccess(Result.failure());
                        } finally {
                            unregisterReceiver();
                        }
                    }
                };

                BackgroundWorkerModule.context.registerReceiver(
                    receiver, 
                    new IntentFilter(id + "result")
                );

                // Emit the event to JS
                BackgroundWorkerModule.context.getJSModule(DeviceEventManagerModule.RCTDeviceEventEmitter.class)
                        .emit(name, Arguments.fromBundle(extras));

            } catch (Exception e) {
                Log.e(TAG, "Error in work creation", e);
                unregisterReceiver();
                emitter.onSuccess(Result.failure());
            }
        });
    }

    private void unregisterReceiver() {
        if (receiver != null && BackgroundWorkerModule.context != null) {
            try {
                BackgroundWorkerModule.context.unregisterReceiver(receiver);
            } catch (Exception e) {
                Log.w(TAG, "Error unregistering receiver", e);
            }
            receiver = null;
        }
    }

    @Override
    public void onStopped() {
        unregisterReceiver();
        super.onStopped();
    }
}
