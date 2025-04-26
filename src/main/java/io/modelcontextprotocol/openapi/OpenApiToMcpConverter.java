package io.modelcontextprotocol.openapi;

import io.modelcontextprotocol.server.McpServer;
import io.modelcontextprotocol.server.McpAsyncServer;
import io.modelcontextprotocol.server.McpServerFeatures;
import io.modelcontextprotocol.server.transport.HttpServletSseServerTransportProvider;
import io.modelcontextprotocol.spec.McpSchema;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import io.modelcontextprotocol.spec.McpSchema.TextContent;
import io.modelcontextprotocol.spec.McpSchema.Tool;
import io.modelcontextprotocol.spec.McpSchema.ServerCapabilities;

import io.swagger.parser.OpenAPIParser;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.Operation;
import io.swagger.v3.oas.models.PathItem;
import io.swagger.v3.oas.models.Paths;
import io.swagger.v3.oas.models.parameters.Parameter;
import io.swagger.v3.oas.models.media.Schema;
import io.swagger.v3.oas.models.media.MediaType;
import io.swagger.v3.oas.models.responses.ApiResponse;
import io.swagger.v3.oas.models.responses.ApiResponses;
import io.swagger.v3.parser.core.models.SwaggerParseResult;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.node.ArrayNode;

import reactor.core.publisher.Mono;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Converts OpenAPI specifications to MCP tools and serves them via an MCP server.
 */
public class OpenApiToMcpConverter {

    private final String baseUrl;
    private final OpenAPI openAPI;
    private final McpAsyncServer mcpServer;
    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;
    private final ExecutorService executorService;

    /**
     * Creates a new OpenAPI to MCP converter.
     *
     * @param openApiSpec The OpenAPI specification as a string
     * @param baseUrl The base URL for API calls
     * @param port The port for the MCP server
     * @throws IllegalArgumentException if the OpenAPI spec cannot be parsed
     */
    public OpenApiToMcpConverter(String openApiSpec, String baseUrl, int port) {
        this.baseUrl = baseUrl;
        this.objectMapper = new ObjectMapper();
        this.executorService = Executors.newCachedThreadPool();
        this.httpClient = HttpClient.newBuilder()
                .executor(executorService)
                .connectTimeout(Duration.ofSeconds(10))
                .build();

        // Parse the OpenAPI spec
        SwaggerParseResult parseResult = new OpenAPIParser().readContents(openApiSpec, null, null);
        if (parseResult.getMessages() != null && !parseResult.getMessages().isEmpty()) {
            throw new IllegalArgumentException("Failed to parse OpenAPI spec: " +
                    String.join(", ", parseResult.getMessages()));
        }
        this.openAPI = parseResult.getOpenAPI();

        // Create and start the MCP server
        HttpServletSseServerTransportProvider transportProvider = HttpServletSseServerTransportProvider.builder()
                .messageEndpoint("/mcp/message")
                .sseEndpoint("/mcp/sse")
                .build();

        this.mcpServer = createMcpServer(transportProvider);
    }

    /**
     * Creates and configures the MCP server with tools based on the OpenAPI spec.
     */
    private McpAsyncServer createMcpServer(HttpServletSseServerTransportProvider transportProvider) {
        List<McpServerFeatures.AsyncToolSpecification> tools = createToolsFromOpenApi();

        return McpServer.async(transportProvider)
                .serverInfo("OpenAPI-MCP-Bridge", "1.0.0")
                .capabilities(ServerCapabilities.builder().tools(true).build())
                .tools(tools)
                .build();
    }

    /**
     * Converts OpenAPI paths to MCP tools.
     */
    private List<McpServerFeatures.AsyncToolSpecification> createToolsFromOpenApi() {
        List<McpServerFeatures.AsyncToolSpecification> tools = new ArrayList<>();
        Paths paths = openAPI.getPaths();

        if (paths == null) {
            return tools;
        }

        for (Map.Entry<String, PathItem> pathEntry : paths.entrySet()) {
            String path = pathEntry.getKey();
            PathItem pathItem = pathEntry.getValue();

            tools.addAll(createToolsForPath(path, pathItem));
        }

        return tools;
    }

    /**
     * Creates tools for a specific path and its operations.
     */
    private List<McpServerFeatures.AsyncToolSpecification> createToolsForPath(String path, PathItem pathItem) {
        List<McpServerFeatures.AsyncToolSpecification> tools = new ArrayList<>();

        // Process each HTTP method
        addOperation(tools, "GET", path, pathItem.getGet());
        addOperation(tools, "POST", path, pathItem.getPost());
        addOperation(tools, "PUT", path, pathItem.getPut());
        addOperation(tools, "DELETE", path, pathItem.getDelete());
        addOperation(tools, "PATCH", path, pathItem.getPatch());
        addOperation(tools, "HEAD", path, pathItem.getHead());
        addOperation(tools, "OPTIONS", path, pathItem.getOptions());

        return tools;
    }

    /**
     * Adds a tool for a specific operation if it exists.
     */
    private void addOperation(List<McpServerFeatures.AsyncToolSpecification> tools,
                              String method, String path, Operation operation) {
        if (operation == null) {
            return;
        }

        String operationId = operation.getOperationId() != null
                ? operation.getOperationId()
                : method.toLowerCase() + "_" + path.replaceAll("[^a-zA-Z0-9]", "_");

        String description = operation.getSummary() != null
                ? operation.getSummary()
                : (operation.getDescription() != null ? operation.getDescription() : "");

        if (description.isEmpty()) {
            description = method + " " + path;
        }

        // Create the JSON schema for the tool
        String schema = createJsonSchemaForOperation(operation);

        // Create the tool
        Tool tool = new Tool(operationId, description, schema);

        // Add the tool with its handler
        tools.add(new McpServerFeatures.AsyncToolSpecification(
                tool,
                (exchange, args) -> handleToolCall(method, path, operation, args)
        ));
    }

    /**
     * Creates a JSON schema for an operation's parameters and request body.
     */
    private String createJsonSchemaForOperation(Operation operation) {
        ObjectNode schemaNode = objectMapper.createObjectNode();
        schemaNode.put("type", "object");

        ObjectNode properties = objectMapper.createObjectNode();
        ArrayNode required = objectMapper.createArrayNode();

        // Add parameters
        if (operation.getParameters() != null) {
            for (Parameter parameter : operation.getParameters()) {
                String name = parameter.getName();
                io.swagger.v3.oas.models.media.Schema<?> paramSchema = parameter.getSchema();

                // Add parameter to properties
                if (paramSchema != null) {
                    ObjectNode propSchema = objectMapper.createObjectNode();
                    propSchema.put("type", paramSchema.getType());
                    if (paramSchema.getDescription() != null) {
                        propSchema.put("description", paramSchema.getDescription());
                    }
                    properties.set(name, propSchema);

                    // Add to required array if necessary
                    if (Boolean.TRUE.equals(parameter.getRequired())) {
                        required.add(name);
                    }
                }
            }
        }

        // Add request body if present
        if (operation.getRequestBody() != null &&
                operation.getRequestBody().getContent() != null) {

            // Try to find JSON content type
            MediaType jsonMediaType = operation.getRequestBody().getContent().get("application/json");
            if (jsonMediaType != null && jsonMediaType.getSchema() != null) {
                ObjectNode bodySchema = objectMapper.createObjectNode();
                bodySchema.put("type", "object");
                bodySchema.put("description", "Request body");
                properties.set("body", bodySchema);

                if (Boolean.TRUE.equals(operation.getRequestBody().getRequired())) {
                    required.add("body");
                }
            }
        }

        schemaNode.set("properties", properties);

        if (required.size() > 0) {
            schemaNode.set("required", required);
        }

        try {
            return objectMapper.writeValueAsString(schemaNode);
        } catch (Exception e) {
            return "{\"type\": \"object\", \"properties\": {}}";
        }
    }

    /**
     * Handles a tool call by making the corresponding API request.
     */
    private Mono<CallToolResult> handleToolCall(String method, String path,
                                                Operation operation, Map<String, Object> args) {
        return Mono.fromCallable(() -> {
            // Replace path parameters
            String resolvedPath = resolvePath(path, args);
            String url = baseUrl + resolvedPath;

            // Build query parameters
            if ("GET".equals(method) && args != null) {
                StringBuilder queryParams = new StringBuilder();
                boolean first = true;

                for (Map.Entry<String, Object> entry : args.entrySet()) {
                    // Skip path parameters that have already been processed
                    if (path.contains("{" + entry.getKey() + "}")) {
                        continue;
                    }

                    // Add query parameter
                    if (entry.getValue() != null) {
                        if (first) {
                            queryParams.append("?");
                            first = false;
                        } else {
                            queryParams.append("&");
                        }
                        queryParams.append(entry.getKey()).append("=").append(entry.getValue().toString());
                    }
                }

                url += queryParams.toString();
            }

            // Create the HTTP request
            HttpRequest.Builder requestBuilder = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .header("Accept", "application/json");

            // Add request body for methods that support it
            if (("POST".equals(method) || "PUT".equals(method) || "PATCH".equals(method)) &&
                    args != null && args.containsKey("body")) {

                String body = objectMapper.writeValueAsString(args.get("body"));
                requestBuilder.header("Content-Type", "application/json")
                        .method(method, HttpRequest.BodyPublishers.ofString(body));
            } else {
                requestBuilder.method(method, HttpRequest.BodyPublishers.noBody());
            }

            // Execute the request
            HttpResponse<String> response = httpClient.send(
                    requestBuilder.build(),
                    HttpResponse.BodyHandlers.ofString());

            // Process the response
            int statusCode = response.statusCode();
            String responseBody = response.body();

            // Create the tool result
            String resultText = "Status: " + statusCode + "\n\n" + responseBody;
            return new CallToolResult(List.of(new TextContent(resultText)), statusCode >= 400);

        }).onErrorResume(error -> {
            // Handle errors
            return Mono.just(new CallToolResult(
                    List.of(new TextContent("Error: " + error.getMessage())),
                    true
            ));
        });
    }

    /**
     * Resolves path parameters in the URL path.
     */
    private String resolvePath(String path, Map<String, Object> args) {
        String resolvedPath = path;

        if (args != null) {
            for (Map.Entry<String, Object> entry : args.entrySet()) {
                String paramName = entry.getKey();
                String paramValue = entry.getValue() != null ? entry.getValue().toString() : "";

                resolvedPath = resolvedPath.replace("{" + paramName + "}", paramValue);
            }
        }

        return resolvedPath;
    }

    /**
     * Shuts down the converter and releases resources.
     */
    public void shutdown() {
        mcpServer.closeGracefully().block();
        executorService.shutdown();
    }

    /**
     * Returns the MCP server instance.
     */
    public McpAsyncServer getMcpServer() {
        return mcpServer;
    }

    public static void main(String[] args) {
        if (args.length < 3) {
            System.err.println("Usage: OpenApiToMcpConverter <openapi-spec-file> <base-url> <port>");
            System.exit(1);
        }

        try {
            String openApiSpec = new String(java.nio.file.Files.readAllBytes(
                    java.nio.file.Paths.get(args[0])));
            String baseUrl = args[1];
            int port = Integer.parseInt(args[2]);

            OpenApiToMcpConverter converter = new OpenApiToMcpConverter(openApiSpec, baseUrl, port);

            // Keep the server running
            Runtime.getRuntime().addShutdownHook(new Thread(converter::shutdown));

            System.out.println("OpenAPI to MCP Converter running on port " + port);
            System.out.println("Press Ctrl+C to stop");

            // Block indefinitely
            Thread.currentThread().join();

        } catch (Exception e) {
            System.err.println("Error: " + e.getMessage());
            e.printStackTrace();
            System.exit(1);
        }
    }
}