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
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasSize;

import ca.uhn.fhir.context.FhirContext;
import com.google.common.io.Resources;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.hl7.fhir.r4.model.Coding;
import org.hl7.fhir.r4.model.Patient;
import org.junit.Test;

public class LocationTagMutatorTest {

  private static final FhirContext FHIR_CONTEXT = FhirContext.forR4();
  private static final String SUPERVISION_TAG_SYSTEM =
      "https://www.example.com/CodeSystem/supervision-location";

  @Test
  public void getLocationTags_readsNgsaSupervisionLocationTag() throws Exception {
    String json =
        Resources.toString(
            Resources.getResource("location/ngsa-patient.json"), StandardCharsets.UTF_8);
    Patient patient = FHIR_CONTEXT.newJsonParser().parseResource(Patient.class, json);

    List<Coding> tags = LocationTagMutator.getLocationTags(patient, SUPERVISION_TAG_SYSTEM);

    assertThat(tags, hasSize(1));
    assertThat(tags.get(0).getCode(), equalTo("cu-under-1908"));
  }

  @Test
  public void extractFacilityIdFromTags_acceptsBareId() {
    Coding tag = new Coding();
    tag.setSystem(SUPERVISION_TAG_SYSTEM);
    tag.setCode("853470");

    String id = LocationTagMutator.extractFacilityIdFromTags(List.of(tag));

    assertThat(id, equalTo("853470"));
  }

  @Test
  public void extractFacilityIdFromTags_acceptsLocationPrefix() {
    Coding tag = new Coding();
    tag.setSystem(SUPERVISION_TAG_SYSTEM);
    tag.setCode("Location/853470");

    String id = LocationTagMutator.extractFacilityIdFromTags(List.of(tag));

    assertThat(id, equalTo("853470"));
  }
}
