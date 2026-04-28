package dev.abramenka.aggregation.enrichment.beneficialowners;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

class EntityNumbersExtractorTest {

    private final JsonMapper objectMapper = JsonMapper.builder().build();

    @Test
    void childNumbers_collectsPrincipalOwnersBeforeIndirectOwnersInFirstSeenOrderAndDeduplicates() {
        ObjectNode owner = json("""
            {
              "entity": {
                "ownershipStructure": [
                  {
                    "principalOwners": [
                      {"memberDetails": {"number": "P-1"}},
                      {"memberDetails": {"number": "P-2"}}
                    ],
                    "indirectOwners": [
                      {"memberDetails": {"number": "I-1"}},
                      {"memberDetails": {"number": "P-2"}}
                    ]
                  },
                  {
                    "principalOwners": [
                      {"memberDetails": {"number": "P-1"}},
                      {"memberDetails": {"number": "P-3"}}
                    ],
                    "indirectOwners": [
                      {"memberDetails": {"number": "I-2"}}
                    ]
                  }
                ]
              }
            }
            """);

        assertThat(new ArrayList<>(EntityNumbersExtractor.childNumbers(owner)))
                .containsExactly("P-1", "P-2", "I-1", "P-3", "I-2");
    }

    @Test
    void childNumbers_ignoresMissingBlankAndNonArrayOwnerEntries() {
        ObjectNode owner = json("""
            {
              "entity": {
                "ownershipStructure": [
                  {
                    "principalOwners": {},
                    "indirectOwners": [
                      {},
                      {"memberDetails": {}},
                      {"memberDetails": {"number": "  "}},
                      {"memberDetails": {"number": "I-1"}}
                    ]
                  },
                  {
                    "principalOwners": [
                      {"memberDetails": {"number": "P-1"}}
                    ]
                  }
                ]
              }
            }
            """);

        assertThat(new ArrayList<>(EntityNumbersExtractor.childNumbers(owner))).containsExactly("I-1", "P-1");
    }

    @Test
    void ownerNumber_prefersIndividualNumberOverEntityNumber() {
        ObjectNode owner = json("""
            {
              "individual": {
                "number": "I-1"
              },
              "entity": {
                "number": "E-1"
              }
            }
            """);

        assertThat(EntityNumbersExtractor.ownerNumber(owner)).isEqualTo("I-1");
    }

    @Test
    void ownerNumber_fallsBackToEntityNumber() {
        ObjectNode owner = json("""
            {
              "individual": {
                "number": "  "
              },
              "entity": {
                "number": "E-1"
              }
            }
            """);

        assertThat(EntityNumbersExtractor.ownerNumber(owner)).isEqualTo("E-1");
    }

    private ObjectNode json(String raw) {
        try {
            return (ObjectNode) objectMapper.readTree(raw);
        } catch (Exception ex) {
            throw new IllegalArgumentException(ex);
        }
    }
}
