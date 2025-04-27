# OpenAPI to MCP Converter
This project provides a bridge between OpenAPI specifications and the Model Context Protocol (MCP). It allows LLM-based tools like the MCP Inspector to interact with REST APIs defined using OpenAPI.

## Features
- Dynamically converts OpenAPI specifications into MCP tools
- Exposes REST API endpoints as callable functions for LLMs
- Supports path parameters, query parameters, request bodies, and headers
- Handles authentication (Bearer tokens, Basic auth, API keys)
- Supports all HTTP methods (GET, POST, PUT, DELETE, etc.)
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
<img width="1789" alt="Screenshot 2025-04-27 at 01 09 58" src="https://github.com/user-attachments/assets/87152f2b-6e4b-4218-b2d4-4b58ba0512cd" />

1. Launch MCP Inspector
2. Select "Connect" or create a new connection
3. Use the following configuration:
   - Transport Type: `stdio`
   - Command: `java`
   - Arguments: `-jar /absolute/path/to/target/openapi-mcp-0.1.0-SNAPSHOT-jar-with-dependencies.jar /absolute/path/to/openapi.json https://api.example.com`
     Make sure to use absolute paths for both the JAR file and the OpenAPI specification file.
4. Connect to the server
5. You should now see all the API operations from your OpenAPI specification as available MCP tools

## Connecting with Claude Desktop
1. Create or edit Claude's configuration file:
```
On macOS: ~/Library/Application Support/Claude/claude_desktop_config.json
On Windows: %APPDATA%\Claude\claude_desktop_config.json\
```

2. Add your OpenAPI MCP server configuration:
   ```json
   {
     "mcpServers": {
       "petstore": {
         "command": "java",
         "args": [
           "-jar",
           "/absolute/path/to/openapi-mcp-0.1.0-SNAPSHOT-jar-with-dependencies.jar",
           "/absolute/path/to/openapi.json",
           "https://api.example.com"
         ]
       }
     }
   }
3. Restart Claude Desktop for the changes to take effect.   
4. Verify the tools are available by looking for the hammer icon in the bottom right corner of the input box. Clicking on it should show your API operations as available tools.
   <img width="907" alt="Screenshot 2025-04-27 at 12 08 10" src="https://github.com/user-attachments/assets/06e264e4-76e4-49e2-b555-cff9c55c898a" />

5. Test it out by asking Claude to perform operations using your API:

   Example: "Get the details for pet with ID 123 from the petstore API."

   <img width="973" alt="Screenshot 2025-04-27 at 12 11 32" src="https://github.com/user-attachments/assets/d160a4de-6df8-4fb6-a038-cfa8f206b72f" />


Claude will detect when to use the API operations and will ask for your approval before making the actual calls to the backend API.

Note that Claude Desktop will run the commands in the configuration file with the permissions of your user account, so only add commands from sources you trust.
   
## Example: Using with Swagger Petstore
1. Save the Petstore OpenAPI spec to a file (e.g., `petstore.json`)
2. Connect with MCP Inspector using:
   - Command: `java`
   - Arguments: `-jar /absolute/path/to/target/openapi-mcp-0.1.0-SNAPSHOT-jar-with-dependencies.jar /absolute/path/to/petstore.json https://petstore.swagger.io/v2`
3. Available operations should include `findPetsByStatus`, `getPetById`, etc.

## Authentication Support

The converter supports various authentication methods commonly used in REST APIs:

### Bearer Token Authentication

```json
{
  "operationId": "listItems",
  "auth_bearer": "your-jwt-token-here"
}
```

This will add the header: `Authorization: Bearer your-jwt-token-here`

### Basic Authentication

```json
{
  "operationId": "getUser",
  "auth_basic": "base64-encoded-credentials"
}
```

This will add the header: `Authorization: Basic base64-encoded-credentials`

### API Key Authentication

```json
{
  "operationId": "createItem",
  "auth_apikey": "your-api-key-here"
}
```

This will add the header: `X-API-Key: your-api-key-here`

### Custom Headers

```json
{
  "operationId": "updateItem",
  "header_X-Custom-Header": "custom-value"
}
```

This will add the header: `X-Custom-Header: custom-value`

## Troubleshooting

### Connection Issues
If you encounter connection issues:
1. Verify the base URL is correct and accessible:
   ```bash
   curl -v https://api.example.com/some-endpoint
   ```
2. Check network settings (proxies, firewalls)
3. Ensure the paths in your OpenAPI specification match the actual API endpoints

### Authentication Problems
If you're having authentication issues:
1. Double-check your authentication parameters:
   - For Bearer tokens, use `auth_bearer` parameter
   - For Basic auth, use `auth_basic` parameter with Base64-encoded credentials
   - For API keys, use `auth_apikey` parameter

2. Verify the token/credentials are valid by testing with curl:
   ```bash
   curl -v -H "Authorization: Bearer your-token" https://api.example.com/endpoint
   ```

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
