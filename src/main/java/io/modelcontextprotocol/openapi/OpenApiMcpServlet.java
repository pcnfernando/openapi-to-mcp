package io.modelcontextprotocol.openapi.web;

import io.modelcontextprotocol.openapi.OpenApiToMcpConverter;
import io.modelcontextprotocol.server.transport.HttpServletSseServerTransportProvider;
import jakarta.servlet.ServletConfig;
import jakarta.servlet.ServletException;
import jakarta.servlet.annotation.WebServlet;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Scanner;

/**
 * Servlet that initializes and hosts the OpenAPI to MCP converter.
 * This servlet delegates all requests to the HttpServletSseServerTransportProvider.
 */
@WebServlet(urlPatterns = "/*", asyncSupported = true)
public class OpenApiMcpServlet extends HttpServlet {

    private static final long serialVersionUID = 1L;
    private static final Logger logger = LoggerFactory.getLogger(OpenApiMcpServlet.class);

    private HttpServletSseServerTransportProvider transportProvider;
    private OpenApiToMcpConverter converter;

    @Override
    public void init(ServletConfig config) throws ServletException {
        super.init(config);

        // Configuration parameters
        String openApiSpecPath = config.getInitParameter("openApiSpecPath");
        String baseUrl = config.getInitParameter("baseUrl");

        if (openApiSpecPath == null || openApiSpecPath.isEmpty()) {
            openApiSpecPath = "/openapi.json"; // Default path
        }

        if (baseUrl == null || baseUrl.isEmpty()) {
            baseUrl = "http://localhost:8080/api"; // Default base URL
        }

        try {
            // Load OpenAPI spec from classpath resource
            String openApiSpec = loadResourceAsString(openApiSpecPath);

            // Create transport provider
            this.transportProvider = HttpServletSseServerTransportProvider.builder()
                    .messageEndpoint("/mcp/message")
                    .sseEndpoint("/mcp/sse")
                    .build();

            // Create the converter
            this.converter = new OpenApiToMcpConverter(openApiSpec, baseUrl, 0);

            logger.info("OpenAPI to MCP converter initialized with spec: {}, baseUrl: {}",
                    openApiSpecPath, baseUrl);

        } catch (Exception e) {
            logger.error("Failed to initialize OpenAPI to MCP converter", e);
            throw new ServletException("Failed to initialize OpenAPI to MCP converter", e);
        }
    }

    @Override
    protected void service(HttpServletRequest request, HttpServletResponse response)
            throws ServletException, IOException {

        // Delegate to transport provider
        try {
            transportProvider.service(request, response);
        } catch (Exception e) {
            logger.error("Error handling request", e);
            response.sendError(HttpServletResponse.SC_INTERNAL_SERVER_ERROR,
                    "Error processing request: " + e.getMessage());
        }
    }

    @Override
    public void destroy() {
        try {
            if (converter != null) {
                converter.shutdown();
            }
        } catch (Exception e) {
            logger.error("Error shutting down converter", e);
        }
        super.destroy();
    }

    /**
     * Loads a resource from the classpath as a string.
     */
    private String loadResourceAsString(String resourcePath) throws IOException {
        try (InputStream is = getClass().getResourceAsStream(resourcePath)) {
            if (is == null) {
                throw new IOException("Resource not found: " + resourcePath);
            }

            try (Scanner scanner = new Scanner(is, StandardCharsets.UTF_8.name())) {
                return scanner.useDelimiter("\\A").next();
            }
        }
    }
}