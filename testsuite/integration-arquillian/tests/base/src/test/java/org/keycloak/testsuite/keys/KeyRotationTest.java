/*
 * Copyright 2016 Red Hat, Inc. and/or its affiliates
 * and other contributors as indicated by the @author tags.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.keycloak.testsuite.keys;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.jboss.arquillian.graphene.page.Page;
import org.junit.Rule;
import org.junit.Test;
import org.keycloak.RSATokenVerifier;
import org.keycloak.common.VerificationException;
import org.keycloak.common.util.KeyUtils;
import org.keycloak.common.util.MultivaluedHashMap;
import org.keycloak.common.util.PemUtils;
import org.keycloak.keys.Attributes;
import org.keycloak.keys.KeyProvider;
import org.keycloak.keys.RsaKeyProviderFactory;
import org.keycloak.representations.UserInfo;
import org.keycloak.representations.idm.ClientRepresentation;
import org.keycloak.representations.idm.ComponentRepresentation;
import org.keycloak.representations.idm.KeysMetadataRepresentation;
import org.keycloak.representations.idm.RealmRepresentation;
import org.keycloak.storage.UserStorageProvider;
import org.keycloak.testsuite.AbstractKeycloakTest;
import org.keycloak.testsuite.AssertEvents;
import org.keycloak.testsuite.pages.AppPage;
import org.keycloak.testsuite.pages.AppPage.RequestType;
import org.keycloak.testsuite.pages.LoginPage;
import org.keycloak.testsuite.util.KeycloakModelUtils;
import org.keycloak.testsuite.util.OAuthClient;
import org.keycloak.testsuite.util.UserInfoClientUtil;

import javax.ws.rs.client.ClientBuilder;
import javax.ws.rs.core.Response;
import java.io.IOException;
import java.security.KeyPair;
import java.security.PublicKey;
import java.util.List;

import static org.junit.Assert.*;
import static org.keycloak.testsuite.admin.AbstractAdminTest.loadJson;

/**
 * @author <a href="mailto:sthorger@redhat.com">Stian Thorgersen</a>
 */
public class KeyRotationTest extends AbstractKeycloakTest {

    @Rule
    public AssertEvents events = new AssertEvents(this);

    @Page
    protected AppPage appPage;

    @Page
    protected LoginPage loginPage;

    @Override
    public void addTestRealms(List<RealmRepresentation> testRealms) {
        RealmRepresentation realm = loadJson(getClass().getResourceAsStream("/testrealm.json"), RealmRepresentation.class);
        testRealms.add(realm);

        ClientRepresentation confApp = KeycloakModelUtils.createClient(realm, "confidential-cli");
        confApp.setSecret("secret1");
        confApp.setServiceAccountsEnabled(Boolean.TRUE);
    }

    @Test
    public void testIdentityCookie() throws Exception {
        // Create keys #1
        createKeys1();

        // Login with keys #1
        loginPage.open();
        loginPage.login("test-user@localhost", "password");
        assertEquals(RequestType.AUTH_RESPONSE, appPage.getRequestType());

        // Create keys #2
        createKeys2();

        // Login again with cookie signed with old keys
        appPage.open();
        oauth.openLoginForm();
        assertEquals(RequestType.AUTH_RESPONSE, appPage.getRequestType());

        // Drop key #1
        dropKeys1();

        // Login again with key #1 dropped - should pass as cookie should be refreshed
        appPage.open();
        oauth.openLoginForm();
        assertEquals(RequestType.AUTH_RESPONSE, appPage.getRequestType());

        // Drop key #2
        dropKeys2();

        // Login again with key #2 dropped - should fail as cookie hasn't been refreshed
        appPage.open();
        oauth.openLoginForm();
        assertTrue(loginPage.isCurrent());
    }

    @Test
    public void testTokens() throws Exception {
        // Create keys #1
        PublicKey key1 = createKeys1();

        // Get token with keys #1
        oauth.doLogin("test-user@localhost", "password");
        OAuthClient.AccessTokenResponse response = oauth.doAccessTokenRequest(oauth.getCurrentQuery().get("code"), "password");
        assertEquals(200, response.getStatusCode());
        assertTokenSignature(key1, response.getAccessToken());
        assertTokenSignature(key1, response.getRefreshToken());

        // Userinfo with keys #1
        assertUserInfo(response.getAccessToken(), 200);

        // Token introspection with keys #1
        assertTokenIntrospection(response.getAccessToken(), true);

        // Create keys #2
        PublicKey key2 = createKeys2();

        // Refresh token with keys #2
        response = oauth.doRefreshTokenRequest(response.getRefreshToken(), "password");
        assertEquals(200, response.getStatusCode());
        assertTokenSignature(key2, response.getAccessToken());
        assertTokenSignature(key2, response.getRefreshToken());

        // Userinfo with keys #2
        assertUserInfo(response.getAccessToken(), 200);

        // Token introspection with keys #2
        assertTokenIntrospection(response.getAccessToken(), true);

        // Drop key #1
        dropKeys1();

        // Refresh token with keys #1 dropped - should pass as refresh token should be signed with key #2
        response = oauth.doRefreshTokenRequest(response.getRefreshToken(), "password");
        assertTokenSignature(key2, response.getAccessToken());
        assertTokenSignature(key2, response.getRefreshToken());

        // Userinfo with keys #1 dropped
        assertUserInfo(response.getAccessToken(), 200);

        // Token introspection with keys #1 dropped
        assertTokenIntrospection(response.getAccessToken(), true);

        // Drop key #2
        dropKeys2();

        // Userinfo with keys #2 dropped
        assertUserInfo(response.getAccessToken(), 401);

        // Token introspection with keys #2 dropped
        assertTokenIntrospection(response.getAccessToken(), false);

        // Refresh token with keys #2 dropped - should fail as refresh token is signed with key #2
        response = oauth.doRefreshTokenRequest(response.getRefreshToken(), "password");
        assertEquals(400, response.getStatusCode());
        assertEquals("Invalid refresh token", response.getErrorDescription());
    }

    @Test
    public void providerOrder() throws Exception {
        PublicKey keys1 = createKeys1();
        PublicKey keys2 = createKeys2();

        KeysMetadataRepresentation keyMetadata = adminClient.realm("test").keys().getKeyMetadata();
        assertEquals(PemUtils.encodeKey(keys2), keyMetadata.getKeys().get(0).getPublicKey());
    }

    @Test
    public void rotateKeys() throws InterruptedException {
        for (int i = 0; i < 10; i++) {
            String activeKid = adminClient.realm("test").keys().getKeyMetadata().getActive().get("RSA");

            // Rotate public keys on the parent broker
            String realmId = adminClient.realm("test").toRepresentation().getId();
            ComponentRepresentation keys = new ComponentRepresentation();
            keys.setName("generated" + i);
            keys.setProviderType(KeyProvider.class.getName());
            keys.setProviderId("rsa-generated");
            keys.setParentId(realmId);
            keys.setConfig(new MultivaluedHashMap<>());
            keys.getConfig().putSingle("priority", "1000" + i);
            Response response = adminClient.realm("test").components().add(keys);
            assertEquals(201, response.getStatus());
            response.close();

            String updatedActiveKid = adminClient.realm("test").keys().getKeyMetadata().getActive().get("RSA");
            assertNotEquals(activeKid, updatedActiveKid);
        }
    }


    static void assertTokenSignature(PublicKey expectedKey, String token) {
        String kid = null;
        try {
            RSATokenVerifier verifier = RSATokenVerifier.create(token).checkTokenType(false).checkRealmUrl(false).checkActive(false).publicKey(expectedKey);
            kid = verifier.getHeader().getKeyId();
            verifier.verify();
        } catch (VerificationException e) {
            fail("Token not signed by expected keys, kid was " + kid);
        }
    }


    private PublicKey createKeys1() throws Exception {
        return createKeys("1000");
    }

    private PublicKey createKeys2() throws Exception {
        return createKeys("2000");
    }

    private PublicKey createKeys(String priority) throws Exception {
        KeyPair keyPair = KeyUtils.generateRsaKeyPair(1024);
        String privateKeyPem = PemUtils.encodeKey(keyPair.getPrivate());
        PublicKey publicKey = keyPair.getPublic();

        ComponentRepresentation rep = new ComponentRepresentation();
        rep.setName("mycomponent");
        rep.setParentId("test");
        rep.setProviderId(RsaKeyProviderFactory.ID);
        rep.setProviderType(KeyProvider.class.getName());

        org.keycloak.common.util.MultivaluedHashMap config = new org.keycloak.common.util.MultivaluedHashMap();
        config.addFirst("priority", priority);
        config.addFirst(Attributes.PRIVATE_KEY_KEY, privateKeyPem);
        rep.setConfig(config);

        adminClient.realm("test").components().add(rep);

        return publicKey;
    }

    private void dropKeys1() {
        dropKeys("1000");
    }

    private void dropKeys2() {
        dropKeys("2000");
    }

    private void dropKeys(String priority) {
        for (ComponentRepresentation c : adminClient.realm("test").components().query("test", KeyProvider.class.getName())) {
            if (c.getConfig().getFirst("priority").equals(priority)) {
                adminClient.realm("test").components().component(c.getId()).remove();
                return;
            }
        }
        throw new RuntimeException("Failed to find keys1");
    }

    private void assertUserInfo(String token, int expectedStatus) {
        Response userInfoResponse = UserInfoClientUtil.executeUserInfoRequest_getMethod(ClientBuilder.newClient(), token);
        assertEquals(expectedStatus, userInfoResponse.getStatus());
        userInfoResponse.close();
    }

    private void assertTokenIntrospection(String token, boolean expectActive) {
        try {
            String tokenResponse = oauth.introspectAccessTokenWithClientCredential("confidential-cli", "secret1", token);
            ObjectMapper objectMapper = new ObjectMapper();
            JsonNode jsonNode = objectMapper.readTree(tokenResponse);
            assertEquals(expectActive, jsonNode.get("active").asBoolean());
        } catch (IOException e) {
            throw new RuntimeException(e);
        }

    }

}

