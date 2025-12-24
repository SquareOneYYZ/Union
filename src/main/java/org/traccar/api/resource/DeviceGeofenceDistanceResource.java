/*
 * Copyright 2025 Anton Tananaev (anton@traccar.org)
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

import org.traccar.api.BaseResource;
import org.traccar.model.Device;
import org.traccar.model.DeviceGeofenceSegment;
import org.traccar.storage.StorageException;
import org.traccar.storage.query.Columns;
import org.traccar.storage.query.Condition;
import org.traccar.storage.query.Order;
import org.traccar.storage.query.Request;

import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.WebApplicationException;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import java.util.Collection;
import java.util.Date;
import java.util.LinkedList;

@Path("devicegeofencedistances")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class DeviceGeofenceDistanceResource extends BaseResource {

    @GET
    public Collection<DeviceGeofenceSegment> get(
            @QueryParam("deviceId") long deviceId,
            @QueryParam("geofenceId") long geofenceId,
            @QueryParam("from") Date from,
            @QueryParam("to") Date to) throws StorageException {

        if (deviceId > 0) {
            permissionsService.checkPermission(Device.class, getUserId(), deviceId);
            return getSegments(deviceId, geofenceId, from, to);
        } else if (geofenceId > 0) {
            Collection<DeviceGeofenceSegment> segments = getSegments(0, geofenceId, from, to);
            segments.removeIf(segment -> {
                try {
                    permissionsService.checkPermission(Device.class, getUserId(), segment.getDeviceId());
                    return false;
                } catch (Exception e) {
                    return true;
                }
            });
            return segments;
        } else {
            throw new WebApplicationException(
                    Response.status(Response.Status.BAD_REQUEST)
                            .entity("Either deviceId or geofenceId must be provided")
                            .build());
        }
    }

    @Path("{deviceId}/{geofenceId}")
    @GET
    public Collection<DeviceGeofenceSegment> getByDeviceAndGeofence(
            @PathParam("deviceId") long deviceId,
            @PathParam("geofenceId") long geofenceId,
            @QueryParam("from") Date from,
            @QueryParam("to") Date to) throws StorageException {

        permissionsService.checkPermission(Device.class, getUserId(), deviceId);
        return getSegments(deviceId, geofenceId, from, to);
    }

    private Collection<DeviceGeofenceSegment> getSegments(
            long deviceId, long geofenceId, Date from, Date to) throws StorageException {

        var conditions = new LinkedList<Condition>();

        if (deviceId > 0) {
            conditions.add(new Condition.Equals("deviceId", deviceId));
        }
        if (geofenceId > 0) {
            conditions.add(new Condition.Equals("geofenceId", geofenceId));
        }
        if (from != null && to != null) {
            conditions.add(new Condition.Between("enterTime", "from", from, "to", to));
        }

        return storage.getObjects(DeviceGeofenceSegment.class,
                new Request(new Columns.All(), Condition.merge(conditions),
                        new Order("enterTime")));
    }
}
