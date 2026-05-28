/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.unomi.persistence.elasticsearch.querybuilders.advanced;

import co.elastic.clients.elasticsearch._types.query_dsl.Query;
import org.apache.unomi.api.Profile;
import org.apache.unomi.api.conditions.Condition;
import org.apache.unomi.api.services.DefinitionsService;
import org.apache.unomi.api.services.SegmentService;
import org.apache.unomi.persistence.elasticsearch.ConditionESQueryBuilder;
import org.apache.unomi.persistence.elasticsearch.ConditionESQueryBuilderDispatcher;
import org.apache.unomi.persistence.spi.PersistenceService;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public class PastEventMetricConditionESQueryBuilder implements ConditionESQueryBuilder {
    private DefinitionsService definitionsService;
    private PersistenceService persistenceService;
    private SegmentService segmentService;

    public void setDefinitionsService(DefinitionsService definitionsService) {
        this.definitionsService = definitionsService;
    }

    public void setPersistenceService(PersistenceService persistenceService) {
        this.persistenceService = persistenceService;
    }

    public void setSegmentService(SegmentService segmentService) {
        this.segmentService = segmentService;
    }

    @Override
    public Query buildQuery(Condition condition, Map<String, Object> context, ConditionESQueryBuilderDispatcher dispatcher) {
        String generatedPropertyKey = (String) condition.getParameter("generatedPropertyKey");
        if (generatedPropertyKey != null && generatedPropertyKey.equals(segmentService.getGeneratedPropertyKey((Condition) condition.getParameter("eventCondition"), condition))) {
            return dispatcher.buildFilter(getProfileMetricCondition(condition), context);
        }
        return Query.of(q -> q.matchNone(m -> m));
    }

    @Override
    public long count(Condition condition, Map<String, Object> context, ConditionESQueryBuilderDispatcher dispatcher) {
        String generatedPropertyKey = (String) condition.getParameter("generatedPropertyKey");
        if (generatedPropertyKey != null && generatedPropertyKey.equals(segmentService.getGeneratedPropertyKey((Condition) condition.getParameter("eventCondition"), condition))) {
            return persistenceService.queryCount(getProfileMetricCondition(condition), Profile.ITEM_TYPE);
        }
        return 0;
    }

    private Condition getProfileMetricCondition(Condition condition) {
        String generatedPropertyKey = (String) condition.getParameter("generatedPropertyKey");
        if (isNegativeOperator((String) condition.getParameter("operator"))) {
            return createMetricNotOccurredCondition(generatedPropertyKey);
        }
        return createMetricOccurredCondition(generatedPropertyKey, getAggregationType(condition), getMinimumValue(condition), getMaximumValue(condition));
    }

    private Condition createMetricOccurredCondition(String generatedPropertyKey, String aggregationType, double minimumValue, double maximumValue) {
        Condition metricValueCondition = new Condition(definitionsService.getConditionType("profilePropertyCondition"));
        metricValueCondition.setParameter("propertyName", "systemProperties.pastEventMetrics." + aggregationType);
        metricValueCondition.setParameter("comparisonOperator", "between");
        metricValueCondition.setParameter("propertyValuesDouble", List.of(minimumValue, maximumValue));

        Condition keyCondition = new Condition(definitionsService.getConditionType("profilePropertyCondition"));
        keyCondition.setParameter("propertyName", "systemProperties.pastEventMetrics.key");
        keyCondition.setParameter("comparisonOperator", "equals");
        keyCondition.setParameter("propertyValue", generatedPropertyKey);

        Condition andCondition = new Condition(definitionsService.getConditionType("booleanCondition"));
        andCondition.setParameter("operator", "and");
        andCondition.setParameter("subConditions", List.of(metricValueCondition, keyCondition));

        Condition nestedCondition = new Condition(definitionsService.getConditionType("nestedCondition"));
        nestedCondition.setParameter("path", "systemProperties.pastEventMetrics");
        nestedCondition.setParameter("subCondition", andCondition);
        return nestedCondition;
    }

    private Condition createMetricNotOccurredCondition(String generatedPropertyKey) {
        Condition keyCondition = new Condition(definitionsService.getConditionType("profilePropertyCondition"));
        keyCondition.setParameter("propertyName", "systemProperties.pastEventMetrics.key");
        keyCondition.setParameter("comparisonOperator", "equals");
        keyCondition.setParameter("propertyValue", generatedPropertyKey);

        Condition notCondition = new Condition(definitionsService.getConditionType("notCondition"));
        notCondition.setParameter("subCondition", keyCondition);

        Condition zeroCondition = createMetricOccurredCondition(generatedPropertyKey, "count", 0.0d, 0.0d);

        List<Condition> subConditions = new ArrayList<>();
        subConditions.add(notCondition);
        subConditions.add(zeroCondition);

        Condition orCondition = new Condition(definitionsService.getConditionType("booleanCondition"));
        orCondition.setParameter("operator", "or");
        orCondition.setParameter("subConditions", subConditions);
        return orCondition;
    }

    private String getAggregationType(Condition condition) {
        String aggregationType = (String) condition.getParameter("aggregationType");
        return aggregationType == null ? "count" : aggregationType;
    }

    private double getMinimumValue(Condition condition) {
        Number minimum = (Number) condition.getParameter("minimumValue");
        return minimum == null ? 0.0d : minimum.doubleValue();
    }

    private double getMaximumValue(Condition condition) {
        Number maximum = (Number) condition.getParameter("maximumValue");
        return maximum == null ? Double.MAX_VALUE : maximum.doubleValue();
    }

    private boolean isNegativeOperator(String operator) {
        return "eventsNotOccurred".equals(operator) || "notOccurred".equals(operator);
    }
}
