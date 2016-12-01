/*
* Copyright 2016 UnderArmour
*
* Licensed under the Apache License, Version 2.0 (the "License");
* you may not use this file except in compliance with the License.
* You may obtain a copy of the License at
*
*    http://www.apache.org/licenses/LICENSE-2.0
*
* Unless required by applicable law or agreed to in writing, software
* distributed under the License is distributed on an "AS IS" BASIS,
* WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
* See the License for the specific language governing permissions and
* limitations under the License.
*/

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
