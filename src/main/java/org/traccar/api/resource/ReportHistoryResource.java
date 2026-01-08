
package org.traccar.api.resource;

import org.traccar.api.BaseResource;
import org.traccar.model.ReportHistory;
import org.traccar.storage.StorageException;
import org.traccar.storage.query.Columns;
import org.traccar.storage.query.Condition;
import org.traccar.storage.query.Order;
import org.traccar.storage.query.Request;

import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.DELETE;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import java.util.Collection;
import java.util.LinkedList;

@Path("reporthistory")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class ReportHistoryResource extends BaseResource {

    @GET
    public Collection<ReportHistory> get(
            @QueryParam("limit") int limit,
            @QueryParam("reportType") String reportType) throws StorageException {

        var conditions = new LinkedList<Condition>();
        conditions.add(new Condition.Equals("userId", getUserId()));
        if (reportType != null && !reportType.isEmpty()) {
            conditions.add(new Condition.Equals("reportType", reportType));
        }
        if (limit <= 0) {
            limit = 20;
        }

        return storage.getObjects(ReportHistory.class, new Request(
                new Columns.All(),
                Condition.merge(conditions),
                new Order("generatedAt", true, limit)));
    }

    @Path("{id}")
    @DELETE
    public Response remove(@PathParam("id") long id) throws StorageException {
        
        ReportHistory reportHistory = storage.getObject(ReportHistory.class, new Request(
                new Columns.All(),
                new Condition.Equals("id", id)));

        if (reportHistory == null) {
            return Response.status(Response.Status.NOT_FOUND).build();
        }
        if (reportHistory.getUserId() != getUserId()) {
            return Response.status(Response.Status.FORBIDDEN).build();
        }
        storage.removeObject(ReportHistory.class, new Request(
                new Condition.Equals("id", id)));
        return Response.noContent().build();
    }

}
