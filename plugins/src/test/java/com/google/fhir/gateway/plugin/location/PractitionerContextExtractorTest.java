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
package com.google.fhir.gateway.plugin.location;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.equalTo;

import ca.uhn.fhir.context.FhirContext;
import com.google.common.io.Resources;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import org.hl7.fhir.r4.model.Bundle;
import org.junit.Test;

public class PractitionerContextExtractorTest {

  private static final FhirContext FHIR_CONTEXT = FhirContext.forR4();

  @Test
  public void extractAllFromPractitionerRoleBundle_unionsMultiCuLocations() throws IOException {
    String json =
        Resources.toString(
            Resources.getResource("location/ngsa-practitioner-roles.json"), StandardCharsets.UTF_8);
    Bundle bundle = FHIR_CONTEXT.newJsonParser().parseResource(Bundle.class, json);

    PractitionerContextExtractor.PractitionerRoleContext ctx =
        PractitionerContextExtractor.extractAllFromPractitionerRoleBundle(bundle);

    assertThat(ctx.role(), equalTo("community-health-assistant"));
    assertThat(ctx.assignedLocationIds(), contains("cu-1", "cu-2"));
  }
}
