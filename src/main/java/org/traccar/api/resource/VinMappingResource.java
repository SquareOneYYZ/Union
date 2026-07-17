package org.traccar.api.resource;

import jakarta.inject.Inject;
import jakarta.ws.rs.*;
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
import org.traccar.vinmapping.BulkImportSummary;
import org.traccar.vinmapping.VinMappingFileParser;
import org.traccar.vinmapping.VinMappingService;

import java.io.InputStream;
import java.util.*;
import java.util.regex.Pattern;

@Path("vinmappings")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class VinMappingResource extends BaseResource {

    private static final Pattern IMEI_PATTERN = Pattern.compile("^\\d{15}$");

    private static final Pattern VIN_PATTERN = Pattern.compile("^[A-HJ-NPR-Z0-9]{17}$");

    private static final long MAX_UPLOAD_BYTES = 5L * 1024 * 1024; // 5 MB
    private static final int MAX_BULK_ROWS = 5000;

    private static final String CSV_TYPE = "text/csv";
    private static final String XLSX_TYPE = "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";

    @Inject
    private VinMappingService vinMappingService;


    @GET
    public Collection<VinMapping> get(
            @QueryParam("organizationId") long organizationId,
            @QueryParam("userId")         long userId,
            @QueryParam("groupId")        long groupId,
            @QueryParam("imei")           String imei,
            @QueryParam("vin")            String vin) throws StorageException {

        boolean isAdmin = !permissionsService.notAdmin(getUserId());

        long effectiveOrgId;
        if (isAdmin) {
            effectiveOrgId = organizationId;
        } else {
            effectiveOrgId = getCallerOrganizationId();
            if (effectiveOrgId <= 0) {
                return List.of();
            }
        }

        List<Condition> conditions = new ArrayList<>();

        if (effectiveOrgId > 0) {
            conditions.add(new Condition.Equals("organizationid", effectiveOrgId));
        }
        if (userId > 0 && isAdmin) {
            conditions.add(new Condition.Equals("userid", userId));
        }
        if (groupId > 0) {
            conditions.add(new Condition.Equals("groupid", groupId));
        }
        if (imei != null && !imei.isBlank()) {
            conditions.add(new Condition.Equals("imei", imei.trim()));
        }
        if (vin != null && !vin.isBlank()) {
            conditions.add(new Condition.Equals("vin", vin.trim().toUpperCase()));
        }

        Condition condition = conditions.isEmpty() ? null : Condition.merge(conditions);

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

        entity.setUserId(getUserId());
        String validationError = validate(entity);
        if (validationError != null) {
            return Response.status(Response.Status.BAD_REQUEST).entity(validationError).build();
        }

        VinMapping duplicateImei = storage.getObject(VinMapping.class, new Request(
                new Columns.Include("id"),
                new Condition.And(
                        new Condition.Equals("organizationid", entity.getOrganizationId()),
                        new Condition.Equals("imei", entity.getImei()))));
        if (duplicateImei != null) {
            return Response.status(Response.Status.BAD_REQUEST)
                    .entity("IMEI already exists in your mapping list").build();
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

        VinMapping duplicateImeiUpdate = storage.getObject(VinMapping.class, new Request(
                new Columns.Include("id"),
                new Condition.And(
                        new Condition.Equals("organizationid", entity.getOrganizationId()),
                        new Condition.Equals("imei", entity.getImei()))));
        if (duplicateImeiUpdate != null && duplicateImeiUpdate.getId() != entity.getId()) {
            return Response.status(Response.Status.BAD_REQUEST)
                    .entity("IMEI already exists in your mapping list").build();
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
    @Consumes(MediaType.APPLICATION_JSON)
    public Response bulkImport(List<VinMapping> rows) throws Exception {
        if (rows == null || rows.isEmpty()) {
            return Response.status(Response.Status.BAD_REQUEST)
                    .entity("Request body must be a non-empty JSON array").build();
        }
        if (rows.size() > MAX_BULK_ROWS) {
            return Response.status(Response.Status.BAD_REQUEST)
                    .entity("Too many rows in a single request (max " + MAX_BULK_ROWS + ")").build();
        }

        boolean isAdmin = !permissionsService.notAdmin(getUserId());
        long callerOrgId = isAdmin ? 0 : getCallerOrganizationId();

        List<BulkImportResult> results = new ArrayList<>();
        Set<String> seenImeis = new HashSet<>();
        Set<String> seenVins = new HashSet<>();

        int rowNumber = 0;
        for (VinMapping row : rows) {
            rowNumber++;
            processRow(rowNumber, row.getImei(), row.getVin(), row.getGroupId(), row.getOrganizationId(),
                    isAdmin, callerOrgId, seenImeis, seenVins, results);
        }

        return Response.ok(new BulkImportSummary(results)).build();
    }


    @POST
    @Path("bulk/file")
    @Consumes({CSV_TYPE, XLSX_TYPE, MediaType.APPLICATION_OCTET_STREAM})
    public Response bulkImportFile(
            @HeaderParam("X-Filename") String filename,
            InputStream fileStream) throws Exception {

        if (fileStream == null) {
            return Response.status(Response.Status.BAD_REQUEST)
                    .entity("No file uploaded").build();
        }
        if (filename == null || filename.isBlank()) {
            return Response.status(Response.Status.BAD_REQUEST)
                    .entity("Missing X-Filename header").build();
        }

        String lowerFilename = filename.toLowerCase();
        if (!lowerFilename.endsWith(".csv") && !lowerFilename.endsWith(".xlsx") && !lowerFilename.endsWith(".xls")) {
            return Response.status(Response.Status.BAD_REQUEST)
                    .entity("Unsupported file type. Upload a .csv or .xlsx file.").build();
        }

        List<VinMappingFileParser.ParsedRow> parsedRows;
        try {
            parsedRows = VinMappingFileParser.parse(lowerFilename, boundedStream(fileStream));
        } catch (Exception e) {
            return Response.status(Response.Status.BAD_REQUEST)
                    .entity("Failed to parse file: " + e.getMessage()).build();
        }

        if (parsedRows.isEmpty()) {
            return Response.status(Response.Status.BAD_REQUEST)
                    .entity("File contains no data rows").build();
        }
        if (parsedRows.size() > MAX_BULK_ROWS) {
            return Response.status(Response.Status.BAD_REQUEST)
                    .entity("Too many rows in file (max " + MAX_BULK_ROWS + ")").build();
        }

        boolean isAdmin = !permissionsService.notAdmin(getUserId());
        long callerOrgId = isAdmin ? 0 : getCallerOrganizationId();

        List<BulkImportResult> results = new ArrayList<>();
        Set<String> seenImeis = new HashSet<>();
        Set<String> seenVins = new HashSet<>();

        for (VinMappingFileParser.ParsedRow row : parsedRows) {
            if (row.getParseError() != null) {
                results.add(new BulkImportResult(
                        row.getRowNumber(), row.getImei(), row.getVin(),
                        BulkImportResult.Status.REJECTED, row.getParseError()));
                continue;
            }
            processRow(row.getRowNumber(), row.getImei(), row.getVin(), row.getGroupId(), row.getOrganizationId(),
                    isAdmin, callerOrgId, seenImeis, seenVins, results);
        }

        return Response.ok(new BulkImportSummary(results)).build();
    }

    private void processRow(
            int rowNumber, String rawImei, String rawVin, Long groupId, Long organizationId,
            boolean isAdmin, long callerOrgId,
            Set<String> seenImeis, Set<String> seenVins, List<BulkImportResult> results) {

        String imei = rawImei != null ? rawImei.trim() : null;
        String vin = rawVin != null ? rawVin.trim().toUpperCase() : null;

        long orgId = isAdmin && organizationId != null && organizationId > 0
                ? organizationId
                : callerOrgId;

        if (orgId <= 0) {
            results.add(new BulkImportResult(rowNumber, imei, vin,
                    BulkImportResult.Status.REJECTED, "No organization associated with your account"));
            return;
        }

        VinMapping row = new VinMapping();
        row.setImei(imei);
        row.setVin(vin);
        row.setOrganizationId(orgId);
        row.setUserId(getUserId());
        if (groupId != null && groupId > 0) {
            row.setGroupId(groupId);
        }

        String validationError = validate(row);
        if (validationError != null) {
            results.add(new BulkImportResult(rowNumber, imei, vin,
                    BulkImportResult.Status.REJECTED, validationError));
            return;
        }

        String imeiKey = orgId + ":" + row.getImei();
        String vinKey = orgId + ":" + row.getVin();
        if (!seenImeis.add(imeiKey)) {
            results.add(new BulkImportResult(rowNumber, imei, vin,
                    BulkImportResult.Status.REJECTED, "Duplicate IMEI within uploaded file"));
            return;
        }
        if (!seenVins.add(vinKey)) {
            results.add(new BulkImportResult(rowNumber, imei, vin,
                    BulkImportResult.Status.REJECTED, "Duplicate VIN within uploaded file"));
            return;
        }

        try {
            VinMapping existingByImei = storage.getObject(VinMapping.class, new Request(
                    new Columns.Include("id"),
                    new Condition.And(
                            new Condition.Equals("organizationid", orgId),
                            new Condition.Equals("imei", row.getImei()))));
            if (existingByImei != null) {
                results.add(new BulkImportResult(rowNumber, imei, vin,
                        BulkImportResult.Status.REJECTED, "IMEI already exists in your mapping list"));
                return;
            }

            VinMapping existingByVin = storage.getObject(VinMapping.class, new Request(
                    new Columns.Include("id"),
                    new Condition.And(
                            new Condition.Equals("organizationid", orgId),
                            new Condition.Equals("vin", row.getVin()))));
            if (existingByVin != null) {
                results.add(new BulkImportResult(rowNumber, imei, vin,
                        BulkImportResult.Status.REJECTED, "VIN already exists in your mapping list"));
                return;
            }

            row.setId(storage.addObject(row, new Request(new Columns.Exclude("id"))));
            LogAction.create(getUserId(), row);
            results.add(new BulkImportResult(rowNumber, imei, vin, BulkImportResult.Status.CREATED, null));
        } catch (StorageException e) {
            results.add(new BulkImportResult(rowNumber, imei, vin,
                    BulkImportResult.Status.REJECTED, "Database error: " + e.getMessage()));
        }
    }

    private InputStream boundedStream(InputStream in) {
        return in;
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
        if (entity.getOrganizationId() <= 0) {
            return "organizationId is required";
        }
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
                throw new jakarta.ws.rs.WebApplicationException(
                        jakarta.ws.rs.core.Response.status(jakarta.ws.rs.core.Response.Status.FORBIDDEN)
                                .entity("VinMapping access denied").build());
            }
        }
    }


    private long getCallerOrganizationId() throws StorageException {
        User user = permissionsService.getUser(getUserId());
        return user != null ? user.getOrganizationId() : 0;
    }

}
