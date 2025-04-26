# OpenAPI to MCP Converter

This project provides a bridge between OpenAPI specifications and the Model Context Protocol (MCP). It allows LLM-based tools like the MCP Inspector to interact with REST APIs defined using OpenAPI.

## Features

- Dynamically converts OpenAPI specifications into MCP tools
- Exposes REST API endpoints as callable functions for LLMs
- Supports path parameters, query parameters, and request bodies
- Handles all HTTP methods (GET, POST, PUT, DELETE, etc.)
- Works with the MCP Inspector and other MCP clients

## Prerequisites

- Java 11 or later
- Maven 3.6 or later
- An OpenAPI specification file (JSON format)
- MCP Inspector or another MCP client

## Building the Project

1. Clone the repository:
   ```bash
   git clone https://github.com/pcnfernando/openapi-to-mcp.git
   cd openapi-to-mcp
   ```

2. Build the project with Maven:
   ```bash
   mvn clean package
   ```

   This will create a fat JAR with all dependencies included:
   ```
   target/openapi-mcp-0.1.0-SNAPSHOT-jar-with-dependencies.jar
   ```

## Running the Server

Run the server with an OpenAPI specification file and the base URL of the API:

```bash
java -jar target/openapi-mcp-0.1.0-SNAPSHOT-jar-with-dependencies.jar /path/to/openapi.json https://api.example.com
```

### Arguments:
- `path/to/openapi.json`: Path to your OpenAPI specification file
- `https://api.example.com`: Base URL of the API (optional, defaults to http://localhost:8080/api)

## Connecting with MCP Inspector

1. Launch MCP Inspector

2. Select "Connect" or create a new connection

3. Use the following configuration:
    - Transport Type: `stdio`
    - Command: `java`
    - Arguments: `-jar /absolute/path/to/target/openapi-mcp-0.1.0-SNAPSHOT-jar-with-dependencies.jar /absolute/path/to/openapi.json https://api.example.com`

   Make sure to use absolute paths for both the JAR file and the OpenAPI specification file.

4. Connect to the server

5. You should now see all the API operations from your OpenAPI specification as available MCP tools

## Example: Using with Swagger Petstore

1. Save the Petstore OpenAPI spec to a file (e.g., `petstore.json`)

2. Connect with MCP Inspector using:
    - Command: `java`
    - Arguments: `-jar /absolute/path/to/target/openapi-mcp-0.1.0-SNAPSHOT-jar-with-dependencies.jar /absolute/path/to/petstore.json https://petstore.swagger.io/v2`

3. Available operations should include `findPetsByStatus`, `getPetById`, etc.

## Troubleshooting

### Connection Issues

If you encounter connection issues:

1. Verify the base URL is correct and accessible:
   ```bash
   curl -v https://api.example.com/some-endpoint
   ```

2. Check network settings (proxies, firewalls)

3. Ensure the paths in your OpenAPI specification match the actual API endpoints

### Java Errors

If you see Java errors:

1. Make sure you're using Java 11 or later:
   ```bash
   java -version
   ```

2. Check that your OpenAPI spec is valid JSON

3. Look for error details in the server logs

## License

[MIT License](LICENSE)