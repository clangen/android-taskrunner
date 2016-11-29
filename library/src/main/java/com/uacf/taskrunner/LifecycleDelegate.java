package com.uacf.taskrunner;

import android.content.Context;
import android.os.Bundle;

public class LifecycleDelegate {
    private Runner runner;
    private Context context;
    private Runner.TaskCallbacks callbacks;
    private Class<?> callingType;
    private Invoker.Builder builder;

    public LifecycleDelegate(Context context,
                             Runner.TaskCallbacks callbacks,
                             Class<?> callingType,
                             Invoker.Builder builder) {
        final Context appContext = context.getApplicationContext();
        this.context = appContext != null ? appContext : context;
        this.callbacks = callbacks;
        this.callingType = callingType;
        this.builder = builder;
    }

    public void onCreate(Bundle savedInstanceState) {
        runner = Runner.attach(context, callingType, callbacks, savedInstanceState, builder);
    }

    public void onSaveInstanceState(Bundle outState) {
        runner.saveState(outState);
    }

    public void onResume() {
        runner.resume();
    }

    public void onPause() {
        runner.pause();
    }

    public void onDestroy() {
        runner.detach(callbacks);
    }

    public Runner runner() {
        return runner;
    }
}
