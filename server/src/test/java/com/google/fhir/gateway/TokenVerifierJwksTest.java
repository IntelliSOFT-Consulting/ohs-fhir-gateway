/*
 * Copyright 2021-2026 Google LLC
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *       http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.google.fhir.gateway;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

import com.auth0.jwt.JWT;
import com.auth0.jwt.JWTCreator;
import com.auth0.jwt.algorithms.Algorithm;
import com.google.common.base.Preconditions;
import com.google.common.io.Resources;
import java.io.IOException;
import java.net.URI;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.util.Base64;
import org.apache.http.HttpResponse;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Answers;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.junit.MockitoJUnitRunner;

@RunWith(MockitoJUnitRunner.class)
public class TokenVerifierJwksTest {

  private static final String TOKEN_ISSUER = "https://token.issuer";
  private static final String TEST_KID = "test-signing-key";

  @Mock private HttpUtil httpUtilMock;
  private KeyPair keyPair;
  private TokenVerifier testInstance;

  @Before
  public void setUp() throws IOException {
    keyPair = generateKeyPair();
    RSAPublicKey publicKey = (RSAPublicKey) keyPair.getPublic();
    String modulus =
        Base64.getUrlEncoder()
            .withoutPadding()
            .encodeToString(stripLeadingZero(publicKey.getModulus().toByteArray()));
    String exponent =
        Base64.getUrlEncoder()
            .withoutPadding()
            .encodeToString(stripLeadingZero(publicKey.getPublicExponent().toByteArray()));
    String jwksJson =
        String.format(
            "{\"keys\":[{\"kid\":\"%s\",\"kty\":\"RSA\",\"alg\":\"RS256\",\"use\":\"sig\",\"n\":\"%s\",\"e\":\"%s\"}]}",
            TEST_KID, modulus, exponent);

    HttpResponse responseMock = Mockito.mock(HttpResponse.class, Answers.RETURNS_DEEP_STUBS);
    when(httpUtilMock.getResourceOrFail(any(URI.class)))
        .thenAnswer(
            invocation -> {
              URI uri = invocation.getArgument(0);
              if (uri.toString().endsWith("/protocol/openid-connect/certs")) {
                TestUtil.setUpFhirResponseMock(responseMock, jwksJson);
              } else {
                TestUtil.setUpFhirResponseMock(
                    responseMock, "{public_key: 'unused-for-this-test'}");
              }
              return responseMock;
            });
    URL idpUrl = Resources.getResource("idp_keycloak_config.json");
    String testIdpConfig = Resources.toString(idpUrl, StandardCharsets.UTF_8);
    when(httpUtilMock.fetchWellKnownConfig(anyString(), anyString())).thenReturn(testIdpConfig);
    testInstance = new TokenVerifier(TOKEN_ISSUER, "test", httpUtilMock);
  }

  @Test
  public void decodeAndVerifyBearerTokenUsesJwksKid() {
    JWTCreator.Builder jwtBuilder = JWT.create().withIssuer(TOKEN_ISSUER).withKeyId(TEST_KID);
    Algorithm algorithm =
        Algorithm.RSA256((RSAPublicKey) keyPair.getPublic(), (RSAPrivateKey) keyPair.getPrivate());
    testInstance.decodeAndVerifyBearerToken("Bearer " + jwtBuilder.sign(algorithm));
  }

  private static KeyPair generateKeyPair() {
    try {
      KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
      generator.initialize(2048);
      return generator.generateKeyPair();
    } catch (GeneralSecurityException e) {
      Preconditions.checkState(false);
      return null;
    }
  }

  private static byte[] stripLeadingZero(byte[] bytes) {
    if (bytes.length > 1 && bytes[0] == 0) {
      byte[] trimmed = new byte[bytes.length - 1];
      System.arraycopy(bytes, 1, trimmed, 0, trimmed.length);
      return trimmed;
    }
    return bytes;
  }
}
