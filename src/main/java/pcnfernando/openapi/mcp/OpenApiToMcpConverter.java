package pcnfernando.openapi.mcp;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.modelcontextprotocol.server.McpServer;
import io.modelcontextprotocol.server.McpServerFeatures;
import io.modelcontextprotocol.server.McpSyncServer;
import io.modelcontextprotocol.server.transport.StdioServerTransportProvider;
import io.modelcontextprotocol.spec.McpSchema;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicBoolean;
import java.time.Duration;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.Iterator;

/**
 * Enhanced converter that transforms an OpenAPI specification into MCP tools with rich semantic context,
 * making API endpoints more accessible and understandable for AI models through the Model Context Protocol.
 */
public class OpenApiToMcpConverter implements AutoCloseable {

    private static final Logger logger = LoggerFactory.getLogger(OpenApiToMcpConverter.class);
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final String baseUrl;
    private final String openApiSpec;
    private final StdioServerTransportProvider transportProvider;
    private final McpSyncServer mcpServer;
    private final AtomicBoolean running = new AtomicBoolean(false);
    private final HttpClient httpClient;

    // Pattern to detect potential security issues in descriptions
    private static final Pattern SECURITY_PATTERN = Pattern.compile(
            "(?i)(ignore|bypass|hack|sql\\s*inject|xss|exploit|malicious|\\bauth\\b|\\btoken\\b|credential)",
            Pattern.CASE_INSENSITIVE);

    // Configure logging to go to stderr
    static {
        System.setProperty("org.slf4j.simpleLogger.logFile", "System.err");
    }

    // Default capabilities
    private boolean enableTools = true;
    private boolean enableResources = false;
    private boolean enablePrompts = false;
    private boolean advertiseTools = true;
    private boolean advertiseResources = false;
    private boolean advertisePrompts = false;
    private Map<String, String> additionalHeaders = new HashMap<>();

    /**
     * Creates a new OpenAPI to MCP converter.
     *
     * @param openApiSpec The OpenAPI specification in JSON format
     * @param baseUrl     The base URL of the API
     * @param port        The port to run the MCP server on (not used with stdio transport)
     * @throws IOException If there's an error parsing the OpenAPI spec
     */
    public OpenApiToMcpConverter(String openApiSpec, String baseUrl, int port) throws IOException {
        this.openApiSpec = openApiSpec;
        this.baseUrl = baseUrl;

        // Load configuration from environment variables
        loadConfigFromEnvironment();

        // Create HTTP client for API calls
        this.httpClient = HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_2)
                .connectTimeout(Duration.ofSeconds(30))
                .build();

        // Create MCP server transport provider (using stdio for stdin/stdout communication)
        this.transportProvider = new StdioServerTransportProvider();

        // Create MCP server and register tools based on OpenAPI spec
        this.mcpServer = createMcpServer();

        // Start server
        start();
    }

    /**
     * Loads configuration from environment variables.
     */
    private void loadConfigFromEnvironment() {
        // Feature enablement configuration
        enableTools = getBooleanEnv("ENABLE_TOOLS", true);
        enableResources = getBooleanEnv("ENABLE_RESOURCES", false);
        enablePrompts = getBooleanEnv("ENABLE_PROMPTS", false);

        // Capability advertisement configuration
        advertiseTools = getBooleanEnv("CAPABILITIES_TOOLS", true);
        advertiseResources = getBooleanEnv("CAPABILITIES_RESOURCES", false);
        advertisePrompts = getBooleanEnv("CAPABILITIES_PROMPTS", false);

        // Load additional headers
        String extraHeaders = System.getenv("EXTRA_HEADERS");
        if (extraHeaders != null && !extraHeaders.isEmpty()) {
            for (String headerLine : extraHeaders.split("\\r?\\n")) {
                if (headerLine != null && headerLine.contains(":")) {
                    String[] parts = headerLine.split(":", 2);
                    if (parts.length == 2) {
                        String key = parts[0].trim();
                        String value = parts[1].trim();
                        if (!key.isEmpty() && !value.isEmpty()) {
                            additionalHeaders.put(key, value);
                            logger.debug("Added additional header: {}", key);
                        }
                    }
                }
            }
        }
    }

    /**
     * Helper method to get boolean environment variables with default values.
     */
    private boolean getBooleanEnv(String name, boolean defaultValue) {
        String value = System.getenv(name);
        if (value == null || value.isEmpty()) {
            return defaultValue;
        }
        return value.toLowerCase().matches("true|yes|1|on");
    }

    /**
     * Creates and configures the MCP server with tools based on the OpenAPI spec.
     */
    private McpSyncServer createMcpServer() {
        try {
            // Parse OpenAPI spec
            JsonNode openApiJson = objectMapper.readTree(openApiSpec);

            // Extract API info for global context
            String apiTitle;
            String apiDescription = "";
            String apiVersion = "";

            if (openApiJson.has("info")) {
                JsonNode info = openApiJson.get("info");
                if (info.has("title")) {
                    apiTitle = info.get("title").asText();
                } else {
                    apiTitle = "API";
                }
                if (info.has("description")) {
                    apiDescription = info.get("description").asText();
                }
                if (info.has("version")) {
                    apiVersion = info.get("version").asText();
                }
            } else {
                apiTitle = "API";
            }

            // Extract global external docs if available
            String globalExternalDocs = "";
            if (openApiJson.has("externalDocs")) {
                JsonNode docs = openApiJson.get("externalDocs");
                if (docs.has("description")) {
                    globalExternalDocs = docs.get("description").asText();
                }
                if (docs.has("url")) {
                    globalExternalDocs += " - " + docs.get("url").asText();
                }
            }

            // Build MCP server with tools derived from OpenAPI paths
            // Set up capabilities based on configuration
            McpSchema.ServerCapabilities.Builder capabilitiesBuilder = McpSchema.ServerCapabilities.builder();

            // Add tools capability if enabled and advertised
            if (enableTools && advertiseTools) {
                capabilitiesBuilder.tools(true);
            }

            // Add resources capability if enabled and advertised
            if (enableResources && advertiseResources) {
                // Note: The current MCP Java SDK may not support resources capability
                // This would need to be implemented when SDK supports it
                logger.info("Resources capability enabled but may not be supported by the MCP Java SDK");
            }

            // Add prompts capability if enabled and advertised
            if (enablePrompts && advertisePrompts) {
                // Note: The current MCP Java SDK may not support prompts capability
                // This would need to be implemented when SDK supports it
                logger.info("Prompts capability enabled but may not be supported by the MCP Java SDK");
            }

            McpServer.SyncSpecification serverBuilder = McpServer.sync(transportProvider)
                    .serverInfo(apiTitle, apiVersion)
                    .capabilities(capabilitiesBuilder.build());

            // Extract tag descriptions for enriching tool context
            Map<String, String> tagDescriptions = new HashMap<>();
            if (openApiJson.has("tags") && openApiJson.get("tags").isArray()) {
                for (JsonNode tag : openApiJson.get("tags")) {
                    if (tag.has("name") && tag.has("description")) {
                        tagDescriptions.put(tag.get("name").asText(), tag.get("description").asText());
                    }
                }
            }

            // Extract security schemes
            Map<String, JsonNode> securitySchemes = new HashMap<>();
            if (openApiJson.has("components") &&
                    openApiJson.get("components").has("securitySchemes")) {
                JsonNode schemes = openApiJson.get("components").get("securitySchemes");
                schemes.fields().forEachRemaining(entry ->
                        securitySchemes.put(entry.getKey(), entry.getValue()));
            }

            // Process all paths in the OpenAPI spec
            JsonNode paths = openApiJson.get("paths");
            if (paths != null && paths.isObject()) {
                String finalGlobalExternalDocs = globalExternalDocs;
                paths.fields().forEachRemaining(pathEntry -> {
                    String path = pathEntry.getKey();
                    JsonNode operations = pathEntry.getValue();

                    operations.fields().forEachRemaining(operationEntry -> {
                        String method = operationEntry.getKey().toUpperCase();
                        JsonNode operation = operationEntry.getValue();

                        if (operation.has("operationId")) {
                            String operationId = operation.get("operationId").asText();

                            // Create capability-oriented description
                            String description = createCapabilityDescription(
                                    operation, path, method, tagDescriptions, apiTitle, finalGlobalExternalDocs);

                            // Sanitize description for security
                            description = sanitizeDescription(description);

                            // Create input schema for the tool
                            ObjectNode inputSchema = createInputSchema(operation, path, method);

                            // Add JSON-LD context if available
                            enrichSchemaWithSemantics(inputSchema, operation, openApiJson);

                            // Create tool handler
                            McpServerFeatures.SyncToolSpecification toolSpec =
                                    createToolSpecification(operationId, description, inputSchema, path, method);

                            // Add tool to server
                            serverBuilder.tools(toolSpec);
                            logger.info("Added tool: {} for {} {}", operationId, method, path);
                        }
                    });
                });
            }

            return serverBuilder.build();

        } catch (JsonProcessingException e) {
            logger.error("Error parsing OpenAPI spec", e);
            throw new RuntimeException("Failed to parse OpenAPI specification", e);
        }
    }

    /**
     * Creates a capability-oriented description for an API operation.
     * This transforms traditional API documentation into capability descriptions
     * that are more suitable for AI models to understand when and how to use the API.
     */
    private String createCapabilityDescription(
            JsonNode operation,
            String path,
            String method,
            Map<String, String> tagDescriptions,
            String apiTitle,
            String globalExternalDocs) {

        StringBuilder description = new StringBuilder();

        // Start with action-oriented summary
        String actionVerb = getActionVerb(method);

        if (operation.has("summary")) {
            description.append(operation.get("summary").asText());
        } else {
            description.append(actionVerb).append(" ");

            // Extract resource from path
            String resource = extractResourceFromPath(path);
            description.append(resource);
        }

        // Add detailed description
        if (operation.has("description")) {
            description.append("\n\n").append(operation.get("description").asText());
        }

        // Add semantic capability context based on HTTP method
        description.append("\n\nCapability: ");
        if (method.equalsIgnoreCase("GET")) {
            description.append("Retrieves data without modifying resources. ");
            description.append("Use this when you need to fetch information or check the current state.");
        } else if (method.equalsIgnoreCase("POST")) {
            description.append("Creates new resources or submits data. ");
            description.append("Use this when you need to add new items or send information to the server.");
        } else if (method.equalsIgnoreCase("PUT")) {
            description.append("Updates or replaces existing resources. ");
            description.append("Use this when you need to update the entire resource with a complete replacement.");
        } else if (method.equalsIgnoreCase("PATCH")) {
            description.append("Partially updates existing resources. ");
            description.append("Use this when you need to make partial updates to a resource.");
        } else if (method.equalsIgnoreCase("DELETE")) {
            description.append("Removes resources. ");
            description.append("Use this when you need to delete items or information.");
        }

        // Add domain context from tags
        if (operation.has("tags") && operation.get("tags").isArray()) {
            ArrayNode tags = (ArrayNode) operation.get("tags");
            if (tags.size() > 0) {
                description.append("\n\nDomain: ");
                List<String> tagList = new ArrayList<>();

                for (int i = 0; i < tags.size(); i++) {
                    String tagName = tags.get(i).asText();
                    tagList.add(tagName);

                    // Add tag description if available
                    if (tagDescriptions.containsKey(tagName)) {
                        description.append("\n- ").append(tagName).append(": ")
                                .append(tagDescriptions.get(tagName));
                    }
                }

                // If no detailed tag descriptions were found, just list the tags
                if (tagList.size() > 0 && !description.toString().contains("\n- ")) {
                    description.append(String.join(", ", tagList));
                }
            }
        }

        // Add external documentation if available
        if (operation.has("externalDocs")) {
            JsonNode externalDocs = operation.get("externalDocs");
            description.append("\n\nAdditional Information: ");
            if (externalDocs.has("description")) {
                description.append(externalDocs.get("description").asText());
            }
        }

        // Add parameter usage examples if possible
        if (operation.has("parameters") && operation.get("parameters").isArray()) {
            description.append("\n\nUsage Example:");
            // Just provide a basic example template
            description.append("\n- To ").append(actionVerb.toLowerCase()).append(" ");
            description.append(extractResourceFromPath(path));
            description.append(", provide the required parameters.");
        }

        // Add request body example for POST/PUT/PATCH methods
        if ((method.equals("POST") || method.equals("PUT") || method.equals("PATCH")) &&
                operation.has("requestBody") &&
                operation.get("requestBody").has("content") &&
                operation.get("requestBody").get("content").has("application/json")) {

            description.append("\n\nBody required for this operation. ");
            description.append("Provide all required fields in the body parameter.");
        }

        // Add deprecation warning if applicable
        if (operation.has("deprecated") && operation.get("deprecated").asBoolean()) {
            description.append("\n\nWARNING: This operation is deprecated and may be removed in future versions.");
        }

        return description.toString();
    }

    /**
     * Extract a user-friendly resource name from a path.
     */
    private String extractResourceFromPath(String path) {
        // Remove leading and trailing slashes
        String cleanPath = path.replaceAll("^/+|/+$", "");

        // Replace path parameters with friendly names
        cleanPath = cleanPath.replaceAll("\\{([^}]+)\\}", "specific $1");

        // Replace slashes with spaces
        cleanPath = cleanPath.replace("/", " ");

        // If empty, return generic resource
        if (cleanPath.isEmpty()) {
            return "resource";
        }

        return cleanPath;
    }

    /**
     * Get the appropriate action verb for an HTTP method.
     */
    private String getActionVerb(String method) {
        switch (method.toUpperCase()) {
            case "GET":
                return "Retrieve";
            case "POST":
                return "Create";
            case "PUT":
                return "Update";
            case "PATCH":
                return "Modify";
            case "DELETE":
                return "Delete";
            default:
                return "Use";
        }
    }

    /**
     * Sanitizes a description to prevent potential security issues.
     */
    private String sanitizeDescription(String description) {
        if (description == null) {
            return "";
        }

        // Check for potential security issues
        if (SECURITY_PATTERN.matcher(description).find()) {
            logger.warn("Potentially unsafe content detected in description. Sanitizing.");
            // Replace suspicious patterns with neutral terms
            description = SECURITY_PATTERN.matcher(description)
                    .replaceAll("[FILTERED]");
        }

        // Remove any instructions that might look like prompt injection
        description = description.replaceAll("(?i)(ignore|forget|disregard) (previous|earlier|above) instructions",
                "[FILTERED CONTENT]");

        return description;
    }

    /**
     * Creates an input schema for an operation based on its parameters and request body,
     * including header parameters with enhanced descriptions.
     */
    private ObjectNode createInputSchema(JsonNode operation, String path, String method) {
        ObjectNode schema = objectMapper.createObjectNode();
        schema.put("type", "object");

        ObjectNode properties = objectMapper.createObjectNode();
        ArrayNode required = objectMapper.createArrayNode();

        // Process path parameters
        if (path.contains("{")) {
            JsonNode parameters = operation.get("parameters");
            if (parameters != null && parameters.isArray()) {
                for (JsonNode param : parameters) {
                    if (param.has("in") && "path".equals(param.get("in").asText())) {
                        String name = param.get("name").asText();
                        JsonNode paramSchema = param.get("schema");
                        if (paramSchema != null) {
                            // Enhanced: Copy the schema and add a more descriptive parameter description
                            ObjectNode enhancedSchema = paramSchema.deepCopy();
                            if (param.has("description")) {
                                String enhancedDesc = "Path parameter: " + param.get("description").asText();
                                enhancedSchema.put("description", enhancedDesc);
                            }
                            properties.set(name, enhancedSchema);
                            if (param.has("required") && param.get("required").asBoolean()) {
                                required.add(name);
                            }
                        }
                    }
                }
            }
        }

        // Process query parameters
        JsonNode parameters = operation.get("parameters");
        if (parameters != null && parameters.isArray()) {
            for (JsonNode param : parameters) {
                if (param.has("in") && "query".equals(param.get("in").asText())) {
                    String name = param.get("name").asText();
                    JsonNode paramSchema = param.get("schema");
                    if (paramSchema != null) {
                        // Enhanced: Copy the schema and add a more descriptive parameter description
                        ObjectNode enhancedSchema = paramSchema.deepCopy();
                        if (param.has("description")) {
                            String enhancedDesc = "Query parameter: " + param.get("description").asText();
                            enhancedSchema.put("description", enhancedDesc);
                        }
                        properties.set(name, enhancedSchema);
                        if (param.has("required") && param.get("required").asBoolean()) {
                            required.add(name);
                        }
                    }
                }
            }
        }

        // Process header parameters
        if (parameters != null && parameters.isArray()) {
            for (JsonNode param : parameters) {
                if (param.has("in") && "header".equals(param.get("in").asText())) {
                    String name = param.get("name").asText();
                    // Use a special prefix to identify headers in the args map
                    String headerParamName = "header_" + name;
                    JsonNode paramSchema = param.get("schema");
                    if (paramSchema != null) {
                        // Enhanced: Copy the schema and add a more descriptive parameter description
                        ObjectNode enhancedSchema = paramSchema.deepCopy();
                        if (param.has("description")) {
                            String enhancedDesc = "HTTP Header: " + param.get("description").asText();
                            enhancedSchema.put("description", enhancedDesc);
                        }
                        properties.set(headerParamName, enhancedSchema);
                        if (param.has("required") && param.get("required").asBoolean()) {
                            required.add(headerParamName);
                        }
                    }
                }
            }
        }

        // Process request body for POST, PUT, PATCH with enhanced descriptions
        if (("POST".equals(method) || "PUT".equals(method) || "PATCH".equals(method))
                && operation.has("requestBody")) {
            JsonNode requestBody = operation.get("requestBody");
            if (requestBody.has("content") && requestBody.get("content").has("application/json")) {
                JsonNode contentSchema = requestBody.get("content")
                        .get("application/json")
                        .get("schema");

                if (contentSchema != null) {
                    ObjectNode enhancedBodySchema = contentSchema.deepCopy();
                    // Add a descriptive name for the body parameter
                    if (requestBody.has("description")) {
                        enhancedBodySchema.put("description",
                                "Request Body: " + requestBody.get("description").asText());
                    } else {
                        enhancedBodySchema.put("description",
                                "Request Body: Data to be sent in the request");
                    }
                    properties.set("body", enhancedBodySchema);
                    if (requestBody.has("required") && requestBody.get("required").asBoolean()) {
                        required.add("body");
                    }
                }
            }
        }

        // Add security fields with more detailed descriptions
        JsonNode security = operation.get("security");
        if (security != null && security.isArray() && security.size() > 0) {
            // For each security requirement
            for (JsonNode secReq : security) {
                secReq.fieldNames().forEachRemaining(secName -> {
                    // Add as a header parameter with enhanced description
                    String description = "Authentication: Required for this operation. ";

                    // Add security scope information if available
                    ArrayNode scopes = (ArrayNode) secReq.get(secName);
                    if (scopes != null && scopes.size() > 0) {
                        List<String> scopeList = new ArrayList<>();
                        for (JsonNode scope : scopes) {
                            scopeList.add(scope.asText());
                        }
                        if (!scopeList.isEmpty()) {
                            description += "Required scopes: " + String.join(", ", scopeList);
                        }
                    }

                    properties.put("auth_" + secName, objectMapper.createObjectNode()
                            .put("type", "string")
                            .put("description", description));
                });
            }
        }

        schema.set("properties", properties);
        if (required.size() > 0) {
            schema.set("required", required);
        }

        return schema;
    }

    /**
     * Enriches a schema with semantic information from JSON-LD or extension properties.
     */
    private void enrichSchemaWithSemantics(ObjectNode schema, JsonNode operation, JsonNode openApiSpec) {
        // Check for x-linkedData or similar extensions in the operation
        if (operation.has("x-linkedData")) {
            ObjectNode annotations = objectMapper.createObjectNode();
            annotations.set("linkedData", operation.get("x-linkedData"));
            schema.set("x-semantic-annotations", annotations);
        }

        // Check for x-properties at the operation level
        operation.fields().forEachRemaining(entry -> {
            String fieldName = entry.getKey();
            if (fieldName.startsWith("x-") && !fieldName.equals("x-linkedData")) {
                // Include custom extensions as annotations
                if (!schema.has("x-semantic-annotations")) {
                    schema.set("x-semantic-annotations", objectMapper.createObjectNode());
                }
                ((ObjectNode) schema.get("x-semantic-annotations")).set(
                        fieldName.substring(2), entry.getValue());
            }
        });

        // Check for global JSON-LD context in OpenAPI spec's info section
        if (openApiSpec.has("info") && openApiSpec.get("info").has("x-linkedData")) {
            if (!schema.has("x-semantic-annotations")) {
                schema.set("x-semantic-annotations", objectMapper.createObjectNode());
            }
            ((ObjectNode) schema.get("x-semantic-annotations")).set(
                    "apiContext", openApiSpec.get("info").get("x-linkedData"));
        }
    }

    /**
     * Creates a tool specification for the given operation.
     */
    private McpServerFeatures.SyncToolSpecification createToolSpecification(
            String operationId,
            String description,
            ObjectNode inputSchema,
            String path,
            String method) {

        // Ensure the operationId is properly action-oriented
        String enhancedOperationId = ensureActionOriented(operationId, method, path);

        // Create the tool definition with enhanced operationId
        McpSchema.Tool tool = new McpSchema.Tool(
                enhancedOperationId,
                description,
                inputSchema.toString()
        );

        // Create the tool handler that will execute the API call
        return new McpServerFeatures.SyncToolSpecification(
                tool,
                (exchange, args) -> {
                    // Make actual API call to the backend service
                    return handleApiRequest(enhancedOperationId, path, method, args);
                }
        );
    }

    /**
     * Ensures that an operationId is action-oriented (starts with a verb).
     */
    private String ensureActionOriented(String operationId, String method, String path) {
        // Common verbs that might already be present in operationIds
        List<String> commonVerbs = List.of(
                "get", "retrieve", "fetch", "list", "find", "search",
                "create", "add", "post", "insert",
                "update", "modify", "change", "edit", "patch",
                "delete", "remove", "clear"
        );

        // Check if operationId already starts with a common verb
        for (String verb : commonVerbs) {
            if (operationId.toLowerCase().startsWith(verb)) {
                return operationId; // Already action-oriented
            }
        }

        // If not action-oriented, prefix with appropriate verb based on HTTP method
        switch (method.toUpperCase()) {
            case "GET":
                return "get" + capitalize(operationId);
            case "POST":
                return "create" + capitalize(operationId);
            case "PUT":
                return "update" + capitalize(operationId);
            case "PATCH":
                return "modify" + capitalize(operationId);
            case "DELETE":
                return "delete" + capitalize(operationId);
            default:
                return operationId;
        }
    }

    /**
     * Capitalizes the first letter of a string.
     */
    private String capitalize(String str) {
        if (str == null || str.isEmpty()) {
            return str;
        }
        return str.substring(0, 1).toUpperCase() + str.substring(1);
    }

    /**
     * Handles an API request by making real HTTP calls to the backend API.
     * Now with header parameter support and additional configurable headers.
     */
    private McpSchema.CallToolResult handleApiRequest(
            String operationId,
            String path,
            String method,
            Map<String, Object> args) {

        logger.info("Executing operation: {} {} with args: {}", method, path, args);

        try {
            // Check if baseUrl ends with a slash and path starts with one to avoid double slash
            String normalizedBaseUrl = baseUrl;
            if (baseUrl.endsWith("/") && path.startsWith("/")) {
                normalizedBaseUrl = baseUrl.substring(0, baseUrl.length() - 1);
            } else if (!baseUrl.endsWith("/") && !path.startsWith("/")) {
                normalizedBaseUrl = baseUrl + "/";
            }

            // Replace path parameters in the URL
            String resolvedPath = path;
            for (Map.Entry<String, Object> entry : args.entrySet()) {
                String paramName = entry.getKey();
                Object paramValue = entry.getValue();

                // Skip null values, headers, auth, and the body parameter
                if (paramValue == null || paramName.startsWith("header_") ||
                        paramName.startsWith("auth_") || "body".equals(paramName)) {
                    continue;
                }

                // Check if this parameter is used in the path
                if (resolvedPath.contains("{" + paramName + "}")) {
                    resolvedPath = resolvedPath.replace(
                            "{" + paramName + "}",
                            URLEncoder.encode(String.valueOf(paramValue), StandardCharsets.UTF_8));
                }
            }

            // Extract query parameters (those not used in path and not headers, auth, or body)
            Map<String, Object> queryParams = new HashMap<>();
            for (Map.Entry<String, Object> entry : args.entrySet()) {
                String paramName = entry.getKey();
                Object paramValue = entry.getValue();

                // Skip null values, headers, auth, path parameters, and body
                if (paramValue == null || paramName.startsWith("header_") ||
                        paramName.startsWith("auth_") || "body".equals(paramName) ||
                        resolvedPath.contains("{" + paramName + "}")) {
                    continue;
                }

                // This must be a query parameter
                queryParams.put(paramName, paramValue);
            }

            // Construct query string
            String queryString = "";
            if (!queryParams.isEmpty()) {
                queryString = "?" + queryParams.entrySet().stream()
                        .filter(e -> e.getValue() != null)
                        .map(e -> URLEncoder.encode(e.getKey(), StandardCharsets.UTF_8) + "=" +
                                URLEncoder.encode(String.valueOf(e.getValue()), StandardCharsets.UTF_8))
                        .collect(Collectors.joining("&"));
            }

            // Construct final URL
            String url = normalizedBaseUrl + resolvedPath + queryString;

            // Log the full URL for debugging
            logger.info("Full URL: {}", url);

            try {
                // Check if the URL is valid
                new URI(url);
            } catch (Exception e) {
                logger.error("Invalid URL format: {}", url, e);
                return new McpSchema.CallToolResult(
                        "Error: Invalid URL format: " + url + " - " + e.getMessage(), true);
            }

            // Build HTTP request
            HttpRequest.Builder requestBuilder = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .header("Accept", "application/json");

            // Add standard headers
            requestBuilder.header("User-Agent", "OpenAPI-MCP-Bridge/1.0");

            // Add any additional headers from configuration
            for (Map.Entry<String, String> header : additionalHeaders.entrySet()) {
                requestBuilder.header(header.getKey(), header.getValue());
                logger.debug("Added additional header: {}", header.getKey());
            }

            // Process header parameters
            for (Map.Entry<String, Object> entry : args.entrySet()) {
                String paramName = entry.getKey();
                Object paramValue = entry.getValue();

                if (paramValue != null) {
                    // Add header parameters
                    if (paramName.startsWith("header_")) {
                        String headerName = paramName.substring("header_".length());
                        requestBuilder.header(headerName, String.valueOf(paramValue));
                    }

                    // Add authentication parameters
                    if (paramName.startsWith("auth_")) {
                        String authName = paramName.substring("auth_".length());
                        // Common auth header patterns
                        if (authName.equalsIgnoreCase("bearer") || authName.equalsIgnoreCase("token")) {
                            requestBuilder.header("Authorization", "Bearer " + paramValue);
                        } else if (authName.equalsIgnoreCase("basic")) {
                            requestBuilder.header("Authorization", "Basic " + paramValue);
                        } else if (authName.equalsIgnoreCase("apikey")) {
                            requestBuilder.header("X-API-Key", String.valueOf(paramValue));
                        } else {
                            // Default to putting the value in a header with the auth name
                            requestBuilder.header(authName, String.valueOf(paramValue));
                        }
                    }
                }
            }

            // Add request body for methods that need it
            if (("POST".equals(method) || "PUT".equals(method) || "PATCH".equals(method)) && args.containsKey("body")) {
                Object body = args.get("body");
                if (body != null) {
                    String requestBody = objectMapper.writeValueAsString(body);
                    requestBuilder.header("Content-Type", "application/json")
                            .method(method, HttpRequest.BodyPublishers.ofString(requestBody));
                } else {
                    requestBuilder.method(method, HttpRequest.BodyPublishers.noBody());
                }
            } else {
                // For GET, DELETE, etc.
                requestBuilder.method(method, HttpRequest.BodyPublishers.noBody());
            }

            HttpRequest request = requestBuilder.build();

            // Execute the HTTP request
            logger.info("Sending HTTP request: {} {}", method, url);

            try {
                HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

                // Process the response
                int statusCode = response.statusCode();
                String responseBody = response.body();

                logger.info("Received response: Status {} Body length: {}", statusCode, responseBody.length());

                // Check if request was successful (2xx status code)
                boolean isError = statusCode < 200 || statusCode >= 300;

                if (isError) {
                    logger.error("HTTP error: {} {}", statusCode, responseBody);
                }

                // Format response for better readability in MCP
                String formattedResponse = formatResponseForMcp(responseBody, statusCode);

                // Create the appropriate MCP-native response format
                // In MCP, responses can be structured as multiple content elements
                List<McpSchema.Content> responseContents = new ArrayList<>();

                // Add the primary response content
                responseContents.add(new McpSchema.TextContent(formattedResponse));

                // Add metadata about the response as additional context
                Map<String, Object> metadataMap = new HashMap<>();
                metadataMap.put("statusCode", statusCode);
                metadataMap.put("operation", operationId);
                metadataMap.put("method", method);
                metadataMap.put("path", path);

                // Add response headers as metadata if they exist
                Map<String, List<String>> responseHeaders = response.headers().map();
                if (!responseHeaders.isEmpty()) {
                    // Convert multi-valued headers to single string values for simplicity
                    Map<String, String> simplifiedHeaders = new HashMap<>();
                    for (Map.Entry<String, List<String>> header : responseHeaders.entrySet()) {
                        String headerName = header.getKey();
                        List<String> values = header.getValue();
                        if (values != null && !values.isEmpty()) {
                            simplifiedHeaders.put(headerName, String.join(", ", values));
                        }
                    }
                    metadataMap.put("headers", simplifiedHeaders);
                }

                // Create metadata content
                String metadataJson = objectMapper.writeValueAsString(metadataMap);
                responseContents.add(new McpSchema.TextContent(metadataJson));

                // Return formatted response with metadata in MCP-native format
                return new McpSchema.CallToolResult(responseContents, isError);
            } catch (java.net.ConnectException e) {
                logger.error("Connection error to URL: {}", url, e);
                List<McpSchema.Content> errorContent = new ArrayList<>();
                errorContent.add(new McpSchema.TextContent(
                        "Connection error: Cannot connect to " + url +
                                ". Please check if the URL is correct and the server is running. Error: " + e.getMessage()));
                return new McpSchema.CallToolResult(errorContent, true);
            } catch (java.net.UnknownHostException e) {
                logger.error("Unknown host in URL: {}", url, e);
                List<McpSchema.Content> errorContent = new ArrayList<>();
                errorContent.add(new McpSchema.TextContent(
                        "Unknown host: Cannot resolve hostname in " + url +
                                ". Please check if the URL is correct. Error: " + e.getMessage()));
                return new McpSchema.CallToolResult(errorContent, true);
            }

        } catch (Exception e) {
            logger.error("Error handling API request", e);
            List<McpSchema.Content> errorContent = new ArrayList<>();
            errorContent.add(new McpSchema.TextContent("Error: " + e.getMessage()));
            return new McpSchema.CallToolResult(errorContent, true);
        }
    }

    /**
     * Formats API response for better readability in MCP.
     * Attempts to pretty-print JSON responses and add context for error responses.
     * Enhanced with semantic markers to help AI models understand the response.
     */
    private String formatResponseForMcp(String responseBody, int statusCode) {
        try {
            // Try to parse as JSON for prettier formatting
            JsonNode jsonNode = objectMapper.readTree(responseBody);

            StringBuilder formatted = new StringBuilder();

            // Add semantic status code context with HTTP standard meanings
            if (statusCode >= 200 && statusCode < 300) {
                formatted.append("SUCCESS (").append(statusCode).append("): ");

                // Add more specific context based on common status codes
                switch (statusCode) {
                    case 200:
                        formatted.append("OK - Request succeeded");
                        break;
                    case 201:
                        formatted.append("Created - Resource successfully created");
                        break;
                    case 202:
                        formatted.append("Accepted - Request accepted for processing");
                        break;
                    case 204:
                        formatted.append("No Content - Request succeeded but no content returned");
                        break;
                    default:
                        formatted.append("Request succeeded");
                        break;
                }
            } else {
                formatted.append("ERROR (").append(statusCode).append("): ");

                // Add more specific context based on common error codes
                if (statusCode >= 400 && statusCode < 500) {
                    switch (statusCode) {
                        case 400:
                            formatted.append("Bad Request - The request was invalid");
                            break;
                        case 401:
                            formatted.append("Unauthorized - Authentication is required");
                            break;
                        case 403:
                            formatted.append("Forbidden - You don't have permission");
                            break;
                        case 404:
                            formatted.append("Not Found - The requested resource was not found");
                            break;
                        case 409:
                            formatted.append("Conflict - Request conflicts with current state");
                            break;
                        case 429:
                            formatted.append("Too Many Requests - Rate limit exceeded");
                            break;
                        default:
                            formatted.append("Client error");
                            break;
                    }
                } else if (statusCode >= 500) {
                    switch (statusCode) {
                        case 500:
                            formatted.append("Internal Server Error - Something went wrong on the server");
                            break;
                        case 502:
                            formatted.append("Bad Gateway - Invalid response from upstream server");
                            break;
                        case 503:
                            formatted.append("Service Unavailable - Server temporarily unavailable");
                            break;
                        default:
                            formatted.append("Server error");
                            break;
                    }
                }
            }

            // Try to identify the type of response based on content
            if (jsonNode.isObject()) {
                int fieldCount = 0;
                boolean hasId = false;
                boolean hasList = false;
                boolean hasError = false;

                for (Iterator<String> it = jsonNode.fieldNames(); it.hasNext(); ) {
                    String fieldName = it.next();
                    fieldCount++;

                    if (fieldName.equalsIgnoreCase("id") || fieldName.endsWith("Id")) {
                        hasId = true;
                    } else if (fieldName.equalsIgnoreCase("items") ||
                            fieldName.equalsIgnoreCase("results") ||
                            fieldName.equalsIgnoreCase("data") ||
                            (jsonNode.get(fieldName) != null && jsonNode.get(fieldName).isArray())) {
                        hasList = true;
                    } else if (fieldName.equalsIgnoreCase("error") ||
                            fieldName.equalsIgnoreCase("errors") ||
                            fieldName.equalsIgnoreCase("message")) {
                        hasError = true;
                    }
                }

                // Add semantic hints about the response structure
                formatted.append("\n\n");
                if (hasList) {
                    formatted.append("RESPONSE TYPE: Collection of resources");
                } else if (hasId) {
                    formatted.append("RESPONSE TYPE: Single resource");
                } else if (hasError) {
                    formatted.append("RESPONSE TYPE: Error details");
                } else if (fieldCount > 0) {
                    formatted.append("RESPONSE TYPE: Object");
                } else {
                    formatted.append("RESPONSE TYPE: Empty object");
                }
            } else if (jsonNode.isArray()) {
                formatted.append("\n\nRESPONSE TYPE: Array with ").append(jsonNode.size()).append(" elements");
            }

            // Add pretty-printed JSON
            formatted.append("\n\n").append(objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(jsonNode));

            return formatted.toString();
        } catch (Exception e) {
            // If not valid JSON or other error, return as-is with status context
            if (statusCode >= 200 && statusCode < 300) {
                return "SUCCESS (" + statusCode + "): " + responseBody;
            } else {
                return "ERROR (" + statusCode + "): " + responseBody;
            }
        }
    }

    /**
     * Starts the converter by initializing the MCP server.
     */
    private void start() {
        if (running.compareAndSet(false, true)) {
            logger.info("OpenAPI to MCP Converter started using stdio transport");
        }
    }

    /**
     * Shuts down the converter, stopping the MCP server.
     */
    public void shutdown() {
        if (running.compareAndSet(true, false)) {
            mcpServer.closeGracefully();
            logger.info("OpenAPI to MCP Converter shut down");
        }
    }

    @Override
    public void close() {
        shutdown();
    }
}