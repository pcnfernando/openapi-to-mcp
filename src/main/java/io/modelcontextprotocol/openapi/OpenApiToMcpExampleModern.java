package io.modelcontextprotocol.openapi;

import io.modelcontextprotocol.client.McpClient;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.client.transport.StdioClientTransport;
import io.modelcontextprotocol.client.transport.ServerParameters;
import io.modelcontextprotocol.spec.McpSchema;
import io.modelcontextprotocol.spec.McpSchema.CallToolRequest;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import io.modelcontextprotocol.spec.McpSchema.InitializeResult;
import io.modelcontextprotocol.spec.McpSchema.ListToolsResult;
import io.modelcontextprotocol.spec.McpSchema.TextContent;

import java.util.HashMap;
import java.util.Map;
import java.io.IOException;

/**
 * Modern example demonstrating the use of the OpenAPI to MCP converter.
 * This example connects to a MCP server that exposes a Petstore API defined by an OpenAPI specification.
 */
public class OpenApiToMcpExampleModern {

    // Example OpenAPI specification (Petstore)
    private static final String OPENAPI_SPEC = """
    {
      "openapi": "3.0.0",
      "info": {
        "version": "1.0.0",
        "title": "Petstore API",
        "description": "A simple API for managing a pet store"
      },
      "servers": [
        {
          "url": "https://petstore.example.com/api/v1"
        }
      ],
      "paths": {
        "/pets": {
          "get": {
            "summary": "List all pets",
            "operationId": "listPets",
            "parameters": [
              {
                "name": "limit",
                "in": "query",
                "description": "How many items to return at one time",
                "required": false,
                "schema": {
                  "type": "integer",
                  "format": "int32"
                }
              }
            ],
            "responses": {
              "200": {
                "description": "A paged array of pets",
                "content": {
                  "application/json": {
                    "schema": {
                      "type": "array",
                      "items": {
                        "$ref": "#/components/schemas/Pet"
                      }
                    }
                  }
                }
              }
            }
          },
          "post": {
            "summary": "Create a pet",
            "operationId": "createPet",
            "requestBody": {
              "description": "Pet to add to the store",
              "required": true,
              "content": {
                "application/json": {
                  "schema": {
                    "$ref": "#/components/schemas/Pet"
                  }
                }
              }
            },
            "responses": {
              "201": {
                "description": "Created pet",
                "content": {
                  "application/json": {
                    "schema": {
                      "$ref": "#/components/schemas/Pet"
                    }
                  }
                }
              }
            }
          }
        },
        "/pets/{petId}": {
          "get": {
            "summary": "Get a pet by ID",
            "operationId": "getPet",
            "parameters": [
              {
                "name": "petId",
                "in": "path",
                "required": true,
                "description": "The ID of the pet to retrieve",
                "schema": {
                  "type": "string"
                }
              }
            ],
            "responses": {
              "200": {
                "description": "Details about a pet",
                "content": {
                  "application/json": {
                    "schema": {
                      "$ref": "#/components/schemas/Pet"
                    }
                  }
                }
              }
            }
          }
        }
      },
      "components": {
        "schemas": {
          "Pet": {
            "type": "object",
            "required": [
              "id",
              "name"
            ],
            "properties": {
              "id": {
                "type": "integer",
                "format": "int64"
              },
              "name": {
                "type": "string"
              },
              "tag": {
                "type": "string"
              }
            }
          }
        }
      }
    }
    """;

    public static void main(String[] args) {
        // Define server parameters
        String baseUrl = "https://petstore.example.com/api/v1";

        try {
            // Start the OpenAPI to MCP converter
            OpenApiToMcpConverter converter = new OpenApiToMcpConverter(OPENAPI_SPEC, baseUrl, 8080);
            System.out.println("OpenAPI to MCP Converter started using stdio transport");

            // Create a process for the MCP server and connect to it via stdio
            // In a real implementation, we'd launch an actual process here
            // For this example, we're simulating it by directly creating a client

            // Create an MCP client with stdio transport
            McpSyncClient client = createMcpClient();

            try {
                // Initialize the client and check server info
                InitializeResult initResult = client.initialize();
                System.out.println("\nConnected to server: " + initResult.serverInfo().name() + " v" + initResult.serverInfo().version());

                // List available tools derived from the OpenAPI spec
                ListToolsResult tools = client.listTools();
                System.out.println("\nAvailable API operations:");
                tools.tools().forEach(tool -> {
                    System.out.println("- " + tool.name() + ": " + tool.description());
                });

                // Example 1: List pets with a limit
                System.out.println("\nExample 1: List pets with a limit of 3");
                Map<String, Object> listPetsArgs = new HashMap<>();
                listPetsArgs.put("limit", 3);

                CallToolResult listPetsResult = client.callTool(
                        new CallToolRequest("listPets", listPetsArgs));
                printToolResult(listPetsResult);

                // Example 2: Get a specific pet
                System.out.println("\nExample 2: Get pet with ID 123");
                Map<String, Object> getPetArgs = new HashMap<>();
                getPetArgs.put("petId", "123");

                CallToolResult getPetResult = client.callTool(
                        new CallToolRequest("getPet", getPetArgs));
                printToolResult(getPetResult);

                // Example 3: Create a pet
                System.out.println("\nExample 3: Create a new pet");
                Map<String, Object> petData = new HashMap<>();
                petData.put("name", "Fluffy");
                petData.put("tag", "cat");

                Map<String, Object> createPetArgs = new HashMap<>();
                createPetArgs.put("body", petData);

                CallToolResult createPetResult = client.callTool(
                        new CallToolRequest("createPet", createPetArgs));
                printToolResult(createPetResult);
            } finally {
                // Ensure the client is closed properly
                client.closeGracefully();

                // Shut down the converter
                converter.shutdown();
            }
        } catch (Exception e) {
            System.err.println("Error: " + e.getMessage());
            e.printStackTrace();
        }
    }

    /**
     * Creates an MCP client using stdio transport.
     * @return A configured MCP client
     */
    private static McpSyncClient createMcpClient() throws IOException {
        // In a real implementation, we would connect to a separate process
        // For demonstration purposes, we're using direct stdio connections
        ServerParameters params = new ServerParameters.Builder("java")
                .args("-cp", ".", "io.modelcontextprotocol.openapi.OpenApiToMcpConverter")
                .build();

        return McpClient.sync(
                        new StdioClientTransport(params))
                .clientInfo("MCP-Petstore-Client", "1.0.0")
                .build();
    }

    /**
     * Prints a tool result in a formatted way.
     * @param result The tool call result to print
     */
    private static void printToolResult(CallToolResult result) {
        if (result.isError() != null && result.isError()) {
            System.out.println("Error occurred:");
        } else {
            System.out.println("Success:");
        }

        if (result.content() != null) {
            for (McpSchema.Content content : result.content()) {
                if (content instanceof TextContent) {
                    System.out.println(((TextContent) content).text());
                } else {
                    System.out.println("[Non-text content]");
                }
            }
        }
        System.out.println();
    }
}