/*
 * Copyright 2026 Anton Tananaev (anton@traccar.org)
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

import org.traccar.api.ExtendedObjectResource;
import org.traccar.model.Device;
import org.traccar.model.Group;
import org.traccar.model.Region;
import org.traccar.model.User;
import org.traccar.storage.StorageException;
import org.traccar.storage.query.Columns;
import org.traccar.storage.query.Condition;
import org.traccar.storage.query.Order;
import org.traccar.storage.query.Request;

import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;
import java.util.Collection;
import java.util.LinkedList;

@Path("regions")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class RegionResource extends ExtendedObjectResource<Region> {

    public RegionResource() {
        super(Region.class, "name");
    }

    @GET
    @Path("filter")
    public Collection<Region> get(
            @QueryParam("type") String type) throws StorageException {

        var conditions = new LinkedList<Condition>();
        if (type != null && !type.isEmpty()) {
            conditions.add(new Condition.Equals("type", type));
        }

        return storage.getObjects(Region.class, new Request(
                new Columns.All(), Condition.merge(conditions), new Order("id")));
    }

}
