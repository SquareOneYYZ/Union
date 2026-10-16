/*
 * Copyright 2016 - 2021 Anton Tananaev (anton@traccar.org)
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

import jakarta.ws.rs.*;
import org.traccar.api.BaseResource;
import org.traccar.model.Device;
import org.traccar.model.Event;
import org.traccar.model.Position;
import org.traccar.storage.StorageException;
import org.traccar.storage.query.Columns;
import org.traccar.storage.query.Condition;
import org.traccar.storage.query.Order;
import org.traccar.storage.query.Request;

import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

import java.util.*;

@Path("events")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class EventResource extends BaseResource {

    @Path("{id}")
    @GET
    public Event get(@PathParam("id") long id) throws StorageException {
        Event event = storage.getObject(Event.class, new Request(
                new Columns.All(), new Condition.Equals("id", id)));
        if (event == null) {
            throw new WebApplicationException(Response.status(Response.Status.NOT_FOUND).build());
        }
        permissionsService.checkPermission(Device.class, getUserId(), event.getDeviceId());
        return event;
    }



    @Path("search")
    @GET
    public Response search(
            @QueryParam("deviceId")  List<Long> deviceIds,
            @QueryParam("from")      Date         from,
            @QueryParam("to")        Date         to,
            @QueryParam("type")      List<String> types,
            @QueryParam("alarm")     List<String> alarms,
            @QueryParam("keyword")   String       keyword,
            @QueryParam("sortBy")    @DefaultValue("eventTime") String sortBy,
            @QueryParam("sortOrder") @DefaultValue("asc")       String sortOrder,
            @QueryParam("page")      @DefaultValue("1")         int    page,
            @QueryParam("pageSize")  @DefaultValue("100")       int    pageSize)
            throws StorageException {

        if (from == null || to == null) {
            return Response.status(Response.Status.BAD_REQUEST)
                    .entity("'from' and 'to' date parameters are required").build();
        }
        if (deviceIds == null || deviceIds.isEmpty()) {
            return Response.status(Response.Status.BAD_REQUEST)
                    .entity("'deviceId' parameter is required").build();
        }
        if (from.after(to)) {
            return Response.status(Response.Status.BAD_REQUEST)
                    .entity("'from' must be before 'to'").build();
        }
        if (page < 1) {
            return Response.status(Response.Status.BAD_REQUEST)
                    .entity("'page' must be >= 1").build();
        }
        if (pageSize < 1 || pageSize > 500) {
            return Response.status(Response.Status.BAD_REQUEST)
                    .entity("'pageSize' must be between 1 and 500").build();
        }
        if (!sortBy.equals("eventTime") && !sortBy.equals("type")) {
            return Response.status(Response.Status.BAD_REQUEST)
                    .entity("'sortBy' must be 'eventTime' or 'type'").build();
        }
        if (!sortOrder.equalsIgnoreCase("asc") && !sortOrder.equalsIgnoreCase("desc")) {
            return Response.status(Response.Status.BAD_REQUEST)
                    .entity("'sortOrder' must be 'asc' or 'desc'").build();
        }

        for (Long deviceId : deviceIds) {
            permissionsService.checkPermission(Device.class, getUserId(), deviceId);
        }

        Condition deviceCondition = null;

        for (int i = 0; i < deviceIds.size(); i++) {
            String variableName = "deviceId" + i;
            Condition currentCondition = new Condition.Compare("deviceId", "=", variableName, deviceIds.get(i));

            if (deviceCondition == null) {
                deviceCondition = currentCondition;
            } else {
                deviceCondition = new Condition.Or(deviceCondition, currentCondition);
            }
        }

        List<Event> events = storage.getObjects(Event.class, new Request(
                new Columns.All(),
                new Condition.And(
                        deviceCondition,
                        new Condition.Between("eventTime", "from", from, "to", to)),
                new Order("eventTime")));

        boolean all = types == null || types.isEmpty() || types.contains(Event.ALL_EVENTS);
        if (!all) {
            events.removeIf(event -> {
                if (!types.contains(event.getType())) {
                    return true;
                }
                if (event.getType().equals(Event.TYPE_ALARM)
                        && alarms != null && !alarms.isEmpty()) {
                    String alarm = event.getString(Position.KEY_ALARM);
                    return alarm == null || !alarms.contains(alarm);
                }
                return false;
            });
        }

        if (keyword != null && !keyword.isBlank()) {
            String kw = keyword.trim().toLowerCase();
            events.removeIf(event -> {
                String type  = event.getType() != null
                        ? event.getType().toLowerCase() : "";
                String alarm = event.getString(Position.KEY_ALARM) != null
                        ? event.getString(Position.KEY_ALARM).toLowerCase() : "";
                return !type.contains(kw) && !alarm.contains(kw);
            });
        }

        Comparator<Event> comparator;
        if ("type".equals(sortBy)) {
            comparator = Comparator.comparing(
                    e -> e.getType() != null ? e.getType() : "",
                    String.CASE_INSENSITIVE_ORDER);
        } else {
            comparator = Comparator.comparing(
                    e -> e.getEventTime() != null ? e.getEventTime() : new Date(0));
        }
        if ("desc".equalsIgnoreCase(sortOrder)) {
            comparator = comparator.reversed();
        }
        events.sort(comparator);

        long total = events.size();
        int fromIndex = Math.min((page - 1) * pageSize, events.size());
        int toIndex   = Math.min(fromIndex + pageSize, events.size());
        List<Event> pageData = new ArrayList<>(events.subList(fromIndex, toIndex));

        return Response.ok(new EventSearchResult(pageData, total, page, pageSize)).build();
    }

    public static class EventSearchResult {
        private final Collection<Event> data;
        private final long total;
        private final int page;
        private final int pageSize;

        public EventSearchResult(Collection<Event> data, long total, int page, int pageSize) {
            this.data     = data;
            this.total    = total;
            this.page     = page;
            this.pageSize = pageSize;
        }

        public Collection<Event> getData() {
            return data;
        }

        public long getTotal() {
            return total;
        }

        public int getPage() {
            return page;
        }

        public int getPageSize() {
            return pageSize;
        }
    }


}
