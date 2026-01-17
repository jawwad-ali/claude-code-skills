#!/bin/bash
# ===========================================
# Next.js App Router Skill Structure Validator
# ===========================================
# Validates that a Next.js project follows App Router conventions

set -e

# Colors for output
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
NC='\033[0m' # No Color

ERRORS=0
WARNINGS=0

# Get project root (default to current directory)
PROJECT_ROOT="${1:-.}"

echo "Validating Next.js App Router structure in: $PROJECT_ROOT"
echo "=================================================="

# Check if app directory exists
if [ ! -d "$PROJECT_ROOT/app" ]; then
    echo -e "${RED}ERROR: No 'app' directory found. This doesn't appear to be an App Router project.${NC}"
    exit 1
fi

echo -e "${GREEN}✓ Found app directory${NC}"

# Check for root layout
if [ ! -f "$PROJECT_ROOT/app/layout.tsx" ] && [ ! -f "$PROJECT_ROOT/app/layout.js" ]; then
    echo -e "${RED}ERROR: Missing root layout (app/layout.tsx or app/layout.js)${NC}"
    ((ERRORS++))
else
    echo -e "${GREEN}✓ Root layout exists${NC}"

    # Check if root layout has html and body tags
    if [ -f "$PROJECT_ROOT/app/layout.tsx" ]; then
        LAYOUT_FILE="$PROJECT_ROOT/app/layout.tsx"
    else
        LAYOUT_FILE="$PROJECT_ROOT/app/layout.js"
    fi

    if ! grep -q "<html" "$LAYOUT_FILE"; then
        echo -e "${RED}ERROR: Root layout missing <html> tag${NC}"
        ((ERRORS++))
    fi

    if ! grep -q "<body" "$LAYOUT_FILE"; then
        echo -e "${RED}ERROR: Root layout missing <body> tag${NC}"
        ((ERRORS++))
    fi
fi

# Check for root page
if [ ! -f "$PROJECT_ROOT/app/page.tsx" ] && [ ! -f "$PROJECT_ROOT/app/page.js" ]; then
    echo -e "${YELLOW}WARNING: No root page (app/page.tsx). The / route won't be accessible.${NC}"
    ((WARNINGS++))
else
    echo -e "${GREEN}✓ Root page exists${NC}"
fi

# Check for common issues in pages
echo ""
echo "Checking page files..."

find "$PROJECT_ROOT/app" -name "page.tsx" -o -name "page.js" | while read -r page_file; do
    # Check for default export
    if ! grep -q "export default" "$page_file"; then
        echo -e "${RED}ERROR: $page_file - Missing default export${NC}"
        ((ERRORS++))
    fi
done

# Check for 'use client' placement
echo ""
echo "Checking 'use client' directives..."

find "$PROJECT_ROOT/app" -name "*.tsx" -o -name "*.jsx" | while read -r file; do
    # Check if file has 'use client' and if it's at the top
    if grep -q "'use client'" "$file"; then
        first_line=$(head -n 1 "$file" | tr -d '[:space:]')
        if [[ "$first_line" != "'useclient'" && "$first_line" != "\"useclient\"" ]]; then
            # Check if it's within first 3 lines (allowing for comments)
            if ! head -n 3 "$file" | grep -q "'use client'\|\"use client\""; then
                echo -e "${YELLOW}WARNING: $file - 'use client' should be at the top of the file${NC}"
                ((WARNINGS++))
            fi
        fi
    fi
done

# Check error.tsx files are client components
echo ""
echo "Checking error boundaries..."

find "$PROJECT_ROOT/app" -name "error.tsx" -o -name "error.js" | while read -r error_file; do
    if ! grep -q "'use client'\|\"use client\"" "$error_file"; then
        echo -e "${RED}ERROR: $error_file - error.tsx must be a Client Component (add 'use client')${NC}"
        ((ERRORS++))
    else
        echo -e "${GREEN}✓ $error_file is correctly marked as Client Component${NC}"
    fi
done

# Check loading.tsx files exist where needed
echo ""
echo "Checking loading states..."

find "$PROJECT_ROOT/app" -type d | while read -r dir; do
    if [ -f "$dir/page.tsx" ] || [ -f "$dir/page.js" ]; then
        if [ ! -f "$dir/loading.tsx" ] && [ ! -f "$dir/loading.js" ]; then
            route="${dir#$PROJECT_ROOT/app}"
            route="${route:-/}"
            echo -e "${YELLOW}INFO: Route '$route' has no loading.tsx${NC}"
        fi
    fi
done

# Check route.ts files don't conflict with page.tsx
echo ""
echo "Checking for route conflicts..."

find "$PROJECT_ROOT/app" -type d | while read -r dir; do
    has_page=false
    has_route=false

    [ -f "$dir/page.tsx" ] || [ -f "$dir/page.js" ] && has_page=true
    [ -f "$dir/route.ts" ] || [ -f "$dir/route.js" ] && has_route=true

    if $has_page && $has_route; then
        echo -e "${RED}ERROR: $dir has both page and route files - they cannot coexist${NC}"
        ((ERRORS++))
    fi
done

# Check for proper API route structure
echo ""
echo "Checking API routes..."

if [ -d "$PROJECT_ROOT/app/api" ]; then
    find "$PROJECT_ROOT/app/api" -name "route.ts" -o -name "route.js" | while read -r route_file; do
        # Check for HTTP method exports
        if ! grep -qE "export (async )?(function|const) (GET|POST|PUT|PATCH|DELETE|HEAD|OPTIONS)" "$route_file"; then
            echo -e "${YELLOW}WARNING: $route_file - No HTTP method handlers found${NC}"
            ((WARNINGS++))
        fi
    done
    echo -e "${GREEN}✓ API routes directory exists${NC}"
fi

# Check imports from next/navigation vs next/router
echo ""
echo "Checking navigation imports..."

find "$PROJECT_ROOT/app" -name "*.tsx" -o -name "*.jsx" -o -name "*.ts" -o -name "*.js" | while read -r file; do
    if grep -q "from 'next/router'\|from \"next/router\"" "$file"; then
        echo -e "${RED}ERROR: $file - Using next/router instead of next/navigation (App Router uses next/navigation)${NC}"
        ((ERRORS++))
    fi
done

# Summary
echo ""
echo "=================================================="
echo "Validation Complete"
echo "=================================================="

if [ $ERRORS -gt 0 ]; then
    echo -e "${RED}Errors: $ERRORS${NC}"
fi

if [ $WARNINGS -gt 0 ]; then
    echo -e "${YELLOW}Warnings: $WARNINGS${NC}"
fi

if [ $ERRORS -eq 0 ] && [ $WARNINGS -eq 0 ]; then
    echo -e "${GREEN}All checks passed!${NC}"
fi

exit $ERRORS
