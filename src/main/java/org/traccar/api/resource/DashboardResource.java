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
import org.traccar.helper.LogAction;
import org.traccar.model.UserRestrictions;
import org.traccar.reports.WeeklyKmByGroupReportProvider;
import org.traccar.reports.model.WeeklyKmByGroupItem;
import org.traccar.storage.StorageException;

import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;
import java.util.Collection;
import java.util.Date;
import java.util.List;

@Path("dashboard")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class DashboardResource extends BaseResource {

    @Inject
    private WeeklyKmByGroupReportProvider weeklyKmByGroupReportProvider;

    @Path("weeklykm")
    @GET
    public Collection<WeeklyKmByGroupItem> getWeeklyKmByGroup(
            @QueryParam("deviceId") List<Long> deviceIds,
            @QueryParam("groupId") List<Long> groupIds,
            @QueryParam("from") Date from,
            @QueryParam("to") Date to) throws StorageException {
        permissionsService.checkRestriction(getUserId(), UserRestrictions::getDisableReports);
        LogAction.report(getUserId(), false, "weeklykm", from, to, deviceIds, groupIds);
        return weeklyKmByGroupReportProvider.getObjects(getUserId(), deviceIds, groupIds, from, to);
    }
}
