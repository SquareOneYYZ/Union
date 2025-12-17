/*
 * Copyright 2015 - 2024 Anton Tananaev (anton@traccar.org)
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

import com.warrenstrange.googleauth.GoogleAuthenticator;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.ws.rs.DELETE;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.core.Context;
import org.traccar.api.BaseObjectResource;
import org.traccar.config.Config;
import org.traccar.config.Keys;
import org.traccar.helper.LogAction;
import org.traccar.helper.PasswordGenerator;
import org.traccar.helper.SessionHelper;
import org.traccar.helper.TotpHelper;
import org.traccar.helper.model.UserUtil;
import org.traccar.mail.MailManager;
import org.traccar.model.Device;
import org.traccar.model.ManagedUser;
import org.traccar.model.Permission;
import org.traccar.model.User;
import org.traccar.notification.TextTemplateFormatter;
import org.traccar.storage.StorageException;
import org.traccar.storage.query.Columns;
import org.traccar.storage.query.Condition;
import org.traccar.storage.query.Order;
import org.traccar.storage.query.Request;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import jakarta.annotation.security.PermitAll;
import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import java.util.Collection;
import java.util.LinkedList;

@Path("users")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class UserResource extends BaseObjectResource<User> {

    private static final Logger LOGGER = LoggerFactory.getLogger(UserResource.class);

    @Inject
    private Config config;

    @Inject
    private MailManager mailManager;

    @Inject
    private TextTemplateFormatter textTemplateFormatter;

    @Context
    private HttpServletRequest request;

    public UserResource() {
        super(User.class);
    }

    @GET
    public Collection<User> get(
            @QueryParam("userId") long userId, @QueryParam("deviceId") long deviceId) throws StorageException {
        var conditions = new LinkedList<Condition>();
        if (userId > 0) {
            permissionsService.checkUser(getUserId(), userId);
            conditions.add(new Condition.Permission(User.class, userId, ManagedUser.class).excludeGroups());
        } else if (permissionsService.notAdmin(getUserId())) {
            conditions.add(new Condition.Permission(User.class, getUserId(), ManagedUser.class).excludeGroups());
        }
        if (deviceId > 0) {
            permissionsService.checkManager(getUserId());
            conditions.add(new Condition.Permission(User.class, Device.class, deviceId).excludeGroups());
        }
        return storage.getObjects(baseClass, new Request(
                new Columns.All(), Condition.merge(conditions), new Order("name")));
    }

    @Override
    @PermitAll
    @POST
    public Response add(User entity) throws StorageException {
        User currentUser = getUserId() > 0 ? permissionsService.getUser(getUserId()) : null;
        boolean isSubaccountAdmin = currentUser != null && currentUser.getUserLimit() != 0;
        String temporaryPassword = null;
        
        if (currentUser == null || !currentUser.getAdministrator()) {
            permissionsService.checkUserUpdate(getUserId(), new User(), entity);
            if (isSubaccountAdmin) {
                int userLimit = currentUser.getUserLimit();
                if (userLimit > 0) {
                    int userCount = storage.getObjects(baseClass, new Request(
                            new Columns.All(),
                            new Condition.Permission(User.class, getUserId(), ManagedUser.class).excludeGroups()))
                            .size();
                    if (userCount >= userLimit) {
                        throw new SecurityException("Manager user limit reached");
                    }
                }
                
                // Generate temporary password and TOTP for subaccount admin created users
                temporaryPassword = PasswordGenerator.generate();
                entity.setPassword(temporaryPassword);
                
                // Generate TOTP secret if not already provided
                if (entity.getTotpKey() == null) {
                    String totpSecret = new GoogleAuthenticator().createCredentials().getKey();
                    entity.setTotpKey(totpSecret);
                }
                
                // Mark user as temporary to force password reset on first login
                entity.setTemporary(true);
                
            } else {
                if (UserUtil.isEmpty(storage)) {
                    entity.setAdministrator(true);
                } else if (!permissionsService.getServer().getRegistration()) {
                    throw new SecurityException("Registration disabled");
                }
                if (permissionsService.getServer().getBoolean(Keys.WEB_TOTP_FORCE.getKey())
                        && entity.getTotpKey() == null) {
                    throw new SecurityException("One-time password key is required");
                }
                UserUtil.setUserDefaults(entity, config);
            }
        }

        entity.setId(storage.addObject(entity, new Request(new Columns.Exclude("id"))));
        storage.updateObject(entity, new Request(
                new Columns.Include("hashedPassword", "salt", "totpKey", "temporary"),
                new Condition.Equals("id", entity.getId())));

        LogAction.create(getUserId(), entity);

        if (isSubaccountAdmin) {
            storage.addPermission(new Permission(User.class, getUserId(), ManagedUser.class, entity.getId()));
            LogAction.link(getUserId(), User.class, getUserId(), ManagedUser.class, entity.getId());
            
            // Send onboarding email with credentials
            if (temporaryPassword != null && entity.getEmail() != null && !entity.getEmail().isEmpty()) {
                try {
                    sendOnboardingEmail(entity, temporaryPassword);
                    LogAction.emailDispatched(getUserId(), "userOnboarding", entity.getEmail());
                } catch (Exception e) {
                    LOGGER.error("Failed to send onboarding email to user: " + entity.getEmail(), e);
                    // Continue despite email failure - user is already created
                }
            }
        }
        return Response.ok(entity).build();
    }
    
    private void sendOnboardingEmail(User user, String temporaryPassword) throws Exception {
        var velocityContext = textTemplateFormatter.prepareContext(permissionsService.getServer(), user);
        
        // Add temporary password to context
        velocityContext.put("temporaryPassword", temporaryPassword);
        
        // Generate TOTP QR code URL
        String totpQrCodeUrl = TotpHelper.generateQrCodeUrl(
            user.getEmail(),
            user.getTotpKey(),
            "RidesIQ"
        );
        velocityContext.put("totpQrCodeUrl", totpQrCodeUrl);
        
        var fullMessage = textTemplateFormatter.formatMessage(velocityContext, "userOnboarding", "full");
        mailManager.sendMessage(user, true, fullMessage.getSubject(), fullMessage.getBody());
    }

    @Path("{id}")
    @DELETE
    public Response remove(@PathParam("id") long id) throws Exception {
        Response response = super.remove(id);
        if (getUserId() == id) {
            request.getSession().removeAttribute(SessionHelper.USER_ID_KEY);
        }
        return response;
    }

    @Path("totp")
    @PermitAll
    @POST
    public String generateTotpKey() throws StorageException {
        if (!permissionsService.getServer().getBoolean(Keys.WEB_TOTP_ENABLE.getKey())) {
            throw new SecurityException("One-time password is disabled");
        }
        return new GoogleAuthenticator().createCredentials().getKey();
    }

}
