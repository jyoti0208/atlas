/**
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 * <p>
 * http://www.apache.org/licenses/LICENSE-2.0
 * <p>
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.atlas.services;

import com.google.common.annotations.VisibleForTesting;
import com.google.inject.Singleton;
import org.apache.atlas.ApplicationProperties;
import org.apache.atlas.AtlasException;
import org.apache.atlas.model.metrics.AtlasMetrics;
import org.apache.atlas.repository.graph.AtlasGraphProvider;
import org.apache.atlas.repository.graphdb.AtlasGraph;
import org.apache.atlas.util.AtlasGremlinQueryProvider;
import org.apache.atlas.util.AtlasGremlinQueryProvider.AtlasGremlinQuery;
import org.apache.commons.configuration.Configuration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.inject.Inject;
import javax.script.ScriptException;
import java.util.List;
import java.util.Map;

@Singleton
public class MetricsService {
    private static final Logger LOG = LoggerFactory.getLogger(MetricsService.class);

    // Query Category constants
    public static final String TYPE    = "type";
    public static final String ENTITY  = "entity";
    public static final String TAG     = "tag";
    public static final String GENERAL = "general";

    // Query names
    protected static final String METRIC_TYPE_COUNT        = TYPE + "Count";
    protected static final String METRIC_TYPE_UNUSED_COUNT = TYPE + "UnusedCount";
    protected static final String METRIC_TYPE_ENTITIES     = TYPE + "Entities";

    protected static final String METRIC_ENTITY_COUNT    = ENTITY + "Count";
    protected static final String METRIC_ENTITY_DELETED  = ENTITY + "Deleted";
    protected static final String METRIC_TAGGED_ENTITIES = ENTITY + "Tagged";
    protected static final String METRIC_TAGS_PER_ENTITY = ENTITY + "Tags";

    protected static final String METRIC_TAG_COUNT        = TAG + "Count";
    protected static final String METRIC_ENTITIES_PER_TAG = TAG + "Entities";

    public static final String METRIC_QUERY_PREFIX       = "atlas.metric.query.";
    public static final String METRIC_QUERY_CACHE_TTL    = "atlas.metric.query.cache.ttlInSecs";
    public static final int    DEFAULT_CACHE_TTL_IN_SECS = 900;

    public static final String METRIC_COLLECTION_TIME = "collectionTime";

    private static Configuration            configuration = null;
    private static AtlasGremlinQueryProvider gremlinQueryProvider = null;

    private final AtlasGraph                atlasGraph;
    private final int                       cacheTTLInSecs;

    private AtlasMetrics cachedMetrics       = null;
    private long         cacheExpirationTime = 0;


    @Inject
    public MetricsService() throws AtlasException {
        this(ApplicationProperties.get(), AtlasGraphProvider.getGraphInstance());
    }

    @VisibleForTesting
    MetricsService(Configuration configuration, AtlasGraph graph) {
        MetricsService.configuration = configuration;

        atlasGraph        = graph;
        cacheTTLInSecs    = configuration != null ? configuration.getInt(METRIC_QUERY_CACHE_TTL, DEFAULT_CACHE_TTL_IN_SECS)
                : DEFAULT_CACHE_TTL_IN_SECS;
        gremlinQueryProvider = AtlasGremlinQueryProvider.INSTANCE;
    }

    @SuppressWarnings("unchecked")
    public AtlasMetrics getMetrics(boolean ignoreCache) {
        if (ignoreCache || !isCacheValid()) {
            AtlasMetrics metrics = new AtlasMetrics();

            for (MetricQuery metricQuery : MetricQuery.values()) {
                try {
                    if (LOG.isDebugEnabled()) {
                        LOG.debug("Executing query: {}", metricQuery);
                    }
                    executeGremlinQuery(metrics, metricQuery.group, metricQuery.name, metricQuery.query);
                } catch (ScriptException e) {
                    if (LOG.isDebugEnabled()) {
                        LOG.debug("Gremlin execution failed for metric {}", metricQuery, e);
                    } else {
                        LOG.warn("Gremlin execution failed for metric {}", metricQuery);
                    }
                }
            }

            long collectionTime = System.currentTimeMillis();

            metrics.addData(GENERAL, METRIC_COLLECTION_TIME, collectionTime);

            this.cachedMetrics       = metrics;
            this.cacheExpirationTime = (collectionTime + cacheTTLInSecs * 1000);
        }

        return cachedMetrics;
    }

    private void executeGremlinQuery(AtlasMetrics metrics, String type, String name, String query) throws ScriptException {
        Object result = atlasGraph.executeGremlinScript(query, false);

        if (result instanceof Number) {
            metrics.addData(type, name, ((Number) result).intValue());
        } else if (result instanceof List) {
            for (Map<String, Number> resultMap : (List<Map<String, Number>>) result) {
                for (Map.Entry<String, Number> entry : resultMap.entrySet()) {
                    metrics.addData(type, entry.getKey(), entry.getValue().intValue());
                }
            }
        } else {
            String returnClassName = result != null ? result.getClass().getSimpleName() : "null";

            LOG.warn("Unhandled return type {} for {}. Ignoring", returnClassName, query);
        }
    }

    private boolean isCacheValid() {
        boolean valid = cachedMetrics != null && System.currentTimeMillis() < cacheExpirationTime;

        if (LOG.isDebugEnabled()) {
            LOG.debug("cachedMetrics: {}", cachedMetrics != null);
            LOG.debug("cacheExpirationTime: {}", cacheExpirationTime);
            LOG.debug("valid: {}", valid);
        }

        return valid;
    }

    private static String getQuery(String type, String name, String defaultQuery) {
        String ret = configuration != null ? configuration.getString(METRIC_QUERY_PREFIX + type + "." + name, defaultQuery)
                : defaultQuery;

        if (LOG.isDebugEnabled()) {
            LOG.debug("query for {}.{}: {}", type, name, ret);
        }

        return ret;
    }

    /**
     * MetricQuery enum has the capability of reading the queries from the externalized config.
     *
     * The default behavior is to read from the properties and override the statically type query if the configured
     * query is not blank/empty.
     */
    private enum MetricQuery {
        TYPE_COUNT(GENERAL, METRIC_TYPE_COUNT, AtlasGremlinQuery.TYPE_COUNT_METRIC),
        UNUSED_TYPE_COUNT(GENERAL, METRIC_TYPE_UNUSED_COUNT, AtlasGremlinQuery.TYPE_UNUSED_COUNT_METRIC),
        ENTITY_COUNT(GENERAL, METRIC_ENTITY_COUNT, AtlasGremlinQuery.ENTITY_COUNT_METRIC),
        TAGS_COUNT(GENERAL, METRIC_TAG_COUNT, AtlasGremlinQuery.TAG_COUNT_METRIC),
        DELETED_ENTITY_COUNT(GENERAL, METRIC_ENTITY_DELETED, AtlasGremlinQuery.ENTITY_DELETED_METRIC),

        ENTITIES_PER_TYPE(ENTITY, METRIC_TYPE_ENTITIES, AtlasGremlinQuery.ENTITIES_PER_TYPE_METRIC),
        TAGGED_ENTITIES(ENTITY, METRIC_TAGGED_ENTITIES, AtlasGremlinQuery.TAGGED_ENTITIES_METRIC),

        ENTITIES_WITH_SPECIFIC_TAG(TAG, METRIC_ENTITIES_PER_TAG, AtlasGremlinQuery.ENTITIES_FOR_TAG_METRIC),
        ;

        private final String group;
        private final String name;
        private final String query;

        MetricQuery(String group, String name, AtlasGremlinQuery gremlinQuery) {
            this.group          = group;
            this.name           = name;
            this.query          = MetricsService.getQuery(group, name, gremlinQueryProvider.getQuery(gremlinQuery));
        }

        @Override
        public String toString() {
            return "MetricQuery{" + "group='" + group + '\'' +
                    ", name='" + name + '\'' +
                    ", query='" + query + '\'' +
                    '}';
        }
    }
}
