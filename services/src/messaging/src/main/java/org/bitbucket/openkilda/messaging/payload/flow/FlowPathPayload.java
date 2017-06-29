package org.bitbucket.openkilda.messaging.payload.flow;

import static com.google.common.base.MoreObjects.toStringHelper;
import static org.bitbucket.openkilda.messaging.Utils.FLOW_ID;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;
import com.fasterxml.jackson.databind.annotation.JsonSerialize;

import java.io.Serializable;
import java.util.List;
import java.util.Objects;

/**
 * Flow path representation class.
 */
@JsonSerialize
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonPropertyOrder({
        FLOW_ID,
        "flowpath"})
public class FlowPathPayload implements Serializable {
    /**
     * Serialization version number constant.
     */
    private static final long serialVersionUID = 1L;

    /**
     * The id of the flow.
     */
    @JsonProperty(FLOW_ID)
    protected String id;

    /**
     * The path of the flow.
     */
    @JsonProperty("flowpath")
    protected List<String> path;

    /**
     * Instance constructor.
     *
     * @param id   flow id
     * @param path flow path
     * @throws IllegalArgumentException if flow id or flow path is null or empty
     */
    @JsonCreator
    public FlowPathPayload(@JsonProperty(FLOW_ID) final String id,
                           @JsonProperty("flowpath") final List<String> path) {
        setFlowName(id);
        setPath(path);
    }

    /**
     * Returns id of the flow.
     *
     * @return id of the flow
     */
    public String getId() {
        return id;
    }

    /**
     * Sets id of the flow.
     *
     * @param id id of the flow
     */
    public void setFlowName(String id) {
        if (id == null || id.isEmpty()) {
            throw new IllegalArgumentException("need to set id");
        }
        this.id = id;
    }

    /**
     * Returns path of the flow.
     *
     * @return path of the flow
     */
    public List<String> getPath() {
        return path;
    }

    /**
     * Sets path of the flow.
     *
     * @param path path of the flow
     */
    public void setPath(final List<String> path) {
        if (path == null || path.isEmpty()) {
            throw new IllegalArgumentException("need to set path");
        }
        this.path = path;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public String toString() {
        return toStringHelper(this)
                .add(FLOW_ID, id)
                .add("flowpath", path)
                .toString();
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public int hashCode() {
        return Objects.hash(id, path);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public boolean equals(Object object) {
        if (this == object) {
            return true;
        }
        if (object == null || getClass() != object.getClass()) {
            return false;
        }

        FlowPathPayload that = (FlowPathPayload) object;
        return Objects.equals(getId(), that.getId())
                && Objects.equals(getPath(), that.getPath());
    }
}