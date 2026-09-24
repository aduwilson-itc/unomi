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
import org.apache.unomi.api.services.EventService;
import org.junit.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.assertEquals;

public class RelatedEntityStateActionTest {

    private final RelatedEntityStateAction executor = new RelatedEntityStateAction();

    @Test
    public void upsertsReplaysAndDeletesOneEntity() {
        Profile profile = new Profile("customer-1");
        Action action = action(2);

        Event create = event(profile, "vehicle-1", "upsert", "2026-09-17T10:00:00Z", Map.of("status", "active"));
        assertEquals(EventService.PROFILE_UPDATED, executor.execute(action, create));
        assertEquals(1, profile.getNestedProperty("products.fihankra.activeVehicleCount"));
        assertEquals(EventService.NO_CHANGE, executor.execute(action, create));

        Event update = event(profile, "vehicle-1", "upsert", "2026-09-17T11:00:00Z", Map.of("status", "inactive"));
        assertEquals(EventService.PROFILE_UPDATED, executor.execute(action, update));
        assertEquals("inactive", entities(profile).get(0).get("status"));

        Event stale = event(profile, "vehicle-1", "upsert", "2026-09-17T10:30:00Z", Map.of("status", "active"));
        stale.setProperty("activeVehicleCount", 9);
        assertEquals(EventService.NO_CHANGE, executor.execute(action, stale));
        assertEquals("inactive", entities(profile).get(0).get("status"));
        assertEquals(1, profile.getNestedProperty("products.fihankra.activeVehicleCount"));

        Event delete = event(profile, "vehicle-1", "delete", "2026-09-17T12:00:00Z", null);
        delete.setProperty("activeVehicleCount", 0);
        assertEquals(EventService.PROFILE_UPDATED, executor.execute(action, delete));
        assertEquals(0, entities(profile).size());
        assertEquals(0, profile.getNestedProperty("products.fihankra.activeVehicleCount"));
        assertEquals(EventService.NO_CHANGE, executor.execute(action, delete));

        Event staleAfterDelete = event(
                profile,
                "vehicle-1",
                "upsert",
                "2026-09-17T11:30:00Z",
                Map.of("status", "active")
        );
        assertEquals(EventService.NO_CHANGE, executor.execute(action, staleAfterDelete));
        assertEquals(0, entities(profile).size());
    }

    @Test(expected = IllegalArgumentException.class)
    public void enforcesCollectionLimit() {
        Profile profile = new Profile("customer-1");
        Action action = action(1);
        executor.execute(action, event(profile, "vehicle-1", "upsert", "2026-09-17T10:00:00Z", Map.of()));
        executor.execute(action, event(profile, "vehicle-2", "upsert", "2026-09-17T11:00:00Z", Map.of()));
    }

    private static Action action(int maxItems) {
        Action action = new Action();
        action.setParameter("collectionPropertyName", "products.fihankra.vehicles");
        action.setParameter("versionPropertyName", "itc.relatedEntityVersions.products.fihankra.vehicles");
        action.setParameter("maxItems", maxItems);
        action.setParameter("summaryProperties", Map.of(
                "activeVehicleCount", "products.fihankra.activeVehicleCount"
        ));
        return action;
    }

    private static Event event(Profile profile, String id, String operation, String updatedAt, Map<String, Object> state) {
        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("entityId", id);
        properties.put("entityOperation", operation);
        properties.put("sourceUpdatedAt", updatedAt);
        if (state != null) {
            properties.put("entityState", state);
        }
        properties.put("activeVehicleCount", 1);
        return new Event("vehicle_state_updated", null, profile, "fihankra", null, null, properties, null, true);
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> entities(Profile profile) {
        return (List<Map<String, Object>>) profile.getNestedProperty("products.fihankra.vehicles");
    }
}
