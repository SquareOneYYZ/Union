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
package org.traccar.helper;

import java.io.UnsupportedEncodingException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

public final class TotpHelper {

    private TotpHelper() {
    }

    public static String generateQrCodeUrl(String accountName, String secret, String issuer) {
        try {
            String encodedAccount = URLEncoder.encode(accountName, StandardCharsets.UTF_8.name());
            String encodedIssuer = URLEncoder.encode(issuer, StandardCharsets.UTF_8.name());
            return String.format(
         "https://chart.googleapis.com/chart?chs=200x200&chld=M|0&cht=qr&chl=otpauth://totp/%s:%s?secret=%s&issuer=%s",
                encodedIssuer,
                encodedAccount,
                secret,
                encodedIssuer
            );
        } catch (UnsupportedEncodingException e) {
            throw new RuntimeException("UTF-8 encoding not supported", e);
        }
    }

    public static String generateProvisioningUri(String accountName, String secret, String issuer) {
        try {
            String encodedAccount = URLEncoder.encode(accountName, StandardCharsets.UTF_8.name());
            String encodedIssuer = URLEncoder.encode(issuer, StandardCharsets.UTF_8.name());
            return String.format(
                "otpauth://totp/%s:%s?secret=%s&issuer=%s",
                encodedIssuer,
                encodedAccount,
                secret,
                encodedIssuer
            );
        } catch (UnsupportedEncodingException e) {
            throw new RuntimeException("UTF-8 encoding not supported", e);
        }
    }
}
