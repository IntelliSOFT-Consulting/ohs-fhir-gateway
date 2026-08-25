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
import ca.uhn.fhir.rest.server.exceptions.BaseServerResponseException;
import ca.uhn.fhir.rest.server.exceptions.ForbiddenOperationException;
import org.hl7.fhir.instance.model.api.IBaseOperationOutcome;
import org.hl7.fhir.r4.model.OperationOutcome;

/** Builds FHIR R4 OperationOutcome resources for gateway error responses. */
public final class OperationOutcomeBuilder {

  private OperationOutcomeBuilder() {}

  public static IBaseOperationOutcome build(BaseServerResponseException exception) {
    OperationOutcome outcome = new OperationOutcome();
    OperationOutcome.OperationOutcomeIssueComponent issue = outcome.addIssue();
    issue.setSeverity(OperationOutcome.IssueSeverity.ERROR);
    issue.setCode(issueTypeFor(exception));
    issue.setDiagnostics(exception.getMessage());
    return outcome;
  }

  private static OperationOutcome.IssueType issueTypeFor(BaseServerResponseException exception) {
    if (exception instanceof AuthenticationException) {
      return OperationOutcome.IssueType.SECURITY;
    }
    if (exception instanceof ForbiddenOperationException) {
      return OperationOutcome.IssueType.FORBIDDEN;
    }
    if (exception.getStatusCode() >= 500) {
      return OperationOutcome.IssueType.EXCEPTION;
    }
    return OperationOutcome.IssueType.PROCESSING;
  }
}
