# Data Fetching Patterns

## Server-Side Data Fetching

### Basic Fetch in Server Components

```tsx
// app/posts/page.tsx
async function getPosts() {
  const res = await fetch('https://api.example.com/posts')

  if (!res.ok) {
    throw new Error('Failed to fetch posts')
  }

  return res.json()
}

export default async function PostsPage() {
  const posts = await getPosts()

  return (
    <ul>
      {posts.map((post: { id: string; title: string }) => (
        <li key={post.id}>{post.title}</li>
      ))}
    </ul>
  )
}
```

### Caching Strategies

```tsx
// Default: Cache indefinitely (static)
const res = await fetch('https://api.example.com/data')

// No caching (dynamic)
const res = await fetch('https://api.example.com/data', {
  cache: 'no-store',
})

// Time-based revalidation
const res = await fetch('https://api.example.com/data', {
  next: { revalidate: 3600 }, // Revalidate every hour
})

// Tag-based revalidation
const res = await fetch('https://api.example.com/data', {
  next: { tags: ['posts'] },
})
```

### Direct Database Queries

```tsx
import { db } from '@/lib/db'
import { unstable_cache } from 'next/cache'

// Cached database query
const getCachedUser = unstable_cache(
  async (id: string) => {
    return db.user.findUnique({ where: { id } })
  },
  ['user'],
  { revalidate: 3600, tags: ['user'] }
)

export default async function UserPage({
  params,
}: {
  params: Promise<{ id: string }>
}) {
  const { id } = await params
  const user = await getCachedUser(id)

  return <div>{user?.name}</div>
}
```

## Server Actions

### Basic Server Action

```tsx
// app/actions.ts
'use server'

import { revalidatePath } from 'next/cache'
import { redirect } from 'next/navigation'
import { db } from '@/lib/db'

export async function createPost(formData: FormData) {
  const title = formData.get('title') as string
  const content = formData.get('content') as string

  await db.post.create({
    data: { title, content },
  })

  revalidatePath('/posts')
  redirect('/posts')
}
```

### Using in Form

```tsx
// app/posts/new/page.tsx
import { createPost } from '@/app/actions'

export default function NewPostPage() {
  return (
    <form action={createPost}>
      <input name="title" placeholder="Title" required />
      <textarea name="content" placeholder="Content" required />
      <button type="submit">Create Post</button>
    </form>
  )
}
```

### Server Action with Validation

```tsx
// app/actions.ts
'use server'

import { z } from 'zod'
import { revalidatePath } from 'next/cache'

const PostSchema = z.object({
  title: z.string().min(1).max(100),
  content: z.string().min(1).max(10000),
})

export type ActionState = {
  success?: boolean
  errors?: { title?: string[]; content?: string[] }
  message?: string
}

export async function createPost(
  prevState: ActionState,
  formData: FormData
): Promise<ActionState> {
  const validatedFields = PostSchema.safeParse({
    title: formData.get('title'),
    content: formData.get('content'),
  })

  if (!validatedFields.success) {
    return {
      errors: validatedFields.error.flatten().fieldErrors,
      message: 'Validation failed',
    }
  }

  try {
    await db.post.create({ data: validatedFields.data })
    revalidatePath('/posts')
    return { success: true, message: 'Post created!' }
  } catch (error) {
    return { message: 'Database error' }
  }
}
```

### Client Component with useActionState

```tsx
'use client'

import { useActionState } from 'react'
import { createPost, type ActionState } from '@/app/actions'

const initialState: ActionState = {}

export function PostForm() {
  const [state, formAction, pending] = useActionState(createPost, initialState)

  return (
    <form action={formAction}>
      <div>
        <input name="title" placeholder="Title" />
        {state.errors?.title && (
          <p className="text-red-500">{state.errors.title[0]}</p>
        )}
      </div>

      <div>
        <textarea name="content" placeholder="Content" />
        {state.errors?.content && (
          <p className="text-red-500">{state.errors.content[0]}</p>
        )}
      </div>

      <button type="submit" disabled={pending}>
        {pending ? 'Creating...' : 'Create Post'}
      </button>

      {state.message && <p>{state.message}</p>}
    </form>
  )
}
```

### Optimistic Updates

```tsx
'use client'

import { useOptimistic } from 'react'
import { likePost } from '@/app/actions'

export function LikeButton({
  postId,
  initialLikes,
}: {
  postId: string
  initialLikes: number
}) {
  const [optimisticLikes, addOptimisticLike] = useOptimistic(
    initialLikes,
    (state, _) => state + 1
  )

  async function handleLike() {
    addOptimisticLike(null)
    await likePost(postId)
  }

  return (
    <form action={handleLike}>
      <button type="submit">❤️ {optimisticLikes}</button>
    </form>
  )
}
```

## Route Handlers (API Routes)

### Basic CRUD Operations

```tsx
// app/api/posts/route.ts
import { NextRequest, NextResponse } from 'next/server'
import { db } from '@/lib/db'

export async function GET(request: NextRequest) {
  const { searchParams } = request.nextUrl
  const page = parseInt(searchParams.get('page') ?? '1')
  const limit = parseInt(searchParams.get('limit') ?? '10')

  const posts = await db.post.findMany({
    skip: (page - 1) * limit,
    take: limit,
    orderBy: { createdAt: 'desc' },
  })

  return NextResponse.json(posts)
}

export async function POST(request: NextRequest) {
  try {
    const body = await request.json()

    const post = await db.post.create({
      data: body,
    })

    return NextResponse.json(post, { status: 201 })
  } catch (error) {
    return NextResponse.json(
      { error: 'Failed to create post' },
      { status: 500 }
    )
  }
}
```

### Dynamic Route Handler

```tsx
// app/api/posts/[id]/route.ts
import { NextRequest, NextResponse } from 'next/server'
import { db } from '@/lib/db'

export async function GET(
  request: NextRequest,
  { params }: { params: Promise<{ id: string }> }
) {
  const { id } = await params

  const post = await db.post.findUnique({ where: { id } })

  if (!post) {
    return NextResponse.json({ error: 'Post not found' }, { status: 404 })
  }

  return NextResponse.json(post)
}

export async function PUT(
  request: NextRequest,
  { params }: { params: Promise<{ id: string }> }
) {
  const { id } = await params
  const body = await request.json()

  const post = await db.post.update({
    where: { id },
    data: body,
  })

  return NextResponse.json(post)
}

export async function DELETE(
  request: NextRequest,
  { params }: { params: Promise<{ id: string }> }
) {
  const { id } = await params

  await db.post.delete({ where: { id } })

  return new NextResponse(null, { status: 204 })
}
```

### Streaming Response

```tsx
// app/api/stream/route.ts
export async function GET() {
  const encoder = new TextEncoder()

  const stream = new ReadableStream({
    async start(controller) {
      for (let i = 0; i < 10; i++) {
        controller.enqueue(encoder.encode(`data: ${i}\n\n`))
        await new Promise(resolve => setTimeout(resolve, 1000))
      }
      controller.close()
    },
  })

  return new Response(stream, {
    headers: {
      'Content-Type': 'text/event-stream',
      'Cache-Control': 'no-cache',
      Connection: 'keep-alive',
    },
  })
}
```

## Revalidation

### On-Demand Revalidation

```tsx
// app/api/revalidate/route.ts
import { revalidatePath, revalidateTag } from 'next/cache'
import { NextRequest, NextResponse } from 'next/server'

export async function POST(request: NextRequest) {
  const { path, tag, secret } = await request.json()

  // Validate secret
  if (secret !== process.env.REVALIDATION_SECRET) {
    return NextResponse.json({ error: 'Invalid secret' }, { status: 401 })
  }

  if (path) {
    revalidatePath(path)
    return NextResponse.json({ revalidated: true, path })
  }

  if (tag) {
    revalidateTag(tag)
    return NextResponse.json({ revalidated: true, tag })
  }

  return NextResponse.json({ error: 'Path or tag required' }, { status: 400 })
}
```

### Revalidation in Server Actions

```tsx
'use server'

import { revalidatePath, revalidateTag } from 'next/cache'

export async function updatePost(id: string, data: PostData) {
  await db.post.update({ where: { id }, data })

  // Revalidate specific path
  revalidatePath(`/posts/${id}`)

  // Or revalidate all paths with a tag
  revalidateTag('posts')
}
```

## Error Handling

### fetch Error Handling

```tsx
async function getData() {
  const res = await fetch('https://api.example.com/data')

  if (!res.ok) {
    // This will be caught by error.tsx
    throw new Error(`Failed to fetch: ${res.status}`)
  }

  return res.json()
}
```

### Try-Catch Pattern

```tsx
export default async function Page() {
  try {
    const data = await getData()
    return <div>{data.content}</div>
  } catch (error) {
    // Log error for debugging
    console.error('Error fetching data:', error)

    // Return fallback UI
    return <div>Failed to load data</div>
  }
}
```
