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

package org.apache.unomi.plugins.baseplugin.conditions;

import org.apache.unomi.api.Item;
import org.apache.unomi.api.Profile;
import org.apache.unomi.api.conditions.Condition;
import org.apache.unomi.persistence.spi.conditions.evaluator.ConditionEvaluator;
import org.apache.unomi.persistence.spi.conditions.evaluator.ConditionEvaluatorDispatcher;

import java.util.List;
import java.util.Map;

public class PastEventMetricConditionEvaluator implements ConditionEvaluator {

    @Override
    public boolean eval(Condition condition, Item item, Map<String, Object> context, ConditionEvaluatorDispatcher dispatcher) {
        double metricValue = getMetricValue(condition, item);
        if (isNegativeOperator((String) condition.getParameter("operator"))) {
            return metricValue == 0.0d;
        }

        Number minimum = (Number) condition.getParameter("minimumValue");
        Number maximum = (Number) condition.getParameter("maximumValue");
        double minimumValue = minimum == null ? 0.0d : minimum.doubleValue();
        double maximumValue = maximum == null ? Double.MAX_VALUE : maximum.doubleValue();
        return metricValue > 0.0d && metricValue >= minimumValue && metricValue <= maximumValue;
    }

    private double getMetricValue(Condition condition, Item item) {
        if (!(item instanceof Profile) || condition.getParameter("generatedPropertyKey") == null) {
            return 0.0d;
        }

        Profile profile = (Profile) item;
        String key = (String) condition.getParameter("generatedPropertyKey");
        String aggregationType = getAggregationType(condition);
        List<Map<String, Object>> metrics = (List<Map<String, Object>>) profile.getSystemProperties().get("pastEventMetrics");
        if (metrics == null) {
            return 0.0d;
        }

        return metrics.stream()
                .filter(metric -> key.equals(metric.get("key")))
                .findFirst()
                .map(metric -> metric.get(aggregationType))
                .filter(Number.class::isInstance)
                .map(Number.class::cast)
                .map(Number::doubleValue)
                .orElse(0.0d);
    }

    private String getAggregationType(Condition condition) {
        String aggregationType = (String) condition.getParameter("aggregationType");
        return aggregationType == null ? "count" : aggregationType;
    }

    private boolean isNegativeOperator(String operator) {
        return "eventsNotOccurred".equals(operator) || "notOccurred".equals(operator);
    }
}
