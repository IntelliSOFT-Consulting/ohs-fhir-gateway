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

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.instanceOf;
import static org.mockito.Mockito.when;

import ca.uhn.fhir.context.FhirContext;
import ca.uhn.fhir.parser.IParser;
import ca.uhn.fhir.rest.api.Constants;
import ca.uhn.fhir.rest.server.RestfulServer;
import ca.uhn.fhir.rest.server.exceptions.AuthenticationException;
import ca.uhn.fhir.rest.server.servlet.ServletRequestDetails;
import ca.uhn.fhir.rest.server.servlet.ServletRestfulResponse;
import org.hl7.fhir.instance.model.api.IBaseResource;
import org.hl7.fhir.r4.model.OperationOutcome;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnitRunner;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

@RunWith(MockitoJUnitRunner.class)
public class FhirGatewayExceptionHandlingInterceptorTest {

  private static final FhirContext FHIR_CONTEXT = FhirContext.forR4();

  private final FhirGatewayExceptionHandlingInterceptor interceptor =
      new FhirGatewayExceptionHandlingInterceptor();

  @Mock private RestfulServer serverMock;

  private ServletRequestDetails requestDetails;
  private MockHttpServletResponse servletResponse;

  @Before
  public void setUp() {
    when(serverMock.getFhirContext()).thenReturn(FHIR_CONTEXT);

    requestDetails = new ServletRequestDetails();
    requestDetails.setServer(serverMock);
    servletResponse = new MockHttpServletResponse();
    requestDetails.setServletResponse(servletResponse);

    MockHttpServletRequest servletRequest = new MockHttpServletRequest("GET", "/fhir/Patient");
    servletRequest.addHeader(Constants.HEADER_ACCEPT, Constants.CT_FHIR_JSON_NEW);
    requestDetails.setServletRequest(servletRequest);
    requestDetails.setResponse(new ServletRestfulResponse(requestDetails));
  }

  @Test
  public void authenticationExceptionReturnsOperationOutcomeJson() throws Exception {
    AuthenticationException exception =
        new AuthenticationException(
            "Failed to decode JWT: The token was expected to have 3 parts, but got 0.");

    interceptor.handleException(
        requestDetails, exception, requestDetails.getServletRequest(), servletResponse);

    assertThat(servletResponse.getStatus(), equalTo(401));
    assertThat(servletResponse.getContentType(), containsString("application/fhir+json"));

    IParser parser = FHIR_CONTEXT.newJsonParser();
    IBaseResource resource = parser.parseResource(servletResponse.getContentAsString());
    assertThat(resource, instanceOf(OperationOutcome.class));

    OperationOutcome outcome = (OperationOutcome) resource;
    assertThat(outcome.getIssueFirstRep().getCode(), equalTo(OperationOutcome.IssueType.SECURITY));
    assertThat(
        outcome.getIssueFirstRep().getDiagnostics(),
        equalTo("Failed to decode JWT: The token was expected to have 3 parts, but got 0."));
  }
}
