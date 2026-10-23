package io.devflow.board.internal.dto;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import com.fasterxml.jackson.annotation.JsonProperty;
import io.devflow.board.api.ColumnStatusCategory;
import java.util.LinkedHashSet;
import java.util.Set;

/** PATCH DTO that tracks property presence and rejects mass-assigned relations. */
public final class PatchColumnRequest {

    private String name;
    private ColumnStatusCategory statusCategory;
    private final Set<String> suppliedFields = new LinkedHashSet<>();
    private final Set<String> unknownFields = new LinkedHashSet<>();

    @JsonProperty("name")
    public void setName(String name) {
        suppliedFields.add("name");
        this.name = name;
    }

    @JsonProperty("statusCategory")
    public void setStatusCategory(ColumnStatusCategory statusCategory) {
        suppliedFields.add("statusCategory");
        this.statusCategory = statusCategory;
    }

    @JsonAnySetter
    public void addUnknownField(String name, Object value) {
        unknownFields.add(name);
    }

    public String name() {
        return name;
    }

    public ColumnStatusCategory statusCategory() {
        return statusCategory;
    }

    public boolean hasName() {
        return suppliedFields.contains("name");
    }

    public boolean hasStatusCategory() {
        return suppliedFields.contains("statusCategory");
    }

    public Set<String> suppliedFields() {
        return Set.copyOf(suppliedFields);
    }

    public Set<String> unknownFields() {
        return Set.copyOf(unknownFields);
    }
}
