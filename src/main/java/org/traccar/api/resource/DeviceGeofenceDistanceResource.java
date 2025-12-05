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
import org.traccar.api.service.DeviceGeofenceDistanceService;
import org.traccar.model.Device;
import org.traccar.model.DeviceGeofenceDistance;
import org.traccar.model.DeviceGeofenceDistanceDto;
import org.traccar.storage.StorageException;
import org.traccar.storage.query.Columns;
import org.traccar.storage.query.Condition;
import org.traccar.storage.query.Request;

import jakarta.inject.Inject;
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
import java.util.LinkedList;

@Path("devicegeofencedistances")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class DeviceGeofenceDistanceResource extends BaseResource {

    @Inject
    private DeviceGeofenceDistanceService distanceService;

    @GET
    public Collection<DeviceGeofenceDistanceDto> get(
            @QueryParam("deviceId") long deviceId,
            @QueryParam("geofenceId") long geofenceId) throws StorageException {
        
        if (deviceId > 0) {
            permissionsService.checkPermission(Device.class, getUserId(), deviceId);
            
            Collection<DeviceGeofenceDistance> records;
            if (geofenceId > 0) {
                var conditions = new LinkedList<Condition>();
                conditions.add(new Condition.Equals("deviceId", deviceId));
                conditions.add(new Condition.Equals("geofenceId", geofenceId));
                records = storage.getObjects(DeviceGeofenceDistance.class, new Request(
                        new Columns.All(), Condition.merge(conditions)));
            } else {
                records = storage.getObjects(DeviceGeofenceDistance.class, new Request(
                        new Columns.All(), new Condition.Equals("deviceId", deviceId)));
            }
            
            return distanceService.calculateDistances(records);
        } else if (geofenceId > 0) {
            Collection<DeviceGeofenceDistance> records = storage.getObjects(DeviceGeofenceDistance.class, new Request(
                    new Columns.All(), new Condition.Equals("geofenceId", geofenceId)));
            
            records.removeIf(distance -> {
                try {
                    permissionsService.checkPermission(Device.class, getUserId(), distance.getDeviceId());
                    return false;
                } catch (Exception e) {
                    return true;
                }
            });
            
            return distanceService.calculateDistances(records);
        } else {
            throw new WebApplicationException(
                    Response.status(Response.Status.BAD_REQUEST)
                            .entity("Either deviceId or geofenceId must be provided")
                            .build());
        }
    }

    @Path("{id}")
    @GET
    public DeviceGeofenceDistanceDto getSingle(@PathParam("id") long id) throws StorageException {
        DeviceGeofenceDistance distance = storage.getObject(DeviceGeofenceDistance.class, new Request(
                new Columns.All(), new Condition.Equals("id", id)));
        
        if (distance == null) {
            throw new WebApplicationException(Response.status(Response.Status.NOT_FOUND).build());
        }
        
        permissionsService.checkPermission(Device.class, getUserId(), distance.getDeviceId());
        
        var conditions = new LinkedList<Condition>();
        conditions.add(new Condition.Equals("deviceId", distance.getDeviceId()));
        conditions.add(new Condition.Equals("geofenceId", distance.getGeofenceId()));
        Collection<DeviceGeofenceDistance> relatedRecords = storage.getObjects(
                DeviceGeofenceDistance.class, 
                new Request(new Columns.All(), Condition.merge(conditions)));
        
        return distanceService.calculateDistanceForSingle(distance, relatedRecords);
    }

}
