/**
 * Copyright 2015 Palantir Technologies
 *
 * Licensed under the BSD-3 License (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://opensource.org/licenses/BSD-3-Clause
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.palantir.atlasdb.keyvalue.remoting.iterators;

import java.util.Set;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.google.common.collect.ImmutableList;
import com.palantir.atlasdb.keyvalue.api.KeyValueService;
import com.palantir.atlasdb.keyvalue.api.RangeRequest;
import com.palantir.atlasdb.keyvalue.api.RowResult;
import com.palantir.common.base.ClosableIterator;

public class TimestampsRangeIterator extends RangeIterator<Set<Long>> {
    @JsonCreator
    public TimestampsRangeIterator(@JsonProperty("tableName") String tableName,
                                   @JsonProperty("range") RangeRequest range,
                                   @JsonProperty("timestamp") long timestamp,
                                   @JsonProperty("hasNext") boolean hasNext,
                                   @JsonProperty("page") ImmutableList<RowResult<Set<Long>>> page) {
        super(tableName, range, timestamp, hasNext, page);
    }

    @Override
    protected ClosableIterator<RowResult<Set<Long>>> getMoreRows(KeyValueService kvs, String tableName,
                                                                 RangeRequest newRange, long timestamp) {
        return kvs.getRangeOfTimestamps(tableName, newRange, timestamp);
    }
}
