package pcnfernando.openapi.mcp;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Main entry point for embedding the OpenAPI to MCP server in Java applications.
 * Uses a builder pattern for easy configuration.
 */
public class OpenApiMcpServer {
    private static final Logger logger = LoggerFactory.getLogger(OpenApiMcpServer.class);

    private final String openApiSpec;
    private final String baseUrl;
    private final CapabilitiesConfig capabilities;
    private final Map<String, String> additionalHeaders;
    private final TransportType transportType;
    private final int port;

    private OpenApiToMcpConverter converter;
    private ExecutorService executorService;
    private boolean isRunning = false;

    /**
     * Available transport types for the MCP server.
     */
    public enum TransportType {
        STDIO,
        HTTP,
        WEBSOCKET
    }

    /**
     * Creates a new OpenApiMcpServer instance using the provided configuration.
     */
    private OpenApiMcpServer(Builder builder) {
        this.openApiSpec = builder.openApiSpec;
        this.baseUrl = builder.baseUrl;
        this.capabilities = builder.capabilities;
        this.additionalHeaders = builder.additionalHeaders;
        this.transportType = builder.transportType;
        this.port = builder.port;
    }

    /**
     * Main method to maintain compatibility with the standalone JAR approach.
     * This allows MCP clients to execute the JAR directly.
     */
    public static void main(String[] args) {
        // Call the original OpenApiMcpServer class's main method
        // We need to use a different class to avoid recursion
        try {
            if (args.length < 1) {
                System.err.println("Usage: OpenApiMcpServer <openapi-spec-path> [base-url]");
                System.err.println("Example: OpenApiMcpServer ./petstore.json https://api.example.com");
                System.exit(1);
            }

            // Get the OpenAPI spec path from command line args
            String openApiSpecPath = args[0];

            // Get the base URL (optional, defaults to http://localhost:8080/api)
            String baseUrl = args.length > 1 ? args[1] : "http://localhost:8080/api";

            // Create a server instance
            OpenApiMcpServer server = OpenApiMcpServer.builder()
                    .withOpenApiSpecFromFile(openApiSpecPath)
                    .withBaseUrl(baseUrl)
                    .build();

            // Start the server
            server.start();

            // Keep the main thread alive
            Thread.currentThread().join();
        } catch (Exception e) {
            System.err.println("Error: " + e.getMessage());
            e.printStackTrace(System.err);
            System.exit(1);
        }
    }

    /**
     * Starts the MCP server.
     */
    public void start() {
        if (isRunning) {
            logger.warn("Server is already running");
            return;
        }

        if (openApiSpec == null || openApiSpec.isEmpty()) {
            throw new IllegalStateException("OpenAPI specification is required");
        }

        if (baseUrl == null || baseUrl.isEmpty()) {
            throw new IllegalStateException("Base URL is required");
        }

        try {
            // Set system properties for capabilities
            if (capabilities != null) {
                if (capabilities.enableTools) {
                    System.setProperty("ENABLE_TOOLS", "true");
                    System.setProperty("CAPABILITIES_TOOLS", String.valueOf(capabilities.advertiseTools));
                }

                if (capabilities.enableResources) {
                    System.setProperty("ENABLE_RESOURCES", "true");
                    System.setProperty("CAPABILITIES_RESOURCES", String.valueOf(capabilities.advertiseResources));
                }

                if (capabilities.enablePrompts) {
                    System.setProperty("ENABLE_PROMPTS", "true");
                    System.setProperty("CAPABILITIES_PROMPTS", String.valueOf(capabilities.advertisePrompts));
                }
            }

            // Set additional headers
            if (additionalHeaders != null && !additionalHeaders.isEmpty()) {
                StringBuilder headersStr = new StringBuilder();
                for (Map.Entry<String, String> entry : additionalHeaders.entrySet()) {
                    headersStr.append(entry.getKey()).append(": ").append(entry.getValue()).append("\n");
                }
                System.setProperty("EXTRA_HEADERS", headersStr.toString());
            }

            // Start the converter in a separate thread
            executorService = Executors.newSingleThreadExecutor();
            executorService.submit(() -> {
                try {
                    converter = new OpenApiToMcpConverter(openApiSpec, baseUrl, port);
                    isRunning = true;
                    logger.info("OpenAPI MCP Server started with transport type: {}", transportType);
                } catch (Exception e) {
                    logger.error("Failed to start OpenAPI MCP Server", e);
                }
            });
        } catch (Exception e) {
            logger.error("Error starting OpenAPI MCP Server", e);
            throw new RuntimeException("Failed to start OpenAPI MCP Server", e);
        }
    }

    /**
     * Shuts down the MCP server.
     */
    public void shutdown() {
        if (!isRunning) {
            logger.warn("Server is not running");
            return;
        }

        try {
            if (converter != null) {
                converter.shutdown();
            }

            if (executorService != null) {
                executorService.shutdown();
            }

            isRunning = false;
            logger.info("OpenAPI MCP Server shutdown complete");
        } catch (Exception e) {
            logger.error("Error shutting down OpenAPI MCP Server", e);
        }
    }

    /**
     * Checks if the server is currently running.
     */
    public boolean isRunning() {
        return isRunning;
    }

    /**
     * Creates a new builder instance.
     */
    public static Builder builder() {
        return new Builder();
    }

    /**
     * Builder for configuring the OpenAPI MCP Server.
     */
    public static class Builder {
        private String openApiSpec;
        private String baseUrl;
        private CapabilitiesConfig capabilities = new CapabilitiesConfig();
        private Map<String, String> additionalHeaders = new HashMap<>();
        private TransportType transportType = TransportType.STDIO;
        private int port = 0;

        /**
         * Sets the OpenAPI specification from a file path.
         */
        public Builder withOpenApiSpecFromFile(String filePath) {
            try {
                Path path = Paths.get(filePath);
                this.openApiSpec = Files.readString(path);
                return this;
            } catch (IOException e) {
                throw new RuntimeException("Failed to read OpenAPI spec from file: " + filePath, e);
            }
        }

        /**
         * Sets the OpenAPI specification from a URL.
         */
        public Builder withOpenApiSpecFromUrl(String url) {
            try {
                // Set system property for the converter to use
                System.setProperty("OPENAPI_SPEC_URL", url);
                return this;
            } catch (Exception e) {
                throw new RuntimeException("Failed to configure OpenAPI spec URL: " + url, e);
            }
        }

        /**
         * Sets the OpenAPI specification directly as a string.
         */
        public Builder withOpenApiSpec(String spec) {
            this.openApiSpec = spec;
            return this;
        }

        /**
         * Sets the base URL for the API.
         */
        public Builder withBaseUrl(String baseUrl) {
            this.baseUrl = baseUrl;
            System.setProperty("SERVER_URL_OVERRIDE", baseUrl);
            return this;
        }

        /**
         * Configures the capabilities of the MCP server.
         */
        public Builder withCapabilities(Consumer<CapabilitiesConfig> configurator) {
            configurator.accept(this.capabilities);
            return this;
        }

        /**
         * Adds an additional header to include in API requests.
         */
        public Builder withAdditionalHeader(String name, String value) {
            this.additionalHeaders.put(name, value);
            return this;
        }

        /**
         * Sets all additional headers to include in API requests.
         */
        public Builder withAdditionalHeaders(Map<String, String> headers) {
            this.additionalHeaders.putAll(headers);
            return this;
        }

        /**
         * Sets the transport type for the MCP server.
         */
        public Builder withTransportType(TransportType transportType) {
            this.transportType = transportType;
            return this;
        }

        /**
         * Sets the port for HTTP or WebSocket transport (not used for STDIO).
         */
        public Builder withPort(int port) {
            this.port = port;
            return this;
        }

        /**
         * Builds the OpenApiMcpServer instance.
         */
        public OpenApiMcpServer build() {
            return new OpenApiMcpServer(this);
        }
    }

    /**
     * Configuration for MCP capabilities.
     */
    public static class CapabilitiesConfig {
        private boolean enableTools = true;
        private boolean enableResources = false;
        private boolean enablePrompts = false;
        private boolean advertiseTools = true;
        private boolean advertiseResources = false;
        private boolean advertisePrompts = false;

        /**
         * Enables or disables tool functionality.
         */
        public CapabilitiesConfig enableTools(boolean enable) {
            this.enableTools = enable;
            return this;
        }

        /**
         * Enables or disables resource functionality.
         */
        public CapabilitiesConfig enableResources(boolean enable) {
            this.enableResources = enable;
            return this;
        }

        /**
         * Enables or disables prompt functionality.
         */
        public CapabilitiesConfig enablePrompts(boolean enable) {
            this.enablePrompts = enable;
            return this;
        }

        /**
         * Sets whether tools capability should be advertised.
         */
        public CapabilitiesConfig advertiseTools(boolean advertise) {
            this.advertiseTools = advertise;
            return this;
        }

        /**
         * Sets whether resources capability should be advertised.
         */
        public CapabilitiesConfig advertiseResources(boolean advertise) {
            this.advertiseResources = advertise;
            return this;
        }

        /**
         * Sets whether prompts capability should be advertised.
         */
        public CapabilitiesConfig advertisePrompts(boolean advertise) {
            this.advertisePrompts = advertise;
            return this;
        }
    }
}