package org.traccar.database;

import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.traccar.config.Config;
import org.traccar.helper.model.GeofenceUtil;
import org.traccar.model.Device;
import org.traccar.model.Group;
import org.traccar.model.ObjectOperation;
import org.traccar.model.Permission;
import org.traccar.model.Position;
import org.traccar.model.User;
import org.traccar.session.cache.CacheManager;
import org.traccar.storage.Storage;
import org.traccar.storage.StorageException;
import org.traccar.storage.query.Columns;
import org.traccar.storage.query.Condition;
import org.traccar.storage.query.Request;

import java.util.List;


@Singleton
public class DeviceReassignmentService {

    private static final Logger LOGGER = LoggerFactory.getLogger(DeviceReassignmentService.class);

    private final Storage storage;
    private final CacheManager cacheManager;
    private final Config config;

    @Inject
    public DeviceReassignmentService(Storage storage, CacheManager cacheManager, Config config) {
        this.storage = storage;
        this.cacheManager = cacheManager;
        this.config = config;
    }

    public void onDeviceOnline(long deviceId) {
        try {
            processDeviceOnline(deviceId);
        } catch (Exception e) {
            LOGGER.warn("Device reassignment failed for device {}", deviceId, e);
        }
    }

    private void processDeviceOnline(long deviceId) throws Exception {

        Device device = storage.getObject(Device.class, new Request(
                new Columns.All(), new Condition.Equals("id", deviceId)));
        if (device == null) {
            LOGGER.debug("Device reassignment: device {} not found", deviceId);
            return;
        }

        if (device.getGroupId() == 0) {
            LOGGER.debug("Device reassignment: device {} has no group, skipping", deviceId);
            return;
        }

        Group group = storage.getObject(Group.class, new Request(
                new Columns.All(), new Condition.Equals("id", device.getGroupId())));
        if (group == null || group.getUnassigned() == 0) {
            LOGGER.debug("Device reassignment: device {} group {} has unassigned=0, skipping",
                    deviceId, device.getGroupId());
            return;
        }

        long targetGroupId = group.getUnassigned();

        Position position = cacheManager.getPosition(deviceId);
        if (position == null) {
            LOGGER.debug("Device reassignment: device {} has no known position, skipping", deviceId);
            return;
        }

        List<Long> activeGeofenceIds = GeofenceUtil.getCurrentGeofences(config, cacheManager, position);
        if (!activeGeofenceIds.isEmpty()) {
            LOGGER.debug("Device reassignment: device {} is inside geofence(s) {}, skipping",
                    deviceId, activeGeofenceIds);
            return;
        }

        LOGGER.info("Device reassignment: device {} (uniqueId={}) is outside geofences and in unassigned group {}. "
                + "Reassigning to group {}.",
                deviceId, device.getUniqueId(), device.getGroupId(), targetGroupId);

        List<Permission> userPermissions = storage.getPermissions(User.class, Device.class)
                .stream()
                .filter(p -> p.getPropertyId() == deviceId)
                .toList();

        storage.removeObject(Device.class, new Request(new Condition.Equals("id", deviceId)));
        cacheManager.invalidateObject(true, Device.class, deviceId, ObjectOperation.DELETE);

        device.setId(0);
        device.setGroupId(targetGroupId);
        long newDeviceId = storage.addObject(device, new Request(new Columns.Exclude("id")));
        device.setId(newDeviceId);

        for (Permission oldPermission : userPermissions) {
            long ownerId = oldPermission.getOwnerId();
            try {
                storage.addPermission(new Permission(User.class, ownerId, Device.class, newDeviceId));
                cacheManager.invalidatePermission(true, User.class, ownerId, Device.class, newDeviceId, true);
            } catch (StorageException e) {
                LOGGER.warn("Device reassignment: failed to restore permission for user {} → device {}",
                        ownerId, newDeviceId, e);
            }
        }

        LOGGER.info("Device reassignment complete: old id={} → new id={}, group={} → group={}",
                deviceId, newDeviceId, group.getId(), targetGroupId);
    }
}
