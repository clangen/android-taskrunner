TaskRunner is what AsyncTasks or Loaders would be like if they were implemented better. Whatever that means.

# Introduction

Most Android apps suffer from couple fundamental problems:

1. **Losing work or crashing during screen rotations**: Android makes it very difficult to manage asynchronous work across device rotations or configuration changes.
2. **I/O on the main thread**: this results in slow screen loads, chunky scrolling, and in extreme cases, "Application Not Responding" dialogs which, to the end user, appear to be crashes.
3. **Memory leaks**: usually caused by performing asynchronous operations with callbacks that are implicitly bound to their containing Activity via closure. That means that Activities (including all their Fragments and Views) are retained until the operation complete. In many cases this is no longer than a few seconds, but in some cases it can be as long as minutes. In the case of programmer error, they may leak indefinitely. As the user navigates around the app, these leaks begin to pile up, and can eventually lead to OutOfMemoryExceptions.
4. **Modifying paused, destroyed components**: depending on timing, the callbacks described in (2) may be invoked while the UI component is in one of the following 'invalid' states:
    * *Paused*: modifying UI components while they are in the paused state leads to buggy behavior, especially for Fragments. The UI updates may be reverted by the platform automatically during the resume cycle.
    * *Destroyed*: the runtime will generally throw an exception if UI components are modified after they have been destroyed.

**TaskRunner** is a small library that helps address of these problems.

# What does TaskRunner do?

TaskRunner does the following:

1. Keeps track of its respective component's lifecycle and ensures completion events are not raised unless the component is in a valid (i.e. active) state.
2. Ensures work is tracked/cached across screen rotations and other configuration changes
3. Is designed to discourage implicit binding via closure to Activity instances
3. Adds an easy facility to do simple I/O bound tasks in the background, safely

# How does TaskRunner work?

TaskRunner requires cooperation with the component (Activity or Fragment) it is bound to. The hosting component must notify its Runner instance about certain changes to its lifecycle. It follows a couple very simple rules:

1. A Runner may be *paused* or *resumed*, and *attached* or *detached*.
2. *Attached* instances are bound to a component, and may be either paused or resumed. 
3. Instances are considered *paused* when their containing component is paused. Paused Runners may accept new Tasks, however:
    * These Tasks will not be started until the Runner is resumed.
    * Work that was started while resumed, but finished while paused, will be cached but not relayed to the containing component until the Runner is resumed.
4. Instances are considered *resumed* when their containing component is resumed. Resumed instances are allowed to accept and start work, and also delivered results.
5. Instances become *detached* when their containing component is destroyed. Upon detach, the instance is added to a global cache with a short TTL. If not re-attached within a short period of time, the Runner will be removed from the cache and all work (pending and completed) will be forgotten. The short TTL allows the library to automatically clean up resources for orphaned Runners/Tasks in a predicable manner, and ensures that work is almost never restarted.

The following sequence diagram explains common behavior visually:

Note: in this diagram Activity could instead be a Fragment, Service, or any other construct that needs a Runner.

![sequence diagram 1](https://github.com/underarmour/android-taskrunner/blob/master/static/img/sequence_diagram_1.png?raw=true)

# Integrating TaskRunner with your app

If you read the previous section you'll notice an emphasis on using Activities and Fragments to drive Runners. This may seem slightly unintuitive at first, but it can actually help reduce tight coupling between components. It's best to think of Tasks as light-weight, reliable asynchronous wrappers around existing synchronous operations. This paradigm works well with service-oriented architectures. In a nutshell, the idea is this:

1. Use a micro-service oriented architecture for your Android app. Services house business logic for things not strictly related to UI interaction – communicating with the backend, querying the database, etc. Note that services do not necessarily need to be subclasses of Android's Service base class. Rather, they are components within an Android application that perform I/O bound operations on behalf of the user interface.
2. Remove all AsyncTasks and Loaders from your UI. TaskRunner will handle this for you. Service methods should simply return their result on the calling thread, throwing an exception if necessary.
3. Write Tasks that wrap the synchronous methods as required. 

A sequence diagram for this approach looks like the following:

![sequence diagram 2](https://github.com/underarmour/android-taskrunner/blob/master/static/img/sequence_diagram_2.png?raw=true)

Interaction using an event bus library (e.g. Otto) may look like the following:

![sequence diagram 3](https://github.com/underarmour/android-taskrunner/blob/master/static/img/sequence_diagram_3.png?raw=true)

Following this approach has the these benefits:

1. **Services become easier to write** – no need to worry about multi-threading in most cases. All public methods can be synchronous.
2. **Synchronous methods make the service layer easier to test**. You won't be required to create CountDownLatches with timeouts, or spin waiting for results.
3. **Tasks are easily reusable**, and can be integrated into ViewModels easily.

# Usage

## Lifecycle Integration

The best way to integrate with TaskRunner is to create an instance of `com.uacf.taskrunner.LifecycleDelegate` in your Activity or Fragment and forward the relevant lifecycle events to it: `onCreate`, `onSaveInstanceState`, `onPause`, `onResume`, and `onDestroy`. Make sure you instantiate the `LifecycleDelegate` in `onCreate`. 

Your Activity will also need to implement `com.uacf.taskrunner.Runner.TaskCallbacks` (or create an inner class that implements it) to receive results.

Here's an example:

```java
class TaskRunnerActivity extends AppCompatActivity implements Runner.TaskCallbacks {
    private final LifecycleDelegate delegate;

    public void onCreate() {
        super.onCreate();
        delegate = new LifecycleDelegate(this, this, getClass(), null);
        delegate.onCreate();
    }

    public void onDestroy() {
        super.onDestroy();
        delegate.onDestroy();
    }

    public void onPause() {
        super.onPause();
        delegate.onPause();
    }

    public void onRersume() {
        super.onResume();
        delegate.onResume();
    }

    public void onSaveInstanceState(Bundle state) {
        super.onSaveInstanceState(state);
        delegate.onSaveInstanceState(state);
    }

    public void onTaskCompleted(String name, long id, Task task, Object result) {
        /* process results here */
    }

    public void onTaskError(String name, long id, Task task, Throwable error) {
        /* process errors here */
    }

    protected Runner getRunner() {
        return delegate.runner();
    }
}
```

## Defining a Task

Tasks should either be defined as static inner classes, or regular outer classes. That is, you don't want your Task to have an implicit binding to whatever Activity/Fragment is instantaiting it.

```java
private static class MyTask extends Tasks.Blocking<MyResultType, MyErrorType> {
    public MyTask(...) {
    }

    @Override
    protected MyResultType exec(final Context context) throws MyErrorType {
        final MyResultType result = ...;
        ...
        return result;
    }
}
```

Some important notes:

* Tasks are parameterized by two types: the `ResultType`, and an `ErrorType`.
* All long-running work should be performed in the `ResultType exec(final Context context) throws MyErrorType` method.
* You may pass data via constructor, but you should not retain anything that is implicitly (or explicitly) bound to the host Activity or Fragment.
* Generally you should use `Tasks.Blocking` as your base class. This type of task will be run on a background thread, but will block waiting for a result. `Tasks.AsyncWait` is also provided and can be used to wrap existing asynchronous operations.
* If your operation does not throw an explicit exception, you can use `Tasks.Blocking.Unchecked<ResultType>` to omit specifying an `ErrorType`.

## Starting a Task

Once your Activity has been integrated with `LifecycleDelegate` and you have a Task defined, you're ready to go:

```java
class MyActivity extends TaskRunnerActivity {
    private long myTaskId = -1;

    public void onUserAction() {
        taskId = getRunner().run(new MyTask(argument));
    }

    void onTaskCompleted(String name, long id, Task task, Object result) {
        if (id == myTaskId) {
            /* done! */
        }
    }

    void onTaskError(String name, long id, Task task, Throwable error) {
        if (id == myTaskId) {
            /* error! */
        }
    }

    ...
}
```

Note that this example matches task completion by `id`. Users may also compare tasks by `name` by using the `Runner.run(String name, Task task)` method.

When running by name the caller can opt in to caching. If a Task is run to completion, and another Task is enqueued with the same name, this operation can be de-duped and the result from the first Task can be return immediately. Note that cached results are only available to the same Activity/Fragment that instantiated the Task. That is, if two Activities define Tasks with the same name, one will not get cached results from the other. Here's an example:

```java
getRunner().run("MyNamedTask", new MyTask(...), Runner.CacheMode.CacheOnSuccess);
```

Similarly, if two name Tasks are enqueued at the same time, the user can specify the preferred de-duplication method:

```java
getRunner().run("MyNamedTask", new MyTask(...), Runner.DedupeMode.UseExisting);
```

The default cache mode is `CacheMode.None` (don't cache anything) and the default dedupe method is `DedupeMode.Throw` (throws an exception if two tasks with the same name are enqueued simultaenously). The default modes may be tweaked by calling `runner.setDefaultCacheMode()` and `runner.setDefaultDedupeMode()`.

# Task best practices

When writing tasks always do the following:

1. Task subclasses that are inner classes should always be declared as static, as to not leak implicit references of their containing type
2. Task subclasses should be extremely judicious about their inputs, and not accept anything that that explicitly, or implicitly references large objects (e.g. Views, Fragments, Activities, Bitmaps, etc).

# Critique and future

Although TaskRunner helps solve a couple fundamental issues with Android app development, it can certainly be better.

Perhaps the biggest problem with TaskRunner is the proliferation of small wrapper classes that exist just to call through to service methods. While Tasks are generally easy to write, they do take time and are not exactly elegant. In the future we'd like to figure out a way to dynamically create Tasks at compile-time, perhaps leveraging some clever use of annotations. 

Additionally, TaskRunner provides no facility for composing or chaining Tasks. This, however, may be addressed by using something like RxJava's Observers synchronously within a Task's work method.
