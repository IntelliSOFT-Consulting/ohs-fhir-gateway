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

import ca.uhn.fhir.rest.server.exceptions.AuthenticationException;
import com.auth0.jwt.JWT;
import com.auth0.jwt.JWTVerifier;
import com.auth0.jwt.algorithms.Algorithm;
import com.auth0.jwt.exceptions.JWTDecodeException;
import com.auth0.jwt.exceptions.JWTVerificationException;
import com.auth0.jwt.interfaces.DecodedJWT;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Preconditions;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.math.BigInteger;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.NoSuchAlgorithmException;
import java.security.interfaces.RSAPublicKey;
import java.security.spec.EncodedKeySpec;
import java.security.spec.InvalidKeySpecException;
import java.security.spec.RSAPublicKeySpec;
import java.security.spec.X509EncodedKeySpec;
import java.util.Base64;
import java.util.HashMap;
import java.util.Map;
import org.apache.http.HttpResponse;
import org.apache.http.util.EntityUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class TokenVerifier {

  private static final Logger logger = LoggerFactory.getLogger(TokenVerifier.class);
  private static final String TOKEN_ISSUER_ENV = "TOKEN_ISSUER";
  private static final String WELL_KNOWN_ENDPOINT_ENV = "WELL_KNOWN_ENDPOINT";
  private static final String KEYCLOAK_INTERNAL_BASE_URL_ENV = "KEYCLOAK_INTERNAL_BASE_URL";
  private static final String WELL_KNOWN_ENDPOINT_DEFAULT = ".well-known/openid-configuration";
  private static final String JWKS_CERTS_PATH = "/protocol/openid-connect/certs";
  public static final String BEARER_PREFIX = "Bearer ";

  // TODO: Make this configurable or based on the given JWT; we should at least support some other
  // RSA* and ES* algorithms (requires ECDSA512 JWT algorithm).
  private static final String SIGN_ALGORITHM = "RS256";

  private final String tokenIssuer;
  // Access to `verifierForIssuerKid` and `publicKeyForIssuerKid` should be non-concurrent.
  private final Map<String, JWTVerifier> verifierForIssuerKid;
  private final Map<String, RSAPublicKey> publicKeyForIssuerKid;
  private final HttpUtil httpUtil;
  private final String configJson;

  @VisibleForTesting
  TokenVerifier(String tokenIssuer, String wellKnownEndpoint, HttpUtil httpUtil)
      throws IOException {
    this.tokenIssuer = tokenIssuer;
    this.httpUtil = httpUtil;
    this.configJson = httpUtil.fetchWellKnownConfig(tokenIssuer, wellKnownEndpoint);
    this.verifierForIssuerKid = new HashMap<>();
    this.publicKeyForIssuerKid = new HashMap<>();
  }

  public static TokenVerifier createFromEnvVars() throws IOException {
    String tokenIssuer = System.getenv(TOKEN_ISSUER_ENV);
    if (tokenIssuer == null) {
      throw new IllegalArgumentException(
          String.format("The environment variable %s is not set!", TOKEN_ISSUER_ENV));
    }

    String wellKnownEndpoint = System.getenv(WELL_KNOWN_ENDPOINT_ENV);
    if (wellKnownEndpoint == null) {
      wellKnownEndpoint = WELL_KNOWN_ENDPOINT_DEFAULT;
      logger.info(
          String.format(
              "The environment variable %s is not set! Using default value of %s instead ",
              WELL_KNOWN_ENDPOINT_ENV, WELL_KNOWN_ENDPOINT_DEFAULT));
    }
    return new TokenVerifier(tokenIssuer, wellKnownEndpoint, new HttpUtil());
  }

  public String getWellKnownConfig() {
    return configJson;
  }

  private RSAPublicKey fetchAndDecodePublicKey(String realmMetadataUrl) throws IOException {
    // Preconditions.checkState(SIGN_ALGORITHM.equals("ES512"));
    Preconditions.checkState(SIGN_ALGORITHM.equals("RS256"));
    // final String keyAlgorithm = "EC";
    final String keyAlgorithm = "RSA";
    try {
      HttpResponse response = httpUtil.getResourceOrFail(new URI(realmMetadataUrl));
      JsonObject jsonObject =
          JsonParser.parseString(EntityUtils.toString(response.getEntity(), StandardCharsets.UTF_8))
              .getAsJsonObject();
      String keyStr = jsonObject.get("public_key").getAsString();
      if (keyStr == null) {
        ExceptionUtil.throwRuntimeExceptionAndLog(
            logger, "Cannot find 'public_key' in issuer metadata.");
      }
      KeyFactory keyFactory = KeyFactory.getInstance(keyAlgorithm);
      EncodedKeySpec keySpec = new X509EncodedKeySpec(Base64.getDecoder().decode(keyStr));
      return (RSAPublicKey) keyFactory.generatePublic(keySpec);
    } catch (URISyntaxException e) {
      ExceptionUtil.throwRuntimeExceptionAndLog(
          logger,
          "Error in token issuer URI " + realmMetadataUrl,
          e,
          AuthenticationException.class);
    } catch (NoSuchAlgorithmException e) {
      ExceptionUtil.throwRuntimeExceptionAndLog(
          logger, "Invalid algorithm " + keyAlgorithm, e, AuthenticationException.class);
    } catch (InvalidKeySpecException e) {
      ExceptionUtil.throwRuntimeExceptionAndLog(
          logger, "Invalid KeySpec: " + e.getMessage(), e, AuthenticationException.class);
    }
    // We should never get here, this is to keep the IDE happy!
    return null;
  }

  private RSAPublicKey fetchPublicKeyFromJwks(String jwksUrl, String kid) throws IOException {
    try {
      HttpResponse response = httpUtil.getResourceOrFail(new URI(jwksUrl));
      JsonObject jwks =
          JsonParser.parseString(EntityUtils.toString(response.getEntity(), StandardCharsets.UTF_8))
              .getAsJsonObject();
      JsonArray keys = jwks.getAsJsonArray("keys");
      if (keys == null) {
        return null;
      }
      for (JsonElement keyElement : keys) {
        JsonObject key = keyElement.getAsJsonObject();
        if (!key.has("kid") || !kid.equals(key.get("kid").getAsString())) {
          continue;
        }
        if (!"RSA".equals(key.get("kty").getAsString())) {
          continue;
        }
        if (key.has("use") && !"sig".equals(key.get("use").getAsString())) {
          continue;
        }
        if (!key.has("n") || !key.has("e")) {
          continue;
        }
        return decodeRsaPublicKeyFromJwk(key.get("n").getAsString(), key.get("e").getAsString());
      }
      logger.warn("No matching signing key found in JWKS {} for kid {}", jwksUrl, kid);
      return null;
    } catch (URISyntaxException e) {
      ExceptionUtil.throwRuntimeExceptionAndLog(
          logger, "Error in JWKS URI " + jwksUrl, e, AuthenticationException.class);
    } catch (NoSuchAlgorithmException | InvalidKeySpecException e) {
      ExceptionUtil.throwRuntimeExceptionAndLog(
          logger,
          String.format("Invalid JWKS key for kid %s: %s", kid, e.getMessage()),
          e,
          AuthenticationException.class);
    }
    return null;
  }

  private static RSAPublicKey decodeRsaPublicKeyFromJwk(
      String modulusBase64Url, String exponentBase64Url)
      throws NoSuchAlgorithmException, InvalidKeySpecException {
    BigInteger modulus = new BigInteger(1, base64UrlDecode(modulusBase64Url));
    BigInteger exponent = new BigInteger(1, base64UrlDecode(exponentBase64Url));
    RSAPublicKeySpec keySpec = new RSAPublicKeySpec(modulus, exponent);
    return (RSAPublicKey) KeyFactory.getInstance("RSA").generatePublic(keySpec);
  }

  private static byte[] base64UrlDecode(String value) {
    return Base64.getUrlDecoder().decode(value);
  }

  /**
   * Resolves the Keycloak realm metadata URL used to fetch the RSA public key.
   *
   * <p>In DEV mode, when the JWT {@code iss} claim uses the public hostname (e.g. {@code
   * https://ngsadev.example.com/realms/ngsa}) but {@code TOKEN_ISSUER} points at an internal
   * Keycloak base URL, fetch the key from {@code KEYCLOAK_INTERNAL_BASE_URL}/realms/{realm} so
   * signature verification uses the correct realm keys.
   */
  private String resolveRealmMetadataUrl(String jwtIssuer) {
    if (jwtIssuer.equals(tokenIssuer)) {
      return tokenIssuer;
    }
    if (FhirProxyServer.isDevMode()) {
      String realm = extractRealmName(jwtIssuer);
      String internalBase = System.getenv(KEYCLOAK_INTERNAL_BASE_URL_ENV);
      if (realm != null && internalBase != null && !internalBase.isBlank()) {
        String base =
            internalBase.endsWith("/")
                ? internalBase.substring(0, internalBase.length() - 1)
                : internalBase;
        return base + "/realms/" + realm;
      }
      logger.warn(
          "JWT issuer {} differs from TOKEN_ISSUER {}; using JWT issuer URL for public key fetch.",
          jwtIssuer,
          tokenIssuer);
    }
    return jwtIssuer;
  }

  private String resolveJwksUrl(String jwtIssuer) {
    return resolveRealmMetadataUrl(jwtIssuer) + JWKS_CERTS_PATH;
  }

  static String extractRealmName(String issuerUrl) {
    if (issuerUrl == null || issuerUrl.isBlank()) {
      return null;
    }
    String marker = "/realms/";
    int idx = issuerUrl.indexOf(marker);
    if (idx < 0) {
      return null;
    }
    String rest = issuerUrl.substring(idx + marker.length());
    int end = rest.indexOf('/');
    return end < 0 ? rest : rest.substring(0, end);
  }

  private static String cacheKey(String issuer, String kid) {
    return issuer + "#" + (kid == null ? "" : kid);
  }

  private synchronized void invalidateCachesForIssuer(String issuer) {
    verifierForIssuerKid.keySet().removeIf(key -> key.startsWith(issuer + "#"));
    publicKeyForIssuerKid.keySet().removeIf(key -> key.startsWith(issuer + "#"));
    logger.info("Invalidated cached JWT verification keys for issuer {}", issuer);
  }

  private synchronized RSAPublicKey getPublicKeyForIssuer(String jwtIssuer, String kid)
      throws IOException {
    String cacheEntryKey = cacheKey(jwtIssuer, kid);
    if (!publicKeyForIssuerKid.containsKey(cacheEntryKey)) {
      RSAPublicKey publicKey = null;
      if (kid != null && !kid.isBlank()) {
        publicKey = fetchPublicKeyFromJwks(resolveJwksUrl(jwtIssuer), kid);
      }
      if (publicKey == null) {
        publicKey = fetchAndDecodePublicKey(resolveRealmMetadataUrl(jwtIssuer));
      }
      publicKeyForIssuerKid.put(cacheEntryKey, publicKey);
    }
    return publicKeyForIssuerKid.get(cacheEntryKey);
  }

  private synchronized JWTVerifier getJwtVerifier(String issuer, String kid) throws IOException {
    if (!tokenIssuer.equals(issuer)) {
      if (FhirProxyServer.isDevMode()) {
        logger.warn(
            "Server run in DEV mode. JWT issuer {} differs from configured TOKEN_ISSUER {}.",
            issuer,
            tokenIssuer);
      } else {
        ExceptionUtil.throwRuntimeExceptionAndLog(
            logger,
            String.format("The token issuer %s does not match the expected token issuer", issuer),
            AuthenticationException.class);
        return null;
      }
    }
    String cacheEntryKey = cacheKey(issuer, kid);
    if (!verifierForIssuerKid.containsKey(cacheEntryKey)) {
      RSAPublicKey publicKey = getPublicKeyForIssuer(issuer, kid);
      verifierForIssuerKid.put(
          cacheEntryKey, JWT.require(Algorithm.RSA256(publicKey, null)).withIssuer(issuer).build());
    }
    return verifierForIssuerKid.get(cacheEntryKey);
  }

  @VisibleForTesting
  public DecodedJWT decodeAndVerifyBearerToken(String authHeader) {
    if (!authHeader.startsWith(BEARER_PREFIX)) {
      ExceptionUtil.throwRuntimeExceptionAndLog(
          logger,
          "Authorization header is not a valid Bearer token!",
          AuthenticationException.class);
    }
    String bearerToken = authHeader.substring(BEARER_PREFIX.length());
    DecodedJWT jwt = null;
    try {
      jwt = JWT.decode(bearerToken);
    } catch (JWTDecodeException e) {
      ExceptionUtil.throwRuntimeExceptionAndLog(
          logger, "Failed to decode JWT: " + e.getMessage(), e, AuthenticationException.class);
    }
    String issuer = jwt.getIssuer();
    String kid = jwt.getKeyId();
    String algorithm = jwt.getAlgorithm();
    logger.info(
        String.format(
            "JWT issuer is %s, audience is %s, algorithm is %s, kid is %s",
            issuer, jwt.getAudience(), algorithm, kid));

    if (!SIGN_ALGORITHM.equals(algorithm)) {
      ExceptionUtil.throwRuntimeExceptionAndLog(
          logger,
          String.format(
              "Only %s signing algorithm is supported, got %s", SIGN_ALGORITHM, algorithm),
          AuthenticationException.class);
    }

    try {
      return verifyJwtWithRetry(jwt, issuer, kid, false);
    } catch (IOException e) {
      ExceptionUtil.throwRuntimeExceptionAndLog(
          logger,
          String.format("Failed to fetch public key for issuer %s: %s", issuer, e.getMessage()),
          e,
          AuthenticationException.class);
      return null;
    }
  }

  private DecodedJWT verifyJwtWithRetry(
      DecodedJWT jwt, String issuer, String kid, boolean alreadyRetried) throws IOException {
    JWTVerifier jwtVerifier = getJwtVerifier(issuer, kid);
    try {
      return jwtVerifier.verify(jwt);
    } catch (JWTVerificationException e) {
      if (!alreadyRetried) {
        logger.warn(
            "JWT signature verification failed for issuer {} kid {}; refreshing keys and retrying"
                + " once.",
            issuer,
            kid);
        invalidateCachesForIssuer(issuer);
        return verifyJwtWithRetry(jwt, issuer, kid, true);
      }
      ExceptionUtil.throwRuntimeExceptionAndLog(
          logger,
          String.format("JWT verification failed with error: %s", e.getMessage()),
          e,
          AuthenticationException.class);
      return null;
    }
  }
}
