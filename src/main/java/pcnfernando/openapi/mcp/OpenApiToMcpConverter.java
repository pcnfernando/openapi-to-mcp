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
import java.util.stream.Collectors;

/**
 * Converter that transforms an OpenAPI specification into MCP tools, making API
 * endpoints accessible through the Model Context Protocol.
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

    // Configure logging to go to stderr
    static {
        System.setProperty("org.slf4j.simpleLogger.logFile", "System.err");
    }

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
     * Creates and configures the MCP server with tools based on the OpenAPI spec.
     */
    private McpSyncServer createMcpServer() {
        try {
            // Parse OpenAPI spec
            JsonNode openApiJson = objectMapper.readTree(openApiSpec);

            // Build MCP server with tools derived from OpenAPI paths
            McpServer.SyncSpecification serverBuilder = McpServer.sync(transportProvider)
                    .serverInfo("OpenAPI-MCP-Bridge", "1.0.0")
                    .capabilities(McpSchema.ServerCapabilities.builder()
                            .tools(true)
                            .build());

            // Process all paths in the OpenAPI spec
            JsonNode paths = openApiJson.get("paths");
            if (paths != null && paths.isObject()) {
                paths.fields().forEachRemaining(pathEntry -> {
                    String path = pathEntry.getKey();
                    JsonNode operations = pathEntry.getValue();

                    operations.fields().forEachRemaining(operationEntry -> {
                        String method = operationEntry.getKey().toUpperCase();
                        JsonNode operation = operationEntry.getValue();

                        if (operation.has("operationId")) {
                            String operationId = operation.get("operationId").asText();
                            String description = operation.has("summary") ?
                                    operation.get("summary").asText() :
                                    "Endpoint " + method + " " + path;

                            // Create input schema for the tool
                            ObjectNode inputSchema = createInputSchema(operation, path, method);

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
     * Creates an input schema for an operation based on its parameters and request body.
     * including header parameters.
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
                            properties.set(name, paramSchema);
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
                        properties.set(name, paramSchema);
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
                        properties.set(headerParamName, paramSchema);
                        if (param.has("required") && param.get("required").asBoolean()) {
                            required.add(headerParamName);
                        }
                    }
                }
            }
        }

        // Process request body for POST, PUT, PATCH
        if (("POST".equals(method) || "PUT".equals(method) || "PATCH".equals(method))
                && operation.has("requestBody")) {
            JsonNode requestBody = operation.get("requestBody");
            if (requestBody.has("content") && requestBody.get("content").has("application/json")) {
                JsonNode contentSchema = requestBody.get("content")
                        .get("application/json")
                        .get("schema");

                if (contentSchema != null) {
                    properties.set("body", contentSchema);
                    if (requestBody.has("required") && requestBody.get("required").asBoolean()) {
                        required.add("body");
                    }
                }
            }
        }

        // Add security fields (for API keys, tokens, etc.)
        JsonNode security = operation.get("security");
        if (security != null && security.isArray() && security.size() > 0) {
            // For each security requirement
            for (JsonNode secReq : security) {
                secReq.fieldNames().forEachRemaining(secName -> {
                    // Add as a header parameter
                    properties.put("auth_" + secName, objectMapper.createObjectNode()
                            .put("type", "string")
                            .put("description", "Authentication token or API key for " + secName));
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
     * Creates a tool specification for the given operation.
     */
    private McpServerFeatures.SyncToolSpecification createToolSpecification(
            String operationId,
            String description,
            ObjectNode inputSchema,
            String path,
            String method) {

        // Create the tool definition
        McpSchema.Tool tool = new McpSchema.Tool(
                operationId,
                description,
                inputSchema.toString()
        );

        // Create the tool handler that will execute the API call
        return new McpServerFeatures.SyncToolSpecification(
                tool,
                (exchange, args) -> {
                    // Make actual API call to the backend service
                    return handleApiRequest(operationId, path, method, args);
                }
        );
    }

    /**
     * Handles an API request by making real HTTP calls to the backend API.
     * Now with header parameter support.
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

                return new McpSchema.CallToolResult(responseBody, isError);
            } catch (java.net.ConnectException e) {
                logger.error("Connection error to URL: {}", url, e);
                return new McpSchema.CallToolResult(
                        "Connection error: Cannot connect to " + url +
                                ". Please check if the URL is correct and the server is running. Error: " + e.getMessage(),
                        true);
            } catch (java.net.UnknownHostException e) {
                logger.error("Unknown host in URL: {}", url, e);
                return new McpSchema.CallToolResult(
                        "Unknown host: Cannot resolve hostname in " + url +
                                ". Please check if the URL is correct. Error: " + e.getMessage(),
                        true);
            }

        } catch (Exception e) {
            logger.error("Error handling API request", e);
            return new McpSchema.CallToolResult(
                    "Error: " + e.getMessage(), true);
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