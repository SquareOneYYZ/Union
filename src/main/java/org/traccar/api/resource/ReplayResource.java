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

import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.WebApplicationException;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.traccar.api.BaseResource;
import org.traccar.api.replay.ReplaySessionService;
import org.traccar.model.Device;
import org.traccar.model.Position;
import org.traccar.model.ReplaySession;
import org.traccar.model.UserRestrictions;
import org.traccar.storage.StorageException;

import java.util.Date;
import java.util.List;

@Path("replay")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class ReplayResource extends BaseResource {

    private static final int DEFAULT_CHUNK_LIMIT = 100;
    private static final int MAX_CHUNK_LIMIT = 1000;
    private static final int DEFAULT_OVERVIEW_LIMIT = 1000;
    private static final int MAX_OVERVIEW_LIMIT = 5000;

    @Inject
    private ReplaySessionService replaySessionService;

    @POST
    @Path("session")
    @Consumes(MediaType.APPLICATION_JSON)
    public ReplaySession createSession(ReplaySessionRequest request) throws StorageException {
        if (request == null || request.getDeviceId() <= 0 || request.getFrom() == null || request.getTo() == null) {
            throw new WebApplicationException(
                    Response.status(Response.Status.BAD_REQUEST).entity("deviceId, from, to are required").build());
        }

        permissionsService.checkPermission(Device.class, getUserId(), request.getDeviceId());
        permissionsService.checkRestriction(getUserId(), UserRestrictions::getDisableReports);

        return replaySessionService.createSession(getUserId(), request.getDeviceId(), request.getFrom(), request.getTo());
    }


    @GET
    @Path("session/{id}/chunk")
    public List<Position> getChunk(
            @PathParam("id") String sessionId,
            @QueryParam("offset") int offset,
            @QueryParam("limit") int limit) throws StorageException {

        if (limit <= 0) {
            limit = DEFAULT_CHUNK_LIMIT;
        } else if (limit > MAX_CHUNK_LIMIT) {
            limit = MAX_CHUNK_LIMIT;
        }

        ReplaySession session = replaySessionService.getSession(sessionId);
        if (session == null) {
            throw new WebApplicationException(
                    Response.status(Response.Status.NOT_FOUND).entity("Session not found or expired").build());
        }
        if (session.getUserId() != getUserId()) {
            throw new WebApplicationException(Response.Status.FORBIDDEN);
        }

        return replaySessionService.getChunk(session, offset, limit);
    }

    @GET
    @Path("session/{id}/overview")
    public List<Position> getOverview(
            @PathParam("id") String sessionId,
            @QueryParam("limit") int limit) throws StorageException {

        if (limit <= 0) {
            limit = DEFAULT_OVERVIEW_LIMIT;
        } else if (limit > MAX_OVERVIEW_LIMIT) {
            limit = MAX_OVERVIEW_LIMIT;
        }

        ReplaySession session = replaySessionService.getSession(sessionId);
        if (session == null) {
            throw new WebApplicationException(
                    Response.status(Response.Status.NOT_FOUND).entity("Session not found or expired").build());
        }
        if (session.getUserId() != getUserId()) {
            throw new WebApplicationException(Response.Status.FORBIDDEN);
        }

        return replaySessionService.getOverview(session, limit);
    }


    public static class ReplaySessionRequest {
        private long deviceId;
        private Date from;
        private Date to;

        public long getDeviceId() {
            return deviceId;
        }

        public void setDeviceId(long deviceId) {
            this.deviceId = deviceId;
        }

        public Date getFrom() {
            return from;
        }

        public void setFrom(Date from) {
            this.from = from;
        }

        public Date getTo() {
            return to;
        }

        public void setTo(Date to) {
            this.to = to;
        }
    }

}
