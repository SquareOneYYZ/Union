package org.traccar.api.resource;

import org.traccar.api.BaseResource;
import org.traccar.model.Device;
import org.traccar.model.TollTraversal;
import org.traccar.model.UserRestrictions;
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
import jakarta.ws.rs.WebApplicationException;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Date;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * Read-only access to priced toll traversals ({@code tc_toll_traversal}, docs/toll-pricing/22), shaped
 * like {@link DeviceGeofenceDistanceResource}: {@code GET /api/tolltraversals?deviceId=1&deviceId=2&from=..&to=..}.
 *
 * <ul>
 *   <li>The rows are location history: a user with {@code disableReports} (or a device share) is refused,
 *       as by the reports and positions endpoints.</li>
 *   <li>Every requested device is permission-checked; one the user cannot see fails the request.</li>
 *   <li>{@code from}/{@code to} are required, at most {@link #MAX_RANGE_DAYS} days apart, with at most
 *       {@link #MAX_DEVICES} devices: the table is never scanned unbounded.</li>
 *   <li>Rows are selected by {@code exitTime} (an exit without an enter has no {@code enterTime} but is
 *       still listed), ordered by it. A row without an exit position has neither and is not listed.</li>
 *   <li>{@code amount} null means unknown and {@code reason} says why; amounts are per row in the row's
 *       {@code currency} and must never be summed across currencies.</li>
 * </ul>
 */
@Path("tolltraversals")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class TollTraversalResource extends BaseResource {

    static final int MAX_RANGE_DAYS = 93;
    static final long MAX_RANGE_MS = TimeUnit.DAYS.toMillis(MAX_RANGE_DAYS);
    static final int MAX_DEVICES = 500;

    @GET
    public Collection<TollTraversal> get(
            @QueryParam("deviceId") List<Long> deviceIds,
            @QueryParam("from") Date from,
            @QueryParam("to") Date to) throws StorageException {

        if (deviceIds == null || deviceIds.isEmpty() || from == null || to == null) {
            throw badRequest("deviceId, from and to are required");
        }
        if (!from.before(to)) {
            throw badRequest("from must be before to");
        }
        if (to.getTime() - from.getTime() > MAX_RANGE_MS) {
            throw badRequest("from-to range cannot exceed " + MAX_RANGE_DAYS + " days");
        }
        LinkedHashSet<Long> unique = new LinkedHashSet<>(deviceIds);
        if (unique.size() > MAX_DEVICES) {
            throw badRequest("at most " + MAX_DEVICES + " devices per request");
        }
        // Rows carry entry/exit coordinates and times: location history, gated like the reports.
        permissionsService.checkRestriction(getUserId(), UserRestrictions::getDisableReports);
        for (long deviceId : unique) {
            permissionsService.checkPermission(Device.class, getUserId(), deviceId);
        }
        List<TollTraversal> result = new ArrayList<>();
        for (long deviceId : unique) {
            result.addAll(storage.getObjects(TollTraversal.class, new Request(
                    new Columns.All(),
                    new Condition.And(
                            new Condition.Equals("deviceId", deviceId),
                            new Condition.Between("exitTime", "from", from, "to", to)),
                    new Order("exitTime"))));
        }
        return result;
    }

    private static WebApplicationException badRequest(String message) {
        return new WebApplicationException(Response.status(Response.Status.BAD_REQUEST).entity(message).build());
    }
}
