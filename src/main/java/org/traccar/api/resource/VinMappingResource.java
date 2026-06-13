package org.traccar.api.resource;

import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.DELETE;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.PUT;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.traccar.api.BaseResource;
import org.traccar.helper.LogAction;
import org.traccar.model.User;
import org.traccar.model.VinMapping;
import org.traccar.storage.StorageException;
import org.traccar.storage.query.Columns;
import org.traccar.storage.query.Condition;
import org.traccar.storage.query.Order;
import org.traccar.storage.query.Request;
import org.traccar.vinmapping.BulkImportResult;
import org.traccar.vinmapping.VinMappingService;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedList;
import java.util.List;
import java.util.regex.Pattern;

@Path("vinmappings")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class VinMappingResource extends BaseResource {

    private static final Pattern IMEI_PATTERN = Pattern.compile("^\\d{15}$");

    private static final Pattern VIN_PATTERN = Pattern.compile("^[A-HJ-NPR-Z0-9]{17}$");

    @Inject
    private VinMappingService vinMappingService;


    @GET
    public Collection<VinMapping> get(
            @QueryParam("organizationId") long organizationId) throws StorageException {

        boolean isAdmin = !permissionsService.notAdmin(getUserId());

        Condition condition;
        if (isAdmin) {
            condition = organizationId > 0
                    ? new Condition.Equals("organizationid", organizationId)
                    : null;
        } else {
            long callerOrgId = getCallerOrganizationId();
            if (callerOrgId <= 0) {
                return List.of();
            }
            condition = new Condition.Equals("organizationid", callerOrgId);
        }

        return storage.getObjects(VinMapping.class, new Request(
                new Columns.All(), condition, new Order("imei")));
    }


    @Path("{id}")
    @GET
    public Response getSingle(@PathParam("id") long id) throws StorageException {
        VinMapping mapping = storage.getObject(VinMapping.class, new Request(
                new Columns.All(), new Condition.Equals("id", id)));
        if (mapping == null) {
            return Response.status(Response.Status.NOT_FOUND).build();
        }
        checkOrgAccess(mapping);
        return Response.ok(mapping).build();
    }


    @POST
    public Response add(VinMapping entity) throws Exception {
        enforceOrganization(entity);

        String validationError = validate(entity);
        if (validationError != null) {
            return Response.status(Response.Status.BAD_REQUEST).entity(validationError).build();
        }

        VinMapping duplicateVin = storage.getObject(VinMapping.class, new Request(
                new Columns.Include("id"),
                new Condition.And(
                        new Condition.Equals("organizationid", entity.getOrganizationId()),
                        new Condition.Equals("vin", entity.getVin()))));
        if (duplicateVin != null) {
            return Response.status(Response.Status.BAD_REQUEST)
                    .entity("VIN already exists in your mapping list").build();
        }

        entity.setId(storage.addObject(entity, new Request(new Columns.Exclude("id"))));
        LogAction.create(getUserId(), entity);

        return Response.ok(entity).build();
    }


    @Path("{id}")
    @PUT
    public Response update(VinMapping entity) throws Exception {
        VinMapping existing = storage.getObject(VinMapping.class, new Request(
                new Columns.All(), new Condition.Equals("id", entity.getId())));
        if (existing == null) {
            return Response.status(Response.Status.NOT_FOUND).build();
        }
        checkOrgAccess(existing);

        enforceOrganization(entity);
        entity.setOrganizationId(existing.getOrganizationId());

        String validationError = validate(entity);
        if (validationError != null) {
            return Response.status(Response.Status.BAD_REQUEST).entity(validationError).build();
        }

        VinMapping duplicateVin = storage.getObject(VinMapping.class, new Request(
                new Columns.Include("id"),
                new Condition.And(
                        new Condition.Equals("organizationid", entity.getOrganizationId()),
                        new Condition.Equals("vin", entity.getVin()))));
        if (duplicateVin != null && duplicateVin.getId() != entity.getId()) {
            return Response.status(Response.Status.BAD_REQUEST)
                    .entity("VIN already exists in your mapping list").build();
        }

        storage.updateObject(entity, new Request(
                new Columns.Exclude("id"),
                new Condition.Equals("id", entity.getId())));
        LogAction.edit(getUserId(), entity);

        return Response.ok(entity).build();
    }


    @Path("{id}")
    @DELETE
    public Response remove(@PathParam("id") long id) throws Exception {
        VinMapping existing = storage.getObject(VinMapping.class, new Request(
                new Columns.All(), new Condition.Equals("id", id)));
        if (existing == null) {
            return Response.status(Response.Status.NOT_FOUND).build();
        }
        checkOrgAccess(existing);

        storage.removeObject(VinMapping.class, new Request(new Condition.Equals("id", id)));
        LogAction.remove(getUserId(), VinMapping.class, id);

        return Response.noContent().build();
    }


    @POST
    @Path("bulk")
    public Response bulkImport(List<VinMapping> rows) throws Exception {
        if (rows == null || rows.isEmpty()) {
            return Response.status(Response.Status.BAD_REQUEST)
                    .entity("Request body must be a non-empty JSON array").build();
        }

        boolean isAdmin = !permissionsService.notAdmin(getUserId());
        long callerOrgId = isAdmin ? 0 : getCallerOrganizationId();

        List<BulkImportResult> results = new ArrayList<>();

        for (VinMapping row : rows) {
            if (row.getImei() != null) {
                row.setImei(row.getImei().trim());
            }
            if (row.getVin() != null) {
                row.setVin(row.getVin().trim().toUpperCase());
            }

            long orgId = isAdmin && row.getOrganizationId() > 0
                    ? row.getOrganizationId()
                    : callerOrgId;

            if (orgId <= 0) {
                results.add(new BulkImportResult(
                        row.getImei(), row.getVin(),
                        BulkImportResult.Status.REJECTED,
                        "No organization associated with your account"));
                continue;
            }
            row.setOrganizationId(orgId);

            String validationError = validate(row);
            if (validationError != null) {
                results.add(new BulkImportResult(
                        row.getImei(), row.getVin(),
                        BulkImportResult.Status.REJECTED,
                        validationError));
                continue;
            }

            VinMapping existing = storage.getObject(VinMapping.class, new Request(
                    new Columns.Include("id"),
                    new Condition.And(
                            new Condition.Equals("organizationid", orgId),
                            new Condition.Equals("imei", row.getImei()))));
            if (existing != null) {
                results.add(new BulkImportResult(
                        row.getImei(), row.getVin(),
                        BulkImportResult.Status.REJECTED,
                        "IMEI already exists in your mapping list"));
                continue;
            }

            VinMapping dupVin = storage.getObject(VinMapping.class, new Request(
                    new Columns.Include("id"),
                    new Condition.And(
                            new Condition.Equals("organizationid", orgId),
                            new Condition.Equals("vin", row.getVin()))));
            if (dupVin != null) {
                results.add(new BulkImportResult(
                        row.getImei(), row.getVin(),
                        BulkImportResult.Status.REJECTED,
                        "VIN already exists in your mapping list"));
                continue;
            }

            try {
                row.setId(storage.addObject(row, new Request(new Columns.Exclude("id"))));
                LogAction.create(getUserId(), row);
                results.add(new BulkImportResult(
                        row.getImei(), row.getVin(),
                        BulkImportResult.Status.CREATED, null));
            } catch (StorageException e) {
                results.add(new BulkImportResult(
                        row.getImei(), row.getVin(),
                        BulkImportResult.Status.REJECTED,
                        "Database error: " + e.getMessage()));
            }
        }

        return Response.ok(results).build();
    }


    @POST
    @Path("{id}/apply")
    public Response apply(@PathParam("id") long id) throws Exception {
        VinMapping mapping = storage.getObject(VinMapping.class, new Request(
                new Columns.All(), new Condition.Equals("id", id)));
        if (mapping == null) {
            return Response.status(Response.Status.NOT_FOUND).build();
        }
        checkOrgAccess(mapping);

        try {
            vinMappingService.reApply(mapping);
        } catch (IllegalStateException e) {
            return Response.status(Response.Status.CONFLICT).entity(e.getMessage()).build();
        }

        VinMapping updated = storage.getObject(VinMapping.class, new Request(
                new Columns.All(), new Condition.Equals("id", id)));
        return Response.ok(updated).build();
    }


    private String validate(VinMapping entity) {
        if (entity.getImei() == null || entity.getImei().isBlank()) {
            return "IMEI is required";
        }
        if (!IMEI_PATTERN.matcher(entity.getImei()).matches()) {
            return "IMEI must be exactly 15 digits";
        }
        if (entity.getVin() == null || entity.getVin().isBlank()) {
            return "VIN is required";
        }
        if (!VIN_PATTERN.matcher(entity.getVin()).matches()) {
            return "VIN must be exactly 17 uppercase alphanumeric characters (I, O, Q not allowed)";
        }
        return null;
    }

    private void enforceOrganization(VinMapping entity) throws StorageException {
        if (permissionsService.notAdmin(getUserId())) {
            long orgId = getCallerOrganizationId();
            entity.setOrganizationId(orgId);
        }
    }


    private void checkOrgAccess(VinMapping mapping) throws StorageException {
        if (permissionsService.notAdmin(getUserId())) {
            long callerOrgId = getCallerOrganizationId();
            if (mapping.getOrganizationId() != callerOrgId) {
                throw new SecurityException("VinMapping access denied");
            }
        }
    }


    private long getCallerOrganizationId() throws StorageException {
        User user = permissionsService.getUser(getUserId());
        return user != null ? user.getOrganizationId() : 0;
    }

}
