package io.modelcontextprotocol.openapi;

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
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicBoolean;

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

    /**
     * Creates a new OpenAPI to MCP converter.
     *
     * @param openApiSpec The OpenAPI specification in JSON format
     * @param baseUrl     The base URL of the API
     * @param port        The port to run the MCP server on
     * @throws IOException If there's an error parsing the OpenAPI spec
     */
    public OpenApiToMcpConverter(String openApiSpec, String baseUrl, int port) throws IOException {
        this.openApiSpec = openApiSpec;
        this.baseUrl = baseUrl;

        // Create MCP server transport provider
        this.transportProvider = HttpServletSseServerTransportProvider.builder()
                .messageEndpoint("/mcp/message")
                .sseEndpoint("/mcp/sse")
                .build();

        // Create and configure embedded Tomcat server
        this.tomcat = createTomcatServer(port);

        // Create MCP server and register tools based on OpenAPI spec
        this.mcpServer = createMcpServer();

        // Start server
        start();
    }

    // No longer need the Tomcat server setup method as we're using Stdio transport

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
                    // Here we would implement the actual API call to the OpenAPI service
                    // For the example, we'll just mock the responses
                    return handleApiRequest(operationId, path, method, args);
                }
        );
    }

    /**
     * Handles an API request by mocking responses appropriate for each operation.
     * In a real implementation, this would make actual HTTP requests to the backend API.
     */
    private McpSchema.CallToolResult handleApiRequest(
            String operationId,
            String path,
            String method,
            Map<String, Object> args) {

        logger.info("Executing operation: {} {} with args: {}", method, path, args);

        try {
            // This is where you would implement the actual HTTP call to the backend API
            // For demonstration purposes, we're just returning mock responses

            switch (operationId) {
                case "listPets":
                    int limit = args.containsKey("limit") ? ((Number)args.get("limit")).intValue() : 10;
                    List<Map<String, Object>> pets = new ArrayList<>();
                    for (int i = 1; i <= limit; i++) {
                        Map<String, Object> pet = new HashMap<>();
                        pet.put("id", i);
                        pet.put("name", "Pet " + i);
                        pet.put("tag", i % 2 == 0 ? "dog" : "cat");
                        pets.add(pet);
                    }
                    return new McpSchema.CallToolResult(
                            objectMapper.writeValueAsString(pets), false);

                case "createPet":
                    @SuppressWarnings("unchecked")
                    Map<String, Object> petInput = (Map<String, Object>) args.get("body");
                    Map<String, Object> createdPet = new HashMap<>(petInput);
                    // Ensure the pet has an ID
                    if (!createdPet.containsKey("id")) {
                        createdPet.put("id", ThreadLocalRandom.current().nextInt(1000, 9999));
                    }
                    return new McpSchema.CallToolResult(
                            objectMapper.writeValueAsString(createdPet), false);

                case "getPet":
                    String petId = args.get("petId").toString();
                    Map<String, Object> pet = new HashMap<>();
                    pet.put("id", Integer.parseInt(petId));
                    pet.put("name", "Pet " + petId);
                    pet.put("tag", Integer.parseInt(petId) % 2 == 0 ? "dog" : "cat");
                    return new McpSchema.CallToolResult(
                            objectMapper.writeValueAsString(pet), false);

                default:
                    return new McpSchema.CallToolResult(
                            "Operation not implemented: " + operationId, true);
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