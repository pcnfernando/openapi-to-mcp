package pcnfernando.openapi.mcp;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Standalone MCP server that reads a local OpenAPI specification file
 * and exposes it as MCP tools. This server uses stdio transport so it can be
 * connected to by any MCP client like the MCP Inspector.
 */
public class OpenApiMcpServer {
    private static final Logger logger = LoggerFactory.getLogger(OpenApiMcpServer.class);

    // Configure logging to go to stderr
    static {
        // This ensures SimpleLogger output goes to stderr
        System.setProperty("org.slf4j.simpleLogger.logFile", "System.err");
    }

    public static void main(String[] args) {
        if (args.length < 1) {
            logger.error("Usage: OpenApiMcpServer <openapi-spec-path> [base-url]");
            logger.error("Example: OpenApiMcpServer ./petstore.json https://api.example.com");
            System.exit(1);
        }

        // Get the OpenAPI spec path from command line args
        String openApiSpecPath = args[0];

        // Get the base URL (optional, defaults to http://localhost:8080/api)
        String baseUrl = args.length > 1 ? args[1] : "http://localhost:8080/api";

        try {
            // Read the OpenAPI spec from the file
            Path path = Paths.get(openApiSpecPath);
            String openApiSpec = Files.readString(path);

            logger.info("Starting OpenAPI to MCP server with spec from: {}", openApiSpecPath);
            logger.info("Using base URL: {}", baseUrl);

            // Create the converter with stdio transport for communication with MCP clients
            OpenApiToMcpConverter converter = new OpenApiToMcpConverter(openApiSpec, baseUrl, 0);

            // Set up a shutdown hook to clean up resources
            Runtime.getRuntime().addShutdownHook(new Thread(() -> {
                logger.info("Shutting down OpenAPI to MCP server...");
                converter.shutdown();
            }));

            logger.info("OpenAPI to MCP server running...");

            // The server is now running on stdio transport
            // The main thread needs to stay alive, but we don't need to do anything else
            // as the StdioServerTransportProvider handles the communication
            Thread.currentThread().join();

        } catch (IOException e) {
            logger.error("Error reading OpenAPI spec: {}", e.getMessage());
            e.printStackTrace(System.err);
            System.exit(1);
        } catch (Exception e) {
            logger.error("Error starting OpenAPI to MCP server: {}", e.getMessage());
            e.printStackTrace(System.err);
            System.exit(1);
        }
    }
}