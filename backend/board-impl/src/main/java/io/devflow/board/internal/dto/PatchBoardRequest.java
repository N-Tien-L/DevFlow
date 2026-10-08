package io.devflow.board.internal.dto;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.LinkedHashSet;
import java.util.Set;

/** PATCH DTO that distinguishes an omitted property from an explicit JSON null. */
public final class PatchBoardRequest {

    private String name;
    private String description;
    private Boolean archived;
    private final Set<String> suppliedFields = new LinkedHashSet<>();
    private final Set<String> unknownFields = new LinkedHashSet<>();

    @JsonProperty("name")
    public void setName(String name) {
        suppliedFields.add("name");
        this.name = name;
    }

    @JsonProperty("description")
    public void setDescription(String description) {
        suppliedFields.add("description");
        this.description = description;
    }

    @JsonProperty("archived")
    public void setArchived(Boolean archived) {
        suppliedFields.add("archived");
        this.archived = archived;
    }

    @JsonAnySetter
    public void addUnknownField(String name, Object value) {
        unknownFields.add(name);
    }

    public String name() {
        return name;
    }

    public String description() {
        return description;
    }

    public Boolean archived() {
        return archived;
    }

    public boolean hasName() {
        return suppliedFields.contains("name");
    }

    public boolean hasDescription() {
        return suppliedFields.contains("description");
    }

    public boolean hasArchived() {
        return suppliedFields.contains("archived");
    }

    public Set<String> suppliedFields() {
        return Set.copyOf(suppliedFields);
    }

    public Set<String> unknownFields() {
        return Set.copyOf(unknownFields);
    }
}
