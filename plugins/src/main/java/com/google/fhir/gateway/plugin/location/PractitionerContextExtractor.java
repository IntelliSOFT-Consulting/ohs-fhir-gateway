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

import com.google.common.base.Preconditions;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import org.hl7.fhir.r4.model.Bundle;
import org.hl7.fhir.r4.model.CodeableConcept;
import org.hl7.fhir.r4.model.Coding;
import org.hl7.fhir.r4.model.PractitionerRole;
import org.hl7.fhir.r4.model.Reference;

/** Extracts role and assigned location from a PractitionerRole resource. */
final class PractitionerContextExtractor {

  private PractitionerContextExtractor() {}

  /**
   * Extracts the role code from the first PractitionerRole in the bundle. Uses the first coding in
   * the first code element.
   */
  static String extractRoleFromPractitionerRole(Bundle bundle) {
    Preconditions.checkNotNull(bundle, "bundle");
    if (bundle.getEntry().isEmpty()) {
      throw new IllegalArgumentException("No PractitionerRole found for practitioner");
    }
    PractitionerRole role = (PractitionerRole) bundle.getEntry().get(0).getResource();
    List<CodeableConcept> codes = role.getCode();
    if (codes.isEmpty()) {
      throw new IllegalArgumentException("PractitionerRole has no code");
    }
    List<Coding> codings = codes.get(0).getCoding();
    if (codings.isEmpty()) {
      throw new IllegalArgumentException("PractitionerRole code has no coding");
    }
    String code = codings.get(0).getCode();
    if (code == null || code.isEmpty()) {
      throw new IllegalArgumentException("PractitionerRole coding has empty code");
    }
    return code;
  }

  /**
   * Extracts the primary location ID from the first PractitionerRole in the bundle. Returns the
   * bare ID (strips "Location/" prefix if present).
   */
  static String extractPrimaryLocationIdFromPractitionerRole(Bundle bundle) {
    Preconditions.checkNotNull(bundle, "bundle");
    if (bundle.getEntry().isEmpty()) {
      throw new IllegalArgumentException("No PractitionerRole found for practitioner");
    }
    PractitionerRole role = (PractitionerRole) bundle.getEntry().get(0).getResource();
    List<Reference> locations = role.getLocation();
    if (locations.isEmpty()) {
      throw new IllegalArgumentException("PractitionerRole has no location");
    }
    String ref = locations.get(0).getReference();
    if (ref == null) {
      throw new IllegalArgumentException("PractitionerRole location reference is null");
    }
    if (ref.startsWith("Location/")) {
      return ref.substring("Location/".length());
    }
    return ref;
  }

  /**
   * Extracts role and all assigned location IDs from every PractitionerRole in the bundle. Role is
   * taken from the first entry; locations are unioned across all entries.
   */
  static PractitionerRoleContext extractAllFromPractitionerRoleBundle(Bundle bundle) {
    Preconditions.checkNotNull(bundle, "bundle");
    if (bundle.getEntry().isEmpty()) {
      throw new IllegalArgumentException("No PractitionerRole found for practitioner");
    }

    String role = null;
    Set<String> locationIds = new LinkedHashSet<>();
    for (Bundle.BundleEntryComponent entry : bundle.getEntry()) {
      if (!(entry.getResource() instanceof PractitionerRole)) {
        continue;
      }
      PractitionerRole pr = (PractitionerRole) entry.getResource();
      if (role == null) {
        List<CodeableConcept> codes = pr.getCode();
        if (codes.isEmpty()) {
          throw new IllegalArgumentException("PractitionerRole has no code");
        }
        List<Coding> codings = codes.get(0).getCoding();
        if (codings.isEmpty()) {
          throw new IllegalArgumentException("PractitionerRole code has no coding");
        }
        String code = codings.get(0).getCode();
        if (code == null || code.isEmpty()) {
          throw new IllegalArgumentException("PractitionerRole coding has empty code");
        }
        role = code;
      }
      for (Reference locationRef : pr.getLocation()) {
        String ref = locationRef.getReference();
        if (ref == null) {
          continue;
        }
        if (ref.startsWith("Location/")) {
          locationIds.add(ref.substring("Location/".length()));
        } else {
          locationIds.add(ref);
        }
      }
    }

    if (role == null) {
      throw new IllegalArgumentException("No PractitionerRole found for practitioner");
    }
    if (locationIds.isEmpty()) {
      throw new IllegalArgumentException("PractitionerRole has no location");
    }

    return new PractitionerRoleContext(role, new ArrayList<>(locationIds));
  }

  /** Role plus all assigned location IDs for a practitioner. */
  static final class PractitionerRoleContext {
    private final String role;
    private final List<String> assignedLocationIds;

    PractitionerRoleContext(String role, List<String> assignedLocationIds) {
      this.role = role;
      this.assignedLocationIds = assignedLocationIds;
    }

    String role() {
      return role;
    }

    List<String> assignedLocationIds() {
      return assignedLocationIds;
    }
  }
}
