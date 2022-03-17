/**
 * Copyright © 2016-2022 The Thingsboard Authors
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
package org.thingsboard.server.controller;

import org.jboss.aerogear.security.otp.Totp;
import org.jboss.aerogear.security.otp.api.Base32;
import org.junit.Before;
import org.junit.Test;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.boot.test.mock.mockito.SpyBean;
import org.springframework.web.util.UriComponents;
import org.springframework.web.util.UriComponentsBuilder;
import org.thingsboard.rule.engine.api.SmsService;
import org.thingsboard.server.service.security.auth.mfa.config.TwoFactorAuthSettings;
import org.thingsboard.server.service.security.auth.mfa.config.account.SmsTwoFactorAuthAccountConfig;
import org.thingsboard.server.service.security.auth.mfa.config.account.TotpTwoFactorAuthAccountConfig;
import org.thingsboard.server.service.security.auth.mfa.config.account.TwoFactorAuthAccountConfig;
import org.thingsboard.server.service.security.auth.mfa.config.provider.SmsTwoFactorAuthProviderConfig;
import org.thingsboard.server.service.security.auth.mfa.config.provider.TotpTwoFactorAuthProviderConfig;
import org.thingsboard.server.service.security.auth.mfa.config.provider.TwoFactorAuthProviderConfig;
import org.thingsboard.server.service.security.auth.mfa.provider.TwoFactorAuthProviderType;
import org.thingsboard.server.service.security.auth.mfa.provider.impl.TotpTwoFactorAuthProvider;

import java.util.Arrays;
import java.util.Collections;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

public abstract class TwoFactorAuthTest extends AbstractControllerTest {

    @SpyBean
    private TotpTwoFactorAuthProvider totpTwoFactorAuthProvider;
    @MockBean
    private SmsService smsService;

    @Before
    public void beforeEach() throws Exception {
        loginSysAdmin();
    }


    @Test
    public void testSaveTwoFaSettings() throws Exception {
        loginSysAdmin();
        testSaveTestTwoFaSettings();

        loginTenantAdmin();
        testSaveTestTwoFaSettings();
    }

    private void testSaveTestTwoFaSettings() throws Exception {
        TotpTwoFactorAuthProviderConfig totpTwoFaProviderConfig = new TotpTwoFactorAuthProviderConfig();
        totpTwoFaProviderConfig.setIssuerName("tb");
        SmsTwoFactorAuthProviderConfig smsTwoFaProviderConfig = new SmsTwoFactorAuthProviderConfig();
        smsTwoFaProviderConfig.setSmsVerificationMessageTemplate("${verificationCode}");

        saveProvidersConfigs(totpTwoFaProviderConfig, smsTwoFaProviderConfig);

        TwoFactorAuthSettings savedTwoFaSettings = readResponse(doGet("/api/2fa/settings").andExpect(status().isOk()), TwoFactorAuthSettings.class);

        assertThat(savedTwoFaSettings.getProviders()).hasSize(2);
        assertThat(savedTwoFaSettings.getProviders()).contains(totpTwoFaProviderConfig, smsTwoFaProviderConfig);
    }

    @Test
    public void testSaveTotpTwoFaProviderConfig_validationError() throws Exception {
        TotpTwoFactorAuthProviderConfig invalidTotpTwoFaProviderConfig = new TotpTwoFactorAuthProviderConfig();
        invalidTotpTwoFaProviderConfig.setIssuerName("   ");

        String errorResponse = saveTwoFaSettingsAndGetError(invalidTotpTwoFaProviderConfig);
        assertThat(errorResponse).containsIgnoringCase("issuer name must not be blank");
    }

    @Test
    public void testSaveSmsTwoFaProviderConfig_validationError() throws Exception {
        SmsTwoFactorAuthProviderConfig invalidSmsTwoFaProviderConfig = new SmsTwoFactorAuthProviderConfig();
        invalidSmsTwoFaProviderConfig.setSmsVerificationMessageTemplate("does not contain verification code");

        String errorResponse = saveTwoFaSettingsAndGetError(invalidSmsTwoFaProviderConfig);
        assertThat(errorResponse).containsIgnoringCase("must contain verification code");
    }

    private String saveTwoFaSettingsAndGetError(TwoFactorAuthProviderConfig invalidTwoFaProviderConfig) throws Exception {
        TwoFactorAuthSettings twoFaSettings = new TwoFactorAuthSettings();
        twoFaSettings.setProviders(Collections.singletonList(invalidTwoFaProviderConfig));

        return getErrorMessage(doPost("/api/2fa/settings", twoFaSettings)
                .andExpect(status().isBadRequest()));
    }

    @Test
    public void testSaveTwoFaAccountConfig_providerNotConfigured() throws Exception {
        configureSmsTwoFaProvider("${verificationCode}");

        loginTenantAdmin();

        TwoFactorAuthProviderType notConfiguredProviderType = TwoFactorAuthProviderType.TOTP;
        String errorMessage = getErrorMessage(doPost("/api/2fa/account/config/generate?providerType=" + notConfiguredProviderType)
                .andExpect(status().isBadRequest()));
        assertThat(errorMessage).containsIgnoringCase("provider is not configured");

        TotpTwoFactorAuthAccountConfig notConfiguredProviderAccountConfig = new TotpTwoFactorAuthAccountConfig();
        notConfiguredProviderAccountConfig.setAuthUrl("aba");
        errorMessage = getErrorMessage(doPost("/api/2fa/account/config/submit", notConfiguredProviderAccountConfig));
        assertThat(errorMessage).containsIgnoringCase("provider is not configured");
    }

    @Test
    public void testGenerateTotpTwoFaAccountConfig() throws Exception {
        TotpTwoFactorAuthProviderConfig totpTwoFaProviderConfig = configureTotpTwoFaProvider();

        loginTenantAdmin();

        assertThat(readResponse(doGet("/api/2fa/account/config").andExpect(status().isOk()), String.class)).isNullOrEmpty();
        generateTotpTwoFaAccountConfig(totpTwoFaProviderConfig);
    }

    @Test
    public void testSubmitTotpTwoFaAccountConfig() throws Exception {
        TotpTwoFactorAuthProviderConfig totpTwoFaProviderConfig = configureTotpTwoFaProvider();

        loginTenantAdmin();

        TotpTwoFactorAuthAccountConfig generatedTotpTwoFaAccountConfig = generateTotpTwoFaAccountConfig(totpTwoFaProviderConfig);
        doPost("/api/2fa/account/config/submit", generatedTotpTwoFaAccountConfig).andExpect(status().isOk());
        verify(totpTwoFactorAuthProvider).prepareVerificationCode(argThat(user -> user.getEmail().equals(TENANT_ADMIN_EMAIL)),
                eq(totpTwoFaProviderConfig), eq(generatedTotpTwoFaAccountConfig));
    }

    @Test
    public void testVerifyAndSaveTotpTwoFaAccountConfig() throws Exception {
        TotpTwoFactorAuthProviderConfig totpTwoFaProviderConfig = configureTotpTwoFaProvider();

        loginTenantAdmin();

        TotpTwoFactorAuthAccountConfig generatedTotpTwoFaAccountConfig = generateTotpTwoFaAccountConfig(totpTwoFaProviderConfig);

        String secret = UriComponentsBuilder.fromUriString(generatedTotpTwoFaAccountConfig.getAuthUrl()).build()
                .getQueryParams().getFirst("secret");
        String correctVerificationCode = new Totp(secret).now();

        doPost("/api/2fa/account/config?verificationCode=" + correctVerificationCode, generatedTotpTwoFaAccountConfig)
                .andExpect(status().isOk());

        TwoFactorAuthAccountConfig twoFaAccountConfig = readResponse(doGet("/api/2fa/account/config").andExpect(status().isOk()), TwoFactorAuthAccountConfig.class);
        assertThat(twoFaAccountConfig).isEqualTo(generatedTotpTwoFaAccountConfig);
    }

    @Test
    public void testVerifyAndSaveTotpTwoFaAccountConfig_incorrectVerificationCode() throws Exception {
        TotpTwoFactorAuthProviderConfig totpTwoFaProviderConfig = configureTotpTwoFaProvider();

        loginTenantAdmin();

        TotpTwoFactorAuthAccountConfig generatedTotpTwoFaAccountConfig = generateTotpTwoFaAccountConfig(totpTwoFaProviderConfig);

        String incorrectVerificationCode = "100000";
        String errorMessage = getErrorMessage(doPost("/api/2fa/account/config?verificationCode=" + incorrectVerificationCode, generatedTotpTwoFaAccountConfig)
                .andExpect(status().isBadRequest()));

        assertThat(errorMessage).containsIgnoringCase("verification code is incorrect");
    }

    private TotpTwoFactorAuthAccountConfig generateTotpTwoFaAccountConfig(TotpTwoFactorAuthProviderConfig totpTwoFaProviderConfig) throws Exception {
        TwoFactorAuthAccountConfig generatedTwoFaAccountConfig = readResponse(doPost("/api/2fa/account/config/generate?providerType=TOTP")
                .andExpect(status().isOk()), TwoFactorAuthAccountConfig.class);
        assertThat(generatedTwoFaAccountConfig).isInstanceOf(TotpTwoFactorAuthAccountConfig.class);

        assertThat(((TotpTwoFactorAuthAccountConfig) generatedTwoFaAccountConfig)).satisfies(accountConfig -> {
            UriComponents otpAuthUrl = UriComponentsBuilder.fromUriString(accountConfig.getAuthUrl()).build();
            assertThat(otpAuthUrl.getScheme()).isEqualTo("otpauth");
            assertThat(otpAuthUrl.getHost()).isEqualTo("totp");
            assertThat(otpAuthUrl.getQueryParams().getFirst("issuer")).isEqualTo(totpTwoFaProviderConfig.getIssuerName());
            assertThat(otpAuthUrl.getPath()).isEqualTo("/%s:%s", totpTwoFaProviderConfig.getIssuerName(), TENANT_ADMIN_EMAIL);
            assertThat(otpAuthUrl.getQueryParams().getFirst("secret")).satisfies(secretKey -> {
                assertDoesNotThrow(() -> Base32.decode(secretKey));
            });
        });
        return (TotpTwoFactorAuthAccountConfig) generatedTwoFaAccountConfig;
    }


    @Test
    public void testGetTwoFaAccountConfig_whenProviderNotConfigured() throws Exception {
        testVerifyAndSaveTotpTwoFaAccountConfig();
        assertThat(readResponse(doGet("/api/2fa/account/config").andExpect(status().isOk()),
                TotpTwoFactorAuthAccountConfig.class)).isNotNull();

        loginSysAdmin();

        saveProvidersConfigs();

        assertThat(readResponse(doGet("/api/2fa/account/config").andExpect(status().isOk()), String.class))
                .isNullOrEmpty();
    }

//    @Test
//    public void testSubmitSmsTwoFaAccountConfig() throws Exception {
//        String verificationMessageTemplate = "Here is your verification code: ${verificationCode}";
//        SmsTwoFactorAuthProviderConfig smsTwoFaProviderConfig = configureSmsTwoFaProvider(verificationMessageTemplate);
//
//        SmsTwoFactorAuthAccountConfig smsTwoFaAccountConfig = new SmsTwoFactorAuthAccountConfig();
//        smsTwoFaAccountConfig.setPhoneNumber("+38054159785");
//
//        String verificationCode = ""; ?
//
//        verify(smsService).sendSms(eq(tenantId), any(), argThat(phoneNumbers -> {
//            return phoneNumbers[0].equals(smsTwoFaAccountConfig.getPhoneNumber())
//        }), eq("Here is your verification code: " + verificationCode));
//    }



    private TotpTwoFactorAuthProviderConfig configureTotpTwoFaProvider() throws Exception {
        TotpTwoFactorAuthProviderConfig totpTwoFaProviderConfig = new TotpTwoFactorAuthProviderConfig();
        totpTwoFaProviderConfig.setIssuerName("tb");

        saveProvidersConfigs(totpTwoFaProviderConfig);
        return totpTwoFaProviderConfig;
    }

    private SmsTwoFactorAuthProviderConfig configureSmsTwoFaProvider(String verificationMessageTemplate) throws Exception {
        SmsTwoFactorAuthProviderConfig smsTwoFaProviderConfig = new SmsTwoFactorAuthProviderConfig();
        smsTwoFaProviderConfig.setSmsVerificationMessageTemplate(verificationMessageTemplate);

        saveProvidersConfigs(smsTwoFaProviderConfig);
        return smsTwoFaProviderConfig;
    }

    private void saveProvidersConfigs(TwoFactorAuthProviderConfig... providerConfigs) throws Exception {
        TwoFactorAuthSettings twoFaSettings = new TwoFactorAuthSettings();
        twoFaSettings.setProviders(Arrays.stream(providerConfigs).collect(Collectors.toList()));
        doPost("/api/2fa/settings", twoFaSettings).andExpect(status().isOk());
    }

}
