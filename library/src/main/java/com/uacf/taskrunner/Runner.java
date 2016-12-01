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
import android.os.Looper;
import android.util.Log;

import java.util.*;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicLong;

/**
 * A wrapper around an async service (Invoker) that has the notion of a component
 * lifecycle. This allows Android UI things like Activity and Fragment to each
 * have their own pool of tasks that are robust against screen rotation and
 * other Lifecycle configuration changes.
 *
 * These are kind of like Loaders, but easier to use... hopefully.
 *
 * Instances of Runner are created implicitly through attach()ing statically.
 * Callers are required to call detach() when they are being destroyed (or
 * re-created), which will trigger a delayed shutdown and cleanup if not
 * re-attach()ed after a short period of time.
 *
 * The idea is this: because Android (generally) re-creates UI components very
 * quickly in response to configuration changes, most state between instances
 * can be stored in a single global cache with very short TTLs. Components
 * detach() during onDestroy, and attach() during onCreate. If not attach()ed
 * after some period of time, the Tasks will be stopped and their caches
 * cleared.
 *
 * Runners, once created, may be paused or resumed. When a Runner is paused,
 * subsequent run() operations will be queued but not started. Running
 * tasks are allowed to complete, but the results will be cached.
 *
 * When a Runner is resume()ed, Tasks enqueued while paused are started, and
 * any results that completed will be dispatched.
 *
 * Task completion and error events are reported through a TaskCallback interface,
 * which changes as component instances are re-created during configuration
 * changes. These callbacks are guaranteed to be called from the main thread.
 *
 * WARNING #1:
 *
 *   We keep a static map from { Context -> Runner }, which is only mutated
 *   during construction and destruction. Improperly maintaining this map may
 *   yield memory leaks or bad crashes. It is called DANGEROUS_STATIC_MAP.
 *   Please, DO NOT TOUCH THIS STRUCTURE UNLESS YOU HAVE TO AND UNDERSTAND
 *   WHAT YOU'RE DOING.
 *
 *   Everything else is pretty safe, just try and make sure the Task closures
 *   don't include Activity or Fragment instances and you should be fine
 *
 * WARNING #2:
 *
 *   This class may only be called from the "main" (aka "ui") thread, and will
 *   throw if called from the background.
 */
public class Runner {
    private static final boolean ENABLE_DEBUG_LOGGING = true;

    private static final String TAG = Runner.class.getCanonicalName();
    private static final String EXTRA_RUNNER_ID = TAG + ".runner_id";
    private static final String EXTRA_NAME = TAG + ".name";
    private static final String ANONYMOUS_TASK_NAME = TAG + ".ANONYMOUS_TASK";
    private static final int DEFAULT_RUNNER_TIMEOUT_MILLIS = 3500;

    private static final Map<Long, Runner> DANGEROUS_STATIC_MAP = new HashMap<>();
    private static final AtomicLong NEXT_ID = new AtomicLong(0);

    private Invoker invoker;
    private long id = -1;
    private String name;
    private Set<TaskCallbacks> callbacks;
    private State state = State.Paused;
    private Class<?> callingType;
    private CacheMode defaultCacheMode = CacheMode.None;
    private DedupeMode defaultDedupeMode = DedupeMode.Throw;

    private List<TaskDescriptor> pendingStart = new ArrayList<>();
    private Map<TaskDescriptor, Throwable> pendingDelivery = new HashMap<>();
    private Map<String, TaskDescriptor> runningByName = new HashMap<>();
    private Map<String, TaskDescriptor> instanceCache = new HashMap<>();
    private Context context;

    private enum State {
        Running,
        Paused,
        Destroyed
    }

    /**
     * Defines the mid-to-long lifetime of a Task result bound to a Runner.
     * Note that when Runner instances are destroyed, all Tasks are stopped
     * and internal are cleared forgotten.
     */
    public enum CacheMode {
        /**
         * Don't cache this Task result.
         */
        None,

        /**
         * Only cache this Task result if the exec() call succeeds. On failure,
         * forget about about it.
         */
        CacheOnSuccess,

        /**
         * Always cache the Task result, whether it's an error or failure.
         */
        CacheAlways
    }

    /**
     * Describes what should happen if the caller enqueues the same task more
     * than one time. By default an exception will be thrown.
     */
    public enum DedupeMode {
        /**
         * If a duplicate task is attempted to be run, throw an exception.
         */
        Throw,

        /**
         * If a duplicate task is enqueued, cancel the existing task and start
         * the new one.
         */
        CancelExisting,

        /**
         * If a duplicate task is enqueued, keep the existing task and discard
         * the new one.
         */
        UseExisting
    }

    /**
     * A task, and associated metadata, as scheduled by a Runner. This should
     * remain a PRIVATE structure.
     */
    private static class TaskDescriptor {
        Task task;
        String name;
        long id;
        CacheMode cacheMode;
        boolean canceled = false;
        Future<?> future;
    }

    /**
     * Interface used to communicate task completion status.
     */
    public interface TaskCallbacks {
        void onTaskCompleted(String name, long id, Task task, Object result);
        void onTaskError(String name, long id, Task task, Throwable error);
    }

    private Runner(final Context context,
                   final Class<?> callingType,
                   final String name,
                   long id,
                   Invoker.Builder builder) {
        final Context appContext = context.getApplicationContext();
        this.context = appContext != null ? appContext : context;
        this.name = name;
        this.id = id;
        this.callingType = callingType;

        if (builder == null) {
            builder = new Invoker.Builder(name).setUseCachedExecutor();
        }

        this.callbacks = new HashSet<>();
        this.invoker = builder.build();

        synchronized (DANGEROUS_STATIC_MAP) {
            DANGEROUS_STATIC_MAP.put(this.id, this);
        }
    }

    /**
     * Run the specified task. Note that because no name is specified, the task
     * will be considered "anonymous" and will not have any sort of caching
     * ability or de-duplication logic.
     *
     * @param task task instance
     * @return a globally unique task identifier
     */
    public long run(final Task task) {
        return run(ANONYMOUS_TASK_NAME, task);
    }

    /**
     * Run the specified task. CacheMode.None and DedupeMode.Throw will be
     * used by default.
     *
     * @param name the task's unique name
     * @param task the task instance
     * @return a globally unique task identifier.
     */
    public long run(final String name, final Task task)
    {
        return run(name, task, defaultCacheMode, defaultDedupeMode);
    }

    /**
     * Run the specified task with the specified caching policy. DedupeMode.Throw
     * will be used for de-duplication.
     *
     * @param name the task's unique name
     * @param task the task instance
     * @param cacheMode the cache mode
     * @return a globally unique task identifier
     */
    public long run(final String name, final Task task, CacheMode cacheMode) {
        return run(name, task, cacheMode, defaultDedupeMode);
    }

    /**
     * Run the specified task with the specified de-duplicate mode. CacheMode.None
     * will be used for caching logic.
     *
     * @param name the task's unique name
     * @param task the task instance
     * @param dedupeMode the de-duplication mode
     * @return a globally unique task identifier
     */
    public long run(final String name, final Task task, DedupeMode dedupeMode) {
        return run(name, task, defaultCacheMode, dedupeMode);
    }

    /**
     * Run the specified task with the specified CacheMode and DedupeMode.
     *
     * @param name the task's unique name
     * @param task a task instance
     * @param cacheMode the desired cache mode
     * @param dedupeMode the desired de-duplication mode
     * @return a globally unique task identifier
     */
    public long run(final String name,
                    final Task task,
                    CacheMode cacheMode,
                    DedupeMode dedupeMode)
    {
        throwIfDestroyed();
        throwIfNotOnMainThread();

        debug("attempting to enqueue task name='%s'", name);

        final boolean anonymous = ANONYMOUS_TASK_NAME.equals(name);

        /* non-anonymous tasks may only be in the queue once. if one already
        exists the queue, a behavior may be defined by the caller. honor this
        behavior here... by default we throw */
        if (!anonymous) {
            /* is it running right now? */
            TaskDescriptor existing = runningByName.get(name);

            /* if we found an existing task we need to de-duplicate it */
            if (existing != null) {
                switch (dedupeMode) {
                    case Throw:
                        throw new RuntimeException("Task already enqueued!");
                    case CancelExisting:
                        cancel(name);
                        break;
                    case UseExisting:
                        return existing.id;
                }
            }
        }
        else /* if anonymous */ {
            cacheMode = CacheMode.None; /* anon tasks never cached */
        }

        /* if we get here, the requested task isn't running yet. go ahead and
        construct our TaskDescriptor structure, this contains metadata about
        the task that's being enqueued */
        final TaskDescriptor descriptor = new TaskDescriptor();
        descriptor.name = name;
        descriptor.id = NEXT_ID.incrementAndGet();
        descriptor.cacheMode = cacheMode;
        descriptor.task = task;

        /* before proceeding, check the cache mode. if the caller has opted
        into caching, see if this task has already finished. */
        if (cacheMode != CacheMode.None) {
            final TaskDescriptor cachedDescriptor = instanceCache.get(name);
            if (cachedDescriptor != null) {
                debug("task name='%s' found in cache, short-circuiting...", name);
                postCompleted(cachedDescriptor);
                return cachedDescriptor.id;
            }
            else {
                /* cache mode requested, but cache miss. remember this
                TaskDescriptor  so subsequent calls get it back */
                instanceCache.put(name, descriptor);
            }
        }

        /* create mapping only after caching checking; we don't want to corrupt
        our local cache */
        runningByName.put(name, descriptor);

        debug("task name='%s' given id=%d", descriptor.name, descriptor.id);

        /* if the runner is paused, don't start immediately. */
        if (state == State.Paused) {
            pendingStart.add(descriptor);
        }
        /* otherwise, let's go! */
        else {
            startAsync(descriptor);
        }

        return descriptor.id;
    }

    /**
     * Cancel task with the specified name.
     *
     * @param name the unique task name as decided by the caller
     * @return true if the task was canceled, false if it was not found.
     */
    public boolean cancel(String name) {
        throwIfDestroyed();
        throwIfNotOnMainThread();

        final TaskDescriptor descriptor = runningByName.get(name);
        if (descriptor != null) {
            cancel(descriptor);
            return true;
        }

        return false;
    }

    /**
     * Cancel the task with the specified identifier.
     * @param id the unique task id assigned by run()
     * @return true if the task was canceled, false if it was not found.
     */
    public boolean cancel(long id) {
        throwIfDestroyed();
        throwIfNotOnMainThread();

        for (TaskDescriptor descriptor : runningByName.values()) {
            if (descriptor.id == id) {
                cancel(descriptor);
                return true;
            }
        }

        return false;
    }

    /**
     * Returns whether or not a task with the specified name is running.
     * @param taskName the unique task name as decided by the caller
     * @return true if this task is running, false otherwise
     */
    public boolean running(final String taskName) {
        throwIfNotOnMainThread();
        return runningByName.containsKey(taskName);
    }

    /**
     * Returns whether or not a task with the specified id is running. This is
     * an O(n) lookup!
     * @param id the unique task name as assigned by run()
     * @return true if this task is running, false otherwise
     */
    public boolean running(final long id) {
        throwIfNotOnMainThread();
        for (TaskDescriptor td : runningByName.values()) {
            if (td.id == id) {
                return true;
            }
        }
        return false;
    }

    /**
     * Pause the Runner. New run() calls will be enqueued (but not started), and
     * completed tasks will be remembered (but results not dispatched) until the Runner
     * has been resumed.
     */
    public void pause() {
        debug("(start) pause id=%d", id);
        throwIfDestroyed();
        throwIfNotOnMainThread();
        state = State.Paused;
        debug("(finish) pause id=%d", id);
    }

    /**
     * Resumes a Runner. All pending start and finished events will be fired before
     * resume() has finished.
     */
    public void resume() {
        debug("(start) resume id=%d", id);

        throwIfDestroyed();
        throwIfNotOnMainThread();

        state = State.Running;

        debug("dispatching task completion, total=%d", pendingDelivery.size());
        for (TaskDescriptor descriptor : pendingDelivery.keySet()) {
            completed(descriptor, pendingDelivery.get(descriptor));
        }

        debug("dispatching task start, total=%d", pendingStart.size());
        for (TaskDescriptor descriptor : pendingStart) {
            startAsync(descriptor);
        }

        pendingDelivery.clear();
        pendingStart.clear();

        debug("(finish) resume id=%d", id);
    }

    public static Runner attach(final Context context,
                                 final Class<?> callingType,
                                 final TaskCallbacks callbacks,
                                 final Bundle savedInstanceState) {
        return attach(context, callingType, callbacks, savedInstanceState, null);
    }

    /**
     * Attaches the instance to a Runner.
     *
     * @param context
     * @param callingType
     * @param callbacks
     * @param savedInstanceState
     * @param builder
     * @return
     */
    public static Runner attach(final Context context,
                                 final Class<?> callingType,
                                 final TaskCallbacks callbacks,
                                 final Bundle savedInstanceState,
                                 final Invoker.Builder builder) {
        throwIfNotOnMainThread();

        Runner runner;
        long id = NEXT_ID.incrementAndGet();

        if (savedInstanceState != null) {
            Bundle runnerBundle = savedInstanceState.getBundle(getExtraBundleName(callingType));

            if (runnerBundle != null) {
                id = runnerBundle.getLong(EXTRA_RUNNER_ID, id);
                debug("attempting to reattach to runner with id=%d", id);
            }
        }

        synchronized (DANGEROUS_STATIC_MAP) {
            runner = DANGEROUS_STATIC_MAP.get(id);
        }

        if (runner == null) {
            final String name = String.format("%s-%d", callingType.getCanonicalName(), id);
            runner = new Runner(context, callingType, name, id, builder);
        }

        runner.attach(callbacks);
        return runner;
    }

    public long getId() {
        return id;
    }

    public void saveState(Bundle bundle) {
        throwIfNotOnMainThread();

        if (bundle != null) {
            bundle.putBundle(getExtraBundleName(callingType), toBundle());
        }
    }

    public void detach(TaskCallbacks taskCallbacks) {
        detach(taskCallbacks, DEFAULT_RUNNER_TIMEOUT_MILLIS);
    }

    public void detach(TaskCallbacks taskCallbacks, long ttlMillis) {
        throwIfNotOnMainThread();
        debug("detaching id=%d", id, ttlMillis);

        if (!callbacks.contains(taskCallbacks)) {
            throw new RuntimeException("Wrong instance detaching??");
        }

        if (state == State.Running) {
            pause();
        }

        callbacks.remove(taskCallbacks);

        if (callbacks.isEmpty()) {
            debug("empty callback set! will destroy in %d millis", ttlMillis);
            invoker.getHandler().removeCallbacks(destroyRunnable);
            invoker.getHandler().postDelayed(destroyRunnable, ttlMillis);
        }
    }

    public boolean destroyed() {
        throwIfNotOnMainThread();
        return (state == State.Destroyed);
    }

    public boolean removeFromInstanceCache(final String taskName) {
        throwIfNotOnMainThread();
        return (instanceCache.remove(taskName) != null);
    }

    public void clearInstanceCache() {
        throwIfNotOnMainThread();
        instanceCache.clear();
    }

    public Runner setDefaultCacheMode(CacheMode mode) {
        defaultCacheMode = mode;
        return this;
    }

    public Runner setDefaultDedupeMode(DedupeMode mode) {
        defaultDedupeMode = mode;
        return this;
    }

    private void cancel(final TaskDescriptor descriptor) {
        descriptor.canceled = true;

        if (descriptor.future != null) {
            descriptor.future.cancel(true);
        }

        instanceCache.remove(descriptor.name);
    }

    private void startAsync(final TaskDescriptor descriptor) {
        descriptor.future = invoker.async(new Runnable() {
            @Override
            public void run() {
                try {
                    descriptor.task.run(context);
                    postCompleted(descriptor);
                }
                catch (Throwable ex) {
                    postCompleted(descriptor, ex);
                }
            }
        });
    }

    public void attach(TaskCallbacks callback) {
        throwIfDestroyed();
        throwIfNotOnMainThread();

        callbacks.add(callback);
        invoker.getHandler().removeCallbacks(destroyRunnable);
        debug("attached to runner with id=%d", id);
    }

    private void postCompleted(final TaskDescriptor descriptor) {
        postCompleted(descriptor, null);
    }

    private synchronized void postCompleted(final TaskDescriptor descriptor, final Throwable throwable) {
        if (state != State.Destroyed && invoker != null) {
            invoker.post(new Runnable() {
                public void run() {
                    completed(descriptor, throwable);
                }
            });
        }
    }

    private void completed(TaskDescriptor descriptor, Throwable throwable) {
        if (!destroyed()) {
            if (state == State.Paused) {
                debug("task finished but runner paused, stashing result until resume");
                pendingDelivery.put(descriptor, throwable);
            }
            else {
                /* so, the check here is totally unintuitive. here's what can
                happen: if the caller starts a Task, but it's canceled, then
                another task with the same name is started again, the first
                will be canceled then replaced with the new one. we ONLY remove
                this task from the running set if its the running one. */
                if (runningByName.get(descriptor.name) == descriptor) {
                    runningByName.remove(descriptor.name);
                }

                raiseCallback(descriptor, throwable);
            }
        }
    }

    private void raiseCallback(TaskDescriptor descriptor, Throwable throwable) {
        /* if we're raising the callback, we're done. reset the future */
        descriptor.future = null;

        /* if there's no failure, we should be able to pull the result from the
        task. however, this MAY fail depending on the implementation. if the get()
        fails, propagate that exception */
        Object result = null;
        if (throwable == null) {
            try {
                result = descriptor.task.get();
            }
            catch (Throwable ex) {
                throwable = ex;
            }
        }

        /* honor the requested caching policy. however, if we DO cache, make sure
        we only cache the result just in case the user has large instances (e.g.
        Fragments, Activities) implicitly bound by their closure */
        switch (descriptor.cacheMode) {
            case None:
                instanceCache.remove(descriptor.name);
                break;

            case CacheAlways:
                instanceCache.put(descriptor.name, descriptor);
                break;

            case CacheOnSuccess:
                if (throwable != null) {
                    instanceCache.remove(descriptor.name);
                }
                else {
                    instanceCache.put(descriptor.name, descriptor);
                }
                break;
        }

        if (descriptor.canceled) {
            debug(
                "task name='%s' id='%d' CANCELED",
                descriptor.name,
                descriptor.id
            );
        }
        else {
            /* make a copy of the callback set, just in case the user ends up calling back into
            attach() or detach() while we're iterating. */
            final Set<TaskCallbacks> callbacksCopy = new HashSet<>(callbacks);

            /* failure case */
            if (throwable != null) {
                debug(
                    "task name='%s' id='%d' FAILED with error='%s'",
                    descriptor.name,
                    descriptor.id,
                    throwable.getClass().getCanonicalName());

                for (TaskCallbacks callback : callbacksCopy) {
                    callback.onTaskError(descriptor.name, descriptor.id, descriptor.task, throwable);
                }
            }
            /* success case */
            else {
                debug(
                    "task name='%s' id='%d' SUCCEEDED with result type='%s'",
                    descriptor.name,
                    descriptor.id,
                    result == null ? "null" : result.getClass().getCanonicalName());

                for (TaskCallbacks callback : callbacksCopy) {
                    callback.onTaskCompleted(descriptor.name, descriptor.id, descriptor.task, result);
                }
            }
        }
    }

    private Bundle toBundle() {
        Bundle b = new Bundle();
        b.putLong(EXTRA_RUNNER_ID, id);
        b.putString(EXTRA_NAME, name);
        return b;
    }

    private static void throwIfNotOnMainThread() {
        if (Looper.getMainLooper().getThread() != Thread.currentThread()) {
            throw new RuntimeException("method not called from main thread");
        }
    }

    private void throwIfDestroyed() {
        if (state == State.Destroyed) {
            throw new IllegalStateException("instance already destroyed!");
        }
    }

    private static String getExtraBundleName(Class<?> classType) {
        return (TAG + "[" + classType.getCanonicalName() + "].runner");
    }

    private static void debug(String format, Object... args) {
        if (ENABLE_DEBUG_LOGGING) {
            Log.d(TAG, String.format(format, args));
        }
    }

    private Runnable destroyRunnable = new Runnable() {
        @Override
        public void run() {
            if (!destroyed()) {
                debug("destroying runner id=%d", id);

                synchronized (Runner.this) {
                    state = State.Destroyed;
                    invoker.shutdown();
                    invoker = null;
                }

                Runner removed;
                synchronized (DANGEROUS_STATIC_MAP) {
                    removed = DANGEROUS_STATIC_MAP.remove(id);
                }

                if (removed != null) {
                    removed.pendingDelivery.clear();
                    removed.pendingStart.clear();
                    removed.runningByName.clear();
                    removed.instanceCache.clear();
                    removed.callbacks.clear();
                }

                debug("destroyed runner id=%d", id);
            }
        }
    };
}
