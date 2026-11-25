package com.tsanet.demo.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * The configured workspaces (tsanetgit/Connect_SDK#190), bound as one plain string and parsed in
 * {@link WorkspaceDefinitions}, so a bad value fails startup with a message that names the
 * variable and never echoes the value: it holds gate passwords and member credentials.
 *
 * @param definitions a JSON list of workspaces (TSANET_DEMO_WORKSPACES); blank means none, and the
 *                    demo then has only the default workspace, as before
 */
@ConfigurationProperties(prefix = "tsanet.demo.workspaces")
public record DemoWorkspacesProperties(String definitions) {

    public static final String VARIABLE = "TSANET_DEMO_WORKSPACES";

    /** Never the value: a record's default toString would print it. */
    @Override
    public String toString() {
        return "DemoWorkspacesProperties[definitions " + (definitions == null || definitions.isBlank() ? "unset" : "set") + "]";
    }
}
