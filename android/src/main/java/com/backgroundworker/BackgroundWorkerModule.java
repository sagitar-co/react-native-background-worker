
package com.backgroundworker;

import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;

import androidx.lifecycle.LiveData;
import androidx.lifecycle.Observer;
import androidx.work.Constraints;
import androidx.work.Data;
import androidx.work.ExistingPeriodicWorkPolicy;
import androidx.work.OneTimeWorkRequest;
import androidx.work.Operation;
import androidx.work.PeriodicWorkRequest;
import androidx.work.WorkInfo;
import androidx.work.WorkManager;
import androidx.work.WorkRequest;

import com.facebook.react.bridge.Arguments;
import com.facebook.react.bridge.Promise;
import com.facebook.react.bridge.ReactApplicationContext;
import com.facebook.react.bridge.ReactContextBaseJavaModule;
import com.facebook.react.bridge.ReactMethod;
import com.facebook.react.bridge.ReadableMap;
import com.facebook.react.modules.core.DeviceEventManagerModule;
import com.google.common.util.concurrent.ListenableFuture;

import java.util.HashMap;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import javax.annotation.Nonnull;

import static androidx.work.Operation.State;

public class BackgroundWorkerModule extends ReactContextBaseJavaModule {

    static ReactApplicationContext context;
    private final Handler handler = new Handler(Looper.getMainLooper());

    private HashMap<String, ReadableMap> queuedWorkers = new HashMap<>();
    private HashMap<String, Constraints> queuedConstraints = new HashMap<>();
    private HashMap<String, Observer<WorkInfo>> listeners = new HashMap<>();

    BackgroundWorkerModule(ReactApplicationContext reactContext) {
        super(reactContext);
        context = reactContext;
    }

    @Nonnull
    @Override
    public String getName() {
        return "BackgroundWorker";
    }

    @Override
    public void onCatalystInstanceDestroy() {
        for (String id : listeners.keySet()) removeListener(id);
        super.onCatalystInstanceDestroy();
    }

    /**
     * If the worker is queued, stores the worker information to be registered when enqueued
     * if the worker is periodic, registers it and send back it's id
     * @param worker the worker information to be registered
     * @param constraints the worker constraints
     * @param p the promise to send back results to JS
     */
    @ReactMethod
    public void registerWorker(ReadableMap worker, ReadableMap constraints, Promise p) {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            handler.post(() -> registerWorker(worker, constraints, p));
            return;
        }

        try {
            String type = worker.getString("type");
            String name = worker.getString("name");

            if(name == null || type == null) {
                p.reject("ERROR", "missing worker info");
                return;
            }

            if(type.equals("queue")) {
                Constraints _constraints = Parser.getConstraints(constraints);
                if(_constraints!=null) queuedConstraints.put(name, _constraints);
                queuedWorkers.put(name, worker);
                p.resolve(null);
                return;
            }

            if(type.equals("periodic")) {
                int repeatInterval = worker.getInt("repeatInterval");
                Constraints _constraints = Parser.getConstraints(constraints);

                PeriodicWorkRequest.Builder builder = new PeriodicWorkRequest.Builder(BackgroundWorker.class, Math.max(15, repeatInterval), TimeUnit.MINUTES);
                if(_constraints!=null) builder.setConstraints(_constraints);

                Data inputData = new Data.Builder()
                        .putAll(worker.toHashMap())
                        .build();

                builder.setInputData(inputData);
                PeriodicWorkRequest request = builder.build();

                Operation operation = WorkManager.getInstance(context)
                    .enqueueUniquePeriodicWork(name, ExistingPeriodicWorkPolicy.REPLACE, request);
                
                operation.getResult().addListener(() -> {
                    try {
                        operation.getResult().get();
                        handler.post(() -> p.resolve(request.getId().toString()));
                    } catch (Exception e) {
                        handler.post(() -> p.reject("REGISTER_ERROR", "Failed to register periodic work: " + e.getMessage()));
                    }
                }, Executors.newSingleThreadExecutor());
                return;
            }
            p.reject("ERROR", "incompatible worker type: " + type);
        } catch (Exception e) {
            p.reject("ERROR", "Failed to register worker: " + e.getMessage());
        }
    }

    /**
     * Enqueues payloads to a queued worker and returns the work's id
     * @param worker name of the worker that will process the payload
     * @param payload payload to be enqueued
     * @param p the promise to send back the work's id to JS
     */
    @ReactMethod
    public void enqueue(String worker, String payload, Promise p) {
        // Ensure we're running on the right thread for React Native bridge operations
        if (Looper.myLooper() != Looper.getMainLooper()) {
            handler.post(() -> enqueue(worker, payload, p));
            return;
        }

        ReadableMap _worker = queuedWorkers.get(worker);
        Constraints _constraints = queuedConstraints.get(worker);

        if(_worker==null) {
            p.reject("ERROR", "worker not registered");
            return;
        }

        try {
            Data inputData = new Data.Builder()
                    .putAll(_worker.toHashMap())
                    .putString("payload", payload)
                    .build();

            OneTimeWorkRequest.Builder builder = new OneTimeWorkRequest.Builder(BackgroundWorker.class)
                    .setInputData(inputData);

            if(_constraints!=null) builder.setConstraints(_constraints);

            WorkRequest request = builder.build();

            // Enqueue the work and handle the operation result
            Operation operation = WorkManager.getInstance(context).enqueue(request);
            operation.getResult().addListener(() -> {
                try {
                    operation.getResult().get();
                    // Ensure promise resolution happens on the main thread
                    handler.post(() -> p.resolve(request.getId().toString()));
                } catch (Exception e) {
                    handler.post(() -> p.reject("ENQUEUE_ERROR", "Failed to enqueue work: " + e.getMessage()));
                }
            }, Executors.newSingleThreadExecutor());
        } catch (Exception e) {
            p.reject("ENQUEUE_ERROR", "Failed to create work request: " + e.getMessage());
        }
    }

    /**
     * Method to delegate the "start headless task" decision to JS so we can easily verify if the app is in foreground
     * @param workConfiguration The entire info to run the work
     */
    @ReactMethod
    public void startHeadlessTask(ReadableMap workConfiguration) {
        Intent headlessIntent = new Intent(context, BackgroundWorkerService.class);
        Bundle extras = Arguments.toBundle(workConfiguration);
        if(extras!=null) headlessIntent.putExtras(extras);
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O)
            BackgroundWorkerModule.this.getReactApplicationContext().startForegroundService(headlessIntent);
        else BackgroundWorkerModule.this.getReactApplicationContext().startService(headlessIntent);
    }

    /**
     * Called when the JS task is finished to inform the native side so the worker can wrap up and store the information
     * @param id the work's id for this task
     * @param value the value returned by the task
     * @param result task's resolution, could be success, failure or retry
     */
    @ReactMethod
    public void result(String id, String value, String result) {
        Intent intent = new Intent(id + "result");
        intent.putExtra("result", result);
        intent.putExtra("value", value);
        context.sendBroadcast(intent);
    }

    /**
     * Called from JS to cancel a work
     * @param id the work's id to be canceled
     * @param p the promise to inform the JS side if the work was really canceled
     */
    @ReactMethod
    public void cancel(String id, final Promise p) {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            handler.post(() -> cancel(id, p));
            return;
        }

        try {
            final Operation operation = WorkManager.getInstance(context)
                .cancelWorkById(UUID.fromString(id));
            
            operation.getResult().addListener(() -> {
                try {
                    State.SUCCESS success = operation.getResult().get();
                    handler.post(() -> p.resolve(success != null));
                } catch (Exception e) {
                    handler.post(() -> p.reject("CANCEL_ERROR", "Failed to cancel work: " + e.getMessage()));
                }
            }, Executors.newSingleThreadExecutor());
        } catch (Exception e) {
            p.reject("ERROR", "Invalid work ID: " + e.getMessage());
        }
    }

    /**
     * Called from JS to get the instant information about some work
     * @param id the work's id
     * @param p the promise to send the work's info back to JS
     */
    @ReactMethod
    public void info(String id, final Promise p) {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            handler.post(() -> info(id, p));
            return;
        }

        try {
            ListenableFuture<WorkInfo> futureInfo = WorkManager.getInstance(context)
                .getWorkInfoById(UUID.fromString(id));
            
            futureInfo.addListener(() -> {
                try {
                    WorkInfo info = futureInfo.get();
                    if (info == null) {
                        handler.post(() -> p.reject("ERROR", "Work info not found for id: " + id));
                        return;
                    }
                    handler.post(() -> p.resolve(Arguments.fromBundle(Parser.getWorkInfo(info))));
                } catch (Exception e) {
                    handler.post(() -> p.reject("ERROR", "Failed to get work info: " + e.getMessage()));
                }
            }, Executors.newSingleThreadExecutor());
        } catch (Exception e) {
            p.reject("ERROR", "Invalid work ID: " + e.getMessage());
        }
    }

    /**
     * Method called to add a listener to changes on some work's info,
     * The callback is not called always after the subscription, so we manually send the information to a more
     * consistent behaviour
     * @param id the work's id that one wants to listen
     */
    @ReactMethod
    public void addListener(String id) {

        if(listeners.containsKey(id)) return;

        final LiveData<WorkInfo> data = WorkManager.getInstance(context).getWorkInfoByIdLiveData(UUID.fromString(id));

        final Observer<WorkInfo> listener = workInfo -> {
            if (workInfo == null) return;
            context.getJSModule(DeviceEventManagerModule.RCTDeviceEventEmitter.class)
                    .emit(id+"info", Arguments.fromBundle(Parser.getWorkInfo(workInfo)));
        };

        handler.post(() -> {
            data.observeForever(listener);
            listeners.put(id, listener);
        });

        WorkInfo info = data.getValue();
        if(info!=null) context.getJSModule(DeviceEventManagerModule.RCTDeviceEventEmitter.class)
                .emit(id+"info", Arguments.fromBundle(Parser.getWorkInfo(info)));

    }

    /**
     * Method to remove a listener from JS, it has problems when one work has multiple listener and
     * one of them unsubscribes, should set some counter
     * @param id the work's id that one wants to unsubscribe
     */
    @ReactMethod
    public void removeListener(String id) {

        Observer<WorkInfo> listener = listeners.get(id);

        if(listener==null) return;

        final LiveData<WorkInfo> data = WorkManager.getInstance(context).getWorkInfoByIdLiveData(UUID.fromString(id));

        handler.post(() -> {
            data.removeObserver(listener);
            listeners.remove(id);
        });

    }

}
