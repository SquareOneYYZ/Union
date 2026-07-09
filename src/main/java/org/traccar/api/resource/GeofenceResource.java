/*
 * Copyright 2016 - 2017 Anton Tananaev (anton@traccar.org)
 *
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
package org.traccar.api.resource;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.PUT;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.traccar.api.ExtendedObjectResource;
import org.traccar.model.Geofence;
import org.traccar.storage.StorageException;
import org.traccar.storage.spaces.DigitalOceanSpacesService;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Collectors;

@Path("geofences")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class GeofenceResource extends ExtendedObjectResource<Geofence> {

    private static final Logger LOGGER = LoggerFactory.getLogger(GeofenceResource.class);
    private static final String SPACES_FOLDER = "geofence";

    @Inject
    private DigitalOceanSpacesService spacesService;

    @Inject
    private ObjectMapper objectMapper;

    public GeofenceResource() {
        super(Geofence.class, "name");
    }


    @GET
    @Path("list")
    public Response getIdNameList(
            @QueryParam("all") boolean all,
            @QueryParam("userId") long userId,
            @QueryParam("groupId") long groupId,
            @QueryParam("deviceId") long deviceId) throws StorageException {

        Collection<Geofence> geofences = get(all, userId, groupId, deviceId);

        List<Map<String, Object>> result = geofences.stream()
                .map(g -> Map.<String, Object>of("id", g.getId(), "name", g.getName()))
                .collect(Collectors.toList());

        return Response.ok(result).build();
    }


    @POST
    @Override
    public Response add(Geofence entity) throws Exception {
        Response response = super.add(entity);
        if (response.getStatus() == Response.Status.OK.getStatusCode()
                || response.getStatus() == Response.Status.CREATED.getStatusCode()) {
            CompletableFuture.runAsync(() -> uploadToSpaces(entity));
        }
        return response;
    }


    @PUT
    @Path("{id}")
    @Override
    public Response update(Geofence entity) throws Exception {
        Response response = super.update(entity);
        if (response.getStatus() == Response.Status.OK.getStatusCode()
                || response.getStatus() == Response.Status.CREATED.getStatusCode()) {
            CompletableFuture.runAsync(() -> uploadToSpaces(entity));
        }
        return response;
    }


    private void uploadToSpaces(Geofence geofence) {
        if (!spacesService.isAvailable()) {
            LOGGER.warn("DigitalOcean Spaces is not configured. Skipping upload for geofence {}", geofence.getId());
            return;
        }
        try {
            String json = objectMapper.writeValueAsString(geofence);
            spacesService.put(SPACES_FOLDER, geofence.getId(), json);
        } catch (Exception e) {
            LOGGER.warn("Failed to upload geofence {} to DigitalOcean Spaces", geofence.getId(), e);
        }
    }

}
