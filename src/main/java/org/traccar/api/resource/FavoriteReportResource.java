
package org.traccar.api.resource;

import jakarta.ws.rs.*;
import org.traccar.api.BaseResource;
import org.traccar.model.FavoriteReport;
import org.traccar.storage.StorageException;
import org.traccar.storage.query.Columns;
import org.traccar.storage.query.Condition;
import org.traccar.storage.query.Order;
import org.traccar.storage.query.Request;

import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import java.util.Collection;
import java.util.Date;

@Path("favoritereports")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class FavoriteReportResource extends BaseResource {

    @GET
    public Collection<FavoriteReport> get(
            @QueryParam("userId") long userId) throws StorageException {
        var conditions = new java.util.LinkedList<Condition>();
        if (userId > 0) {
            conditions.add(new Condition.Equals("createdBy", userId));
        }
        return storage.getObjects(FavoriteReport.class, new Request(
                new Columns.All(),
                Condition.merge(conditions),
                new Order("id")));
    }

    @POST
    public Response add(FavoriteReport entity) throws StorageException {
        entity.setCreatedBy(getUserId());
        entity.setCreatedAt(new Date());
        entity.setUpdatedAt(new Date());
        entity.setId(storage.addObject(entity, new Request(new Columns.Exclude("id"))));
        return Response.ok(entity).build();
    }

    @Path("{id}")
    @PUT
    public Response update(FavoriteReport entity) throws StorageException {
        entity.setUpdatedAt(new Date());
        storage.updateObject(entity, new Request(
                new Columns.Exclude("id", "createdBy", "createdAt"),
                new Condition.Equals("id", entity.getId())));
        return Response.ok(entity).build();
    }

    @Path("{id}")
    @DELETE
    public Response remove(@PathParam("id") long id) throws StorageException {
        storage.removeObject(FavoriteReport.class, new Request(
                new Condition.Equals("id", id)));
        return Response.noContent().build();
    }

}
