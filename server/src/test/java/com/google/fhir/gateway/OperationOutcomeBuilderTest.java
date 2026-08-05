/*
 * Copyright 2021-2025 Google LLC
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
import static org.hamcrest.Matchers.equalTo;

import ca.uhn.fhir.rest.server.exceptions.AuthenticationException;
import ca.uhn.fhir.rest.server.exceptions.ForbiddenOperationException;
import ca.uhn.fhir.rest.server.exceptions.InternalErrorException;
import org.hl7.fhir.instance.model.api.IBaseOperationOutcome;
import org.hl7.fhir.r4.model.OperationOutcome;
import org.junit.Test;

public class OperationOutcomeBuilderTest {

  @Test
  public void authenticationExceptionUsesSecurityIssueCode() {
    IBaseOperationOutcome outcome =
        OperationOutcomeBuilder.build(
            new AuthenticationException("No Authorization header provided!"));

    OperationOutcome parsed = (OperationOutcome) outcome;
    assertThat(parsed.getIssueFirstRep().getCode(), equalTo(OperationOutcome.IssueType.SECURITY));
    assertThat(
        parsed.getIssueFirstRep().getDiagnostics(), equalTo("No Authorization header provided!"));
  }

  @Test
  public void forbiddenExceptionUsesForbiddenIssueCode() {
    IBaseOperationOutcome outcome =
        OperationOutcomeBuilder.build(new ForbiddenOperationException("User is not authorized"));

    OperationOutcome parsed = (OperationOutcome) outcome;
    assertThat(parsed.getIssueFirstRep().getCode(), equalTo(OperationOutcome.IssueType.FORBIDDEN));
  }

  @Test
  public void serverErrorUsesExceptionIssueCode() {
    IBaseOperationOutcome outcome =
        OperationOutcomeBuilder.build(new InternalErrorException("Unexpected failure"));

    OperationOutcome parsed = (OperationOutcome) outcome;
    assertThat(parsed.getIssueFirstRep().getCode(), equalTo(OperationOutcome.IssueType.EXCEPTION));
  }
}
