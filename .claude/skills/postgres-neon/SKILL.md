---
name: postgres-neon
description: Set up PostgreSQL with Neon database and Prisma ORM. Use when initializing a database, connecting to Neon, configuring Prisma, running migrations, or troubleshooting database connections.
disable-model-invocation: false
argument-hint: [project-name]
allowed-tools: Read, Grep, Bash(npm *), Bash(npx prisma *), Bash(npx tsx *)
paths: "*.prisma,**/migrations/**,.env*,prisma.config.*"
---

# PostgreSQL + Neon + Prisma ORM Setup

Set up a PostgreSQL database using Neon with Prisma ORM for **$ARGUMENTS**.

## Step 1: Install Dependencies

```bash
npm install @prisma/client @prisma/adapter-pg pg dotenv
npm install prisma tsx --save-dev
npx prisma init
```

Uses `@prisma/adapter-pg` with the `pg` driver — the proven approach for Neon + Prisma on serverless.

## Step 2: Configure Prisma Schema

Update `prisma/schema.prisma`:

```prisma
generator client {
  provider = "prisma-client-js"
  output   = "../src/app/generated/prisma"
}

datasource db {
  provider = "postgresql"
}
```

- **Prisma 7+**: Do NOT put `url` in the schema — it goes in `prisma.config.ts` only. Prisma 7 will error if you include `url` here.
- Custom `output` path: use `../src/app/generated/prisma` if your project uses a `src/` directory, or `../app/generated/prisma` without `src/`.
- Import from: `@/app/generated/prisma/client`
- Add `**/generated/**` to `.eslintrc.json` `ignorePatterns` to suppress lint errors on generated files.

## Step 3: Environment Variables

Create `.env` with both connection strings from Neon Console → Connect:

```env
# Pooled connection (pgbouncer) — used by the app at runtime
DATABASE_URL=postgresql://[user]:[password]@[endpoint]-pooler.[region].aws.neon.tech/[dbname]?sslmode=require&connect_timeout=30

# Unpooled connection — used by Prisma CLI for migrations (no pgbouncer)
DATABASE_URL_UNPOOLED=postgresql://[user]:[password]@[endpoint].[region].aws.neon.tech/[dbname]?sslmode=require&connect_timeout=30
```

The pooled URL has `-pooler` in the hostname. The unpooled URL does not.

**CRITICAL: Always add `connect_timeout=30`** to both URLs. Prisma's CLI uses an internal Rust engine (not the Node.js `pg` driver) which has a short default timeout. On some networks (especially Windows + SSL to Neon), the SSL handshake takes longer than the default, causing `P1001: Can't reach database server` even when the database is reachable. The `connect_timeout=30` parameter fixes this.

**Put DB URLs in `.env` (not just `.env.local`)**. Prisma CLI's `dotenv/config` loads `.env` by default. Next.js loads `.env.local` at runtime. Keep DB URLs in both, or use `.env` for DB and `.env.local` for app secrets (Clerk keys, etc.).

Also create `.env.example` with placeholder values (no real credentials).

Add `.env`, `.env.local` to `.gitignore` if not already there.

## Step 4: Prisma Client Singleton (Serverless-Safe)

Create `lib/db/prisma.ts` (or `src/lib/db/prisma.ts` if using `src/`):

```typescript
import { PrismaClient } from "@/app/generated/prisma/client";
import { PrismaPg } from "@prisma/adapter-pg";

const globalForPrisma = globalThis as unknown as { prisma: PrismaClient };

function createPrismaClient() {
  const adapter = new PrismaPg({
    connectionString: process.env.DATABASE_URL!,
  });
  return new PrismaClient({ adapter });
}

export const prisma = globalForPrisma.prisma || createPrismaClient();

if (process.env.NODE_ENV !== "production") globalForPrisma.prisma = prisma;
```

This pattern:
- Prevents connection pool exhaustion in serverless (Vercel)
- Reuses the client during Next.js hot reload in development
- Creates a fresh instance per deployment in production

## Step 5: Migration Config (`prisma.config.ts`)

Create `prisma.config.ts` in the project root:

```typescript
import "dotenv/config";
import { defineConfig } from "prisma/config";

export default defineConfig({
  schema: "prisma/schema.prisma",
  migrations: {
    path: "prisma/migrations",
  },
  datasource: {
    url: process.env["DATABASE_URL_UNPOOLED"] || process.env["DATABASE_URL"],
  },
});
```

This is CRITICAL — pgbouncer (pooler) does NOT support the transaction control needed for schema migrations. The `prisma.config.ts` tells Prisma CLI to use the unpooled connection for all migration operations.

## Step 6: Package.json Scripts

Add these scripts to `package.json`:

```json
{
  "scripts": {
    "postinstall": "prisma generate",
    "build": "prisma generate && next build",
    "db:push": "prisma db push",
    "db:studio": "prisma studio",
    "db:generate": "prisma generate",
    "db:migrate": "prisma migrate dev",
    "db:seed": "npx tsx prisma/seed.ts"
  }
}
```

- `postinstall` — ensures Prisma Client is generated on `npm install` (required for Vercel)
- `build` — regenerates client before every Next.js build
- `db:push` — push schema changes without migration files (fast prototyping)
- `db:seed` — run seed script via `tsx`

## Step 7: Seed Script Template

Create `prisma/seed.ts`:

```typescript
import { PrismaClient } from "../app/generated/prisma/client";
import { PrismaPg } from "@prisma/adapter-pg";
import "dotenv/config";

const adapter = new PrismaPg({
  connectionString: process.env.DATABASE_URL!,
});
const prisma = new PrismaClient({ adapter });

async function main() {
  // Use upsert for idempotent seeding (safe to run multiple times)
  // await prisma.user.upsert({ ... })
  console.log("Seeding complete.");
}

main()
  .catch((e) => {
    console.error(e);
    process.exit(1);
  })
  .finally(async () => {
    await prisma.$disconnect();
  });
```

Note: The seed script must create its own PrismaClient (not import the singleton) because it runs outside of Next.js.

## Step 8: Migration Workflow

```bash
# Push schema directly (prototyping — no migration files)
npx prisma db push

# Create a migration file and apply it
npx prisma migrate dev --name init

# Generate the Prisma Client after schema changes
npx prisma generate

# Deploy migrations in CI/CD
npx prisma migrate deploy

# Open Prisma Studio to browse data
npx prisma studio

# Run seed script
npx tsx prisma/seed.ts
```

## Step 9: Verify Connection

Test with a simple query in a server action or API route:

```typescript
import { prisma } from "@/lib/db/prisma";

const result = await prisma.$queryRaw`SELECT 1 as connected`;
console.log("Database connected:", result);
```

## Usage in Next.js App Router

```typescript
"use server";

import { prisma } from "@/lib/db/prisma";

export async function getUsers() {
  return prisma.user.findMany();
}
```

## Troubleshooting

- **P1001 "Can't reach database server"** (but DB is online): Add `connect_timeout=30` to your connection string. Prisma CLI uses an internal Rust engine with a short default timeout — SSL handshakes to Neon can exceed it, especially on Windows. The Node.js `pg` driver connects fine, but Prisma's engine fails silently with a misleading "can't reach" error. This is the #1 gotcha with Neon + Prisma.
- **P1012 "url is no longer supported in schema"**: Prisma 7+ removed `url` from `datasource` in schema. Move it to `prisma.config.ts` instead.
- **P1017 connection pool exhausted**: Ensure you're using the singleton pattern in Step 4
- **Prepared statement error on migrations**: Ensure `prisma.config.ts` exists and points to `DATABASE_URL_UNPOOLED` (must bypass pgbouncer)
- **Migration hangs or times out**: You're hitting the pooler — check that `prisma.config.ts` datasource uses the unpooled URL
- **Cannot find module '@/app/generated/prisma/client'**: Run `npx prisma generate` first — the output directory must exist
- **ESLint errors in generated files**: Add `"ignorePatterns": ["**/generated/**"]` to `.eslintrc.json`
- **Prisma CLI ignores .env.local**: Prisma's `dotenv/config` loads `.env` by default, not `.env.local`. Put DB URLs in `.env` or explicitly configure `config({ path: ".env.local" })` in `prisma.config.ts`.
- **SSL error**: Add `?sslmode=require` to both connection strings
- **Token expired on Neon**: Regenerate credentials in Neon Console → Connection Details
- **Seed script import error**: Seed script must use relative import (`../app/generated/prisma/client`), not the `@/` alias
