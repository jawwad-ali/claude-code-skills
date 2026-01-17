---
name: FastMCP Development
description: This skill should be used when the user asks to "create an MCP server", "build MCP tools", "add MCP resources", "create MCP prompts", "implement MCP protocol", "deploy MCP server", "create AI tools", "expose API as MCP", "build Claude tools", "create LLM tools", or mentions FastMCP, MCP server, Model Context Protocol, MCP tools, MCP resources, MCP prompts, or server composition.
version: 1.0.0
---

# FastMCP Development Guide

This skill provides comprehensive guidance for building Model Context Protocol (MCP) servers and clients using FastMCP, a fast and Pythonic framework.

## Core Concepts

FastMCP simplifies building MCP servers that allow LLMs to interact with your data and services:

- **Tools**: Functions that LLMs can call to perform actions
- **Resources**: Data that LLMs can read (files, APIs, databases)
- **Prompts**: Reusable prompt templates with arguments
- **Context**: Access to logging, progress reporting, and LLM sampling
- **Composition**: Combine multiple servers via import/mount/proxy

## Installation

```bash
pip install fastmcp
```

## Project Structure

```
project/
├── server.py              # Main MCP server
├── tools/                 # Tool definitions
│   ├── __init__.py
│   ├── database.py
│   └── api.py
├── resources/             # Resource definitions
│   ├── __init__.py
│   └── files.py
├── prompts/               # Prompt templates
│   ├── __init__.py
│   └── templates.py
└── requirements.txt
```

## Basic Server Setup

```python
from fastmcp import FastMCP

# Create the server
mcp = FastMCP("My Server")

# Define a tool
@mcp.tool
def add(a: int, b: int) -> int:
    """Add two numbers together."""
    return a + b

# Run the server
if __name__ == "__main__":
    mcp.run()
```

## Tools

Tools are functions that LLMs can call. FastMCP automatically generates schemas from type hints and docstrings.

### Basic Tool

```python
from fastmcp import FastMCP

mcp = FastMCP("Calculator")

@mcp.tool
def multiply(a: float, b: float) -> float:
    """Multiply two numbers.

    Args:
        a: First number
        b: Second number

    Returns:
        Product of a and b
    """
    return a * b
```

### Tool with Type Annotations

```python
from typing import Annotated
from pydantic import Field

@mcp.tool
def search_products(
    query: Annotated[str, Field(description="Search query")],
    category: Annotated[str | None, Field(description="Product category")] = None,
    limit: Annotated[int, Field(description="Max results", ge=1, le=100)] = 10,
) -> list[dict]:
    """Search the product catalog."""
    # Implementation
    return []
```

### Tool with Pydantic Models

```python
from pydantic import BaseModel, Field

class OrderItem(BaseModel):
    product_id: str
    quantity: int = Field(gt=0)
    price: float = Field(gt=0)

class Order(BaseModel):
    customer_id: str
    items: list[OrderItem]

@mcp.tool
def place_order(order: Order) -> dict:
    """Place a new order."""
    total = sum(item.quantity * item.price for item in order.items)
    return {"order_id": "ORD-123", "total": total}
```

### Async Tool

```python
import httpx

@mcp.tool
async def fetch_weather(city: str) -> dict:
    """Fetch weather for a city."""
    async with httpx.AsyncClient() as client:
        response = await client.get(f"https://api.weather.com/{city}")
        return response.json()
```

### Tool with Context

```python
from fastmcp import FastMCP, Context

mcp = FastMCP("ContextDemo")

@mcp.tool
async def process_data(data_uri: str, ctx: Context) -> dict:
    """Process data with progress reporting."""
    # Logging
    await ctx.info(f"Processing {data_uri}")

    # Read a resource
    resource = await ctx.read_resource(data_uri)

    # Report progress
    await ctx.report_progress(progress=50, total=100)

    # Sample from LLM
    summary = await ctx.sample(f"Summarize: {resource}")

    await ctx.report_progress(progress=100, total=100)
    return {"summary": summary.text}
```

### Custom Tool Metadata

```python
@mcp.tool(
    name="find_products",
    description="Search the product catalog with filters",
    tags={"catalog", "search"},
    meta={"version": "1.2"}
)
def search_implementation(query: str) -> list:
    """Internal implementation."""
    return []
```

## Resources

Resources expose data that LLMs can read.

### Static Resource

```python
from fastmcp import FastMCP

mcp = FastMCP("DataServer")

@mcp.resource("resource://greeting")
def get_greeting() -> str:
    """Provides a greeting message."""
    return "Hello from FastMCP!"
```

### JSON Resource

```python
@mcp.resource("data://config")
def get_config() -> dict:
    """Provides configuration as JSON."""
    return {
        "version": "1.0.0",
        "features": ["tools", "resources"],
    }
```

### Resource Templates

```python
# Single parameter
@mcp.resource("weather://{city}/current")
def get_weather(city: str) -> dict:
    """Weather for a specific city."""
    return {"city": city, "temp": 72, "condition": "Sunny"}

# Multiple parameters
@mcp.resource("repos://{owner}/{repo}/info")
def get_repo_info(owner: str, repo: str) -> dict:
    """GitHub repository information."""
    return {"owner": owner, "name": repo, "stars": 100}

# Query parameters
@mcp.resource("api://{endpoint}{?limit,offset}")
def call_api(endpoint: str, limit: int = 10, offset: int = 0) -> dict:
    """Call API with pagination."""
    return {"endpoint": endpoint, "limit": limit, "offset": offset}
```

### File and Directory Resources

```python
from pathlib import Path
from fastmcp.resources import FileResource, TextResource, DirectoryResource

# Static file
mcp.add_resource(FileResource(
    uri="file://readme",
    path=Path("./README.md"),
    name="README",
    mime_type="text/markdown",
))

# Static text
mcp.add_resource(TextResource(
    uri="resource://notice",
    name="Notice",
    text="System maintenance scheduled.",
))

# Directory listing
mcp.add_resource(DirectoryResource(
    uri="resource://data",
    path=Path("./data"),
    name="Data Files",
    recursive=True,
))
```

### Resource Metadata

```python
@mcp.resource(
    uri="data://status",
    name="AppStatus",
    description="Current application status",
    mime_type="application/json",
    tags={"monitoring"},
)
def get_status() -> dict:
    return {"status": "healthy", "uptime": 12345}
```

## Prompts

Prompts are reusable templates that generate messages for LLMs.

### Basic Prompt

```python
from fastmcp import FastMCP

mcp = FastMCP("PromptServer")

@mcp.prompt
def explain_topic(topic: str) -> str:
    """Generate a prompt asking to explain a topic."""
    return f"Please explain the concept of '{topic}' in simple terms."
```

### Prompt with Message Type

```python
from fastmcp.prompts.prompt import PromptMessage, TextContent

@mcp.prompt
def code_review(language: str, code: str) -> PromptMessage:
    """Generate a code review prompt."""
    return PromptMessage(
        role="user",
        content=TextContent(
            type="text",
            text=f"Review this {language} code:\n\n```{language}\n{code}\n```"
        )
    )
```

### Prompt with Metadata

```python
@mcp.prompt(
    name="data_analysis",
    description="Request data analysis",
    tags={"analysis", "data"},
)
def analysis_prompt(data_uri: str, analysis_type: str = "summary") -> str:
    return f"Perform a '{analysis_type}' analysis on: {data_uri}"
```

## Running the Server

### STDIO Transport (Default)

```python
if __name__ == "__main__":
    mcp.run()  # Default: STDIO
```

### HTTP Transport

```python
if __name__ == "__main__":
    mcp.run(transport="http", host="0.0.0.0", port=8000)
```

### SSE Transport (Legacy)

```python
if __name__ == "__main__":
    mcp.run(transport="sse", host="127.0.0.1", port=8000)
```

## Client Usage

```python
import asyncio
from fastmcp import Client, FastMCP

# Connect to different server types
client = Client("my_server.py")           # Local script
client = Client("https://example.com/mcp")  # HTTP server
client = Client(FastMCP("Test"))           # In-memory (testing)

async def main():
    async with client:
        # Ping server
        await client.ping()

        # List capabilities
        tools = await client.list_tools()
        resources = await client.list_resources()
        prompts = await client.list_prompts()

        # Call a tool
        result = await client.call_tool("add", {"a": 1, "b": 2})
        print(result)

        # Read a resource
        data = await client.read_resource("data://config")
        print(data)

        # Get a prompt
        prompt = await client.get_prompt("explain_topic", {"topic": "MCP"})
        print(prompt)

asyncio.run(main())
```

## Server Composition

### Mount Subserver

```python
from fastmcp import FastMCP

main = FastMCP("Main")
sub = FastMCP("Sub")

@sub.tool
def sub_tool() -> str:
    return "Hello from sub!"

# Mount with prefix
main.mount(sub, prefix="sub")
# Tool accessible as: sub_sub_tool
```

### Import Subserver

```python
# Static copy of components
main.import_server(sub, prefix="imported")
```

### Proxy Remote Server

```python
from fastmcp import FastMCP, Client

# Proxy a remote server
remote_proxy = FastMCP.as_proxy(Client("https://api.example.com/mcp"))
main.mount(remote_proxy, prefix="remote")
```

### Multi-Server Configuration

```python
config = {
    "mcpServers": {
        "weather": {"url": "https://weather.example.com/mcp"},
        "calendar": {"url": "https://calendar.example.com/mcp"},
    }
}
mcp = FastMCP.from_config(config)
```

## OpenAPI Integration

Convert any OpenAPI specification to an MCP server:

```python
import httpx
from fastmcp import FastMCP

# Load OpenAPI spec
spec = httpx.get("https://api.example.com/openapi.json").json()

# Create MCP server from spec
mcp = FastMCP.from_openapi(
    openapi_spec=spec,
    client=httpx.AsyncClient(base_url="https://api.example.com"),
    name="API Server"
)

if __name__ == "__main__":
    mcp.run()
```

## FastAPI Integration

Convert a FastAPI app to MCP server:

```python
from fastapi import FastAPI
from fastmcp import FastMCP

app = FastAPI()

@app.get("/items/{item_id}")
def get_item(item_id: int):
    return {"item_id": item_id}

# Convert to MCP server
mcp = FastMCP.from_fastapi(app=app)
```

## Authentication

### Client with Bearer Token

```python
from fastmcp import Client
from fastmcp.client.auth import BearerAuth

async with Client(
    "https://api.example.com/mcp",
    auth=BearerAuth(token="your-token"),
) as client:
    await client.ping()
```

## Context Features

### Logging

```python
@mcp.tool
async def my_tool(ctx: Context) -> str:
    await ctx.debug("Debug message")
    await ctx.info("Info message")
    await ctx.warning("Warning message")
    await ctx.error("Error message")
    return "done"
```

### Progress Reporting

```python
@mcp.tool
async def long_task(ctx: Context) -> str:
    for i in range(100):
        await ctx.report_progress(progress=i, total=100)
        await do_work()
    return "complete"
```

### LLM Sampling

```python
@mcp.tool
async def summarize(text: str, ctx: Context) -> str:
    result = await ctx.sample(f"Summarize: {text}")
    return result.text
```

## Best Practices

1. **Use Type Hints**: Always annotate parameters for automatic schema generation
2. **Write Docstrings**: Clear docstrings become tool/resource descriptions
3. **Use Pydantic**: Complex inputs should use Pydantic models for validation
4. **Async for I/O**: Use async functions for network/file operations
5. **Report Progress**: Long operations should report progress via context
6. **Log Appropriately**: Use context logging for visibility
7. **Organize with Prefixes**: Use composition to organize large servers
8. **Handle Errors**: Catch and log errors gracefully
9. **Test with Client**: Use in-memory client for testing
10. **Secure Endpoints**: Use authentication for production deployments

## Common Patterns

See the `references/` directory for detailed patterns:
- `tools.md` - Advanced tool patterns and validation
- `resources.md` - Resource templates and file handling
- `composition.md` - Server composition and proxying

See the `examples/` directory for working code:
- `basic_server.py` - Simple MCP server setup
- `full_server.py` - Complete server with tools, resources, prompts
- `client_example.py` - Client usage patterns
