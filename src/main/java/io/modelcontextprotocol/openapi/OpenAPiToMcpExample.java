package io.modelcontextprotocol.openapi;

import io.modelcontextprotocol.client.McpClient;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.client.transport.HttpClientSseClientTransport;
import io.modelcontextprotocol.spec.McpSchema;
import io.modelcontextprotocol.spec.McpSchema.CallToolRequest;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import io.modelcontextprotocol.spec.McpSchema.InitializeResult;
import io.modelcontextprotocol.spec.McpSchema.ListToolsResult;
import io.modelcontextprotocol.spec.McpSchema.TextContent;

import java.util.Map;
import java.util.HashMap;

/**
 * Example demonstrating the use of the OpenAPI to MCP converter.
 */
public class OpenApiToMcpExample {

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
        int port = 8080;

        try {
            // Start the OpenAPI to MCP converter
            OpenApiToMcpConverter converter = new OpenApiToMcpConverter(OPENAPI_SPEC, baseUrl, port);
            System.out.println("OpenAPI to MCP Converter started");

            // Create an MCP client to connect to the server
            McpSyncClient client = McpClient.sync(
                            HttpClientSseClientTransport.builder("http://localhost:" + port)
                                    .sseEndpoint("/mcp/sse")
                                    .build())
                    .clientInfo("MCP-Petstore-Client", "1.0.0")
                    .build();

            try {
                // Initialize the client
                InitializeResult initResult = client.initialize();
                System.out.println("Client initialized: " + initResult.serverInfo().name());

                // List available tools
                ListToolsResult tools = client.listTools();
                System.out.println("\nAvailable tools:");
                tools.tools().forEach(tool -> {
                    System.out.println("- " + tool.name() + ": " + tool.description());
                });

                // Example 1: List pets with a limit
                System.out.println("\nExample 1: List pets with a limit");
                Map<String, Object> listPetsArgs = new HashMap<>();
                listPetsArgs.put("limit", 10);

                CallToolResult listPetsResult = client.callTool(
                        new CallToolRequest("listPets", listPetsArgs));

                printResult(listPetsResult);

                // Example 2: Get a specific pet
                System.out.println("\nExample 2: Get a specific pet");
                Map<String, Object> getPetArgs = new HashMap<>();
                getPetArgs.put("petId", "123");

                CallToolResult getPetResult = client.callTool(
                        new CallToolRequest("getPet", getPetArgs));

                printResult(getPetResult);

                // Example 3: Create a pet
                System.out.println("\nExample 3: Create a pet");
                Map<String, Object> createPetArgs = new HashMap<>();
                Map<String, Object> petBody = new HashMap<>();
                petBody.put("id", 456);
                petBody.put("name", "Fluffy");
                petBody.put("tag", "cat");
                createPetArgs.put("body", petBody);

                CallToolResult createPetResult = client.callTool(
                        new CallToolRequest("createPet", createPetArgs));

                printResult(createPetResult);

            } finally {
                // Clean up
                client.closeGracefully();
                converter.shutdown();
            }

        } catch (Exception e) {
            System.err.println("Error: " + e.getMessage());
            e.printStackTrace();
        }
    }

    private static void printResult(CallToolResult result) {
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