"""
FastMCP Client Example

Demonstrates how to use the FastMCP client to interact with
MCP servers for testing and integration.
"""

import asyncio
from fastmcp import Client, FastMCP


# ============================================================
# Create a Test Server (In-Memory)
# ============================================================

def create_test_server() -> FastMCP:
    """Create a simple test server."""
    mcp = FastMCP("Test Server")

    @mcp.tool
    def add(a: int, b: int) -> int:
        """Add two numbers."""
        return a + b

    @mcp.tool
    def greet(name: str, formal: bool = False) -> str:
        """Greet someone."""
        if formal:
            return f"Good day, {name}."
        return f"Hello, {name}!"

    @mcp.tool
    def echo(message: str) -> dict:
        """Echo a message with metadata."""
        return {
            "original": message,
            "length": len(message),
            "uppercase": message.upper(),
        }

    @mcp.resource("resource://greeting")
    def get_greeting() -> str:
        return "Welcome to the test server!"

    @mcp.resource("data://config")
    def get_config() -> dict:
        return {"version": "1.0", "debug": True}

    @mcp.resource("user://{user_id}/profile")
    def get_user_profile(user_id: str) -> dict:
        return {"user_id": user_id, "name": f"User {user_id}"}

    @mcp.prompt
    def explain_topic(topic: str) -> str:
        return f"Please explain {topic} in simple terms."

    return mcp


# ============================================================
# Basic Client Usage
# ============================================================

async def basic_usage():
    """Demonstrate basic client operations."""
    print("=" * 50)
    print("Basic Client Usage")
    print("=" * 50)

    # Create in-memory server for testing
    server = create_test_server()
    client = Client(server)

    async with client:
        # Ping the server
        print("\n1. Ping server:")
        await client.ping()
        print("   Server is responsive!")

        # List available tools
        print("\n2. List tools:")
        tools = await client.list_tools()
        for tool in tools:
            print(f"   - {tool.name}: {tool.description}")

        # List resources
        print("\n3. List resources:")
        resources = await client.list_resources()
        for resource in resources:
            print(f"   - {resource.uri}: {resource.name}")

        # List prompts
        print("\n4. List prompts:")
        prompts = await client.list_prompts()
        for prompt in prompts:
            print(f"   - {prompt.name}: {prompt.description}")


# ============================================================
# Tool Calls
# ============================================================

async def tool_examples():
    """Demonstrate calling tools."""
    print("\n" + "=" * 50)
    print("Tool Calls")
    print("=" * 50)

    server = create_test_server()
    client = Client(server)

    async with client:
        # Call add tool
        print("\n1. Call 'add' tool:")
        result = await client.call_tool("add", {"a": 5, "b": 3})
        print(f"   add(5, 3) = {result}")

        # Call greet tool
        print("\n2. Call 'greet' tool:")
        result = await client.call_tool("greet", {"name": "Alice"})
        print(f"   greet('Alice') = {result}")

        result = await client.call_tool("greet", {"name": "Bob", "formal": True})
        print(f"   greet('Bob', formal=True) = {result}")

        # Call echo tool
        print("\n3. Call 'echo' tool:")
        result = await client.call_tool("echo", {"message": "Hello, World!"})
        print(f"   echo('Hello, World!') = {result}")


# ============================================================
# Resource Reading
# ============================================================

async def resource_examples():
    """Demonstrate reading resources."""
    print("\n" + "=" * 50)
    print("Resource Reading")
    print("=" * 50)

    server = create_test_server()
    client = Client(server)

    async with client:
        # Read static resource
        print("\n1. Read static resource:")
        content = await client.read_resource("resource://greeting")
        print(f"   greeting: {content}")

        # Read JSON resource
        print("\n2. Read JSON resource:")
        config = await client.read_resource("data://config")
        print(f"   config: {config}")

        # Read templated resource
        print("\n3. Read templated resource:")
        profile = await client.read_resource("user://123/profile")
        print(f"   user profile: {profile}")


# ============================================================
# Prompt Usage
# ============================================================

async def prompt_examples():
    """Demonstrate getting prompts."""
    print("\n" + "=" * 50)
    print("Prompt Usage")
    print("=" * 50)

    server = create_test_server()
    client = Client(server)

    async with client:
        # Get a prompt
        print("\n1. Get 'explain_topic' prompt:")
        result = await client.get_prompt(
            "explain_topic",
            {"topic": "quantum computing"}
        )
        print(f"   Prompt messages: {result.messages}")


# ============================================================
# Different Client Sources
# ============================================================

async def client_sources_example():
    """Show different ways to create clients."""
    print("\n" + "=" * 50)
    print("Client Sources")
    print("=" * 50)

    print("""
Different ways to create a FastMCP client:

1. In-memory server (for testing):
   server = FastMCP("Test")
   client = Client(server)

2. Local Python script:
   client = Client("./my_server.py")

3. HTTP server:
   client = Client("https://example.com/mcp")

4. With authentication:
   from fastmcp.client.auth import BearerAuth
   client = Client(
       "https://api.example.com/mcp",
       auth=BearerAuth(token="your-token")
   )
""")


# ============================================================
# Error Handling
# ============================================================

async def error_handling_example():
    """Demonstrate error handling."""
    print("\n" + "=" * 50)
    print("Error Handling")
    print("=" * 50)

    server = create_test_server()
    client = Client(server)

    async with client:
        # Call non-existent tool
        print("\n1. Calling non-existent tool:")
        try:
            await client.call_tool("nonexistent", {})
        except Exception as e:
            print(f"   Error: {type(e).__name__}: {e}")

        # Call with invalid arguments
        print("\n2. Calling with invalid arguments:")
        try:
            await client.call_tool("add", {"a": "not a number", "b": 5})
        except Exception as e:
            print(f"   Error: {type(e).__name__}: {e}")

        # Read non-existent resource
        print("\n3. Reading non-existent resource:")
        try:
            await client.read_resource("resource://nonexistent")
        except Exception as e:
            print(f"   Error: {type(e).__name__}: {e}")


# ============================================================
# Integration Testing Pattern
# ============================================================

async def integration_test_example():
    """Show integration testing pattern."""
    print("\n" + "=" * 50)
    print("Integration Testing Pattern")
    print("=" * 50)

    # Create test server
    server = create_test_server()
    client = Client(server)

    async with client:
        # Test 1: Tool functionality
        print("\n1. Testing 'add' tool...")
        result = await client.call_tool("add", {"a": 2, "b": 3})
        assert result[0].content == "5", f"Expected 5, got {result}"
        print("   PASS")

        # Test 2: Tool with optional params
        print("\n2. Testing 'greet' tool with defaults...")
        result = await client.call_tool("greet", {"name": "Test"})
        assert "Hello" in str(result[0].content)
        print("   PASS")

        # Test 3: Resource availability
        print("\n3. Testing resource availability...")
        resources = await client.list_resources()
        resource_uris = [r.uri for r in resources]
        assert "resource://greeting" in resource_uris
        print("   PASS")

        print("\n All tests passed!")


# ============================================================
# Main
# ============================================================

async def main():
    """Run all examples."""
    await basic_usage()
    await tool_examples()
    await resource_examples()
    await prompt_examples()
    await client_sources_example()
    await error_handling_example()
    await integration_test_example()


if __name__ == "__main__":
    asyncio.run(main())
