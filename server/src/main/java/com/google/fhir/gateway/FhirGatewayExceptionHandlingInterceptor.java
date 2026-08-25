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

import ca.uhn.fhir.interceptor.api.Hook;
import ca.uhn.fhir.interceptor.api.Interceptor;
import ca.uhn.fhir.interceptor.api.Pointcut;
import ca.uhn.fhir.parser.DataFormatException;
import ca.uhn.fhir.rest.api.server.RequestDetails;
import ca.uhn.fhir.rest.server.exceptions.BaseServerResponseException;
import ca.uhn.fhir.rest.server.exceptions.InternalErrorException;
import ca.uhn.fhir.rest.server.exceptions.InvalidRequestException;
import ca.uhn.fhir.rest.server.interceptor.ExceptionHandlingInterceptor;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;

/**
 * Ensures gateway errors are returned as FHIR OperationOutcome JSON instead of plain text.
 *
 * <p>HAPI writes plain-text bodies for authentication failures unless an exception-handling
 * interceptor handles the response. This interceptor also maps HTTP error types to appropriate FHIR
 * issue codes (security, forbidden, etc.).
 */
@Interceptor
public class FhirGatewayExceptionHandlingInterceptor extends ExceptionHandlingInterceptor {

  @Hook(Pointcut.SERVER_HANDLE_EXCEPTION)
  @Override
  public boolean handleException(
      RequestDetails theRequestDetails,
      BaseServerResponseException theException,
      HttpServletRequest theRequest,
      HttpServletResponse theResponse)
      throws ServletException, IOException {
    attachOperationOutcomeIfAbsent(theException);
    return super.handleException(theRequestDetails, theException, theRequest, theResponse);
  }

  @Hook(Pointcut.SERVER_PRE_PROCESS_OUTGOING_EXCEPTION)
  @Override
  public BaseServerResponseException preProcessOutgoingException(
      RequestDetails theRequestDetails,
      Throwable theException,
      HttpServletRequest theServletRequest)
      throws ServletException {
    BaseServerResponseException converted = convertToServerResponseException(theException);
    attachOperationOutcomeIfAbsent(converted);
    return converted;
  }

  private static BaseServerResponseException convertToServerResponseException(Throwable exception) {
    if (exception instanceof DataFormatException) {
      return new InvalidRequestException(exception);
    }
    if (exception instanceof BaseServerResponseException) {
      return (BaseServerResponseException) exception;
    }
    return new InternalErrorException(exception);
  }

  private static void attachOperationOutcomeIfAbsent(BaseServerResponseException exception) {
    if (exception.getOperationOutcome() != null) {
      return;
    }
    exception.setOperationOutcome(OperationOutcomeBuilder.build(exception));
  }
}
