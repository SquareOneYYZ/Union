/*
 * Copyright 2024 Anton Tananaev (anton@traccar.org)
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

import org.traccar.api.BaseObjectResource;
import org.traccar.dtos.FeaturePermissionRequest;
import org.traccar.helper.LogAction;
import org.traccar.model.Feature;
import org.traccar.model.Permission;
import org.traccar.model.User;
import org.traccar.storage.StorageException;
import org.traccar.storage.query.Columns;
import org.traccar.storage.query.Condition;
import org.traccar.storage.query.Request;

import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.DELETE;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.PUT;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.WebApplicationException;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.UriInfo;
import java.util.Collection;
import java.util.stream.Collectors;

@Path("feature")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class FeatureResource extends BaseObjectResource<Feature> {

    @Context
    private UriInfo uriInfo;

    public FeatureResource() {
        super(Feature.class);
    }

    @GET
    public Collection<Feature> get() throws StorageException {
        try {
            long userId = getUserId();
            Collection<Feature> userMappedFeatures = storage.getObjects(Feature.class, new Request(
                    new Columns.All(),
                    new Condition.Permission(User.class, userId, Feature.class)));

            if (userMappedFeatures == null || userMappedFeatures.isEmpty()) {
                return storage.getObjects(Feature.class, new Request(new Columns.All()))
                        .stream()
                        .filter(Feature::getEnabled)
                        .collect(Collectors.toList());
            }
            long mappedFeatureId = userMappedFeatures.iterator().next().getId();
            Collection<Feature> allFeatures = storage.getObjects(Feature.class, new Request(new Columns.All()));
            return allFeatures.stream()
                    .filter(feature -> feature.getId() > mappedFeatureId)
                    .filter(Feature::getEnabled)
                    .collect(Collectors.toList());

        } catch (StorageException e) {
            throw new WebApplicationException("Database error while fetching features: "
                    + e.getMessage(), Response.Status.INTERNAL_SERVER_ERROR);
        } catch (Exception e) {
            throw new WebApplicationException("Unexpected error: "
                    + e.getMessage(), Response.Status.INTERNAL_SERVER_ERROR);
        }
    }


    @POST
    @Override
    public Response add(Feature entity) throws Exception {
        try {
        permissionsService.checkAdmin(getUserId());
        if (entity.getFeature() == null || entity.getFeature().isEmpty()) {
            throw new WebApplicationException("Feature name is required", Response.Status.BAD_REQUEST);
        }
            entity.setId(storage.addObject(entity, new Request(new Columns.Exclude("id"))));

            LogAction.create(getUserId(), entity);
            return Response.ok(entity).build();

        } catch (StorageException e) {
            throw new WebApplicationException("Database error: "
                    + e.getMessage(), Response.Status.INTERNAL_SERVER_ERROR);
        }
    }

    @Path("{id}")
    @PUT
    @Override
    public Response update(Feature entity) throws Exception {
        String idString = uriInfo.getPathParameters().getFirst("id");
        if (idString != null) {
            entity.setId(Long.parseLong(idString));
        }
        permissionsService.checkAdmin(getUserId());
        if (entity.getFeature() == null || entity.getFeature().isEmpty()) {
            throw new WebApplicationException("Feature name is required", Response.Status.BAD_REQUEST);
        }
        return super.update(entity);
    }

    @Path("{id}")
    @DELETE
    @Override
    public Response remove(@PathParam("id") long id) throws Exception {
        permissionsService.checkAdmin(getUserId());
        return super.remove(id);
    }


    @Path("permission")
    @POST
    public Response assignPermission(FeaturePermissionRequest request) throws Exception {
        try {
            if (request == null
                    || request.getUserId() == null
                    || request.getFeatureId() == null) {
                throw new WebApplicationException("userId and featureId are required",
                        Response.Status.BAD_REQUEST);
            }

            long userId = request.getUserId();
            long featureId = request.getFeatureId();
            Collection<Feature> existingMapped = storage.getObjects(Feature.class, new Request(
                    new Columns.All(),
                    new Condition.Permission(User.class, userId, Feature.class)));

            if (!existingMapped.isEmpty()) {
                Feature oldFeature = existingMapped.iterator().next();

                Permission oldPermission = new Permission(User.class, userId, Feature.class, oldFeature.getId());
                storage.removePermission(oldPermission);

                LogAction.unlink(getUserId(),
                        User.class, userId,
                        Feature.class, oldFeature.getId());
            }
            Permission newPermission = new Permission(User.class, userId, Feature.class, featureId);
            storage.addPermission(newPermission);

            LogAction.link(getUserId(),
                    User.class, userId,
                    Feature.class, featureId);

            return Response.ok().build();

        } catch (WebApplicationException e) {
            throw e;
        } catch (StorageException e) {
            throw new WebApplicationException("Database error: "
                    + e.getMessage(), Response.Status.INTERNAL_SERVER_ERROR);
        } catch (Exception e) {
            throw new WebApplicationException("Unexpected error: "
                    + e.getMessage(), Response.Status.INTERNAL_SERVER_ERROR);
        }
    }

}
