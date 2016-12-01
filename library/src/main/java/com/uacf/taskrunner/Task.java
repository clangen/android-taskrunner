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
