// ===========================================
// EXAMPLE: Route Handlers (API Routes)
// ===========================================

import { NextRequest, NextResponse } from 'next/server'
import { z } from 'zod'

// ===========================================
// Example 1: Basic GET Handler
// File: app/api/posts/route.ts
// ===========================================

export async function GET(request: NextRequest) {
  const { searchParams } = request.nextUrl

  // Parse query parameters
  const page = parseInt(searchParams.get('page') ?? '1')
  const limit = parseInt(searchParams.get('limit') ?? '10')
  const search = searchParams.get('search')

  const posts = await db.post.findMany({
    where: search
      ? {
          OR: [
            { title: { contains: search } },
            { content: { contains: search } },
          ],
        }
      : undefined,
    skip: (page - 1) * limit,
    take: limit,
    orderBy: { createdAt: 'desc' },
  })

  const total = await db.post.count()

  return NextResponse.json({
    data: posts,
    pagination: {
      page,
      limit,
      total,
      totalPages: Math.ceil(total / limit),
    },
  })
}

// ===========================================
// Example 2: POST Handler with Validation
// File: app/api/posts/route.ts
// ===========================================

const CreatePostSchema = z.object({
  title: z.string().min(1).max(100),
  content: z.string().min(1),
  published: z.boolean().optional().default(false),
})

export async function POST(request: NextRequest) {
  try {
    const body = await request.json()

    // Validate request body
    const validatedData = CreatePostSchema.parse(body)

    const post = await db.post.create({
      data: validatedData,
    })

    return NextResponse.json(post, { status: 201 })
  } catch (error) {
    if (error instanceof z.ZodError) {
      return NextResponse.json(
        { error: 'Validation failed', details: error.errors },
        { status: 400 }
      )
    }

    console.error('Error creating post:', error)
    return NextResponse.json(
      { error: 'Internal server error' },
      { status: 500 }
    )
  }
}

// ===========================================
// Example 3: Dynamic Route Handler
// File: app/api/posts/[id]/route.ts
// ===========================================

export async function GET_SINGLE(
  request: NextRequest,
  { params }: { params: Promise<{ id: string }> }
) {
  const { id } = await params

  const post = await db.post.findUnique({
    where: { id },
    include: {
      author: {
        select: { id: true, name: true, image: true },
      },
      comments: {
        include: { author: { select: { name: true } } },
        orderBy: { createdAt: 'desc' },
      },
    },
  })

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

  try {
    const post = await db.post.update({
      where: { id },
      data: body,
    })

    return NextResponse.json(post)
  } catch (error) {
    return NextResponse.json({ error: 'Post not found' }, { status: 404 })
  }
}

export async function DELETE(
  request: NextRequest,
  { params }: { params: Promise<{ id: string }> }
) {
  const { id } = await params

  try {
    await db.post.delete({ where: { id } })
    return new NextResponse(null, { status: 204 })
  } catch (error) {
    return NextResponse.json({ error: 'Post not found' }, { status: 404 })
  }
}

// ===========================================
// Example 4: Route Handler with Authentication
// File: app/api/protected/route.ts
// ===========================================

import { auth } from '@/lib/auth'

export async function GET_PROTECTED(request: NextRequest) {
  const session = await auth()

  if (!session) {
    return NextResponse.json({ error: 'Unauthorized' }, { status: 401 })
  }

  // Access user info
  const userId = session.user.id

  const userData = await db.user.findUnique({
    where: { id: userId },
    select: {
      id: true,
      email: true,
      name: true,
      posts: true,
    },
  })

  return NextResponse.json(userData)
}

// ===========================================
// Example 5: File Upload Handler
// File: app/api/upload/route.ts
// ===========================================

export async function POST_UPLOAD(request: NextRequest) {
  const formData = await request.formData()
  const file = formData.get('file') as File | null

  if (!file) {
    return NextResponse.json({ error: 'No file provided' }, { status: 400 })
  }

  // Validate file type
  const allowedTypes = ['image/jpeg', 'image/png', 'image/webp']
  if (!allowedTypes.includes(file.type)) {
    return NextResponse.json(
      { error: 'Invalid file type. Only JPEG, PNG, and WebP are allowed.' },
      { status: 400 }
    )
  }

  // Validate file size (5MB max)
  const maxSize = 5 * 1024 * 1024
  if (file.size > maxSize) {
    return NextResponse.json(
      { error: 'File too large. Maximum size is 5MB.' },
      { status: 400 }
    )
  }

  // Process file (example: save to storage)
  const bytes = await file.arrayBuffer()
  const buffer = Buffer.from(bytes)

  // Save to your storage solution
  // const url = await uploadToStorage(buffer, file.name)

  return NextResponse.json({
    message: 'File uploaded successfully',
    filename: file.name,
    size: file.size,
  })
}

// ===========================================
// Example 6: Streaming Response
// File: app/api/stream/route.ts
// ===========================================

export async function GET_STREAM() {
  const encoder = new TextEncoder()

  const stream = new ReadableStream({
    async start(controller) {
      // Simulate streaming data
      for (let i = 1; i <= 5; i++) {
        const data = JSON.stringify({ count: i, timestamp: Date.now() })
        controller.enqueue(encoder.encode(`data: ${data}\n\n`))
        await new Promise((resolve) => setTimeout(resolve, 1000))
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

// ===========================================
// Example 7: Webhook Handler
// File: app/api/webhooks/stripe/route.ts
// ===========================================

import Stripe from 'stripe'

const stripe = new Stripe(process.env.STRIPE_SECRET_KEY!)

export async function POST_WEBHOOK(request: NextRequest) {
  const body = await request.text()
  const signature = request.headers.get('stripe-signature')!

  let event: Stripe.Event

  try {
    event = stripe.webhooks.constructEvent(
      body,
      signature,
      process.env.STRIPE_WEBHOOK_SECRET!
    )
  } catch (error) {
    console.error('Webhook signature verification failed')
    return NextResponse.json({ error: 'Invalid signature' }, { status: 400 })
  }

  // Handle the event
  switch (event.type) {
    case 'checkout.session.completed':
      const session = event.data.object as Stripe.Checkout.Session
      // Handle successful checkout
      await handleCheckoutComplete(session)
      break

    case 'customer.subscription.updated':
      const subscription = event.data.object as Stripe.Subscription
      // Handle subscription update
      await handleSubscriptionUpdate(subscription)
      break

    default:
      console.log(`Unhandled event type: ${event.type}`)
  }

  return NextResponse.json({ received: true })
}

// ===========================================
// Example 8: CORS Headers
// File: app/api/public/route.ts
// ===========================================

export async function GET_WITH_CORS(request: NextRequest) {
  const data = { message: 'Hello from API' }

  return NextResponse.json(data, {
    headers: {
      'Access-Control-Allow-Origin': '*',
      'Access-Control-Allow-Methods': 'GET, POST, OPTIONS',
      'Access-Control-Allow-Headers': 'Content-Type, Authorization',
    },
  })
}

export async function OPTIONS() {
  return new NextResponse(null, {
    status: 204,
    headers: {
      'Access-Control-Allow-Origin': '*',
      'Access-Control-Allow-Methods': 'GET, POST, OPTIONS',
      'Access-Control-Allow-Headers': 'Content-Type, Authorization',
    },
  })
}

// ===========================================
// Example 9: Rate Limiting (Simple)
// File: app/api/rate-limited/route.ts
// ===========================================

const rateLimit = new Map<string, { count: number; timestamp: number }>()
const WINDOW_MS = 60 * 1000 // 1 minute
const MAX_REQUESTS = 10

export async function GET_RATE_LIMITED(request: NextRequest) {
  const ip = request.headers.get('x-forwarded-for') ?? 'unknown'
  const now = Date.now()

  const record = rateLimit.get(ip)

  if (record) {
    if (now - record.timestamp > WINDOW_MS) {
      // Reset window
      rateLimit.set(ip, { count: 1, timestamp: now })
    } else if (record.count >= MAX_REQUESTS) {
      return NextResponse.json(
        { error: 'Too many requests' },
        {
          status: 429,
          headers: {
            'Retry-After': String(Math.ceil((WINDOW_MS - (now - record.timestamp)) / 1000)),
          },
        }
      )
    } else {
      record.count++
    }
  } else {
    rateLimit.set(ip, { count: 1, timestamp: now })
  }

  return NextResponse.json({ message: 'Success' })
}

// Mock types for examples
declare const db: {
  post: {
    findMany: (args?: any) => Promise<any[]>
    findUnique: (args: any) => Promise<any>
    create: (args: any) => Promise<any>
    update: (args: any) => Promise<any>
    delete: (args: any) => Promise<void>
    count: () => Promise<number>
  }
  user: { findUnique: (args: any) => Promise<any> }
}

declare function handleCheckoutComplete(session: any): Promise<void>
declare function handleSubscriptionUpdate(subscription: any): Promise<void>
