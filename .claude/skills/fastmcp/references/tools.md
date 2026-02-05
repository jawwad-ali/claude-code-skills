# FastMCP Tools Reference

## Overview

Tools are the primary way LLMs interact with your server. They are functions that can be called with arguments and return results.

## Basic Tool Definition

```python
from fastmcp import FastMCP

mcp = FastMCP("Server")

@mcp.tool
def greet(name: str) -> str:
    """Greet a user by name.

    Args:
        name: The name of the user to greet.

    Returns:
        A personalized greeting message.
    """
    return f"Hello, {name}!"
```

## Type Annotations

### Supported Types

```python
from typing import Optional, List, Dict, Literal
from pydantic import BaseModel

# Basic types
@mcp.tool
def basic_types(
    text: str,
    number: int,
    decimal: float,
    flag: bool,
) -> dict:
    """Tool with basic types."""
    return {"text": text, "number": number}

# Optional parameters
@mcp.tool
def with_optional(
    required: str,
    optional: str | None = None,
) -> str:
    """Tool with optional parameter."""
    return f"{required} - {optional or 'default'}"

# Collections
@mcp.tool
def with_collections(
    items: list[str],
    mapping: dict[str, int],
) -> dict:
    """Tool with collections."""
    return {"count": len(items), "mapping": mapping}

# Literal for enums
@mcp.tool
def with_enum(
    status: Literal["pending", "active", "done"],
) -> str:
    """Tool with enum-like parameter."""
    return f"Status is: {status}"
```

### Annotated with Field

```python
from typing import Annotated
from pydantic import Field

@mcp.tool
def annotated_params(
    query: Annotated[str, Field(description="Search query string")],
    limit: Annotated[int, Field(description="Max results", ge=1, le=100)] = 10,
    format: Annotated[
        Literal["json", "xml", "csv"],
        Field(description="Output format")
    ] = "json",
) -> dict:
    """Search with annotated parameters."""
    return {"query": query, "limit": limit, "format": format}
```

### Pydantic Models

```python
from pydantic import BaseModel, Field, EmailStr

class Address(BaseModel):
    street: str
    city: str
    country: str = "USA"
    zip_code: str = Field(pattern=r"^\d{5}$")

class Customer(BaseModel):
    name: str = Field(min_length=1, max_length=100)
    email: EmailStr
    address: Address
    tags: list[str] = []

@mcp.tool
def create_customer(customer: Customer) -> dict:
    """Create a new customer.

    Args:
        customer: Customer details including name, email, and address.
    """
    return {
        "id": "cust_123",
        "customer": customer.model_dump(),
        "created": True,
    }
```

## Async Tools

```python
import httpx

@mcp.tool
async def fetch_data(url: str) -> dict:
    """Fetch data from a URL.

    Args:
        url: The URL to fetch data from.
    """
    async with httpx.AsyncClient() as client:
        response = await client.get(url)
        response.raise_for_status()
        return response.json()

@mcp.tool
async def parallel_fetch(urls: list[str]) -> list[dict]:
    """Fetch multiple URLs in parallel.

    Args:
        urls: List of URLs to fetch.
    """
    import asyncio

    async with httpx.AsyncClient() as client:
        tasks = [client.get(url) for url in urls]
        responses = await asyncio.gather(*tasks)
        return [r.json() for r in responses]
```

## Context-Aware Tools

The `Context` parameter provides access to server capabilities:

```python
from fastmcp import FastMCP, Context

mcp = FastMCP("ContextDemo")

@mcp.tool
async def process_with_context(data: str, ctx: Context) -> dict:
    """Process data with full context access.

    Args:
        data: Data to process.
        ctx: MCP context (automatically injected).
    """
    # Logging at different levels
    await ctx.debug(f"Starting to process: {data[:50]}...")
    await ctx.info("Processing initiated")

    # Read another resource
    try:
        config = await ctx.read_resource("data://config")
        await ctx.info(f"Loaded config: {config}")
    except Exception as e:
        await ctx.warning(f"Could not load config: {e}")

    # Report progress
    total_steps = 10
    for i in range(total_steps):
        await ctx.report_progress(progress=i + 1, total=total_steps)
        # Do work...

    # Sample from the client's LLM
    summary = await ctx.sample(f"Summarize in 10 words: {data}")

    return {
        "processed": True,
        "summary": summary.text,
    }
```

### Context Methods

| Method | Description |
|--------|-------------|
| `ctx.debug(msg)` | Log debug message |
| `ctx.info(msg)` | Log info message |
| `ctx.warning(msg)` | Log warning message |
| `ctx.error(msg)` | Log error message |
| `ctx.report_progress(progress, total)` | Report progress |
| `ctx.read_resource(uri)` | Read a resource |
| `ctx.sample(prompt)` | Sample from client's LLM |

## Decorator Arguments

```python
@mcp.tool(
    name="search_products",        # Custom tool name
    description="Search catalog",  # Override docstring
    tags={"search", "catalog"},    # Categorization
    meta={"version": "2.0"},       # Custom metadata
)
def search_impl(query: str) -> list:
    """Internal description (ignored when description provided)."""
    return []
```

## Error Handling

```python
from fastmcp import FastMCP, Context
from fastmcp.exceptions import FastMCPError

@mcp.tool
async def safe_operation(data: str, ctx: Context) -> dict:
    """Perform operation with error handling."""
    try:
        result = process(data)
        return {"success": True, "result": result}
    except ValueError as e:
        await ctx.warning(f"Invalid input: {e}")
        return {"success": False, "error": str(e)}
    except Exception as e:
        await ctx.error(f"Operation failed: {e}")
        raise  # Re-raise for MCP to handle
```

## Tool Patterns

### Database Operations

```python
@mcp.tool
async def query_database(
    table: Literal["users", "orders", "products"],
    filters: dict[str, str] | None = None,
    limit: int = 100,
    ctx: Context = None,
) -> list[dict]:
    """Query database table with optional filters.

    Args:
        table: Table to query.
        filters: Optional key-value filters.
        limit: Maximum rows to return.
    """
    if ctx:
        await ctx.info(f"Querying {table} with {filters}")

    # Database query implementation
    return []
```

### API Integration

```python
@mcp.tool
async def call_external_api(
    endpoint: str,
    method: Literal["GET", "POST", "PUT", "DELETE"] = "GET",
    body: dict | None = None,
    headers: dict[str, str] | None = None,
) -> dict:
    """Call an external API endpoint.

    Args:
        endpoint: API endpoint path.
        method: HTTP method.
        body: Request body for POST/PUT.
        headers: Additional headers.
    """
    async with httpx.AsyncClient(base_url="https://api.example.com") as client:
        response = await client.request(
            method=method,
            url=endpoint,
            json=body,
            headers=headers,
        )
        return response.json()
```

### File Operations

```python
from pathlib import Path

@mcp.tool
def read_file(
    path: str,
    encoding: str = "utf-8",
    max_lines: int | None = None,
) -> str:
    """Read contents of a file.

    Args:
        path: Path to the file.
        encoding: File encoding.
        max_lines: Maximum lines to read.
    """
    file_path = Path(path)
    if not file_path.exists():
        raise FileNotFoundError(f"File not found: {path}")

    content = file_path.read_text(encoding=encoding)

    if max_lines:
        lines = content.split("\n")[:max_lines]
        content = "\n".join(lines)

    return content
```

### Batch Operations

```python
from pydantic import BaseModel

class BatchItem(BaseModel):
    id: str
    action: Literal["create", "update", "delete"]
    data: dict | None = None

class BatchResult(BaseModel):
    id: str
    success: bool
    error: str | None = None

@mcp.tool
async def batch_process(
    items: list[BatchItem],
    ctx: Context,
) -> list[BatchResult]:
    """Process multiple items in batch.

    Args:
        items: List of items to process.
    """
    results = []

    for i, item in enumerate(items):
        await ctx.report_progress(progress=i, total=len(items))

        try:
            # Process item
            await process_item(item)
            results.append(BatchResult(id=item.id, success=True))
        except Exception as e:
            results.append(BatchResult(id=item.id, success=False, error=str(e)))

    return results
```

## Best Practices

1. **Clear Docstrings**: Write comprehensive docstrings - they become tool descriptions
2. **Type Everything**: Use type hints for all parameters and return types
3. **Validate with Pydantic**: Use Pydantic models for complex inputs
4. **Use Annotated**: Add Field descriptions for better documentation
5. **Async for I/O**: Always use async for network/file operations
6. **Report Progress**: Long operations should report progress
7. **Log Operations**: Use context logging for debugging
8. **Handle Errors**: Catch and handle errors gracefully
9. **Keep Tools Focused**: Each tool should do one thing well
10. **Test Thoroughly**: Test tools with various inputs
