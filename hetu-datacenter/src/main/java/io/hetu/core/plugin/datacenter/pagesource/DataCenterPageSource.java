/*
 * Copyright (C) 2018-2020. Huawei Technologies Co., Ltd. All rights reserved.
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package io.hetu.core.plugin.datacenter.pagesource;

import com.google.common.collect.ImmutableMap;
import io.airlift.log.Logger;
import io.prestosql.client.DataCenterClientSession;
import io.prestosql.client.DataCenterStatementClient;
import io.prestosql.spi.Page;
import io.prestosql.spi.connector.ColumnHandle;
import io.prestosql.spi.connector.ConnectorPageSource;
import io.prestosql.spi.dynamicfilter.BloomFilterDynamicFilter;
import io.prestosql.spi.dynamicfilter.DynamicFilter;
import io.prestosql.spi.dynamicfilter.HashSetDynamicFilter;
import okhttp3.OkHttpClient;

import java.io.IOException;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.Set;
import java.util.function.Supplier;

/**
 * Data center page source.
 *
 * @since 2020-02-11
 */
public class DataCenterPageSource
        implements ConnectorPageSource
{
    private static final Logger LOGGER = Logger.get(DataCenterPageSource.class);

    private final long startTime;
    private final int numberOfColumns;
    private DataCenterStatementClient client;
    private long readBytes;
    private long lastMemoryUsage;
    private Queue<Page> pages = new LinkedList<>();
    private final Supplier<Map<ColumnHandle, DynamicFilter>> dynamicFilterSupplier;
    private final Set<String> appliedDynamicFilters = new HashSet<>();

    /**
     * Constructor of data center page source.
     *
     * @param httpClient http client.
     * @param clientSession session client.
     * @param sql sql statement.
     * @param queryId id of query that user issued.
     * @param columns columns of sql.
     */
    public DataCenterPageSource(OkHttpClient httpClient, DataCenterClientSession clientSession, String sql,
            String queryId, List<ColumnHandle> columns)
    {
        this(httpClient, clientSession, sql, queryId, columns, null);
    }

    public DataCenterPageSource(OkHttpClient httpClient, DataCenterClientSession clientSession, String sql,
            String queryId, List<ColumnHandle> columns, Supplier<Map<ColumnHandle, DynamicFilter>> dynamicFilterSupplier)
    {
        this.startTime = System.nanoTime();
        this.client = DataCenterStatementClient.newStatementClient(httpClient, clientSession, sql, queryId);
        this.numberOfColumns = columns.size();
        this.dynamicFilterSupplier = dynamicFilterSupplier;
    }

    @Override
    public long getCompletedBytes()
    {
        return this.readBytes;
    }

    @Override
    public long getReadTimeNanos()
    {
        return System.nanoTime() - this.startTime;
    }

    @Override
    public boolean isFinished()
    {
        return this.pages.isEmpty() && !this.client.isRunning();
    }

    @Override
    public Page getNextPage()
    {
        if (dynamicFilterSupplier != null) {
            applyDynamicFilters(dynamicFilterSupplier.get());
        }

        if (!this.pages.isEmpty()) {
            return processPage(this.pages.poll());
        }
        if (this.client.isRunning()) {
            List<Page> pageList = client.getPages();
            if (pageList != null && !pageList.isEmpty()) {
                this.update(pageList);
                this.pages.addAll(pageList);
            }
            this.client.advance();
        }
        return null;
    }

    private void applyDynamicFilters(Map<ColumnHandle, DynamicFilter> dynamicFilters)
    {
        ImmutableMap.Builder<String, DynamicFilter> builder = new ImmutableMap.Builder();
        for (Map.Entry<ColumnHandle, DynamicFilter> entry : dynamicFilters.entrySet()) {
            if (!appliedDynamicFilters.contains(entry.getKey().getColumnName())) {
                DynamicFilter df = entry.getValue();
                if (df instanceof HashSetDynamicFilter) {
                    df = BloomFilterDynamicFilter.fromHashSetDynamicFilter((HashSetDynamicFilter) df);
                    ((BloomFilterDynamicFilter) df).createSerializedBloomFilter();
                }
                builder.put(entry.getKey().getColumnName(), df);
            }
        }

        Map<String, DynamicFilter> newDynamicFilters = builder.build();
        if (!newDynamicFilters.isEmpty()) {
            if (client.applyDynamicFilters(newDynamicFilters)) {
                appliedDynamicFilters.addAll(newDynamicFilters.keySet());
            }
        }
    }

    private Page processPage(Page page)
    {
        if (this.numberOfColumns == 0) {
            // request/response with no columns, used for queries like "select count star"
            return new Page(page.getPositionCount());
        }
        else {
            return page;
        }
    }

    private void update(List<Page> pageList)
    {
        long bytes = 0L;
        long memory = 0L;
        for (Page page : pageList) {
            bytes += page.getSizeInBytes();
            memory += page.getRetainedSizeInBytes();
        }
        this.readBytes += bytes;
        this.lastMemoryUsage = memory;
    }

    @Override
    public long getSystemMemoryUsage()
    {
        return this.lastMemoryUsage;
    }

    @Override
    public void close()
            throws IOException
    {
        this.client.close();
    }
}
