package com.tsanet.demo.config;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Reads {@code TSANET_DEMO_WORKSPACES} (tsanetgit/Connect_SDK#190): a JSON list with one object per
 * workspace.
 * <pre>[{"name": "team-a", "username": "...", "password": "...",
 *   "seeds": {"beta": {"username": "...", "password": "..."},
 *             "dev": {"clientId": "...", "clientSecret": "..."}},
 *   "e2eePartners": {"beta": {"<company id>": {"jwksUrl": "https://keys.partner.example/.well-known/jwks.json"}}}}]</pre>
 * {@code name}, {@code username} and {@code password} are required, and the username holds no colon,
 * which a Basic login cannot carry. {@code seeds} holds, by
 * environment key, the member credentials written into that environment's empty store at startup:
 * a username and password, or a client id and client secret. {@code e2eePartners} holds, by
 * environment key, a partner key table in the shape {@link PartnerKeysStore} reads.
 *
 * <p>The value holds gate passwords and member credentials, so nothing here echoes any of it. A
 * value that cannot be used fails startup with a message that names the variable, the entry and the
 * rule. The message carries no cause, because the JSON parser's own message quotes what it read.
 * Names it does print (a workspace name, an environment key, a field) are printed only once they
 * are known to be plain words.
 */
final class WorkspaceDefinitions {

    /** One configured workspace, checked. {@code partnerSeeds} is JSON text by environment key. */
    record Definition(String name, Workspaces.Login login, Map<String, CredentialsStore.Credentials> seeds,
                      Map<String, String> partnerSeeds) {
    }

    // A name is a directory under the data dir, and file systems that fold case would merge two
    // names that differ by case alone, so upper case is refused rather than folded.
    private static final Pattern NAME = Pattern.compile("[a-z0-9][a-z0-9-]{0,31}");
    private static final Pattern PLAIN_WORD = Pattern.compile("[A-Za-z0-9_-]{1,32}");
    private static final Set<String> ENTRY_FIELDS = Set.of("name", "username", "password", "seeds", "e2eePartners");
    private static final Set<String> SEED_FIELDS = Set.of("username", "password", "clientId", "clientSecret");
    // A field given twice would otherwise lose one of its values silently.
    private static final JsonMapper JSON = JsonMapper.builder().enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION).build();

    private WorkspaceDefinitions() {
    }

    /** The configured workspaces in the order given; none for a blank value. */
    static List<Definition> parse(String json, DemoProperties properties) {
        if (json == null || json.isBlank()) {
            return List.of();
        }
        JsonNode root;
        try {
            root = JSON.readTree(json);
        } catch (RuntimeException notJson) {
            throw refuse("is not valid JSON, or gives a field twice");
        }
        if (root == null || !root.isArray()) {
            throw refuse("must be a JSON list with one object per workspace");
        }
        List<Definition> definitions = new ArrayList<>();
        for (int i = 0; i < root.size(); i++) {
            Definition definition = definition(root.get(i), "entry " + (i + 1), properties);
            if (definitions.stream().anyMatch(earlier -> earlier.name().equals(definition.name()))) {
                throw refuse("names two workspaces " + definition.name());
            }
            definitions.add(definition);
        }
        return definitions;
    }

    private static Definition definition(JsonNode entry, String where, DemoProperties properties) {
        if (!entry.isObject()) {
            throw refuse(where + " is not an object");
        }
        requireKnownFields(entry, ENTRY_FIELDS, where);
        String name = text(entry, "name");
        if (name == null || !NAME.matcher(name).matches()) {
            throw refuse(where + " needs a name of lower-case letters, digits and hyphens, at most 32 long,"
                + " starting with a letter or a digit");
        }
        if (Workspaces.DEFAULT.equals(name)) {
            throw refuse(where + " is named " + Workspaces.DEFAULT + ", which is the operator's own workspace");
        }
        String workspace = "workspace " + name;
        String username = text(entry, "username");
        String password = text(entry, "password");
        if (username == null || password == null) {
            throw refuse(workspace + " needs a username and a password");
        }
        if (username.indexOf(':') >= 0) {
            // HTTP Basic sends "username:password" and splits it at the first colon, so this login could never succeed.
            throw refuse(workspace + " has a colon in its username, which a login cannot carry");
        }
        Map<String, CredentialsStore.Credentials> seeds = new LinkedHashMap<>();
        for (Map.Entry<String, JsonNode> seed : perEnvironment(entry, "seeds", workspace, properties).entrySet()) {
            seeds.put(seed.getKey(), seed(seed.getValue(), workspace + "'s seed for " + seed.getKey(),
                properties.require(seed.getKey())));
        }
        Map<String, String> partnerSeeds = new LinkedHashMap<>();
        for (Map.Entry<String, JsonNode> table : perEnvironment(entry, "e2eePartners", workspace, properties).entrySet()) {
            // The table's own shape is PartnerKeysStore's to check, when the workspace's state is built.
            partnerSeeds.put(table.getKey(), table.getValue().toString());
        }
        // A workspace login is one handed to a member team, as the guest login is, so it has the guest's upload limit.
        return new Definition(name, new Workspaces.Login(username, password, true), seeds, partnerSeeds);
    }

    /** The object under {@code field}, by environment key; empty when the field is absent. */
    private static Map<String, JsonNode> perEnvironment(JsonNode entry, String field, String workspace,
                                                        DemoProperties properties) {
        JsonNode node = entry.get(field);
        Map<String, JsonNode> byEnvironment = new LinkedHashMap<>();
        if (node == null || node.isNull()) {
            return byEnvironment;
        }
        if (!node.isObject()) {
            throw refuse(workspace + "'s " + field + " must be an object keyed by environment");
        }
        for (Map.Entry<String, JsonNode> each : node.properties()) {
            if (properties.environments() == null || !properties.environments().containsKey(each.getKey())) {
                throw refuse(workspace + "'s " + field + " names an environment that is not configured" + plain(each.getKey()));
            }
            if (!each.getValue().isObject()) {
                throw refuse(workspace + "'s " + field + " for " + each.getKey() + " must be an object");
            }
            byEnvironment.put(each.getKey(), each.getValue());
        }
        return byEnvironment;
    }

    private static CredentialsStore.Credentials seed(JsonNode seed, String where, DemoProperties.EnvironmentDef environment) {
        requireKnownFields(seed, SEED_FIELDS, where);
        String username = text(seed, "username");
        String password = text(seed, "password");
        String clientId = text(seed, "clientId");
        String clientSecret = text(seed, "clientSecret");
        boolean passwordForm = username != null && password != null && clientId == null && clientSecret == null;
        boolean oauthForm = clientId != null && clientSecret != null && username == null && password == null;
        if (!passwordForm && !oauthForm) {
            throw refuse(where + " must be either a username and a password, or a clientId and a clientSecret");
        }
        if (oauthForm && !environment.oauthAvailable()) {
            throw refuse(where + " is a clientId and a clientSecret, but that environment has no Entra tenant and audience");
        }
        return oauthForm
            ? CredentialsStore.Credentials.oauth(clientId, clientSecret)
            : CredentialsStore.Credentials.password(username, password);
    }

    private static void requireKnownFields(JsonNode object, Set<String> known, String where) {
        for (String field : object.propertyNames()) {
            if (!known.contains(field)) {
                throw refuse(where + " has a field this demo does not know" + plain(field)
                    + ". The fields are " + String.join(", ", known.stream().sorted().toList()));
            }
        }
    }

    /** The text under {@code field}, or null when it is absent, blank or not text. */
    private static String text(JsonNode object, String field) {
        JsonNode node = object.get(field);
        return node == null || !node.isString() || node.stringValue().isBlank() ? null : node.stringValue();
    }

    /** {@code word} for a message, when it is a plain word; nothing when it could be a misplaced value. */
    private static String plain(String word) {
        return PLAIN_WORD.matcher(word).matches() ? ": " + word : "";
    }

    static IllegalStateException refuse(String what) {
        return new IllegalStateException(DemoWorkspacesProperties.VARIABLE + " " + what + ".");
    }
}
