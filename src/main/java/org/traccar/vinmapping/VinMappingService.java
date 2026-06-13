/*
 * Copyright 2024 - 2026 Traccar contributors
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
package org.traccar.vinmapping;

import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.traccar.model.Device;
import org.traccar.model.Group;
import org.traccar.model.Organization;
import org.traccar.model.VinMapping;
import org.traccar.model.ObjectOperation;
import org.traccar.session.cache.CacheManager;
import org.traccar.storage.Storage;
import org.traccar.storage.StorageException;
import org.traccar.storage.query.Columns;
import org.traccar.storage.query.Condition;
import org.traccar.storage.query.Request;

import java.sql.SQLException;
import java.util.Date;
import java.util.HashSet;
import java.util.Set;


@Singleton
public class VinMappingService {

    private static final Logger LOGGER = LoggerFactory.getLogger(VinMappingService.class);

    private static final int MAX_GROUP_DEPTH = 10;

    public static final String ATTR_ERROR = "error";

    public static final String ERR_VIN_CONFLICT = "vin-conflict";

    public static final String ERR_VIN_CONFLICT_OTHER = "vin-conflict-other-device";

    private final Storage storage;
    private final CacheManager cacheManager;

    @Inject
    public VinMappingService(Storage storage, CacheManager cacheManager) {
        this.storage = storage;
        this.cacheManager = cacheManager;
    }


    public void onDeviceAssigned(Device device, long newGroupId) {
        if (newGroupId <= 0) {
            return;
        }
        try {
            applyMapping(device, newGroupId);
        } catch (Exception e) {
            LOGGER.warn("VIN mapping hook failed for device {} group {}: {}",
                    device.getId(), newGroupId, e.getMessage(), e);
        }
    }


    private void applyMapping(Device device, long newGroupId) throws Exception {
        Organization org = resolveOrganization(newGroupId);
        if (org == null) {
            LOGGER.debug("VinMapping: group {} has no owning organization – skipping device {}",
                    newGroupId, device.getId());
            return;
        }

        if (device.getOrganizationId() > 0 && device.getOrganizationId() != org.getId()) {
            LOGGER.warn("VinMapping: device {} belongs to org {} but is being assigned to a group "
                    + "under org {} – skipping VIN auto-apply",
                    device.getId(), device.getOrganizationId(), org.getId());
            return;
        }

        VinMapping mapping = findMapping(org.getId(), device.getUniqueId());
        if (mapping == null) {
            return;
        }

        String mappedVin = mapping.getVin();

        if (mappedVin.equalsIgnoreCase(device.getVin())) {
            if (mapping.getDeviceId() != device.getId() || mapping.getAppliedAt() == null) {
                markApplied(mapping, device.getId());
            }
            return;
        }

        if (device.getVin() != null && !device.getVin().isBlank()) {
            LOGGER.warn("VinMapping: device {} already has VIN '{}'; mapped VIN '{}' not applied (conflict)",
                    device.getId(), device.getVin(), mappedVin);
            flagMapping(mapping, ERR_VIN_CONFLICT);
            return;
        }

        applyVin(device, mapping, mappedVin);
    }


    public void reApply(VinMapping mapping) throws Exception {
        if (mapping.getDeviceId() <= 0) {
            throw new IllegalStateException("Mapping has no matched device");
        }
        Device device = storage.getObject(Device.class, new Request(
                new Columns.All(), new Condition.Equals("id", mapping.getDeviceId())));
        if (device == null) {
            throw new IllegalStateException("Matched device not found");
        }

        String mappedVin = mapping.getVin();

        if (mappedVin.equalsIgnoreCase(device.getVin())) {
            mapping.removeAttribute(ATTR_ERROR);
            persistMappingStatus(mapping);
            return;
        }

        if (device.getVin() != null && !device.getVin().isBlank()) {
            flagMapping(mapping, ERR_VIN_CONFLICT);
            throw new IllegalStateException("VIN conflict: device already has a different VIN");
        }

        applyVin(device, mapping, mappedVin);
    }


    Organization resolveOrganization(long groupId) throws StorageException {
        Set<Long> visited = new HashSet<>();
        long current = groupId;
        int depth = 0;

        while (current > 0 && depth < MAX_GROUP_DEPTH) {
            if (!visited.add(current)) {
                LOGGER.warn("VinMapping: cycle detected in group tree at group {}", current);
                return null;
            }

            Group group = storage.getObject(Group.class, new Request(
                    new Columns.All(), new Condition.Equals("id", current)));
            if (group == null) {
                break;
            }

            if (group.getOrganizationId() > 0) {
                return storage.getObject(Organization.class, new Request(
                        new Columns.All(), new Condition.Equals("id", group.getOrganizationId())));
            }

            current = group.getGroupId();
            depth++;
        }

        return null;
    }

    private VinMapping findMapping(long organizationId, String imei) throws StorageException {
        return storage.getObject(VinMapping.class, new Request(
                new Columns.All(),
                new Condition.And(
                        new Condition.Equals("organizationid", organizationId),
                        new Condition.Equals("imei", imei))));
    }


    private void applyVin(Device device, VinMapping mapping, String vin) throws Exception {
        device.setVin(vin);
        try {
            storage.updateObject(device, new Request(
                    new Columns.Include("vin"),
                    new Condition.Equals("id", device.getId())));
        } catch (StorageException e) {
            if (isUniqueConstraintViolation(e)) {
                LOGGER.warn("VinMapping: VIN '{}' is already held by another device – flagging mapping {}",
                        vin, mapping.getId());
                flagMapping(mapping, ERR_VIN_CONFLICT_OTHER);
                return;
            }
            throw e;
        }

        try {
            cacheManager.invalidateObject(true, Device.class, device.getId(), ObjectOperation.UPDATE);
        } catch (Exception e) {
            LOGGER.warn("VinMapping: cache invalidation failed after VIN apply (non-fatal): {}", e.getMessage());
        }

        markApplied(mapping, device.getId());

        LOGGER.info("VinMapping: applied VIN '{}' to device {} (mapping {})",
                vin, device.getId(), mapping.getId());
    }

    private void markApplied(VinMapping mapping, long deviceId) throws StorageException {
        mapping.setDeviceId(deviceId);
        mapping.setAppliedAt(new Date());
        mapping.removeAttribute(ATTR_ERROR);
        persistMappingStatus(mapping);
    }

    private void flagMapping(VinMapping mapping, String errorCode) throws StorageException {
        mapping.set(ATTR_ERROR, errorCode);
        persistMappingStatus(mapping);
    }

    private void persistMappingStatus(VinMapping mapping) throws StorageException {
        storage.updateObject(mapping, new Request(
                new Columns.Include("deviceid", "appliedat", "attributes"),
                new Condition.Equals("id", mapping.getId())));
    }


    private boolean isUniqueConstraintViolation(StorageException e) {
        Throwable cause = e.getCause();
        while (cause != null) {
            if (cause instanceof SQLException sqlEx) {
                String state = sqlEx.getSQLState();
                if (state != null && state.startsWith("23")) {
                    return true;
                }
                String msg = sqlEx.getMessage();
                if (msg != null && (msg.contains("Duplicate") || msg.contains("unique constraint")
                        || msg.contains("UNIQUE") || msg.contains("uk_devices_vin"))) {
                    return true;
                }
            }
            cause = cause.getCause();
        }
        return false;
    }

}
