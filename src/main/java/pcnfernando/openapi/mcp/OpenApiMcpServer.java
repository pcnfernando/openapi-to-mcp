package pcnfernando.openapi.mcp;

import io.modelcontextprotocol.server.transport.StdioServerTransportProvider;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * Standalone MCP server that reads a local OpenAPI specification file
 * and exposes it as MCP tools. This server uses stdio transport so it can be
 * connected to by any MCP client like the MCP Inspector.
 */
public class OpenApiMcpServer {

    public static void main(String[] args) {
        if (args.length < 1) {
            System.err.println("Usage: OpenApiMcpServer <openapi-spec-path> [base-url]");
            System.err.println("Example: OpenApiMcpServer ./petstore.json https://api.example.com");
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

            System.err.println("Starting OpenAPI to MCP server with spec from: " + openApiSpecPath);
            System.err.println("Using base URL: " + baseUrl);

            // Create the converter with stdio transport for communication with MCP clients
            OpenApiToMcpConverter converter = new OpenApiToMcpConverter(openApiSpec, baseUrl, 0);

            // Note: We're using System.err for logging so it doesn't interfere with the
            // stdio communication protocol which uses System.out/System.in

            // Set up a shutdown hook to clean up resources
            Runtime.getRuntime().addShutdownHook(new Thread(() -> {
                System.err.println("Shutting down OpenAPI to MCP server...");
                converter.shutdown();
            }));

            System.err.println("OpenAPI to MCP server running...");

            // The server is now running on stdio transport
            // The main thread needs to stay alive, but we don't need to do anything else
            // as the StdioServerTransportProvider handles the communication
            Thread.currentThread().join();

        } catch (IOException e) {
            System.err.println("Error reading OpenAPI spec: " + e.getMessage());
            e.printStackTrace();
            System.exit(1);
        } catch (Exception e) {
            System.err.println("Error starting OpenAPI to MCP server: " + e.getMessage());
            e.printStackTrace();
            System.exit(1);
        }
    }
}