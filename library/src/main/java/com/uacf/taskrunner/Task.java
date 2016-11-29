package com.uacf.taskrunner;

import android.content.Context;

/**
 * Interface used by Runner that represents a "task" bound to a component.
 *
 * @param <ResultT> the type of data the Task returns
 * @param <ErrorT> the type of Exception that may be thrown while resolving the result.
 */
public interface Task<ResultT, ErrorT extends Throwable> {
    /**
     * External entry point. Pulls data and caches the data for get().
     *
     * @return the computed result
     * @throws Error
     */
    ResultT run(Context context) throws ErrorT;

    /**
     * Returns the result of a pull, or throws if it's not available yet.
     *
     * @return the computed, result
     * @throws NotCompletedException
     */
    ResultT get() throws ErrorT;
}
