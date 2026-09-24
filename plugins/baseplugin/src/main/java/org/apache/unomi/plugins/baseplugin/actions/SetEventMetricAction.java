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

package org.apache.unomi.plugins.baseplugin.actions;

import org.apache.commons.beanutils.PropertyUtils;
import org.apache.unomi.api.Event;
import org.apache.unomi.api.Profile;
import org.apache.unomi.api.actions.Action;
import org.apache.unomi.api.actions.ActionExecutor;
import org.apache.unomi.api.conditions.Condition;
import org.apache.unomi.api.services.DefinitionsService;
import org.apache.unomi.api.services.EventService;
import org.apache.unomi.persistence.spi.PersistenceService;
import org.apache.unomi.persistence.spi.PropertyHelper;

import javax.xml.bind.DatatypeConverter;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class SetEventMetricAction implements ActionExecutor {
    private DefinitionsService definitionsService;
    private PersistenceService persistenceService;

    public void setDefinitionsService(DefinitionsService definitionsService) {
        this.definitionsService = definitionsService;
    }

    public void setPersistenceService(PersistenceService persistenceService) {
        this.persistenceService = persistenceService;
    }

    @Override
    public int execute(Action action, Event event) {
        final Condition metricCondition = (Condition) action.getParameterValues().get("pastEventMetricCondition");
        EventMetricValue value = calculateMetric(metricCondition, event);
        String key = (String) metricCondition.getParameter("generatedPropertyKey");
        boolean updated = updateProfileMetric(event, key, value);
        updated = updateDisplayProfileMetric(event, metricCondition, key, value) || updated;
        if (updated) {
            return EventService.PROFILE_UPDATED;
        }
        return EventService.NO_CHANGE;
    }

    private EventMetricValue calculateMetric(Condition metricCondition, Event event) {
        Condition query = getMatchingEventsCondition(metricCondition, event);
        long totalCount = persistenceService.queryCount(query, Event.ITEM_TYPE);

        String aggregationType = getAggregationType(metricCondition);
        String metricProperty = (String) metricCondition.getParameter("metricProperty");
        double sum = 0.0d;
        long metricCount = totalCount;

        if (!"count".equals(aggregationType)) {
            Map<String, Double> metrics = persistenceService.getSingleValuesMetrics(query, new String[]{"sum", "count"}, metricProperty, Event.ITEM_TYPE);
            sum = metrics.getOrDefault("_sum", 0.0d);
            metricCount = metrics.getOrDefault("_count", 0.0d).longValue();
        }

        if (inTimeRange(metricCondition, event)) {
            totalCount++;
            if (!"count".equals(aggregationType)) {
                Double currentValue = getNumericEventProperty(event, metricProperty);
                if (currentValue != null) {
                    sum += currentValue;
                    metricCount++;
                }
            } else {
                metricCount = totalCount;
            }
        }

        double avg = metricCount > 0 ? sum / metricCount : 0.0d;
        return new EventMetricValue(totalCount, sum, avg, metricCount);
    }

    private Condition getMatchingEventsCondition(Condition metricCondition, Event event) {
        Condition andCondition = new Condition(definitionsService.getConditionType("booleanCondition"));
        andCondition.setParameter("operator", "and");
        List<Condition> conditions = new ArrayList<>();

        Condition eventCondition = (Condition) metricCondition.getParameter("eventCondition");
        definitionsService.resolveConditionType(eventCondition);
        conditions.add(eventCondition);

        Condition profileFilter = new Condition(definitionsService.getConditionType("eventPropertyCondition"));
        profileFilter.setParameter("propertyName", "profileId");
        profileFilter.setParameter("comparisonOperator", "equals");
        profileFilter.setParameter("propertyValue", event.getProfileId());
        conditions.add(profileFilter);

        Condition eventIdFilter = new Condition(definitionsService.getConditionType("eventPropertyCondition"));
        eventIdFilter.setParameter("propertyName", "itemId");
        eventIdFilter.setParameter("comparisonOperator", "notEquals");
        eventIdFilter.setParameter("propertyValue", event.getItemId());
        conditions.add(eventIdFilter);

        String eventScope = (String) metricCondition.getParameter("eventScope");
        if (eventScope != null) {
            Condition scopeFilter = new Condition(definitionsService.getConditionType("eventPropertyCondition"));
            scopeFilter.setParameter("propertyName", "scope");
            scopeFilter.setParameter("comparisonOperator", "equals");
            scopeFilter.setParameter("propertyValue", eventScope);
            conditions.add(scopeFilter);
        }

        addTimeConditions(metricCondition, conditions);
        andCondition.setParameter("subConditions", conditions);
        return andCondition;
    }

    private void addTimeConditions(Condition metricCondition, List<Condition> conditions) {
        Integer numberOfDays = (Integer) metricCondition.getParameter("numberOfDays");
        String fromDate = (String) metricCondition.getParameter("fromDate");
        String toDate = (String) metricCondition.getParameter("toDate");

        if (numberOfDays != null) {
            Condition c = new Condition(definitionsService.getConditionType("eventPropertyCondition"));
            c.setParameter("propertyName", "timeStamp");
            c.setParameter("comparisonOperator", "greaterThan");
            c.setParameter("propertyValueDateExpr", "now-" + numberOfDays + "d");
            conditions.add(c);
        }
        if (fromDate != null) {
            Condition c = new Condition(definitionsService.getConditionType("eventPropertyCondition"));
            c.setParameter("propertyName", "timeStamp");
            c.setParameter("comparisonOperator", "greaterThanOrEqualTo");
            c.setParameter("propertyValueDate", fromDate);
            conditions.add(c);
        }
        if (toDate != null) {
            Condition c = new Condition(definitionsService.getConditionType("eventPropertyCondition"));
            c.setParameter("propertyName", "timeStamp");
            c.setParameter("comparisonOperator", "lessThanOrEqualTo");
            c.setParameter("propertyValueDate", toDate);
            conditions.add(c);
        }
    }

    private boolean updateProfileMetric(Event event, String key, EventMetricValue value) {
        List<Map<String, Object>> metrics = (List<Map<String, Object>>) event.getProfile().getSystemProperties().get("pastEventMetrics");
        if (metrics == null) {
            metrics = new ArrayList<>();
            event.getProfile().getSystemProperties().put("pastEventMetrics", metrics);
        }

        for (Map<String, Object> metric : metrics) {
            if (key.equals(metric.get("key"))) {
                if (sameMetric(metric, value)) {
                    return false;
                }
                setMetricValues(metric, value);
                return true;
            }
        }

        Map<String, Object> metric = new HashMap<>();
        metric.put("key", key);
        setMetricValues(metric, value);
        metrics.add(metric);
        return true;
    }

    private void setMetricValues(Map<String, Object> metric, EventMetricValue value) {
        metric.put("count", value.count);
        metric.put("sum", value.sum);
        metric.put("avg", value.avg);
        metric.put("metricCount", value.metricCount);
    }

    private boolean sameMetric(Map<String, Object> metric, EventMetricValue value) {
        return sameLong(metric.get("count"), value.count)
                && sameDouble(metric.get("sum"), value.sum)
                && sameDouble(metric.get("avg"), value.avg)
                && sameLong(metric.get("metricCount"), value.metricCount);
    }

    private boolean sameLong(Object actual, long expected) {
        return actual instanceof Number && ((Number) actual).longValue() == expected;
    }

    private boolean sameDouble(Object actual, double expected) {
        return actual instanceof Number && Double.compare(((Number) actual).doubleValue(), expected) == 0;
    }

    private boolean updateDisplayProfileMetric(Event event, Condition metricCondition, String key, EventMetricValue value) {
        String displayProperty = normalizeDisplayProfileProperty((String) metricCondition.getParameter("displayProfileProperty"));
        if (displayProperty == null) {
            return false;
        }

        Map<String, Object> displayValue = new HashMap<>();
        Object segmentId = metricCondition.getParameter("displayProfileSegmentId");
        if (segmentId != null) {
            displayValue.put("segmentId", segmentId);
        }
        displayValue.put("generatedPropertyKey", key);
        displayValue.put("aggregationType", getAggregationType(metricCondition));
        displayValue.put("count", value.count);
        displayValue.put("sum", value.sum);
        displayValue.put("avg", value.avg);
        displayValue.put("metricCount", value.metricCount);
        return setNestedProperty(getProfileProperties(event), displayProperty, displayValue);
    }

    private Map<String, Object> getProfileProperties(Event event) {
        Profile profile = event.getProfile();
        if (profile.getProperties() == null) {
            profile.setProperties(new HashMap<String, Object>());
        }
        return profile.getProperties();
    }

    private String normalizeDisplayProfileProperty(String displayProperty) {
        if (displayProperty == null) {
            return null;
        }
        String normalized = displayProperty.trim().replaceFirst("^properties\\.", "");
        if (normalized.isEmpty() || normalized.startsWith("systemProperties.")) {
            return null;
        }
        return normalized;
    }

    private boolean setNestedProperty(Map<String, Object> root, String path, Object value) {
        String[] parts = path.split("\\.");
        Map<String, Object> current = root;
        for (int i = 0; i < parts.length - 1; i++) {
            Object next = current.get(parts[i]);
            if (!(next instanceof Map)) {
                next = new HashMap<String, Object>();
                current.put(parts[i], next);
            }
            current = (Map<String, Object>) next;
        }
        Object previous = current.put(parts[parts.length - 1], value);
        return !value.equals(previous);
    }

    private boolean inTimeRange(Condition metricCondition, Event event) {
        LocalDateTime eventTime = LocalDateTime.ofInstant(event.getTimeStamp().toInstant(), ZoneId.of("UTC"));
        String eventScope = (String) metricCondition.getParameter("eventScope");
        if (eventScope != null && !eventScope.equals(event.getScope())) {
            return false;
        }
        Integer numberOfDays = (Integer) metricCondition.getParameter("numberOfDays");
        String fromDate = (String) metricCondition.getParameter("fromDate");
        String toDate = (String) metricCondition.getParameter("toDate");

        if (numberOfDays != null) {
            LocalDateTime now = LocalDateTime.now(ZoneId.of("UTC"));
            if (eventTime.isAfter(now) || Duration.between(eventTime, now).toDays() > numberOfDays) {
                return false;
            }
        }
        if (fromDate != null) {
            Calendar calendar = DatatypeConverter.parseDateTime(fromDate);
            if (LocalDateTime.ofInstant(calendar.toInstant(), ZoneId.of("UTC")).isAfter(eventTime)) {
                return false;
            }
        }
        if (toDate != null) {
            Calendar calendar = DatatypeConverter.parseDateTime(toDate);
            if (LocalDateTime.ofInstant(calendar.toInstant(), ZoneId.of("UTC")).isBefore(eventTime)) {
                return false;
            }
        }
        return true;
    }

    private String getAggregationType(Condition metricCondition) {
        String aggregationType = (String) metricCondition.getParameter("aggregationType");
        return aggregationType == null ? "count" : aggregationType;
    }

    private Double getNumericEventProperty(Event event, String metricProperty) {
        try {
            Object value = PropertyUtils.getNestedProperty(event, metricProperty);
            return value == null ? null : PropertyHelper.getDouble(value);
        } catch (Exception e) {
            return null;
        }
    }

    private static class EventMetricValue {
        private final long count;
        private final double sum;
        private final double avg;
        private final long metricCount;

        private EventMetricValue(long count, double sum, double avg, long metricCount) {
            this.count = count;
            this.sum = sum;
            this.avg = avg;
            this.metricCount = metricCount;
        }
    }
}
