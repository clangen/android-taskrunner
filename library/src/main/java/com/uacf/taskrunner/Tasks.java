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

import java.util.concurrent.CountDownLatch;

public class Tasks {
    /**
     * An abstract implementation of Task.
     * <p/>
     * get() WILL throw if data isn't available yet; it will only be returned
     * after its cached by run().
     *
     * @param <ResultT>
     * @param <ErrorT>
     */
    public static abstract class Blocking<ResultT, ErrorT extends Throwable> implements Task<ResultT, ErrorT> {
        private ResultT result;
        private final Object resultMonitor = new Object();

        /**
         * Interface method. Pull and cache the result.
         *
         * @param context a Context instance
         * @return the computed result
         * @throws Error
         */
        @Override
        public final ResultT run(final Context context) throws ErrorT {
            synchronized (this) {
                synchronized (resultMonitor) {
                    if (result != null) {
                        return result;
                    }
                }

                ResultT r = exec(context);

                synchronized (resultMonitor) {
                    result = r;
                }
            }

            return result;
        }

        @Override
        public final ResultT get() throws ErrorT, NotCompletedException {
            synchronized (resultMonitor) {
                if (result == null) {
                    throw new NotCompletedException();
                }

                return result;
            }
        }

        /**
         * Pulls the data from wherever it comes from, and returns it. Throws
         * the specified error type if it has to.
         * <p/>
         * This is *always* a blocking call, unlike "get".
         *
         * @param context a Context instance
         * @return the computed result
         * @throws Error
         */
        protected abstract ResultT exec(Context context) throws ErrorT;

        /**
         * Doesn't require "throws" notation when declaring, so "unsafe". Easier to
         * to use though...
         *
         * @param <ResultT>
         */
        public static abstract class Unchecked<ResultT> extends Blocking<ResultT, RuntimeException> {
            /* whee */
        }
    }

    /**
     * A wrapper Task that may be used for jobs that are already asynchronous.
     *
     * get() will block until the specified Completion handler is finalize()d in
     * the pull() method.
     *
     * As of now, this type should generally be avoided unless necessary because it
     * essentially uses a background thread to block waiting for another background
     * thread.
     *
     * @param <ResultT> the result type
     * @param <ErrorT> the error type
     */
    public static abstract class AsyncWait<ResultT, ErrorT extends Throwable> implements Task<ResultT, ErrorT> {
        /**
         * The completion handler to be called when the asynchronous job run has
         * completed.
         *
         * @param <ResultT> the result type
         * @param <ErrorT> the error type
         */
        public interface Completion<ResultT, ErrorT> {
            void setResult(ResultT type);
            void setFailure(ErrorT error);
            void complete();
        }

        private ResultT result;
        private ErrorT error;

        @Override
        public ResultT get() throws ErrorT {
            synchronized (this) {
                if (result == null) {
                    if (error == null) {
                        throw new NotCompletedException();
                    }
                    else {
                        throw error;
                    }
                }

                return result;
            }
        }

        @Override
        public final ResultT run(Context context) throws ErrorT {
            final CountDownLatch latch = new CountDownLatch(1);

            exec(context, new Completion<ResultT, ErrorT>() {
                @Override
                public void setResult(ResultT result) {
                    synchronized (AsyncWait.this) {
                        AsyncWait.this.result = result;
                    }
                }

                @Override
                public void setFailure(ErrorT error) {
                    synchronized (AsyncWait.this) {
                        AsyncWait.this.error = error;
                    }
                }

                @Override
                public void complete() {
                    latch.countDown();
                }
            });

            try {
                latch.await();
            }
            catch (InterruptedException ex) {
                /* no-op */
            }

            return get();
        }

        public abstract void exec(Context context, Completion<ResultT, ErrorT> completion) throws ErrorT;

        /**
         * Doesn't require "throws" notation when declaring, so "unchecked". Easier to
         * to use though...
         *
         * @param <ResultT> the result type
         */
        public static abstract class Unchecked<ResultT> extends AsyncWait<ResultT, RuntimeException> {
            /* whee */
        }
    }
}