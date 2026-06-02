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
package org.apache.unomi.rest.endpoints;

import org.apache.cxf.rs.security.cors.CrossOriginResourceSharing;
import org.apache.unomi.api.Event;
import org.apache.unomi.api.Profile;
import org.apache.unomi.api.conditions.Condition;
import org.apache.unomi.api.services.DefinitionsService;
import org.apache.unomi.api.services.ProfileService;
import org.apache.unomi.persistence.spi.PersistenceService;
import org.apache.unomi.persistence.spi.PropertyHelper;
import org.osgi.service.component.annotations.Component;
import org.osgi.service.component.annotations.Reference;

import javax.ws.rs.POST;
import javax.ws.rs.Path;
import javax.ws.rs.Produces;
import javax.ws.rs.core.MediaType;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Produces(MediaType.APPLICATION_JSON)
@CrossOriginResourceSharing(
        allowAllOrigins = true,
        allowCredentials = true
)
@Path("/ingestion/rollback")
@Component(service = IngestionRollbackEndPoint.class, property = "osgi.jaxrs.resource=true")
public class IngestionRollbackEndPoint {

    @Reference
    private DefinitionsService definitionsService;

    @Reference
    private PersistenceService persistenceService;

    @Reference
    private ProfileService profileService;

    @POST
    @Path("/run")
    public Map<String, Object> rollbackRun(Map<String, Object> request) {
        String runId = (String) request.get("runId");
        if (runId == null || runId.isBlank()) {
            throw new IllegalArgumentException("runId is required");
        }

        List<Map<String, Object>> snapshots = (List<Map<String, Object>>) request.getOrDefault("snapshots", new ArrayList<>());
        boolean deleteEvents = !Boolean.FALSE.equals(request.get("deleteEvents"));
        boolean eventsDeleted = deleteEvents && persistenceService.removeByQuery(runEventCondition(runId), Event.class);
        List<String> profileIdsToDelete = (List<String>) request.getOrDefault("profileIdsToDelete", new ArrayList<>());
        int deletedProfiles = deleteProfiles(profileIdsToDelete);
        int restoredProfiles = restoreProfiles(snapshots, new HashSet<>(profileIdsToDelete));

        Map<String, Object> response = new HashMap<>();
        response.put("status", "completed");
        response.put("runId", runId);
        response.put("eventsDeleted", eventsDeleted);
        response.put("profilesDeleted", deletedProfiles);
        response.put("snapshotsRestored", snapshots.size());
        response.put("profilesRestored", restoredProfiles);
        response.put("recalculation", "not_available");
        return response;
    }

    private Condition runEventCondition(String runId) {
        Condition condition = new Condition(definitionsService.getConditionType("eventPropertyCondition"));
        condition.setParameter("propertyName", "properties.runId");
        condition.setParameter("comparisonOperator", "equals");
        condition.setParameter("propertyValue", runId);
        return condition;
    }

    private int deleteProfiles(List<String> profileIds) {
        int deletedProfiles = 0;
        for (String profileId : profileIds) {
            if (profileId == null || profileId.isBlank()) {
                continue;
            }
            Profile profile = profileService.load(profileId);
            if (profile == null) {
                continue;
            }
            profileService.delete(profileId, false);
            deletedProfiles++;
        }
        return deletedProfiles;
    }

    private int restoreProfiles(List<Map<String, Object>> snapshots, Set<String> deletedProfileIds) {
        int restoredProfiles = 0;
        Map<String, List<Map<String, Object>>> snapshotsByProfile = new HashMap<>();
        for (Map<String, Object> snapshot : snapshots) {
            snapshotsByProfile.computeIfAbsent((String) snapshot.get("profileId"), key -> new ArrayList<>()).add(snapshot);
        }

        for (Map.Entry<String, List<Map<String, Object>>> entry : snapshotsByProfile.entrySet()) {
            if (deletedProfileIds.contains(entry.getKey())) {
                continue;
            }
            Profile profile = profileService.load(entry.getKey());
            if (profile == null) {
                continue;
            }
            boolean changed = false;
            for (Map<String, Object> snapshot : entry.getValue()) {
                if ("__profile__".equals(snapshot.get("propertyPath"))) {
                    continue;
                }
                String propertyPath = "properties." + snapshot.get("propertyPath");
                if (Boolean.TRUE.equals(snapshot.get("existed"))) {
                    changed |= PropertyHelper.setProperty(profile, propertyPath, snapshot.get("value"), "alwaysSet");
                } else {
                    changed |= PropertyHelper.setProperty(profile, propertyPath, "", "remove");
                }
            }
            if (changed) {
                profileService.save(profile);
                restoredProfiles++;
            }
        }
        return restoredProfiles;
    }
}
