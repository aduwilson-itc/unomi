/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements. See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0.
 */
package org.apache.unomi.plugins.baseplugin.actions;

import org.apache.unomi.api.Event;
import org.apache.unomi.api.Profile;
import org.apache.unomi.api.actions.Action;
import org.apache.unomi.api.actions.ActionExecutor;
import org.apache.unomi.api.services.EventService;
import org.apache.unomi.persistence.spi.PropertyHelper;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Maintains a bounded collection of related entity state on a profile.
 */
public class RelatedEntityStateAction implements ActionExecutor {

    static final String UPSERT = "upsert";
    static final String DELETE = "delete";

    @Override
    public int execute(Action action, Event event) {
        Profile profile = event.getProfile();
        if (profile == null) {
            return EventService.NO_CHANGE;
        }

        Map<String, Object> parameters = action.getParameterValues();
        String collectionPropertyName = requiredString(parameters, "collectionPropertyName");
        String entityIdPropertyName = stringValue(parameters, "entityIdPropertyName", "entityId");
        String operationPropertyName = stringValue(parameters, "operationPropertyName", "entityOperation");
        String entityStatePropertyName = stringValue(parameters, "entityStatePropertyName", "entityState");
        String sourceUpdatedAtPropertyName = stringValue(parameters, "sourceUpdatedAtPropertyName", "sourceUpdatedAt");
        String versionPropertyName = stringValue(
                parameters,
                "versionPropertyName",
                "itc.relatedEntityVersions." + collectionPropertyName
        );
        Map<String, Object> summaryProperties = mapValue(parameters.get("summaryProperties"));
        int maxItems = integerValue(parameters.get("maxItems"), 250);
        if (maxItems < 1) {
            throw new IllegalArgumentException("maxItems must be greater than zero");
        }

        String entityId = requiredEventString(event, entityIdPropertyName);
        String operation = requiredEventString(event, operationPropertyName).toLowerCase();
        if (!UPSERT.equals(operation) && !DELETE.equals(operation)) {
            throw new IllegalArgumentException("entityOperation must be upsert or delete");
        }

        String incomingUpdatedAt = requiredEventString(event, sourceUpdatedAtPropertyName);
        List<Map<String, Object>> entities = collection(profile, collectionPropertyName);
        Map<String, Object> versions = versionMap(profile, versionPropertyName);
        int existingIndex = findEntity(entities, entityId);
        Map<String, Object> existing = existingIndex >= 0 ? entities.get(existingIndex) : null;
        String existingUpdatedAt = stringValue(existing, sourceUpdatedAtPropertyName, null);
        if (existingUpdatedAt == null) {
            existingUpdatedAt = stringValue(versions, entityId, null);
        }
        if (isStale(incomingUpdatedAt, existingUpdatedAt)) {
            return EventService.NO_CHANGE;
        }

        boolean collectionChanged = false;
        if (DELETE.equals(operation) && existingIndex >= 0) {
            entities.remove(existingIndex);
            collectionChanged = true;
        } else {
            if (UPSERT.equals(operation)) {
                Object rawState = event.getProperty(entityStatePropertyName);
                if (!(rawState instanceof Map)) {
                    throw new IllegalArgumentException("entityState must be an object for upsert operations");
                }
                if (existingIndex < 0 && entities.size() >= maxItems) {
                    throw new IllegalArgumentException("Related entity collection exceeds maxItems=" + maxItems);
                }
                Map<String, Object> replacement = new LinkedHashMap<>((Map<String, Object>) rawState);
                replacement.put("entityId", entityId);
                replacement.put(sourceUpdatedAtPropertyName, incomingUpdatedAt);
                if (existingIndex >= 0) {
                    if (!Objects.equals(existing, replacement)) {
                        entities.set(existingIndex, replacement);
                        collectionChanged = true;
                    }
                } else {
                    entities.add(replacement);
                    collectionChanged = true;
                }
            }
        }

        boolean changed = false;
        if (collectionChanged) {
            changed = PropertyHelper.setProperty(
                    profile,
                    "properties." + collectionPropertyName,
                    entities,
                    "alwaysSet"
            );
        }
        versions.remove(entityId);
        versions.put(entityId, incomingUpdatedAt);
        while (versions.size() > maxItems) {
            versions.remove(versions.keySet().iterator().next());
        }
        changed |= PropertyHelper.setProperty(
                profile,
                "properties." + versionPropertyName,
                versions,
                "alwaysSet"
        );
        for (Map.Entry<String, Object> summary : summaryProperties.entrySet()) {
            Object eventValue = event.getProperty(summary.getKey());
            changed |= PropertyHelper.setProperty(
                    profile,
                    "properties." + String.valueOf(summary.getValue()),
                    eventValue,
                    "alwaysSet"
            );
        }
        return changed ? EventService.PROFILE_UPDATED : EventService.NO_CHANGE;
    }

    private static List<Map<String, Object>> collection(Profile profile, String propertyName) {
        Object raw = profile.getNestedProperty(propertyName);
        List<Map<String, Object>> result = new ArrayList<>();
        if (raw == null) {
            return result;
        }
        if (!(raw instanceof List)) {
            throw new IllegalArgumentException("Related entity profile property must be a list: " + propertyName);
        }
        for (Object item : (List<?>) raw) {
            if (!(item instanceof Map)) {
                throw new IllegalArgumentException("Related entity collection items must be objects: " + propertyName);
            }
            result.add(new LinkedHashMap<>((Map<String, Object>) item));
        }
        return result;
    }

    private static int findEntity(List<Map<String, Object>> entities, String entityId) {
        for (int index = 0; index < entities.size(); index++) {
            if (entityId.equals(String.valueOf(entities.get(index).get("entityId")))) {
                return index;
            }
        }
        return -1;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> versionMap(Profile profile, String propertyName) {
        Object raw = profile.getNestedProperty(propertyName);
        if (raw == null) {
            return new LinkedHashMap<>();
        }
        if (!(raw instanceof Map)) {
            throw new IllegalArgumentException("Related entity version property must be an object: " + propertyName);
        }
        return new LinkedHashMap<>((Map<String, Object>) raw);
    }

    private static boolean isStale(String incoming, String existing) {
        if (incoming == null || existing == null) {
            return false;
        }
        return !parseInstant(incoming).isAfter(parseInstant(existing));
    }

    private static Instant parseInstant(String value) {
        try {
            return Instant.parse(value);
        } catch (DateTimeParseException ignored) {
            try {
                return OffsetDateTime.parse(value).toInstant();
            } catch (DateTimeParseException exception) {
                throw new IllegalArgumentException("sourceUpdatedAt must be an ISO-8601 date-time: " + value, exception);
            }
        }
    }

    private static String requiredString(Map<String, Object> values, String key) {
        String value = stringValue(values, key, null);
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(key + " is required");
        }
        return value;
    }

    private static String requiredEventString(Event event, String propertyName) {
        String value = optionalEventString(event, propertyName);
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(propertyName + " is required");
        }
        return value;
    }

    private static String optionalEventString(Event event, String propertyName) {
        Object value = event.getProperty(propertyName);
        return value == null ? null : String.valueOf(value);
    }

    private static String stringValue(Map<String, Object> values, String key, String defaultValue) {
        if (values == null) {
            return defaultValue;
        }
        Object value = values.get(key);
        return value == null ? defaultValue : String.valueOf(value);
    }

    private static int integerValue(Object value, int defaultValue) {
        if (value == null) {
            return defaultValue;
        }
        if (value instanceof Number) {
            return ((Number) value).intValue();
        }
        try {
            return Integer.parseInt(String.valueOf(value));
        } catch (NumberFormatException exception) {
            throw new IllegalArgumentException("maxItems must be an integer", exception);
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> mapValue(Object value) {
        if (value == null) {
            return Map.of();
        }
        if (!(value instanceof Map)) {
            throw new IllegalArgumentException("summaryProperties must be an object");
        }
        return (Map<String, Object>) value;
    }
}
