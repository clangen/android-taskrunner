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

import android.os.Handler;
import android.os.Looper;

import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * A wrapper around an ExecutorService and a Handler that provides easy
 * semantics for front-end applications that need to do work in the
 * background but be notified of completion on the main thread.
 *
 * Created by clangen on 10/21/14.
 */
public final class Invoker {
    private static final String TAG = Invoker.class.getSimpleName();

    private static final int DEFAULT_THREAD_COUNT = 3;

    private AtomicInteger counter = new AtomicInteger();
    private ExecutorType executorType = ExecutorType.Cached;
    private ExecutorService executor;
    private Handler handler = new Handler(Looper.getMainLooper());
    private String name;
    private int threadCount = DEFAULT_THREAD_COUNT;
    private boolean destroyed;

    private enum ExecutorType {
        FixedSize,
        Cached
    }

    /**
     * A Builder interface that can be used for constructing and customizing
     * the behavior of an Invoker.
     * @param <T>
     */
    public static class Builder<T extends Builder> {
        private Invoker instance = new Invoker();

        public Builder(String name) {
            if (name == null || name.trim().length() == 0) {
                throw new IllegalArgumentException("tag must not be null");
            }

            instance.name = name;
            instance.threadCount = DEFAULT_THREAD_COUNT;
        }

        public T setHandler(Handler handler) {
            instance.handler = handler;
            return (T) this;
        }

        public T setUseFixedSizeExecutor(int threadCount) {
            instance.threadCount = threadCount;
            instance.executorType = ExecutorType.FixedSize;
            return (T) this;
        }

        public T setUseCachedExecutor() {
            instance.threadCount = DEFAULT_THREAD_COUNT;
            instance.executorType = ExecutorType.Cached;
            return (T) this;
        }

        public Invoker build() {
            return instance;
        }
    }

    public enum InvokeMode {
        Auto,
        Async,
        Post,
        Sync
    }

    /**
     * Run the specified runnable on a background thread.
     * @param runnable
     */
    public Future<?> async(Runnable runnable) {
        throwIfDestroyed();
        return safeGetExecutor().submit(runnable);
    }

    /**
     * Run the specified runnable on a background thread, and call
     * the speicifed completion handler on the Handler thread when
     * finished.
     *
     * @param runnable
     * @param completion
     */
    public Future<?> async(final Runnable runnable, final Runnable completion) {
        throwIfDestroyed();
        return safeGetExecutor().submit(new Runnable() {
            @Override
            public void run() {
                runnable.run();

                if (!destroyed) {
                    post(completion);
                }
            }
        });
    }

    /**
     * Run the specified runnable now, on the current thread.
     * @param runnable
     */
    public void sync(Runnable runnable) {
        throwIfDestroyed();
        runnable.run();
    }

    /**
     * Run the specified Runnable on the Handler thread
     * @param runnable
     */
    public void post(Runnable runnable) {
        throwIfDestroyed();
        handler.post(runnable);
    }

    /**
     * Run the specified Runnable on the Handler thread after the specified
     * delay.
     * @param runnable
     * @param delay
     */
    public void post(Runnable runnable, int delay) {
        throwIfDestroyed();
        handler.postDelayed(runnable, delay);
    }

    /**
     * If the current thread is Handler thread, run the specified Runnable in
     * the background. Otherwise, run it synchronously on the current thread
     * @param runnable
     */
    public void auto(Runnable runnable) {
        invoke(runnable, InvokeMode.Auto);
    }

    /**
     * Run the specified Runnable with the specified mode (async, sync, or auto)
     * @param runnable
     * @param mode
     */
    public void invoke(Runnable runnable, InvokeMode mode) {
        throwIfDestroyed();

        if (mode == InvokeMode.Async) {
            async(runnable);
        }
        else if (mode == InvokeMode.Post) {
            post(runnable);
        }
        else if (mode == InvokeMode.Sync) {
            sync(runnable);
        }
        else {
            if (handler.getLooper() == Looper.myLooper()) {
                async(runnable);
            }
            else {
                sync(runnable);
            }
        }
    }

    /**
     * Get the Handler instance used by this instance.
     * @return
     */
    public Handler getHandler() {
        return handler;
    }

    public synchronized void shutdown() {
        if (executor != null) {
            executor.shutdownNow();
            executor = null;
        }

        destroyed = true;
    }

    private ExecutorService createExecutorService() {
        final ThreadFactory factory = new ThreadFactory() {
            @Override
            public Thread newThread(final Runnable runnable) {
                final Thread thread = new Thread(runnable);
                thread.setName(name + "-" + String.valueOf(counter.incrementAndGet()));
                return thread;
            }
        };

        switch (executorType) {
            case FixedSize:
                return Executors.newFixedThreadPool(threadCount, factory);

            case Cached:
                /* same thing Executors.java does, except with a shorter thread timeout
                (we use 10 seconds, instead of 60). */
                return new ThreadPoolExecutor(
                    0, Integer.MAX_VALUE,
                    10L, TimeUnit.SECONDS,
                    new SynchronousQueue<Runnable>(),
                    factory);
        }

        throw new RuntimeException("invalid ExecutorType specified. this should never happen!");
    }

    private synchronized ExecutorService safeGetExecutor() {
        if (executor == null) {
            executor = createExecutorService();
        }

        return executor;
    }


    private synchronized void throwIfDestroyed() {
        if (destroyed) {
            throw new RuntimeException(TAG + " " + name + " already destroyed.");
        }
    }
}
