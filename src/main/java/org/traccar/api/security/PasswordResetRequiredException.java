
package org.traccar.api.security;

public class PasswordResetRequiredException extends SecurityException {
    public PasswordResetRequiredException() {
        super("Password reset required");
    }
}
