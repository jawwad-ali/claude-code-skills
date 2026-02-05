"""
Basic FastMCP Server Example

Demonstrates the fundamental setup of a FastMCP server with
tools, resources, and prompts.
"""

from fastmcp import FastMCP

# Create the server
mcp = FastMCP("Basic Demo Server")


# ============================================================
# Tools
# ============================================================

@mcp.tool
def add(a: int, b: int) -> int:
    """Add two numbers together.

    Args:
        a: First number.
        b: Second number.

    Returns:
        Sum of the two numbers.
    """
    return a + b


@mcp.tool
def multiply(a: float, b: float) -> float:
    """Multiply two numbers.

    Args:
        a: First number.
        b: Second number.

    Returns:
        Product of the two numbers.
    """
    return a * b


@mcp.tool
def greet(name: str, formal: bool = False) -> str:
    """Generate a greeting message.

    Args:
        name: Name of the person to greet.
        formal: Whether to use formal greeting.

    Returns:
        A personalized greeting.
    """
    if formal:
        return f"Good day, {name}. How may I assist you?"
    return f"Hello, {name}!"


@mcp.tool
def get_length(text: str) -> dict:
    """Get information about a text string.

    Args:
        text: The text to analyze.

    Returns:
        Dictionary with character count, word count, and line count.
    """
    return {
        "characters": len(text),
        "words": len(text.split()),
        "lines": len(text.splitlines()) or 1,
    }


# ============================================================
# Resources
# ============================================================

@mcp.resource("resource://greeting")
def get_greeting() -> str:
    """Provides a welcome message."""
    return "Welcome to the Basic Demo Server!"


@mcp.resource("data://config")
def get_config() -> dict:
    """Provides server configuration."""
    return {
        "name": "Basic Demo Server",
        "version": "1.0.0",
        "features": ["tools", "resources", "prompts"],
        "max_requests": 100,
    }


@mcp.resource("data://stats")
def get_stats() -> dict:
    """Provides usage statistics."""
    from datetime import datetime
    return {
        "uptime": "running",
        "timestamp": datetime.now().isoformat(),
        "requests_served": 42,
    }


# ============================================================
# Prompts
# ============================================================

@mcp.prompt
def explain_topic(topic: str) -> str:
    """Generate a prompt asking to explain a topic.

    Args:
        topic: The topic to explain.
    """
    return f"Please explain '{topic}' in simple terms that a beginner can understand."


@mcp.prompt
def summarize_text(text: str, max_sentences: int = 3) -> str:
    """Generate a prompt for summarizing text.

    Args:
        text: The text to summarize.
        max_sentences: Maximum sentences in summary.
    """
    return f"Summarize the following text in {max_sentences} sentences or less:\n\n{text}"


# ============================================================
# Run Server
# ============================================================

if __name__ == "__main__":
    print("Starting Basic Demo Server...")
    print("Tools: add, multiply, greet, get_length")
    print("Resources: greeting, config, stats")
    print("Prompts: explain_topic, summarize_text")
    print()

    # Run with STDIO (default)
    mcp.run()

    # Alternative: Run with HTTP
    # mcp.run(transport="http", host="0.0.0.0", port=8000)
