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
import org.traccar.model.Feature;
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
        return storage.getObjects(Feature.class, new Request(
                new Columns.All(),
                new Condition.Permission(User.class, getUserId(), Feature.class)));
    }

    @POST
    @Override
    public Response add(Feature entity) throws Exception {
        permissionsService.checkAdmin(getUserId());
        if (entity.getFeature() == null || entity.getFeature().isEmpty()) {
            throw new WebApplicationException("Feature name is required", Response.Status.BAD_REQUEST);
        }
        return super.add(entity);
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

}
