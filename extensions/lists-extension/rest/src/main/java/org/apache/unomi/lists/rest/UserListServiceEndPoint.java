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

package org.apache.unomi.lists.rest;

import org.apache.cxf.rs.security.cors.CrossOriginResourceSharing;
import org.apache.unomi.api.Metadata;
import org.apache.unomi.api.PartialList;
import org.apache.unomi.api.Event;
import org.apache.unomi.api.Profile;
import org.apache.unomi.api.query.Query;
import org.apache.unomi.api.services.EventService;
import org.apache.unomi.api.services.ProfileService;
import org.apache.unomi.lists.UserList;
import org.apache.unomi.services.UserListService;
import org.osgi.service.component.annotations.Component;
import org.osgi.service.component.annotations.Reference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.ws.rs.*;
import javax.ws.rs.core.MediaType;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;


/**
 * @author Christophe Laprun
 */
@Produces(MediaType.APPLICATION_JSON)
@CrossOriginResourceSharing(
        allowAllOrigins = true,
        allowCredentials = true
)
@Path("/lists")
@Component(service=UserListServiceEndPoint.class,property = "osgi.jaxrs.resource=true")
public class UserListServiceEndPoint {

    private static final Logger LOGGER = LoggerFactory.getLogger(UserListServiceEndPoint.class.getName());

    @Reference
    private UserListService userListService;

    @Reference
    private ProfileService profileService;

    @Reference
    private EventService eventService;

    public UserListServiceEndPoint() {
        LOGGER.info("Initializing user list service endpoint...");
    }

    public void setUserListService(UserListService userListService) {
        this.userListService = userListService;
    }

    @GET
    @Path("/")
    public PartialList<Metadata> getListMetadatas() {
        return userListService.getListMetadatas(0, 50, null);
    }

    @POST
    @Path("/query")
    public PartialList<Metadata> getListMetadatas(Query query) {
        return userListService.getListMetadatas(query);
    }

    @GET
    @Path("/{listId}")
    public UserList load(@PathParam("listId") String listId) {
        return userListService.load(listId);
    }

    @POST
    @Path("/")
    public void save(UserList list) {
        userListService.save(list);
    }

    @DELETE
    @Path("/{listId}")
    public void delete(@PathParam("listId") String listId) {
        userListService.delete(listId);
    }

    @POST
    @Path("/{listId}/members")
    @Consumes(MediaType.APPLICATION_JSON)
    public Map<String, Object> updateMembers(@PathParam("listId") String listId, MembershipChanges changes) {
        if (userListService.load(listId) == null) {
            throw new NotFoundException("List not found: " + listId);
        }
        Set<String> additions = normalized(changes == null ? null : changes.getAddProfileIds());
        Set<String> removals = normalized(changes == null ? null : changes.getRemoveProfileIds());
        Set<String> overlap = new LinkedHashSet<>(additions);
        overlap.retainAll(removals);
        if (!overlap.isEmpty()) {
            throw new BadRequestException("A profile cannot be added and removed in the same request");
        }

        Map<String, Profile> profiles = new LinkedHashMap<>();
        Set<String> missing = new LinkedHashSet<>();
        Set<String> requested = new LinkedHashSet<>(additions);
        requested.addAll(removals);
        for (String profileId : requested) {
            Profile profile = profileService.load(profileId);
            if (profile == null) {
                missing.add(profileId);
            } else {
                profiles.put(profileId, profile);
            }
        }
        if (!missing.isEmpty()) {
            throw new NotFoundException("Profiles not found: " + String.join(", ", missing));
        }

        int added = 0;
        int removed = 0;
        for (Map.Entry<String, Profile> entry : profiles.entrySet()) {
            Profile profile = entry.getValue();
            List<String> memberships = profile.getSystemProperties().get("lists") instanceof List
                    ? new ArrayList<>((List<String>) profile.getSystemProperties().get("lists"))
                    : new ArrayList<>();
            boolean changed = false;
            if (additions.contains(entry.getKey()) && !memberships.contains(listId)) {
                memberships.add(listId);
                added++;
                changed = true;
            }
            if (removals.contains(entry.getKey()) && memberships.remove(listId)) {
                removed++;
                changed = true;
            }
            if (changed) {
                profile.getSystemProperties().put("lists", memberships);
                Event profileUpdated = new Event("profileUpdated", null, profile, null, null, profile, new Date());
                profileUpdated.setPersistent(false);
                eventService.send(profileUpdated);
                profileService.save(profileUpdated.getProfile());
            }
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("listId", listId);
        result.put("added", added);
        result.put("removed", removed);
        result.put("unchanged", requested.size() - added - removed);
        return result;
    }

    private Set<String> normalized(List<String> values) {
        if (values == null) {
            return Collections.emptySet();
        }
        Set<String> normalized = new LinkedHashSet<>();
        for (String value : values) {
            if (value != null && !value.trim().isEmpty()) {
                normalized.add(value.trim());
            }
        }
        return normalized;
    }

    public static class MembershipChanges {
        private List<String> addProfileIds = Collections.emptyList();
        private List<String> removeProfileIds = Collections.emptyList();

        public List<String> getAddProfileIds() {
            return addProfileIds;
        }

        public void setAddProfileIds(List<String> addProfileIds) {
            this.addProfileIds = addProfileIds;
        }

        public List<String> getRemoveProfileIds() {
            return removeProfileIds;
        }

        public void setRemoveProfileIds(List<String> removeProfileIds) {
            this.removeProfileIds = removeProfileIds;
        }
    }
}
